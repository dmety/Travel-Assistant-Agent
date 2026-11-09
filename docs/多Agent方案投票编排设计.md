# 多 Agent 方案投票编排设计

本文说明旅行助手的“主 Agent 生成多套方案、其他 Agent 打分、主 Agent 计票”怎么落地。计票原型已在 `tools/agent/plan_vote.py` 跑通，22 个自测用例全过，并被门禁 `tools/gate.sh` 每次交付前复跑。Java 侧 P0 运行基座与 L1 编排骨架已于 2026-11-09 落地，两轮投票协议也在同日晚上线：三位评委 Bean、`POST /api/agent/vote` 与确定性计票器 `PlanVoteTally` 已实现并接入主 Agent。下文除“待决”外均已在代码里兑现。

## 与其他文档的关系

《多 Agent 与票务能力方案》开头已注明，它是 2026-11-02 的目标态记录，不作运行时最高口径。本文遵循《Agent 运行时架构设计》和《Agent 接入规范》的契约，不改契约主版本。

Java 侧已有一套 V3 的“候选与投票”，`CandidateService` 和 `VoteTally` 让成员对候选投 `SUPPORT` / `OPPOSE` / `ABSTAIN`。那是**人对方案投票**。本文的投票由 Agent 给出结构化评分，两者概念不同，不复用同一套表和算法。混用会让“几票支持”和“几个评分项”出现在同一处，结果无法解释。

两套做法共用的边界：投票只产出建议，不自动改写行程。方案要写进行程，仍需用户显式确认。

契约侧不新增内容。请求沿用现有 `DECISION_ADVICE` intent，评分用 `CONTENT` 类型的 `Fact` 承载，`value.category` 写 `PLAN_SCORE`。这与《美食 Agent 架构设计》放餐饮候选的做法一致，都不动 `Fact.type` 枚举，因此 `schemaVersion` 保持 1.x，不必重跑全部 Provider 准入。

## 两轮协议

第一轮征集事实。每个评委 Agent 按自己的职责上报 `facts`，编排层记录每个 `providerId` 交过哪些 `factId`。主 Agent 汇总这些事实，生成 N 套候选方案，每套方案带 `planId` 和它引用的 `factId` 列表。

第二轮评审。编排层把 N 套方案一次性发给每个评委。评委一次调用返回对所有方案的评分票，不按方案拆成多次调用。编排层校验全部票，再计票。

评委在两轮里是同一批 Provider，但评分标准必须各自独立。预算看总花费和费用披露，美食看餐厅质量和忌口覆盖，交通看换乘次数和步行距离，天气看室内备选和降雨风险，体力看步行负荷和休息时段。各评委只允许按自己声明的评分项打分。

## 评分票的形状

评分票是响应 `facts` 数组里的一条 `Fact`。`providerId` 由编排层按请求来源记录，不写进 `Fact`，保持 `additionalProperties: false` 下字段不膨胀。

```json
{
  "factId": "score:budget.auditor:A",
  "type": "CONTENT",
  "value": {
    "category": "PLAN_SCORE",
    "planId": "A",
    "rubric": "budget.auditor",
    "scores": { "totalCost": 5, "feeDisclosure": 4 },
    "groundingFactIds": ["f-budget-A"],
    "decision": "SCORE"
  },
  "evidence": {
    "sourceKind": "EXTERNAL",
    "verificationStatus": "unverified",
    "fetchedAt": "2026-11-09T06:00:00Z"
  }
}
```

分项分数取 0 至 5 的整数或小数。`decision` 有三种取值：`SCORE` 给分，`ABSTAIN` 表示本评委对这套方案没有可用信息，`VETO` 表示命中硬约束。评委归一化分数取各评分项的算术平均，再按声明权重进入计票。

`groundingFactIds` 只能填本评委在第一轮上报过的 `factId`。评委不写自由文本理由，理由由编排层从 `breakdown` 里的结构化分项拼出。这样 `advice` 不会引入 facts 之外的店名、数字或链接，规避 `E_SEMANTIC_ADVICE`。

## 校验规则

