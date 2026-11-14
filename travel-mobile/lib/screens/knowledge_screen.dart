import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 `knowledge.html` 移动端：城市 / 景点检索与详情。
///
/// 数据直接来自知识库接口；库不可用（503）时如实显示原因，不拿样例数据顶替。
class KnowledgeScreen extends StatefulWidget {
  const KnowledgeScreen({super.key});

  @override
  State<KnowledgeScreen> createState() => _KnowledgeScreenState();
}

class _KnowledgeScreenState extends State<KnowledgeScreen> {
  final _city = TextEditingController();
  final _name = TextEditingController();
  bool _busy = false;
  String _error = '';
  List<dynamic> _rows = const [];

  @override
  void dispose() {
    _city.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    setState(() {
      _busy = true;
      _error = '';
    });
    try {
      final data = await ApiClient.instance.searchAttractions(
        city: _city.text.trim(),
        name: _name.text.trim(),
      );
      if (!mounted) return;
      List<dynamic> rows;
      if (data is List) {
        rows = data;
      } else if (data is Map && data['attractions'] is List) {
        rows = data['attractions'] as List<dynamic>;
      } else {
        rows = const <dynamic>[];
      }
      setState(() => _rows = rows);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '知识库暂时不可用：$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('景点知识库',
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
            child: Column(
              children: [
                TextField(
                  controller: _city,
                  decoration: InputDecoration(
                    hintText: '城市（如 武汉）',
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
                const SizedBox(height: T.sp2),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _name,
                        decoration: InputDecoration(
                          hintText: '景点名（可留空）',
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
                          : const Text('检索'),
                    ),
                  ],
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
    if (_rows.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(T.sp4),
        child: Text('填一个城市，看看库里有什么。', style: T.tXs),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(T.sp4, 0, T.sp4, T.sp4),
      itemCount: _rows.length,
      separatorBuilder: (_, __) => const Divider(height: 1, color: T.line),
      itemBuilder: (context, i) {
        final m = _rows[i] is Map
            ? Map<String, dynamic>.from(_rows[i] as Map)
            : <String, dynamic>{};
        final ticket = (m['ticket'] ?? '').toString().trim();
        return ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text((m['name'] ?? '未命名').toString(), style: T.tSmInk),
          subtitle: Text(
            '${m['city'] ?? ''} · ${m['level'] ?? '其他'}${ticket.isEmpty ? '' : ' · $ticket'}',
            style: T.tXs,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          onTap: () => _detail(m),
        );
      },
    );
  }

  void _detail(Map<String, dynamic> m) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: T.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(T.rCard)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(T.sp4),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text((m['name'] ?? '景点').toString(), style: T.tLgStrong),
              const SizedBox(height: T.sp2),
              Text('城市：${m['city'] ?? '未知'}', style: T.tSm),
              Text('等级：${m['level'] ?? '其他'}', style: T.tSm),
              Text('票价：${(m['ticket'] ?? '').toString().isEmpty ? '未收录' : m['ticket']}',
                  style: T.tSm),
              Text('开放时间：${(m['openTime'] ?? '').toString().isEmpty ? '未收录' : m['openTime']}',
                  style: T.tSm),
              const SizedBox(height: T.sp3),
              const Text('票价与开放时间以景区公告为准，本页不代下单。', style: T.tXs),
            ],
          ),
        ),
      ),
    );
  }
}
