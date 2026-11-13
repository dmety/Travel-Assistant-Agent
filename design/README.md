# 旅行助手 · 界面设计稿

移动端与网页端的 UI 设计稿，以及品牌与功能图标系统。展示板只放在 `design/` 目录：
`api-console.html` 是开发者用的接口测试台，保持原样；`index.html` 与 `planner.html`
已按本稿重写，旧的 `chat.html` / `ticket-assistant.html` 已删除，原地址 302 到工作台。
`acceptance.py` 的静态页文本断言同批改到 `planner.html` 上。

真正跑在项目里的是 `travel-server/src/main/resources/static/` 下的两个页面：
`index.html`（登录/注册）与 `planner.html`（三个视图的工作台），另有 `favicon.ico`
放在 static 根目录。白名单、页面路由与同批测试见第 11 节。

项目里原有的蓝色静态页是早期随手生成的，本稿不以它为基准，重新设计。

---

## 1. 怎么打开

| 方式 | 说明 |
|---|---|
| 直接双击 | `design/mobile.html` / `design/desktop.html` / `design/icon.html`，浏览器打开即可，**无需构建、无需联网** |
| 本地服务 | `cd design && python -m http.server 8899`，访问 `http://127.0.0.1:8899/mobile.html` |

`tokens.css` 只用系统字体栈（`-apple-system / Segoe UI / PingFang SC / Microsoft YaHei / Noto Sans CJK SC`），
不加载 Google Fonts，断网也不塌版。想看真实地图才需要联网并注入百度 AK（见第 5 节）。

| 文件 | 用途 |
|---|---|
| `tokens.css` | 唯一色板与设计令牌；组件语义变量映射到第一版色板，并给出 `[data-theme="dark"]` 的深色取值 |
| `mobile.html` | 手机端 6 屏并排展示板（375 × 812 每屏） |
| `desktop.html` | 网页端单页（1440 × 900 基准） |
| `axure-kit.svg` | Axure 可导入的矢量元件库，7 节 |
| `brand-mark.svg` | 品牌主标唯一导出源，字面色值供 data-URI 独立渲染 |
| `icons.svg` | 28 个功能图标的 `symbol` 精灵图，`currentColor` 继承文字色 |
| `icon.html` | 图标规范页：四态主标、尺寸矩阵、图标全览、自检表 |
| `export/` | 9 张 PNG 与 5 个 Android mipmap 密度目录 |
| `tools/design_icons.py` | 项目根下的图标资产构建工具，见第 9 节 |
| `../travel-server/.../planner.html` | 落地页，接真实接口，见第 11 节 |
| `README.md` | 本文件 |

---

## 2. 设计方向与这一版改了什么

### 定调

| 项 | 决策 |
|---|---|
| 色板 | **沿用第一版暖纸 + 墨绿 + 赤陶**，用户已确认这一版整体色调可用 |
| 功能参考 | 只借鉴同类产品的**功能组织与页面间交互**（Wanderlog / TripIt / Roadtrippers / 圆周旅迹 / Mindtrip / Google AI Mode Canvas），不强制照搬视觉 |
| 网页端定位 | 主屏是整幅地图：没有表格、权限矩阵、后台页签、变更记录。AI 对话浮在左侧 |
| AI 的位置 | AI 对话是主入口；行程列表、地图、横向日程卡共用同一份行程数据 |
| 手机端形态 | 圆周旅迹式“上半地图 + 下半 AI 对话面板”，追问后地图路线就地重排，不跳页 |
| 图标 | 刻石方印加赤陶折线山径，与页面同一套色值；不用通用图标集 |

### 针对反馈的收敛过程

| 反馈 | 处理 |
|---|---|
| “AI 味道还是很强” | 曾误判为“砍光装饰”做了编辑式 v2，被否，已删除废稿并回退；改为保留视觉完成度、重做功能组织 |
| “你这还不如第一版” | 恢复第一版色板与质感，不再做减法式重做 |
| “网页版更像管理系统了” | 移除表格 / 权限矩阵 / 后台页签，改成整幅地图 + 横向日程卡 |
| “而且少了让 ai 规划的功能” | AI 规划回到主流程，桌面与手机端都能对话、出草案、补问、检查、确认保存 |
| “AI 对话可以学一下圆周旅迹” | 按真实截图重做：固定 query bar（可清除）、“深度思考完成（AI 生成）”、编号核心提醒、可展开来源、草案摘要、补问 chips、语音 / 发送控件；追问后地图同步重排 |

