#!/usr/bin/env python3
"""旅行助手图标资产构建工具。

唯一源
  design/brand-mark.svg   品牌主标（字面色值，供 data-URI 独立渲染）
  design/icons.svg        25 个功能图标 symbol（currentColor）

派生目标
  design/icon.html        4 个主标变体 + 25 个功能图标的内联定义
  design/desktop.html     顶栏主标 + AI 面板功能图标
  design/mobile.html      返回 / 发送 / 语音 / 底部导航等图标
  static/planner.html     落地工作台的图标定义（已写成 use，只刷新定义）
  design/export/          多尺寸 PNG 与 Android mipmap 包
  static/favicon.ico      16 / 32 / 48 三尺寸浏览器图标

改了 SVG 源之后按此顺序重跑，不要在展示页里手改路径：
  python tools/design_icons.py inline    重新派生内联定义
  python tools/design_icons.py wire      重新接入两份设计稿与落地工作台
  python tools/design_icons.py export    重新导出 PNG 与 ICO（需本机 Edge）
  python tools/design_icons.py all       以上三步依次执行

所有子命令幂等，可反复运行。临时文件固定落在 F:/wb_tmp，用完删除，
不写 C 盘；可用环境变量 TRAVEL_TMP 覆盖。
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design"
TMP = Path(os.environ.get("TRAVEL_TMP", "F:/wb_tmp"))
EDGE = os.environ.get("TRAVEL_EDGE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
STATIC = ROOT / "travel-server" / "src" / "main" / "resources" / "static"

PAPER, PINE, CLAY = "#F7F4EE", "#1E4A3E", "#B4542A"
ICON_COUNT = 25


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def write(p: Path, s: str) -> None:
    p.write_text(s, encoding="utf-8")


def assert_xml(p: Path) -> str:
    s = read(p)
    ET.fromstring(s)
    for i, c in enumerate(re.findall(r"<!--(.*?)-->", s, re.S)):
        if "--" in c:
            raise ValueError(f"{p.name} 第 {i} 段注释含非法双连字符，XML 解析器会报错")
    return s


# ---------------------------------------------------------------- inline

def recolor(s: str, paper: str, pine: str, clay: str) -> str:
    return s.replace(PAPER, paper).replace(PINE, pine).replace(CLAY, clay)


def build_marks(brand: str) -> tuple[str, str, str, str, str]:
    defs = re.search(r"<defs>.*?</defs>", brand, re.S).group(0)
    body = brand[brand.index("</defs>") + len("</defs>"): brand.rindex("</svg>")].strip()

    full = recolor(body, "var(--paper)", "var(--pine)", "var(--clay)")

    mono = recolor(body, "var(--paper)", "var(--pine)", "var(--pine)")
    line = ('<use href="#ridge" fill="none" stroke="var(--pine)" '
            'stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>')
    if line not in mono:
        raise ValueError("mono 版找不到山径细线")
    mono = mono.replace(line, line.replace('stroke-width="3"', 'stroke-width="3" stroke-dasharray="5 3.5"'), 1)

    inverse = recolor(body, "var(--paper)", "var(--pine)", "var(--clay)")
    pairs = [
        ('fill="var(--paper)" stroke="var(--pine)" stroke-width="3.2"',
         'fill="var(--pine)" stroke="var(--pine)" stroke-width="3.2"'),
        ('<g fill="none" stroke="var(--pine)" stroke-linecap="round">',
         '<g fill="none" stroke="var(--surface-warm)" stroke-linecap="round">'),
        ('stroke="var(--paper)" stroke-width="7"', 'stroke="var(--pine)" stroke-width="7"'),
        ('stroke="var(--clay)" stroke-width="3"', 'stroke="var(--clay-line)" stroke-width="3"'),
        ('r="2.5" fill="var(--paper)" stroke="var(--clay)"',
         'r="2.5" fill="var(--pine)" stroke="var(--clay-line)"'),
        ('r="4.6" fill="var(--clay)"', 'r="4.6" fill="var(--clay-line)"'),
        ('r="1.6" fill="var(--paper)"', 'r="1.6" fill="var(--pine)"'),
    ]
    for old, new in pairs:
        if old not in inverse:
            raise ValueError(f"inverse 版找不到 {old}")
        inverse = inverse.replace(old, new, 1)
    # 深底版去掉切口第二道刻线：贴着印框描边会看成多出来的对勾
    inverse = re.sub(r'  <path d="M44\.5 55\.5 L55\.5 44\.5"[^\n]*\n', "", inverse, count=1)

    micro = '''  <path d="M15 3.5 H49 A11.5 11.5 0 0 1 60.5 15 V40 L40 60.5 H15 A11.5 11.5 0 0 1 3.5 49 V15 A11.5 11.5 0 0 1 15 3.5 Z"
        fill="var(--paper)" stroke="var(--pine)" stroke-width="5.5" stroke-linejoin="round"/>
  <path d="M14 16 L22 24 L18 33 L30 38 L40 48" fill="none" stroke="var(--paper)"
        stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M14 16 L22 24 L18 33 L30 38 L40 48" fill="none" stroke="var(--clay)"
        stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="40" cy="48" r="7" fill="var(--clay)"/>'''
    return defs, full, mono, inverse, micro


def cmd_inline() -> None:
    brand = assert_xml(DESIGN / "brand-mark.svg")
    icons = assert_xml(DESIGN / "icons.svg")
    defs, full, mono, inverse, micro = build_marks(brand)
    symbols = re.findall(r"<symbol\b.*?</symbol>", icons, re.S)
    if len(symbols) != ICON_COUNT:
        raise ValueError(f"功能图标应为 {ICON_COUNT} 个，实际 {len(symbols)}")

    marks = [
        ("mark-full", full), ("mark-mono", mono),
        ("mark-inverse", inverse), ("mark-micro", micro),
    ]
    inline = (
        '<svg id="inlineDefs" xmlns="http://www.w3.org/2000/svg" width="0" height="0" '
        'style="position:absolute" aria-hidden="true">\n'
        "  <!-- 由 brand-mark.svg 与 icons.svg 派生，勿在此手改路径 -->\n"
        + defs + "\n"
        + "".join(f'  <symbol id="{k}" viewBox="0 0 64 64">\n{v}\n  </symbol>\n' for k, v in marks)
        + "\n".join(symbols) + "\n</svg>"
    )

    p = DESIGN / "icon.html"
    s = read(p)
    if "<!--INLINE_SVG-->" in s:
        s = s.replace("<!--INLINE_SVG-->", inline, 1)
    else:
        pat = re.compile(r'<svg id="inlineDefs".*?</svg>', re.S)
        if not pat.search(s):
            raise ValueError("icon.html 既无占位符也无内联定义块")
        s = pat.sub(lambda _: inline, s, count=1)
    write(p, s)
    print(f"inline: icon.html 已派生 4 个主标变体 + {len(symbols)} 个功能图标")


# ---------------------------------------------------------------- wire

def ico(name: str) -> str:
    return f'<svg class="ico" aria-hidden="true"><use href="#{name}"/></svg>'


DESKTOP_TABLE = [
    ('<span class="seal">行</span>',
     '<span class="seal"><svg viewBox="0 0 64 64" aria-hidden="true"><use href="#mark-full"/></svg></span>'),
    ('id="panelClose" aria-label="收起面板">×</button>',
     f'id="panelClose" aria-label="收起面板">{ico("i-close")}</button>'),
    ('id="queryClear" aria-label="清除问题">×</button>',
     f'id="queryClear" aria-label="清除问题">{ico("i-close")}</button>'),
    ('<div class="think"><span>⌄</span><span>深度思考完成（AI 生成）</span></div>',
     f'<div class="think">{ico("i-think")}<span>深度思考完成（AI 生成）</span></div>'),
    ('id="sourceBtn">来源 ⌄</button>', f'id="sourceBtn">来源 {ico("i-chevron")}</button>'),
    ('id="micBtn" aria-label="语音输入">◉</button>',
     f'id="micBtn" aria-label="语音输入">{ico("i-mic")}</button>'),
    ('id="sendBtn" aria-label="发送">↑</button>',
     f'id="sendBtn" aria-label="发送">{ico("i-send")}</button>'),
    ("think.innerHTML = '<span>⌄</span><span>深度思考完成（AI 生成）</span>';",
     f'think.innerHTML = \'{ico("i-think")}<span>深度思考完成（AI 生成）</span>\';'),
]

MOBILE_TABLE = [
    ('<span class="back">‹</span>', f'<span class="back">{ico("i-back")}</span>'),
    ('id="queryClear2" aria-label="清除问题">×</button>',
     f'id="queryClear2" aria-label="清除问题">{ico("i-close")}</button>'),
    ('<div class="think"><span>⌄</span><span>深度思考完成（AI 生成）</span></div>',
     f'<div class="think">{ico("i-think")}<span>深度思考完成（AI 生成）</span></div>'),
    ('id="sourceBtn2">来源 ⌄</button>', f'id="sourceBtn2">来源 {ico("i-chevron")}</button>'),
    ('id="mic2" aria-label="语音输入" style="background:var(--surface);color:var(--text-3);border:1px solid var(--line-strong)">◉</button>',
     'id="mic2" aria-label="语音输入" style="background:var(--surface);color:var(--text-3);border:1px solid var(--line-strong)">'
     + ico("i-mic") + '</button>'),
    ('id="send2" aria-label="发送">↑</button>',
     f'id="send2" aria-label="发送">{ico("i-send")}</button>'),
    ('<span>展开其余 2 项检查提示</span><span>⌄</span>',
     '<span>展开其余 2 项检查提示</span>' + ico("i-chevron")),
    ('邀请 +</span>', '邀请 ' + ico("i-plus") + '</span>'),
    ('在地图查看 →</button>', '在地图查看 ' + ico("i-map") + '</button>'),
    ('看列表 →</button>', '看列表 ' + ico("i-list") + '</button>'),
    ('<span class="ico">▤</span>行程', ico("i-list") + '行程'),
    ('<span class="ico">◇</span>待规划', ico("i-candidate") + '待规划'),
    ('<span class="ico">◌</span>消息', ico("i-ai") + '消息'),
    ('<span class="ico">○</span>我的', ico("i-collab") + '我的'),
    ('data-sheet="open" aria-label="新建行程">+</button>',
     'data-sheet="open" aria-label="新建行程">' + ico("i-plus") + '</button>'),
    ("think.innerHTML = '<span>⌄</span><span>深度思考完成（AI 生成）</span>';",
     f'think.innerHTML = \'{ico("i-think")}<span>深度思考完成（AI 生成）</span>\';'),
]


def cmd_wire() -> None:
    icons = assert_xml(DESIGN / "icons.svg")
    page = read(DESIGN / "icon.html")
    defs = re.search(r"<defs>.*?</defs>", page, re.S).group(0)
    mark = re.search(r'<symbol id="mark-full".*?</symbol>', page, re.S).group(0)
    symbols = re.findall(r"<symbol\b.*?</symbol>", icons, re.S)
    if len(symbols) != ICON_COUNT:
        raise ValueError(f"功能图标应为 {ICON_COUNT} 个，实际 {len(symbols)}")

    glyphs = (
        '<svg id="glyphDefs" xmlns="http://www.w3.org/2000/svg" width="0" height="0" '
        'style="position:absolute;overflow:hidden" aria-hidden="true">\n'
        "  <!-- 由 design/icons.svg 与 design/brand-mark.svg 派生，勿在此手改 -->\n"
        + defs + "\n" + mark + "\n" + "\n".join(symbols) + "\n</svg>\n"
    )

    targets = [(DESIGN / "desktop.html", DESKTOP_TABLE),
               (DESIGN / "mobile.html", MOBILE_TABLE)]
    planner = STATIC / "planner.html"
    if planner.exists():
        # 落地页已经写成 <use href="#...">，只需刷新定义，不做占位符替换。
        targets.append((planner, ()))
    for p, table in targets:
        s = read(p)
        s = re.sub(r'<svg id="glyphDefs".*?</svg>\n?', "", s, count=1, flags=re.S)
        if "<body>" not in s:
            raise ValueError(f"{p.name} 找不到 body")
        s = s.replace("<body>", "<body>\n" + glyphs, 1)
        for old, new in table:
            if old in s:
                s = s.replace(old, new)
            elif new not in s:
                raise ValueError(f"{p.name} 替换表失配：{old[:60]}")
        write(p, s)
        print(f"wire: {p.name} 已刷新 {len(symbols)} 个图标定义")

    tokens = DESIGN / "tokens.css"
    t = read(tokens)
    rule = """
