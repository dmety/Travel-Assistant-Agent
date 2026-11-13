/* ==========================================================================
   浮动看板娘 · 游小满 v4.1（前端组件）
   --------------------------------------------------------------------------
   自包含：脚本注入所需样式，挂在任何页面都能用，不依赖 tokens.css 是否已加载。
   行为：
     · 全身可拖：浮窗任意位置 pointerdown 即拖拽；拖动期间只写 transform
       （合成层位移，不逐帧改 left/top 触发重排与 drop-shadow 重绘），
       松手才落位 left/top 并持久化；拖拽中暂停呼吸/指针跟随、隐藏气泡。
     · 双击切换「徽章档」：正脸头像小圆，少遮地图；再双击恢复立绘。
     · 周期眨眼：差分帧硬切（不做半透明交叉淡入，避免闪烁感）。
     · 点击循环四段情绪 idle → 害羞 → 不耐烦 → 生气 → idle；
       idle/害羞 点头，不耐烦/生气 抖动；指针跟随整窗轻微倾斜。
   资产：/mascot/mascot-v4-front-*.png（正脸透明底，头像一律正脸）。
   表情契约类名 .look-user / .look-pass / .look-annoyed / .look-angry 与
   tokens.css / design/kanban.html 共用，勿改名；.m-idle 为默认。
   关闭：在 <body data-float-mascot="off"> 时本组件不挂载（登录页有 hero 看板娘，不重复）。
   ========================================================================== */
