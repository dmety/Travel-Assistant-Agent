import 'package:flutter/material.dart';

import '../models/plan_view.dart';
import '../theme/tokens.dart';
import 'fold.dart';

/// 补问卡：缺什么就给什么控件。
///
/// 与 Web `qaCard` 同规则：
/// - `departDate` / `returnDate` 给「今天 / 明天 / 本周六 / 下周六」，返回日期依赖出发日期；
/// - `days` / `travelers` / `budget` 给预设档位；
/// - 没有对应控件的补问项降级成文字，不硬造选项；
/// - 提交时把选择拼回一句人话 + 附带结构化字段（extra 优先于解析结果）。
class QaCard extends StatefulWidget {
  const QaCard({
    super.key,
    required this.questions,
    required this.baseText,
    required this.onSubmit,
    required this.onSkip,
    this.busy = false,
  });

  final List<PlanQuestion> questions;
  final String baseText;
  final void Function(String text, Map<String, dynamic> extra) onSubmit;
  final VoidCallback onSkip;
  final bool busy;

  @override
  State<QaCard> createState() => _QaCardState();
}

class _QaCardState extends State<QaCard> {
  final Map<String, dynamic> _picks = {};
  bool _submitted = false;
  String? _hint;

  static const _kinds = <String, _QaKind>{
    'departDate': _QaKind(label: '出发日期', isDate: true),
    'returnDate': _QaKind(label: '返回日期', isDate: true),
    'days': _QaKind(label: '玩几天', unit: '天', options: [1, 2, 3, 5, 7]),
    'travelers': _QaKind(label: '同行人数', unit: '人', options: [1, 2, 3, 4]),
    'budget': _QaKind(label: '预算上限', unit: '元', options: [2000, 5000, 10000, 15000]),
  };

  @override
  void initState() {
    super.initState();
    // 上一轮已说过的信息预填回来，不重复问（与 Web 的正则回填同义）
    final t = widget.baseText;
    final out = RegExp(r'(\d{4}-\d{2}-\d{2})\s*出发').firstMatch(t);
    if (out != null) _picks['departDate'] = out.group(1);
    final back = RegExp(r'(\d{4}-\d{2}-\d{2})\s*返回').firstMatch(t);
    if (back != null) _picks['returnDate'] = back.group(1);
    final bud = RegExp(r'预算\s*(\d{2,6})').firstMatch(t);
    if (bud != null) _picks['budget'] = int.tryParse(bud.group(1)!);
  }

  static String _iso(DateTime d) {
    String two(int n) => n < 10 ? '0$n' : '$n';
    return '${d.year}-${two(d.month)}-${two(d.day)}';
  }

  static String _short(DateTime d) {
    String two(int n) => n < 10 ? '0$n' : '$n';
    return '${two(d.month)}-${two(d.day)}';
  }

  static DateTime _addDays(DateTime d, int n) => d.add(Duration(days: n));

  /// 本周六（今天就是周六则取今天）。
  static DateTime _nextSaturday(DateTime today) {
    final delta = (DateTime.saturday - today.weekday) % 7;
    return _addDays(today, delta);
  }

  DateTime? get _depart {
    final v = _picks['departDate'];
    if (v is! String) return null;
    return DateTime.tryParse(v);
  }

  List<Widget> _dateChips(String field) {
    final today = DateTime.now();
    final chips = <(String, DateTime)>[];
    if (field == 'returnDate') {
      final base = _depart;
      if (base == null) return const []; // 没有出发日就不猜返程
      chips.addAll([
        ('当天返程', base),
        ('第 2 天返', _addDays(base, 1)),
        ('第 3 天返', _addDays(base, 2)),
        ('第 4 天返', _addDays(base, 3)),
      ]);
    } else {
      final sat = _nextSaturday(today);
      chips.addAll([
        ('今天', today),
        ('明天', _addDays(today, 1)),
        ('本周六', sat),
        ('下周六', _addDays(sat, 7)),
      ]);
    }

    return chips.map((c) {
      final iso = _iso(c.$2);
      final on = _picks[field] == iso;
      return _Chip(
        label: '${c.$1} ${_short(c.$2)}',
        selected: on,
        onTap: _submitted
            ? null
            : () => setState(() {
                  _picks[field] = on ? null : iso;
                  // 改出发日期后，已选的返回日期可能早于它，直接清掉
                  if (field == 'departDate' && _picks['returnDate'] != null) {
                    final r = DateTime.tryParse(_picks['returnDate'].toString());
                    if (r != null && r.isBefore(c.$2)) _picks.remove('returnDate');
                  }
                }),
      );
    }).toList();
  }

  String _compose() {
    var head = widget.baseText.replaceAll(RegExp(r'[，,。！？!?\s]+$'), '');
    final bits = <String>[];

    // 原话里已有同字段表述时就地改写，没有才追加到末尾。
    // 二次提交时 baseText 已经是上一轮拼好的整句，无脑追加会让日期/预算整段重复。
    void upsert(RegExp re, String value) {
      if (re.hasMatch(head)) {
        head = head.replaceFirst(re, value);
      } else {
        bits.add(value);
      }
    }

    final days = _picks['days'];
    if (days != null) upsert(RegExp(r'\d+\s*天'), '$days 天');
    final pax = _picks['travelers'];
    if (pax != null) upsert(RegExp(r'\d+\s*人'), '$pax 人');
    final dep = _picks['departDate'];
    if (dep != null) upsert(RegExp(r'\d{4}-\d{2}-\d{2}\s*出发'), '$dep 出发');
    final ret = _picks['returnDate'];
    if (ret != null) upsert(RegExp(r'\d{4}-\d{2}-\d{2}\s*返回'), '$ret 返回');
    final bud = _picks['budget'];
    if (bud != null) upsert(RegExp(r'预算\s*\d+'), '预算 $bud');

    return head + (bits.isEmpty ? '' : '，${bits.join('，')}');
  }