### 圆周旅迹式对话面板的构成

```
桌面：整幅地图铺满 → 左侧浮动 AI 面板（366 宽）
手机：appbar → 292px 地图（DAY 标签 + 路线 + 点位）→ 下半屏 sheet（480px）
       ├ 抓取条
       ├ 固定 query bar（一句话需求 + 清除 ×）
       ├ 深度思考完成（AI 生成）
       ├ 正文 + 编号“核心提醒”+ 来源折叠 + 草案摘要 + 补问 chips
       └ 输入栏（语音占位 / 发送）
```

追问时地图**留在原位**：路线 `d` 重绘、DAY 2 标签脉冲并显示“已减量”、map-pill 状态改为“路线已重排”；
桌面端同样有一次 `flash` 反馈。这是设计稿的演示交互，不是真实模型调用。

---

## 3. 设计令牌

全部定义在 `tokens.css`。组件层只用语义变量，不写死色值，因此改色板不需要动 HTML 结构。

### 色板（第一版，用户确认保留）

| 用途 | 变量 | 值 |
|---|---|---|
| 页面底 | `--paper` | `#F7F4EE` |
| 卡片面 | `--surface` | `#FFFFFF` |
| 暖白面 / 面板 | `--surface-warm` | `#FCFAF5` |
| 次级纸色 | `--paper-2` | `#EFEAE0` |
| 正文 | `--ink` | `#20241E` |
| 次级正文 | `--text-2` | `#4A4F46` |
| 说明文字 | `--text-3` | `#6E6A5E` |
| 主色 / 主行动 | `--pine` → `--primary` | `#1E4A3E` |
| 主色浅 | `--pine-soft` → `--primary-soft` | `#E4EDE7` |
| 强调（需人决断处） | `--clay` | `#B4542A` |
| 强调浅 | `--clay-soft` | `#F6E8DF` |
| 等级 | `--gold` | `#8A6D2B` |
| 已核验 | `--ok` | `#2E6B52` |
| 分隔线 | `--line` | `#DED7C9` |
| 控件边框 | `--line-strong` | `#C4BAA6` |
| 警示 / 错误 | `--warn` / `--err` | `#8A6212` / `#A03A2C` |

赤陶只给“待投票 / 待定 / 需要决断”，不做通用装饰；墨绿承担主行动与选中态。

### 深色主题

`tokens.css` 末尾给出 `html[data-theme="dark"]` 的整套取值，纸换成夜里的墨
（`--paper #171A16` / `--surface #212620`），绿与陶土各提亮一档
（`--pine #4E8C72` / `--clay #D9805A`），阴影改用纯黑。

三条约束，改深色时逐条对一遍：

| 约束 | 为什么 |
|---|---|
| 绿色当**文字**用走 `--pine-text`（`#9ACDB4`），不走 `--pine` | `--pine` 提亮后作前景仍偏暗，压在 `--surface` 上不足 4.5:1 |
| 绿色当**按钮底色**走 `--action-bg`（`#2F6B57`）+ `--action-fg`（`#F2F7F3`） | 反向问题：细绿底配白字，白字压不住 |
| `--surface` 是字面量（浅色下写死 `#FFFFFF`），深色块必须重写 | 其余语义变量是 `var(--palette)`，会自动跟随 |

`tokens.css` **只提供显式深色**，不写 `prefers-color-scheme` 媒体查询：
本文件的使用者是 `design/*.html` 三张规范稿，含浅色示意底图与浅色卡片，
跟随系统变深会把稿里的浅底文字压成白字。需要“跟随系统”的应用页
（`planner.html` / `index.html`）各自内联同一份令牌并自行补上媒体查询。

应用页的主题三态：

| `<html>` 上 | 行为 |
|---|---|
| 无 `data-theme` | 跟随系统 |
| `data-theme="light"` | 强制浅色 |
| `data-theme="dark"` | 强制深色 |

开关状态存 `localStorage` 的 `travel.theme`，`<head>` 里先用一段同步脚本读一次，
避免首帧闪白；按钮在浅/深之间切换 `#i-moon` / `#i-sun`。

### 字阶 / 间距 / 圆角 / 阴影

