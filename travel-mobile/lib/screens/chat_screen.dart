import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../api/client.dart';
import '../models/chat_message.dart';
import '../models/plan_view.dart';
import '../services/chat_store.dart';
import '../services/place_guess.dart';
import '../theme/tokens.dart';
import '../widgets/message_bubble.dart';

/// 主对话页：与 Web `chat.html` 同一套信息架构，换成移动端形态。
///
/// 交互链路与 Web 完全一致：
/// 1. 先 `POST /api/chat`——模型看着工具清单自己决定查什么，回答里带结构化卡片；
/// 2. 模型不可用（`reply` 为空）就回退 `POST /api/intent/parse` + `POST /api/agent/invoke`
///    这条确定性链路；
/// 3. 连意图解析都失败，再用静态地名表兜底，认不出就如实问一句，不猜城市。
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.username, required this.onLogout});

  final String username;
  final Future<void> Function() onLogout;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final _focus = FocusNode();

  List<ChatSession> _sessions = [];
  ChatSession? _current;
  bool _busy = false;
  bool _loading = true;

  /// 跨轮沿用的槽位（城市/天数/人数/预算/偏好），供下一句省略时补全。
  final Map<String, dynamic> _slots = {};

  @override
  void initState() {
    super.initState();
    _boot();
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    _focus.dispose();
    super.dispose();
  }

  Future<void> _boot() async {
    final loaded = await ChatStore.instance.load();
    setState(() {
      _sessions = loaded;
      _current = loaded.isNotEmpty ? loaded.first : _newSession();
      if (loaded.isEmpty) _sessions = [_current!];
      _loading = false;
    });
  }

  ChatSession _newSession() => ChatSession(
        id: DateTime.now().microsecondsSinceEpoch.toString(),
        title: '新的对话',
      );

  // ---------------- 数据写入 ----------------

  void _addUser(String text) {
    _current!.messages.add(ChatMessage(
      id: DateTime.now().microsecondsSinceEpoch.toString(),
      role: MsgRole.user,
      text: text,
      time: _now(),
    ));
    // 首句用作会话标题，方便侧栏识别
    if (_current!.title == '新的对话') {
      _current!.title = text.length > 14 ? '${text.substring(0, 14)}…' : text;
    }
    _current!.updatedAt = DateTime.now();
    setState(() {});
    _scrollToBottom();
  }

  void _addAssistant(
    String text, {
    PlanView? plan,
    FoodView? food,
    List<ToolCard> cards = const [],
    List<String> notices = const [],
    List<String> trace = const [],
  }) {
    _current!.messages.add(ChatMessage(
      id: DateTime.now().microsecondsSinceEpoch.toString(),
      role: MsgRole.assistant,
      text: text,
      plan: plan,
      food: food,
      cards: cards,
      notices: notices,
      trace: trace,
      time: _now(),
    ));
    _current!.updatedAt = DateTime.now();
    setState(() {});
    _scrollToBottom();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scroll.hasClients) return;
      _scroll.animateTo(
        _scroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOut,
      );
    });
  }

  Future<void> _persist() async {
    _sessions.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    await ChatStore.instance.save(_sessions);
  }

  String _now() {
    final d = DateTime.now();
    String two(int n) => n < 10 ? '0$n' : '$n';
    return '${two(d.hour)}:${two(d.minute)}';
  }

  List<Map<String, String>> _history() {
    final msgs = _current?.messages ?? const <ChatMessage>[];
    final recent = msgs.length > 8 ? msgs.sublist(msgs.length - 8) : msgs;
    return recent
        .where((m) => m.text.trim().isNotEmpty)
        .map((m) => {
              'role': m.role == MsgRole.user ? 'user' : 'assistant',
              'text': m.text,
            })
        .toList();
  }

  // ---------------- 发送主链路 ----------------

  Future<void> _send(String raw, {Map<String, dynamic>? extra}) async {
    final text = raw.trim();
    if (text.isEmpty || _busy) return;
    _input.clear();
    _addUser(text);
    setState(() => _busy = true);

    try {
      // 1) 模型驱动的对话（会自己在工具里规划/查景点/查天气）
      if (extra == null) {
        final handled = await _tryChat(text);
        if (handled) return;
      }
      // 2) 确定性链路：解析 → 编排
      await _deterministic(text, extra: extra);
    } on AuthExpiredException {
      await widget.onLogout();
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        await widget.onLogout();
      } else {
        _addAssistant('网络或服务异常：${e.message ?? '连接失败'}');
      }
    } on ApiException catch (e) {
      _addAssistant('请求失败：$e');
    } catch (e) {
      _addAssistant('这条没处理成功：$e');
    } finally {
      if (mounted) setState(() => _busy = false);
      await _persist();
      _scrollToBottom();
    }
  }

  /// 模型驱动：`reply` 非空即算处理完（卡片可能是行程/天气/景点/投票）。
  Future<bool> _tryChat(String text) async {
    Map<String, dynamic> r;
    try {
      r = await ApiClient.instance.chat(
        message: text,
        history: _history(),
        state: _slots,
      );
    } catch (_) {
      return false; // 任何异常都回退确定性链路，不让用户卡住
    }
    final reply = (r['reply'] as String?)?.trim();
    if (reply == null || reply.isEmpty) return false;

    final cards = ToolCard.listFrom(r['cards']);
    final traces = <String>[];
    if (r['trace'] is List) {
      for (final t in (r['trace'] as List)) {
        if (t is Map) {
          final label = t['label']?.toString();
          if (label != null && label.isNotEmpty) traces.add(label);
        }
      }
    }
    // 工具已经规划出行程时，卡片里会带 envelope；这里同时把结论并入正文下方
    _absorbState(r['state']);
    _addAssistant(reply, cards: cards, trace: traces);
    return true;
  }

  /// 确定性链路：意图解析 → 编排 → 渲染。
  Future<void> _deterministic(
    String text, {
    Map<String, dynamic>? extra,
  }) async {
    IntentView? u;
    try {
      u = await ApiClient.instance.parseIntent(
        text: text,
        history: _history(),
        state: _slots,
      );
    } catch (_) {
      u = null; // 解析服务不可用，下面走静态表兜底
    }

    String? dest;
    var days = u?.days;
    var alts = u?.alternatives ?? const <String>[];
    String? note = u?.note;

    if (u != null && u.resolved && u.city != null) {
      dest = u.city;
    } else {
      // 解析没落到城市：先用静态表兜底，仍认不出就如实问
      dest = guessPlace(text);
      days ??= guessDays(text);
      if (dest == null) {
        _addAssistant(
          u?.note?.isNotEmpty == true
              ? u!.note!
              : '没认出这句话里的目的地。告诉我想去哪座城市，比如「杭州 3 天」。',
        );
        _addDestCard(alts);
        return;
      }
      note ??= '按「$dest」检索';
    }

    final ctx = <String, dynamic>{};
    if (u != null && u.resolved) {
      ctx.addAll(u.toContext(extra: extra));
    } else {
      ctx['destination'] = dest;
      if (days != null) ctx['days'] = days;
      if (extra != null) ctx.addAll(extra);
    }
    // 兜底路径下 context 可能没带 destination（例如解析出了 city 但 toContext 为空）
    ctx.putIfAbsent('destination', () => dest);

    _absorbSlots(ctx);

    final intent = _pickIntent(u, text);
    final env = await ApiClient.instance.invoke(intent: intent, context: ctx);

    final status = (env['status'] ?? '').toString();
    if (status == 'REFUSED') {
      _addAssistant('这轮请求被拒绝了：${_adviceOf(env) ?? '缺必要信息'}');
      return;
    }

    if (intent == 'FOOD_RECOMMEND') {
      final food = FoodView.fromEnvelope(env);
      if (food == null || (food.items.isEmpty && food.advice == null)) {
        _addAssistant('附近没查到可推荐的餐馆，换个位置或放宽预算再试。');
        return;
      }
      final head = food.advice ??
          (food.items.isEmpty
              ? '附近没有检索到餐馆。'
              : '在附近找到 ${food.items.length} 家可以去的餐馆，按距离与本地优先排序。');
      _addAssistant(head, food: food, notices: food.notices);
      return;
    }

    final view = PlanView.fromEnvelope(env);
    if (view == null) {
      _addAssistant(_adviceOf(env) ?? '这轮没有返回结构化草案。');
      return;
    }

    final head = (view.advice?.trim().isNotEmpty ?? false)
        ? view.advice!
        : _headline(view, dest ?? '', note);
    _addAssistant(
      head,
      plan: view,
      notices: view.notices,
    );
  }

  /// 意图判定：吃 → 美食；其余走行程。
  /// Web 端由后端 intent 决定，这里对后端给的值做一层校验式兜底。
  String _pickIntent(IntentView? u, String text) {
    final declared = u?.intent ?? '';
    if (declared == 'FOOD_RECOMMEND') return 'FOOD_RECOMMEND';
    if (declared == 'TICKET_ADVICE') return 'TICKET_ADVICE';
    if (RegExp(r'吃|美食|小吃|餐馆|饭|餐厅|夜宵|早点').hasMatch(text)) {
      return 'FOOD_RECOMMEND';
    }
    return 'PLAN_DRAFT';
  }

  String? _adviceOf(Map<String, dynamic> env) {
    final r = env['result'];
    if (r is Map) {
      final a = r['advice'];
      if (a != null && a.toString().trim().isNotEmpty) return a.toString();
    }
    return null;
  }

  /// 没有模型正文时，用检索结果拼一句如实开场（与 Web `headline` 同义）。
  String _headline(PlanView v, String place, String? note) {
    final parts = <String>[];
    if (v.candidates.isNotEmpty) {
      parts.add('已从景区知识库检索到「$place」的 ${v.candidates.length} 个候选景点，按等级与评分排序。');
    } else {
      parts.add('知识库里没有检索到「$place」的景点记录。');
    }
    if (v.dailySchedules.isNotEmpty) {
      parts.add('按 ${v.filledDays} 天给出逐日草案，只排有可查景点的天；具体游览时刻没有依据，留给你确认。');
      if (v.trimmedDays > 0) {
        parts.add('还有 ${v.trimmedDays} 天没有可查景点，没有硬凑空天。');
      }
    } else {
      parts.add('这轮没有可排的候选，告诉我玩几天、或换个目的地再试。');
    }
    final high = v.checks.where((c) => c.isHigh).length;
    if (high > 0) parts.add('有 $high 项需要先处理（见下方提示）。');
    if (note != null && note.isNotEmpty) parts.add(note);
    return parts.join('');
  }

  void _addDestCard(List<String> alternatives) {
    if (alternatives.isEmpty) return;
    _addAssistant(
      '换成这些目的地试试：',
      cards: [
        ToolCard(
          type: 'dests',
          intent: 'DEST',
          data: alternatives.take(6).map((c) => {'name': c}).toList(),
        ),
      ],
    );
  }

  void _absorbSlots(Map<String, dynamic> ctx) {
    for (final k in ['destination', 'days', 'travelers', 'budget', 'preferences']) {
      if (ctx[k] != null) _slots[k == 'destination' ? 'city' : k] = ctx[k];
    }
  }

  void _absorbState(dynamic state) {
    if (state is! Map) return;
    for (final k in ['city', 'days', 'travelers', 'budget', 'preferences']) {
      if (state[k] != null) _slots[k] = state[k];
    }
  }

  // ---------------- 会话管理 ----------------

  void _switchTo(ChatSession s) {
    setState(() => _current = s);
    Navigator.of(context).pop(); // 关抽屉
    _scrollToBottom();
  }

  void _newChat() {
    if (_busy) return;
    final s = _newSession();
    setState(() {
      _sessions.insert(0, s);
      _current = s;
    });
    _persist();
  }

  Future<void> _deleteSession(ChatSession s) async {
    setState(() {
      _sessions.remove(s);
      if (_current?.id == s.id) {
        _current = _sessions.isNotEmpty ? _sessions.first : _newSession();
        if (_sessions.isEmpty) _sessions = [_current!];
      }
    });
    await _persist();
  }

  // ---------------- 视图 ----------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      drawer: _drawer(),
      appBar: AppBar(
        titleSpacing: T.sp4,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              _current?.title ?? '旅行助手',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: T.fsMd,
                color: T.ink,
                fontWeight: T.wStrong,
              ),
            ),
            const Text(
              '基于景区知识库 + 实时地图',
              style: TextStyle(fontSize: T.fsXs, color: T.text3),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: '新对话',
            onPressed: _busy ? null : _newChat,
            icon: const Icon(Icons.add_comment_outlined, size: 22),
            color: T.text2,
          ),
          const SizedBox(width: T.sp1),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
          : Column(
              children: [
                Expanded(child: _messagesArea()),
                _composer(),
              ],
            ),
    );
  }

  Widget _messagesArea() {
    final msgs = _current?.messages ?? const <ChatMessage>[];
    if (msgs.isEmpty) return _emptyState();

    // 逐条推进「最近一条用户原话」，补问卡拼句时以它为基准。
    final items = <Widget>[];
    var lastUser = '';
    for (final m in msgs) {
      if (m.role == MsgRole.user && m.text.trim().isNotEmpty) {
        lastUser = m.text.trim();
      }
      items.add(MessageBubble(
        key: ValueKey(m.id),
        message: m,
        baseText: lastUser,
        busy: _busy,
        onQaSubmit: (text, extra) => _send(text, extra: extra),
        onQaSkip: () {},
        onPickPlace: (place) => _send(place),
      ));
    }
    if (_busy) items.add(const _TypingRow());

    return ListView(
      controller: _scroll,
      padding: const EdgeInsets.fromLTRB(T.sp4, T.sp4, T.sp4, T.sp2),
      children: items,
    );
  }

  /// 空状态即功能入口：一句标题 + 四张可直接点的建议（与 Web 一致）。
  Widget _emptyState() {
    const suggests = [
      ('云南 5 天行程', '昆明 · 大理 · 丽江，含每日安排', '帮我规划云南 5 天行程'),
      ('武汉两日游', '黄鹤楼、东湖与本地小吃', '武汉周末两天怎么玩'),
      ('查景点信息', '开放时间、门票与坐标核验', '黄鹤楼开放时间和门票'),
      ('定制节奏', '按人群调整强度与通勤', '带老人小孩，行程别太赶'),
    ];

    return ListView(
      padding: const EdgeInsets.fromLTRB(T.sp4, T.sp8, T.sp4, T.sp4),
      children: [
        Center(
          child: Container(
            width: 56,
            height: 56,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: T.primarySoft,
              borderRadius: BorderRadius.circular(T.rCard),
              border: Border.all(color: T.primaryLine),
            ),
            child: const Text(
              '旅',
              style: TextStyle(
                fontSize: T.fsLg,
                color: T.primary,
                fontWeight: T.wStrong,
              ),
            ),
          ),
        ),
        const SizedBox(height: T.sp4),
        const Text(
          '今天想去哪儿？',
          textAlign: TextAlign.center,
          style: T.tXlStrong,
        ),
        const SizedBox(height: T.sp2),
        const Text(
          '告诉我目的地、天数或偏好，我会结合景区知识库与实时路况，'
          '给出可执行的每日行程。',
          textAlign: TextAlign.center,
          style: T.tSm,
        ),
        const SizedBox(height: T.sp6),
        for (final s in suggests)
          Padding(
            padding: const EdgeInsets.only(bottom: T.sp2),
            child: _SuggestTile(
              title: s.$1,
              subtitle: s.$2,
              onTap: () => _send(s.$3),
            ),
          ),
        const SizedBox(height: T.sp4),
        const Text(
          '行程建议仅供参考，门票与开放时间以景区公告为准。',
          textAlign: TextAlign.center,
          style: T.tXs,
        ),
      ],
    );
  }

  Widget _composer() {
    final canSend = _input.text.trim().isNotEmpty && !_busy;
    return Container(
      decoration: const BoxDecoration(
        color: T.surface,
        border: Border(top: BorderSide(color: T.line)),
      ),
      padding: EdgeInsets.fromLTRB(
        T.sp4,
        T.sp2,
        T.sp4,
        T.sp2 + MediaQuery.of(context).padding.bottom * 0.5,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Container(
                  constraints: const BoxConstraints(maxHeight: 120),
                  decoration: BoxDecoration(
                    color: T.surface2,
                    borderRadius: BorderRadius.circular(T.rCtl),
                    border: Border.all(color: T.lineStrong),
                  ),
                  padding: const EdgeInsets.symmetric(
                    horizontal: T.sp3,
                    vertical: T.sp1,
                  ),
                  child: TextField(
                    controller: _input,
                    focusNode: _focus,
                    minLines: 1,
                    maxLines: 4,
                    textInputAction: TextInputAction.newline,
                    keyboardType: TextInputType.multiline,
                    style: T.tSmInk,
                    onChanged: (_) => setState(() {}),
                    onSubmitted: (_) {
                      if (canSend) _send(_input.text);
                    },
                    decoration: const InputDecoration(
                      isDense: true,
                      border: InputBorder.none,
                      hintText: '说说目的地、天数和偏好…',
                      hintStyle: TextStyle(
                        fontSize: T.fsSm,
                        color: T.text3,
                      ),
                      contentPadding: EdgeInsets.symmetric(vertical: 10),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: T.sp2),
              _SendButton(
                enabled: canSend,
                onTap: () => _send(_input.text),
              ),
            ],
          ),
          const SizedBox(height: T.sp1),
          Row(
            children: [
              const Text('点右侧 ↑ 发送', style: T.tXs),
              const Spacer(),
              Text(
                _current?.messages.isNotEmpty == true ? '${_current!.messages.length} 条' : '',
                style: T.tXs,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _drawer() {
    return Drawer(
      backgroundColor: T.surface2,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.all(T.sp4),
              child: Row(
                children: [
                  Container(
                    width: 32,
                    height: 32,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: T.primarySoft,
                      borderRadius: BorderRadius.circular(T.rCtl),
                      border: Border.all(color: T.primaryLine),
                    ),
                    child: const Text(
                      '旅',
                      style: TextStyle(
                        fontSize: T.fsSm,
                        color: T.primary,
                        fontWeight: T.wStrong,
                      ),
                    ),
                  ),
                  const SizedBox(width: T.sp2),
                  const Text(
                    '旅行助手',
                    style: TextStyle(
                      fontSize: T.fsMd,
                      color: T.ink,
                      fontWeight: T.wStrong,
                    ),
                  ),
                ],
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: T.sp4),
              child: Text('最近对话', style: T.tLabel),
            ),
            const SizedBox(height: T.sp2),
            Expanded(
              child: _sessions.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.all(T.sp4),
                      child: Text(
                        '还没有对话。问一句「武汉两天怎么玩」就开始。',
                        style: T.tXs,
                      ),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.symmetric(horizontal: T.sp2),
                      itemCount: _sessions.length,
                      itemBuilder: (_, i) {
                        final s = _sessions[i];
                        final active = s.id == _current?.id;
                        return _SessionTile(
                          title: s.title,
                          count: s.messages.length,
                          active: active,
                          onTap: () => _switchTo(s),
                          onDelete: () => _deleteSession(s),
                        );
                      },
                    ),
            ),
            const Divider(color: T.line, height: 1),
            Padding(
              padding: const EdgeInsets.all(T.sp4),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.username,
                          style: T.tSmInk.copyWith(fontWeight: T.wStrong),
                        ),
                        const Text('已登录', style: T.tXs),
                      ],
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () async {
                      Navigator.of(context).pop();
                      await widget.onLogout();
                    },
                    icon: const Icon(Icons.logout, size: 16),
                    label: const Text('退出'),
                    style: TextButton.styleFrom(
                      foregroundColor: T.text2,
                      textStyle: const TextStyle(fontSize: T.fsXs),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 建议卡：白底描边，标题 14/600 + 说明 12/灰，点击直接发问。
class _SuggestTile extends StatelessWidget {
  const _SuggestTile({
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(T.rCtl),
      child: Container(
        padding: const EdgeInsets.symmetric(
          horizontal: T.sp3,
          vertical: T.sp3,
        ),
        decoration: T.cardDeco(radius: T.rCtl),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: T.tSmInk.copyWith(fontWeight: T.wStrong),
                  ),
                  const SizedBox(height: 2),
                  Text(subtitle, style: T.tXs),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, size: 18, color: T.text3),
          ],
        ),
      ),
    );
  }
}

class _SessionTile extends StatelessWidget {
  const _SessionTile({
    required this.title,
    required this.count,
    required this.active,
    required this.onTap,
    required this.onDelete,
  });

  final String title;
  final int count;
  final bool active;
  final VoidCallback onTap;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 2),
      decoration: BoxDecoration(
        color: active ? T.primarySoft : Colors.transparent,
        borderRadius: BorderRadius.circular(T.rCtl),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(T.rCtl),
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: T.sp3,
            vertical: T.sp2 + 2,
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: T.tSmInk.copyWith(
                        fontWeight: active ? T.wStrong : T.wBody,
                        color: active ? T.primary : T.ink,
                      ),
                    ),
                    const SizedBox(height: 1),
                    Text('$count 条消息', style: T.tXs),
                  ],
                ),
              ),
              IconButton(
                onPressed: onDelete,
                icon: const Icon(Icons.close, size: 15),
                color: T.text3,
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 打字占位：三个点呼吸，替代 Web 的 typingMsg。
class _TypingRow extends StatefulWidget {
  const _TypingRow();

  @override
  State<_TypingRow> createState() => _TypingRowState();
}

class _TypingRowState extends State<_TypingRow>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: T.sp3),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: T.primarySoft,
              borderRadius: BorderRadius.circular(T.rCtl),
              border: Border.all(color: T.primaryLine),
            ),
            child: const Text(
              '旅',
              style: TextStyle(
                fontSize: T.fsXs,
                color: T.primary,
                fontWeight: T.wStrong,
              ),
            ),
          ),
          const SizedBox(width: T.sp2),
          Container(
            padding: const EdgeInsets.symmetric(
              horizontal: T.sp3,
              vertical: T.sp3,
            ),
            decoration: T.cardDeco(),
            child: AnimatedBuilder(
              animation: _c,
              builder: (_, _) {
                // 三个点按相位错开明暗，表示「正在处理」
                return Row(
                  mainAxisSize: MainAxisSize.min,
                  children: List.generate(3, (i) {
                    final phase = (_c.value + i * 0.22) % 1.0;
                    final o = 0.25 + 0.55 * (1 - (phase - 0.5).abs() * 2).clamp(0.0, 1.0);
                    return Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: Container(
                        width: 6,
                        height: 6,
                        decoration: BoxDecoration(
                          color: T.text3.withValues(alpha: o),
                          shape: BoxShape.circle,
                        ),
                      ),
                    );
                  }),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _SendButton extends StatelessWidget {
  const _SendButton({required this.enabled, required this.onTap});
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: '发送',
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(T.rCtl),
        child: Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: enabled ? T.primary : T.surface2,
            borderRadius: BorderRadius.circular(T.rCtl),
            border: Border.all(color: enabled ? T.primary : T.lineStrong),
          ),
          child: Icon(
            Icons.arrow_upward_rounded,
            size: 20,
            color: enabled ? Colors.white : T.text3,
          ),
        ),
      ),
    );
  }
}
