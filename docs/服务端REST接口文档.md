# Travel Assistant 服务端接口文档

> 适用版本：`wei`（V1 → V3 全部功能）　|　技术栈：Spring Boot 3.5 + Spring Security 6 + Java 21
>
> 本文档覆盖**账号与认证**、**行程管理**、**协作与外部查询**、**团队决策与总结**、
> **Agent 注册表与投票**五套 REST 接口，
> 包含请求/响应示例、数据模型、权限模型与乐观锁机制。

---

## 1. 基础约定

### 1.1 地址与端口
- 默认基址：`http://localhost:8080`
- 端口由 `server.port`（启动参数 `--server.port=8080`）或环境变量 `PORT` 决定。
- 所有接口均以 `/api` 为前缀（登录页除外，见 5）。

### 1.2 认证模型（会话 + Cookie）
- 登录使用 Spring Security **表单登录**：向 `/api/login` 提交表单字段 `username`、`password`（`application/x-www-form-urlencoded`）。
- 登录成功后，服务端建立 **HttpSession** 并通过 **`JSESSIONID`** Cookie 维持。
- Cookie 属性：`HttpOnly=true`、`SameSite=Strict`。
- 此后所有受保护接口必须在请求中携带该 `JSESSIONID` Cookie（浏览器自动携带；`curl` 用 `-b cookies.txt`）。
- 退出调用 `/api/logout`，服务端使会话失效并清除 `JSESSIONID`。

### 1.3 公开的接口（无需登录）

与 `SecurityConfiguration#permitAll()` 保持一致（按 matcher 计 20 条，含通配）：

| 路径 | 说明 |
|---|---|
| `GET /api/health` | 健康检查 |
| `POST /api/account/register` | 注册 |
| `POST /api/login` | 表单登录 |
| `GET /api/attractions`、`GET /api/attractions/**` | 景点查询（只读知识库，见第 8 节） |
| `GET /api/tickets`、`GET /api/tickets/**` | 购票辅助（只读人工核验的官方入口，见 §3.14） |
| `GET /api/location/status` | 高德是否配置（只回布尔与降级原因，无用户数据） |
| `GET /api/agent/status` | 模型是否配置与本进程用量（见 §3.16.4） |
| `GET /api/agent/capabilities` | 能力协商：Provider 的 id / 状态 / 能力标签 / 最近拒绝原因码，不含 endpoint、authRef 与凭据（见 §3.22） |
| `GET /`、`GET /login`、`GET /app` | 首页（`PageController` 转发到 `index.html`，登录 / 注册页） |
| `GET /index.html` | 同上，静态资源直连地址 |
| `GET /chat.html`、`GET /ticket-assistant.html` | 旧页面地址，302 到 `/planner.html`，保留以兼容旧书签 |
| `GET /planner.html`、`GET /planner` | 行程工作台（规划 / 票务 / 我的行程三个视图，靠 hash 切换；未登录由页内脚本跳回 `/?next=/planner.html`） |
| `GET /api-console.html` | 接口测试台（登录卡与测试台同页切换），无需登录即可打开 |
| `GET /login.html`、`GET /app.html` | 旧版两个页面的地址，302 到 `/`，保留以兼容旧书签 |
| `GET /favicon.ico` | 站点图标，避免未登录时打日志噪音 |

其余 `/api/**` 均要求登录，未登录直接返回 **401**（不跳转登录页，便于 JSON 客户端处理）。
`/api/location/**`（高德）和 `/api/agent/**`（规划）除上表三个状态接口外都需要登录：
前者按调用量计费，后者消耗模型配额。

> 已修正的历史项：`SecurityConfiguration` 曾放行 `/register.html`，但 `static/` 下并无该文件（注册在页面内切换标签完成）。该放行已于 2026-11-05 随前端整合一并删除。

### 1.4 统一错误响应
除登录/退出外，错误统一为 JSON：

```json
{ "error": "错误描述" }
```

| HTTP | 含义 | 触发场景 |
|---|---|---|
| 400 | 请求参数/校验不合法 | 注册字段不合规、缺 `version`、类型错误、**行程标题为空**、**费用金额为负**、**住宿离店早于入住**、**邀请未注册用户 / 邀请自己 / 邀请已是成员**、**创建者尝试退出行程** |
| 401 | 未登录 | 访问受保护接口但未带有效会话 |
| 403 | 无权限 | 非参与者 / 成员无该板块编辑权 / 非 owner 操作成员或删除 / 非 owner 发起或撤回邀请 / 非受邀者处理邀请 |
| 404 | 资源不存在 | 行程 `id` 不存在；**成员不在成员列表中**；**邀请 `id` 不存在** |
| 409 | 版本冲突 | 提交 `version` 与服务端不一致（响应头带回 `X-Version`）；**邀请已被接受/拒绝/撤回后再次处理** |
| 503 | 依赖不可用 | **景点知识库缺失或损坏**，查询已停用，响应带 `reason` |
| 201 | 创建成功 | 注册 / 创建行程 / 首次发出邀请（带 `Location`） |
| 200 | 复用已存在资源 | 向同一受邀人重复发起邀请：不新增记录，只更新授权板块 |
| 204 | 成功无内容 | 成员主动退出 |

> 校验类错误信息形如 `{ "error": "费用金额不能为负数：-999.99（交通）" }`；
> 成员级 404 额外带回 `id` 与 `username`，便于区分「行程不存在」与「成员不存在」。

### 1.5 异常与状态码映射（服务端实现）

错误响应由统一异常处理生成，下表与代码一一对应，便于排查「接口返回 400/403/404/409 到底是哪条规则触发的」。

| 异常 | HTTP | 响应体 | 触发场景 |
|---|---|---|---|
| `InvalidAccountException` | 400 | `{ "error": "<原因>" }` | 注册时用户名/密码不符合长度与字符规则 |
| `DuplicateAccountException` | 409 | `{ "error": "账号已存在: xxx" }` | 注册时用户名已存在 |
| `MethodArgumentNotValidException` | 400 | `{ "error": "<首个字段校验消息>" }` | 请求体 Bean Validation 失败（如 `title` 为 `@NotBlank`） |
| `InvalidItineraryException` | 400 | `{ "error": "<原因>" }` | 标题为空、费用为负、住宿日期倒置、邀请未注册用户等业务校验 |
| `ItineraryForbiddenException` | 403 | `{ "error": "无权限" }` | 非参与者访问；成员编辑未授权板块；非 owner 管理成员或删除 |
| `ItineraryNotFoundException` | 404 | `{ "error": "行程不存在", "id": "..." }` | 行程 ID 不存在 |
| `MemberNotFoundException` | 404 | `{ "error": "成员不存在", "id": "...", "username": "..." }` | 目标用户不在成员列表中（不递增版本号） |
| `VersionConflictException` | 409 | `{ "error": "版本冲突，已被他人修改", "currentVersion": N }`，响应头带 `X-Version` | 提交的 `version` 与服务端不一致 |
| `AttractionUnavailableException` | 503 | `{ "error": "景点查询已停用", "reason": "<原因>" }` | 景点库文件缺失、JSON 解析失败或结构异常 |
| `InvitationNotFoundException` | 404 | `{ "error": "邀请不存在", "invitationId": "..." }` | 邀请 ID 不存在 |
| `InvitationConflictException` | 409 | `{ "error": "邀请已被处理，不能再次变更", "invitationId": "...", "status": "ACCEPTED" }` | 邀请已接受/已拒绝/已撤回，不能再次变更 |
| `InvalidCommentException` | 400 | `{ "error": "<原因>" }` | 留言内容为空或超过 2000 字 |
| `InvalidTicketQueryException` | 400 | `{ "error": "请提供景点 id 或景点名称" }` | 购票查询既没给 `attractionId` 也没给 `name` |
| `AmapUnavailableException` | 503 | `{ "error": "外部信息查询不可用", "reason": "<原因>" }` | 未配置 `AMAP_KEY`、服务不可达、配额不足或高德返回错误 |
| `InvalidPlanningRequestException` | 400 | `{ "error": "<原因>" }` | 规划请求缺 `destination` |
| `ItineraryArchivedException` | 409 | `{ "error": "行程已归档，当前为只读状态…", "id": "...", "archived": true }` | 对已归档行程做任何写操作（含成员、候选、投票） |
| `InvalidCandidateException` | 400 | `{ "error": "<原因>" }` | 候选名称为空/超长/重复，投票选项缺失，撤回没有的票 |
| `CandidateNotFoundException` | 404 | `{ "error": "候选项不存在", "candidateId": "..." }` | 候选 ID 不存在或不属于该行程 |

> 模型调用失败**不**走异常映射：`/api/agent/plan` 会降级为「只有检索结果、没有文字建议」，
> 把原因写进响应的 `notices`，请求本身仍是 200。

> 未登录访问受保护接口由 Spring Security 直接返回 **401**，不经过上述处理器。

> **校验顺序**：不存在（404）→ 无权限（403）→ 已归档（409）→ 版本冲突（409）。
> 无权限者一律只看到 403，响应体里**不含** `archived`——归档状态属于行程内部信息，
> 不该让一个与这份行程无关的人通过报错内容推断出来。

---

## 2. 账号与认证

### 2.1 注册
`POST /api/account/register`

请求体（`application/json`）：

```json
{ "username": "alice", "password": "Passw0rd123" }
```

校验规则：
- `username`：3–32 位，仅限字母、数字、下划线（`^[A-Za-z0-9_]{3,32}$`）
- `password`：至少 8 位（服务端仅校验长度，客户端页面提示另算）

响应：
- **201** 成功：`{ "username": "alice", "created": true }`
- **409** 用户名已存在：`{ "error": "账号已存在: alice" }`
- **400** 校验失败：`{ "error": "用户名需为 3-32 位字母、数字或下划线" }` 或 `"密码至少 8 位"`

> 密码以 **BCrypt 哈希**落盘 `data/accounts.json`，绝不保存明文。

### 2.2 登录
`POST /api/login`（表单字段，非 JSON）

```bash
curl -c cookies.txt -X POST http://localhost:8080/api/login \
  -d "username=alice&password=Passw0rd123"
```

响应：
- **200** 成功：`{ "authenticated": true, "username": "alice" }`（同时下发 `JSESSIONID`）
- **401** 失败：`{ "authenticated": false, "error": "用户名或密码错误" }`

### 2.3 查询当前用户
`GET /api/account/me`（需登录，带 `JSESSIONID`）

```bash
curl -b cookies.txt http://localhost:8080/api/account/me
```

响应：**200** `{ "username": "alice", "roles": ["ROLE_USER"] }`
未登录：**401** `{ "error": "未登录" }`

