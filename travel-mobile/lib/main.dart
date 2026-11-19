import 'package:flutter/material.dart';
import 'package:flutter_baidu_mapapi_base/flutter_baidu_mapapi_base.dart';

import 'api/client.dart';
import 'config/map_config.dart';
import 'models/plan_view.dart';
import 'screens/home_screen.dart';
import 'screens/login_screen.dart';
import 'theme/app_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 仅在配置了百度地图 AK 时初始化 SDK 并同意隐私协议；
  // 未配置则 MapPanel 走 SVG 示意底图，不触动原生地图。
  if (baiduMapAk.isNotEmpty) {
    BMFMapSDK.setAgreePrivacy(true);
    BMFMapSDK.setCoordType(BMF_COORD_TYPE.BD09LL);
  }
  await ApiClient.instance.init();
  runApp(const TravelApp());
}

class TravelApp extends StatelessWidget {
  const TravelApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '出逃计划',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      home: const BootGate(),
    );
  }
}

/// 启动闸门：先问一次「我是谁」，决定进对话页还是登录页。
///
/// 不用本地布尔值判断登录态——session 是后端内存态的，
/// 只有 `/api/account/me` 说活着才算活着。
class BootGate extends StatefulWidget {
  const BootGate({super.key});

  @override
  State<BootGate> createState() => _BootGateState();
}

class _BootGateState extends State<BootGate> {
  AccountView? _account;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _probe();
  }

  Future<void> _probe() async {
    final a = await ApiClient.instance.me();
    if (!mounted) return;
    setState(() {
      _account = a;
      _loading = false;
    });
  }

  Future<void> _logout() async {
    await ApiClient.instance.logout();
    if (!mounted) return;
    setState(() => _account = null);
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    }
    final a = _account;
    if (a == null) {
      return LoginScreen(
        onLoggedIn: (username) {
          setState(() {
            _account = AccountView(username: username, roles: const []);
          });
        },
      );
    }
    return HomeScreen(username: a.username, onLogout: _logout);
  }
}