- 字号 5 级：`--fs-xs 12 / --fs-sm 14 / --fs-md 16 / --fs-lg 20 / --fs-xl 25`；字重只有 `400 / 600` 两档
- 间距 8 级：`4 / 8 / 12 / 16 / 24 / 32 / 48 / 64`
- 圆角：`3 / 6 / 10 / 16 / 999`
- 阴影三级：`shadow-1/2/3`，带绿褐环境色 `rgba(46,42,32,…)` 而非纯黑
- 动效：微交互 160ms；`prefers-reduced-motion` 下直接到位

---

## 4. 交付内容

### 移动端 `mobile.html`（6 屏 × 375 × 812）

| # | 屏幕 | 设计要点 |
|---|---|---|
| 01 | 行程列表 | 底部中间 `+` 是唯一主入口；点开是“新建方式”底部 sheet |
| 02 | AI 规划 | 上半 292px 地图（DAY 标签 / 路线 / 点位）+ 下半可滚动对话 sheet；query bar、深度思考、核心提醒、来源、草案摘要、补问 chips、语音 / 发送；追问后路线就地重排 |
| 03 | 草案与确认 | 逐日草案 + 检查项 + “确认保存”；未确认前一律“草案未保存”，不写库 |
| 04 | 列表模式 | 行程详情列表视图，与地图共用同一份数据 |
| 05 | 地图模式 | 行程详情地图视图，点位与列表联动 |
| 06 | 待定与协作 | 底部滑出抽屉：候选 / 待规划 / 成员 / 预算体检 |

设计约束：**候选不足的天留空**，不补景点、不编造游览时刻；票价未核验时预算结论写“无法判定”，
不写“没超支”。这是与项目契约（数字 grounding、外部 facts 一律 unverified）一致的如实呈现。

### 网页端 `desktop.html`（1440 × 900）

单页：

```
topbar（品牌 / 行程名 / 成员头像 / 成员 · 体检 · 导出行程单）
└ workspace
   ├ map-stage（整幅铺满）：示意底图或 BMapGL、按天路线、点位 pin、
   │                        trip-card、spot-card、fabs、legend、模式标注
   ├ ai-panel（左浮动 366×792）：AI 规划对话
   ├ daystrip（底部横向日程卡，左 414 / 右 24 / 底 22）
   └ drawer（右侧抽屉，4 个 pane：候选与投票 / 待规划地点 / 成员与动态 / 预算与体检）
```

顶部只有一条 60px 的行程信息条；状态信息只在选中点的详情卡出现一次，不做成常驻清单。
预算、成员、候选、变更记录全部收进抽屉，主屏保持“地图 + 对话”。

两份设计稿顶栏的“行”字方框已换成品牌主标，`× ⌄ ↑ ◉ ‹ ▤ ◇ ◌ ○` 等字符占位符
全部换成 `svg.ico` 线性图标，详见第 9 节。

---

## 5. 百度地图接入

按官方 JS API GL 版接入，**AK 不硬编码在文件里**：

```js
window.BAIDU_MAP_AK = '你的 AK';   // 方式一：在页面里注入
// 方式二：页面地址追加 ?ak=你的 AK
```

- 有 AK：加载 `https://api.map.baidu.com/api?v=1.0&type=webgl&ak=…&callback=__initBaiduMap`，
  把行程点位画成圆形徽标并用 `Polyline` 连主径，隐藏示意底图与 pin，标注条改“实时地图”。
- 无 AK：**保留 SVG 手绘示意底图**，标注条显示“未配置 AK · 示意底图”，控制台给一次提示。
  **不伪造地图、不把示意当成真实地理数据。**

`POINTS` 示例坐标是武当山一带的经纬度，`tag` 统一为字符串（曾有一个 number 笔误，已修）。
手机端同理：无 AK 时地图区是等高线示意 + DAY 标签 + 路线，不加载任何外部脚本。

---

## 6. 自检结果（2026-11-12 实测）

用无头 Edge 的 DOM 探针在浏览器内量取，并用 Python / Node 做静态校验：