### 2.4 退出登录
`POST /api/logout`（需登录，带 `JSESSIONID`）

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/logout
```

响应：**200** `{ "loggedOut": true }`，并清除 `JSESSIONID` Cookie。

### 2.5 健康检查
`GET /api/health`（公开）

响应：**200** `{ "status": "UP" }`

---

## 3. 行程管理

行程是聚合根：**归属者 `owner` + 成员 `members` + 五大板块数据**，整体持有**一个乐观锁版本号 `version`**。

五大板块与授权枚举 `Section` 一一对应：

| Section 值 | 板块 | 含义 |
|---|---|---|
| `PLAN` | 方案 | 目的地/摘要/备注 |
| `DAILY` | 每日安排 | 按天排的活动 |
| `TRANSPORT` | 交通 | 班次/起止/座位 |
| `LODGING` | 住宿 | 酒店/入离店 |
| `EXPENSE` | 费用 | 分类/金额/币种 |

### 3.1 权限模型（板块级细粒度）
- **owner**：完全权限（查看 + 编辑全部板块 + 管理成员 + 删除）。
- **member**：默认可**查看**；仅当 `editSections` 包含某板块时，才能**编辑**该板块。
- **非参与者**：任何访问返回 **403**。

### 3.2 乐观锁（版本控制）
- 每份行程有 `version`（创建时为 `1`）。**任何写操作（含成员变更）都会使 `version +1`**。
- 客户端写前须先读取当前 `version`，并在写请求中通过查询参数 `?version=<当前值>` 提交。
- 服务端比对：不一致 → **409** 拒绝覆盖，响应头 `X-Version` 带回服务端最新版本，body `{ "error": "版本冲突，已被他人修改", "currentVersion": N }`。
- **并发语义**：服务端在「按行程维度的写锁」内完成「校验版本 → 写入」，因此 N 个请求同时提交同一版本时，
  恰好 **1 个成功（200）**，其余 **409**，不存在后写覆盖前写导致的静默丢失更新。
- 读取当前版本的方式：
  - 板块 `GET`（§3.5–3.9）在响应头 `X-Version` 返回；
  - 行程详情 `GET /api/itineraries/{id}`（§3.4）在 body 的 `version` 字段返回（成员管理须用此值）。

---

### 3.3 创建行程
`POST /api/itineraries`（需登录）

请求体：

```json
{
  "title": "国庆云南行",
  "plan": { "destination": "云南", "summary": "昆明-大理-丽江", "notes": "备好防晒" }
}
```

- `title`：必填字符串，**去空白后不得为空**，否则 **400** `{ "error": "行程标题不能为空" }`。
- `plan`：可选（省略则创建空方案）。

响应：**201** `Location: /api/itineraries/{id}`，body 为完整 `Itinerary`（见 §4.1），`owner` 为当前登录用户，`version=1`。

### 3.4 列出我的行程
`GET /api/itineraries`（需登录）

返回当前用户作为 **owner 或成员** 的全部行程（不含已无权访问他人的）。

响应：**200** `Itinerary[]`（见 §4.1）。

### 3.5 行程详情
`GET /api/itineraries/{id}`（需登录，参与者可见）

响应：**200** 完整 `Itinerary`（含 `version` 字段，供后续写操作使用）；不存在 **404**；无权限 **403**。

### 3.6 删除行程
`DELETE /api/itineraries/{id}`（**仅 owner**）

响应：**200** `{ "deleted": true }`；非 owner **403**；不存在 **404**。

---

### 3.7 方案 PLAN
- **查看**：`GET /api/itineraries/{id}/plan`（参与者）→ **200** `Plan`，响应头含 `X-Version`。
- **更新**：`PUT /api/itineraries/{id}/plan?version={v}`（owner 或 `editSections` 含 `PLAN`）→ **200** 完整 `Itinerary`（version 已 +1）。
  请求体：`Plan`（见 §4.2）。

> `plan` 自 2026-11-04 起支持 `departDate` / `returnDate`（`YYYY-MM-DD`）。两者都填写且返回早于出发时返回 **400**
> `{ "error": "返回日期不能早于出发日期：2026-11-12 → 2026-11-10" }`，且**版本号不变**；任一为空不校验（缺失由 §3.11 体检提示）。

### 3.8 每日安排 DAILY
- **查看**：`GET /api/itineraries/{id}/daily`（参与者）→ **200** `DailySchedule[]`，响应头含 `X-Version`。
- **更新**：`PUT /api/itineraries/{id}/daily?version={v}`（owner 或含 `DAILY`）→ **200** `Itinerary`。
  请求体：`DailySchedule[]`（见 §4.3）。子项 `id` 缺失时由服务端自动补全。

### 3.9 交通 / 住宿 / 费用
结构一致，权限分别对应 `TRANSPORT` / `LODGING` / `EXPENSE`。

| 板块 | 查看 | 更新（带 `?version`） |
|---|---|---|
| 交通 | `GET /api/itineraries/{id}/transports` | `PUT /api/itineraries/{id}/transports?version={v}` |
| 住宿 | `GET /api/itineraries/{id}/lodgings` | `PUT /api/itineraries/{id}/lodgings?version={v}` |
| 费用 | `GET /api/itineraries/{id}/expenses` | `PUT /api/itineraries/{id}/expenses?version={v}` |

响应：查看返回对应 `List`（响应头 `X-Version`）；更新返回完整 `Itinerary`。
子项 `id` 缺失由服务端补全（保证稳定引用）。

更新时的字段校验（不通过返回 **400**）：

| 板块 | 规则 | 错误示例 |
|---|---|---|
| 费用 | `amount` 必须为**非负数**（`null` 视为未填，不拦截） | `{ "error": "费用金额不能为负数：-999.99（交通）" }` |
| 住宿 | `checkOut` 不得早于 `checkIn`（任一为空则不校验） | `{ "error": "离店日期不能早于入住日期：2026-11-12 → 2026-11-10（西湖边民宿）" }` |

---

### 3.10 成员管理（仅 owner）
所有成员操作均需提交行程当前 `version`（从 `GET /api/itineraries/{id}` 的 body 读取）。

- **列出成员**：`GET /api/itineraries/{id}/members` → **200** `Member[]`。
- **添加/替换成员**：`POST /api/itineraries/{id}/members?version={v}`
  请求体：
  ```json
  { "username": "bob", "editSections": ["TRANSPORT", "LODGING"] }
  ```
  若 `username` 已存在则替换为新权限。
  **该用户必须已注册**，否则 **400** `{ "error": "用户不存在：xxx（只能邀请已注册用户）" }`。
- **修改成员权限**：`PUT /api/itineraries/{id}/members/{username}?version={v}`
  请求体同上为 `MemberRequest`。`username` 不在成员列表中 → **404**
  `{ "error": "成员不存在", "id": "...", "username": "..." }`（不会递增版本号）。
- **移除成员**：`DELETE /api/itineraries/{id}/members/{username}?version={v}`
  同上，目标不是成员时 → **404**。

> 成员仅被授予的板块可编辑；查看权默认对全部成员开放。owner 不可被移除（自身即归属者）。
>
> 写操作在服务端按行程加锁执行「校验版本 → 写入」，并发提交同一 `version` 时**只有一个成功**，
> 其余返回 **409**，不会出现静默丢失更新。

---

### 3.11 预算汇总与行程体检（只读，参与者）

| 接口 | 说明 |
|---|---|
| `GET /api/itineraries/{id}/budget` | 预算汇总，响应头含 `X-Version` |
| `GET /api/itineraries/{id}/checks` | 行程体检，响应体内含 `version` |

两个接口都是只读的，**不会递增版本号**；权限与行程查看权一致（owner 或成员），非参与者 **403**，未登录 **401**。

`budget` 响应（多币种示例）：

```json
{
  "primaryCurrency": "CNY",
  "totals": [
    { "currency": "CNY", "amount": 1400.00, "itemCount": 2 },
    { "currency": "USD", "amount": 100.00, "itemCount": 1 }
  ],
  "byCategory": [
    { "category": "交通", "currency": "CNY", "amount": 600.00, "itemCount": 1 },
    { "category": "住宿", "currency": "CNY", "amount": 800.00, "itemCount": 1 },
    { "category": "交通", "currency": "USD", "amount": 100.00, "itemCount": 1 }
  ],
  "itemCount": 3,
  "pendingCount": 1,
  "pending": [ { "id": "…", "category": "餐饮", "note": "未询价" } ],
  "currencies": ["CNY", "USD"],
  "mixedCurrency": true,
  "notes": [
    "存在 2 种币种（CNY、USD），本汇总未做汇率换算，请按币种分别核对，不要把不同币种的金额直接相加",
    "有 1 笔费用未填写金额，已标记为待填写，未计入合计"
  ]
}
```

汇总口径（**与验收相关，不能简化**）：

- **不跨币种相加**：不同币种分别成行、按「币种 + 类别」拆分。100 元与 100 美元相加得 200 是错的；汇率换算需要外部汇率源与时间点，本版本未实现，只给出提示。
- `primaryCurrency`：条目最多的币种；平票时 `CNY` 优先，再按字典序，保证同一份数据每次结果一致。
- `amount` 为 `null` 的费用进 `pending`，**不计入合计**；`amount` 为负的照实计入并在 `notes` 中点明（写入时已拦截，能出现的只可能是历史数据）。
- `category` 为空归入「未分类」，`currency` 为空按 `CNY`；金额统一两位小数（`HALF_UP`），接口按 `1400.00` 输出，不会退化成 `1400.0`。

`checks` 响应：

```json
{
  "itineraryId": "…",
  "version": 3,
  "ok": false,
  "warnings": [
    { "code": "EXPENSE_AMOUNT_MISSING", "field": "expenses", "message": "有 1 笔费用未填写金额" },
    { "code": "MIXED_CURRENCY", "field": "expenses", "message": "存在多种币种，未做汇率换算，预算总额按币种分别给出" }
  ]
}
```

`code` 为稳定标识（文案可调整，代码不变）：

| code | 含义 |
|---|---|
| `DEPART_DATE_MISSING` / `RETURN_DATE_MISSING` | 出发/返回日期未填写 |
| `RETURN_BEFORE_DEPART` | 返回早于出发（写入已拦截，仅历史数据会出现） |
| `EXPENSE_AMOUNT_MISSING` | 有费用未填金额 |
| `EXPENSE_NEGATIVE` | 有负数金额 |
| `NO_EXPENSE` | 尚未记录任何费用 |
| `MIXED_CURRENCY` | 存在多币种 |
| `DAILY_OUT_OF_RANGE` / `TRANSPORT_OUT_OF_RANGE` / `LODGING_OUT_OF_RANGE` | 安排日期超出旅行时间范围 |
| `LODGING_PERIOD_INVALID` | 离店早于入住（历史数据） |
| `ACTIVITY_TIME_MISSING` | 某天所有安排都没填起止时间（一天最多一条，不逐条刷屏） |
| `ACTIVITY_TIME_INVALID` | 安排结束早于开始（写入已拦截，仅历史数据会出现） |
| `ACTIVITY_TIME_OVERLAP` | 同日两项安排时间重叠，需用户确认是否调整 |

> `ACTIVITY_TIME_*` 为 V2 §3 新增。重叠用扫描线找出「与当前最晚结束项相交」的对，
> 既不会漏报，也不会把 n 项互相重叠展开成 n² 条提示；首尾相接（前一项 12:00 结束、
> 后一项 12:00 开始）不算重叠。整天只有自由文本时间（如「上午」）时**不判**为缺失，
> 那是有意的模糊安排。

`ok=true` 表示没有需要用户处理的提示。日期区间倒置时**只报** `RETURN_BEFORE_DEPART`，不再逐条报越界——否则每条安排都会再报一次，噪声会掩盖真正的问题。

---

### 3.12 邀请与成员退出（需求 §4）

流程：**创建者发出邀请 → 受邀者登录后看到待处理邀请 → 接受（成为成员）或拒绝 → 创建者可撤回**。

**邀请不是成员**：发出邀请不改变行程 `version`，受邀者在接受前访问行程仍为 **403**。
这样创建者发邀请不会让正在编辑的人凭空撞上版本冲突。

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/itineraries/{id}/invitations` | POST | 仅创建者 | 发起邀请，请求体 `InviteRequest` |
| `/api/itineraries/{id}/invitations` | GET | 仅创建者 | 该行程的邀请记录（含已处理），**不需要** `version` |
| `/api/invitations` | GET | 需登录 | 我收到的待处理邀请，最新在前 |
| `/api/invitations/{invId}/accept` | POST | 仅受邀者 | 接受并成为成员 |
| `/api/invitations/{invId}/reject` | POST | 仅受邀者 | 拒绝 |
| `/api/invitations/{invId}` | DELETE | 仅创建者 | 撤回待处理的邀请 |
| `/api/itineraries/{id}/leave` | POST | 仅成员 | 主动退出，返回 **204** |

**发起邀请**

```json
POST /api/itineraries/{id}/invitations
{ "username": "guest_b", "editSections": ["DAILY", "EXPENSE"] }
```

- **201** 首次发出（带 `Location: /api/invitations/{invId}`，响应体 `InvitationView`）
- **200** 同一行程同一受邀人已有待处理邀请：**不新增记录**，只更新 `editSections` 后重新发出
- **400** 用户名为空 / 受邀账号未注册 / 邀请自己 / 该用户已是成员
- **403** 非创建者
- **404** 行程不存在

**接受邀请**

```
POST /api/invitations/{invId}/accept
```

- **200** `InvitationView`（`status=ACCEPTED`，`respondedAt` 已填）
- **403** 该邀请不是发给我的
- **404** 邀请不存在
- **409** 邀请已被处理（`status` 回带当前状态）

> **接受不需要 `version`**：加入成员是受邀者对邀请的响应，不是与创建者的并发编辑。
> 若要求版本号，受邀者得先取到行程最新版本，而创建者随时可能改动，一步能成的事会变成反复冲突。
> 服务端在行程写锁内以「当前版本 + 1」写入，版本号仍严格递增。

**撤回 / 拒绝** 同理，只有 `PENDING` 可处理；行程被删除时，其所有待处理邀请自动置为 `CANCELLED`。

**成员退出**

```
POST /api/itineraries/{id}/leave     → 204
```

- **400** 创建者尝试退出（行程必须始终有归属人，需删除行程）
- **404** 不是该行程成员

> `POST /api/itineraries/{id}/members`（§3.10）保留为**创建者直接授权**的管理入口，与邀请流程并存；
> 主流程建议走邀请，受邀者才有机会拒绝。

---

