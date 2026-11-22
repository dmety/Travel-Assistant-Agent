import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';
import '../widgets/collab_sheet.dart';

/// 服务端已保存的行程列表（区别于「行程」页里的本地草案会话）。
///
/// 点开进协作抽屉（设计稿 06 屏），可导出文本摘要或删除。
/// 本地草案未确认前不会出现在这里——与「草案未保存，不写库」一致。
class SavedTripsScreen extends StatefulWidget {
  const SavedTripsScreen({super.key});

  @override
  State<SavedTripsScreen> createState() => _SavedTripsScreenState();
}

class _SavedTripsScreenState extends State<SavedTripsScreen> {
  late Future<List<dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    setState(() => _future = ApiClient.instance.itineraries());
  }

  Map<String, dynamic> _map(dynamic row) =>
      row is Map ? Map<String, dynamic>.from(row) : <String, dynamic>{};

  Future<void> _delete(String id) async {
    try {
      await ApiClient.instance.deleteItinerary(id);
      _reload();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('删除失败：$e')),
      );
    }
  }

  Future<void> _export(String id) async {
    try {
      final text = await ApiClient.instance.exportText(id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: T.surface,
          title: const Text('行程摘要', style: T.tSmInk),
          content: SingleChildScrollView(
            child: Text(text.toString(), style: T.tSm),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('关闭'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('导出失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('已保存的行程',
            style: TextStyle(
                fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: FutureBuilder<List<dynamic>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator(strokeWidth: 2));
          }
          if (snap.hasError) {
            return Padding(
              padding: const EdgeInsets.all(T.sp4),
              child: Text('加载失败：${snap.error}', style: T.tSm),
            );
          }
          final rows = snap.data ?? const <dynamic>[];
          if (rows.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(T.sp4),
              child: Text('还没有已保存的行程。草案要确认之后才写库。',
                  style: T.tXs),
            );
          }
          return ListView.separated(
            padding: const EdgeInsets.all(T.sp4),
            itemCount: rows.length,
            separatorBuilder: (_, __) => const SizedBox(height: T.sp2),
            itemBuilder: (context, i) {
              final m = _map(rows[i]);
              final id = (m['id'] ?? '').toString();
              final title = (m['title'] ?? '未命名行程').toString();
              final version = m['version'];
              return Container(
                padding: const EdgeInsets.all(T.sp3),
                decoration: T.cardDeco(),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: T.tSmInk),
                    const SizedBox(height: T.sp1),
                    Text('版本 ${version ?? '—'} · ${m['archived'] == true ? '已归档' : '进行中'}',
                        style: T.tXs),
                    const SizedBox(height: T.sp1),
                    Wrap(
                      spacing: T.sp1,
                      children: [
                        TextButton(
                          onPressed: id.isEmpty
                              ? null
                              : () => CollabSheet.show(context, id),
                          child: const Text('待定与协作', style: T.tXs),
                        ),
                        TextButton(
                          onPressed: id.isEmpty ? null : () => _export(id),
                          child: const Text('导出摘要', style: T.tXs),
                        ),
                        TextButton(
                          onPressed: id.isEmpty ? null : () => _delete(id),
                          child: const Text('删除', style: T.tXs),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }
}