编排层默认拒绝，不做静默降级。校验不通过的票整体丢弃，在 `rejections` 里留原因码。

| 原因码 | 判定 | 来源 |
| --- | --- | --- |
| `E_SCHEMA_ADDITIONAL` | `Fact`、`value`、`evidence` 出现白名单之外的键 | 规范已有 |
| `E_SCHEMA_TYPE` | `type` 不是 `CONTENT`，或 `decision`、`scores` 类型不对 | 规范已有 |
| `E_SCHEMA_LIMIT` | 分项分数超出 0 至 5 | 规范已有 |
| `E_SCHEMA_REQUIRED` | `SCORE` 票没给任何评分项 | 规范已有 |
| `E_SEMANTIC_VERIFIED` | `verificationStatus` 写了 `verified` | 规范已有 |
| `E_SEMANTIC_GROUNDING` | 引用了本评委未上报的 `factId`，或无依据就否决 | 规范已有 |
| `E_VOTE_RUBRIC` | 用了未声明的评分项、别人的 `rubric`，或越权否决 | 编排层局部 |
| `E_VOTE_UNKNOWNPLAN` | `planId` 不在本轮候选里 | 编排层局部 |
| `E_VOTE_DUPLICATE` | 同一评委对同一方案交了两张票 | 编排层局部 |

后三条已定案为编排层局部原因码，不在《Agent 接入规范》的原因码表里，也不进 `AdmissionReport`。它们校验的是编排层的轮次状态，判定对象不是单个 Provider 的响应。判定依据见“新增原因码的查明依据”，边界见《Agent 运行时架构设计》「编排层局部原因码」一节。

## 计票规则

计票是纯函数，输入只有票、方案列表和评委声明，不读时钟、不取随机数，同样的输入必得同样的输出。

**（1）否决硬剔除。** 任一评委投 `VETO`，这套方案直接退出排名，进 `excluded`。否决不降权、不回显到推荐理由，只记否决者和它引用的 `factId`。这条沿用《美食 Agent 架构设计》的忌口口径：硬约束命中就整条剔除。

**（2）弃票不进分母。** `ABSTAIN` 和未交票的评委都不计入该方案的分母，避免把“不知道”算成 0 分。某套方案没有有效评分时，它不参与排名，`notices` 写明原因。

**（3）加权求和。** 方案得分等于各评委归一化分数乘权重之和，除以参与该方案的权重之和。权重来自评委声明，不按票数自动调整。

**（4）平局三步裁决。** 先比得分，再比有效评委数，仍相同就按 `planId` 字典序取最小。裁决过程写进 `notices`。

**（5）同构检测。** 若每个方案上所有评委的归一化分数都相同，计票器把 `homogeneous` 置为 `true`，并在 `notices` 提示投票没提供额外信息。此时应检查各评委是否用了独立的评分项，而不是把票数当成可信度。

输出只含结构化数据：排名、每套方案的 `breakdown`、`excluded`、`notices`、`rejections`。`breakdown` 每项带评委、归一化分数、权重、分项分数和依据 `factId`。

## 实测结果

原型 `tools/agent/plan_vote.py` 用 5 个评委、3 套方案跑自测，22 个用例全过。评委权重为预算 1.2、美食 1.0、交通 1.0、天气 0.8、体力 1.0。

| 用例 | 结果 |
| --- | --- |
| 异质评委正常票 | 0 条拒绝 |
| 体力评委否决方案 B | B 进 `excluded`，不参与排名 |
| 体力评委对方案 C 弃票 | C 的权重和为 4.0，弃票未计入分母 |
| 方案 C 得分 | 3.7750，4 位有效评委 |
| 方案 A 得分 | 3.4200，5 位有效评委 |
| 推荐结果 | C |
| 单看预算评委 | A 胜；全员投票后 C 胜 |
| 同构评委 | `homogeneous=true`，`notices` 给出退化提示 |
| 重复运行 | 排名与推荐完全一致 |
| 平局 | 按 `planId` 字典序裁决为 A |
| 8 类负例 | 各自命中对应原因码 |