### 3.13 协作记录与留言（V2 §4）

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/itineraries/{id}/comments` | GET | 参与者 | 该行程的留言，按时间升序 |
| `/api/itineraries/{id}/comments` | POST | 参与者 | 发表留言，返回 **201** |
| `/api/itineraries/{id}/changelog` | GET | 参与者 | 修改记录，按时间倒序，`limit` 默认 50、上限 200 |

**这两条都是旁路数据，不带 `version` 参数，也不会让行程版本号 +1。**
留言是讨论，不是对方案的编辑；如果它推动版本号，正常聊天会把正在编辑的人挤进 409。

**发表留言**

```json
POST /api/itineraries/{id}/comments
{ "content": "第二天想加个灵隐寺" }
```

- **201** 返回 `Comment`，`author` 取自服务端会话，客户端无法自称
- **400** 内容为空，或超过 2000 字
- **403** 非该行程参与者
- **404** 行程不存在

**修改记录**

```json
GET /api/itineraries/{id}/changelog?limit=20

[
  { "id": "...", "itineraryId": "...", "actor": "owner_a",
    "at": "2026-11-04T12:25:00Z", "section": "DAILY",
    "summary": "更新每日安排：1 天 / 2 项", "version": 2 },
  { "id": "...", "itineraryId": "...", "actor": "owner_a",
    "at": "2026-11-04T12:24:00Z", "section": "ITINERARY",
    "summary": "创建行程「V2联调」", "version": 1 }
]
```

- `section` 取值：`ITINERARY` / `PLAN` / `DAILY` / `TRANSPORT` / `LODGING` / `EXPENSE` / `MEMBER`
- **只由服务端在写操作成功后产生**，客户端不提交、也无法伪造；`actor` 一律取会话身份
- 单个行程保留最近 500 条，超出丢弃最旧的；行程删除时留言与记录一并清空
- 记录写入失败只记 WARN，**不会**让已经落盘的编辑回滚

---

### 3.14 购票辅助（V2 票务 Agent）

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/tickets` | GET | 公开 | 按 `attractionId` 或 `name` 查询购票辅助 |
| `/api/tickets/channels` | GET | 公开 | 已收录的官方入口清单 |

**这一节的核心是「没有时怎么说」**：只展示人工核验过的官方入口，未收录时明确提示自行前往官方渠道，
**不抓取、不推断、不生成任何链接**；余票恒为未核验。

```json
GET /api/tickets?name=西湖

{
  "attractionId": "t-hz-001",
  "attractionName": "西湖",
  "city": "杭州",
  "status": "CHANNEL_FOUND",
  "stockVerified": false,
  "channels": [
    { "attractionId": "t-hz-001", "attractionName": "西湖", "city": "杭州",
      "channelName": "西湖风景名胜区官方预约",
      "officialUrl": "https://www.hzwestlake.gov.cn/ticket",
      "ticketTypes": [
        { "name": "游船（三潭印月）", "price": 55.00, "currency": "CNY", "priceNote": "含上岛" },
        { "name": "环湖观光车", "price": null, "currency": null, "priceNote": "按段计费，现场公示" }
      ],
      "rules": ["需实名预约", "节假日建议提前 3 天"],
      "source": "景区官网公示页面，人工核对",
      "verifiedAt": "2026-11-01",
      "note": "湖区本身免费开放" }
  ],
  "notice": "余票未核验：本系统未接入景区库存接口，购票前请以官方页面显示为准。",
  "source": "景区官网公示页面，人工核对",
  "queriedAt": "2026-11-04T12:25:04Z"
}
```

**状态取值**

| `status` | 含义 | `notice` |
|---|---|---|
| `CHANNEL_FOUND` | 命中配置中的官方入口 | 余票未核验（未记录核验日期时额外提示再次确认） |
| `NO_VERIFIED_CHANNEL` | 没有经核验的入口 | 请自行进入景区官方网站或官方公众号，本系统不生成猜测链接 |

- **400** 既没给 `attractionId` 也没给 `name`
- 票价未知时 `price` 为 `null`，**不补成 0**——0 元会让预算把「不知道」算成「免费」
- 同上：`stockVerified` 恒为 `false`，这一阶段没有接入任何库存接口

**入口配置**（人工维护，默认不存在）

```json
// <data-directory>/ticket_channels.json
{
  "schema": "ticket-channel-v1",
  "channels": [
    {
      "attractionId": "t-hz-001", "attractionName": "西湖", "city": "杭州",
      "channelName": "西湖风景名胜区官方预约",
      "officialUrl": "https://www.hzwestlake.gov.cn/ticket",
      "ticketTypes": [], "rules": [], "source": "官网公示", "verifiedAt": "2026-11-01", "note": ""
    }
  ]
}
```

也接受纯数组。加载规则：

- 文件不存在 → 以「无经核验入口」运行，这是**默认状态**，仓库不内置任何链接
- 解析失败 → 停用购票辅助并记 WARN，**不覆盖原文件**，留下现场供人工修复
- 地址不是 `http/https` 绝对地址 → 丢弃该条并记 WARN，不能让它变成可点链接

---

### 3.15 外部信息查询（V2 §1，高德）

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/location/places` | GET | 需登录 | `keywords` 必填，`city` 可选，`limit` 默认 10 / 上限 25 |
| `/api/location/weather` | GET | 需登录 | `city` 支持城市名或 adcode |
| `/api/location/route` | GET | 需登录 | `mode`=WALKING/DRIVING/TRANSIT，`origin`/`destination` 为「经度,纬度」 |
| `/api/location/status` | GET | 公开 | 是否已配置密钥；只返回布尔、不触发计费调用，匿名可读 |

**places / weather / route 需要登录**：高德接口按调用量计费，对外开放会让配额被匿名消耗。`status` 是例外——只返回是否配置密钥、不发起计费调用，故在安全白名单里匿名可读。

**未配置密钥（`AMAP_KEY` 为空）时一律 503，不是 200 + 空结果。**
那会把「没接服务」表现成「当地没有这个地方」。

```json
// 503
{ "error": "外部信息查询不可用",
  "reason": "未配置高德 Web 服务密钥（环境变量 AMAP_KEY），地点、天气与路线查询已停用；行程、景点、预算与协作功能不受影响" }
```

**地点检索**

```json
GET /api/location/places?keywords=西湖&city=杭州&limit=5

{
  "keyword": "西湖", "city": "杭州",
  "places": [
    { "name": "西湖", "type": "风景名胜", "address": "龙井路1号",
      "city": "杭州市", "district": "西湖区", "tel": "0571-1234",
      "lat": 30.259, "lon": 120.13, "coordinateSystem": "GCJ-02",
      "distanceMeters": "0", "rating": 4.7, "commentCount": null,
      "poiId": "B00155MN8E", "detailUrl": null }
  ],
  "source": "高德地图 Web 服务 · 地点检索",
  "queriedAt": "2026-11-04T12:30:00Z",
  "notice": null
}
```

- 坐标固定标注 `GCJ-02`，**不能**与 WGS-84 直接混用
- 结果为空时 `notice` 给出可操作提示，而不是干巴巴的空数组
- 地点检索改用 `extensions=all` 才带 `biz_ext.rating` 地图用户评分；没有评分时 `rating` 为 `null`，不补默认分。`commentCount` 是百度 `detail_info.comment_num` 的评价数，高德 place/text 不提供该字段时为 `null`；**`biz_ext.cost` 是人均消费、不是门票价**，不下发、也不展示成票价
- 对话里的景点候选按真实评分降序展示（无评分的排后），评分只用于排序与展示，不代表官方评级；地图实时检索额外用评价数做可信度加权，避免「6 条评价的 5.0」压过「53 条评价的 4.6」
- `poiId` / `detailUrl` 只用于打开**地图官方详情页看真实评论**：高德返回 POI `id`，百度区域检索返回 `uid` 与 `detail_info.detail_url`（官方 POI 详情 / UGC 评论页）。两家的 Web 服务 API **都不返回评论正文**（实测 75 条 POI，高德 `featured_reviews` 全为空数组），平台条款也禁止抓取 UGC，因此对话景点卡的「N 条评价」点击后展开说明面板并跳官方页面，不内联、不生成评论文字。链接统一过 `ReviewLinks` 的官方域名白名单（`www.amap.com`、`ditu.amap.com`、`uri.amap.com`、`map.baidu.com`、`api.map.baidu.com`）并强制 https；知识库条目没有 POI id 时只给 `uri.amap.com` 官方搜索页，并明确标注是搜索页而非评论页

**天气预报**

```json
GET /api/location/weather?city=杭州

{
  "city": "杭州", "adcode": "330100", "reportTime": "2026-11-04 18:00:00",
  "days": [ { "date": "2026-11-05", "dayWeather": "多云", "nightWeather": "晴",
              "dayTemp": "22", "nightTemp": "14", "dayWind": "东北", "dayPower": "1-3",
              "nightWind": "东北", "nightPower": "1-3" } ],
  "source": "高德地图 Web 服务 · 天气预报",
  "queriedAt": "2026-11-04T12:30:00Z"
}
```

- 天气接口只认 adcode；传城市名时服务端先走地理编码解析，解析不到直接报错，**不拿猜的代码去查**
- 这是**预报**，不是实时观测，展示时必须说明

**路线规划**

```json
GET /api/location/route?mode=WALKING&origin=120.1,30.2&destination=120.2,30.3

{
  "mode": "WALKING", "originLngLat": "120.1,30.2", "destinationLngLat": "120.2,30.3",
  "distanceMeters": 1600, "durationSeconds": 1200,
  "distanceText": "1.6 公里", "durationText": "20 分钟",
  "steps": [ { "instruction": "向东步行 300 米", "road": "北山街",
               "distanceMeters": 300, "durationSeconds": 240 } ],
  "source": "高德地图 Web 服务 · 路线规划",
  "queriedAt": "2026-11-04T12:30:00Z"
}
```

- 距离与耗时都是**预估**，不含等车、排队、检票时间
- 公交必须给 `city`，否则报错；坐标格式不合法直接拒绝，不把错误坐标发给高德

**错误码翻译**（`infocode` 保留在消息里便于排查）

| infocode | 提示 |
|---|---|
| 10001 / 10009 | 密钥无效或与绑定平台不符 |
| 10002 / 10010 / 10016 / 10019 / 10041 | 当前密钥没有该接口权限 |
| 10003 / 10012 / 10017 / 10044 | 当日调用量已达上限 |
| 10004 | 请求过于频繁 |
| 10005 / 10006 / 10029 | IP 或域名不在白名单 |
| 10013 / 10014 / 10015 | 服务未开通或已过期 |
| 20000 / 20001 / 20002 | 请求参数不合法 |

---

### 3.16 规划草案与确认保存（V2 §2，2026-11-08 补齐 #5 闭环）

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/api/agent/plan` | POST | 需登录 | 生成规划草案（补问 / 逐日草案 / 检查），**不落库** |
| `/api/agent/plan/materialize` | POST | 需登录 | 用户确认后生成行程载荷，**仍不落库** |
| `/api/agent/invoke` | POST | 需登录 | L1 编排层入口，按 intent 走注册表并做契约校验，见 §3.16.1 |
| `/api/agent/status` | GET | 无需登录 | 模型是否配置、规则能力清单、本进程模型用量，见 §3.16.4 |

**两条草案接口都只读。** 需求要求「用户确认后才将建议保存到正式方案」，真正落库仍走
§3.3 `POST /api/itineraries` 与 §3.8 `PUT /api/itineraries/{id}/daily?version=<创建版本号>`，
两者之间隔着一次明确的人工确认。

请求新增 `departDate` / `returnDate`（可空，缺失时进入结构化补问）：

```json
POST /api/agent/plan
{
  "destination": "杭州",
  "departDate": "2026-11-20",
  "returnDate": "2026-11-21",
  "days": 2,
  "preferences": ["人文"],
  "budget": 1000,
  "currency": "CNY",
  "travelers": 2
}
```

原有 `request / candidates / weather / tickets / advice / notices / sources / generatedAt`
保持不变，新增四个字段（示例为节选）：

