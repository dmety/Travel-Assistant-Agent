# 知识库六项问题推进结果（2026-11-04）

> **时效提示：** 本报告下方原始表格记录的是较早阶段，部分汇总现已过时；请优先看紧随其后的“当前六项数据快照”。截至本次更新，全库票价缺失 11,080 条、非空未核验 45 条；开放时间缺失 7,225 条、非空未核验 123 条；坐标系 WGS-84 4,069、mixed 0、GCJ-02 7,789。湖北票价与开放时间进度见 [`hubei-ticket-hours-progress-2026-11-04.md`](hubei-ticket-hours-progress-2026-11-04.md)。

范围：当前 `data/china_attractions.json` 快照（11,858 条），对照项目经理截图中的六项问题。数字由 `python -m tools.kb.quality_report` 只读重算。此报告区分了代码防护与数据补齐，不能把前者当作后者。

## 当前六项数据快照

以下指标在本报告更新时由当前快照重算。下方原有表格记录的是先前工作阶段的结果，其中部分数字和结论已过时。

| # | 当前指标 | 当前结果 |
|---|---|---|
| 1 票价缺失/误判免费 | 缺失 11,080；非空未核验 45；非空无行级来源 752 | 湖北省 394 条中，45 条有票价、349 条仍缺；26 条有来源、19 条旧值无来源并已降为未核验 |
| 2 开放时间缺失 | 缺失 7,225；非空未核验 123；非空无行级来源 4,628 | 湖北省 394 条中，123 条有开放时间、271 条仍缺；5 条有来源、118 条旧值无来源并已降为未核验 |
| 3 城市简介重复 | 景点行内 `city_intro` 0；独立城市键 66；缺失/孤立键 0；重复描述行 0 | 本轮从 11,858 条景点记录中迁出重复城市简介，按省份与城市存入根级 `city_introductions` |
| 4 占位值与状态 | 占位值 0；票价/开放时间状态不一致 0 | 当前快照符合这两项计数 |
| 5 坐标系混用 | WGS-84 4,069；mixed 0；GCJ-02 7,789；缺失/未知/无效坐标系均为 0 | 264 条 mixed 按主记录 ID 的坐标来源校正：OSM 主记录设为 WGS-84，Amap 主记录设为 GCJ-02；未转换坐标值 |
| 6 同名跨源匹配 | 同名组 106；无风险/复核标记组 0；来源合并行 2,166；缺 OSM ID 0、缺 Amap ID 249；来源元数据不一致 0 | 80 组无标记同名组现均加人工复核标记；恢复 1,917 条有审核证据的来源关联后，仍有 249 条缺次级 Amap ID |

城市简介迁移脚本为 [`tools/kb/migrate_city_introductions.py`](../tools/kb/migrate_city_introductions.py)，查询层新增 `city_introduction(doc, city, province=None)`。坐标与同名元数据修复脚本为 [`tools/kb/repair_source_coordinate_and_name_metadata.py`](../tools/kb/repair_source_coordinate_and_name_metadata.py)；它只按主记录 ID 补主来源 ID 和坐标系，并将未标记的重复同名组转为人工复核，不猜测 249 条缺失的次级 Amap ID。

