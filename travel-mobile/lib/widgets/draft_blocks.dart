import 'package:flutter/material.dart';

import '../models/plan_view.dart';
import '../theme/tokens.dart';
import 'cards.dart';
import 'fold.dart';
import 'qa_card.dart';

/// 助手富内容统一入口：正文之后的卡片顺序与 Web `appendDraftBlocks` 一致——
/// notices（无 advice 时）→ 逐日草案 → 检查项（前 2 + 折叠）→ 候选 → 补问。
///
/// 顺序是刻意的：先给结论（草案），再给需要处理的事（检查项），
/// 最后才是可展开的清单与追问，避免用户第一条看到的是六条警告。
class DraftBlocks extends StatelessWidget {
  const DraftBlocks({
    super.key,
    required this.view,
    required this.baseText,
    required this.onQaSubmit,
    required this.onQaSkip,
    this.busy = false,
  });

  final PlanView view;
  final String baseText;
  final void Function(String text, Map<String, dynamic> extra) onQaSubmit;
  final VoidCallback onQaSkip;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    if (view.isRefused) return const SizedBox.shrink();

    final checks = CheckList.actionable(view.checks);
    final head = checks.take(2).toList();
    final rest = checks.skip(2).toList();

    final blocks = <Widget>[];

    // 有模型正文时不再重复列 notices：正文通常已覆盖同样的缺失项
    if (view.advice == null || view.advice!.isEmpty) {
      for (final n in view.notices.take(3)) {
        blocks.add(NoticeBar(n));
      }
    }

    if (view.dailySchedules.isNotEmpty || view.trimmedDays > 0) {
      blocks.add(PlanCard(view: view));
    }

    if (head.isNotEmpty) blocks.add(CheckList(checks: head));
    if (rest.isNotEmpty) {
      blocks.add(Container(
        padding: const EdgeInsets.symmetric(horizontal: T.sp2),
        decoration: T.panelDeco(),
        child: FoldBlock(
          label: '展开其余 ${rest.length} 项检查提示',
          child: CheckList(checks: rest),
        ),
      ));
    }

    if (view.candidates.isNotEmpty) blocks.add(CandList(items: view.candidates));

    if (view.questions.isNotEmpty) {
      blocks.add(QaCard(
        questions: view.questions,
        baseText: baseText,
        onSubmit: onQaSubmit,
        onSkip: onQaSkip,
        busy: busy,
      ));
    }

    if (blocks.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final b in blocks)
          Padding(padding: const EdgeInsets.only(top: T.sp2), child: b),
      ],
    );
  }
}

/// 美食回复块：正文 + notices + 餐馆清单。
class FoodBlocks extends StatelessWidget {
  const FoodBlocks({super.key, required this.view});
  final FoodView view;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (view.advice != null) NoticeBar(view.advice!),
        for (final n in view.notices.take(3)) NoticeBar(n),
        if (view.items.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: T.sp2),
            child: FoodList(items: view.items),
          ),
      ],
    );
  }
}

/// 闲聊卡片分发（`/api/chat` 的 `cards[]`）：天气 / 景点 / 投票 / 信封 / 备选地名。
/// 未知类型静默跳过——后端加新卡时不至于让老 App 崩。
class ToolCardBlocks extends StatelessWidget {
  const ToolCardBlocks({super.key, required this.cards, this.onPickPlace});

  final List<ToolCard> cards;

  /// 备选城市被点中时回发一句话（对齐 Web `destCard` 的可点选行为）。
  final void Function(String place)? onPickPlace;

  @override
  Widget build(BuildContext context) {
    final widgets = <Widget>[];
    for (final c in cards) {
      final w = _build(c);
      if (w != null) {
        widgets.add(Padding(
          padding: const EdgeInsets.only(top: T.sp2),
          child: w,
        ));
      }
    }
    if (widgets.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: widgets,
    );
  }

  Widget? _build(ToolCard card) {
    switch (card.type) {
      case 'weather':
        return _weather(card.data);
      case 'places':
        return _places(card.data);
      case 'vote':
        return _vote(card.data);
      case 'dests':
        return _dests(card.data);
      case 'envelope':
        if (card.data is! Map) return null;
        final env = Map<String, dynamic>.from(card.data as Map);
        // 美食与购票也要认，否则闲聊链路里的这两类回复会只剩文字
        if (card.intent == 'FOOD_RECOMMEND') {
          final food = FoodView.fromEnvelope(env);
          if (food == null) return null;
          if (food.items.isEmpty && food.advice == null) return null;
          return FoodBlocks(view: food);
        }
        if (card.intent == 'TICKET_ADVICE') {
          final g = env['ticketGuidance'];
          if (g is! Map) return null;
          return _ticket(g);
        }
        final view = PlanView.fromEnvelope(env);
        if (view == null) return null;
        if (view.candidates.isEmpty && view.dailySchedules.isEmpty) return null;
        return PlanCard(view: view);
      default:
        return null;
    }
  }