```json
{
  "questions": [
    { "code": "MISSING_BUDGET", "field": "budget", "question": "请填写预算上限",
      "hint": "数字，单位由 currency 决定（默认 CNY）", "retryWith": "POST /api/agent/plan" }
  ],
  "dailySchedules": [
    { "dayIndex": 1, "date": "2026-11-20", "note": "第 1 天；时间待确认，景点信息来自本地知识库快照",
      "items": [
        { "attractionId": "t-hz-001", "name": "西湖", "city": "杭州",
          "source": "OpenStreetMap + Amap", "ticket": "免费", "openTime": "全天开放",
          "unknowns": [], "note": "attractionId=t-hz-001；来源=OpenStreetMap + Amap；未知项=无" }
      ] }
  ],
  "checks": [
    { "code": "UNKNOWN_TICKET_PRICE", "severity": "MEDIUM", "field": "budget",
      "message": "有 2 个景点票价未知或未核验，未计入预算粗估",
      "suggestion": "核实票价后再判断；未知不等于免费" }
  ],
  "budgetEstimate": {
    "currency": "CNY", "budgetLimit": 1000, "travelers": 2,
    "knownTicketSubtotal": 0, "totalForKnownTickets": 0, "unknownTicketCount": 2,
    "verdict": "CANNOT_DETERMINE", "message": "仅门票口径，不含交通、住宿、餐饮"
  }
}
```

**规则口径（全部确定性，与模型是否配置无关）**：

- 缺 `departDate` / `returnDate` / `days` / `travelers` / `budget` 时逐条生成 `questions`；
  只填日期未填天数时按含首尾区间换算天数，并在 `notices` 注明来源。
- 逐日草案每天最多 2 个候选，按「等级 → 评分 → 名称」的排序结果等分；不编造游览时刻，
  `time` 一律「待定」；超出旅行日期区间的天不给 `date`。
- `unknowns` 明确列出票价 / 开放时间 / 来源 / ID 未知，占位值（「暂无」）与 `null` 同义，
  展示为「未知」，不推断为免费或全天开放。
- `checks` 覆盖 `RETURN_BEFORE_DEPART`、`DEPART_IN_PAST`、`DAYS_DATE_MISMATCH`、
  `DRAFT_DAY_OUT_OF_RANGE`、`DATE_INCOMPLETE`、`BUDGET_EXCEEDS_LIMIT`、
  `UNKNOWN_TICKET_PRICE`、`NO_BUDGET` 等，每条带 `severity` 与 `suggestion`。
- 预算只统计 `verified` / `confirmed` 且能解析出金额的门票；`verdict` 为
  `NO_BUDGET` / `EXCEEDS_LIMIT` / `CANNOT_DETERMINE` / `WITHIN_KNOWN_SCOPE` 之一。
  不含交通、住宿、餐饮，票价未知时只说「无法判定」，不说「没超支」。

**编排方式**：由 Java 决定调用哪些工具（景点 → 天气 → 票务），模型只负责组织语言。
每条建议都能追溯到来源，不会出现「没查就答」。

**候选排序**：等级（5A > 4A > 3A）→ 评分 → 名称。知识库按名称排列，
直接截断会拿到字母序靠前的普通景点，把故宫、颐和园挤掉。

**降级是显式的，最差情况也不是「什么都给不了」**：

| 情况 | 行为 |
|---|---|
| 景点库不可用 | `candidates` 为空 + `notices` 说明原因，请求仍是 200 |
| 高德未配置 | 不查天气，`notices` 说明「未接入实时天气」 |
| 模型未配置或调用失败 | `advice` 为 `null` + `notices` 说明原因，候选照常返回 |

- **400** `destination` 为空
- 候选为空时先按 `preferences` 兜底检索，并提示「请确认目的地」

#### 确认载荷与落库顺序

```json
POST /api/agent/plan/materialize
{ "title": "杭州二日游", "request": { ...同上... }, "attractionIds": [] }
```

返回 `{ title, plan, dailySchedules, checks, notices, sources, generatedFrom }`。
`attractionIds` 为空表示沿用草案已分配的景点；非空时按给出的顺序重新排到各天。
服务端按 ID **重新从知识库读取**名称、票价、开放时间与来源，客户端回传不了结论；
库里不存在的 ID、缺日期、日期倒置、天数超出日期区间一律 **400**。

落库顺序（前端「① 生成草案 → ② 确认保存」与 `tools/acceptance.py` 一致）：

1. `POST /api/itineraries` 带 `title` + `plan` → **201**，记下响应里的 `version`；
2. `PUT /api/itineraries/{id}/daily?version=<version>` 带 `dailySchedules` → **200**；
3. 重启服务后 `GET /api/itineraries/{id}` 仍能读到同一份逐日安排。

每日安排不改领域模型：景点 ID、来源与未知项写在 `DailyActivity.note` 的结构化前缀
`attractionId=...；来源=...；未知项=...` 里，避免为草案新增字段而改动历史数据与既有测试。

#### 3.16.1 L1 编排入口（`POST /api/agent/invoke`）

按 `intent` 走 Provider 注册表：逐个 invoke、响应过契约校验（`ContractValidator`），
不通过就切下一个，全失败落到 `builtin.rules`。**需登录**：餐饮与规划链路都会触发地图和模型配额。

```json
POST /api/agent/invoke
{ "intent": "PLAN_DRAFT", "context": { "destination": "十堰", "days": 2 } }
```

`context` 字段与 `/api/agent/plan` 的请求体同名（`destination` / `days` /
`departDate` / `returnDate` / `preferences` / `budget` / `currency` / `travelers`）。

响应是信封：`intent`、`status`、`providerId`、`registryVersion`、`attempts`
（每个 Provider 的 `status` / `reason` / `codes`）与 `result`（契约形状：
`schemaVersion` / `facts` / `advice` / `unknownFields` / `notices` / `usage`）。

**`PLAN_DRAFT` 的结构化草案放在 facts 里**，一条 `CONTENT` fact
（`factId=builtin.planning:draft-summary`，`value.category=PLAN_DRAFT`），
含 `candidates` / `dailySchedules` / `questions` / `checks` / `budgetEstimate` / `advice`，
键名与 `/api/agent/plan` 一致，客户端不必维护两套解析。

信封的 `advice` 只放无数字、无断言的摘要（与美食 Provider 同口径）：模型正文若照抄
知识库里的「免费」「全天开放」，会触发 S-03（未知不得写成确定值），
把整条响应判为 `CONTRACT_VIOLATION`。正文与明细一律进 `value` 由前端渲染。

#### 3.16.2 能力协商与 Provider 管理

注册表对外暴露三档信息：能力协商（公开）、路由与准入明细（需登录）、注册表管理（独立管理令牌）。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/api/agent/capabilities` | 公开 | 契约版本、注册表版本与每个 Provider 的状态/能力标签；不含 endpoint、authRef 或凭据 |
| `GET` | `/api/agent/providers` | 需登录 | capabilities 全量 + 每个 intent 的 `primary` / `fallback` 路由 |
| `GET` | `/api/agent/providers/{id}/admission` | 管理令牌 | 该 Provider 的准入报告；id 不存在返回 404 |
| `POST` | `/api/agent/providers/reload` | 管理令牌 | 重新加载配置式 Provider，返回新版本与数量 |
| `POST` | `/api/agent/providers/{id}/probe` | 管理令牌 | G1 契约探针 + G2 安全探针，按配置发真实请求，不注入桩 |
| `POST` | `/api/agent/providers/{id}/shadow?samples=N` | 管理令牌 | G3 影子；`samples` 缺省 0 时读规范源 `shadowSamples`（20） |
| `POST` | `/api/agent/providers/{id}/activate` | 管理令牌 | G4 人工启用：需 G3 通过且配置 `enabled=true` |

**管理令牌与用户会话解耦（F1）**：`reload` / `probe` / `shadow` / `activate` / `admission` 会改注册表状态、携服务端凭据发真实请求、消耗配额或泄漏 endpoint，因此不对任意登录用户开放。`AdminTokenFilter` 只认 `X-Admin-Token` 请求头，令牌经环境变量 `AGENT_ADMIN_TOKEN` 注入（配置键 `travel.agent.admin-token`）；未配置（空）时 fail-closed，一律 **403** `{"error":"管理端点需要有效的管理令牌"}`；比对用常量时间避免计时侧信道。匿名携带正确令牌即可管理，登录但无令牌一律拒绝。这五个路径在 `SecurityConfiguration` 里 `permitAll` 是为了与用户会话解耦，真正的门禁交给本过滤器。

`capabilities` 响应（节选，字段以实际注册表为准）：

```json
GET /api/agent/capabilities
{
  "contractVersion": "1.0", "registryVersion": 3, "registryNote": "…",
  "providers": [
    { "id": "builtin.food", "version": "1.0.0", "schemaVersion": "1.0",
      "capabilities": ["FOOD_RECOMMEND"], "kind": "BEAN", "sideEffect": "NONE",
      "enabled": true, "status": "ACTIVE", "lastRejectionCode": null }
  ]
}
```

`providers` 在此基础上追加 `routes`，形如 `[ { "intent": "FOOD_RECOMMEND", "primary": "builtin.food", "fallback": ["builtin.rules"] } ]`。`probe` / `shadow` / `activate` 统一返回 `{ report, status, registryVersion, shadow }`：`report` 为准入报告（含原因码、违规规则 id、字段路径、实际值与期望值），`status` 为该 Provider 当前状态（找不到时 `UNREGISTERED`），`shadow` 为影子统计。`reload` 返回 `{ reloaded, registryVersion, registryNote, providerCount }`。

#### 3.16.3 两轮评委投票（`POST /api/agent/vote`）

AI 评委对候选方案做两轮投票并结构化计票，**与 §3.17 的成员人投票是两套接口**，不复用、不写库。**需登录**：会触发模型与地图配额；路径不在安全白名单里，匿名返回 401。

```json
POST /api/agent/vote
{ "intent": "DECISION_ADVICE", "context": { "destination": "杭州", "days": 2 } }
```

`intent` 必填且只接受 `DECISION_ADVICE`：缺失返回 **400** `{"error":"缺少 intent"}`，非法返回 **400** `{"error":"投票只支持 DECISION_ADVICE intent"}`。`context`（Map）、`hints`（Map<String,String>）、`judges`（List<String>）均可省略；`judges` 省略时由注册表解析默认评委集合。

响应是信封加结构化计票：`intent` / `status` / `registryVersion` / `candidateSource` / `judges` / `plans` / `draft` / `collectedFactCount` / `notices`，以及 `result`：

| `result` 字段 | 说明 |
|---|---|
| `schemaVersion` / `intent` | 计票契约版本与 intent |
| `winner` | 胜出方案 id；无胜出时为 `null` |
| `ranking[]` | 每个方案的 `planId` / `score` / `voterCount` / `weightSum` / `abstained`，及 `breakdown`（各评委 `providerId` / `normalized` / `weight` / `criteria` / `groundingFactIds`） |
| `excluded[]` | 被剔除方案：`planId` / `reason` / `providers` / `groundingFactIds` |
| `rejections[]` | 契约拒绝：`providerId` / `factId` / `code`（`E_VOTE_*`）/ `detail`；只在此出现，不进准入报告 |
| `homogeneous` | 评委同质性标记 |
| `notices` | 计票过程提示 |

响应不含 endpoint、authRef 或任何凭据。

#### 3.16.4 能力状态与模型用量（`GET /api/agent/status`）

**无需登录**（`SecurityConfiguration` 的 `permitAll` 白名单内，只回布尔、统计与能力描述）。
规则能力与模型能力分开列，未配置密钥时不把「补问 / 逐日草案 / 检查 / 确认载荷」
说成没有，也不把降级路径说成具备「文字建议」能力。

```json
GET /api/agent/status
{
  "modelConfigured": true,
  "capabilities": ["景点检索", "天气", "购票辅助", "结构化补问", "逐日草案",
                   "草案日期与预算检查", "确认载荷", "综合决策分析", "文字建议"],
  "note": "模型已配置；文字建议仅为措辞润色，建议仍需用户确认后才写入正式方案",
  "usage": {
    "model": "deepseek-ai/DeepSeek-V3.2",
    "endpoint": "https://api.siliconflow.cn/v1",
    "calls": 12,
    "promptTokens": 8421,
    "completionTokens": 2103,
    "cachedPromptTokens": 1024,
    "totalTokens": 10524,
    "lastCall": "POST /api/agent/plan",
    "since": "2026-11-11T09:12:03Z",
    "balanceAvailable": false
  }
}
```

`usage` 是**本进程**的累计值，重启归零；它给的是用量不是余量——硅基流动的余额接口已下线，
程序侧查不到账户余额，故 `balanceAvailable` 恒为 `false`。`usage` 字段只在模型客户端存在时出现，
未配置密钥的部署不返回该键。

### 3.17 候选与投票（V3 §1）

成员为「要不要去某个地方」提候选并投票。**候选与选票都不进行程聚合**，因此这些操作
不改变行程版本号，也不会因为有人投票而让正在编辑日程的人撞上 409。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/itineraries/{id}/candidates` | 添加候选，`201` |
| `GET` | `/api/itineraries/{id}/candidates` | 候选看板（含票数、排序、并列与未投票提示） |
| `DELETE` | `/api/itineraries/{id}/candidates/{candidateId}` | 删除候选（提出者本人或创建者），连带清票 |
| `POST` | `/api/itineraries/{id}/candidates/{candidateId}/votes` | 投票或改票 |
| `DELETE` | `/api/itineraries/{id}/candidates/{candidateId}/votes` | 撤回自己的票 |
| `POST` | `/api/itineraries/{id}/candidates/{candidateId}/apply?version=N` | 确认候选，写入某一天的每日安排 |

