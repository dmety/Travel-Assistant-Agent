import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 `food.html` 移动端：一句话问吃的，走 FOOD_RECOMMEND 编排入口。
///
/// 只展示接口返回的候选与建议；查不到就直说查不到，不编店名、人均或评分。
class FoodScreen extends StatefulWidget {
  const FoodScreen({super.key});

  @override
  State<FoodScreen> createState() => _FoodScreenState();
}

class _FoodScreenState extends State<FoodScreen> {
  final _input = TextEditingController(text: '武汉有什么本地小馆子');
  bool _busy = false;
  String _advice = '';
  String _error = '';
  List<dynamic> _items = const [];

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final text = _input.text.trim();
    if (text.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final r = await ApiClient.instance.foodRecommend(text);
      final result = r['result'] is Map
          ? Map<String, dynamic>.from(r['result'] as Map)
          : r;
      final facts = result['facts'] ?? r['facts'];
      setState(() {
        _advice = (result['advice'] ?? r['advice'] ?? '').toString();
        _items = facts is List ? facts : const <dynamic>[];
      });
    } catch (e) {
      setState(() => _error = '暂时没查到：$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('找吃的',
            style: TextStyle(
                fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(T.sp4),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    decoration: InputDecoration(
                      hintText: '想吃什么、在哪一帶',
                      hintStyle: T.tXs,
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: T.sp3, vertical: T.sp2),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(T.rCtl),
                        borderSide: const BorderSide(color: T.line),
                      ),
                    ),
                    style: T.tSm,
                    onSubmitted: (_) => _search(),
                  ),
                ),
                const SizedBox(width: T.sp2),
                ElevatedButton(
                  onPressed: _busy ? null : _search,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: T.primary,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(T.rCtl)),
                  ),
                  child: _busy
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('查一下'),
                ),
              ],
            ),
          ),
          Expanded(child: _body()),
        ],
      ),
    );
  }

  Widget _body() {
    if (_error.isNotEmpty) {
      return Padding(
        padding: const EdgeInsets.all(T.sp4),
        child: Text(_error, style: T.tSm),
      );
    }
    if (_advice.isEmpty && _items.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(T.sp4),
        child: Text('说一句想吃什么，我来查候选。', style: T.tXs),
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(T.sp4, 0, T.sp4, T.sp4),
      children: [
        if (_advice.isNotEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(T.sp3),
            decoration: T.cardDeco(),
            child: Text(_advice, style: T.tSm),
          ),
        const SizedBox(height: T.sp3),
        const Text('候选', style: T.tLabel),
        const SizedBox(height: T.sp2),
        if (_items.isEmpty)
          const Text('这条链路没有返回候选', style: T.tXs)
        else
          ..._items.map((f) => Container(
                margin: const EdgeInsets.only(bottom: T.sp2),
                padding: const EdgeInsets.all(T.sp3),
                decoration: T.cardDeco(),
                child: Text(_factText(f), style: T.tSm),
              )),
        const SizedBox(height: T.sp3),
        const Text(
          '票价、人均与营业时间未核验时一律不补数字，以商家现场为准。',
          style: T.tXs,
        ),
      ],
    );
  }

  String _factText(dynamic fact) {
    if (fact is! Map) return fact.toString();
    final m = Map<String, dynamic>.from(fact);
    final v = m['value'];
    if (v is Map) {
      final vm = Map<String, dynamic>.from(v);
      final name = vm['name'] ?? vm['title'] ?? vm['text'];
      if (name != null) return name.toString();
    }
    return (m['text'] ?? m['summary'] ?? '一条候选').toString();
  }
}