单看预算选 A，全员投票选 C，其余评委的视角改变了结论。同构场景给出反证：评委给同一套分数时，计票器会报退化提示。

负例覆盖引用他人 `factId`、自称 `verified`、分数越界、未声明评分项、未知方案、多余顶层字段、重复票、无依据否决。

## 调用次数与预算

评委一次调用返回对所有 N 套方案的评分，所以评审阶段是 M 次调用，不是 N 乘 M 次。两轮合计约 2M 次，M 是评委数。原型里 M 为 5，合计 10 次。

主 Agent 生成方案另算一次模型调用。候选方案数要设上限，方案越多，评分票越长，会逼近 `facts` 的 50 条上限和响应体大小限制。超过上限时按 `planId` 截断，不悄悄丢弃，并在 `notices` 写明。

评分票算 `facts`，与领域事实共用 50 条配额。M 个评委、N 套方案时，单个评委返回 N 张评分票。若还要回传领域事实，总条数是两者之和，落地时按这个数校核上限。

## 待决

三条 `E_VOTE_*` 的归属已定案，见上节，已在《Agent 运行时架构设计》单列一节，不列入本表。

| 项 | 待定内容 |
| --- | --- |
| 权重来源 | 评委权重写进注册配置，还是按用户偏好动态给 |
| 否决范围 | 哪些硬约束允许否决，是否只限预算、忌口和体力 |
| 方案生成 | 主 Agent 生成方案时怎样绑定 `factId`，需要定结构化草案格式 |
| 结果落地 | 推荐结果是否写进 V3 候选看板，由用户再投一次人票 |
| 降级 | 评委超时或拒绝时，是降低该评委权重还是整体降级 |

### 新增原因码的查明依据

《Agent 接入规范》是 Provider Admission Contract 1.0，基准提交 `66f00fa`，文件头注明规则、Schema、闸门与接口都未落地代码。它的原因码表只列 `E_META_*`、`E_TRANSPORT_*`、`E_SCHEMA_*`、`E_SEMANTIC_*`、`E_SECURITY_*`、`E_PROBE_*` 六组，判定对象是单个 Provider 的单次响应；第 312 行写明原因码是稳定接口，接入方按 `code` 自助修复。

三条新码覆盖的校验，现有码接不上。`planId` 不在本轮候选，不属于 `E_SEMANTIC_GROUNDING` 管的事实接地；重复票是跨 fact 的聚合一致性，`E_TRANSPORT_IDEMPOTENCY` 管的是同一 `runId` 重放；rubric 越权是评委声明与票的一致性，规范里没有对应项。原型不能假装已有码能覆盖，否则负例对不上号。

纳入规范的代价也比“升规范版本”这句说法更大。规范第 421 至 424 行的版本治理表规定，收窄枚举或把 `WARN` 升 `BLOCK` 要升主版本 1.x 到 2.0，全部已 ACTIVE Provider 转 `DEPRECATED` 重新准入。三条码都是新增的 BLOCK 级拒绝条件，票会被整体丢弃，落在收窄一侧。第 427 行还要求规范变更同步更新规范本文、`tools/agent/provider-contract.json`、gold cases 与《Agent 运行时架构设计》的引用，不允许只改一处。定案时 `provider-contract.json` 与 gold cases 还没建，Java 侧 `ContractValidator` 等基座也是零文件，当时纳入连同步对象都不齐。这些文件与基座同日已补齐，结论不变。

更根本的是层级。候选清单由编排层本轮生成，rubric 声明由编排层持有，重复票要聚合后才看得出来。这些判定不归 Provider 自检，修复方也常是编排层自己。写进接入方契约，会让接入方为自己无法控制的失败码去自助修复。

定案口径（2026-11-09，用户确认）：保留 `E_VOTE_*` 作为编排层局部原因码，不进准入契约、不参与 `contractVersion`、不触发重新准入，也不写进 `AdmissionReport`。编排层拒绝只进 `rejections` 与 `notices`，不计 Provider 违规、不加熔断计数。