/* 功能图标：由 design/icons.svg 提供符号，1em 跟随控件字号 */
svg.ico {
  width: 1em; height: 1em; display: inline-block; vertical-align: -.15em;
  flex: 0 0 auto; fill: none; stroke: currentColor;
  stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round;
}
"""
    if "svg.ico {" not in t:
        write(tokens, t.rstrip() + "\n" + rule)

    d = DESIGN / "desktop.html"
    s = read(d)
    old_seal = """.seal {
  width: 30px; height: 30px; display: grid; place-items: center;
  border: 2px solid var(--clay); color: var(--clay);
  font-size: var(--fs-md); font-weight: var(--w-strong); transform: rotate(-3deg);
  box-shadow: inset 0 0 0 2px var(--surface-warm), inset 0 0 0 3px var(--clay);
}"""
    new_seal = """.seal { width: 32px; height: 32px; display: grid; place-items: center; flex: 0 0 auto; }
.seal svg { width: 100%; height: 100%; display: block; }"""
    if old_seal in s:
        write(d, s.replace(old_seal, new_seal, 1))
    elif new_seal not in s:
        raise ValueError("desktop.html 的 .seal 样式既非旧版也非新版")

    m = DESIGN / "mobile.html"
    s = read(m)
    tweak = """
/* 图标化后的控件微调：返回与新建按钮保持 20px 触点内图形 */
.back svg.ico, .plus svg.ico { width: 20px; height: 20px; }
"""
    if ".back svg.ico, .plus svg.ico" not in s:
        write(m, s.replace("</style>", tweak + "</style>", 1))


# ---------------------------------------------------------------- export

def cmd_export() -> None:
    if not Path(EDGE).exists():
        raise FileNotFoundError(f"找不到 Edge：{EDGE}，可用 TRAVEL_EDGE 指定")
    TMP.mkdir(parents=True, exist_ok=True)

    brand = assert_xml(DESIGN / "brand-mark.svg")
    page = read(DESIGN / "icon.html")
    sym = re.search(r'<symbol id="mark-micro".*?</symbol>', page, re.S).group(0)
    micro_body = sym[sym.index(">") + 1: sym.rindex("</symbol>")]
    for token, value in (("var(--paper)", PAPER), ("var(--pine)", PINE), ("var(--clay)", CLAY)):
        micro_body = micro_body.replace(token, value)
    micro = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
             + micro_body + '</svg>')
    app = brand.replace("<defs>",
                        '<rect x="0" y="0" width="64" height="64" rx="13" fill="#EFEAE0"/>\n  <defs>', 1)

    svgs = {"full": brand, "micro": micro, "app": app}
    tasks = [
        ("favicon-16", "micro", 16), ("favicon-32", "full", 32), ("favicon-48", "full", 48),
        ("app-icon-48", "app", 48), ("app-icon-72", "app", 72), ("app-icon-96", "app", 96),
        ("app-icon-144", "app", 144), ("app-icon-192", "app", 192), ("app-icon-512", "app", 512),
    ]

    render = """<!doctype html><meta charset="utf-8"><body><pre id="R">PENDING</pre>
