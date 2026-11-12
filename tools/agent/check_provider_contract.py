#!/usr/bin/env python3
"""G0 静态准入闸门：离线、零依赖，读同一份 provider-contract.json。

用法：
    python -B tools/agent/check_provider_contract.py                 # 校验种子与运行态配置
    python -B tools/agent/check_provider_contract.py data/x.json     # 只校验指定文件
    python -B tools/agent/check_provider_contract.py --self-test     # 跑内置规则用例
    python -B tools/agent/check_provider_contract.py --json          # 输出完整报告

判定口径见《Agent 接入规范》「规则集」A 组 M-01 至 M-12。G0 是纯离线闸门，
不发网络请求；endpoint 的 DNS 解析与 SSRF 探测属于 G2，不在这里做。

提交态（*.example.json 与显式 --strict）要求 enabled=false；运行态配置里
G4 已人工确认的 Provider 允许 enabled=true，故默认按文件名区分两种口径。
"""
from __future__ import annotations

import argparse
import copy
import ipaddress
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = Path(__file__).with_name("provider-contract.json")

ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z0-9-]+){1,3}$")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
AUTHREF_RE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
SCHEMA_RE = re.compile(r"^1\.[0-9]+$")
CREDENTIAL_KEY_RE = re.compile(
    r"token|api[_-]?key|secret|password|passwd|pwd|credential|authorization|access[_-]?key|private[_-]?key",
    re.IGNORECASE,
)
LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1", "169.254.169.254", "metadata.google.internal"}


def load_contract(path: Path = CONTRACT_PATH) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def _failure(contract: dict, rule: str, field: str, actual, expected: str,
             hint: str = "", level: str = "BLOCK") -> dict:
    code = ""
    for item in contract["rules"]:
        if item["id"] == rule:
            code = item.get("code", "")
            level = item.get("level", level)
            break
    return {
        "code": code,
        "rule": rule,
        "level": level,
        "field": field,
        "actual": actual if isinstance(actual, (str, int, float, bool, type(None))) else str(actual),
        "expected": expected,
        "hint": hint,
    }


def _inline_credential_fields(node, prefix: str = "") -> list[str]:
    """找出配置里任何层级的内联凭据字段名，M-09 不允许出现。"""
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            if CREDENTIAL_KEY_RE.search(str(key)):
                found.append(path)
            found.extend(_inline_credential_fields(value, path))
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found.extend(_inline_credential_fields(value, f"{prefix}[{i}]"))
    return found


def _check_endpoint(endpoint, contract: dict, field: str) -> list[dict]:
    allowlist = contract["runtime"]["endpointAllowlist"]
    if not isinstance(endpoint, str) or not endpoint.strip():
        return [_failure(contract, "M-08", field, endpoint, "非空 https URL")]
    raw = endpoint.strip()
    try:
        parts = urlsplit(raw)
    except ValueError as exc:
        return [_failure(contract, "M-08", field, raw, "可解析的 https URL", str(exc))]
    problems = []
    if parts.scheme != "https":
        problems.append(_failure(contract, "M-08", field, parts.scheme, "https"))
    if parts.username or parts.password:
        problems.append(_failure(contract, "M-08", field, "含 userinfo", "不含 userinfo"))
    if parts.query or parts.fragment:
        problems.append(_failure(contract, "M-08", field, "含 query 或 fragment", "不含 query / fragment"))
    host = (parts.hostname or "").lower()
    if not host:
        problems.append(_failure(contract, "M-08", field, raw, "含合法 host"))
    else:
        try:
            ipaddress.ip_address(host)
            problems.append(_failure(contract, "M-08", field, host, "域名，不接受 IP 字面量"))
        except ValueError:
            pass
        if host in LOOPBACK_HOSTS or host.endswith((".local", ".internal", ".localdomain")):
            problems.append(_failure(contract, "M-08", field, host, "公网域名，不接受环回 / 内网"))
        if host not in allowlist:
            problems.append(_failure(contract, "M-08", field, host,
                                     "host 在 endpointAllowlist 内：" + ", ".join(allowlist)))
        if parts.port is not None:
            problems.append(_failure(contract, "M-08", field, str(parts.port), "不显式指定端口"))
    return problems


