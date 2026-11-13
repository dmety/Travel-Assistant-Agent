import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';

/// 登录页：品牌区 + 单卡片表单。
///
/// 层级只有三级：品牌（标题）→ 表单（唯一主行动「登录」）→ 服务器地址（默认折叠）。
/// 服务器地址收进「高级」里，因为真机/模拟器之外的场景极少需要改它。
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.onLoggedIn});

  final void Function(String username) onLoggedIn;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _server = TextEditingController(text: baseUrl);

  bool _busy = false;
  bool _showServer = false;
  bool _obscure = true;
  String? _error;

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    _server.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final u = _user.text.trim();
    final p = _pass.text;
    if (u.isEmpty || p.isEmpty) {
      setState(() => _error = '用户名和密码都要填。');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final server = _server.text.trim();
      if (server.isNotEmpty && server != baseUrl) {
        ApiClient.instance.setBaseUrl(server);
      }
      final r = await ApiClient.instance.login(u, p);
      if (!mounted) return;
      if (r['authenticated'] == true) {
        widget.onLoggedIn((r['username'] ?? u).toString());
      } else {
        setState(() {
          _error = (r['error'] ?? '用户名或密码不正确。').toString();
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => _error = '登录失败：$e');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: T.bg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(T.sp6),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ---------- 品牌 ----------
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
                  '旅行助手',
                  textAlign: TextAlign.center,
                  style: T.tLgStrong,
                ),
                const SizedBox(height: T.sp1),
                const Text(
                  '景区知识库 + 实时地图，生成可执行的每日行程',
                  textAlign: TextAlign.center,
                  style: T.tXs,
                ),
                const SizedBox(height: T.sp6),

                // ---------- 表单卡片 ----------
                Container(
                  decoration: T.cardDeco(),
                  padding: const EdgeInsets.all(T.sp4),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _label('用户名'),
                      _field(
                        controller: _user,
                        hint: '请输入用户名',
                        icon: Icons.person_outline,
                        action: TextInputAction.next,
                      ),
                      const SizedBox(height: T.sp3),
                      _label('密码'),
                      _field(
                        controller: _pass,
                        hint: '请输入密码',
                        icon: Icons.lock_outline,
                        obscure: _obscure,
                        action: TextInputAction.done,
                        onSubmitted: (_) => _submit(),
                        suffix: IconButton(
                          onPressed: () =>
                              setState(() => _obscure = !_obscure),
                          icon: Icon(
                            _obscure
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                            size: 18,
                            color: T.text3,
                          ),
                          tooltip: _obscure ? '显示密码' : '隐藏密码',
                        ),
                      ),
                      if (_error != null) ...[
                        const SizedBox(height: T.sp3),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: T.sp3,
                            vertical: T.sp2,
                          ),
                          decoration: BoxDecoration(
                            color: T.errSoft,
                            borderRadius: BorderRadius.circular(T.rCtl),
                            border: Border.all(color: T.errLine),
                          ),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Icon(Icons.error_outline,
                                  size: 16, color: T.err),
                              const SizedBox(width: T.sp2),
                              Expanded(
                                child: Text(
                                  _error!,
                                  style: const TextStyle(
                                    fontSize: T.fsXs,
                                    height: 1.5,
                                    color: T.err,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                      const SizedBox(height: T.sp4),
                      SizedBox(
                        height: 46,
                        child: FilledButton(
                          onPressed: _busy ? null : _submit,
                          style: FilledButton.styleFrom(
                            backgroundColor: T.primary,
                            foregroundColor: Colors.white,
                            disabledBackgroundColor: T.primaryLine,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(T.rCtl),
                            ),
                            textStyle: const TextStyle(
                              fontSize: T.fsMd,
                              fontWeight: T.wStrong,
                            ),
                          ),
                          child: _busy
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Text('登录'),
                        ),
                      ),
                    ],
                  ),
                ),

                const SizedBox(height: T.sp4),

                // ---------- 高级：服务器地址 ----------
                InkWell(
                  onTap: () => setState(() => _showServer = !_showServer),
                  borderRadius: BorderRadius.circular(T.rSm),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: T.sp2),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          _showServer ? '收起服务器地址' : '服务器地址（高级）',
                          style: const TextStyle(
                            fontSize: T.fsXs,
                            color: T.text3,
                          ),
                        ),
                        Icon(
                          _showServer
                              ? Icons.keyboard_arrow_up
                              : Icons.keyboard_arrow_down,
                          size: 16,
                          color: T.text3,
                        ),
                      ],
                    ),
                  ),
                ),
                if (_showServer) ...[
                  const SizedBox(height: T.sp1),
                  _field(
                    controller: _server,
                    hint: 'http://127.0.0.1:8080',
                    icon: Icons.dns_outlined,
                    keyboard: TextInputType.url,
                  ),
                  const SizedBox(height: T.sp2),
                  const Text(
                    '真机调试保持 127.0.0.1:8080（配合 adb reverse）；'
                    '模拟器用 10.0.2.2:8080；局域网用电脑 IP。',
                    textAlign: TextAlign.center,
                    style: T.tXs,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _label(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: T.sp1),
      child: Text(text, style: T.tLabel),
    );
  }

  Widget _field({
    required TextEditingController controller,
    required String hint,
    required IconData icon,
    bool obscure = false,
    TextInputAction action = TextInputAction.next,
    TextInputType? keyboard,
    ValueChanged<String>? onSubmitted,
    Widget? suffix,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: T.surface2,
        borderRadius: BorderRadius.circular(T.rCtl),
        border: Border.all(color: T.lineStrong),
      ),
      padding: const EdgeInsets.symmetric(horizontal: T.sp3),
      child: Row(
        children: [
          Icon(icon, size: 18, color: T.text3),
          const SizedBox(width: T.sp2),
          Expanded(
            child: TextField(
              controller: controller,
              obscureText: obscure,
              textInputAction: action,
              keyboardType: keyboard,
              onSubmitted: onSubmitted,
              style: T.tSmInk,
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: hint,
                hintStyle: const TextStyle(fontSize: T.fsSm, color: T.text3),
                contentPadding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ),
          ?suffix,
        ],
      ),
    );
  }
}
