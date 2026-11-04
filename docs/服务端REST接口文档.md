# Travel Assistant 服务端接口文档

> 适用版本：`main`（自 `8214902` 合入起生效）　|　技术栈：Spring Boot 3.5 + Spring Security 6 + Java 21
>
> 本文档覆盖**账号与认证**与**行程管理**两套 REST 接口，包含请求/响应示例、数据模型、权限模型与乐观锁机制。

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

与 `SecurityConfiguration#permitAll()` 保持一致，共 11 项：

| 路径 | 说明 |
|---|---|
| `GET /api/health` | 健康检查 |
| `POST /api/account/register` | 注册 |
| `POST /api/login` | 表单登录 |
| `GET /api/attractions`、`GET /api/attractions/**` | 景点查询（只读知识库，见第 8 节） |
| `GET /`、`GET /login`、`GET /login.html` | 登录页（注册在页内切换标签完成） |
| `GET /app`、`GET /app.html` | 接口测试台页面 |

其余 `/api/**` 均要求登录，未登录直接返回 **401**（不跳转登录页，便于 JSON 客户端处理）。

> **待确认项**：`SecurityConfiguration` 另放行了 `/register.html`，但 `travel-server/src/main/resources/static/` 下并无该文件（注册实际在 `login.html` 内完成）。保留放行不影响功能，是否删除该条目需与开发负责人确认；确认后本表同步更新。

### 1.4 统一错误响应
除登录/退出外，错误统一为 JSON：

```json
{ "error": "错误描述" }
```

| HTTP | 含义 | 触发场景 |
|---|---|---|
| 400 | 请求参数/校验不合法 | 注册字段不合规、缺 `version`、类型错误、**行程标题为空**、**费用金额为负**、**住宿离店早于入住**、**邀请未注册用户** |
| 401 | 未登录 | 访问受保护接口但未带有效会话 |
| 403 | 无权限 | 非参与者 / 成员无该板块编辑权 / 非 owner 操作成员或删除 |
| 404 | 资源不存在 | 行程 `id` 不存在；**成员不在成员列表中** |
| 409 | 版本冲突 | 提交 `version` 与服务端不一致（响应头带回 `X-Version`） |
| 503 | 依赖不可用 | **景点知识库缺失或损坏**，查询已停用，响应带 `reason` |
| 201 | 创建成功 | 注册 / 创建行程（带 `Location`） |

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

> 未登录访问受保护接口由 Spring Security 直接返回 **401**，不经过上述处理器。

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

`ok=true` 表示没有需要用户处理的提示。日期区间倒置时**只报** `RETURN_BEFORE_DEPART`，不再逐条报越界——否则每条安排都会再报一次，噪声会掩盖真正的问题。

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

### 4.2 Plan（方案）
| 字段 | 类型 | 说明 |
|---|---|---|
| `destination` | string | 目的地 |
| `summary` | string | 行程摘要 |
| `notes` | string | 备注 |
| `departDate` | string(date) | 出发日期 `YYYY-MM-DD`，可为 `null`（2026-11-04 新增） |
| `returnDate` | string(date) | 返回日期 `YYYY-MM-DD`，可为 `null`；早于 `departDate` 时写入被拒（2026-11-04 新增） |

> 历史行程的 `plan` 没有这两个键，读取时按 `null` 处理（不会报错），由 §3.11 体检提示「未填写」。

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
| `time` | string | 时间（自由文本，如 "09:30"） |
| `title` | string | 活动名称 |
| `location` | string | 地点 |
| `note` | string | 备注 |

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

### 4.7 Member（成员）
| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | 成员用户名 |
| `editSections` | Section[] | 可编辑板块集合（`PLAN`/`DAILY`/`TRANSPORT`/`LODGING`/`EXPENSE`） |

---

## 5. 测试前端页面

服务端内置两个静态测试页（仅用于本地联调，非生产 UI）：

| 地址 | 页面 | 功能 |
|---|---|---|
| `GET /`、`GET /login` | `login.html` | 登录 / 注册标签页，登录后展示当前会话，支持退出 |
| `GET /app`、`GET /app.html` | `app.html` | **接口测试台**：登录/注册/退出 + 行程列表/创建/删除 + 五大板块编辑（JSON 编辑并 PUT）+ 成员权限管理，自动携带 `version` 并展示 409 冲突 |

两个页面均通过 `fetch` 调用 §2/§3 的真实接口，会话基于同源 `JSESSIONID` Cookie。

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
- 两份数据文件均已纳入 Git（空数组种子），运行时由 `TRAVEL_DATA_DIR`（默认 `./data`）定位。
- `csrf` 已禁用：客户端为第一方控制台程序，且会话 Cookie 为 `HttpOnly + SameSite=Strict`；**生产务必启用 HTTPS**。

---

## 8. 景点查询（只读知识库）

景点能力沿用「Python 离线构建、Java 在线检索」的分工：离线脚本把融合库清洗成精简索引库，
服务端启动时**只读**加载并在内存建索引，查询不访问磁盘、不写任何文件。

### 8.1 数据来源与加载

| 项 | 说明 |
|---|---|
| 离线构建 | `python tools/build_attraction_index.py` → `data/attractions_index.json`（只读 `china_attractions.json`，输出路径与输入相同时拒绝执行） |
| 加载时机 | 服务端启动，`AttractionStore#load()` |
| 库路径 | `ATTRACTION_KB_PATH`，默认 `${TRAVEL_DATA_DIR}/attractions_index.json` |
| 索引 | 城市/省份（含别名）、名称、ID 三套内存索引 |
| 当前规模 | 11,858 条，66 个城市，13MB → 5.7MB |

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
      "openTimeText": "全年 07:30–17:00开放 最晚进入16:30；……"
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

> 调用方**不得**把 `ticket = null` 解释为「免费」，也不得把 `openTime = null` 解释为「全天开放」。

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
{ "available": true, "count": 11858, "cityCount": 126, "loadedAt": "2026-11-04T11:28:28Z",
  "path": "./data/attractions_index.json" }
```

不可用时：

```json
{ "available": false, "reason": "景点知识库文件不存在：……（请先运行 python tools/build_attraction_index.py）",
  "path": "./data/attractions_index.json" }
```

`cityCount` 为**含别名的索引条目数**（同一城市会有「杭州」「杭州市」两个键），不等于城市数。

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

- 票价、开放时间、等级、评分均为**离线快照**，不保证实时性；票价覆盖极低（11,858 条中仅 752 条有值）。
- 坐标存在 WGS-84（OSM）与 GCJ-02（高德）混用，偏差约 50–500 米，未做统一转换。
- 只做 `limit` 截断，没有分页游标；超过 50 条需收窄查询条件。