def validate_provider(provider: dict, index: int, contract: dict,
                      strict_enabled: bool) -> dict:
    p = f"providers[{index}]"
    failures: list[dict] = []
    warnings: list[dict] = []
    enums = contract["enums"]
    limits = contract["limits"]
    runtime = contract["runtime"]

    provider_id = provider.get("id")
    # M-01 / M-02
    if not isinstance(provider_id, str) or not ID_RE.match(provider_id):
        failures.append(_failure(contract, "M-01", f"{p}.id", provider_id,
                                 r"^[a-z][a-z0-9]*(\.[a-z0-9-]+){1,3}$"))
    elif any(provider_id.startswith(prefix) for prefix in runtime["reservedPrefixes"]):
        failures.append(_failure(contract, "M-02", f"{p}.id", provider_id,
                                 "不得使用保留前缀 builtin. / system."))
    # M-03
    version = provider.get("version")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        failures.append(_failure(contract, "M-03", f"{p}.version", version, "语义化 x.y.z"))
    # M-04：顶层与 provider 各自都要匹配规范主版本
    schema = provider.get("schemaVersion")
    if not isinstance(schema, str) or not SCHEMA_RE.match(schema):
        failures.append(_failure(contract, "M-04", f"{p}.schemaVersion", schema, "1.x"))
    # M-05
    caps = provider.get("capabilities")
    if (not isinstance(caps, list) or not caps
            or any(not isinstance(c, str) or c not in enums["capability"] for c in caps)
            or len(set(caps)) != len(caps)):
        failures.append(_failure(contract, "M-05", f"{p}.capabilities", caps,
                                 "非空、去重、全部来自枚举白名单"))
    # M-06
    kind = provider.get("kind")
    side_effect = provider.get("sideEffect")
    if side_effect == "WRITE" or (kind == "HTTP" and side_effect != "NONE"):
        failures.append(_failure(contract, "M-06", f"{p}.sideEffect", side_effect,
                                 "HTTP Provider 必须为 NONE，WRITE 一律拒绝"))
    # M-07
    for key in ("owner", "description"):
        if not isinstance(provider.get(key), str) or not provider[key].strip():
            failures.append(_failure(contract, "M-07", f"{p}.{key}", provider.get(key), "非空字符串"))
    # M-08
    if kind == "HTTP" or "endpoint" in provider:
        failures.extend(_check_endpoint(provider.get("endpoint"), contract, f"{p}.endpoint"))
    # M-09
    auth_ref = provider.get("authRef")
    if auth_ref is not None and (not isinstance(auth_ref, str) or not AUTHREF_RE.match(auth_ref)):
        failures.append(_failure(contract, "M-09", f"{p}.authRef", auth_ref,
                                 r"^[A-Z][A-Z0-9_]{2,63}$"))
    for field in _inline_credential_fields(provider, p):
        failures.append(_failure(contract, "M-09", field, "内联凭据字段",
                                 "改用 authRef 指向环境变量名",
                                 "删除该字段，凭据只从进程环境变量注入"))
    # M-10
    for key, bound in (("timeoutMs", "timeoutMs"), ("maxResponseBytes", "maxResponseBytes")):
        value = provider.get(key)
        rule = limits[bound]
        if not isinstance(value, int) or isinstance(value, bool) or not (rule["min"] <= value <= rule["max"]):
            failures.append(_failure(contract, "M-10", f"{p}.{key}", value,
                                     f"{rule['min']} 至 {rule['max']} 的整数"))
    # M-11：提交态必须 false；运行态允许 G4 已确认的 true
    enabled = provider.get("enabled")
    if not isinstance(enabled, bool):
        failures.append(_failure(contract, "M-11", f"{p}.enabled", enabled, "布尔值"))
    elif strict_enabled and enabled:
        failures.append(_failure(contract, "M-11", f"{p}.enabled", True,
                                 "提交时 false，由 G4 人工确认后置 true"))
    # M-12
    if not provider.get("healthPath"):
        warnings.append(_failure(contract, "M-12", f"{p}.healthPath", provider.get("healthPath"),
                                 "提供 healthPath 供可用性探测", level="WARN"))

    return {
        "contractVersion": contract["contractVersion"],
        "providerId": provider_id if isinstance(provider_id, str) else f"providers[{index}]",
        "status": "REJECTED" if failures else "PROBING",
        "stage": "G0",
        "checkedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "failures": failures,
        "warnings": warnings,
    }


