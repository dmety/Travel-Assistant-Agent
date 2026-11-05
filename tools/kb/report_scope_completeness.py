"""Generate reproducible KB scope and completeness reports without changing data."""
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

from tools.build_attraction_index import normalize

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'reports' / 'current'
FIELDS = {
    'ticket': '票价', 'open_time': '开放时间', 'address': '有效地址',
    'coordinates': '坐标及坐标系', 'tel': '电话', 'website': '网站',
    'level': '等级', 'rating': '评分', 'image': '图片', 'description': '介绍',
}


def city_key(row):
    return (row.get('city') or '').strip().removesuffix('市') or '未标城市'


def present(row):
    flags = {k: bool(row.get(k)) for k in FIELDS}
    try:
        flags['coordinates'] = (-90 <= float(row['lat']) <= 90
                                and -180 <= float(row['lon']) <= 180
                                and row.get('coordinateSystem') in ('WGS-84', 'GCJ-02'))
    except (KeyError, TypeError, ValueError):
        flags['coordinates'] = False
    return flags


def write_csv(name, rows, headers):
    with (OUT / name).open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=headers)
        writer.writeheader()
        writer.writerows(rows)


def main():
    source = ROOT / 'data/china_attractions.json'
    payload = source.read_bytes()
    raw = json.loads(payload)['attractions']
    rows = []
    for item in raw:
        cleaned = normalize(item)
        if cleaned:
            row = {**item, **cleaned}
            row['_flags'] = present(row)
            row['_score'] = sum(row['_flags'].values())
            rows.append(row)
    counts = Counter(city_key(r) for r in rows)
    cities = sorted(counts, key=lambda c: (-counts[c], c))
    top3 = cities[:3]
    both = [r for r in rows if r['_flags']['ticket'] and r['_flags']['open_time']]
    ranked = sorted(both, key=lambda r: (-r['_score'], city_key(r), r['name'], r['id']))
    OUT.mkdir(parents=True, exist_ok=True)
    city_rows = []
    for i, city in enumerate(cities, 1):
        subset = [r for r in rows if city_key(r) == city]
        city_rows.append({'排名': i, '城市': city, '记录数': len(subset),
                          '名称去空白后种类数': len({''.join(r['name'].split()) for r in subset}),
                          '有票价': sum(r['_flags']['ticket'] for r in subset),
                          '有开放时间': sum(r['_flags']['open_time'] for r in subset),
                          '两项都有': sum(r['_flags']['ticket'] and r['_flags']['open_time'] for r in subset),
                          '重点核验': '是' if city in top3 else '否'})
    write_csv('全国城市数量排名.csv', city_rows, list(city_rows[0]))
    export = []
    for i, r in enumerate(ranked, 1):
        export.append({'排名': i, '景点ID': r['id'], '景点': r['name'], '城市': city_key(r),
                       '重点城市': '是' if city_key(r) in top3 else '否', '完整度分数（10分）': r['_score'],
                       '缺失字段': '、'.join(FIELDS[k] for k, ok in r['_flags'].items() if not ok),
                       **{label: (f"{r.get('lat')},{r.get('lon')} ({r.get('coordinateSystem')})" if k == 'coordinates' else r.get(k) or '') for k, label in FIELDS.items()},
                       '票价状态': r.get('ticket_status', ''), '开放时间状态': r.get('open_time_status', ''),
                       '票价字段来源': r.get('ticket_source', ''), '票价核查日期': r.get('ticket_checked_at', ''),
                       '开放时间字段来源': r.get('open_time_source', ''), '开放时间核查日期': r.get('open_time_checked_at', ''),
                       '数据来源': r.get('source', ''),
                       '身份风险': r.get('merge_review') or r.get('same_name_risk') or ''})
    headers = list(export[0])
    write_csv('全国票价与开放时间齐全景点.csv', export, headers)
    write_csv('前三城市优先核验景点.csv', [r for r in export if r['重点城市'] == '是'], headers)
    queue = []
    for r in rows:
        if city_key(r) in top3:
            queue.append({'城市': city_key(r), '景点ID': r['id'], '景点': r['name'],
                          '票价': r.get('ticket') or '', '开放时间': r.get('open_time') or '',
                          '票价状态': r.get('ticket_status'), '开放时间状态': r.get('open_time_status'),
                          '票价字段来源': r.get('ticket_source', ''), '开放时间字段来源': r.get('open_time_source', ''),
                          '核验结论': '待核验', '核验来源URL': '', '核验日期': '', '备注': ''})
    write_csv('前三城市全量核验任务.csv', queue, list(queue[0]))
    def safe(v):
        return str(v).replace('|', '／').replace('\n', ' ')
    def table(items):
        lines = ['| 景点 | 城市 | 完整度 | 票价（库内原文） | 开放时间（库内原文） |', '|---|---|---|---|---|']
        for r in items:
            lines.append('| ' + ' | '.join(safe(r[k]) for k in ['景点', '城市', '完整度分数（10分）', '票价', '开放时间']) + ' |')
        return '\n'.join(lines)
    max_score = max(r['_score'] for r in ranked)
    best = [r for r in export if r['完整度分数（10分）'] == max_score]
    stats = {'records': len(rows), 'normalized_cities': len(cities), 'top3': city_rows[:3],
             'with_both': len(both), 'maximum_score': max_score, 'maximum_score_count': len(best),
             'score_counts_with_both': dict(Counter(r['_score'] for r in both)),
             'ticket_verified': sum(r.get('ticket_status') == 'verified' for r in rows),
             'open_time_verified': sum(r.get('open_time_status') == 'verified' for r in rows),
             'source_sha256': hashlib.sha256(payload).hexdigest()}
    (OUT / '统计摘要.json').write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding='utf-8')
    report = f'''# 知识库范围与信息完整度清单

统计日期：2026-11-05。统计本地快照，未对票价和开放时间进行本轮联网复核。
源文件：data/china_attractions.json；SHA256：{stats['source_sha256']}。

## 已确定范围

保留全国检索，不删除任何城市或记录。重点核验城市按库内城市记录数降序选择前三名：

| 城市 | 记录数 | 名称种类数 | 有票价 | 有开放时间 | 两项都有 |
|---|---:|---:|---:|---:|---:|
''' + '\n'.join('| ' + ' | '.join(str(r[k]) for k in ['城市', '记录数', '名称去空白后种类数', '有票价', '有开放时间', '两项都有']) + ' |' for r in city_rows[:3]) + f'''

全国共 {len(rows)} 条记录，城市字段归一后 {len(cities)} 个城市标签。只合并城市名末尾“市”，不合并景点记录；记录数含同名及待判重记录，不能等同于独立景区数，也不代表覆盖全国所有城市。

## 完整度口径

先使用现有索引构建器清理占位值、“其他”等级和城市拼接伪地址。
先筛选票价与开放时间两项都有值的记录，再按票价、开放时间、有效地址、坐标及坐标系、电话、网站、等级、评分、图片、介绍十项各一分排序。
来源、ID用于追溯，不加分；评分、图片只衡量填充情况，不证明真实性；网站字段未在本轮确认为官网。
坐标仅检查范围和坐标系标签，不证明落点正确。介绍只查非空，不代表无重复或内容正确。排名同分时按城市、名称和ID排列，不代表推荐次序。

两项都有值共 {len(both)} 条；最高 {max_score}/10 分，共 {len(best)} 条。当前票价 verified 为 {stats['ticket_verified']} 条，开放时间 verified 为 {stats['open_time_verified']} 条。因此以下均为“信息较全候选”，不能称“全部核验通过”。票价文本可能是收费规则而非明确成人价，开放时间也可能为概括描述。

## 全国最高完整度景点（全部并列）

''' + table(best) + '''

## 前三城市优先核验候选（各城市前10条）

''' + '\n\n'.join('### ' + city + '\n\n' + table([r for r in export if r['城市'] == city][:10]) for city in top3) + '''

## 单独交付清单

- [全国城市数量排名](全国城市数量排名.csv)
- [全国票价与开放时间齐全景点：全部条目，按完整度排序](全国票价与开放时间齐全景点.csv)
- [前三城市优先核验景点：两项都有值](前三城市优先核验景点.csv)
- [前三城市全量核验任务：含缺失项，结论留待核验](前三城市全量核验任务.csv)

## 后续核验标准

先从每城市完整度较高的10条开始核验，再扩展到全量任务。逐条确认景点身份、官方页面或公告、票价适用票种/人群/季节/收费例外、开放日期/闭馆日/停止入园时间、地址与坐标系、预约和购票入口。记录字段级来源URL与查询日期。
来源无法覆盖当前值、来源冲突、官方未公示时保留未知或unverified并写明原因；不得根据其他公园或泛化条例推定具体景点价格与时间。只有完成字段级证据审查后才改为verified。
本次只确定范围和生成清单，不改源库、索引或核验状态。重跑：`python -m tools.kb.report_scope_completeness`。
'''
    (OUT / '知识库范围与最全景点清单.md').write_text(report, encoding='utf-8')
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
