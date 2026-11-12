import 'package:dio/dio.dart';
import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';
import 'package:path_provider/path_provider.dart';

import '../models/plan_view.dart';

/// 后端基础地址（可运行时修改，见 [ApiClient.setBaseUrl]）。
/// - 真机调试（配合 `adb reverse tcp:8080 tcp:8080`）用 http://127.0.0.1:8080；
/// - Android 模拟器访问宿主机用 http://10.0.2.2:8080；
/// - 局域网真机/桌面用电脑 IP，如 http://192.168.x.x:8080。
String baseUrl = 'http://127.0.0.1:8080';

/// 登录态失效（后端返回 401）。界面据此回登录页，而不是把 401 当网络错误展示。
class AuthExpiredException implements Exception {
  const AuthExpiredException();
  @override
  String toString() => '登录已过期，请重新登录';
}

/// 一次请求的业务失败（4xx/5xx 且不是 401），消息可直接展示给用户。
class ApiException implements Exception {
  final String message;
  const ApiException(this.message);
  @override
  String toString() => message;
}

/// 对后端的统一 REST 客户端：基于会话 Cookie（JSESSIONID）认证，
/// 与 Web 端、CLI 端共用同一套登录态。
///
/// 端点与 Web `chat.html` 完全一致，因此三端行为可比对：
/// `POST /api/login` → `GET /api/account/me` → `POST /api/intent/parse`
/// → `POST /api/chat`（闲聊/工具）→ `POST /api/agent/invoke`（编排）。
class ApiClient {
  ApiClient._();
  static final ApiClient instance = ApiClient._();

  late final Dio dio;
  late final PersistCookieJar _cookieJar;

  Future<void> init() async {
    final dir = await getApplicationDocumentsDirectory();
    _cookieJar = PersistCookieJar(
      storage: FileStorage('${dir.path}/.cookies/api'),
    );
    dio = Dio(BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 60),
    ));
    // 自动持久化 JSESSIONID：登录后所有请求自动携带，与 Web/CLI 同会话。
    dio.interceptors.add(CookieManager(_cookieJar));
  }

  /// 运行时修改后端基地址（如真机从 127.0.0.1 切换为电脑局域网 IP）。
  void setBaseUrl(String url) {
    baseUrl = url;
    dio.options.baseUrl = url;
  }

  /// 当前账号；未登录返回 null（不抛异常，供启动探测用）。
  Future<AccountView?> me() async {
    try {
      final res = await dio.get('/api/account/me');
      return AccountView.from(res.data);
    } on DioException {
      return null;
    }
  }

  Future<bool> isLoggedIn() async => (await me()) != null;

  /// 表单登录（Spring Security 表单登录，参数名固定为 username/password）。
  /// 成功返回 {authenticated:true, username}；失败（401）返回 {authenticated:false, error}。
  Future<Map<String, dynamic>> login(String username, String password) async {
    try {
      final res = await dio.post(
        '/api/login',
        data: {'username': username, 'password': password},
        options: Options(contentType: 'application/x-www-form-urlencoded'),
      );
      final m = _toMap(res.data);
      // Spring 表单登录成功默认返回 302 → Dio 跟随重定向后拿到 200 且无 body，
      // 因此以「能否取到账号」作为最终判据，而不是只看 body。
      if (m['authenticated'] != true) {
        final account = await me();
        if (account != null) {
          return {'authenticated': true, 'username': account.username};
        }
      }
      return m;
    } on DioException catch (e) {
      if (e.response?.data is Map) {
        return _toMap(e.response!.data);
      }
      return {'authenticated': false, 'error': '网络错误：${e.message}'};
    }
  }

  Future<void> logout() async {
    try {
      await dio.post('/api/logout');
    } on DioException {
      // 网络失败也继续清本地 Cookie
    }
    await _cookieJar.deleteAll();
  }

  /// 自然语言解析：一次拿全意图/城市/天数/人数/预算/日期/偏好。
  /// 与 Web 端共用同一接口，避免 App 自己写一套「几个字算天数」的猜测。
  Future<IntentView?> parseIntent({
    required String text,
    List<Map<String, String>> history = const [],
    Map<String, dynamic> state = const {},
  }) async {
    final res = await dio.post('/api/intent/parse', data: {
      'text': text.length > 300 ? text.substring(0, 300) : text,
      'history': history,
      'state': state,
    });
    final m = _toMap(res.data);
    if (m.isEmpty) return null;
    return IntentView.from(m);
  }

  /// 闲聊 / 工具驱动回答。模型不可用时后端返回 `source=unavailable`，
  /// 此时 `reply` 为空，调用方应回退到「解析 + 编排」链路（与 Web 一致）。
  ///
  /// 单独把读超时压到 25 秒：这条走模型，慢起来比确定性链路久一个量级，
  /// 超时即抛异常让上层回退，而不是让用户对着转圈干等。
  Future<Map<String, dynamic>> chat({
    required String message,
    List<Map<String, String>> history = const [],
    Map<String, dynamic> state = const {},
  }) async {
    final res = await dio.post(
      '/api/chat',
      data: {
        'message': message.length > 300 ? message.substring(0, 300) : message,
        'history': history,
        'state': state,
      },
      options: Options(receiveTimeout: const Duration(seconds: 25)),
    );
    return _toMap(res.data);
  }

  /// 调 L1 编排层 POST /api/agent/invoke。
  /// intent ∈ PLAN_DRAFT / FOOD_RECOMMEND / DECISION_ADVICE /
  /// TICKET_ADVICE / WEATHER_ADVICE / CONTENT_INSPIRATION / CHAT。
  Future<Map<String, dynamic>> invoke({
    required String intent,
    Map<String, dynamic>? context,
  }) async {
    final res = await dio.post(
      '/api/agent/invoke',
      data: {'intent': intent, 'context': context ?? <String, dynamic>{}},
    );
    return _toMap(res.data);
  }

  Future<dynamic> attractions({String? city, String? name, int? limit}) async {
    final q = <String, dynamic>{};
    if (city != null && city.isNotEmpty) q['city'] = city;
    if (name != null && name.isNotEmpty) q['name'] = name;
    if (limit != null) q['limit'] = limit;
    final res = await dio.get('/api/attractions', queryParameters: q);
    return res.data;
  }

  Map<String, dynamic> _toMap(dynamic data) {
    if (data is Map) {
      return Map<String, dynamic>.from(data);
    }
    return <String, dynamic>{};
  }
}

/// 把 Dio 异常翻译成界面能直接用的两类错误：
/// 401 → [AuthExpiredException]（回登录页），其余 → [ApiException]（就地提示）。
Never rethrowAsApi(DioException e) {
  final code = e.response?.statusCode;
  if (code == 401) throw const AuthExpiredException();
  final data = e.response?.data;
  if (data is Map) {
    final msg = data['error'] ?? data['message'];
    if (msg != null && msg.toString().isNotEmpty) {
      throw ApiException(msg.toString());
    }
  }
  if (code != null) throw ApiException('请求失败（HTTP $code）');
  throw ApiException('网络不可用：${e.message ?? '连接失败'}');
}
