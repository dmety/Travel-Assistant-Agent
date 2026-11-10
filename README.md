# Travel-Assistant-Agent · 旅行助手

面向多人出行的旅行协作规划项目，将方案、每日安排、交通、住宿和费用集中管理，帮助成员共同编辑行程、比较候选景点，并检查预算与时间冲突。

项目基于 **Java 21、Spring Boot 和 Maven**，提供浏览器联调页面、REST API 和控制台客户端。基础行程管理与本地景点检索无需外部密钥，高德查询与 DeepSeek 文字建议可按需启用。

## 核心功能与当前边界

| 能力 | 当前实现 |
| --- | --- |
| 账号与登录 | 注册、登录与基于会话的身份认证 |
| 行程管理 | 管理方案、每日安排、交通、住宿和费用五个板块 |
| 规划草案与确认保存 | 填目的地、日期、人数、预算后生成逐日草案；缺项会逐条反问，并检查日期与预算；用户点「确认保存」才写进行程，重启后可读回 |
| 成员协作 | 邀请成员、按板块授权、评论与变更记录 |
| 候选与投票 | 汇总候选方案或景点，通过投票辅助共同决策 |
| 预算与时间检查 | 汇总费用、分析预算，并提示行程中的时间冲突等问题 |
| 全国景点检索 | 查询本地景点知识库；当前 7,745 条记录，按省分片维护。北京、上海、深圳为当前重点核验城市 |
| 导出与归档 | 导出文本行程，归档及恢复行程 |
| Agent 辅助 | 提供候选景点、规则分析、逐日草案，以及可选的模型文字建议 |
| 购票辅助 | 北京三景点深度规则建议、行程检查与用户自报购票登记；其他景点保留官方入口查询 |

Agent 由 Java 编排景点、天气和票务工具，模型负责组织文字建议。**规划草案到确认保存的闭环已实现**：填需求 → 生成带景点 ID、来源与未知项的逐日草案 → 缺项补问 → 日期与预算检查 → 用户确认保存 → 重启后读回。生成草案和生成确认载荷都不会自动写库，只有用户点「确认保存」才落到正式行程。未配置模型时仍返回检索候选与规则结论，只是没有文字建议。

购票助手页面为 `/ticket-assistant.html`，可从每日安排活动进入；登记结果标注「用户自报，未经平台核验」。购票辅助不包含下单、支付或出票。

**尚未实现**：完整票务交易（下单、支付、出票、退改）、多角色 Agent 拆分、美食 Agent（目前只有架构设计，无代码）、真实密钥的成功调用联调（当前只验证了降级路径与本地桩）。

以上说明当前代码提供的能力；版本设计、自动化检查与团队正式验收分别管理，不代表所有规划功能均已完成。

## 快速开始

### 1. 准备环境

- JDK 21。
- Maven 3.9，作为建议使用的构建版本。**若 `mvn` 不在 PATH**，不必改系统环境变量：
  - 门禁脚本支持 `MVN=/path/to/mvn bash tools/gate.sh`，或设置 `MAVEN_HOME` 后直接 `bash tools/gate.sh`（脚本会自动探测 `$MAVEN_HOME/bin/mvn`）；
  - 直接执行 `mvn` 命令时，先确保 `<maven>/bin` 在当前 shell 的 PATH 中。
- Python 3.10+：用于知识库维护和真实服务回归，基础服务启动无需 Python。
- Windows 命令使用 PowerShell 7（`pwsh`）。以下命令均在项目根目录执行。

### 2. 构建并启动服务端

```powershell
mvn clean verify
java -jar travel-server/target/travel-server-0.1.0-SNAPSHOT.jar
```