(function () {
  'use strict';
  if (document.getElementById('mascotDock')) return;
  if (document.body && document.body.dataset.floatMascot === 'off') return;

  var PREFIX = '/mascot/mascot-v4-front-';
  var SRC = {
    idle: PREFIX + 'idle.png',
    user: PREFIX + 'shy.png',
    pass: PREFIX + 'impatient.png',
    blink: PREFIX + 'blink.png',
    annoyed: PREFIX + 'impatient.png',
    angry: PREFIX + 'angry.png'
  };
  var MOODS = ['idle', 'user', 'annoyed', 'angry'];
  var CAP = {
    idle: '需要我帮你想想行程吗？戳我一下～',
    user: '这页有看不懂的，直接问我就行。',
    annoyed: '别老戳我啦……',
    angry: '再戳我可要真生气了！'
  };
  // 点头 / 抖动的动效名
  var ANIM = { idle: 'nod', user: 'nod', annoyed: 'shake', angry: 'shake' };

  /* ---------- 注入样式（自包含） ---------- */
  var css =
    '.mascot-dock{position:fixed;right:16px;bottom:16px;z-index:60;width:112px;height:144px;' +
    'cursor:grab;touch-action:none;will-change:transform;' +
    'filter:drop-shadow(0 10px 20px rgba(0,0,0,.18));}' +
    '.mascot-dock.dragging{cursor:grabbing;}' +
    '.mascot-dock.dragging .mascot-svg{animation:none;}' +
    '.mascot-dock:focus-visible{outline:2px solid var(--clay,#B4542A);outline-offset:4px;}' +
    '.mascot-dock.compact{width:56px;height:56px;border-radius:50%;overflow:hidden;' +
    'filter:drop-shadow(0 6px 14px rgba(0,0,0,.22));}' +
    '.mascot-dock.compact .mascot-svg img{object-fit:cover;object-position:50% 12%;}' +
    '.mascot-dock.compact .mascot-cap{display:none;}' +
    '.mascot-dock .mascot-svg{position:relative;display:block;width:100%;height:100%;}' +
    '.mascot-dock .mascot-svg img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;transition:opacity .24s ease;}' +
    '.mascot-dock .m-user,.mascot-dock .m-pass,.mascot-dock .m-blink,.mascot-dock .m-annoyed,.mascot-dock .m-angry{opacity:0;}' +
    '.mascot-dock .mascot-svg.look-user .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-user .m-user{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-pass .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-pass .m-pass{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-annoyed .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-annoyed .m-annoyed{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-angry .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-angry .m-angry{opacity:1;}' +
    /* 眨眼硬切：交叉淡入会在两帧半透明时露底，看起来像闪烁 */
    '.mascot-dock .mascot-svg .m-blink{transition:none;}' +
    '.mascot-dock .mascot-svg.blink .m-idle{opacity:0;transition:none;}.mascot-dock .mascot-svg.blink .m-blink{opacity:1;}' +
    /* 气泡默认放在浮窗左上方：右侧竖栏留给状态胶囊与日程条，避免盖住它们 */
    '.mascot-cap{position:absolute;right:calc(100% + 10px);bottom:calc(100% + 34px);width:max-content;max-width:220px;padding:8px 12px;' +
    'border-radius:14px;border:1px solid var(--pine-line,#BBD0C4);background:var(--surface,#fff);' +
    'color:var(--ink,#20241E);font-size:12px;line-height:1.6;box-shadow:var(--shadow-2,0 4px 14px rgba(0,0,0,.1));' +
    'opacity:0;transform:translateY(6px);pointer-events:none;transition:opacity .2s ease,transform .2s ease;}' +
    '.mascot-cap.flip-x{right:auto;left:calc(100% + 10px);}' +
    '.mascot-cap.flip-y{bottom:auto;top:calc(100% + 10px);}' +
    '.mascot-cap.show{opacity:1;transform:translateY(0);}' +
    '@media (max-width:900px){.mascot-dock{width:76px;height:98px;right:10px;bottom:96px;}' +
    '.mascot-dock.compact{width:44px;height:44px;}}' +
    '@media (prefers-reduced-motion:no-preference){' +
    '@keyframes mgBreath{0%,100%{transform:translateY(0)}50%{transform:translateY(-3px)}}' +
    '@keyframes mgNod{0%{transform:translateY(0) rotate(0)}35%{transform:translateY(4px) rotate(2deg)}70%{transform:translateY(-2px) rotate(-1deg)}100%{transform:translateY(0) rotate(0)}}' +
    '@keyframes mgShake{0%,100%{transform:translateX(0) rotate(0)}20%{transform:translateX(-3px) rotate(-1.5deg)}40%{transform:translateX(3px) rotate(1.5deg)}60%{transform:translateX(-2px) rotate(-1deg)}80%{transform:translateX(2px) rotate(1deg)}}' +
    '.mascot-dock .mascot-svg{animation:mgBreath 5.6s ease-in-out infinite;}' +
    '.mascot-dock .mascot-svg.nod{animation:mgNod .55s cubic-bezier(.16,1,.3,1) both;}' +
    '.mascot-dock .mascot-svg.shake{animation:mgShake .5s cubic-bezier(.16,1,.3,1) both;}}' +
    '@media (prefers-reduced-motion:reduce){' +
    '.mascot-dock .mascot-svg,.mascot-dock .mascot-svg img{animation:none!important;transition:none!important;}}';
  var style = document.createElement('style');
  style.id = 'mascotWidgetStyle';
  style.textContent = css;
  document.head.appendChild(style);

  /* ---------- 构建 DOM ---------- */
  var dock = document.createElement('div');
  dock.id = 'mascotDock';
  dock.className = 'mascot-dock';
  dock.setAttribute('role', 'button');
  dock.setAttribute('tabindex', '0');
  dock.setAttribute('aria-label', '看板娘 游小满（拖动挪位，双击收起）');
  dock.title = '拖动挪位 · 双击收起/展开';

  var cap = document.createElement('div');
  cap.className = 'mascot-cap';
  cap.setAttribute('role', 'status');
  cap.setAttribute('aria-live', 'polite');
  cap.textContent = CAP.idle;

  var svg = document.createElement('div');
  svg.className = 'mascot-svg';
  svg.setAttribute('aria-hidden', 'true');
  ['idle', 'user', 'pass', 'blink', 'annoyed', 'angry'].forEach(function (k) {
    var img = document.createElement('img');
    img.className = 'm-' + k;
    img.src = SRC[k];
    img.alt = '';
    img.draggable = false;
    svg.appendChild(img);
  });

  dock.appendChild(cap);
  dock.appendChild(svg);
  document.body.appendChild(dock);
  var dragging = false, moved = false;

  /* ---------- 台词气泡显隐 ---------- */
  var capTimer = null;
  function showCap(text, sticky) {
    cap.textContent = text;
    cap.classList.add('show');
    var dr = dock.getBoundingClientRect();
    cap.classList.toggle('flip-x', dr.left < cap.offsetWidth + 16);
    cap.classList.toggle('flip-y', dr.top < cap.offsetHeight + 40);
    if (capTimer) { clearTimeout(capTimer); capTimer = null; }
    if (!sticky) capTimer = setTimeout(function () { cap.classList.remove('show'); }, 2600);
  }

  /* ---------- 情绪切换 ---------- */
  var idx = 0;
  function setMood(mood) {
    svg.classList.remove('look-user', 'look-pass', 'look-annoyed', 'look-angry');
    if (mood !== 'idle') svg.classList.add('look-' + mood);
    svg.classList.remove('nod', 'shake');
    void svg.getBoundingClientRect();
    svg.classList.add(ANIM[mood]);
    showCap(CAP[mood]);
  }
  svg.addEventListener('animationend', function (e) {
    if (e.animationName === 'mgNod' || e.animationName === 'mgShake') {
      svg.classList.remove('nod', 'shake');
    }
  });
  function advance() {
    idx = (idx + 1) % MOODS.length;
    setMood(MOODS[idx]);
  }
  /* 单击换情绪、双击收起/展开。用点击间隔判定双击：pointerdown 会 preventDefault，
     部分浏览器不再合成原生 dblclick，但 click 始终触发，按时间判定最稳。 */
  var lastClick = 0;
  dock.addEventListener('click', function (e) {
    if (moved) { moved = false; e.preventDefault(); e.stopPropagation(); return; }
    var now = Date.now();
    if (now - lastClick < 320) { lastClick = 0; toggleCompact(); }
    else { lastClick = now; advance(); }
  });
  dock.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); advance(); }
  });

  /* ---------- 徽章档：双击收起成头像小圆，少遮地图 ---------- */
  var compact = false;
  try { compact = localStorage.getItem('mascotCompact') === '1'; } catch (e) {}
  function applyCompact() {
    dock.classList.toggle('compact', compact);
    dock.setAttribute('aria-label', compact
      ? '看板娘 游小满 · 徽章档（双击展开）'
      : '看板娘 游小满（拖动挪位，双击收起）');
  }
  applyCompact();
  function toggleCompact() {
    compact = !compact;
    applyCompact();
    idx = 0;
    svg.classList.remove('look-user', 'look-pass', 'look-annoyed', 'look-angry', 'nod', 'shake');
    clampPos();
    try { localStorage.setItem('mascotCompact', compact ? '1' : '0'); } catch (e2) {}
  }

  /* ---------- 周期眨眼（硬切） ---------- */
  function scheduleBlink() {
    var wait = 4000 + Math.random() * 4000;
    setTimeout(function () {
      if (document.hidden) { scheduleBlink(); return; }
      svg.classList.add('blink');
      setTimeout(function () { svg.classList.remove('blink'); }, 120);
      scheduleBlink();
    }, wait);
  }
  scheduleBlink();

  /* ---------- 拖拽：全身可拖，位移走 transform，松手落位并持久化 ---------- */
  function clampPos() {
    var l = parseFloat(dock.style.left), t = parseFloat(dock.style.top);
    if (isNaN(l) || isNaN(t)) return;
    dock.style.left = Math.max(0, Math.min(l, window.innerWidth - dock.offsetWidth)) + 'px';
    dock.style.top = Math.max(0, Math.min(t, window.innerHeight - dock.offsetHeight)) + 'px';
  }
  var savedPos = null;
  try { savedPos = JSON.parse(localStorage.getItem('mascotPos') || 'null'); } catch (e) {}
  if (savedPos && typeof savedPos.left === 'number' && typeof savedPos.top === 'number') {
    dock.style.left = savedPos.left + 'px';
    dock.style.top = savedPos.top + 'px';
    dock.style.right = 'auto';
    dock.style.bottom = 'auto';
    clampPos();
  }
  window.addEventListener('resize', clampPos);

  var dragX = 0, dragY = 0, baseL = 0, baseT = 0;
  dock.addEventListener('pointerdown', function (e) {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    e.preventDefault(); e.stopPropagation();
    dragging = true; moved = false;
    var r = dock.getBoundingClientRect();
    baseL = r.left; baseT = r.top;
    dock.style.left = baseL + 'px';
    dock.style.top = baseT + 'px';
    dock.style.right = 'auto';
    dock.style.bottom = 'auto';
    dock.style.transform = '';
    if (raf) { cancelAnimationFrame(raf); raf = null; }
    tx = ty = cx = cy = 0;
    dragX = e.clientX; dragY = e.clientY;
    dock.classList.add('dragging');
    cap.classList.remove('show');
    try { dock.setPointerCapture(e.pointerId); } catch (e2) {}
  });
  dock.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var dx = e.clientX - dragX, dy = e.clientY - dragY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
    var nl = Math.max(0, Math.min(baseL + dx, window.innerWidth - dock.offsetWidth));
    var nt = Math.max(0, Math.min(baseT + dy, window.innerHeight - dock.offsetHeight));
    dock.style.transform = 'translate3d(' + (nl - baseL) + 'px,' + (nt - baseT) + 'px,0)';
  });
  function endDrag() {
    if (!dragging) return;
    dragging = false;
    dock.classList.remove('dragging');
    var m = /translate3d\(\s*(-?[\d.]+)px\s*,\s*(-?[\d.]+)px/.exec(dock.style.transform || '');
    var nl = baseL + (m ? +m[1] : 0), nt = baseT + (m ? +m[2] : 0);
    dock.style.left = nl + 'px';
    dock.style.top = nt + 'px';
    dock.style.transform = '';
    try {
      localStorage.setItem('mascotPos', JSON.stringify({ left: nl, top: nt }));
    } catch (e) {}
  }
  dock.addEventListener('pointerup', endDrag);
  dock.addEventListener('pointercancel', endDrag);

  /* ---------- 指针跟随（整窗轻微倾斜；拖拽期间暂停） ---------- */
  var tx = 0, ty = 0, cx = 0, cy = 0, raf = null;
  function loop() {
    cx += (tx - cx) * 0.12;
    cy += (ty - cy) * 0.12;
    dock.style.transform = 'translate(' + cx.toFixed(2) + 'px,' + cy.toFixed(2) + 'px)';
    if (Math.abs(tx - cx) > 0.1 || Math.abs(ty - cy) > 0.1) {
      raf = requestAnimationFrame(loop);
    } else { raf = null; }
  }
  window.addEventListener('mousemove', function (e) {
    if (dragging) return;
    var r = dock.getBoundingClientRect();
    var dx = (e.clientX - (r.left + r.width / 2)) / (window.innerWidth / 2);
    var dy = (e.clientY - (r.top + r.height / 2)) / (window.innerHeight / 2);
    tx = Math.max(-6, Math.min(6, dx * 6));
    ty = Math.max(-6, Math.min(6, dy * 6));
    if (!raf) raf = requestAnimationFrame(loop);
  }, { passive: true });

  /* 首次进场稍候提示 */
  setTimeout(function () { showCap(CAP.idle); }, 900);
})();