| 检查项 | 结果 |
|---|---|
| 手机 6 屏横向溢出 | 全部 `scrollWidth = clientWidth = 373`，**0** |
| 手机 6 屏纵向溢出 | 全部 `scrollHeight = clientHeight = 810`，**0**（长内容由内部 scroller 承担） |
| 02 屏高度分配 | appbar 52 + 地图 292 + sheet 480 − 重叠 14 = **810**，精确占满 |
| 02 屏会话滚动 | `thread2` client 306 / scroll 736，内部滚动，不撑破手机框 |
| 网页端横向溢出 | `scrollWidth = innerWidth = 1440`，**0** |
| 网页版面 | workspace 1440×840；ai-panel x=24 w=366 h=792 bottom=876（在 workspace 内）；daystrip x=414 right=1416；与面板**无重叠** |
| 地图降级 | 未注入 AK 时 `BMapGL` 不存在，标注条 =“未配置 AK · 示意底图” |
| 交互探针 | 发送追加 3 个节点 + `flash` 触发；清除问题 / 来源折叠 / 点位选中全部生效 |
| 手机追问反馈 | 路线 `d` 已改变、`mapPillMeta2` =“· 路线已重排”、追问气泡 1→2、`is-rerouting` 已加 |
| HTML 结构 | 三份页面均无重复 id、无未闭合 / 错配标签，`getElementById` 与 `<use>` 目标全部存在 |
| 内联 JS | `node --check` 三份脚本均通过 |
| SVG 素材 | `axure-kit.svg` XML 解析通过，273 个元素，32 段注释无非法双连字符，坐标均在 1600×1600 内 |
| 图标渲染 | 五份页面内联的 `symbol` 合计 323 个（icon 42 / desktop 60 / mobile 70 / planner 106 / index 45），重复 id 0、悬空引用 0；顶栏主标 32px；28 个图标格齐全 |
| 图标页视口 | 375 / 768 / 1440 三档 `scrollWidth = clientWidth`，溢出元素 0 |
| 手机展示板窄屏 | 375 视口下 `scrollWidth = clientWidth = 360`，6 屏横向 / 纵向溢出仍全为 0 |
| 图标资源 | `favicon.ico` 含 16 / 32 / 48 三尺寸，Pillow 回读正确；9 张 PNG 与 5 个 mipmap 均非空白 |
| 构建工具 | `design_icons.py all` 全流程通过，`wire` 重复运行结果不变 |
| 工作台三视图 | 三视图共用一页，`location.hash` 切换；DOM 探针 `docH = clientH = 805`，`overflow: hidden` 生效，横向 / 纵向溢出均 0 |
| 工作台双主题 | 浅色 / 深色各测一遍，`scrollWidth = clientWidth`、`scrollHeight = clientHeight`；深色下重算对比度，票务主按钮 5.59:1、次按钮 6.04:1 |
| 工作台真实窄屏 | 504 CSS px 视口（无头 Edge 的 `--window-size` 给外框尺寸）下布局完整，无横向溢出 |
| favicon 落地 | 重新打包后匿名 `GET /favicon.ico` = 200，5424 字节与源文件一致；`/index.html` / `/planner.html` / `/api/health` 仍为 200；`/chat.html` / `/ticket-assistant.html` 为 302 到 `/planner.html` |

未做像素级自动比对。图标与三份页面另做了无头 Edge 截图目测（含 16px 放大预览），
结论来自 DOM 数值测量、静态校验与截图观察。间距节奏、色彩观感请在浏览器里再过一眼。

---

## 7. 在 Axure 里复刻

Axure 的 `.rp` / `.rplib` 是闭源二进制，**无法由代码反向生成**。可行路径是拿素材与规格在 Axure 里重建：

1. **导入矢量元件**：把 `axure-kit.svg` 拖进画布，右键“变换形状 → 取消组合”，拆成独立元件。
   共 7 节：01 色板 / 02 印章与点位 / 03 按钮与表单 / 04 标签 / 05 卡片 / 06 山形插画 /
   07 AI 对话与地图（桌面浮动面板、手机上半地图 + 下半 sheet、map-pill、DAY 标签、横向日程条、规格表）。
   品牌主标 `brand-mark.svg` 与功能图标符号 `icons.svg` 可单独拖入画布取消组合后当元件用。
2. **建样式库**：`项目 → 样式管理器`，按第 3 节 token 建文字样式与填充样式，之后所有元件引用样式。
3. **画板尺寸**：移动端 375×812，网页端 1440×900。
4. **中文字体**：Axure 只认系统已装字体，中文选“思源黑体 CN”或“微软雅黑”；
   本稿不依赖 Fraunces 等外部展示字，无需额外装字体。
