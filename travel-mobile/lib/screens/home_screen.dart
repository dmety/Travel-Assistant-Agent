import 'package:flutter/material.dart';

import '../api/client.dart';
import '../theme/tokens.dart';
import '../widgets/new_trip_sheet.dart';
import 'chat_screen.dart';
import 'profile_screen.dart';
import 'trips_screen.dart';

/// 登录后的主壳：底部三栏导航（规划 / 行程 / 我的）。
///
/// 对应设计稿的导航结构——规划是带地图的 AI 对话，行程是本地已生成的草案列表，
/// 我的是账号与退出。用 [IndexedStack] 保活各页状态，切换不丢对话。
class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.username,
    required this.onLogout,
    required this.themeMode,
    required this.onThemeChanged,
  });

  final String username;
  final Future<void> Function() onLogout;
  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeChanged;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _index = 0;
  /// 每次切到「行程」都换一个 key，让该页重新读一次本地存档。
  /// IndexedStack 保活会让 TripsScreen 的 initState 只跑一次，
  /// 不刷新的话刚生成的草案在行程列表里看不到（规划 / 我的两页仍保活）。
  int _tripsRefresh = 0;
  late final ChatScreen _chat;

  @override
  void initState() {
    super.initState();
    _chat = ChatScreen(username: widget.username, onLogout: widget.onLogout);
  }

  void _setIndex(int i) {
    setState(() {
      _index = i;
      if (i == 1) _tripsRefresh++;
    });
  }

  /// 设计稿 01 屏：底部中间 `+` 是新建行程的唯一主入口。
  void _openNewTrip() {
    NewTripSheet.show(
      context,
      onChat: () => _setIndex(0),
      onCreateBlank: () => _createTrip(<String, dynamic>{
        'title': '新的行程',
        'plan': <String, dynamic>{
          'destination': '',
          'summary': '',
          'notes': '',
        },
      }),
      onCopy: (trip) {
        final title = (trip['title'] ?? '新的行程').toString();
        _createTrip(<String, dynamic>{
          'title': '$title（副本）',
          'plan': trip['plan'] is Map
              ? Map<String, dynamic>.from(trip['plan'] as Map)
              : <String, dynamic>{},
        });
      },
    );
  }

  Future<void> _createTrip(Map<String, dynamic> payload) async {
    try {
      await ApiClient.instance.createItinerary(payload);
      if (!mounted) return;
      setState(() {
        _index = 1;
        _tripsRefresh++;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('行程已创建')),
      );
    } on AuthExpiredException {
      if (mounted) await widget.onLogout();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('创建失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      _chat,
      TripsScreen(
        key: ValueKey('trips_$_tripsRefresh'),
        onGoPlanning: () => _setIndex(0),
      ),
      // 不缓存：主题切换后要立刻拿到新的 ThemeMode。
      ProfileScreen(
        username: widget.username,
        onLogout: widget.onLogout,
        onGoTrips: () => _setIndex(1),
        themeMode: widget.themeMode,
        onThemeChanged: widget.onThemeChanged,
      ),
    ];
    return Scaffold(
      body: IndexedStack(index: _index, children: pages),
      floatingActionButton: FloatingActionButton(
        onPressed: _openNewTrip,
        backgroundColor: T.primary,
        foregroundColor: Colors.white,
        elevation: 2,
        tooltip: '新建行程',
        child: const Icon(Icons.add, size: 26),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _index,
        onTap: _setIndex,
        selectedItemColor: T.primary,
        unselectedItemColor: T.text3,
        backgroundColor: T.surface,
        elevation: 8,
        items: const [
          BottomNavigationBarItem(
            icon: Icon(Icons.map_outlined),
            activeIcon: Icon(Icons.map),
            label: '规划',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.luggage_outlined),
            activeIcon: Icon(Icons.luggage),
            label: '行程',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.person_outline),
            activeIcon: Icon(Icons.person),
            label: '我的',
          ),
        ],
      ),
    );
  }
}