| # | 问题 | 当前结果 | 结论 |
|---|---|---|---|
| 1 | 票价缺失/误判为免费 | 未知费用按 `pending` 处理，查询层不会当作免费，也不会把票价文本直接求和；“收费但金额未标注”另列为 `unknown_cost`。仍有 10,956 条票价缺失；902 条非空值未核验，其中 752 条无行级来源；已核验票价为 0 条。重庆、湖南、北京官方来源补入 24 条，OSM 标签目前补入 126 条，其中 32 条明确收费但未标金额。 | **防误判已做，累计 150 条有行级来源的未核验票价提示/文本；仍有大量缺失。** |
| 2 | 开放时间缺失 | 缺失按 `pending` 处理，查询层明确不承诺当前营业、预约或准入。仍有 7,064 条缺失；4,794 条非空值未核验，其中 4,628 条无行级来源；已核验开放时间为 0 条。OSM `opening_hours` 标签补入 163 条，北京官方详情页补入 3 条。 | **防误判已做，166 条时间文本有行级来源但未核验；仍有大量缺失。** |
| 3 | 城市简介重复写入景点描述 | 城市介绍已独立存放；当前城市键 66 个、缺失城市简介键 0 个，景点记录内城市简介字段 0 条，重复描述记录 0 条。 | **当前快照已清理。** |
| 4 | 用“暂无”等占位文本代替缺失值 | 占位文本计数 0；票价状态不一致 0；开放时间状态不一致 0。 | **当前快照已规范化。** |
| 5 | WGS-84 与 GCJ-02 混用 | 周边查询会转换已知坐标系，跳过 `mixed`。当前 WGS-84 4,069 条、GCJ-02 7,789 条、`mixed` 0；缺失/未知坐标系均为 0。15 条原混合记录中 6 条经 OSM 名称/逐点核验补了 OSM ID；其余 9 条虽然没有可信 OSM 对象，但其唯一来源 ID 为 Amap，生成器和高德官方文档确认其坐标为 GCJ-02，现已据证据修正。另 56 条高德主记录通过精确名称、唯一 OSM 节点和 25 米距离条件补回来源 ID。 | **坐标系歧义已清零；跨源匹配仍有缺失 ID，见第 6 项。** |
| 6 | 跨省同名仅按归一化名称匹配 | 自动合并要求唯一候选、同城、坐标可统一、距离阈值及来源 ID；有同名风险/待审标记时禁止自动合并。107 组同省同名候选均有风险或待审标记；仍有 16 条、7 组待人工复核。当前 2,172 条明确双源记录中，249 条缺 Amap ID；缺 OSM ID 和来源元数据矛盾均已清零。 | **误合并防护、来源一致性和 1,861 条 way 来源关联已做；249 条 Amap ID 缺失及人工复核仍未清零。** |

## 未完成项与约束