构建成功后启动服务，打开 [浏览器联调页面](http://localhost:8080/)。默认端口为 `8080`，运行数据默认存放在项目根目录的 `data/` 下。

**Windows 免命令行启动**：项目根目录 `.env` 放好密钥后，双击 `tools\run-server.bat` 即可。它会读取 `.env`、把 `AMAP_API_KEY` 映射为 `AMAP_KEY`，再启动 JAR；启动横幅只显示各密钥有无，不打印值。需要换端口时用 `tools\run-server.bat 18080`。仓库解压路径含空格或中文时同样可用。

不想用脚本时，也可以在 IDE 的运行配置里一次性填好环境变量（`AMAP_KEY`、`BAIDU_AK`、`DEEPSEEK_API_KEY`），之后直接点运行按钮，效果相同。

> 这些只是**本地开发**的启动方式。部署给最终用户时，密钥配置在服务端进程的环境变量里，用户只需用浏览器访问，无需安装任何东西，也接触不到密钥。

### 3. 体验一次行程管理

1. 注册账号并登录。
2. **快速上手**：打开首页「规划草案 → 确认保存」卡片，填目的地、出发/返回日期、人数、预算，点「① 生成草案」。缺项会逐条列出补问，日期或预算异常会给出检查提示；确认无误后填标题、点「② 确认保存为行程」，系统才会真正写库。
3. 也可以直接创建行程，手动填写方案与出行信息。
4. 编辑每日安排、交通、住宿和费用；多人协作时可邀请成员并分配板块权限。
5. 查看预算分析和行程检查提示，调整安排。
6. 导出文本行程；需要收起的行程可归档，之后可恢复。

浏览器页面用于开发联调。完整接口及请求示例见 [REST 接口文档](docs/服务端REST接口文档.md)。

### 4. 使用控制台客户端（可选）

保持服务端运行，在另一个终端执行：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080
```

不传入子命令时执行服务连通性检查。也可以直接查询景点：

```powershell
java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar http://localhost:8080 attractions 杭州
```

## 可选配置

环境变量在启动服务端前设置，修改后需重启服务。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 服务端口；修改后需同步调整页面地址和客户端服务地址 |
| `TRAVEL_DATA_DIR` | `./data` | 业务数据目录，相对路径以启动时的工作目录为准 |
| `ATTRACTION_KB_PATH` | 数据目录下的 `attractions_index.json` | 只读景点运行索引路径 |
| `TICKET_RULE_FILE` | 数据目录下的 `ticket_rules.json` | 三景点规则与字段级来源，人工核验快照 |
| `TICKET_CHANNEL_FILE` | 数据目录下的 `ticket_channels.json` | 人工维护的官方购票入口文件路径 |
| `AMAP_KEY` | 空 | 高德 Web 服务密钥，用于地点、天气和路线查询 |
| `AMAP_TIMEOUT_MS` | `4000` | 高德查询超时（毫秒） |
| `DEEPSEEK_API_KEY` | 空 | 启用模型文字建议 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 模型服务地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名称 |
| `DEEPSEEK_TIMEOUT_MS` | `20000` | 模型调用超时（毫秒） |
| `SESSION_COOKIE_SECURE` | `false` | 会话 Cookie 是否只允许 HTTPS 传输；**对外部署时设为 `true`** |

PowerShell 7 示例：按需替换占位值，仅设置在当前终端会话中。

```powershell
$env:AMAP_KEY = '替换为你的高德 Web 服务密钥'
$env:DEEPSEEK_API_KEY = '替换为你的 DeepSeek API 密钥'
java -jar travel-server/target/travel-server-0.1.0-SNAPSHOT.jar
```

未配置高德时，地点、天气和路线查询不可用，规划结果会提示缺少实时天气。未配置模型或模型调用失败时，规划服务仍可返回检索候选及提示，不生成模型文字建议。本地行程、预算、协作和景点检索可独立使用。

自定义数据目录时，如需继续使用仓库中的景点索引和购票入口，应通过 `ATTRACTION_KB_PATH`、`TICKET_CHANNEL_FILE` 分别指定对应文件；深度购票助手还需通过 `TICKET_RULE_FILE` 指定规则文件。真实密钥、账号、会话与用户行程不得写入提交或交付材料。

## 开发与数据维护

### 开发检查

在项目根目录依次执行：

```powershell
python -B tools/kb_shards.py --check
python -B tools/build_attraction_index.py --check
python -B tools/kb_quality_gate.py
mvn clean verify
python -B tools/acceptance.py
```

五步分别检查分片一致性、知识库与运行索引一致性、知识库治理欠账、执行 Maven 测试和覆盖率检查、执行真实 HTTP 业务及进程重启恢复回归。最后一步依赖前一步生成的服务端 JAR。

治理审计要求存量欠账在 `tools/kb_quality_waivers.json` 逐项登记理由与证据；未登记的新失败、欠账恶化或豁免过期都会让门禁失败。**推荐直接用 `bash tools/gate.sh` 一次跑完上述全部检查**，脚本会自动探测 `MVN` 或 `MAVEN_HOME`。

回归脚本自动启动隔离服务，使用仓库外临时数据目录和随机端口，不调用外部 API，不修改现有业务数据。具备 Bash 环境时，可用 `bash tools/gate.sh` 执行同一流程。自动化检查通过后，仍需按需求完成团队正式验收。

### 知识库维护

知识库保留全国景点检索范围，当前 **7,745 条**记录、按省分成 30 个分片；北京、上海、深圳为当前重点核验城市。字段有值不等于已核验，历史快照不代表实时信息；未知票价不能视为免费，未知开放时间不能视为全天开放。

`data/china_attractions.json` 是 6.3 MiB 的单行产物，不直接编辑。编辑源按省切成 30 个小文件，放在 `data/attractions/`，每个文件一条记录一行，最大 524 KiB。改广东某景点的票价，只需打开 `广东省.json` 改那一行，再一键同步：

```powershell
python -B tools/kb_shards.py --check   # 确认分片与当前主库一致
python -B tools/kb_shards.py --sync    # 分片 -> 主库 -> 运行索引
```

`--sync` 按需写回主库，再按需重建 `data/attractions_index.json`。索引带构建时间戳，内容一致时跳过重建，避免无意义的字节变化。`--check` 要求分片合成结果与主库逐字节一致：改了分片没同步、绕过分片直接改主库、分片缺失或位置漂移，门禁都会失败。

主库记录原本不按省聚簇，分片里的 `positions` 保存每条记录在原数组中的下标，合成时按下标归位。`manifest.json` 登记顶层字段、城市简介和分片清单；增减分片要同步改清单，或运行 `--split` 从主库重建。旧治理脚本若直接改了主库，同样用 `--split` 反向对齐。需要按字段浏览全库时，可临时运行 `tools/kb_modules.py --split` 生成本地视图，该目录不入库、用完可删。

字段级来源、核验日期与必要证据集中维护。候选扫描和预览默认写入系统临时目录 `travel-assistant-kb-work/`，审查采用后才纳入 `reports/evidence/`；历史治理记录不作为当前核验结论。当前范围及核验情况见 [知识库范围与完整度清单](reports/current/知识库范围与最全景点清单.md)。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `travel-server/` | Spring Boot 后端、REST API 与浏览器联调页面 |
| `travel-client/` | 控制台客户端 |
| `travel-common/` | 共享模型 |
| `data/` | 全国源库产物、`attractions/` 按省分片编辑源、运行索引与购票配置；业务运行数据由各 `Store` 按需创建，不提交 |
| `tools/` | 构建检查、数据维护与回归入口 |
| `reports/` | `current/` 为当前核验清单，`evidence/` 为来源证据，`archive/` 为历史治理记录 |
| `docs/` | 需求、架构、接口、能力规划与开发日志 |

## 文档导航

- 需求与验收：[需求文档](docs/需求文档.md)、[基础业务闭环逐环节验收](reports/current/基础业务闭环-逐环节验收-2026-11-08.md)、[测试闭环逐项验收](reports/current/测试闭环-逐项验收-2026-11-08.md)。
- 架构与实现：[架构设计](docs/架构设计.md)、[业务功能技术实现方案](docs/业务功能技术实现方案.md)。
- 接口与配置：[服务端 REST 接口文档](docs/服务端REST接口文档.md)。
- 能力与规划：[各版本功能模块](docs/旅行助手-各版本功能模块.md)、[多 Agent 与票务能力方案](docs/多Agent与票务能力方案.md)；规划内容以代码实现和验收结果为准。
- Agent 设计（**仅设计，尚未实现**）：[接入规范](docs/Agent接入规范.md)、[运行时架构](docs/Agent运行时架构设计.md)、[美食 Agent](docs/美食Agent架构设计.md)。
- 知识库与证据：[知识库架构与周更工作流](docs/知识库架构与周更工作流.md)、[当前范围与完整度清单](reports/current/知识库范围与最全景点清单.md)、[来源证据](reports/evidence/)。
- 项目协作：[开发日志](docs/开发日志/)、[协作规则](AGENTS.md)。

## 许可证

本项目采用 [MIT License](LICENSE)。
