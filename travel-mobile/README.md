# travel-mobile · 移动端

「出逃计划 — 旅游助手」的 Flutter 客户端。与 Web 工作台、控制台客户端共用同一套 REST 接口和
会话 Cookie 登录态，不是独立后端。界面规范来自 `design/`（`mobile.html` 六屏 +
`account/food/ticket/knowledge/itinerary/collaboration/kanban.html`），色板以
`design/tokens.css` 为准。

## 跑起来

```bash
# 1. 先启动服务端（仓库根目录）
bash tools/run-server.sh

# 2. 真机：把手机的 8080 反向映射到电脑（App 内地址保持 127.0.0.1:8080）
adb reverse tcp:8080 tcp:8080

# 3. 装依赖并运行
flutter pub get
flutter run

# 打成 APK（自动从 .env 取 BAIDU_AK_MOBILE 注入原生清单与 Dart）
bash tools/build_mobile.sh
```

地址在「我的 → 设置」里可改。三种常用值：

| 场景 | 填什么 |
|---|---|
| 真机 + `adb reverse` | `http://127.0.0.1:8080` |
| Android 模拟器访问宿主机 | `http://10.0.2.2:8080` |
| 局域网真机 / 桌面端 | `http://192.168.x.x:8080` |

## 页面与接口

| 设计稿 | 落地页面 | 主要接口 |
|---|---|---|
| 01 行程列表 + 新建方式 sheet | `screens/trips_screen.dart`、`widgets/new_trip_sheet.dart`、`home_screen.dart` 的中央 `+` | `GET/POST /api/itineraries` |
| 02 和游小满聊 | `screens/chat_screen.dart`、`widgets/mascot_avatar.dart`、`widgets/message_bubble.dart` | `/api/chat` → `/api/intent/parse` → `/api/agent/invoke` |
| 03 草案与确认 | `screens/plan_detail_screen.dart` | `/api/agent/plan/materialize`、`POST /api/itineraries` |
| 04 列表模式 / 05 地图模式 | 同上，appbar 一键切换，共用同一份 `PlanView` | 本地数据，不重复请求 |
| 06 待定与协作 | `widgets/collab_sheet.dart`（候选 / 待规划 / 成员 / 体检四 pane） | `/api/itineraries/{id}/candidates`、`votes`、`members`、`comments`、`budget/analysis`、`checks` |
| 已保存行程 · 导出 · 删除 | `screens/saved_trips_screen.dart` | `/api/itineraries`、`/export`、`DELETE` |
| 美食 | `screens/food_screen.dart` | `/api/agent/invoke`（`FOOD_RECOMMEND`） |
| 购票辅助 | `screens/ticket_screen.dart` | `/api/tickets/catalog`、`/api/agent/tickets/advice` |
| 景点知识库 | `screens/knowledge_screen.dart` | `/api/attractions` |
| 设置 / 主题 / 服务状态 | `screens/settings_screen.dart` | `/api/agent/status` |
| 登录 / 我的 | `screens/login_screen.dart`、`screens/profile_screen.dart` | `/api/login`、`/api/account/me`、`/api/logout` |

`lib/api/client.dart` 统一封装 REST，把 401 翻成 `AuthExpiredException`、其余翻成
`ApiException`，页面不各自判状态码。

## 看板娘游小满 v4.1

- 素材在 `assets/mascot/`（六张正脸差分，与 `static/mascot/`、`design/export/png/` 同源），
  已在 `pubspec.yaml` 声明。
- appbar 是活头像：呼吸、随机眨眼（2.6–5.4 秒）、点击情绪循环
  害羞 → 不耐烦 → 生气 → 平常、8 秒自动平复、发送后点头。
- 每条 AI 回复用 `front-bust` 正脸头像 + 「游小满 · AI 生成」署名；思考提示是
  「小满想了想（AI 生成）」。侧脸立绘只留给登录页。
- 她只当对话化身与语气层，不占主内容区、不盖地图点位；票价、天数、来源条数等
  模型事实逐字保留，她只组织语言、不产生事实。

## 明文 HTTP 的边界（Android 9+ 会默认拦）

| 构建 | 配置文件 | 策略 |
|---|---|---|
| release | `android/app/src/main/res/xml/network_security_config.xml` | 默认只允许 HTTPS；仅放行 `127.0.0.1` / `localhost` / `10.0.2.2` |
| debug | `android/app/src/debug/res/xml/network_security_config.xml` | 放开明文，方便连局域网电脑的 IP |

**对外部署时服务端必须上 HTTPS**，否则账号密码是明文过网的。

## 已知限制（如实披露）

1. **本机没有 Flutter SDK**（`flutter` / `dart` 均不在 PATH），本轮改动只做了静态自检
   （import 路径、括号与字符串闭合、`T.*` 令牌引用、素材存在性），**没有跑过
   `flutter pub get` / `flutter analyze` / `flutter build apk`**。首次在有 SDK 的机器上
   请依次跑这三条再装机。
2. **深色主题是框架级**：`T.*` 是静态浅色常量，深色下页面内逐屏对比度尚未校验；
   主题三态目前是会话级，重启回到「跟随系统」，未接 `shared_preferences` 持久化。
3. 设计稿 02 屏的 `DAY 2 · 已减量` 标签与地图 pin 的逐点联动未做，追问后只给
   「路线已重排」提示条；地图点位仍由同一份 `PlanView` 驱动，不会跳页。
4. 语音按钮仍只是输入栏旁的占位，未接 ASR；管理后台（`admin.html`）是 Web 控制台，
   移动端只暴露「服务状态 / 模型用量」，不提供 Provider 准入操作。
5. Cookie 与聊天记录明文落盘在应用私有目录（未 root 的设备上其他应用读不到），
   按「可接受」处理；存更敏感内容时应换 `flutter_secure_storage`。
