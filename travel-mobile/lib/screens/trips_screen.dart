import 'package:flutter/material.dart';

import '../models/chat_message.dart';
import '../models/plan_view.dart';
import '../services/chat_store.dart';
import '../theme/tokens.dart';
import 'plan_detail_screen.dart';

/// 我的行程（设计稿屏 01）：列出本地已生成草案的会话。
///
/// 与 Web 端一致，行程只存在本地会话里——点进去看的是那一轮的结构化草案。
/// 真机/桌面后端暂不持久化行程，所以用「带草案的会话」来代表一条行程。
class TripsScreen extends StatefulWidget {
  const TripsScreen({super.key, this.onGoPlanning});

  /// 空态「去规划」按钮：切到底部导航的「规划」页。
  final VoidCallback? onGoPlanning;

  @override
  State<TripsScreen> createState() => _TripsScreenState();
}

class _TripsScreenState extends State<TripsScreen> {
  List<_TripEntry> _trips = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 从会话里挑出「含结构化草案」的，按更新时间倒序。
  Future<void> _load() async {
    final sessions = await ChatStore.instance.load();
    final entries = <_TripEntry>[];
    for (final s in sessions) {
      PlanView? plan;
      for (var i = s.messages.length - 1; i >= 0; i--) {
        final m = s.messages[i];
        if (m.role == MsgRole.assistant && m.planForTrips != null) {
          plan = m.planForTrips;
          break;
        }
      }
      if (plan == null) continue;
      entries.add(_TripEntry(session: s, plan: plan));
    }
    entries.sort((a, b) => b.session.updatedAt.compareTo(a.session.updatedAt));
    if (!mounted) return;
    setState(() {
      _trips = entries;
      _loading = false;
    });
  }

  String _title(_TripEntry e) {
    final city = e.plan.candidates.isNotEmpty ? e.plan.candidates.first.city : '';
    final days = e.plan.filledDays;
    if (city.isNotEmpty) return days > 0 ? '$city${days}日行程' : '$city行程';
    return e.session.title.isNotEmpty ? e.session.title : '未命名行程';
  }

  String _subtitle(_TripEntry e) {
    final first = e.plan.dailySchedules
        .where((d) => d.items.isNotEmpty)
        .expand((d) => d.items)
        .map((i) => i.name)
        .take(3)
        .join('、');
    if (first.isEmpty) return '本轮没有可排的候选景点';
    return first;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('我的行程', style: TextStyle(fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: T.sp4),
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: T.sp2, vertical: 2),
                decoration: BoxDecoration(
                  color: T.surface2,
                  borderRadius: BorderRadius.circular(T.rPill),
                  border: Border.all(color: T.line),
                ),
                child: Text('${_trips.length} 个行程', style: T.tXs),
              ),
            ),
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
          : _trips.isEmpty
              ? _EmptyState(onGoPlanning: widget.onGoPlanning)
              : ListView.separated(
                  padding: const EdgeInsets.all(T.sp4),
                  itemCount: _trips.length,
                  separatorBuilder: (_, __) => const SizedBox(height: T.sp3),
                  itemBuilder: (_, i) {
                    final e = _trips[i];
                    return _TripCard(
                      title: _title(e),
                      subtitle: _subtitle(e),
                      days: e.plan.filledDays,
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => PlanDetailScreen(
                            title: _title(e),
                            plan: e.plan,
                          ),
                        ),
                      ),
                    );
                  },
                ),
    );
  }
}

class _TripEntry {
  const _TripEntry({required this.session, required this.plan});
  final ChatSession session;
  final PlanView plan;
}

class _TripCard extends StatelessWidget {
  const _TripCard({
    required this.title,
    required this.subtitle,
    required this.days,
    required this.onTap,
  });
  final String title;
  final String subtitle;
  final int days;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(T.rCard),
      child: Container(
        padding: const EdgeInsets.all(T.sp4),
        decoration: T.cardDeco(),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: T.primarySoft,
                borderRadius: BorderRadius.circular(T.rCtl),
                border: Border.all(color: T.primaryLine),
              ),
              child: Icon(Icons.luggage_outlined, size: 24, color: T.primary),
            ),
            const SizedBox(width: T.sp3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: T.tSmInk.copyWith(fontWeight: T.wStrong)),
                  const SizedBox(height: 2),
                  Text(subtitle, style: T.tXs, maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
            if (days > 0)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: T.sp2, vertical: 2),
                decoration: BoxDecoration(
                  color: T.okSoft,
                  borderRadius: BorderRadius.circular(T.rPill),
                  border: Border.all(color: T.okLine),
                ),
                child: Text('$days 天', style: const TextStyle(fontSize: T.fsXs, color: T.ok, fontWeight: T.wStrong)),
              ),
            const SizedBox(width: T.sp2),
            const Icon(Icons.chevron_right, size: 18, color: T.text3),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({this.onGoPlanning});
  final VoidCallback? onGoPlanning;
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(T.sp8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: T.primarySoft,
                borderRadius: BorderRadius.circular(T.rCard),
                border: Border.all(color: T.primaryLine),
              ),
              child: Icon(Icons.map_outlined, size: 32, color: T.primary),
            ),
            const SizedBox(height: T.sp4),
            const Text('还没有行程', style: T.tLgStrong),
            const SizedBox(height: T.sp2),
            const Text(
              '在「规划」页说出目的地和天数，\n生成的结构化草案会自动出现在这里。',
              textAlign: TextAlign.center,
              style: T.tSm,
            ),
            const SizedBox(height: T.sp6),
            FilledButton.icon(
              onPressed: onGoPlanning,
              icon: const Icon(Icons.add_road_outlined, size: 18),
              label: const Text('去规划'),
              style: FilledButton.styleFrom(
                backgroundColor: T.primary,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(T.rCtl),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