  /// 备选地名卡：点一下就按这座城市重问（Web `destCard` 同语义）。
  Widget? _dests(dynamic data) {
    if (data is! List || data.isEmpty) return null;
    final names = data
        .whereType<Map>()
        .map((m) => (m['name'] ?? '').toString())
        .where((s) => s.isNotEmpty)
        .take(6)
        .toList();
    if (names.isEmpty) return null;
    return Wrap(
      spacing: T.sp2,
      runSpacing: T.sp2,
      children: [
        for (final n in names)
          InkWell(
            onTap: onPickPlace == null ? null : () => onPickPlace!(n),
            borderRadius: BorderRadius.circular(T.rPill),
            child: Container(
              padding: const EdgeInsets.symmetric(
                horizontal: T.sp3,
                vertical: T.sp2,
              ),
              decoration: BoxDecoration(
                color: T.surface,
                borderRadius: BorderRadius.circular(T.rPill),
                border: Border.all(color: T.primaryLine),
              ),
              child: Text(
                n,
                style: const TextStyle(
                  fontSize: T.fsXs,
                  height: 1.3,
                  color: T.primary,
                  fontWeight: T.wStrong,
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// 购票指引：只陈述后端给的信息，不编造渠道。
  Widget? _ticket(Map g) {
    final bits = <String>[];
    for (final k in ['summary', 'note', 'channel', 'advice']) {
      final v = g[k];
      if (v != null && v.toString().trim().isNotEmpty) bits.add(v.toString());
    }
    if (bits.isEmpty) return null;
    return Container(
      decoration: T.panelDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('购票指引'),
          const SizedBox(height: T.sp1),
          Text(bits.join('\n'), style: T.tXs),
        ],
      ),
    );
  }

  Widget? _weather(dynamic data) {
    if (data is! Map) return null;
    final days = data['days'] ?? data['forecasts'];
    if (days is! List || days.isEmpty) return null;
    return Container(
      decoration: T.panelDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('天气 · 来自高德预报'),
          const SizedBox(height: T.sp1),
          for (final d in days.take(4))
            if (d is Map)
              InfoRow(
                title: (d['date'] ?? d['day'] ?? '').toString(),
                meta: _weatherMeta(d),
              ),
        ],
      ),
    );
  }

  String _weatherMeta(Map d) {
    final bits = <String>[];
    final day = d['dayWeather'] ?? d['text'] ?? d['weather'];
    final night = d['nightWeather'] ?? d['nightText'];
    if (day != null) bits.add(day.toString());
    if (night != null && night.toString() != day?.toString()) {
      bits.add('夜间${night.toString()}');
    }
    final low = d['nightTemp'] ?? d['minTemp'] ?? d['tempMin'];
    final high = d['dayTemp'] ?? d['maxTemp'] ?? d['tempMax'];
    if (low != null || high != null) {
      bits.add('${low ?? '?'}~${high ?? '?'}℃');
    }
    final wind = d['dayWind'] ?? d['wind'];
    if (wind != null) bits.add(wind.toString());
    return bits.isEmpty ? '暂无详情' : bits.join(' · ');
  }

  Widget? _places(dynamic data) {
    if (data is! List || data.isEmpty) return null;
    final rows = data.whereType<Map>().take(4).toList();
    if (rows.isEmpty) return null;
    return Container(
      decoration: T.panelDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('景点 · 好评高的在前'),
          const SizedBox(height: T.sp1),
          for (final r in rows)
            InfoRow(
              title: (r['name'] ?? '').toString(),
              meta: _placeMeta(r),
            ),
        ],
      ),
    );
  }

  String _placeMeta(Map r) {
    final bits = <String>[];
    if (r['level'] != null) bits.add(r['level'].toString());
    bits.add(r['rating'] == null ? '暂无评分' : '★ ${r['rating']}');
    final t = r['ticket'];
    bits.add((t == null || t.toString().isEmpty) ? '票价未核验' : "票价 ${brief(t.toString(), 20)}");
    return bits.join(' · ');
  }

  Widget? _vote(dynamic data) {
    if (data is! Map) return null;
    final plans = (data['plans'] is List) ? data['plans'] as List : const [];
    final ranking = (data['ranking'] is List) ? data['ranking'] as List : const [];
    final winner = data['winner']?.toString();
    final notices =
        (data['notices'] is List) ? data['notices'] as List : const [];

    final focusText = <String, String>{
      'COST': '省钱',
      'FOOD': '美食',
      'WALK': '少走路',
      'BALANCED': '均衡',
    };

    String? winLine;
    if (winner != null && winner.isNotEmpty) {
      Map? w;
      for (final p in plans) {
        if (p is Map && p['planId'] == winner) w = p;
      }
      winLine = '胜出：$winner'
          '${w?['title'] != null ? ' · ${w!['title']}' : ''}'
          '${w?['focus'] != null ? '（侧重${focusText[w!['focus']] ?? w['focus']}）' : ''}';
    }

    return Container(
      decoration: T.cardDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionLabel('评委投票 · 共 ${plans.length} 套候选'),
          if (winLine != null) ...[
            const SizedBox(height: T.sp1),
            Text(winLine, style: T.tSmInk.copyWith(fontWeight: T.wStrong)),
            const SizedBox(height: 2),
            const Text('评分来自评委 Agent 的结构化票，仅供参考；行程安排最终由你决定。', style: T.tXs),
          ] else if (notices.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: T.sp1),
              child: Text(notices.first.toString(), style: T.tXs),
            ),
          if (ranking.isNotEmpty)
            FoldBlock(
              label: '展开 ${plans.length} 套候选评分',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final r in ranking)
                    if (r is Map)
                      InfoRow(
                        title: (r['planId'] ?? '').toString(),
                        meta: '加权 ${r['score'] ?? '-'} · ${r['voterCount'] ?? 0} 票有效',
                      ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
