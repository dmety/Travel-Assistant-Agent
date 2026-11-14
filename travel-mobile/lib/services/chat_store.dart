import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../models/chat_message.dart';

/// 会话历史本地存档。
///
/// 与 Web 端把会话放 `localStorage` 等价：普通消息只存正文与提示，
/// 美食卡 / 工具卡这类结构化视图不落盘（体积大、随时可重算）；
/// 但「逐日草案」例外——它是「我的行程」列表与详情页的唯一数据源，
/// 会随该条助手消息一起存一份精简摘要，否则行程页永远是空态。
/// 这样闲聊不会撑大存档，历史行程也还能回看。
class ChatStore {
  ChatStore._();
  static final ChatStore instance = ChatStore._();

  File? _file;
  static const _cap = 20; // 最多保留 20 个会话

  Future<File> _resolve() async {
    final cached = _file;
    if (cached != null) return cached;
    final dir = await getApplicationDocumentsDirectory();
    final f = File('${dir.path}/sessions.json');
    _file = f;
    return f;
  }

  Future<List<ChatSession>> load() async {
    try {
      final f = await _resolve();
      if (!await f.exists()) return [];
      final raw = await f.readAsString();
      if (raw.trim().isEmpty) return [];
      final data = jsonDecode(raw);
      if (data is! List) return [];
      final out = <ChatSession>[];
      for (final x in data) {
        if (x is Map) out.add(ChatSession.fromJson(Map<String, dynamic>.from(x)));
      }
      out.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
      return out;
    } catch (_) {
      // 存档损坏不该导致聊天页起不来：当作没有历史
      return [];
    }
  }

  Future<void> save(List<ChatSession> sessions) async {
    try {
      final f = await _resolve();
      final sorted = [...sessions]
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
      final trimmed = sorted.take(_cap).toList();
      await f.writeAsString(
        jsonEncode(trimmed.map((s) => s.toJson()).toList()),
      );
    } catch (_) {
      // 写入失败静默：会话仍在内存里可用
    }
  }

  Future<void> clear() async {
    try {
      final f = await _resolve();
      if (await f.exists()) await f.delete();
    } catch (_) {}
  }
}