<script>
var SVGS = __SVGS__, TASKS = __TASKS__;
function draw(text, size) {
  return new Promise(function (resolve, reject) {
    var img = new Image();
    img.onload = function () {
      var c = document.createElement('canvas'); c.width = size; c.height = size;
      var ctx = c.getContext('2d');
      ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, size, size);
      resolve(c.toDataURL('image/png').split(',')[1]);
    };
    img.onerror = function () { reject(new Error('svg decode failed')); };
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(text);
  });
}
(async function () {
  var lines = [];
  for (var i = 0; i < TASKS.length; i++) {
    var t = TASKS[i];
    try { lines.push(t[0] + '|' + await draw(SVGS[t[1]], t[2])); }
    catch (e) { lines.push('ERROR|' + t[0] + ' ' + e.message); }
  }
  document.getElementById('R').textContent = 'PNGSTART\\n' + lines.join('\\n') + '\\nPNGEND';
})();
</script></body>"""
    render = (render.replace("__SVGS__", json.dumps(svgs, ensure_ascii=False))
                    .replace("__TASKS__", json.dumps(tasks)))
    html_path = TMP / "render_icons.html"
    dom_path = TMP / "render_icons_dom.html"
    write(html_path, render)
    with dom_path.open("w", encoding="utf-8") as fh:
        r = subprocess.run([
            EDGE, "--headless=new", "--disable-gpu", "--no-first-run",
            "--no-default-browser-check", "--allow-file-access-from-files",
            "--virtual-time-budget=15000", "--window-size=900,700", "--dump-dom",
            html_path.as_uri(),
        ], stdout=fh, stderr=subprocess.DEVNULL, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"Edge 退出码 {r.returncode}")
    dom = dom_path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'<pre id="R">PNGSTART\s*(.*?)\s*PNGEND</pre>', dom, re.S)
    if not m:
        raise RuntimeError("Edge 未输出 PNG 数据")
    lines = [x for x in m.group(1).splitlines() if "|" in x]
    errors = [x for x in lines if x.startswith("ERROR|")]
    if errors:
        raise RuntimeError("渲染失败：" + "; ".join(errors))
    pngs = {name: base64.b64decode(b64) for name, b64 in (x.split("|", 1) for x in lines)}

    out = DESIGN / "export"
    png_dir, android = out / "png", out / "android"
    png_dir.mkdir(parents=True, exist_ok=True)
    android.mkdir(parents=True, exist_ok=True)
    for name, data in pngs.items():
        (png_dir / f"{name}.png").write_bytes(data)
    density = {
        "app-icon-48": "mipmap-mdpi", "app-icon-72": "mipmap-hdpi",
        "app-icon-96": "mipmap-xhdpi", "app-icon-144": "mipmap-xxhdpi",
        "app-icon-192": "mipmap-xxxhdpi",
    }
    for name, folder in density.items():
        d = android / folder
        d.mkdir(parents=True, exist_ok=True)
        (d / "ic_launcher.png").write_bytes(pngs[name])

    entries = [(16, pngs["favicon-16"]), (32, pngs["favicon-32"]), (48, pngs["favicon-48"])]
    header = struct.pack("<HHH", 0, 1, len(entries))
    offset = 6 + 16 * len(entries)
    directory, blob = b"", b""
    for size, data in entries:
        directory += struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(data), offset)
        blob += data
        offset += len(data)
    (STATIC / "favicon.ico").write_bytes(header + directory + blob)

    for path in (html_path, dom_path):
        path.unlink(missing_ok=True)
    print(f"export: {len(pngs)} 张 PNG、5 个 mipmap 目录、favicon.ico（16/32/48）")


# ---------------------------------------------------------------- check

def cmd_check() -> None:
    assert_xml(DESIGN / "brand-mark.svg")
    assert_xml(DESIGN / "icons.svg")
    pages = [DESIGN / "icon.html", DESIGN / "desktop.html", DESIGN / "mobile.html"]
    planner = STATIC / "planner.html"
    if planner.exists():
        pages.append(planner)
    for p in pages:
        s = read(p)
        ids = re.findall(r'\bid="([^"]+)"', s)
        dup = [k for k, v in Counter(ids).items() if v > 1]
        uses = set(re.findall(r'<use href="#([^"]+)"', s))
        missing = sorted(uses - set(ids))
        print(f"check: {p.name} ids={len(ids)} dup={dup} uniqueRefs={len(uses)} missing={missing}")
        if dup or missing:
            raise ValueError(f"{p.name} 结构不通过")
    print("check: XML 与引用关系全部通过")


def main() -> int:
    ap = argparse.ArgumentParser(description="旅行助手图标资产构建工具")
    ap.add_argument("command", choices=["inline", "wire", "export", "check", "all"])
    args = ap.parse_args()
    try:
        if args.command in ("inline", "all"):
            cmd_inline()
        if args.command in ("wire", "all"):
            cmd_wire()
        if args.command in ("export", "all"):
            cmd_export()
        if args.command == "check":
            cmd_check()
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
