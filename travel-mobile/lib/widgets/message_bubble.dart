import 'package:flutter/material.dart';

import '../models/chat_message.dart';
import '../theme/tokens.dart';
import 'brand_avatar.dart';
import 'draft_blocks.dart';

/// 一条消息气泡。
///
/// 用户侧：主色实底 + 白字，右对齐，右上角收一刀（气泡指向发送者）。
/// 助手侧：白底描边 + 一级阴影，左对齐，卡片挂在正文下方。
/// 辅助信息（时间、查了哪些工具）用 12px 灰字，不参与层级竞争。
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    required this.onQaSubmit,
    required this.onQaSkip,
    this.onPickPlace,
    this.baseText = '',
    this.busy = false,
  });

  final ChatMessage message;
  final void Function(String text, Map<String, dynamic> extra) onQaSubmit;
  final VoidCallback onQaSkip;

  /// 本会话中最近一条用户原话。补问卡拼句以它为基准，
  /// 直接用助手正文会把「已按景区知识库…」当成目的地再发一遍。
  final String baseText;

  /// 点备选城市时回发一句话。
  final void Function(String place)? onPickPlace;
  final bool busy;

  bool get _isUser => message.role == MsgRole.user;

  @override
  Widget build(BuildContext context) {
    final maxWidth = MediaQuery.of(context).size.width * 0.88;

    final bubble = Container(
      constraints: BoxConstraints(maxWidth: maxWidth),
      padding: const EdgeInsets.fromLTRB(T.sp3, T.sp2 + 2, T.sp3, T.sp3),
      decoration: BoxDecoration(
        color: _isUser ? T.primary : T.surface,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(T.rCard),
          topRight: Radius.circular(_isUser ? T.rSm : T.rCard),
          bottomLeft: const Radius.circular(T.rCard),
          bottomRight: const Radius.circular(T.rCard),
        ),
        border: _isUser ? null : Border.all(color: T.line),
        boxShadow: _isUser ? null : T.shadow1,
      ),
      child: Column(
        crossAxisAlignment:
            _isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          if (message.text.isNotEmpty)
            SelectableText(
              message.text,
              style: TextStyle(
                fontSize: T.fsSm,
                height: 1.65,
                color: _isUser ? Colors.white : T.ink,
              ),
            ),
          if (message.failed && message.text.isEmpty)
            Row(
              children: [
                const Icon(Icons.error_outline, size: 16, color: T.err),
                const SizedBox(width: T.sp1),
                Text(
                  '这条没发出去',
                  style: TextStyle(fontSize: T.fsXs, color: T.err),
                ),
              ],
            ),
          if (!_isUser) _rich(),
          if (message.time.isNotEmpty) ...[
            const SizedBox(height: T.sp1),
            Text(
              message.time,
              style: TextStyle(
                fontSize: T.fsXs,
                height: 1.4,
                color: _isUser ? const Color(0xCCFFFFFF) : T.text3,
              ),
            ),
          ],
        ],
      ),
    );

    return Padding(
      padding: const EdgeInsets.only(bottom: T.sp3),
      child: Row(
        mainAxisAlignment:
            _isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!_isUser) ...[
            const Padding(
              padding: EdgeInsets.only(top: 2),
              child: BrandAvatar(),
            ),
            const SizedBox(width: T.sp2),
          ],
          Flexible(child: bubble),
        ],
      ),
    );
  }

  Widget _rich() {
    final parts = <Widget>[];
    if (message.plan != null) {
      parts.add(DraftBlocks(
        view: message.plan!,
        baseText: _baseText(),
        onQaSubmit: onQaSubmit,
        onQaSkip: onQaSkip,
        busy: busy,
      ));
    }
    if (message.food != null) {
      parts.add(FoodBlocks(view: message.food!));
    }
    if (message.cards.isNotEmpty) {
      parts.add(ToolCardBlocks(cards: message.cards, onPickPlace: onPickPlace));
    }
    if (message.trace.isNotEmpty) {
      parts.add(Padding(
        padding: const EdgeInsets.only(top: T.sp2),
        child: Text('查了 ${message.trace.join('、')}', style: T.tXs),
      ));
    }
    parts.removeWhere((w) => w is SizedBox);
    if (parts.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: parts,
    );
  }

  /// 补问卡拼句时的基准文本：取本会话里最近一条用户原话。
  String _baseText() {
    final raw = baseText.trim().isNotEmpty ? baseText.trim() : message.text.trim();
    if (raw.isEmpty) return '';
    return raw.length > 120 ? raw.substring(0, 120) : raw;
  }
}
