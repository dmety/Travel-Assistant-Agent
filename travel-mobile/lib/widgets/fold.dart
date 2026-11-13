import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 折叠块：次要信息不默认占屏，点开才显示（对齐 Web 的 `foldBlock`）。
class FoldBlock extends StatefulWidget {
  const FoldBlock({
    super.key,
    required this.label,
    this.closeLabel = '收起',
    required this.child,
  });

  final String label;

  /// 展开后的文案。默认「收起」——展开态还写着「展开其余 N 个」会自相矛盾。
  final String closeLabel;
  final Widget child;

  @override
  State<FoldBlock> createState() => _FoldBlockState();
}

class _FoldBlockState extends State<FoldBlock> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: () => setState(() => _open = !_open),
          borderRadius: BorderRadius.circular(T.rSm),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: T.sp2),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  _open ? widget.closeLabel : widget.label,
                  style: const TextStyle(
                    fontSize: T.fsXs,
                    height: 1.4,
                    color: T.primary,
                    fontWeight: T.wStrong,
                  ),
                ),
                const SizedBox(width: 2),
                Icon(
                  _open ? Icons.keyboard_arrow_up : Icons.keyboard_arrow_down,
                  size: 16,
                  color: T.primary,
                ),
              ],
            ),
          ),
        ),
        if (_open)
          Padding(
            padding: const EdgeInsets.only(top: T.sp1),
            child: widget.child,
          ),
      ],
    );
  }
}

/// 小角标：卡片标题右侧的「3 天」「共 6 个」这类定量说明。
class Tag extends StatelessWidget {
  const Tag(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: T.sp2, vertical: 2),
      decoration: BoxDecoration(
        color: T.surface2,
        borderRadius: BorderRadius.circular(T.rPill),
        border: Border.all(color: T.line),
      ),
      child: Text(text, style: T.tLabel),
    );
  }
}

/// 卡片小标题：层级的「标签」角色，用 12px + 600 + 灰，不抢正文。
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Text(text, style: T.tLabel)),
        ?trailing,
      ],
    );
  }
}

/// 一条「名称 + 一行小字」的行，候选/餐馆/景点共用，避免三套重复实现。
class InfoRow extends StatelessWidget {
  const InfoRow({
    super.key,
    required this.title,
    required this.meta,
    this.trailing,
  });

  final String title;
  final String meta;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: T.sp1),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: T.tSmInk.copyWith(fontWeight: T.wStrong),
                ),
                if (meta.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(meta, style: T.tXs),
                ],
              ],
            ),
          ),
          if (trailing != null) ...[
            const SizedBox(width: T.sp2),
            trailing!,
          ],
        ],
      ),
    );
  }
}

/// 按字符数截断。中文按字算，不做字节处理。
///
/// 截断点落在括号内时整段丢弃括号补充说明（「免费（听涛、磨山…」→「免费」），
/// 否则窄屏上会出现半截括号，看着像数据坏了。
String brief(String s, int max) {
  final t = s.trim();
  if (t.isEmpty) return '';
  if (t.length <= max) return t;

  var cut = t.substring(0, max);
  var dropped = false;

  // 括号段是否在截断点前闭合
  final open = cut.lastIndexOf(RegExp(r'[（(]'));
  if (open > 0) {
    final close = RegExp(r'[）)]').firstMatch(t.substring(open));
    final closedBefore = close != null && open + close.end <= max;
    if (!closedBefore) {
      cut = cut.substring(0, open);
      dropped = true;
    }
  }

  cut = cut.replaceAll(RegExp(r'[\s，,、；;：:]+$'), '');
  if (cut.isEmpty) return '${t.substring(0, max)}…';

  // 时间段被切一半（「08:30-18:00,19:30-2…」）比截断本身更难读，回退到上一个分隔符
  final halfRange = RegExp(r'[,\s，]\d{1,2}:\d{2}-?\d{0,2}$').firstMatch(cut);
  if (halfRange != null) {
    final back = cut.substring(0, halfRange.start).trimRight();
    if (back.isNotEmpty) cut = back;
  }

  // 补充说明是整段丢掉的，剩下的是完整核心信息，不再补省略号
  return dropped ? cut : '$cut…';
}