添加候选：

```json
POST /api/itineraries/{id}/candidates
{ "kind": "ATTRACTION", "name": "灵隐寺", "city": "杭州", "note": "" }
```

`kind` 取 `ATTRACTION` / `LODGING` / `OTHER`，省略按景点处理。名称必填、≤80 字，
同名同类型重复添加返回 **400**（不是静默产生两条一样的候选）。

投票：

```json
POST /api/itineraries/{id}/candidates/{candidateId}/votes
{ "choice": "SUPPORT" }
```

`choice` 取 `SUPPORT` / `OPPOSE` / `ABSTAIN`。**同一人对同一候选只保留一张票**——
再次投票是改票（保留首次投票时间），不是叠加。返回更新后的看板。

看板示例：

```json
{
  "itineraryId": "…", "itineraryVersion": 4,
  "participantCount": 2, "voterCount": 2, "candidateCount": 2, "totalVotes": 3,
  "candidates": [
    { "candidate": { "id": "…", "kind": "ATTRACTION", "name": "灵隐寺",
                     "proposedBy": "v3peer", "createdAt": "…" },
      "support": 1, "oppose": 0, "abstain": 0, "score": 1,
      "votes": [ { "voter": "v3peer", "choice": "SUPPORT" } ] }
  ],
  "leaderId": "…", "tie": false,
  "notes": ["投票只用于讨论，不会自动改动行程；确认后需由有权限的成员写入正式安排"]
}
```

**排序口径**：净支持（支持 − 反对）→ 支持数 → 反对数（少者优先）→ 添加时间。
用净支持而不是总票数，是因为「3 人支持 2 人反对」和「4 人支持 4 人反对」不是一回事，
后者实际上是一次僵持。

**平票不选赢家**：榜首并列时 `leaderId` 为 `null`、`tie` 为 `true`，并在 `notes` 里说明。
系统没有资格用「谁先提的」替一群人做决定。

**权限**：能查看行程的人就能提候选、投票，不需要板块编辑权——提候选是提议，不是改方案。
删除候选限提出者本人或创建者。`apply` 需要 `DAILY` 编辑权与当前 `version`，
因为它**真的会写每日安排**，并产生一条正常的修改记录。

### 3.18 预算分析（V3 §2）

```
GET /api/itineraries/{id}/budget/analysis?travelers=2
```

```json
{
  "currency": "CNY", "limit": 2000.00,
  "estimatedTotal": 2000.00, "plannedTotal": 800.00, "actualTotal": 1200.00,
  "remaining": 0.00, "comparable": true, "overLimit": false, "overBy": null,
  "travelers": 2, "perPerson": 1000.00, "perPersonActual": 600.00,
  "warnings": [ { "code": "BUDGET_ACTUAL_OVER_PLAN", "field": "expenses", "message": "…" } ],
  "notes": ["人均按 2 人分摊（创建者 + 成员）", "…"]
}
```

- `estimatedTotal` = 全部已填金额的费用（含已发生）；`actualTotal` = 标记 `actual=true` 的部分。
- `remaining` = `limit − estimatedTotal`；未设上限或币种不一致时为 `null`。
- `travelers` 省略时按「创建者 + 成员」计算，可用查询参数覆盖。
- **币种不一致不硬减**：`comparable=false`、`remaining=null`，并提示未做汇率换算。

| 警告代码 | 含义 |
|---|---|
| `BUDGET_LIMIT_MISSING` | 未设定预算上限 |
| `BUDGET_OVER_LIMIT` | 预计总额已超上限 |
| `BUDGET_ACTUAL_OVER_LIMIT` | 已发生费用已超上限 |
| `BUDGET_ACTUAL_OVER_PLAN` | 已发生高于仍属预计的部分，原计划可能偏低 |
| `BUDGET_CURRENCY_MISMATCH` | 上限币种与费用主币种不一致 |

只读接口，不改变版本号。

### 3.19 综合决策建议（V3 §3）

```
POST /api/agent/itineraries/{id}/advice
{ "travelers": 2, "includeWeather": true }
```

两个字段都可省略。返回：

```json
{
  "itineraryId": "…", "version": 4, "title": "V3联调·杭州",
  "votes": { … 看板同 §3.17 … },
  "budget": { … 预算分析同 §3.18 … },
  "checks": { … 体检同 §3.11 … },
  "weather": null,
  "risks": [
    { "code": "BUDGET_OVER_LIMIT", "severity": "HIGH",
      "message": "预计总额 2500.00 CNY 已超预算上限 2000，超出 500.00",
      "basis": "预算", "suggestion": "先压缩住宿与交通……" }
  ],
  "suggestions": ["票数最高的是「灵隐寺」（支持 1／反对 0），确认后可以加入某一天的安排"],
  "advice": null,
  "notices": ["未接入实时天气（未配置高德密钥），天气相关的风险未纳入判断",
              "未配置模型密钥（DEEPSEEK_API_KEY），本次不生成文字建议；风险与调整建议仍由系统规则给出"],
  "sources": ["本系统行程数据", "成员投票"],
  "generatedAt": "2026-11-04T14:03:00Z"
}
```

`severity` 取 `HIGH` / `MEDIUM` / `INFO`，列表按严重度降序。规则覆盖：

| 代码 | 严重度 | 依据 |
|---|---|---|
| `BUDGET_OVER_LIMIT`、`BUDGET_ACTUAL_OVER_LIMIT` | HIGH | 预算 |
| `ACTIVITY_TIME_INVALID` | HIGH | 行程体检 |
| `TRANSPORT_ARRIVAL_CONFLICT` | HIGH | 交通与每日安排（到达晚于当天安排） |
| `ACTIVITY_TIME_OVERLAP`、`DAILY_OUT_OF_RANGE` | MEDIUM | 行程体检 |
| `VOTE_TIE`、`NO_VOTE_YET`、`VOTE_PARTIAL` | MEDIUM / INFO | 投票 |
| `NO_LODGING` | MEDIUM | 住宿（多晚行程无住宿） |
| `WEATHER_BAD` | MEDIUM | 天气预报（旅行日期内有雨雪） |
| `NO_CANDIDATE`、`NO_TRANSPORT`、`ITINERARY_ARCHIVED` | INFO | 对应板块 |

**判断由 Java 做，模型只负责组织语言**：风险条目全部由规则算出，模型不可用时
`advice` 为 `null`，但 `risks` 与 `suggestions` 完整保留。本接口**不写库**，
建议要用户确认后才落到正式方案。

### 3.20 归档（V3 §4）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/itineraries/{id}/archive` | 归档，转为只读（仅 owner），幂等 |
| `POST` | `/api/itineraries/{id}/unarchive` | 取消归档（仅 owner），幂等 |
| `GET` | `/api/itineraries?archived=true\|false` | 省略时返回全部 |

归档不删除数据，只是禁止写入：此后**所有写操作**（五大板块、成员变更、候选与投票）
一律返回 **409** 并带 `"archived": true`。用 409 而不是 403，是因为这不是权限问题——
创建者自己也改不了，冲突语义才是「当前状态不允许这个操作」。

需要版本号的接口在归档状态下先返回 409（归档），不会先报版本冲突，避免用户以为是并发问题。

### 3.21 导出文本摘要（V3 §4）

```
GET /api/itineraries/{id}/export
```

返回 `text/plain;charset=UTF-8`，并带 `Content-Disposition: attachment` 与 UTF-8 文件名。
内容包括：行程基本信息、成员与授权、方案、每日安排、交通、住宿、费用与小计、
预算分析、候选与投票、行程检查，结尾附「不构成预订承诺、价格以官方为准」的说明。

导出**纯文本**而不是 PDF/Word：没有字体与分页依赖，任何编辑器都能打开，也便于贴进群聊。
服务端不写用户磁盘，落点由调用方决定。

### 3.22 Agent 注册表、准入与投票