def validate_config(config: dict, contract: dict, strict_enabled: bool) -> dict:
    """校验一份完整配置：顶层版本、providers、routes、id 冲突。"""
    reports: list[dict] = []
    global_failures: list[dict] = []
    enums = contract["enums"]

    top_schema = config.get("schemaVersion")
    if not isinstance(top_schema, str) or not SCHEMA_RE.match(top_schema):
        global_failures.append(_failure(contract, "M-04", "schemaVersion", top_schema, "1.x"))
    top_level = {k: v for k, v in config.items() if k not in ("providers", "routes")}
    for field in _inline_credential_fields(top_level):
        global_failures.append(_failure(contract, "M-09", field, "内联凭据字段",
                                        "改用 authRef 指向环境变量名"))

    providers = config.get("providers")
    if not isinstance(providers, list):
        global_failures.append(_failure(contract, "M-01", "providers", providers, "数组"))
        providers = []
    seen: dict[str, int] = {}
    for i, provider in enumerate(providers):
        if not isinstance(provider, dict):
            reports.append({
                "contractVersion": contract["contractVersion"],
                "providerId": f"providers[{i}]", "status": "REJECTED", "stage": "G0",
                "checkedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "failures": [_failure(contract, "M-01", f"providers[{i}]", provider, "对象")],
                "warnings": [],
            })
            continue
        pid = provider.get("id")
        if isinstance(pid, str):
            if pid in seen:
                reports.append({
                    "contractVersion": contract["contractVersion"],
                    "providerId": pid, "status": "REJECTED", "stage": "G0",
                    "checkedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                    "failures": [_failure(contract, "M-01", f"providers[{i}].id", pid,
                                          "全局唯一；与 providers[%d] 冲突" % seen[pid],
                                          "删除重复条目或更换 id")],
                    "warnings": [],
                })
                continue
            seen[pid] = i
        reports.append(validate_provider(provider, i, contract, strict_enabled))

    known_ids = set(seen)
    routes = config.get("routes")
    if routes is not None and not isinstance(routes, list):
        global_failures.append(_failure(contract, "M-05", "routes", routes, "数组"))
        routes = []
    route_intents: set[str] = set()
    for i, route in enumerate(routes or []):
        if not isinstance(route, dict):
            global_failures.append(_failure(contract, "M-05", f"routes[{i}]", route, "对象"))
            continue
        intent = route.get("intent")
        if intent not in enums["capability"]:
            global_failures.append(_failure(contract, "M-05", f"routes[{i}].intent", intent,
                                            "capability 枚举内的 intent"))
        if intent in route_intents:
            global_failures.append(_failure(contract, "M-01", f"routes[{i}].intent", intent,
                                            "同一 intent 只能有一条路由"))
        route_intents.add(intent)
        for key in ("primary", "fallback"):
            refs = route.get(key, [])
            if isinstance(refs, str):
                refs = [refs]
            if not isinstance(refs, list):
                global_failures.append(_failure(contract, "M-01", f"routes[{i}].{key}", refs, "字符串或数组"))
                continue
            for ref in refs:
                if ref not in known_ids and not (isinstance(ref, str)
                                                 and any(ref.startswith(p) for p in contract["runtime"]["reservedPrefixes"])):
                    global_failures.append(_failure(contract, "M-01", f"routes[{i}].{key}", ref,
                                                    "指向已声明的 provider id 或内置 id"))

    return {"reports": reports, "globalFailures": global_failures}


def _compliant_provider() -> dict:
    return {
        "id": "py.food.recommender",
        "kind": "HTTP",
        "endpoint": "https://agent.example.com/v1/invoke",
        "healthPath": "/healthz",
        "authRef": "PY_AGENT_TOKEN",
        "version": "0.1.0",
        "owner": "travel-team",
        "description": "Python 参考实现",
        "capabilities": ["FOOD_RECOMMEND"],
        "schemaVersion": "1.0",
        "timeoutMs": 4000,
        "maxResponseBytes": 65536,
        "sideEffect": "NONE",
        "enabled": False,
    }


def _compliant_config(provider: dict | None = None) -> dict:
    p = provider or _compliant_provider()
    return {
        "schemaVersion": "1.0",
        "providers": [p],
        "routes": [{"intent": "FOOD_RECOMMEND", "primary": p.get("id"), "fallback": ["builtin.rules"]}],
    }


GOLD_CASES_PATH = Path(__file__).with_name("gold_cases.json")
DETECTOR_CODE_RE = re.compile(r"^E_(META|TRANSPORT|SCHEMA|SEMANTIC|SECURITY|PROBE)_[A-Z_]+$")
GOLD_GATES = {"G1", "G2"}


