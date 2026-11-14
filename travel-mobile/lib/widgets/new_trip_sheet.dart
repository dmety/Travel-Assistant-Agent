import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 01 屏：底部中间 `+` 点开的「新建方式」底部 sheet。
///
/// 三种开始方式都能改，不预设用户必须先想清楚目的地：
/// 和小满聊（回对话页）、从空白行程开始（直接写库）、复制已有行程（读列表）。
class NewTripSheet extends StatelessWidget {
  const NewTripSheet({
    super.key,
    required this.onChat,
    required this.onCreateBlank,
    required this.onCopy,
  });

  final VoidCallback onChat;
  final VoidCallback onCreateBlank;
  final void Function(Map<String, dynamic> trip) onCopy;

  static Future<void> show(
    BuildContext context, {
    required VoidCallback onChat,
    required VoidCallback onCreateBlank,
    required void Function(Map<String, dynamic> trip) onCopy,
  }) {
    return showModalBottomSheet<void>(
      context: context,
      backgroundColor: T.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(T.rCard)),
      ),
      builder: (_) => NewTripSheet(
        onChat: onChat,
        onCreateBlank: onCreateBlank,
        onCopy: onCopy,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(T.sp4, T.sp3, T.sp4, T.sp4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
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
            const SizedBox(height: T.sp3),
            const Text('新建行程', style: T.tLabel),
            const SizedBox(height: T.sp1),
            const Text('选一种开始方式，之后都能改', style: T.tXs),
            const SizedBox(height: T.sp3),
            _Tile(
              icon: Icons.chat_bubble_outline,
              title: '和小满聊着定',
              subtitle: '说一句想去哪、玩几天，先出草案再确认',
              onTap: () {
                Navigator.of(context).pop();
                onChat();
              },
            ),
            _Tile(
              icon: Icons.add_circle_outline,
              title: '从空白行程开始',
              subtitle: '先建一条空行程，逐日安排以后再填',
              onTap: () {
                Navigator.of(context).pop();
                onCreateBlank();
              },
            ),
            const SizedBox(height: T.sp2),
            const Text('从已有行程复制', style: T.tXs),
            const SizedBox(height: T.sp1),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 180),
              child: FutureBuilder<List<dynamic>>(
                future: ApiClient.instance.itineraries(),
                builder: (context, snap) {
                  if (snap.connectionState != ConnectionState.done) {
                    return const Padding(
                      padding: EdgeInsets.all(T.sp3),
                      child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
                    );
                  }
                  if (snap.hasError || (snap.data ?? []).isEmpty) {
                    return const Padding(
                      padding: EdgeInsets.all(T.sp3),
                      child: Text('还没有已保存的行程', style: T.tXs),
                    );
                  }
                  final list = snap.data!;
                  return ListView.separated(
                    shrinkWrap: true,
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const Divider(height: 1, color: T.line),
                    itemBuilder: (context, i) {
                      final trip = (list[i] is Map)
                          ? Map<String, dynamic>.from(list[i] as Map)
                          : <String, dynamic>{};
                      return ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(Icons.copy_all_outlined, size: 18, color: T.text3),
                        title: Text(
                          (trip['title'] ?? '未命名行程').toString(),
                          style: T.tSm,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        onTap: () {
                          Navigator.of(context).pop();
                          onCopy(trip);
                        },
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: T.sp2),
      color: T.surfaceWarm,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(T.rCtl),
        side: const BorderSide(color: T.line),
      ),
      child: ListTile(
        leading: Icon(icon, size: 20, color: T.primary),
        title: Text(title, style: T.tSmInk),
        subtitle: Text(subtitle, style: T.tXs),
        onTap: onTap,
      ),
    );
  }
}
