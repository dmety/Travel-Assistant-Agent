import 'plan_view.dart';

enum MsgRole { user, assistant }

/// 一条消息：正文 + 挂在其下的结构化卡片。
///
/// 与 Web 端一致——回复正文用打字机逐字出现，卡片随正文一起挂到同一条
/// 助手气泡里，而不是拆成多条消息。
class ChatMessage {
  final String id;
  final MsgRole role;
  final String text;
  final PlanView? plan;
  /// 只用于「我的行程」索引与地图点位：/api/chat 分支把契约信封放在 cards 里，
  /// 由 DraftBlocks 渲染卡片，但 message.plan 为空。这个字段把同一份草案留档，
  /// 不参与 message_bubble 的富内容渲染（否则「逐日草案」会出现两次）。
  final PlanView? planForTrips;
  final FoodView? food;
  final List<ToolCard> cards;
  final List<String> notices;
  final List<String> trace;
  final String time;
  final bool failed;

  const ChatMessage({
    required this.id,
    required this.role,
    required this.text,
    this.plan,
    this.planForTrips,
    this.food,
    this.cards = const [],
    this.notices = const [],
    this.trace = const [],
    required this.time,
    this.failed = false,
  });

  bool get hasRichContent =>
      plan != null || food != null || cards.isNotEmpty || notices.isNotEmpty;

  Map<String, dynamic> toJson() {
    // 优先留档索引字段；/api/agent/invoke 分支只有 plan，也照样存。
    final archive = planForTrips ?? plan;
    return {
      'id': id,
      'role': role.name,
      'text': text,
      'time': time,
      'failed': failed,
      'notices': notices,
      'trace': trace,
      // 普通聊天只存正文与提示；只有带结构化草案的助手消息多存一份
      // 行程摘要，供「我的行程」列表与详情页回放。美食卡、工具卡不落盘，
      // 避免存档随闲聊条数膨胀。
      if (archive != null) 'plan': archive.toJson(),
    };
  }

  static ChatMessage fromJson(Map<String, dynamic> m) => ChatMessage(
        id: (m['id'] ?? '').toString(),
        role: m['role'] == 'user' ? MsgRole.user : MsgRole.assistant,
        text: (m['text'] ?? '').toString(),
        time: (m['time'] ?? '').toString(),
        planForTrips: PlanView.fromJson(m['plan']),
        failed: m['failed'] == true,
        notices: (m['notices'] is List)
            ? (m['notices'] as List).map((e) => e.toString()).toList()
            : const [],
        trace: (m['trace'] is List)
            ? (m['trace'] as List).map((e) => e.toString()).toList()
            : const [],
      );
}

/// 一个会话（对应 Web 侧栏里的「最近对话」条目）。
class ChatSession {
  final String id;
  String title;
  final List<ChatMessage> messages;
  DateTime updatedAt;

  ChatSession({
    required this.id,
    required this.title,
    List<ChatMessage>? messages,
    DateTime? updatedAt,
  })  : messages = messages ?? [],
        updatedAt = updatedAt ?? DateTime.now();

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'updatedAt': updatedAt.toIso8601String(),
        'messages': messages.map((m) => m.toJson()).toList(),
      };

  static ChatSession fromJson(Map<String, dynamic> m) {
    final list = <ChatMessage>[];
    if (m['messages'] is List) {
      for (final x in (m['messages'] as List)) {
        if (x is Map) list.add(ChatMessage.fromJson(Map<String, dynamic>.from(x)));
      }
    }
    return ChatSession(
      id: (m['id'] ?? '').toString(),
      title: (m['title'] ?? '新的对话').toString(),
      messages: list,
      updatedAt: DateTime.tryParse((m['updatedAt'] ?? '').toString()) ??
          DateTime.now(),
    );
  }
}
