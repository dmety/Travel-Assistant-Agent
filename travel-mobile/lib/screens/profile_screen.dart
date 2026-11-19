import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 「我的」页：账号信息与退出登录。
///
/// 设计稿没有单独定义这一屏（真机导航靠底部三栏），这里补一个轻量页，
/// 与登录页共用品牌语言（方印 + 纸质卡片）。
class ProfileScreen extends StatelessWidget {
  const ProfileScreen({
    super.key,
    required this.username,
    required this.onLogout,
    this.onGoTrips,
  });

  final String username;
  final Future<void> Function() onLogout;
  final VoidCallback? onGoTrips;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('我的', style: TextStyle(fontSize: T.fsMd, color: T.ink, fontWeight: T.wStrong)),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: T.line),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(T.sp4),
        children: [
          Container(
            padding: const EdgeInsets.all(T.sp4),
            decoration: T.cardDeco(),
            child: Row(
              children: [
                Container(
                  width: 52,
                  height: 52,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: T.primarySoft,
                    borderRadius: BorderRadius.circular(T.rCard),
                    border: Border.all(color: T.primaryLine),
                  ),
                  child: const Text('出',
                      style: TextStyle(fontSize: T.fsLg, color: T.primary, fontWeight: T.wStrong)),
                ),
                const SizedBox(width: T.sp3),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(username, style: T.tMd),
                      const SizedBox(height: 2),
                      const Text('已登录', style: T.tXs),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: T.sp3),
          _Row(
            icon: Icons.luggage_outlined,
            label: '我的行程',
            onTap: onGoTrips,
          ),
          const SizedBox(height: T.sp3),
          Container(
            padding: const EdgeInsets.all(T.sp4),
            decoration: T.cardDeco(),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('关于', style: T.tLabel),
                const SizedBox(height: T.sp2),
                const Text('出逃计划 · 景区知识库 + 实时地图', style: T.tSm),
                const SizedBox(height: 2),
                const Text('移动端与网页端共用同一套后端与视觉令牌。', style: T.tXs),
              ],
            ),
          ),
          const SizedBox(height: T.sp6),
          SizedBox(
            height: 46,
            child: OutlinedButton.icon(
              onPressed: () async {
                await onLogout();
              },
              icon: const Icon(Icons.logout, size: 18),
              label: const Text('退出登录'),
              style: OutlinedButton.styleFrom(
                foregroundColor: T.err,
                side: BorderSide(color: T.errLine),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(T.rCtl),
                ),
                textStyle: const TextStyle(fontSize: T.fsMd, fontWeight: T.wStrong),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.icon, required this.label, this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(T.rCard),
      child: Container(
        padding: const EdgeInsets.all(T.sp4),
        decoration: T.cardDeco(),
        child: Row(
          children: [
            Icon(icon, size: 20, color: T.text2),
            const SizedBox(width: T.sp3),
            Expanded(child: Text(label, style: T.tSmInk)),
            const Icon(Icons.chevron_right, size: 18, color: T.text3),
          ],
        ),
      ),
    );
  }
}