5. **版式骨架**：网页端用“动态面板”做整幅地图底 + 浮动 AI 面板 + 横向 daystrip；
   候选 / 待规划 / 成员 / 预算做成 4 个抽屉状态，`On Click → Set Panel State`。
   手机端用动态面板做“地图固定 + sheet 上下拖动”。
6. **交互优先级**：先串起“说一句需求 → 草案 → 追问就地重排 → 检查 → 确认保存”这条主流程，
   投票与预算用静态状态即可，不必每个按钮都做逻辑。

---

## 8. 与后端接口的对应关系

下表路径已对照 `travel-server/src/main/java/com/travelassistant/server/interfaces/rest/` 的 `@RequestMapping` 核实：

| 界面元素 | 接口 |
|---|---|
| 意图识别（城市 / 天数 / 预算 / 偏好） | `POST /api/intent/parse` |
| AI 规划对话主入口 | `POST /api/agent/invoke`（L1 编排，需登录） |
| 行程草案卡 | `POST /api/agent/plan` |
| 用户确认后生成行程载荷 | `POST /api/agent/plan/materialize`（仍不落库） |
| 确认写入行程 | `POST /api/itineraries` |
| 方案投票 | `POST /api/agent/vote`（两轮协议 + `PlanVoteTally`） |
| 每日时间轴 | `GET /api/itineraries/{id}/daily` |
| 预算分类条 / 体检 | `GET /api/itineraries/{id}/budget/analysis`、`…/checks` |
| 候选与投票 | `GET/POST /api/itineraries/{id}/candidates`、`…/candidates/{candidateId}/votes` |
| 地图点位 | `GET /api/attractions?city=` 的 `lat`/`lon` + `/api/location/*` |
| 成员与协作 | `GET /api/itineraries/{id}/members`、`GET/POST …/comments` |
| 导出行程单 | `GET /api/itineraries/{id}/export` |

界面上“未核验 / 未知 / 无法判定”的标注对应票价、开放时间、库存的核验状态。
模型只组织语言，不产生事实。

---

## 9. 图标系统

### 定调

一枚右下被 45 度切开的刻石方印。三道等高线收成山峰，一条赤陶折线山径用纸色宽线把等高线刻断，末端落在切口内侧。方印、折线、落点这三件事缩到 16px 仍能认出来。不用地球、相机、定位针，也不拿通用线性图标集凑数。

功能图标沿用同一套语言：凡有外框，右下角斜切；凡表位置，用实心落点加纸色内点；凡表路径，一律折线。25 个符号各对应一个真实动作，不用于纯装饰。

### 四个主标变体

| 变体 | 用途 | 做法 |
|---|---|---|
| Full | 网页顶栏、文档封面、站点图标 | 纸底加墨绿加赤陶，完整三层等高线 |
| Mono | 单色印刷、印章 | 赤陶并入墨绿，山径改虚线，不靠颜色分层 |
| Inverse | 深色工具栏、App 图标底板 | 印框实填墨绿，线条反白，落点换浅陶保对比度 |
| Micro | 16px favicon | 删掉等高线与起点，只留方印、折线、落点，线宽加倍 |

色值取自 `tokens.css`：纸 `#F7F4EE`、墨绿 `#1E4A3E`、赤陶 `#B4542A`。`brand-mark.svg` 写字面值而不写 CSS 变量，因为导出时以 data-URI 独立渲染，外部变量不会传进去。展示页里的变量版由构建工具从源文件派生，改主标只需改 `brand-mark.svg` 一处。

### 唯一源与构建步骤

| 源文件 | 派生产物 | 命令 |
|---|---|---|
| `brand-mark.svg` + `icons.svg` | `icon.html` 的 `inlineDefs` 内联符号 | `inline` |
| `icons.svg` + `brand-mark.svg` | `desktop.html` / `mobile.html` 的 `glyphDefs` 与 `svg.ico` | `wire` |
| `brand-mark.svg` | 9 张 PNG、5 个 Android mipmap、`favicon.ico` | `export` |
| 以上全部 | XML、引用关系与结构校验 | `check` |

```
python tools/design_icons.py all     # 依次跑 inline / wire / export / check
python tools/design_icons.py check   # 只校验，不写文件
```