三组接口分别负责「有哪些 Agent 可用」「某个 Agent 能不能启用」「多方案怎么投票」。
契约细节（`AgentProvider` 字段、T-01～T-08 校验规则、G0～G4 状态机）以
[Agent 接入规范](Agent接入规范.md) 与 [Agent 运行时架构设计](Agent运行时架构设计.md) 为准，
本节只给端点与鉴权。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/api/agent/capabilities` | 匿名 | 契约版本、注册表版本，以及每个 Provider 的 id / 版本 / 能力标签 / `enabled` / `health` / `status` / `lastRejectionCode`；**不含** endpoint、authRef 与凭据 |
| `GET` | `/api/agent/providers` | 需登录 | 在 capabilities 基础上追加 `routes`：每个 intent 的 `primary` 与 `fallback` |
| `GET` | `/api/agent/providers/{id}/admission` | 需登录 | 最近一次 `AdmissionReport`；未注册的 id 返回 **404** |
| `POST` | `/api/agent/providers/reload` | 需登录 | 重载 `agent_providers.json`，回 `reloaded` / `registryVersion` / `providerCount`；重载后立即生效 |
| `POST` | `/api/agent/providers/{id}/probe` | 需登录 | G1 契约探针 + G2 安全探针，按配置发真实请求，不注入桩 |
| `POST` | `/api/agent/providers/{id}/shadow?samples=N` | 需登录 | G3 影子比对，`samples` 缺省取规范源的 20 |
| `POST` | `/api/agent/providers/{id}/activate` | 需登录 | G4 人工启用，需 G3 通过且配置里 `enabled=true` |
| `POST` | `/api/agent/vote` | 需登录 | 两轮评分投票入口，见 §3.22.1 |

后三个准入 POST（`probe` / `shadow` / `activate`）的响应形状统一为 `report`（`AdmissionReport`）
+ `status`（该 Provider 当前状态）+ `registryVersion` + `shadow`（影子统计）；`reload` 只回
`reloaded` / `registryVersion` / `registryNote` / `providerCount`。状态机未走到对应关卡时不会
「跳过前面直接启用」：G1 未过则 G2 不执行，G3 未过时 `activate` 只回拒绝原因码。

#### 3.22.1 两轮投票（`POST /api/agent/vote`）

```json
POST /api/agent/vote
{ "intent": "PLAN_DRAFT", "candidates": [ { "id": "c1", "summary": "省钱路线" } ] }
```

匿名访问返回 **401**（`/api/agent/**` 由 `anyRequest().authenticated()` 拦截，`capabilities`
是白名单里唯一的例外），缺 `intent` 返回 **400**。响应含各评委的评分、两轮计票结果与
`draft`；`draft` 只归位本轮事实里真实出现的名字（逐日、餐饮、天气、官方购票入口），
缺哪类写进 `unknowns`，不补常识、不编景点、不生成链接。信封脱敏，不含 endpoint、authRef 与凭据。

投票只出比较结果，不改动行程、不写库：要落库仍走 §3.3 与 §3.8 的人工确认路径。

---

## 4. 数据模型

### 4.1 Itinerary（行程聚合根）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 服务端生成的唯一 ID |
| `owner` | string | 归属者用户名 |
| `title` | string | 行程标题 |
| `version` | long | 乐观锁版本号（创建=1，每次写 +1） |
| `createdAt` | string(ISO-8601) | 创建时间 |
| `updatedAt` | string(ISO-8601) | 最后更新时间 |
| `members` | Member[] | 成员列表 |
| `plan` | Plan | 方案 |
| `dailySchedules` | DailySchedule[] | 每日安排 |
| `transports` | Transport[] | 交通 |
| `lodgings` | Lodging[] | 住宿 |
| `expenses` | Expense[] | 费用 |
| `archived` | boolean | 是否已归档（只读）；老 JSON 缺键时为 `false`（2026-11-04 V3 新增） |
| `archivedAt` | string(ISO-8601) | 归档时间，未归档时为 `null`（2026-11-04 V3 新增） |

### 4.2 Plan（方案）
| 字段 | 类型 | 说明 |
|---|---|---|
| `destination` | string | 目的地 |
| `summary` | string | 行程摘要 |
| `notes` | string | 备注 |
| `departDate` | string(date) | 出发日期 `YYYY-MM-DD`，可为 `null`（2026-11-04 新增） |
| `returnDate` | string(date) | 返回日期 `YYYY-MM-DD`，可为 `null`；早于 `departDate` 时写入被拒（2026-11-04 新增） |
| `budgetLimit` | number | 预算上限，可为 `null`（未设定时预算分析不给差额）（2026-11-04 V3 新增） |
| `budgetCurrency` | string | 上限币种，默认 `CNY`；与费用主币种不一致时不比较（2026-11-04 V3 新增） |

> 历史行程的 `plan` 没有这两个键，读取时按 `null` 处理（不会报错），由 §3.11 体检提示「未填写」。
> 预算上限同样允许为空：0 意味着「一分不花」，与「还没填」是两件事，不能互相冒充。

### 4.3 DailySchedule（每日安排）与 DailyActivity
**DailySchedule**
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 当天 ID（缺失由服务端补全） |
| `date` | string(`yyyy-MM-dd`) | 日期 |
| `note` | string | 当天备注 |
| `activities` | DailyActivity[] | 当天活动列表 |

**DailyActivity**
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 活动 ID（缺失由服务端补全） |
| `time` | string | 时间（自由文本，如 "09:30"、"上午"）；无法确定具体时刻时用它 |
| `title` | string | 活动名称 |
| `location` | string | 地点 |
| `note` | string | 备注 |
| `startTime` | string(`HH:mm`) | 开始时间，可为 `null`（2026-11-04 新增，V2 §3） |
| `endTime` | string(`HH:mm`) | 结束时间，可为 `null`；**早于 `startTime` 时写入被拒（400）** |

> 两个新字段都可空，历史数据没有这两个键时按 `null` 读取，不会报错。
> 体检（§3.11）会提示整天都没填起止时间的安排、同日时间重叠、以及安排日期越界；
> 只有「结束早于开始」在写入时就拦截——那是笔误，没有合理解释，
> 而重叠和越界需要用户自己判断是否要调整。

### 4.4 Transport（交通）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | ID（缺失由服务端补全） |
| `mode` | string | 方式（飞机/高铁/自驾…） |
| `from` | string | 出发地 |
| `to` | string | 目的地 |
| `date` | string(`yyyy-MM-dd`) | 日期 |
| `departTime` | string | 出发时间 |
| `arriveTime` | string | 到达时间 |
| `flightNo` | string | 班次/航班号 |
| `seat` | string | 座位 |
| `note` | string | 备注 |

### 4.5 Lodging（住宿）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | ID（缺失由服务端补全） |
| `name` | string | 名称 |
| `address` | string | 地址 |
| `checkIn` | string(`yyyy-MM-dd`) | 入住日期 |
| `checkOut` | string(`yyyy-MM-dd`) | 退房日期 |
| `roomType` | string | 房型 |
| `note` | string | 备注 |

### 4.6 Expense（费用）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | ID（缺失由服务端补全） |
| `category` | string | 类别（交通/餐饮/门票…） |
| `amount` | number | 金额 |
| `currency` | string | 币种（如 CNY） |
| `date` | string(`yyyy-MM-dd`) | 日期 |
| `note` | string | 备注 |
| `actual` | boolean | 是否已发生：`true` 计入「实际总额」，`false`（默认）算「预计」（2026-11-04 V3 新增） |

> 用显式标记而不是「日期早于今天就算已发生」：一张补录的旧票据和一笔提前写下的预算，
> 日期看起来可能完全一样，猜出来的「实际支出」没人敢用。

### 4.7 Member（成员）
| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | 成员用户名 |
| `editSections` | Section[] | 可编辑板块集合（`PLAN`/`DAILY`/`TRANSPORT`/`LODGING`/`EXPENSE`） |

### 4.8 Invitation（邀请）与 InvitationView

**Invitation**（落盘结构，`data/invitations.json`）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 邀请 ID |
| `itineraryId` | string | 目标行程 ID |
| `inviter` | string | 发起人（行程创建者） |
| `invitee` | string | 受邀账号 |
| `editSections` | Section[] | 接受后授予的板块编辑权；空数组表示仅查看 |
| `status` | string | `PENDING` / `ACCEPTED` / `REJECTED` / `CANCELLED` |
| `createdAt` | string(ISO-8601) | 发出时间 |
| `respondedAt` | string(ISO-8601) \| null | 处理时间，未处理为 `null` |

**InvitationView**（接口响应，比落盘结构多一个 `itineraryTitle`）
| 字段 | 类型 | 说明 |
|---|---|---|
| `itineraryTitle` | string \| null | 行程标题，**查询时实时取**，不存快照（行程可改名）；行程已删除时为 `null` |

**InviteRequest**（发起邀请请求体）
| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | 受邀账号，须已注册 |
| `editSections` | Section[] | 可为空数组 |

> 状态机只有一条正向路径 `PENDING → ACCEPTED / REJECTED / CANCELLED`，没有回退。
> 已离开 `PENDING` 的邀请再次处理返回 **409**，不静默覆盖已有决定。

---

## 5. 前端页面

服务端内置**四个**静态页（仅用于本地联调与展示，非生产 UI）：

- `static/index.html`：首页 / 登录与注册页，`GET /`、`/login`、`/app` 均转发到此；
- `static/chat.html`：AI 对话页，登录后的落地页；未登录时页内脚本读 `/api/account/me` 得到 401，跳回 `/?next=/chat.html`；
- `static/api-console.html`：接口测试台，登录卡与测试台在同一页切换；
- `static/ticket-assistant.html`：景区购票助手页，围绕「一条已保存的行程活动」查官方购票入口，不写正式方案（交互见 §5.2）。
- `static/planner.html`：行程工作台，一份页面三个视图——规划 `#plan`（AI 对话、草案、投票卡）、票务 `#ticket`（购票需求与建议）、我的行程 `#trips`；未登录时页内脚本读 `/api/account/me` 得到 401，跳回 `/?next=/planner.html`。旧的 `chat.html` 与 `ticket-assistant.html` 已并入此页并删除，原地址 302 到 `/planner.html`；

接口测试台按会话自动切换两种形态，不需要跳转：

| 状态 | 显示内容 |
|---|---|
| 未登录 | 登录卡（登录 / 注册两个标签页；注册成功后自动把账号密码带入登录表单） |
| 已登录 | **接口测试台**：行程列表 / 创建 / 删除 + 五大板块 JSON 编辑并 PUT + 成员权限管理 + 邀请页签（发出 / 接受 / 拒绝 / 撤回 / 退出行程）+ 预算检查 + 协作页签（留言 / 修改记录）+ 购票页签（官方入口查询）+ 周边查询页签（高德地点 / 天气 / 能力状态）+ 候选投票页签 + 决策建议页签 + 归档导出页签，自动携带 `version` 并展示 409 冲突 |

入口地址：

| 地址 | 行为 |
|---|---|
| `GET /`、`GET /login`、`GET /app` | 转发到 `index.html`（登录 / 注册页；`/login`、`/app` 为历史链接） |
| `GET /index.html` | 登录页直连 |
| `GET /planner.html` | 行程工作台直连（页面公开，数据仍由 `/api/**` 拦截） |
| `GET /chat.html`、`GET /ticket-assistant.html` | 302 到 `/planner.html`（旧地址兼容） |
| `GET /api-console.html` | 接口测试台直连 |
| `GET /login.html`、`GET /app.html` | 302 到 `/`（旧地址兼容） |

页面用左侧竖排栏区分三组页签，便于定位：**行程**（方案 / 每日安排 / 交通 / 住宿 / 费用）、**协作**（成员 / 邀请 / 留言记录）、**智能**（预算检查 / 购票 / 周边查询 / 候选投票 / 决策建议 / 美食推荐 / Agent 投票 / 归档导出）。

所有请求通过 `fetch` 调用 §2/§3 的真实接口，同源携带 `JSESSIONID`；响应按 UTF-8 编码（服务端已配置 `server.servlet.encoding.force=true`，静态页与 JSON 都带 `charset=UTF-8`）。

### 5.1 工作台规划视图（`planner.html#plan`）的交互约定

**每条消息先解析再路由**：用户发送后，对话页先调 `POST /api/intent/parse`（§8.9）拿到
意图与槽位，再按 `intent` 分流：

| intent | 行为 |
|---|---|
| `PLAN_DRAFT` + 有天数 | 直接调 `/api/agent/invoke`（intent=PLAN_DRAFT）排行程 |
| `PLAN_DRAFT` + 无天数 | 先渲染天数点选卡，选完带结构化字段重新提交 |
| `FOOD_RECOMMEND` | 取该城市景点库第一条作锚点，调 `/api/agent/invoke`（intent=FOOD_RECOMMEND） |
| `CHAT` | 本地回一句引导语 + 三个可点的开场示例，不调后端、不消耗模型 |

**地名解析**仍走三级：知识库反查 → 后端 `POST /api/places/resolve`（§8.8）→ 字面相近。
解析失败时不再要求用户重新打字，而是把 `alternatives` 渲染成可点城市按钮；
模型判断出城市但库里没收录时，说明里会明确写「可能属于 X，但知识库里没有 X 的景点」。

**只给地名（没有天数）时不直接出结果**，先渲染一张点选卡：天数必选（1/2/3/5/7），
同行人与偏好可选（已说过的人数与偏好会预选，不重复问）；选完拼成一句人话（如
「武当山，2 天，2 人，自然风光」）重新提交，同时带上 `days`/`travelers`/`prefs`
结构化字段，避免依赖二次解析。另给「先看看景点」出口，只想看候选的人不被卡住。

**美食推荐必须有锚点**：Provider 只按坐标找周边餐馆，不接受「按城市中心」兜底。
对话页先查该城市景点库第一条的 `id` 放进请求体的 `attractionIds`，拿不到就如实说明
「没有可用锚点」，不编地址。

**补问项（`questions`）按 `field` 渲染成控件**：`departDate`/`returnDate` 给相对日期按钮
（今天/明天/本周六/下周六）与原生日期控件，`days`/`travelers`/`budget` 给档位按钮，
点一次「按这些重排」即提交。结构化字段通过请求体直接传回（`days`/`travelers`/`budget`/
`departDate`/`returnDate`），不依赖模型从文本里再解析一遍；`builtin.planning` 会把八个
字段全量透传给 `PlanningRequest`。

### 5.2 工作台票务视图（`planner.html#ticket`）的交互约定

页面以「一条已保存的行程活动」为操作对象，先选行程再选活动；不带参数直接打开时只加载
`GET /api/tickets/catalog` 的核验目录，不主动读用户数据。支持 `?itineraryId=` 与
`?activityId=` 深链，从行程详情跳过来时预选对应活动。

| 动作 | 调用 | 说明 |
|---|---|---|
| 打开页面 | `GET /api/tickets/catalog` | 只取人工核验过的景区目录，用于用户手输景点名时定位 |
| 选行程 | `GET /api/itineraries` → `GET /api/itineraries/{id}` | 活动下拉按「日期 · 标题」列出全部逐日活动 |
| 求建议 | `POST /api/agent/tickets/advice` | 提交 `message`、`needs`、`itineraryId`、`activityId`；返回的 `questions` 直接显示在对话区，右侧只展示核验依据 |
| 登记结果 | `PUT /api/itineraries/{id}/activities/{activityId}/ticket-record?version=` | 携带当前 `version`，409 时提示「行程已变化或归档，请重新加载并核对，未保存登记」 |

页面上的三条边界必须保持：官方入口一律 `target="_blank"` + `rel="noopener noreferrer"`，
不拼接猜测链接；登记的只是**用户自报结果**，文案明写「未经平台核验，费用板块未自动变更」；
未登录时统一提示「请先返回主页登录，再加载行程」，不静默失败。

---

## 6. 端到端示例（curl）

```bash
# 1) 注册
curl -X POST http://localhost:8080/api/account/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Passw0rd123"}'

# 2) 登录（拿到 JSESSIONID）
curl -c cookies.txt -X POST http://localhost:8080/api/login \
  -d "username=alice&password=Passw0rd123"

# 3) 创建行程
curl -b cookies.txt -X POST http://localhost:8080/api/itineraries \
  -H "Content-Type: application/json" \
  -d '{"title":"云南行","plan":{"destination":"云南","summary":"昆明-大理"}}'

# 4) 读取版本后更新方案（假设上一步返回 version=1）
curl -b cookies.txt -X PUT "http://localhost:8080/api/itineraries/{id}/plan?version=1" \
  -H "Content-Type: application/json" \
  -d '{"destination":"云南","summary":"昆明-大理-丽江","notes":"已订票"}'

# 5) 邀请成员（先 GET /{id} 取最新 version）
curl -b cookies.txt -X POST "http://localhost:8080/api/itineraries/{id}/members?version=2" \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","editSections":["TRANSPORT","LODGING"]}'

# 6) 退出
curl -b cookies.txt -X POST http://localhost:8080/api/logout
```

---

## 7. 落盘与版本控制说明
- 账号：`data/accounts.json`（数组，密码为 BCrypt 哈希）。
- 行程：`data/itineraries.json`（数组，原子写：先写临时文件再 `ATOMIC_MOVE` 替换，读取走内存副本）。
- 邀请：`data/invitations.json`（数组，同上原子写）。**邀请不进行程聚合**，因此发出/撤回邀请不改行程 `version`。
- 留言：`data/comments.json`；修改记录：`data/changelog.json`（同上原子写）。两者都**不进行程聚合**，
  因此评论不推动 `version`；单个行程的修改记录保留最近 500 条。
- 候选：`data/candidates.json`；选票：`data/votes.json`（同上原子写）。同样**不进行程聚合**，
  添加候选与投票都不推动 `version`；删除候选时连带删除它的选票。
- 官方购票入口：`data/ticket_channels.json`（**只读**，人工维护白名单）。默认不存在，
  文件缺失或解析失败时购票辅助以「无经核验入口」运行，程序不覆盖原文件。
- 景点库：`data/attractions_index.json`（只读，Python 离线构建产物）。
- 运行数据文件按需创建，**全部由 `.gitignore` 排除**（含 accounts / itineraries / invitations /
  comments / changelog / candidates / votes）。各 `Store` 在文件缺失时自动创建空文件，新克隆无需
  手工初始化即可启动，仓库不再预置 `*.example.json` 空数组种子。
  运行态数据里是真实账号哈希与用户行程，提交等于把凭据散播到公开仓库，因此不跟踪。
- `csrf` 已禁用：客户端为第一方控制台程序，且会话 Cookie 为 `HttpOnly + SameSite=Strict`；**生产务必启用 HTTPS**。

---

## 8. 景点查询（只读知识库）

景点能力沿用「Python 离线构建、Java 在线检索」的分工：离线脚本把融合库清洗成精简索引库，
服务端启动时**只读**加载并在内存建索引，查询不访问磁盘、不写任何文件。

### 8.1 数据来源与加载

| 项 | 说明 |
|---|---|
| 离线构建 | `python tools/build_attraction_index.py` → `data/attractions_index.json`（只读 `china_attractions.json`，输出路径与输入相同时拒绝执行） |
| 分片编辑源 | `data/attractions/*.json`（按省切成 30 个小文件，一条记录一行）；`python -B tools/kb_shards.py --check` 要求分片合成结果与 `china_attractions.json` 逐字节一致 |
| 一键同步 | `python -B tools/kb_shards.py --sync` 贯通「分片 → 主库 → 运行索引」；`tools/gate.sh` 第一步做分片一致性校验 |
| 字段视图 | `tools/kb_modules.py --split` 可按需生成本地视图，目录不入库、不参与门禁，2026-11-08 的 data 目录优化已移除常驻副本 |
| 数据目录 | `data/` 只保留编辑源、两份产物与购票配置；运行态 JSON 按需创建、不提交，仓库不预置空数组种子 |
| 加载时机 | 服务端启动，`AttractionStore#load()` |
| 库路径 | `ATTRACTION_KB_PATH`，默认 `${TRAVEL_DATA_DIR}/attractions_index.json` |
| 索引 | 城市/省份（含别名）、名称、ID 三套内存索引 |
| 当前规模 | 2026-11-08 实测：源库 7,745 条、6.30 MiB；索引 7,745 条、3.94 MiB；索引文件 `city_count` 81 个键。数字随知识库变化 |
| 拆分边界 | 2026-11-08 的拆分与目录优化只改编辑源；主库字节、索引、Java 加载链与返回体不变 |

### 8.2 按城市查询

```
GET /api/attractions?city=杭州&limit=20
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `city` | 二选一 | 城市或省份名，支持别名：`杭州`/`杭州市`、`浙江`/`浙江省` |
| `name` | 二选一 | 景点名称；`city` 与 `name` 同时给出时 **`city` 优先** |
| `limit` | 否 | 返回条数，默认 20，上限 50；非正数回落默认 |

响应：

```json
{
  "matched": 438,
  "returned": 3,
  "limit": 3,
  "message": null,
  "items": [
    {
      "id": "amap-B023B07SDX",
      "name": "东方文化园",
      "city": "杭州市",
      "province": "浙江省",
      "type": "风景名胜",
      "level": "4A景区",
      "rating": "4.4",
      "openTime": "全年 07:30–17:00开放 最晚进入16:30；国庆节 07:30–21:00开放 最晚进入20:30",
      "ticket": "旺季（1-6月、10月）100元/人，淡季（7-9月、11-12月）80元/人；……",
      "address": "大湾路17号",
      "tel": "0571-82338072",
      "website": null,
      "image": null,
      "description": null,
      "lat": 30.10239,
      "lon": 120.19792,
      "source": "Amap",
      "sources": ["Amap"],
      "ticketText": "旺季（1-6月、10月）100元/人，……",
      "openTimeText": "全年 07:30–17:00开放 最晚进入16:30；……",
      "ticketStatus": "unverified",
      "openTimeStatus": "unverified"
    }
  ]
}
```

字段语义：

| 字段 | 说明 |
|---|---|
| `matched` | **截断前**的命中总数 |
| `returned` | 实际返回条数（= `min(matched, limit)`） |
| `limit` | 生效的条数上限（已归一化） |
| `message` | 无结果或缺参时的提示；有结果时为 `null` |
| `ticket` / `openTime` | 原始值，库中缺失时为 `null` |
| `ticketText` / `openTimeText` | 展示值，缺失时固定为「未知」 |
| `ticketStatus` / `openTimeStatus` | 核验状态：`verified` / `unverified` / `pending`（详见下表） |

> 调用方**不得**把 `ticket = null` 解释为「免费」，也不得把 `openTime = null` 解释为「全天开放」。

#### 核验状态契约

| 取值 | 含义 | 展示要求 |
|---|---|---|
| `verified` | 有逐行来源与核验日期，可按官方信息展示 | 可正常展示 |
| `unverified` | 有值但缺逐行证据 | **必须标注「未核验／仅供参考」，不得当作官方定价或官方开放时间** |
| `pending` | 无值或尚未核验 | 按「未知」处理 |

字段有值不等于已核验。`AttractionView` 会把历史词表 `confirmed` 归一为 `verified`，
空值归一为 `pending`，其它未知取值保守按 `unverified` 处理。知识库重建时
`tools/build_unified_kb.py` 只在同时存在 `*_source` 与 `*_checked_at` 逐行证据时
继承 `verified`，否则一律 `unverified`——**没有证据不得凭历史标签复活核验结论**。

### 8.3 按名称查询

```
GET /api/attractions?name=西湖&limit=5
```

三级匹配，命中即止：

1. **精确名**：名称完全相等；
2. **前缀**：名称以关键词开头（如「西湖天地」「西湖公园」）；
3. **包含**：名称中含关键词。

先精确、再前缀、最后包含，是为了避免「西湖」把「**东西湖**区图书馆」这类非目标结果排到前面。
关键词为空时直接返回提示，不返回全库。

### 8.4 按 ID 查详情

```
GET /api/attractions/{id}
```

- 命中：`200`，返回单个景点对象（字段同 §8.2 的 `items[]`）；
- 不存在：`404` `{ "error": "景点不存在", "id": "..." }`；
- 库不可用：`503`（见 §8.6）。

### 8.5 知识库状态

```
GET /api/attractions/status
```

可用时：

```json
{ "available": true, "count": 7745, "cityCount": 158, "loadedAt": "<启动加载时刻，ISO 8601>",
  "path": "./data/attractions_index.json" }
```

不可用时：

```json
{ "available": false, "reason": "景点知识库文件不存在：……（请先运行 python tools/build_attraction_index.py）",
  "path": "./data/attractions_index.json" }
```

`cityCount` 为**含别名的索引条目数**（同一城市会有「杭州」「杭州市」两个键），不等于城市数。示例中 `count` 为 2026-11-08 实测值；`cityCount` 158 是按当前索引与 `cityAliases`/`regionAliases` 规则复算所得，未启动服务实测；`loadedAt` 每次启动都不同，用占位符表示。索引文件里的 `city_count` 是 81，两者口径不同。

### 8.6 库缺失或损坏时的降级

| 场景 | 查询接口 | 状态接口 | 磁盘文件 |
|---|---|---|---|
| 文件不存在 | `503` + `reason` 含「不存在」 | `available=false` | **不自动创建** |
| JSON 解析失败 | `503` + `reason` 含「解析失败」 | `available=false` | **不覆盖、不改写** |
| 缺 `attractions` 数组 | `503` + `reason` 含「结构异常」 | `available=false` | **不覆盖** |
| 全部记录不可解析 | `503` + `reason` 说明条数 | `available=false` | **不覆盖** |

降级时刻意返回 503 而不是空列表：空列表会让调用方误判为「知识库里确实没有这个景点」，
而实际原因是库没加载成功。修复动作一律交给离线构建流程人工执行。

### 8.7 已知限制

- 票价、开放时间、等级、评分均为**离线快照**，不保证实时性。2026-11-08 实测：7,745 条中有票价 874 条、有开放时间 4,346 条；票价状态为 verified 145 / unverified 729 / pending 6,871。只有 `verified` 可显示为确定值，未知不等于免费。
- 源库逐条标注 `coordinateSystem`，2026-11-08 实测 WGS-84 3,350 条、GCJ-02 4,395 条，无 `mixed`、无坐标系缺失；仍有 1 条记录缺坐标值。**精简索引的 `KEEP_FIELDS` 不含该字段**，本接口不下发坐标系。跨源比对要在离线层按各条记录的坐标系换算，不能直接混用。
- 只做 `limit` 截断，没有分页游标；超过 50 条需收窄查询条件。

### 8.8 地名识别（需要登录）

```
POST /api/places/resolve
Content-Type: application/json

{ "text": "黄梅", "candidates": ["黄梅"] }
```

`text` 是用户原话，`candidates` 是前端剥掉语气词后的候选词，二者至少给一个。

```json
{ "query": "黄梅", "resolved": true, "city": "黄冈", "spot": null, "province": null,
  "level": "county", "source": "llm",
  "note": "黄梅县隶属湖北省黄冈市，按 黄冈 检索", "alternatives": [] }
```

未识别时：

```json
{ "query": "婺源", "resolved": false, "city": null, "level": "unknown", "source": "none",
  "note": "「婺源」可能属于上饶市，但知识库里没有 上饶市 的景点", "alternatives": [] }
```

判断顺序（逐级降级，任何一级失败都不阻塞对话）：

1. **知识库直查**：城市/省份/景点名精确命中，`source=kb`，零延迟零成本；
2. **模型判断**：知识库落空时才调用，模型在已知地名清单内判断归属（县级地名、俗称、错别字），
   输出 JSON 后仍要过清单校验——清单外的城市不会被当成确定事实，只在 `note` 里以
   「可能属于 X」的措辞出现；
3. **字面相近**：模型不可用或也判断不出时，用公共子串找相近城市填进 `alternatives`，
   供前端渲染成可点按钮。只共享一个字（「长白山」vs「长沙」）不算相近，避免误导。

`level` 取值 `city｜county｜spot｜unknown`，`source` 取值 `kb｜llm｜none`。
同一候选词的结果按候选组合缓存（上限 256 条），重复提问不再调用模型。

**为什么需要这个接口**：知识库目前只覆盖 81 个城市键，全国地级市尚未跑完。
「库里有这个城市的数据吗」与「这个词是不是一个真实地名」是两件事，
前者由知识库回答，后者不能靠字符串精确匹配来判定——否则「黄梅」「平遥」这类
县级地名会被判成「不认识」。


### 8.9 自然语言解析（需要登录）

```
POST /api/intent/parse
Content-Type: application/json

{ "text": "下周六带爸妈去武当山玩四天，预算六千，想看看人文古迹" }
```

一次请求返回「意图 + 槽位 + 已核实的目的地」，对话页据此决定下一步渲染什么：

```json
{ "text": "下周六带爸妈去武当山玩四天，预算六千，想看看人文古迹",
  "intent": "PLAN_DRAFT", "destination": "武当山",
  "resolved": true, "city": "十堰", "spot": "武当山风景区",
  "note": "「武当山」是「武当山风景区」，按所在城市 十堰 检索",
  "alternatives": [], "days": 4, "travelers": null, "budget": 6000, "currency": "CNY",
  "preferences": ["人文"], "departDate": "2026-11-21", "returnDate": "2026-11-24",
  "confidence": "high", "source": "llm",
  "reason": "下周六出发玩四天，预算六千，带爸妈人数不明，想看人文古迹",
  "notes": ["返回日期按 4 天行程推算为 2026-11-24"] }
```

| 字段 | 说明 |
|---|---|
| `intent` | `PLAN_DRAFT` 排行程 / `FOOD_RECOMMEND` 找吃的 / `WEATHER` 问天气 / `CHAT` 闲聊 |
| `destination` | 用户原话里的地名写法（未解析），没提到为 `null` |
| `resolved` / `city` / `spot` / `note` / `alternatives` | 与 §8.8 同口径，目的地核实结果 |
| `days` / `travelers` / `budget` | 已校验的取值范围（天数 1~30、人数 1~30、预算 ≤ 100 万） |
| `departDate` / `returnDate` | `YYYY-MM-DD`；相对说法（明天/下周六）按服务器当天换算，只接受今天起一年内 |
| `preferences` | 收敛到固定词表：自然/人文/美食/亲子/摄影/购物/夜游/温泉/海岛/慢节奏 |
| `source` | `llm` 模型解析 / `rules` 规则降级（未配密钥、超时、返回非 JSON） |
| `notes` | 解析中的修正说明，例如「天数 99 超过上限 30，已按 30 处理」 |

**口径**：

1. **理解交给模型，校验留在 Java**。模型只输出值，范围与日期边界由服务端兜；
   越界一律截断或丢弃并写进 `notes`，不会把非法值透传给规划。
2. **只解析说到过的信息**。没提天数就是 `null`，不默认 3 天；「带爸妈」不知道人数也留空，
   由补问卡去问——猜错的人数比问一句更贵。
3. **目的地仍走 §8.8 的三级判断**。模型说整句没有地名时不再反查，直接给 `alternatives`
   （知识库里景点最多的几个城市，已排除省份与直辖市重名），让用户点选而不是重打字。
4. **闲聊不查库、不调编排层**：`intent=CHAT` 时 `resolved=false`、`alternatives=[]`，
   回复由模型的 `reply` 字段给出（见 §8.10）；模型不可用时前端用兜底文案。
5. **接口永远 200**：模型不可用时降级为句式规则（认「武汉 3 天 2 人预算 5000」这类写法），
   不会因为模型抖动把对话卡住。结果按「日期 + 上下文 + 原话」缓存 256 条，相对日期隔天自动失效。
6. **不进 Agent 契约**：这是编排层之前的预处理步骤，不新增 capability、不改 `contractVersion`，
   Provider 侧行为完全不变。

#### 8.9.1 请求体带上上下文（2026-11-10）

```json
{ "text": "有没有那种很小众的馆子",
  "history": [
    { "role": "user", "text": "武汉有什么好吃的" },
    { "role": "assistant", "text": "在武汉找到 30 家可以去的餐馆。" }
  ],
  "state": { "city": "武汉", "days": 3 } }
```

| 字段 | 说明 |
|---|---|
| `history` | 最近几轮对话，服务端只取最后 8 条、每条截 240 字；用于理解「那呢」「还有别的吗」「再玩两天」 |
| `state` | 本轮已确认的槽位：`city` / `days` / `travelers` / `budget` / `preferences` |

响应新增两个字段：

| 字段 | 说明 |
|---|---|
| `carried` | 这一句没明说、从上文沿用过来的字段名（`city` / `days` / `travelers` / `budget` / `preferences`），前端据此显示「沿用上文」 |
| `reply` | 仅 `intent=CHAT` 时有值：模型结合上下文的一句话回答；含网址时整段剥掉 |

**继承规则（Java 侧，不靠模型自觉）**：

1. 闲聊永不继承——「你叫什么名字」不会被上文的「武汉」污染成查景点；
2. 只补这一句真的没有的字段，模型自己给了值就以模型为准；
3. 找吃的与问天气只补城市，沿用上一次的天数、预算在这两类里没有意义；
4. 同一句话在不同上文里分开缓存，不会把上一轮的城市套到新对话。

**天气**：`intent=WEATHER` 时**不查景点知识库**，`city` 直接取原话地名交给
`GET /api/location/weather`（高德能按城市名换 adcode）。没给城市时 `resolved=false`，
`note` 问「想查哪座城市的天气」并给可点城市。


### 8.10 对话 Agent（需要登录）

```
POST /api/chat
Content-Type: application/json

{ "message": "武汉有什么好吃的",
  "history": [ { "role": "user", "text": "我想去武汉玩三天" } ],
  "state": { "city": "武汉", "days": 3 } }
```

一次请求返回「回答 + 结构化卡片 + 查询痕迹」：

```json
{ "reply": "找到 8 家，先试热干面和豆皮，都在你选的景点附近。",
  "cards": [ { "type": "envelope", "intent": "FOOD_RECOMMEND", "data": { "status": "OK", "providerId": "builtin.food", "result": { "facts": [] } } } ],
  "trace": [ { "tool": "find_food", "label": "找餐馆", "ok": true } ],
  "state": { "city": "武汉", "days": 3 },
  "source": "agent", "note": null }
```

| 字段 | 说明 |
|---|---|
| `reply` | 自然语言回答，已剥掉 Markdown 与网址；为空表示模型不可用，前端回退到 §8.9 + `/api/agent/invoke` |
| `cards` | `weather`（逐日预报）/ `places`（景点表）/ `envelope`（行程、餐馆或票务，形状与 `/api/agent/invoke` 完全一致）/ `vote`（评委投票结论） |
| `trace` | 这次查了哪些工具，界面显示「查了 天气、景点」，让结论可追溯 |
| `state` | 本轮结束后的已知槽位，下一轮原样传回即可 |
| `source` | `agent` 模型驱动 / `unavailable` 模型不可用（未配密钥、余额不足、超时） |

**模型能用八个工具**（`application/chat/ChatTools`）。其中五个是「主 Agent 调子 Agent」：
票务、天气、灵感、行程、美食全部经由 `builtin.*` Provider 走 L1 编排，同一入口、
同一套契约校验、同一种信封——不再有绕过编排层的直连路径：

| 工具 | 何时用 | 数据来源（子 Agent） |
|---|---|---|
| `resolve_place(name)` | 地名归属不确定、可能是景区名 | §8.8 地名识别 |
| `list_attractions(city, limit)` | 问有什么好玩的、门票多少 | 景区知识库 |
| `get_weather(city, days)` | 问天气、气温、要不要带伞 | `builtin.weather`（`WEATHER_ADVICE`，走 L1 编排） |
| `find_food(city)` | 问吃什么、找馆子 | `builtin.food`（走 L1 编排与契约校验） |
| `draft_itinerary(city, days, …)` | 要行程、问怎么安排 | `builtin.planning`（同上） |
| `book_ticket(message)` | 问门票怎么买、购票条件缺什么 | `builtin.ticket`（`TICKET_ADVICE`，同上；只给建议不能下单） |
| `get_inspiration(city)` | 「随便逛逛」「不知道去哪」 | `builtin.inspiration`（`CONTENT_INSPIRATION`：知识库按评分挑候选；知识库没收录的地名如区县「黄梅」自动改用地图工具层实时检索，结果标注未经核验） |
| `vote_plan(city, days, …)` | 想比较省钱/美食/少走路哪种安排好 | `VoteOrchestrator` 两轮投票（见下） |

**评委投票（`vote_plan`）**：主 Agent 把行程输入交给投票编排层，三位评委 Agent
（`judge.ticket` 票务 / `judge.food` 美食 / `judge.parking` 停车视角）执行既定两轮协议——
第一轮各自上报领域事实，主 Agent 生成最多五套侧重不同的候选方案
（模型生成失败自动降级为确定性规则草案），第二轮按各自 rubric 评分计票
（`PlanVoteTally` 确定性计票），胜出方案与加权得分随 `vote` 卡片返回。
比较耗时（含一次模型生成），系统提示明确要求模型只在用户想比较方案侧重时调用。

**边界**：

1. 工具最多 6 次、最多 5 轮、总预算 45 秒；用尽后强制一次不带工具的收尾，
   不会让模型无限绕圈，也不会让用户等到超时。
2. 票务、天气、灵感、行程、餐馆复用 L1 编排：同一个 Provider、同一套契约校验、
   同一种信封，对话里的结论与直接调 `/api/agent/invoke` 完全一致。
3. 数字、地名、票价、餐馆、天气全部来自工具；工具说没查到，模型必须如实说没查到。
4. `book_ticket` 只给建议与缺口（还差什么条件），不能下单、查库存或退款；
   缺条件时模型照 Provider 的问题问，不替用户猜年龄与学生资格。
5. 模型不可用时返回 `source=unavailable` + `reply=null`，对话页自动回退到确定性链路，
   用户看到的不是错误页。


## 9. 北京景区购票助手（首版）

页面 `/planner.html#ticket`（工作台的票务视图）无需登录即可打开，但 2026-11-08 起「任何购票建议请求都要求登录」：`/api/agent/tickets/advice` 已移出 `permitAll`，匿名返回 401；带行程查询和登记还需相应权限。
现有 `GET /api/tickets` 与 `/channels` 保留；新增 `GET /api/tickets/catalog` 返回人工维护的三景点规则及字段来源。
`TICKET_RULE_FILE` 指定规则文件，默认 `data/ticket_rules.json`，文件缺失或损坏时停止深度建议，原入口查询不受影响。
每个字段保存 value、sourceUrl、verifiedAt、validFrom、validTo、note；未知值不视为免费。启动时读取快照，修改后重启生效。

### 9.1 单轮建议

`POST /api/agent/tickets/advice`，请求示例：

```json
{
  "message": "帮我检查购票安排",
  "needs": {
    "attraction": "八达岭长城", "date": "2026-11-21", "product": "STANDARD",
    "visitors": [{"age": 30, "heightCm": 170, "student": false, "chineseEligible": true}],
    "startTime": "09:00", "endTime": "12:00"
  },
  "itineraryId": null, "activityId": null
}
```

`product` 为 STANDARD（普通入园／北京通史基本陈列）、COMBINED（联票）、SPECIAL（特展活动）、TRANSPORT（交通附加项目）。
`student` 表示符合全日制本科及以下学生条件；`chineseEligible` 表示符合八达岭公布的年龄免票国籍／居留条件，不采集证件号码。
游客最多30人；模型每轮最多一次调用，仅提取明确需求，结构化表单优先，服务端不采信模型价格或网址。
未配置模型、调用失败或输出不合约时，使用表单与确定性规则。相对日期不自动推断，要求确认具体日期。

响应：`needs` 为规范化需求，`questions` 为待补问项，`matches` 为逐人票种与价格，`estimatedTotal` 为估算总额（存在未知则 null），
`stockVerified` 恒 false，`registrationSuggested` 表示是否建议登记，`channels` 为现有入口结果，`evidence` 为字段来源，`notices` 为限制和行程提醒。
`registrationSuggested` 由场馆属性判定：STANDARD 产品且预约字段明确为「无需预约」、定价档位全部为 0 时返回 false，并给出「普通参观无需购票／预约」提示；
该判定不依赖 `estimatedTotal`——游客未填、合计未知时免费场馆仍返回 false，收费场馆仍返回 true。
费用仅为快照估算，不是订单报价。花期准确起止、联票开售、特殊身份、特展及未核实退改规则保留未知。
首都博物馆采用馆方免预约及周二闭馆新公告，不沿用旅游网旧快照；规则适用日期以字段记录为准。
带 `itineraryId` 时校验查看权限；带 `activityId` 时带入对应活动日期、景点与起止时间，未找到活动返回400，越权403。
消息超过1500字符或疑似长证件号码返回400；不得在消息中输入真实身份资料。

### 9.2 用户自报登记

`PUT /api/itineraries/{id}/activities/{activityId}/ticket-record?version={当前行程版本}`

```json
{"attractionId":"amap-B000A45467","product":"成人门票","visitDate":"2026-11-21",
 "session":"上午","travelers":2,"paidTotal":70.00}
```

要求 DAILY 编辑权限；归档或版本冲突409，越权403，无活动、日期不一致、人数或金额非法400。
`paidTotal` 可 null，非负且最多两位小数；`session` 可 null。服务端写入 recordedAt 和 source=USER_REPORTED，忽略客户端伪造来源与时间。
覆盖指定活动的单条登记，不增加活动或费用；响应为更新后的完整行程与版本号。
DailyActivity 新增可空 ticketRecord；旧 JSON 兼容。整表 daily 更新不能伪造登记，调整已登记活动日期前需清除或修改登记。
页面和文本导出必须保留“用户自报已购票／已预约，未经平台核验”，不能改成已出票。
