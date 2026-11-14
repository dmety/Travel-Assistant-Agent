import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 06 屏：底部滑出抽屉，四个 pane——候选 / 待规划 / 成员 / 预算体检。
///
/// 数据全部来自真实接口，加载中、空态、失败都如实显示，不用样例数据填坑。
/// 并列候选时系统不替用户决定（设计稿：赤陶只给「需要人决断」处）。
class CollabSheet extends StatelessWidget {
  const CollabSheet({super.key, required this.itineraryId});

  final String itineraryId;

  static Future<void> show(BuildContext context, String itineraryId) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: T.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(T.rCard)),
      ),
      builder: (_) => SizedBox(
        height: MediaQuery.of(context).size.height * 0.72,
        child: CollabSheet(itineraryId: itineraryId),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: T.sp2),
          Center(
            child: Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: T.lineStrong,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
          ),
          const Padding(
            padding: EdgeInsets.fromLTRB(T.sp4, T.sp2, T.sp4, 0),
            child: Text('待定与协作', style: T.tLabel),
          ),
          const TabBar(
            labelColor: T.primary,
            unselectedLabelColor: T.text3,
            indicatorColor: T.primary,
            labelStyle: TextStyle(fontSize: T.fsXs),
            tabs: [
              Tab(text: '候选'),
              Tab(text: '待规划'),
              Tab(text: '成员'),
              Tab(text: '体检'),
            ],
          ),
          const Divider(height: 1, color: T.line),
          Expanded(
            child: TabBarView(
              children: [
                _CandidatesPane(id: itineraryId, onlyPending: false),
                _CandidatesPane(id: itineraryId, onlyPending: true),
                _MembersPane(id: itineraryId),
                _BudgetPane(id: itineraryId),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 后端返回可能是裸 List，也可能是带 `candidates` 键的看板对象。
List<dynamic> _rows(dynamic data, String key) {
  if (data is List) return data;
  if (data is Map && data[key] is List) return data[key] as List<dynamic>;
  return const <dynamic>[];
}

Map<String, dynamic> _map(dynamic row) =>
    row is Map ? Map<String, dynamic>.from(row) : <String, dynamic>{};

/// 通用的「加载 / 出错 / 空」外壳，四个 pane 共用。
class _AsyncPane extends StatelessWidget {
  const _AsyncPane({required this.future, required this.builder});

  final Future<dynamic> future;
  final Widget Function(List<dynamic> rows) builder;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<dynamic>(
      future: future,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(T.sp4),
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          );
        }
        if (snap.hasError) {
          return Padding(
            padding: const EdgeInsets.all(T.sp4),
            child: Text('加载失败：${snap.error}', style: T.tXs),
          );
        }
        final rows = _rows(snap.data, 'candidates');
        if (rows.isEmpty) {
          return const Padding(
            padding: EdgeInsets.all(T.sp4),
            child: Text('这里还没有内容', style: T.tXs),
          );
        }
        return builder(rows);
      },
    );
  }
}

class _CandidatesPane extends StatefulWidget {
  const _CandidatesPane({required this.id, required this.onlyPending});

  final String id;
  final bool onlyPending;

  @override
  State<_CandidatesPane> createState() => _CandidatesPaneState();
}