def check_gold_cases(contract: dict, doc: dict) -> list[str]:
    """校验 G1/G2 探针用例集与规范源同步，落实规范「四处同步」里的 gold cases 一处。

    只改 rules 不改用例集、用例 id 增减、闸门归属写错、原因码越出六组前缀，
    都会在这里被拦住，避免探针与规范判两套口径。
    """
    errors: list[str] = []
    if doc.get("contractVersion") != contract.get("contractVersion"):
        errors.append("gold cases 的 contractVersion 与规范源不一致")
    cases = doc.get("cases")
    if not isinstance(cases, list) or not cases:
        return errors + ["cases 必须是非空数组"]
    ids = [c.get("id") for c in cases if isinstance(c, dict)]
    expected = list(contract.get("goldCases", []))
    if sorted(ids) != sorted(expected):
        errors.append(f"用例 id 与规范源 goldCases 不一致：实际 {sorted(ids)}，期望 {sorted(expected)}")
    if len(ids) != len(set(ids)):
        errors.append("用例 id 重复")
    for case in cases:
        if not isinstance(case, dict):
            errors.append("用例不是对象")
            continue
        cid = case.get("id", "<无 id>")
        if case.get("gate") not in GOLD_GATES:
            errors.append(f"{cid}.gate 必须是 G1 或 G2")
        if not DETECTOR_CODE_RE.match(str(case.get("detectorCode", ""))):
            errors.append(f"{cid}.detectorCode 必须是规范六组前缀内的原因码")
        expect = case.get("expect")
        if not isinstance(expect, dict) or expect.get("outcome") not in {"ACCEPT", "REJECT"}:
            errors.append(f"{cid}.expect.outcome 必须是 ACCEPT 或 REJECT")
        inp = case.get("input")
        if not isinstance(inp, dict) or not isinstance(inp.get("facts"), list):
            errors.append(f"{cid}.input.facts 必须是数组")
            continue
        if inp.get("deadline") not in {"FUTURE", "EXPIRED"}:
            errors.append(f"{cid}.input.deadline 必须是 FUTURE 或 EXPIRED")
        for fact in inp["facts"]:
            if not isinstance(fact, dict):
                errors.append(f"{cid}.input.facts 含非对象项")
                continue
            evidence = fact.get("evidence", {})
            if evidence.get("verificationStatus") not in contract["enums"]["externalVerificationStatus"]:
                errors.append(f"{cid}.input.facts[].evidence.verificationStatus 不在枚举内")
    return errors