- 未配置或调用 Amap API；用户明确要求暂不配置密钥。当前无法用高德来源补票价、开放时间或恢复 Amap 原始 POI ID。
- Git 历史也无法补足票价、开放时间溯源：提交 `1cc4e86` 有 752 条非空票价但没有 `ticket_source`/核验时间；提交 `099b343` 有 4,628 条非空开放时间但没有 `open_time_source`/核验时间。当前数据处理已将这些值降为 `unverified`，没有沿用缺少证据的旧 `verified` 状态。
- 重庆市文化和旅游发展委员会公开的 A 级景区名录（页面注明数据截至 2025 年 12 月）与库内 6 条记录精确匹配；其中 4 条原来缺票价，现已补入收费/免费及旺淡季票价文本，并记录来源 URL 和本次取数日期。由于源数据距今约 11 个月，状态仍为 `unverified`。另外 2 条本来已有票价：重庆动物园与名录相符；湖广会馆名录为 25/25，而库内记录为 32/16 并注明执行日期 2025-04-21，存在冲突，未覆盖任何一方，待用当前区级价格文件复核。提取证据见 [`chongqing-official-ticket-source-2026-11-04.json`](chongqing-official-ticket-source-2026-11-04.json)，导入脚本为 [`tools/kb/apply_chongqing_ticket_snapshot.py`](../tools/kb/apply_chongqing_ticket_snapshot.py)。
- 检查了本地快照与 Git 中的历史快照，无法恢复缺失的跨源 POI ID；没有据名称或坐标猜测 ID。
- 从 Geofabrik 下载了中国 OSM PBF（HTTP 最后修改时间 2026-11-04，1,623,010,602 字节），MD5 与服务器校验值一致。扩展来源关联后按 KB 中已关联的 5,996 个 OSM ID 扫描，370 个对象含 `fee`/`charge`/`opening_hours` 标签。只对空值记录按精确对象 ID 回填：当前 OSM 来源票价/收费提示 126 条（其中 32 条明确收费但没有金额），开放时间 163 条；所有字段均标为 `unverified` 并附对象 URL。本轮新增 24 条票价/收费提示和 4 条开放时间。原始提取证据在 [`osm-attraction-tag-import-2026-11-04.json`](osm-attraction-tag-import-2026-11-04.json)，回填脚本为 [`tools/kb/apply_osm_attraction_tags.py`](../tools/kb/apply_osm_attraction_tags.py)，现默认预览，需显式 `--apply` 才写库。
- 后续 OSM 基底重采集脚本现已保留 `opening_hours` 和 `fee`/`charge` 标签；金额未知标签会进入费用未知列表。OSM 标签本身不是景区官方时刻/价格保证，因此没有标成已核验。
- 湖南省发改委《政府管价景区目录（2025年）》列出 94 个景区价格；按省份、唯一景区名称和城市逐条匹配后，补入 5 条原先空缺票价。目录数据日期为 2025-04-03，因此仍标为 `unverified`；4 条已有值但价格口径不一致的记录保留原值并列为冲突，不覆盖。证据和导入脚本见 [`hunan-official-ticket-source-2026-11-04.json`](hunan-official-ticket-source-2026-11-04.json) 与 [`tools/kb/apply_hunan_ticket_snapshot.py`](../tools/kb/apply_hunan_ticket_snapshot.py)。
- 北京市文旅局 2026-07-30 的 A 级景区链接清单中，46 项按唯一名称/后缀规范化精确对应知识库记录；详情页可公开读取票价和开放时间文本。对 15 条空票价和 3 条空开放时间逐项抓取并写入，未覆盖原有字段，全部标为 `unverified`。原页面详情包含季节、优惠和预约信息，保留了来源原文。匹配表和详情快照见 [`beijing-official-list-match-2026-11-04.json`](beijing-official-list-match-2026-11-04.json) 与 [`beijing-official-detail-snapshot-2026-11-04.json`](beijing-official-detail-snapshot-2026-11-04.json)，导入脚本为 [`tools/kb/apply_beijing_detail_snapshot.py`](../tools/kb/apply_beijing_detail_snapshot.py)。
- 对原剩余 9 条 `mixed` 记录，以当前 Geofabrik PBF 做了精确名称节点与 way 边界离线扫描。唯一近距离节点为 829 m 外同名公交站；4 个同名 way 最近边界距离均大于 1.6 km，未找到可安全恢复来源 ID 的对象。后续根据其唯一 Amap 来源 ID、`from_amap()` 的 GCJ-02 标注逻辑及高德官方坐标说明，将这 9 条坐标系从 `mixed` 修正为 GCJ-02，仍不补造 OSM ID。证据见 [`mixed-osm-name-candidates-2026-11-04.json`](mixed-osm-name-candidates-2026-11-04.json)、[`mixed-osm-way-candidates-2026-11-04.json`](mixed-osm-way-candidates-2026-11-04.json) 和 [`amap-primary-crs-resolution-2026-11-04.json`](amap-primary-crs-resolution-2026-11-04.json)。
- 对未关联 OSM 的 Amap 主记录做全中国 PBF 精确名称节点扫描，找到 284 个 100 m 内候选；再要求该记录 100 m 内只有一个候选、距离不超过 25 m、无风险标记、OSM ID 未被其他行占用、行政标签不冲突且要素类型在白名单内，最终补回 56 个来源关联。Amap ID 和 GCJ-02 坐标保持为主。候选、复核与已写入记录见 [`amap-osm-node-candidates-2026-11-04.json`](amap-osm-node-candidates-2026-11-04.json)、[`amap-osm-node-candidate-review-2026-11-04.json`](amap-osm-node-candidate-review-2026-11-04.json)、[`amap-osm-approved-node-links-2026-11-04.json`](amap-osm-approved-node-links-2026-11-04.json)；应用脚本为 [`tools/kb/apply_reviewed_osm_node_candidates.py`](../tools/kb/apply_reviewed_osm_node_candidates.py)。新增 ID 中有 2 条收费/免费标注和 1 条开放时间标签可补，均保持 `unverified`。
- 后续扩展到 OSM way 面/线几何：7,726 条符合条件的 Amap 主记录中，找出 5,936 个精确名称 way；以转换后的 Amap 点到闭合多边形内部/边界或线段的距离筛选，进一步要求 25 米内唯一对象、KB 全库名称唯一、无风险标记、要素类别白名单、行政标签不冲突且来源 ID 未被占用，最终写入 1,861 条 way 来源 ID。数据源 ID 总重复仍为 0。候选、复核、写入和扫描脚本见 [`amap-osm-way-candidates-2026-11-04.json`](amap-osm-way-candidates-2026-11-04.json)、[`amap-osm-way-candidate-review-2026-11-04.json`](amap-osm-way-candidate-review-2026-11-04.json)、[`amap-osm-approved-way-links-2026-11-04.json`](amap-osm-approved-way-links-2026-11-04.json) 和 [`tools/kb/scan_amap_osm_way_candidates.py`](../tools/kb/scan_amap_osm_way_candidates.py)。新增对象的标签提取后实际补入 24 条票价/收费提示及 4 条开放时间，均为未核验。
- 另找到北京公共数据平台 218 条景区票价数据、温州公共数据平台 91 条含开放时间/票价的景区数据；两者虽标注开放数据，在线接口说明仍要求注册 `userKey` 或申请 `appsecret`。本轮未创建账号或获取凭据，因此没有调用受控接口。
- 温州数据集公开预览界面可读取 10 条样例，但与当前 KB 没有精确名称匹配；预览字段为“平均价格”而非“门票价格”，不能把 0 平均价推断为免费。完整 91 条数据接口需要注册应用并获批 `appsecret`，因此没有尝试绕过。样例、列名与匹配结果保存在 [`wenzhou-public-preview-2026-11-04.json`](wenzhou-public-preview-2026-11-04.json)。
- 本轮另行检索了全国可复用的票价/开放时间数据集：CnOpenData 有全国范围和两类字段，但完整下载/API 为商业授权；去哪儿网抓取社区集为约两年前快照且复用许可不清；约 1.2 万条的 2022 景区集需登录/积分，页面未说明数据授权；深圳官方开放平台另列 50 条票价 CSV、更新时间 2023-12，但本机直接读取返回 HTTP 403，未取得文件。上述均未写入，以免把不明授权、过时价格或无法核对的记录当作当前事实。资源逐项审计见 [`ticket-hours-public-resource-audit-2026-11-04.json`](ticket-hours-public-resource-audit-2026-11-04.json)。
- Java Agent 还未接入这些 Python 查询辅助和防护；当前结论只覆盖离线知识库与 Python 工具。

## 复核命令

```powershell
python -m tools.kb.quality_report
python -m tools.kb.quality_report --strict
```

当前严格模式仅因 `merged_rows_missing_amap_id=249` 失败；缺 OSM ID、重复 ID、来源元数据矛盾及混合坐标标记均为 0。两份审核证据共有 1,917 条逐项 ID/名称匹配，已通过 [`tools/kb/restore_reviewed_amap_osm_links.py`](../tools/kb/restore_reviewed_amap_osm_links.py) 恢复；因此当前双源记录为 2,166 条，仍有 249 条 OSM 主记录缺少 Amap ID。早期审计记录及坐标复核证据见 [`stale-source-label-repair-2026-11-04.json`](stale-source-label-repair-2026-11-04.json)、[`amap-primary-crs-resolution-2026-11-04.json`](amap-primary-crs-resolution-2026-11-04.json) 与 [`nominatim_review.md`](nominatim_review.md)。
