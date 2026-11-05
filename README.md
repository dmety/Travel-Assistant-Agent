# Travel-Assistant-Agent · 旅行助手

本项目是基于 Java 面向对象开发的控制台旅行规划工具，支持多套行程编排、预算统计与本地文件持久化存储。

Java 21 / Maven 多模块项目。当前进度：

- **已实现**：账号与会话（BCrypt + JSESSIONID）、行程 CRUD、五大板块（方案/每日安排/交通/住宿/费用）、板块级成员权限、**邀请与成员协作**、乐观锁版本控制（并发下仅一次成功）、JSON 文件持久化与重启恢复、**景点查询**（Python 离线构建 + Java 加载索引）、**预算汇总与时间检查**、**V2 四块能力**：详细时间检查（起止时间、重叠提示）、协作记录与留言、购票辅助（人工核验的官方入口，不生成猜测链接）、高德地点/天气/路线查询与规划草案、**V3 团队决策与总结**：候选地点与投票（净支持排序、平票不给结论）、预算分析（上限/预计/实际/差额/人均）、综合决策建议（规则算风险，模型只组织语言）、导出文本摘要与归档（归档后全站只读）；232 条接口测试（27 个测试类，无跳过）。
- **未实现**：票务完整交易（下单/支付/出票/退改）。详见[各版本功能模块](docs/旅行助手-各版本功能模块.md)。
- **外部依赖可降级**：不配 `AMAP_KEY` 时地点/天气/路线返回 503 并说明原因；不配 `DEEPSEEK_API_KEY` 时规划草案仍返回带来源的候选，只是没有文字建议；没有购票入口配置时如实提示自行前往官方渠道。**本机不配置任何密钥也能完整跑起来。**

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

更新景点源库后，先运行 `python tools/build_attraction_index.py` 重建 Java 索引。
交付前运行 `python tools/build_attraction_index.py --check`，过期时返回非零；
`tools/gate.sh` 已包含该检查。打包后可运行 `python examples/acceptance_tasks_2_3_4.py`，
使用临时数据目录和随机端口验证注册登录、杭州行程、景点查询及真实进程重启恢复。

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

**邀请与成员协作**（需登录，邀请记录落盘 `TRAVEL_DATA_DIR/invitations.json`）：

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/itineraries/{id}/invitations` | POST | 仅创建者 | 按账号发起邀请；首次返回 201，命中同一待处理邀请返回 200（只更新授权板块） |
| `/api/itineraries/{id}/invitations` | GET | 仅创建者 | 该行程的邀请记录（含已接受/已拒绝/已撤回） |
| `/api/invitations` | GET | 需登录 | 我收到的待处理邀请（含行程标题、邀请人） |
| `/api/invitations/{invId}/accept` | POST | 仅受邀者 | 接受后成为成员并获得邀请中指定的板块编辑权；已被处理返回 409 |
| `/api/invitations/{invId}/reject` | POST | 仅受邀者 | 拒绝后不产生任何成员关系 |
| `/api/invitations/{invId}` | DELETE | 仅创建者 | 撤回待处理邀请 |
| `/api/itineraries/{id}/leave` | POST | 仅成员 | 成员主动退出；创建者不可退出（400），需删除行程 |

**邀请不等于成员**：发出邀请不改变行程版本号，受邀者在接受前访问行程仍返回 403。
`POST /api/itineraries/{id}/members` 保留为创建者直接授权的管理入口，与邀请流程并存。

**预算与时间检查**（需登录，与行程查看权一致）：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/itineraries/{id}/budget` | GET | 按币种与类别汇总费用；未填金额计入「待填写」不计入合计；**多币种分别汇总，不做汇率换算** |
| `/api/itineraries/{id}/checks` | GET | 行程体检：日期缺失/倒置、费用待填写、多币种、每日安排/交通/住宿越界 |

方案（`plan`）自 2026-11-04 起支持 `departDate`/`returnDate`，返回日期早于出发日期时写入直接返回 400；
两个字段可为空以兼容历史数据，缺失由体检接口提示「未填写」。

**V2 新增接口**：

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/itineraries/{id}/comments` | GET / POST | 参与者 | 留言。**不改变行程版本号**，作者取服务端会话 |
| `/api/itineraries/{id}/changelog` | GET | 参与者 | 修改记录，倒序，`limit` 默认 50 / 上限 200；**只由服务端产生** |
| `/api/tickets?attractionId=` / `?name=` | GET | 公开 | 购票辅助：官方入口、票种、规则、来源与查询时间；`stockVerified` 恒为 false |
| `/api/tickets/channels` | GET | 公开 | 已收录的官方入口清单与说明 |
| `/api/location/places`、`/weather`、`/route` | GET | 需登录 | 高德地点/天气/路线；未配置密钥时 503 + 原因 |
| `/api/location/status` | GET | 需登录 | 是否已配置高德密钥 |
| `/api/agent/plan` | POST | 需登录 | 规划草案，**不落库**；候选带 ID 与来源 |
| `/api/agent/status` | GET | 需登录 | 模型是否已配置 |

时间检查的两条口径值得先知道：**「结束早于开始」写入即 400**（纯笔误，没有合理解释）；
**「同日重叠」「超出旅行范围」只做体检提示**，由用户判断是否调整——服务端直接拒绝会挡掉正在编辑的中间状态。

购票辅助的边界：只展示人工维护白名单里的入口，文件默认不存在。**没有收录就明确说没有，
不抓取、不推断、不生成任何链接**；没有库存接口，所以「余票未核验」是这一阶段唯一诚实的答案。
配置格式见[接口文档 §3.14](docs/服务端REST接口文档.md)。

控制台客户端可演示登录流程，并顺带打印第一份行程的预算与检查结果：`java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 <用户名> <密码>`。

景点查询（控制台）：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions        # 交互：输入城市或景点名，选序号看详情
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions 杭州    # 一次性列出
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions --id <景点ID>
```