class _CandidatesPaneState extends State<_CandidatesPane> {
  late Future<dynamic> _future;
  String? _pendingId;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    setState(() => _future = ApiClient.instance.candidates(widget.id));
  }

  Future<void> _vote(String candidateId, String choice) async {
    setState(() => _pendingId = candidateId);
    try {
      await ApiClient.instance.vote(widget.id, candidateId, choice);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已记下你的选择')),
      );
      _reload();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('投票失败：$e')),
      );
    } finally {
      if (mounted) setState(() => _pendingId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    return _AsyncPane(
      future: _future,
      builder: (all) {
        final rows = widget.onlyPending
            ? all.where((r) {
                final m = _map(r);
                final status = (m['status'] ?? '').toString().toUpperCase();
                return status != 'APPLIED' && m['applied'] != true;
              }).toList()
            : all;
        if (rows.isEmpty) {
          return const Padding(
            padding: EdgeInsets.all(T.sp4),
            child: Text('没有待规划的候选', style: T.tXs),
          );
        }
        return ListView.separated(
          padding: const EdgeInsets.all(T.sp4),
          itemCount: rows.length,
          separatorBuilder: (_, _) => const Divider(height: 1, color: T.line),
          itemBuilder: (context, i) {
            final m = _map(rows[i]);
            final cid = (m['id'] ?? '').toString();
            final name = (m['name'] ?? '未命名候选').toString();
            final kind = (m['kind'] ?? '').toString();
            final votes = m['voteSummary'] ?? m['votes'];
            final busy = _pendingId == cid;
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: T.sp2),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(child: Text(name, style: T.tSmInk)),
                      if (kind.isNotEmpty)
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: T.claySoft,
                            borderRadius: BorderRadius.circular(3),
                          ),
                          child: Text(kind, style: T.tXs),
                        ),
                    ],
                  ),
                  const SizedBox(height: T.sp1),
                  Text('票数 ${votes ?? '—'}', style: T.tXs),
                  if (!widget.onlyPending && cid.isNotEmpty)
                    Wrap(
                      spacing: T.sp1,
                      children: [
                        _voteChip(cid, '支持', 'support', busy),
                        _voteChip(cid, '反对', 'oppose', busy),
                        _voteChip(cid, '弃权', 'abstain', busy),
                      ],
                    ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _voteChip(String cid, String label, String value, bool busy) {
    return TextButton(
      onPressed: busy ? null : () => _vote(cid, value),
      style: TextButton.styleFrom(
        foregroundColor: T.primary,
        padding: const EdgeInsets.symmetric(horizontal: T.sp2),
        minimumSize: const Size(0, 30),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      child: Text(label, style: T.tXs),
    );
  }
}

class _MembersPane extends StatefulWidget {
  const _MembersPane({required this.id});

  final String id;

  @override
  State<_MembersPane> createState() => _MembersPaneState();
}

class _MembersPaneState extends State<_MembersPane> {
  final _input = TextEditingController();
  late Future<dynamic> _members;
  late Future<dynamic> _comments;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    setState(() {
      _members = ApiClient.instance.members(widget.id);
      _comments = ApiClient.instance.comments(widget.id);
    });
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    try {
      await ApiClient.instance.addComment(widget.id, text);
      _input.clear();
      _reload();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('留言失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Expanded(
          child: FutureBuilder<dynamic>(
            future: _members,
            builder: (context, snap) {
              final rows = _rows(snap.data, 'members');
              if (rows.isEmpty) {
                return const Padding(
                  padding: EdgeInsets.all(T.sp4),
                  child: Text('还没有成员', style: T.tXs),
                );
              }
              return ListView(
                padding: const EdgeInsets.all(T.sp4),
                children: [
                  const Text('成员', style: T.tLabel),
                  const SizedBox(height: T.sp2),
                  ...rows.map((r) {
                    final m = _map(r);
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.person_outline,
                          size: 18, color: T.text3),
                      title: Text(
                        (m['username'] ?? m['name'] ?? '成员').toString(),
                        style: T.tSm,
                      ),
                      subtitle: Text(
                        (m['role'] ?? m['editSections'] ?? '').toString(),
                        style: T.tXs,
                      ),
                    );
                  }),
                  const Divider(color: T.line),
                  const Text('留言', style: T.tLabel),
                  const SizedBox(height: T.sp2),
                  FutureBuilder<dynamic>(
                    future: _comments,
                    builder: (context, csnap) {
                      final cs = _rows(csnap.data, 'comments');
                      if (cs.isEmpty) {
                        return const Text('还没有留言', style: T.tXs);
                      }
                      return Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: cs.map((c) {
                          final m = _map(c);
                          return Padding(
                            padding: const EdgeInsets.only(bottom: T.sp2),
                            child: Text(
                              '${m['username'] ?? ''}：${m['content'] ?? m['text'] ?? ''}',
                              style: T.tSm,
                            ),
                          );
                        }).toList(),
                      );
                    },
                  ),
                ],
              );
            },
          ),
        ),
        Container(
          padding: const EdgeInsets.fromLTRB(T.sp4, T.sp2, T.sp2, T.sp2),
          decoration: const BoxDecoration(
            border: Border(top: BorderSide(color: T.line)),
          ),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _input,
                  decoration: const InputDecoration(
                    hintText: '给同伴留一句话',
                    border: InputBorder.none,
                    isDense: true,
                  ),
                  style: T.tSm,
                  onSubmitted: (_) => _send(),
                ),
              ),
              IconButton(
                tooltip: '发送留言',
                icon: const Icon(Icons.send_outlined, size: 18, color: T.primary),
                onPressed: _send,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _BudgetPane extends StatelessWidget {
  const _BudgetPane({required this.id});

  final String id;

  @override
  Widget build(BuildContext context) {
    final client = ApiClient.instance;
    return FutureBuilder<List<dynamic>>(
      future: Future.wait<dynamic>([
        client.budgetAnalysis(id),
        client.checks(id),
      ]),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(T.sp4),
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          );
        }
        if (snap.hasError) {
          return Padding(
            padding: const EdgeInsets.all(T.sp4),
            child: Text('体检加载失败：${snap.error}', style: T.tXs),
          );
        }
        final budget = _map(snap.data!.isNotEmpty ? snap.data![0] : null);
        final checks = _rows(snap.data!.length > 1 ? snap.data![1] : null, 'warnings');
        final total = budget['total'] ?? budget['totalCny'] ?? budget['amount'];
        return ListView(
          padding: const EdgeInsets.all(T.sp4),
          children: [
            const Text('预算', style: T.tLabel),
            const SizedBox(height: T.sp2),
            Text(
              total == null ? '预算无法判定' : '合计 $total',
              style: T.tSmInk,
            ),
            const SizedBox(height: T.sp3),
            const Text('行程体检', style: T.tLabel),
            const SizedBox(height: T.sp2),
            if (checks.isEmpty)
              const Text('没有需要处理的提示', style: T.tXs)
            else
              ...checks.map((c) {
                final m = _map(c);
                return Padding(
                  padding: const EdgeInsets.only(bottom: T.sp2),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.info_outline, size: 14, color: T.warn),
                      const SizedBox(width: T.sp1),
                      Expanded(
                        child: Text(
                          (m['message'] ?? m['code'] ?? '').toString(),
                          style: T.tXs,
                        ),
                      ),
                    ],
                  ),
                );
              }),
          ],
        );
      },
    );
  }
}
