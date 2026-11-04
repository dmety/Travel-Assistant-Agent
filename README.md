# Travel-Assistant-Agent · 旅行助手

本项目是基于 Java 面向对象开发的控制台旅行规划工具，支持多套行程编排、预算统计与本地文件持久化存储。

Java 21 / Maven 多模块项目。当前进度：

- **已实现**：账号与会话（BCrypt + JSESSIONID）、行程 CRUD、五大板块（方案/每日安排/交通/住宿/费用）、板块级成员权限、乐观锁版本控制（并发下仅一次成功）、JSON 文件持久化与重启恢复、**景点查询（Python 离线构建 + Java 加载索引，支持城市/名称/ID 查询，缺库或损坏时停用并说明原因）**、96 条接口测试（16 个测试类，无跳过）。
- **未实现**：DeepSeek 三个 Agent 的编排与调用、高德地图接入、预算自动汇总、出发/返回时间字段、邀请的接受/拒绝流程，以及 V2/V3 全部功能。详见[各版本功能模块](docs/旅行助手-各版本功能模块.md)。

## 文档目录

| 文档 | 内容 |
|---|---|
| [需求文档](docs/需求文档.md) | 目标用户、权限模型、V1–V3 功能与验收标准 |
| [架构设计](docs/架构设计.md) | 架构图、技术栈、项目结构、设计理由 |
| [业务功能技术实现方案](docs/业务功能技术实现方案.md) | 各功能的具体实现思路 |
| [服务端 REST 接口文档](docs/服务端REST接口文档.md) | 账号与行程接口、权限模型、乐观锁、统一错误码 |
| [知识库使用文档](docs/知识库使用文档.md) | 景点 JSON 结构、Python 检索函数、Agent 接入方式 |
| [行程用例-杭州西湖三日游](docs/行程用例-杭州西湖三日游.md) | 端到端用例 UC-ITIN-001 与自动执行脚本 |
| [各版本功能模块](docs/旅行助手-各版本功能模块.md) | V1/V2/V3 功能模块与验收场景 |
| [三个递进版本](docs/旅行助手-三个递进版本.md) | 三阶段目标、范围与技术边界 |
| [多 Agent 与票务能力方案](docs/多Agent与票务能力方案.md) | 角色分工、实施顺序、购票边界 |
| [跨源同名风险审核清单](docs/cross_source_review.md) | 知识库同名景点的判定与合并口径 |
| [测试说明](docs/测试说明.md) | 测试环境、分层结构、用例清单与运行方式 |
| [文档编写规范](docs/文档编写规范.md) | 术语约定、文档归属、日志模板与提交前自检清单 |
| [开发日志](docs/开发日志/) | 按岗位归档（项目经理/开发/测试/文档负责人） |
| [架构图片](docs/assets/) | C4 容器图、后端组件图、技术架构图 |

> **版本口径**：涉及多角色编排、知识库与票务边界时，以[多 Agent 与票务能力方案](docs/多Agent与票务能力方案.md)为准；其余以[需求文档](docs/需求文档.md)为准；[各版本功能模块](docs/旅行助手-各版本功能模块.md)负责拆分验收场景。
>
> **团队任务表**存放在飞书（外部文档，不随仓库分发）：两张表管理任务进度、测试和问题。

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

**景点查询**（公开只读，无需登录）：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/attractions?city=杭州&limit=20` | GET | 按城市/省份查询，含城市别名（杭州 ↔ 杭州市）；默认 20 条、上限 50 |
| `/api/attractions?name=西湖&limit=20` | GET | 按名称查询，精确 → 前缀 → 包含三级匹配 |
| `/api/attractions/{id}` | GET | 按 ID 查详情，不存在 404 |
| `/api/attractions/status` | GET | 知识库加载状态；不可用时给出原因 |

票价、开放时间等字段缺失时，接口返回 `ticketText`/`openTimeText` 为「未知」，不会把缺失当成免费或全天开放。
知识库缺失或损坏时查询返回 503 并说明原因（`reason`），且**不会覆盖或重建原库**。

控制台客户端可演示登录流程：`java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 <用户名> <密码>`。

景点查询（控制台）：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions        # 交互：输入城市或景点名，选序号看详情
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions 杭州    # 一次性列出
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions --id <景点ID>
```

**浏览器测试前端**：启动服务后打开 `http://localhost:8080/app`（登录页为 `http://localhost:8080/login`），可直接注册/登录并在线测试全部行程接口——行程增删查、五大板块编辑、成员权限管理、版本冲突（409）处理。仅用于本地联调，非生产 UI。

健康检查为 `GET /api/health`，景点查询为 `GET /api/attractions/**`，两者公开免登录；行程接口默认需要认证。**行程管理接口**（多份行程 CRUD、方案/每日安排/交通/住宿/费用五大板块、板块级成员权限、乐观锁版本冲突）已实现，详见 [服务端 REST 接口文档](docs/服务端REST接口文档.md)；业务菜单与 Agent 尚待实现。配置文件中的 `TRAVEL_DATA_DIR` 即账号与行程落盘目录。

`DEEPSEEK_API_KEY` 和 `AMAP_API_KEY` 在后续适配器中通过环境变量读取，不提交密钥。Spring AI 与 Spring Boot 的版本在父 pom 中统一管理。

目标方案采用 Spring AI 加 Java 显式编排：V1 实现需求理解、景点推荐、行程规划三个 Agent，V2 增加票务 Agent 的购票辅助；完整下单与支付跳转为 V3 之后满足正式平台授权、接口和交易存储条件的扩展。保留现有单体及三模块，不立即引入额外多 Agent 框架。上述能力均待实现。

景点 JSON 与 Python 离线构建工具已存在，Java 在线检索**已实现**：`tools/build_attraction_index.py` 生成精简索引库
`data/attractions_index.json`，服务端启动时只读加载并建立城市/名称/ID 索引。当前 `data/china_attractions.json` 与
`data/attractions_index.json` 均被 Git 跟踪，发布前须核实数据授权；本地快照不代表实时票价、库存或开放状态。

## 构建与测试状态

2026-11-04 在 `wei` 分支实测：

```text
Tests run: 96, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- 覆盖范围：账号与会话、行程 CRUD、五大板块读写、板块级权限、乐观锁（含 12 路并发）、JSON 持久化与重启恢复、安全边界、景点查询（城市/名称/ID、条数限制、占位值规范化、缺库与损坏降级）。
- 历史缺陷 D-1 ~ D-6 已修复并转为常驻回归用例（`KnownDefectTest`）。
- 运行方式与分层说明见[测试说明](docs/测试说明.md)。

上述结果证明**已实现的账号与行程功能可用**。未实现部分（Agent 编排、高德接入、预算汇总、V2/V3）不计入本次验证。
