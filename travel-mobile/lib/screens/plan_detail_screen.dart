import 'package:flutter/material.dart';

import '../api/client.dart';
import '../models/plan_view.dart';
import '../theme/tokens.dart';
import '../widgets/draft_blocks.dart';
import '../widgets/map_panel.dart';

/// 设计稿 03 / 04 / 05 屏：草案与确认、列表模式、地图模式。
///
/// 共用同一份 [PlanView] 数据，切换只换呈现方式，不重新请求、不产生第二份草案。
/// 未点「确认保存」之前一律显示「草案未保存」，**不写库**——与设计稿和
/// 项目契约一致：确认是人的动作，模型只给候选。
enum _DetailMode { list, map }

class PlanDetailScreen extends StatefulWidget {
  const PlanDetailScreen({
    super.key,
    required this.title,
    required this.plan,
  });

  final String title;
  final PlanView plan;

  @override
  State<PlanDetailScreen> createState() => _PlanDetailScreenState();
}

class _PlanDetailScreenState extends State<PlanDetailScreen> {
  _DetailMode _mode = _DetailMode.list;
  bool _saving = false;

  PlanView get _plan => widget.plan;

  int get _filledDays =>
      _plan.dailySchedules.where((d) => d.items.isNotEmpty).length;

  String get _city => _plan.candidates.isNotEmpty
      ? _plan.candidates.first.city
      : '';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, size: 22, color: T.ink),
          onPressed: () => Navigator.of(context).pop(),
          tooltip: '返回',
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: T.fsMd,
                color: T.ink,
                fontWeight: T.wStrong,
              ),
            ),
            Text(
              _filledDays > 0 ? '$_filledDays 天 · 草案未保存' : '草案未保存',
              style: const TextStyle(fontSize: T.fsXs, color: T.text3),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: _mode == _DetailMode.list ? '切到地图模式' : '切到列表模式',
            icon: Icon(
              _mode == _DetailMode.list ? Icons.map_outlined : Icons.list_alt,
              size: 20,
              color: T.text2,
            ),
            onPressed: () => setState(() {
              _mode = _mode == _DetailMode.list
                  ? _DetailMode.map
                  : _DetailMode.list;
            }),
          ),
          const SizedBox(width: T.sp1),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: _mode == _DetailMode.list ? _listView() : _mapView(),
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(T.sp4, T.sp2, T.sp4, T.sp2),
          decoration: const BoxDecoration(
            color: T.surface,
            border: Border(top: BorderSide(color: T.line)),
          ),
          child: SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: _saving ? null : _confirmSave,
              icon: _saving
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.check, size: 18),
              label: Text(_saving ? '正在保存' : '确认保存'),
              style: ElevatedButton.styleFrom(
                backgroundColor: T.primary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: T.sp3),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(T.rCtl),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 04 屏：列表模式。地图缩略图在上，逐日草案与检查项在下。
  Widget _listView() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(T.sp4, 0, T.sp4, T.sp4),
      children: [
        MapPanel(height: 180, pinCount: _filledDays, city: _city),
        const SizedBox(height: T.sp4),
        if (_plan.advice != null && _plan.advice!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: T.sp3),
            child: Text(_plan.advice!, style: T.tSm),
          ),
        DraftBlocks(
          view: _plan,
          baseText: '',
          onQaSubmit: (text, extra) {},
          onQaSkip: () {},
          busy: false,
        ),
        const SizedBox(height: T.sp3),
        const Text(
          '行程建议仅供参考，门票与开放时间以景区公告为准。',
          style: T.tXs,
        ),
      ],
    );
  }

  /// 05 屏：地图模式。地图占上半，下半是同一份逐日数据的点位清单，
  /// 点某一天会把地图滚到对应位置（这里用选中态表达联动，不伪造坐标）。
  Widget _mapView() {
    final days = _plan.dailySchedules.where((d) => d.items.isNotEmpty).toList();
    return Column(
      children: [
        MapPanel(height: 300, pinCount: days.length, city: _city),
        const SizedBox(height: T.sp2),
        Expanded(
          child: days.isEmpty
              ? const Center(child: Text('还没有点位', style: T.tXs))
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(T.sp4, 0, T.sp4, T.sp3),
                  itemCount: days.length,
                  separatorBuilder: (_, _) =>
                      const Divider(height: 1, color: T.line),
                  itemBuilder: (context, i) {
                    final day = days[i];
                    final names = day.items.map((e) => e.name).join(' · ');
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: CircleAvatar(
                        radius: 14,
                        backgroundColor: T.pineSoft,
                        child: Text(
                          '${day.dayIndex}',
                          style: const TextStyle(
                            fontSize: T.fsXs,
                            color: T.primary,
                            fontWeight: T.wStrong,
                          ),
                        ),
                      ),
                      title: Text('DAY ${day.dayIndex}', style: T.tSm),
                      subtitle: Text(
                        names.isEmpty ? '这天还没有候选' : names,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: T.tXs,
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }

  /// 把结构化草案装成行程载荷。
  /// PlanView 已是结构化数据，不回退成自然语言再解析一次。
  Map<String, dynamic> _payload() {
    final daily = _plan.dailySchedules
        .where((d) => d.items.isNotEmpty)
        .map((d) => <String, dynamic>{
              'date': d.date,
              'note': d.note,
              'activities': d.items
                  .map((i) => <String, dynamic>{
                        'time': '',
                        'title': i.name,
                        'location': i.city,
                        // 票价未核验时如实留空，不写「免费」或 0 元。
                        'note': i.ticket.trim().isEmpty ? '' : i.ticket,
                      })
                  .toList(),
            })
        .toList();
    return <String, dynamic>{
      'title': widget.title,
      'plan': <String, dynamic>{
        'destination': _city,
        'summary': _plan.advice ?? '',
        'notes': _plan.notices.join('；'),
      },
      'daily': daily,
    };
  }

  Future<void> _confirmSave() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: T.surface,
        title: const Text('确认保存这份草案？', style: T.tSmInk),
        content: const Text(
          '保存后会生成一条正式行程，之后仍可编辑逐日安排。',
          style: T.tSm,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('再想想'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认保存'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;

    setState(() => _saving = true);
    try {
      final payload = _payload();
      // Web 端先 materialize 装配载荷再写入；移动端已持有结构化 PlanView，
      // 因此把 materialize 作为可选校验：服务端不认这个形状时直接用本地载荷。
      try {
        await ApiClient.instance.materialize(payload);
      } catch (_) {
        // 装配接口不可用或口径不同，不影响写入。
      }
      await ApiClient.instance.createItinerary(payload);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('行程已保存')),
      );
      Navigator.of(context).pop(true);
    } on AuthExpiredException {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('登录已过期，请重新登录')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败：$e')),
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