  Map<String, dynamic> _extra() {
    final out = <String, dynamic>{};
    for (final k in ['days', 'travelers', 'budget', 'departDate', 'returnDate']) {
      final v = _picks[k];
      if (v != null && v != '') out[k] = v;
    }
    return out;
  }

  void _submit() {
    final d = _depart;
    final r = _picks['returnDate'] is String
        ? DateTime.tryParse(_picks['returnDate'] as String)
        : null;
    if (d != null && r != null && r.isBefore(d)) {
      setState(() => _hint = '返回日期早于出发日期，改一下再排。');
      return;
    }
    setState(() {
      _submitted = true;
      _hint = '正在按这些信息重排…';
    });
    widget.onSubmit(_compose(), _extra());
  }

  @override
  Widget build(BuildContext context) {
    final known = widget.questions
        .where((q) => _kinds.containsKey(q.field))
        .toList();
    final unknown = widget.questions
        .where((q) => !_kinds.containsKey(q.field))
        .toList();

    return Container(
      decoration: T.cardDeco(),
      padding: const EdgeInsets.all(T.sp3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题独占一行：早先带右侧「点即可」标签，长标题被压成两行且与标签挤在一起
          const SectionLabel('补上这几项，行程就能排到具体日期'),
          const SizedBox(height: T.sp2),

          for (final q in known) ...[
            _group(q),
            const SizedBox(height: T.sp3),
          ],

          for (final q in unknown) ...[
            Text(
              q.question.isNotEmpty ? q.question : q.code,
              style: T.tSmInk.copyWith(fontWeight: T.wStrong),
            ),
            if (q.hint.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(q.hint, style: T.tXs),
              ),
            const SizedBox(height: T.sp3),
          ],

          Row(
            children: [
              FilledButton(
                onPressed: (_submitted || widget.busy) ? null : _submit,
                style: FilledButton.styleFrom(
                  backgroundColor: T.primary,
                  foregroundColor: Colors.white,
                  minimumSize: const Size(0, 40),
                  padding: const EdgeInsets.symmetric(horizontal: T.sp4),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(T.rCtl),
                  ),
                  textStyle: const TextStyle(
                    fontSize: T.fsSm,
                    fontWeight: T.wStrong,
                  ),
                ),
                child: Text(_submitted ? '已提交' : '按这些重排'),
              ),
              const SizedBox(width: T.sp2),
              TextButton(
                onPressed: _submitted
                    ? null
                    : () {
                        setState(() {
                          _submitted = true;
                          _hint = '已跳过，下一句话里补充也可以。';
                        });
                        widget.onSkip();
                      },
                style: TextButton.styleFrom(
                  foregroundColor: T.text2,
                  textStyle: const TextStyle(fontSize: T.fsSm),
                ),
                child: const Text('先不填'),
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.only(top: T.sp1),
            child: Text(
              _hint ?? '日期点一下就行，其余可以只选你清楚的。',
              style: T.tXs,
            ),
          ),
        ],
      ),
    );
  }

  Widget _group(PlanQuestion q) {
    final spec = _kinds[q.field]!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          spec.unit == null ? spec.label : '${spec.label}（${spec.unit}）',
          style: const TextStyle(
            fontSize: T.fsXs,
            height: 1.4,
            color: T.text3,
            fontWeight: T.wStrong,
          ),
        ),
        const SizedBox(height: T.sp1),
        Wrap(
          spacing: T.sp2,
          runSpacing: T.sp2,
          children: spec.isDate
              ? _dateChips(q.field)
              : spec.options!.map((o) {
                  final on = _picks[q.field] == o;
                  return _Chip(
                    label: spec.unit == null ? '$o' : '$o${spec.unit}',
                    selected: on,
                    onTap: _submitted
                        ? null
                        : () => setState(
                            () => _picks[q.field] = on ? null : o),
                  );
                }).toList(),
        ),
        // 返回日期必须先有出发日期，否则整组隐藏——不给「猜」的机会
        if (spec.isDate && q.field == 'returnDate' && _depart == null)
          const Padding(
            padding: EdgeInsets.only(top: T.sp1),
            child: Text('先选出发日期，再选返程。', style: T.tXs),
          ),
      ],
    );
  }
}

class _QaKind {
  const _QaKind({
    required this.label,
    this.unit,
    this.options,
    this.isDate = false,
  });
  final String label;
  final String? unit;
  final List<int>? options;
  final bool isDate;
}

/// 选项按钮：选中态用主色淡底 + 主色描边，未选中用描边灰。
class _Chip extends StatelessWidget {
  const _Chip({required this.label, required this.selected, this.onTap});
  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(T.rPill),
      child: Container(
        padding: const EdgeInsets.symmetric(
          horizontal: T.sp3,
          vertical: T.sp2,
        ),
        decoration: BoxDecoration(
          color: selected ? T.primarySoft : T.surface,
          borderRadius: BorderRadius.circular(T.rPill),
          border: Border.all(color: selected ? T.primary : T.lineStrong),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: T.fsXs,
            height: 1.3,
            color: selected ? T.primary : T.text2,
            fontWeight: selected ? T.wStrong : T.wBody,
          ),
        ),
      ),
    );
  }
}
