# Travel-Assistant-Agent · 旅行助手

本项目是基于 Java 面向对象开发的控制台旅行规划工具，支持多套行程编排、预算统计与本地文件持久化存储。

Java 21 / Maven 多模块控制台项目。当前交付为可连接的工程骨架，功能规划见[架构设计](docs/架构设计.md)和[需求文档](docs/需求文档.md)。业务功能尚未实现。

## 文档目录

- [飞书管理简易版使用说明](docs/团队管理/使用说明-简易版.md)：两张表管理任务进度、测试和问题；旧版保留供参考。
- [多 Agent、知识库与票务能力方案](docs/多Agent与票务能力方案.md)：最新角色分工、实施顺序和购票边界。
- [架构设计](docs/架构设计.md)
- [需求文档](docs/需求文档.md)
- [业务功能技术实现方案](docs/业务功能技术实现方案.md)
- [各版本功能模块](docs/旅行助手-各版本功能模块.md)
- [三个递进版本](docs/旅行助手-三个递进版本.md)
- [架构图片](docs/assets/)
- [开发日志](docs/开发日志/)

以下命令均在项目根目录执行。

## 环境与构建

安装 JDK 21 和 Maven 3.9，确认 `java -version` 和 `mvn -version` 指向正确环境。在项目根目录运行：

```powershell
mvn clean verify
```

## 启动

在第一个 PowerShell 窗口运行后端：

```powershell
java -jar travel-server/target/travel-server-0.1.0-SNAPSHOT.jar
```

在另一个窗口运行客户端：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080
```

其他电脑将 localhost 替换为服务端可达地址；部署时配置 HTTPS，避免明文传输凭据。设置 `SESSION_COOKIE_SECURE=true` 启用安全会话 Cookie。

## 认证与账号

服务端采用 Spring Security + 自定义用户读取（`UserDetailsService`），**已替换**启动时随机密码的默认 `user` 账号。密码以 BCrypt 哈希存储，账号落盘到服务端 JSON（`TRAVEL_DATA_DIR/accounts.json`，已纳入版本控制）。认证使用服务端会话（HttpSession）+ JSESSIONID Cookie 维持登录态；退出会使会话失效并清除 Cookie。

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/health` | GET | 公开 | 健康检查 |
| `/api/account/register` | POST | 公开 | 注册并写入服务端 JSON；重复用户名返回 409，弱密码返回 400 |
| `/api/login` | POST | 公开 | 表单登录（`username`/`password`），成功建立会话并下发 JSESSIONID |
| `/api/account/me` | GET | 需登录 | 返回当前用户名与角色 |
| `/api/logout` | POST | 需登录 | 使会话失效并清除 JSESSIONID |

控制台客户端可演示登录流程：`java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 <用户名> <密码>`。

**浏览器测试前端**：启动服务后打开 `http://localhost:8080/app`（登录页为 `http://localhost:8080/login`），可直接注册/登录并在线测试全部行程接口——行程增删查、五大板块编辑、成员权限管理、版本冲突（409）处理。仅用于本地联调，非生产 UI。

健康检查为 `GET /api/health`。其余接口默认需要认证。**行程管理接口**（多份行程 CRUD、方案/每日安排/交通/住宿/费用五大板块、板块级成员权限、乐观锁版本冲突）已实现，详见 [服务端 REST 接口文档](docs/服务端REST接口文档.md)；业务菜单与 Agent 尚待实现。配置文件中的 `TRAVEL_DATA_DIR` 即账号与行程落盘目录。

`DEEPSEEK_API_KEY` 和 `AMAP_API_KEY` 在后续适配器中通过环境变量读取，不提交密钥。Spring AI 与 Spring Boot 的版本在父 pom 中统一管理。

目标方案采用 Spring AI 加 Java 显式编排：V1 实现需求理解、景点推荐、行程规划三个 Agent，V2 增加票务 Agent 的购票辅助；完整下单与支付跳转为 V3 之后满足正式平台授权、接口和交易存储条件的扩展。保留现有单体及三模块，不立即引入额外多 Agent 框架。上述能力均待实现。

景点 JSON 与 Python 离线构建工具已存在，Java 在线检索尚未实现。当前 `data/china_attractions.json` 被 Git 跟踪，发布前须核实数据授权；本地快照不代表实时票价、库存或开放状态。

本次工程验证：Java 21 下 Maven verify 通过，两个服务端接口测试通过；实际启动后端并运行打包客户端，成功获得 UP 响应。该结果仅证明工程构建、安全入口和客户端连接，不代表业务功能已完成。