邀请流程（控制台）：

```powershell
# 查看我收到的待处理邀请（含邀请 ID）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 invitations <我的账号> <密码>
# 接受 / 拒绝
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 invitations <账号> <密码> accept <邀请ID>
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 invitations <账号> <密码> reject <邀请ID>
# 创建者发起邀请（后面列出授予的板块，省略表示仅查看）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 invite <创建者> <密码> <行程ID> <受邀账号> PLAN DAILY
```

V2 相关命令：

```powershell
# 购票辅助（无需登录）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 ticket 西湖
# 留言与修改记录
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 timeline <账号> <密码> <行程ID>
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 comment <账号> <密码> <行程ID> 第二天想加个灵隐寺
# 规划草案（不落库）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 plan <账号> <密码> 北京 2
```

V3 相关命令：

```powershell
# 候选与投票（看板含票数、净支持与排序；平票时不给「最高」）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 votes <账号> <密码> <行程ID>
# 提候选 / 投票（support|oppose|abstain）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 candidate <账号> <密码> <行程ID> 灵隐寺
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 vote <账号> <密码> <行程ID> <候选ID> support
# 综合决策建议：风险与调整建议由规则给出，模型只负责组织语言
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 advice <账号> <密码> <行程ID>
# 导出文本摘要（自行重定向到文件）/ 归档与取消归档（仅创建者）
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 export <账号> <密码> <行程ID> > 行程.txt
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 archive <账号> <密码> <行程ID>
```

**浏览器测试前端**：启动服务后打开 `http://localhost:8080/app`（登录页为 `http://localhost:8080/login`），可直接注册/登录并在线测试全部行程接口——行程增删查、五大板块编辑、成员权限管理、邀请的发出/接受/拒绝/撤回、成员退出、版本冲突（409）处理、**协作留言与修改记录、购票信息、周边查询（地点/天气/能力状态）**、**候选投票、决策建议、归档与导出**。仅用于本地联调，非生产 UI。

健康检查为 `GET /api/health`，景点与购票查询公开免登录；行程、协作、高德与规划接口需要认证。

`DEEPSEEK_API_KEY` 与 `AMAP_KEY`（高德 Web 服务）通过环境变量读取，不提交密钥。两者都为空时服务照常启动，
相关接口降级并说明原因。密钥只出现在请求参数里，不写日志、不进异常消息。

规划编排由 Java 显式完成：先检索本地景点库，再按需查天气与购票入口，最后交给模型组织语言。
**模型只负责措辞，不改事实**——它拿到的全是已经查到的数据，输出也不会被解析成结构化指令。
因此模型说不出一个库里没有的景点 ID。`travel.ai.api-key` 为空时不创建任何外部调用，
这也是测试能强制断网运行的前提。具体边界见[多 Agent 与票务能力方案](docs/多Agent与票务能力方案.md)。

景点 JSON 与 Python 离线构建工具已存在，Java 在线检索**已实现**：`tools/build_attraction_index.py` 生成精简索引库
`data/attractions_index.json`，服务端启动时只读加载并建立城市/名称/ID 索引。当前 `data/china_attractions.json` 与
`data/attractions_index.json` 均被 Git 跟踪，发布前须核实数据授权；本地快照不代表实时票价、库存或开放状态。

## 构建与测试状态

2026-11-04 在 `wei` 分支实测：

```text
Tests run: 232, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- 覆盖范围：账号与会话、行程 CRUD、五大板块读写、板块级权限、邀请流程、乐观锁（含 12 路并发）、JSON 持久化与重启恢复、安全边界、景点查询、预算汇总与时间检查、**详细时间检查（倒置拦截/重叠提示/首尾相接）**、**留言与修改记录（含版本号隔离、防伪造 actor）**、**购票辅助（无入口文案、非法地址丢弃、余票未核验）**、**高德客户端解析与错误码翻译**、**规划草案的降级路径**、**候选与投票（去重/改票/平票/确认入库/版本隔离）**、**预算分析（多币种不硬减、预计与实分离）**、**归档只读语义覆盖全部写入口**、**综合决策建议的规则风险与降级**。
- 历史缺陷 D-1 ~ D-6 已修复并转为常驻回归用例（`KnownDefectTest`）。
- 测试**强制把 `AMAP_KEY` 与 `DEEPSEEK_API_KEY` 置空**：本地配了环境变量也不会发起真实调用、不会产生费用。
- 运行方式与分层说明见[测试说明](docs/测试说明.md)。

上述结果证明**已实现的账号、行程、景点、预算、邀请、V2 四项能力与 V3 团队决策可用**。未实现部分（票务完整交易）不计入本次验证；
高德与模型的真实接口因未配置密钥，只验证了解析逻辑与降级路径，未做真实联调。
