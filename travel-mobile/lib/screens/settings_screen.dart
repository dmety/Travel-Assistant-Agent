import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 设计稿 `account.html` / `admin.html` 移动端：设置与服务状态。
///
/// 主题三态（跟随系统 / 浅 / 深）按设计稿放在这里；本次是会话级切换，
/// 未引入 shared_preferences，重启后回到跟随系统——不假装已经持久化。
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.themeMode,
    required this.onThemeChanged,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeChanged;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _base = TextEditingController(text: baseUrl);
  String _status = '未查询';

  @override
  void dispose() {
    _base.dispose();
    super.dispose();
  }

  Future<void> _probe() async {
    setState(() => _status = '查询中');
    try {
      final data = await ApiClient.instance.agentStatus();
      if (!mounted) return;
      final m = data is Map
          ? Map<String, dynamic>.from(data)
          : <String, dynamic>{};
      final usage = m['usage'] is Map
          ? Map<String, dynamic>.from(m['usage'] as Map)
          : <String, dynamic>{};
      setState(() {
        _status = '模型：${usage['model'] ?? m['model'] ?? '未配置'}\n'
            '本进程调用：${usage['calls'] ?? '—'} 次 · '
            '合计 ${usage['totalTokens'] ?? '—'} tokens\n'
            '余额：接口不可用，请到控制台查看';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = '查询失败：$e');
    }
  }

  void _saveBase() {
    final url = _base.text.trim();
    if (url.isEmpty) return;
    ApiClient.instance.setBaseUrl(url);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('后端地址已更新，重新登录可生效')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      appBar: AppBar(
        title: const Text('设置',
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
          const Text('外观', style: T.tLabel),
          const SizedBox(height: T.sp2),
          Container(
            padding: const EdgeInsets.all(T.sp1),
            decoration: T.cardDeco(),
            child: Column(
              children: [
                _themeTile('跟随系统', ThemeMode.system),
                _themeTile('浅色', ThemeMode.light),
                _themeTile('深色', ThemeMode.dark),
              ],
            ),
          ),
          const SizedBox(height: T.sp4),
          const Text('后端地址', style: T.tLabel),
          const SizedBox(height: T.sp2),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _base,
                  decoration: InputDecoration(
                    hintText: 'http://127.0.0.1:8080',
                    hintStyle: T.tXs,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: T.sp3, vertical: T.sp2),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(T.rCtl),
                      borderSide: const BorderSide(color: T.line),
                    ),
                  ),
                  style: T.tSm,
                  keyboardType: TextInputType.url,
                ),
              ),
              const SizedBox(width: T.sp2),
              OutlinedButton(
                onPressed: _saveBase,
                child: const Text('保存'),
              ),
            ],
          ),
          const SizedBox(height: T.sp2),
          const Text(
            '真机用 adb reverse + 127.0.0.1，模拟器用 10.0.2.2，'
            '局域网真机填电脑 IP。Android 9+ 的明文边界见 networkSecurityConfig。',
            style: T.tXs,
          ),
          const SizedBox(height: T.sp4),
          const Text('服务状态', style: T.tLabel),
          const SizedBox(height: T.sp2),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(T.sp3),
            decoration: T.cardDeco(),
            child: Text(_status, style: T.tSm),
          ),
          const SizedBox(height: T.sp2),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: _probe,
              icon: const Icon(Icons.refresh, size: 16),
              label: const Text('查询模型用量'),
            ),
          ),
          const SizedBox(height: T.sp4),
          const Text('关于', style: T.tLabel),
          const SizedBox(height: T.sp2),
          const Text('出逃计划 · 旅游助手', style: T.tSm),
          const SizedBox(height: 2),
          const Text('移动端与网页端共用同一套后端与视觉令牌。', style: T.tXs),
        ],
      ),
    );
  }

  Widget _themeTile(String label, ThemeMode mode) {
    return RadioListTile<ThemeMode>(
      value: mode,
      groupValue: widget.themeMode,
      onChanged: (v) {
        if (v != null) widget.onThemeChanged(v);
      },
      title: Text(label, style: T.tSm),
      activeColor: T.primary,
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: T.sp2),
    );
  }
}