本机 Python 需带 `PYTHONPATH` 指向 F 盘包目录；`export` 依赖无头 Edge，路径不对时用 `TRAVEL_EDGE` 指定。`wire` 可重复运行，结果不变。HTML 里 `inlineDefs` 与 `glyphDefs` 两个区块由工具生成，不要手改。

### 落地范围

`favicon.ico` 含 16 / 32 / 48 三种尺寸，放在 `travel-server/src/main/resources/static/` 根目录。Spring Boot 会把它映射为 `/favicon.ico`，该路径已在安全白名单，浏览器请求即可拿到，四个静态页不需要加 `link` 标签。

实测匿名 `GET /favicon.ico` 返回 200、`image/x-icon`、5424 字节，与源文件逐字节一致。图标文件是新增静态资源，需要重新执行 `mvn -pl travel-server -am package` 才会进包；旧 jar 里没有该资源时，404 转发到 `/error` 会被认证规则拦成 401，看起来像白名单失效。

`design/export/png/` 的 9 张 PNG 与 `design/export/android/` 的 5 个 mipmap 供后续做 PWA 图标和 Android 启动图标时取用，当前没有写进 `travel-mobile` 工程。

`icon.html` 是图标规范页，双击即可打开：四态主标、16 到 64px 尺寸矩阵、25 个功能图标全览、深浅底与状态对照、复制 SVG、可访问性自检表。页面用 `tokens.css` 的纸质纹理与等高线氛围层，`prefers-reduced-motion` 下取消入场动效。

### 设计稿内的图标替换

两份设计稿顶栏的“行”字方框已换成 Full 主标，`× ⌄ ↑ ◉ ‹ ▤ ◇ ◌ ○` 等字符占位符全部换成 `svg.ico`。图标用 `currentColor` 继承控件文字色，尺寸按 `1em` 跟随字号，因此深色底、选中态、禁用态不用再准备第二套图。

---

## 10. 已知限制

1. `design/` 三张规范稿仍只有浅色基线；暗色只在落地页 `planner.html` 与 `index.html` 里验证过。
   `tokens.css` 已给出 `[data-theme="dark"]` 整套取值（见第 3 节），但没写自动跟随系统的媒体查询——
   稿里含浅色示意底图，跟随系统变深会把浅底文字压成白字。
2. 地图降级底图是 SVG 手绘示意，不含地理数据，只用于视觉占位与断网演示。
3. 展示板里的页面数据是设计样例（武当山四日 / 3 人 / 6 个候选），不写任何 Store，不产生真实业务；
   对话回复是预设文本。落地页 `planner.html` 已接真实接口，见第 11 节。
4. 语音按钮是演示占位，未接 ASR。
5. 手机端画板是 6 屏并排的展示板（900px / 600px 两档断点收窄外边距并把手机框压到视口宽），
   不是可直接装机的移动端页面；要上真机需按 `tokens.css` 切到单屏布局。
6. `favicon.ico` 已落在 static 根目录，浏览器自动请求即可生效；`planner.html` 与 `index.html`
   另有显式 `link` 标签，`api-console.html` 没有。
7. Android mipmap 只是导出包，未接入 `travel-mobile` 工程；PWA manifest 也未配置。

---

## 11. 落地为可运行页面

`design/` 里的两份 HTML 是多屏展示板，用来定视觉与交互；真正跑在项目里的是
`travel-server/src/main/resources/static/` 下的两个页面。`planner.html` 把桌面稿的
“整幅地图加浮动 AI 面板”与手机稿的“上半地图加下半对话面板”合成一份响应式页面，
900px 与 600px 两档断点切换，共用同一份行程数据；`index.html` 是登录/注册页。

### 一份页面三个视图

工作台不再是“一个页面一件事”，而是外壳 + 三个视图，靠 `location.hash` 切换：

| 视图 | hash | 内容 |
|---|---|---|
| 规划 | `#plan` | 地图 + AI 面板（原 `chat.html` 的对话、卡片、投票全在这里） |
| 票务 | `#ticket` | 购票需求 → 建议（原 `ticket-assistant.html` 整块搬入） |
| 行程 | `#trips` | 我的行程、逐日安排、保存与版本 |

搬入的票务样式全部加 `#viewTicket` 前缀作用域，脚本整段包在 IIFE 里，
`$` / `api` / `el` 不与外壳冲突。外壳是“整页不滚动、滚动只在视图内部”，
所以 `html, body` 都写了 `overflow: hidden`。

