import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 `ticket.html` 移动端：票种目录 + 购票建议。
///
/// 只给经核验的官方入口与方法；不下单、不查余票、不生成猜测链接。
class TicketScreen extends StatefulWidget {
  const TicketScreen({super.key});

  @override
  State<TicketScreen> createState() => _TicketScreenState();
}

class _TicketScreenState extends State<TicketScreen> {
  final _input = TextEditingController(text: '黄鹤楼');
  bool _busy = false;
  String _advice = '';
  String _error = '';
  List<dynamic> _catalog = const [];

  @override
  void initState() {
    super.initState();
    _loadCatalog();
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _loadCatalog() async {
    try {
      final data = await ApiClient.instance.ticketCatalog();
      if (!mounted) return;
      setState(() {
        _catalog = data is List
            ? data
            : (data is Map && data['channels'] is List
                ? data['channels'] as List<dynamic>
                : const <dynamic>[]);
      });
    } catch (_) {
      // 目录不可用不阻断建议查询
    }
  }

  Future<void> _ask() async {
    final text = _input.text.trim();
    if (text.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final r = await ApiClient.instance.ticketAdvice({
        'message': '$text怎么买票',
        'needs': <String, dynamic>{},
      });
      if (!mounted) return;
      final m = r is Map ? Map<String, dynamic>.from(r) : <String, dynamic>{};
      setState(() {
        _advice = (m['advice'] ?? m['reply'] ?? m['summary'] ?? '').toString();
      });
    } catch (e) {
      if (!mounted) return;
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
        title: const Text('购票辅助',
            style: TextStyle(
                fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(T.sp4),
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _input,
                  decoration: InputDecoration(
                    hintText: '景区名称',
                    hintStyle: T.tXs,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: T.sp3, vertical: T.sp2),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(T.rCtl),
                      borderSide: const BorderSide(color: T.line),
                    ),
                  ),
                  style: T.tSm,
                  onSubmitted: (_) => _ask(),
                ),
              ),
              const SizedBox(width: T.sp2),
              ElevatedButton(
                onPressed: _busy ? null : _ask,
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
                    : const Text('问一问'),
              ),
            ],
          ),
          const SizedBox(height: T.sp3),
          if (_error.isNotEmpty) Text(_error, style: T.tSm),
          if (_advice.isNotEmpty)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(T.sp3),
              decoration: T.cardDeco(),
              child: Text(_advice, style: T.tSm),
            ),
          const SizedBox(height: T.sp3),
          const Text('经核验的官方购票入口', style: T.tLabel),
          const SizedBox(height: T.sp2),
          if (_catalog.isEmpty)
            const Text('当前没有已核验的入口，购票请到景区官方公众号或现场窗口。',
                style: T.tXs)
          else
            ..._catalog.map((c) {
              final m = c is Map
                  ? Map<String, dynamic>.from(c)
                  : <String, dynamic>{};
              return Container(
                margin: const EdgeInsets.only(bottom: T.sp2),
                padding: const EdgeInsets.all(T.sp3),
                decoration: T.cardDeco(),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text((m['name'] ?? m['channel'] ?? '官方入口').toString(),
                        style: T.tSmInk),
                    if (m['url'] != null)
                      Text(m['url'].toString(), style: T.tXs),
                  ],
                ),
              );
            }),
          const SizedBox(height: T.sp3),
          const Text(
            '票价按数据源原文显示，未核验的不补数字；本系统不代下单、不查余票。',
            style: T.tXs,
          ),
        ],
      ),
    );
  }
}