挂起项已销项，2026-11-09 用户决定单列一节，见《Agent 运行时架构设计》「编排层局部原因码」。三条码仍不进任何准入契约。

## 落地前置

2026-11-09 已补齐 P0 基座：`AgentProvider`、`AgentRegistry`、`ContractValidator`、`HttpAgentAdapter` 和 `AdmissionController` 落在 `application/agent/spi/`，`/api/agent/capabilities`、`provider-contract.json` 和 gold cases 也已存在。同日又落地了 L1 编排层骨架：`AgentOrchestrator` 与 `POST /api/agent/invoke` 已接通注册表，按 intent 逐轮 invoke、响应过契约校验、失败切兜底。

但骨架只做**单轮路由**，不生成候选清单、不收评分票、不跑计票；`INVOKING` 阶段的两轮协议仍未接线。更关键的缺口是评委：用户明确后续由购票、停车推荐等功能 Agent 担任评委，这些 Agent 还没实现，现在硬造评委 Provider 只会得到没有独立视角的假票。因此本轮不启用范式 B，也不把三条 `E_VOTE_*` 局部码搬进 Java 运行时。

原型是离线纯函数，不接入 Java 运行时，也不是 `AgentProvider` 实现，不参与构建。它只证明计票规则可复核，不能当成运行时已可用的证据。

### 实现状态（2026-11-09 晚）

用户决定不再等购票 / 停车推荐独立成「评委 Agent」，而是先把范式 B 跑通：本轮直接用具备独立评分视角的三位评委 Bean 接进投票，等后续功能 Agent 就位再扩展。**状态：已落地，门禁全绿**。

落地的代码（均在 `travel-server/src/main/java/.../application/agent/vote/`）：

- `PlanVoteTally`：确定性计票器，等价移植 `plan_vote.py` 的 22 条规则（VETO 硬剔除、弃票不进分母、加权、三步平局、同构检测、三条 `E_VOTE_*` 只进 `rejections`/`notices`）。Java 侧 `PlanVoteTallyTest` 22 例全过。
- `JudgeProviderConfiguration`：三位评委 Bean `judge.ticket` / `judge.food` / `judge.parking`，各自声明独立 `Rubric`（评分项 + 权重 + 否决权），复用现有 `DECISION_ADVICE` 能力，不新增 capability 枚举。
- `VoteOrchestrator`：两轮编排层。第一轮按 `hints.votePhase=COLLECT` 征集事实并记录 `providerId → ownFactIds`；候选方案「模型优先 + 规则兜底」，模型输出必须引用真实 `factId`，任何编造 / 格式错误 / 无密钥 / 调用失败自动降级为确定性规则草案；第二轮 `SCORE` 一次性收齐评分票后交给 `PlanVoteTally`。评委响应统一过 `ContractValidator`，违规或异常只记编排层 notice，不改注册表状态、不计 Provider 违规。刻意不改动 `AgentOrchestrator`，保护既有单轮测试。
- `AgentVoteController`：`POST /api/agent/vote`，路径落在 `/api/agent/**` 自动继承登录鉴权（匿名 401），信封脱敏不含 endpoint / authRef / 凭据。

触发条件里担心的「硬造评委 Provider 得到假票」在本轮被绕开：评委用确定性规则基于公开行程事实打分，并在 `notices` 如实标注「停车视角暂无专属数据源，未编造停车 POI」；票数只用 `CONTENT` + `value.category=PLAN_SCORE`，不动 `Fact.type` 枚举，契约仍是 1.0。

本轮未做（仍属上方「待决」）：评委权重写入注册配置或按用户偏好动态给、否决范围扩展到忌口 / 体力之外、推荐结果落 V3 候选看板由用户再投人票。这些等真实功能 Agent 接入后再定，不阻塞当前闭环。

## 复核命令

```bash
cd F:/repository/Travel-Assistant-Agent
PYTHONPATH="F:/pythons/pkgs" \
  "C:/Users/zipei/.workbuddy/binaries/python/versions/3.13.12/python.exe" \
  tools/agent/plan_vote.py
```

自测全过时退出码为 0，输出里有 `-- 22/22 通过 --`。
