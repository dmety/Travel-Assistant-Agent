import 'package:flutter/material.dart';

import '../models/plan_view.dart';
import '../theme/tokens.dart';
import '../widgets/draft_blocks.dart';
import '../widgets/map_panel.dart';

/// 行程详情（设计稿屏 03 / 04）：一份只读的结构化草案。
///
/// 顶部用 MapPanel 把天数标成路线点位，下面是逐日草案 / 检查项 / 候选，
/// 复用与对话气泡相同的 [DraftBlocks]，保证两端渲染一致。
/// 补问卡在详情里给空回调——这是已生成的草案，不再回发。
class PlanDetailScreen extends StatelessWidget {
  const PlanDetailScreen({super.key, required this.title, required this.plan});

  final String title;
  final PlanView plan;

  @override
  Widget build(BuildContext context) {
    final days = plan.dailySchedules.where((d) => d.items.isNotEmpty).length;
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
            Text(title, maxLines: 1, overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
            Text(
              days > 0 ? '$days 个点位 · 草案未保存' : '草案未保存',
              style: const TextStyle(fontSize: T.fsXs, color: T.text3),
            ),
          ],
        ),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(T.sp4, 0, T.sp4, T.sp6),
        children: [
          MapPanel(
            height: 180,
            pinCount: days,
            city: plan.candidates.isNotEmpty ? plan.candidates.first.city : null,
          ),
          const SizedBox(height: T.sp4),
          if (plan.advice != null && plan.advice!.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: T.sp3),
              child: Text(plan.advice!, style: T.tSm),
            ),
          DraftBlocks(
            view: plan,
            baseText: '',
            onQaSubmit: (text, extra) {},
            onQaSkip: () {},
            busy: false,
          ),
          const SizedBox(height: T.sp4),
          const Text(
            '行程建议仅供参考，门票与开放时间以景区公告为准。',
            style: T.tXs,
          ),
        ],
      ),
    );
  }
}