def self_test(contract: dict) -> int:
    """内置用例：一条合规基线 + 逐条打坏，锁死 M-01 至 M-12 的判定。"""
    cases: list[tuple[str, dict, set[str]]] = []

    def add(name: str, cfg: dict, expected: set[str]) -> None:
        cases.append((name, cfg, expected))

    add("合规配置通过 G0", _compliant_config(), set())

    def broken(name: str, mutate, expected: set[str]) -> None:
        cfg = _compliant_config()
        mutate(cfg)
        add(name, cfg, expected)

    broken("内联 token 被拒", lambda c: c["providers"][0].update({"token": "sk-fake"}), {"E_META_AUTH"})
    broken("内联 apiKey 被拒", lambda c: c["providers"][0].update({"apiKey": "fake"}), {"E_META_AUTH"})
    broken("schemaVersion 2.0 被拒", lambda c: c["providers"][0].update({"schemaVersion": "2.0"}), {"E_META_SCHEMA"})
    broken("顶层 schemaVersion 2.0 被拒", lambda c: c.update({"schemaVersion": "2.0"}), {"E_META_SCHEMA"})
    broken("保留前缀被拒", lambda c: c["providers"][0].update({"id": "builtin.planning"}), {"E_META_ID"})
    broken("id 格式不合法", lambda c: c["providers"][0].update({"id": "Food_Recommender"}), {"E_META_ID"})
    broken("重复 id 被拒", lambda c: c["providers"].append(copy.deepcopy(c["providers"][0])), {"E_META_ID"})
    broken("version 非语义化", lambda c: c["providers"][0].update({"version": "v1"}), {"E_META_VERSION"})
    broken("capability 不在白名单", lambda c: c["providers"][0].update({"capabilities": ["UNKNOWN"]}), {"E_META_CAPABILITY"})
    broken("capability 重复", lambda c: c["providers"][0].update({"capabilities": ["FOOD_RECOMMEND", "FOOD_RECOMMEND"]}), {"E_META_CAPABILITY"})
    broken("声明 WRITE 被拒", lambda c: c["providers"][0].update({"sideEffect": "WRITE"}), {"E_META_SIDEEFFECT"})
    broken("缺 owner", lambda c: c["providers"][0].pop("owner"), {"E_META_OWNER"})
    broken("http endpoint 被拒", lambda c: c["providers"][0].update({"endpoint": "http://agent.example.com/v1/invoke"}), {"E_META_ENDPOINT"})
    broken("IP 字面量被拒", lambda c: c["providers"][0].update({"endpoint": "https://127.0.0.1/v1/invoke"}), {"E_META_ENDPOINT"})
    broken("环回域名被拒", lambda c: c["providers"][0].update({"endpoint": "https://localhost/v1/invoke"}), {"E_META_ENDPOINT"})
    broken("host 不在白名单", lambda c: c["providers"][0].update({"endpoint": "https://evil.example.org/v1/invoke"}), {"E_META_ENDPOINT"})
    broken("endpoint 带 query", lambda c: c["providers"][0].update({"endpoint": "https://agent.example.com/v1/invoke?k=v"}), {"E_META_ENDPOINT"})
    broken("endpoint 带 userinfo", lambda c: c["providers"][0].update({"endpoint": "https://u:p@agent.example.com/v1/invoke"}), {"E_META_ENDPOINT"})
    broken("authRef 格式不合法", lambda c: c["providers"][0].update({"authRef": "py-token"}), {"E_META_AUTH"})
    broken("timeoutMs 越界", lambda c: c["providers"][0].update({"timeoutMs": 99999}), {"E_META_LIMIT"})
    broken("maxResponseBytes 越界", lambda c: c["providers"][0].update({"maxResponseBytes": 1}), {"E_META_LIMIT"})
    broken("提交态 enabled=true 被拒", lambda c: c["providers"][0].update({"enabled": True}), {"E_META_ENABLED"})
    broken("route 引用不存在的 id", lambda c: c["routes"][0].update({"primary": "ghost.provider"}), {"E_META_ID"})
    broken("route intent 不在枚举", lambda c: c["routes"][0].update({"intent": "GHOST"}), {"E_META_CAPABILITY"})
    broken("缺 healthPath 只告警", lambda c: c["providers"][0].pop("healthPath"), set())

    passed = 0
    for name, cfg, expected in cases:
        result = validate_config(cfg, contract, strict_enabled=True)
        codes = {f["code"] for r in result["reports"] for f in r["failures"]}
        codes |= {f["code"] for f in result["globalFailures"]}
        missing = expected - codes
        extra = codes - expected
        if not missing and not extra:
            passed += 1
            continue
        print(f"  FAIL {name}: missing={sorted(missing)} extra={sorted(codes)}", file=sys.stderr)
    # 运行态口径：G4 已确认的 enabled=true 应放行
    runtime_cfg = _compliant_config()
    runtime_cfg["providers"][0]["enabled"] = True
    runtime_result = validate_config(runtime_cfg, contract, strict_enabled=False)
    runtime_codes = {f["code"] for r in runtime_result["reports"] for f in r["failures"]}
    runtime_codes |= {f["code"] for f in runtime_result["globalFailures"]}
    if not runtime_codes:
        passed += 1
    else:
        print(f"  FAIL 运行态 enabled=true 应放行: {sorted(runtime_codes)}", file=sys.stderr)

    # gold cases 与规范源同步：规范「四处同步」里此前缺的一处，纳入 G0 自测。
    gold_path = Path(__file__).with_name(contract.get("goldCasesFile", "gold_cases.json"))
    gold_doc = json.loads(gold_path.read_text(encoding="utf-8"))
    first = gold_doc["cases"][0]
    broken_evidence = dict(first["input"]["facts"][0])
    broken_evidence["evidence"] = {**broken_evidence["evidence"], "verificationStatus": "verified"}
    gold_scenarios: list[tuple[str, dict, dict, bool]] = [
        ("gold cases 与规范源一致", contract, gold_doc, False),
        ("gold cases 缺用例应报错", contract,
         {**gold_doc, "cases": gold_doc["cases"][:-1]}, True),
        ("gold cases 版本漂移应报错", contract,
         {**gold_doc, "contractVersion": "9.9"}, True),
        ("gold cases gate 非法应报错", contract,
         {**gold_doc, "cases": [dict(first, gate="G9"), *gold_doc["cases"][1:]]}, True),
        ("gold cases 原因码越界应报错", contract,
         {**gold_doc, "cases": [dict(first, detectorCode="NOT_A_CODE"), *gold_doc["cases"][1:]]}, True),
        ("gold cases 期望缺失应报错", contract,
         {**gold_doc, "cases": [dict(first, expect={}), *gold_doc["cases"][1:]]}, True),
        ("gold cases 证据状态非法应报错", contract,
         {**gold_doc, "cases": [dict(first,
              input={**first["input"], "facts": [broken_evidence]}), *gold_doc["cases"][1:]]}, True),
    ]
    for name, gold_contract, doc, expect_error in gold_scenarios:
        found = bool(check_gold_cases(gold_contract, doc))
        if found == expect_error:
            passed += 1
        else:
            print(f"  FAIL {name}: expect_error={expect_error} found={found}", file=sys.stderr)

    total = len(cases) + 1 + len(gold_scenarios)
    print(f"G0 self-test: {passed}/{total} passed")
    return 0 if passed == total else 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Agent Provider G0 静态准入闸门")
    parser.add_argument("configs", nargs="*", help="待校验配置文件；缺省为种子与运行态配置")
    parser.add_argument("--self-test", action="store_true", help="跑内置规则用例")
    parser.add_argument("--json", action="store_true", help="输出完整 JSON 报告")
    parser.add_argument("--strict", action="store_true", help="强制要求 enabled=false（提交态）")
    parser.add_argument("--contract", default=str(CONTRACT_PATH), help="规范源路径")
    args = parser.parse_args(argv)

    contract = load_contract(Path(args.contract))
    # 用例集随规范版本入库：两条路径（自测 / 配置校验）都先确认它没和规范源脱节。
    gold_path = Path(__file__).with_name(contract.get("goldCasesFile", "gold_cases.json"))
    if not gold_path.exists():
        print(f"[G0] gold cases 文件缺失：{gold_path}", file=sys.stderr)
        return 2
    try:
        gold_errors = check_gold_cases(contract, json.loads(gold_path.read_text(encoding="utf-8")))
    except (OSError, KeyError, json.JSONDecodeError) as exc:
        print(f"[G0] gold cases 无法解析：{exc}", file=sys.stderr)
        return 2
    if gold_errors:
        for err in gold_errors:
            print(f"[G0] gold cases: {err}", file=sys.stderr)
        return 1
    if args.self_test:
        return self_test(contract)

    if args.configs:
        paths = [Path(c) for c in args.configs]
    else:
        paths = [ROOT / contract["runtime"]["exampleConfigFile"],
                 ROOT / contract["runtime"]["configFile"]]

    checked = 0
    failed = 0
    all_results = {}
    for path in paths:
        if not path.exists():
            if path.name.endswith(".example.json"):
                print(f"[G0] 缺少种子文件：{path}", file=sys.stderr)
                return 2
            print(f"[G0] {path} 不存在，跳过（运行态配置未启用）")
            continue
        try:
            with path.open("r", encoding="utf-8") as fh:
                config = json.load(fh)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"[G0] {path} 无法解析：{exc}", file=sys.stderr)
            return 2
        strict = args.strict or path.name.endswith(".example.json")
        result = validate_config(config, contract, strict_enabled=strict)
        all_results[str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)] = result
        checked += len(result["reports"])
        print(f"[G0] {path}:")
        for failure in result["globalFailures"]:
            print(f"  config : {failure['code']} ({failure['rule']}) {failure['field']} -> {failure['expected']}")
            failed += 1
        for report in result["reports"]:
            count = len(report["failures"])
            failed += count
            print(f"  {report['providerId']} : {report['status']} "
                  f"({count} failures, {len(report['warnings'])} warnings)")
            for failure in report["failures"]:
                print(f"    {failure['code']} ({failure['rule']}) {failure['field']} -> {failure['expected']}")

    if args.json:
        print(json.dumps(all_results, ensure_ascii=False, indent=2))
    if failed:
        print(f"G0 FAILED: {checked} provider(s) checked, {failed} blocking failure(s)", file=sys.stderr)
        return 1
    print(f"G0 OK: {checked} provider(s) checked")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