### 入口与安全

| 地址 | 说明 |
|---|---|
| `/planner` | `PageController` 转发到 `/planner.html` |
| `/planner.html` | 工作台静态页，匿名可达；页内读 `/api/account/me`，401 时跳 `/?next=/planner.html` |
| `/` | 登录/注册页，已按本稿重写，登录后默认落到 `/planner.html` |
| `/chat.html`、`/ticket-assistant.html` | 旧页已删除，`PageController` 302 到 `/planner.html`（保留放行，否则连 302 都看不到） |
| `/api-console.html` | 开发者用的接口测试台，保持原样 |

`SecurityConfiguration` 白名单收敛为一段：`/chat.html`、`/ticket-assistant.html`、
`/api-console.html`、`/`、`/login`、`/app`、`/login.html`、`/app.html`、`/index.html`、
`/favicon.ico`、`/planner.html`、`/planner`。页面公开，数据和模型配额仍由 `/api/**` 拦截。
`SecurityRulesTest` 同批改了三处断言：`/chat.html` 改为断言 302 到 `/planner.html`，
`plannerPageIsPublic` 增加 `id="viewTicket"` 结构标记，`TicketRecordApiTest` 的
`/ticket-assistant.html` 改为断言 3xx。`tools/acceptance.py` 里读旧对话页的十处
文本断言整体改到 `planner.html` 上。

### 接了哪些真实接口

| 界面动作 | 接口 |
|---|---|
| 登录态与退出 | `GET /api/account/me`、`POST /api/logout` |
| 一句话对话（首选链路） | `POST /api/chat`，返回 `{reply, cards[], trace, state, note}` |
| 理解一句话需求（模型不可用时回退） | `POST /api/intent/parse` |
| 生成草案 | `POST /api/agent/invoke`（`intent=PLAN_DRAFT` 或 `FOOD_RECOMMEND`） |
| 编排层降级兜底 | `POST /api/agent/plan` |
| 确认前重新装配载荷 | `POST /api/agent/plan/materialize` |
| 写入行程与逐日安排 | `POST /api/itineraries`、`PUT /api/itineraries/{id}/daily?version=N` |
| 我的行程：列表 / 详情 / 删除 | `GET /api/itineraries`、`GET /api/itineraries/{id}`、`DELETE /api/itineraries/{id}` |
| 导出行程单 | `GET /api/itineraries/{id}/export` |
| 购票建议与票种 | `POST /api/agent/tickets/advice`、`GET /api/tickets/catalog` |
| 百度地图真实坐标 | `GET /api/attractions?city=`（仅在注入 AK 时取用） |

主链路是「先问 `/api/chat`，失败再走解析 + 编排」：`/api/chat` 一次就能拿回
`reply` 与结构化 `cards`（景点卡、候选卡、投票卡、票务卡都由同一套 `paintCard` 渲染）；
它不可用时退到 `/api/intent/parse` → `/api/agent/invoke`。草案取自契约信封
`result.facts` 里 `category=PLAN_DRAFT` 的 `CONTENT` fact；编排层落到 `builtin.rules`
没有结构化草案时，回退调 `/api/agent/plan`，保证最差情况也有逐日安排与检查项。
补问按 `questions[].field` 落回请求再重排；候选不足的天留空，不补景点、不编造游览时刻。

### 仍是占位

语音按钮只提示未接 ASR；成员协作与预算体检两块抽屉没有搬进落地页，
仍在 `design/desktop.html` 里做交互演示。地图未注入 AK 时是 SVG 示意底图，
点位按草案顺序沿示意路线分布，页面明确标注“示意底图 · 非导航用”；
注入 `window.BAIDU_MAP_AK` 或 `?ak=` 后才启用 BMapGL 并按知识库经纬度打点。

### 构建与同步

图标定义由 `tools/design_icons.py wire` 从 `design/icons.svg` 与
`design/brand-mark.svg` 派生注入 `planner.html` 与 `index.html` 的 `glyphDefs` 区块，
不要手改；`check` 会把五份页面一起校验（重复 id、悬空 `<use>`）。
色板在两个页面内联各一份，改动以 `design/tokens.css` 为准，深色的三态与两条对比度
约束见第 3 节。
新增静态资源需要重新 `mvn -pl travel-server -am package` 才会进 jar，
否则请求会落到 404 再被认证规则拦成 401。
