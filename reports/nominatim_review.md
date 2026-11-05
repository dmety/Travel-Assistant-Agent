# 2026-11-04 坐标来源核验

本次针对 15 条 `coordinateSystem=mixed`、主 ID 为 Amap 且缺 OSM 来源 ID 的记录，按名称+城市+省份向 Nominatim 发起一次性单线程检索，每条间隔至少 1.1 秒。原始响应缓存在 `reports/nominatim-review-2026-11-04.json`。坐标距离使用项目 GCJ-02→WGS-84 逆转换，再计算 haversine；仅接受名称一致（或明确校区后缀）、候选唯一且距离 <1 km 的结果。

| Amap ID | Nominatim OSM ID | 距离 | 处置 |
|---|---|---:|---|
| `amap-B01C301198` 黑龙江职业学院 | `way/705753731` | 34 m | 已补 `source_ids`，坐标标为 GCJ-02 |
| `amap-B0FFKQ4KWA` 中国科学院沈阳自动化研究所 | `way/1454939507` | 69 m | 同名有远处候选；仅近点通过阈值，已补来源 ID |
| `amap-B013704B02` 水上公园 | `way/158030760` | 131 m | 同名有远处候选；仅近点通过阈值，已补来源 ID |
| `amap-B0FFGJR069` 内蒙古医科大学 | `way/500514715` | 2 m | OSM 名称为“内蒙古医科大学(金山校区)”，另一同名候选距约 21 km；已补来源 ID |

对上述 11 条剩余记录，随后按坐标逐条使用 Nominatim reverse 核验，缓存见 `reports/nominatim-reverse-review-2026-11-04.json`。其中 2 条满足名称/历史名称、城市省份、大学要素类型和近距离条件，已补来源 ID；另 9 条仍未匹配：返回道路、停车场、其他小型场所或要素名称/层级不吻合，继续保留 `mixed`。

为复核这 9 条是否遗漏了同名 OSM 对象，又对 2026-11-04 下载并校验过 MD5 的 Geofabrik 中国 PBF 做离线精确名称扫描：检查节点坐标，以及同名 way 的边界节点距离（Amap 坐标先逆转为 WGS-84）。只发现 1 个 10 km 内同名节点，是“庐州公园”公交站（OSM node 8010626477，829 m），不属于公园；另有 4 个同名 way 最近边界节点均大于 1.6 km（哈尔滨工业大学附属中学校 9.7 km、金海湾滨江公园 1.7 km、石人沟约 3,180 km、内蒙古警察学院 34.5 km）。因此没有对象同时通过名称、要素类型和近距离判据，不补 ID。扫描证据见 [`mixed-osm-name-candidates-2026-11-04.json`](mixed-osm-name-candidates-2026-11-04.json) 与 [`mixed-osm-way-candidates-2026-11-04.json`](mixed-osm-way-candidates-2026-11-04.json)，可复跑脚本为 `tools/kb/scan_mixed_osm_name_candidates.py` 和 `tools/kb/scan_mixed_osm_way_candidates.py`。

| Amap ID | Amap 名称 | OSM 对象 | 距离 | 复核依据 |
|---|---|---|---:|---|
| `amap-B03670VA8J` | 滇池学院 | `way/248183776` 云南大学滇池学院 | 38 m | 昆明市/云南省，大学要素 |
| `amap-B036702A40` | 昆明医科大学海源学院 | `way/248184021` 昆明医学院海源学院 | 18 m | 昆明市/云南省，大学要素；OSM 使用历史校名 |

此前 Nominatim 名称搜索返回的其余 11 条候选仍不采纳：例如“庐州公园”有 1.36 km 的公园结果和 0.83 km 的同名公交站，公交站不是景点匹配；“石人沟”返回的多为“石人子沟”行政区、村庄或道路。reverse 结果同样未出现足以改变这些判定的同一景点证据。

复核脚本：`tools/kb/reconcile_nominatim_review.py`。它只从本地缓存复核名称、行政区、要素类型、距离和白名单 OSM ID 后写入；6 条已关联 OSM，另 9 条无可信 OSM 对象，因此仍缺 OSM 关联。后续检查发现这 9 条仅有 Amap 主来源 ID，且代码 `tools/kb/amap_source.py::from_amap` 将 Amap 坐标标为 GCJ-02；高德官方也确认开放平台服务使用 GCJ-02。故它们现已由 `mixed` 改标为 GCJ-02，但不代表找到 OSM 对应物。转换记录见 [`amap-primary-crs-resolution-2026-11-04.json`](amap-primary-crs-resolution-2026-11-04.json)。当前质量报告应显示 `mixed=0`，缺失 OSM ID 0，来源元数据不一致 0；2,172 条明确双源记录仍有 249 条缺 Amap 原始 ID。后续 OSM way 几何审查另将 1,861 个唯一对象 ID 加入 Amap 主记录，证据见 [`amap-osm-approved-way-links-2026-11-04.json`](amap-osm-approved-way-links-2026-11-04.json)。OSM 关联只表示来源可追溯，不证明两个数据源的全部字段相同。

## OSM 使用说明

本次仅用公共 Nominatim 做 15 次名称搜索及 11 次逐点 reverse 人工审计型请求，不是线上服务：请求单线程、间隔至少 1.1 秒，并保留本地缓存。Nominatim 公共实例要求最多 1 请求/秒、可识别 User-Agent，并对小规模一次性批处理要求单线程和缓存；规则可能变更，参见[官方使用政策](https://operations.osmfoundation.org/policies/nominatim/)。

六个已关联记录及仓库既有 OSM 数据需要在实际分发/展示时遵守 OSM 署名和 ODbL 条款；参见[OpenStreetMap 版权与许可说明](https://www.openstreetmap.org/copyright)。本次来源 ID 仅表示可追溯关联，不表示 OSM 对 Amap 票价、开放时间、评分或其他字段背书。

## 本地历史追溯

检查了 Git 中全部 6 个已追踪知识库快照：249 条 OSM 主记录缺失的 Amap 原始 ID 在旧快照中也不存在；当前 236 条未标记风险/待审且坐标明确的记录，与 Amap-only 记录按规范名、省、市及统一坐标后 1 km 范围比对，没有唯一候选；其余 13 条因已有风险/待审标记而排除。原 9 条无 OSM 关联的 Amap 主记录在历史快照中也没有同省同市同名 OSM 行；其坐标系现已确认是 GCJ-02。故 249 条缺失 Amap ID 和 9 条 OSM 关联目前均无可核实的本地历史备份可恢复。
