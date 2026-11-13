import 'package:flutter/material.dart';

import '../models/plan_view.dart';
import '../theme/tokens.dart';
import 'fold.dart';

/// 逐日草案卡：只渲染有候选的天，收敛掉的天合并成一条说明。
/// 票价/开放时间压成一行小字，不逐天重复长 note（与 Web `planCard` 同规则）。
class PlanCard extends StatelessWidget {
  const PlanCard({super.key, required this.view});
  final PlanView view;

  @override
  Widget build(BuildContext context) {
    final filled =
        view.dailySchedules.where((d) => d.items.isNotEmpty).toList();
    final emptyDays = view.dailySchedules.length - filled.length;
    final left = view.trimmedDays + (emptyDays > 0 ? emptyDays : 0);

    return Container(
      decoration: T.cardDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionLabel('逐日草案', trailing: Tag('${filled.length} 天')),
          if (filled.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: T.sp2),
              child: Text('本轮没有可排的候选景点，未生成空天。', style: T.tXs),
            )
          else
            ...filled.map(_dayRow),
          if (left > 0) ...[
            const Padding(
              padding: EdgeInsets.symmetric(vertical: T.sp2),
              child: Divider(color: T.line, height: 1),
            ),
            Text(
              '另有 $left 天没有可查景点，未硬凑空天；'
              '可以把天数缩短到 ${filled.length} 天，或补一个周边城市再排。',
              style: T.tXs,
            ),
          ],
        ],
      ),
    );
  }

  Widget _dayRow(PlanDay d) {
    return Padding(
      padding: const EdgeInsets.only(top: T.sp2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 32,
            padding: const EdgeInsets.symmetric(vertical: 2),
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: T.primarySoft,
              borderRadius: BorderRadius.circular(T.rSm),
            ),
            child: Text(
              'D${d.dayIndex}',
              style: const TextStyle(
                fontSize: T.fsXs,
                height: 1.4,
                color: T.primary,
                fontWeight: T.wStrong,
              ),
            ),
          ),
          const SizedBox(width: T.sp2),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 一景一行：名称独占一行，票价/开放另起一行小字。
                // 早先把名称既拼成标题又拼进明细，窄屏上同一景点名出现两遍，去掉标题行。
                for (var i = 0; i < d.items.length; i++) ...[
                  if (i > 0) const SizedBox(height: T.sp2),
                  Text(
                    d.items[i].name,
                    style: T.tSmInk.copyWith(fontWeight: T.wStrong),
                  ),
                  const SizedBox(height: 2),
                  Text(_meta(d.items[i]), style: T.tXs),
                ],
                if (d.items.isEmpty && d.note.isNotEmpty)
                  Text(d.note, style: T.tXs),
                if (d.date.isNotEmpty) ...[
                  const SizedBox(height: T.sp1),
                  Text(d.date, style: T.tXs),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 票价与开放时间压成一行。金额/时间本身语义自明，不再加「票价」「开放」前缀。
  String _meta(PlanDayItem i) {
    final t = brief(i.ticket, 18);
    // 「周一至周日」等价于「每天」，压缩 3 个字给时间段腾宽度。
    // 只替换「周一至周日」这种全周表述，「周二至周日」（周一闭馆）不动。
    final raw = i.openTime.replaceFirst('周一至周日', '每天');
    final o = brief(raw, 22);
    final parts = <String>[t.isEmpty ? '票价未核验' : t];
    if (o.isNotEmpty) parts.add(o);
    return parts.join(' · ');
  }
}

/// 候选景点：默认只列排序最前的 3 个，其余折叠（Web `candCard` 同规则）。
class CandList extends StatelessWidget {
  const CandList({super.key, required this.items});
  final List<PlanCandidate> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();
    final shown = items.take(3).toList();
    final rest = items.skip(3).toList();

    return Container(
      decoration: T.panelDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionLabel('候选景点 · 共 ${items.length} 个'),
          const SizedBox(height: T.sp1),
          ...shown.map(_row),
          if (rest.isNotEmpty)
            FoldBlock(
              label: '展开其余 ${rest.length} 个候选',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: rest.map(_row).toList(),
              ),
            ),
        ],
      ),
    );
  }

  Widget _row(PlanCandidate a) {
    final bits = <String>[];
    if (a.city.isNotEmpty) bits.add(a.city);
    if (a.level.isNotEmpty) bits.add(a.level);
    if (a.rating.isNotEmpty) bits.add('评分 ${a.rating}');
    bits.add(a.ticket.isEmpty ? '票价未核验' : '票价 ${brief(a.ticket, 16)}');
    return InfoRow(title: a.name, meta: bits.join(' · '));
  }
}

/// 餐馆卡：只展开离得最近的前 8 家，其余在末尾一句说明（Web `foodCard` 同规则）。
class FoodList extends StatelessWidget {
  const FoodList({super.key, required this.items});
  final List<FoodItem> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();
    final shown = items.take(8).toList();
    final restCount = items.length - shown.length;

    return Container(
      decoration: T.panelDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionLabel('可以去的餐馆 · 来自地图 POI'),
          const SizedBox(height: T.sp1),
          ...shown.map(_row),
          if (restCount > 0)
            Padding(
              padding: const EdgeInsets.only(top: T.sp1),
              child: Text(
                '另有 $restCount 家未展开，可以缩小范围再问，比如「东湖附近有什么好吃的」',
                style: T.tXs,
              ),
            ),
        ],
      ),
    );
  }

  Widget _row(FoodItem it) {
    final bits = <String>[];
    if (it.slot.isNotEmpty) bits.add(it.slot);
    if (it.price.isNotEmpty) bits.add('人均 ${it.price} 元');
    final dist = it.distanceText;
    if (dist.isNotEmpty) bits.add(dist);
    if (it.rating.isNotEmpty) bits.add('评分 ${it.rating}');
    if (it.tags.isNotEmpty) bits.add(it.tags.join('/'));
    if (it.locality == 'LOCAL') bits.add('本地');

    Widget? badge;
    if (it.locality == 'LOCAL') {
      badge = Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        decoration: BoxDecoration(
          color: T.okSoft,
          borderRadius: BorderRadius.circular(T.rSm),
          border: Border.all(color: T.okLine),
        ),
        child: const Text(
          '本地',
          style: TextStyle(
            fontSize: T.fsXs,
            height: 1.4,
            color: T.ok,
            fontWeight: T.wStrong,
          ),
        ),
      );
    }
    return InfoRow(
      title: it.name,
      meta: bits.isEmpty ? '暂无更多信息' : bits.join(' · '),
      trailing: badge,
    );
  }
}

/// 检查项：HIGH 用错误色、MEDIUM 用警告色；只用色块 + 边框，不靠颜色单独表意。
class CheckList extends StatelessWidget {
  const CheckList({super.key, required this.checks});
  final List<PlanCheck> checks;

  /// 只保留可执行的两级；LOW 不进正文。
  static List<PlanCheck> actionable(List<PlanCheck> all) =>
      all.where((c) => c.isActionable).toList();

  @override
  Widget build(BuildContext context) {
    if (checks.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: checks.map(_row).toList(),
    );
  }

  Widget _row(PlanCheck c) {
    final high = c.isHigh;
    final text = c.suggestion.isEmpty
        ? c.message
        : '${c.message}｜${c.suggestion}';
    return Container(
      margin: const EdgeInsets.only(top: T.sp2),
      padding: const EdgeInsets.symmetric(
        horizontal: T.sp3,
        vertical: T.sp2,
      ),
      decoration: BoxDecoration(
        color: high ? T.errSoft : T.warnSoft,
        borderRadius: BorderRadius.circular(T.rCtl),
        border: Border.all(color: high ? T.errLine : T.warnLine),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            high ? Icons.error_outline : Icons.info_outline,
            size: 16,
            color: high ? T.err : T.warn,
          ),
          const SizedBox(width: T.sp2),
          Expanded(
            child: Text(
              text,
              style: TextStyle(
                fontSize: T.fsXs,
                height: 1.5,
                color: high ? T.err : T.warn,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 一段纯提示条（notices / 降级说明）。
class NoticeBar extends StatelessWidget {
  const NoticeBar(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(top: T.sp2),
      padding: const EdgeInsets.symmetric(
        horizontal: T.sp3,
        vertical: T.sp2,
      ),
      decoration: BoxDecoration(
        color: T.surface2,
        borderRadius: BorderRadius.circular(T.rCtl),
        border: Border.all(color: T.line),
      ),
      child: Text(text, style: T.tXs),
    );
  }
}
