/* ==========================================================================
   浮动看板娘 · 游小满 v4.4（前端组件）
   --------------------------------------------------------------------------
   自包含：脚本注入所需样式，挂在任何页面都能用，不依赖 tokens.css 是否已加载。

   ★ v4.4 变更：恢复「点击弹台词气泡」，新增「悬停滚轮缩放立绘」。
     她仍是【完整立绘】：没有头像档、没有徽章档、没有圆形裁切，缩放只作用于
     内层 rig，不裁身体。v4.2 的 compact 圆形裁切会把腿裁掉，已整体移除。
     v4.3.1 修差分帧叠帧：基础 img 规则特异性压过隐藏规则，导致 6 帧全不透明
     叠在一起（闪烁 + 表情永远显示最后一帧），显隐规则统一加 img.m-* 前缀。
     Esc 一键归位：清掉误拖到地图中央的位置记忆，回到右下预留位。

   不遮地图的正确解法不是删功能：气泡只在点击后显示 2.6s、pointer-events:none、
   贴边自动翻转，不会常驻压住点位；地图被大面积遮挡来自 planner.html 的
   .ai-panel 浮层，那里另有「收起面板」按钮，与本组件无关。

   通道分层（解决「只有脚能拖 / 拖完闪烁回弹」）：
     dock = 定位层。left/top 落位，拖拽期间独占位移。
     rig  = 指针跟随层。独占 transform 轻微倾斜，拖拽期间冻结清零。
     svg  = 表情层。独占 animation（呼吸/点头/抖动），拖拽期间 animation:none。
     img  = 差分帧。opacity 硬切，不做交叉淡入（半透明叠帧就是闪烁源）。
   所有 img 设 pointer-events:none，事件直接落到 dock 全矩形；
   pointermove/up/cancel 挂 window，配合 setPointerCapture 双保险。
   落位用数值变量，不再正则解析 transform 字符串（解析失败会弹回原位）。

   首帧不自动弹气泡（避免一进页面就挡地图）；点击/Enter/Space 才弹，2.6s 自动收起。
   立绘 96x124 停靠右下，可拖到任意位置并写入 localStorage 记忆；滚轮缩放 0.6~2.0，
   尺寸单独持久化，Esc 只归位不改尺寸。拖拽期间冻结眨眼调度、情绪切换与指针跟随。

   契约（勿改名，登录页 / kanban.html 共用）：
     类名 .mascot-svg + .m-idle/.m-user/.m-pass/.m-blink/.m-annoyed/.m-angry；
     切换类 .look-user/.look-pass/.look-annoyed/.look-angry；
     资源 /mascot/mascot-v4-front-*.png；localStorage 键 mascotPos / mascotScale；
     <body data-float-mascot="off"> 时本组件不挂载（登录页有 hero 看板娘）。
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
  var ANIM = { idle: 'nod', user: 'nod', annoyed: 'shake', angry: 'shake' };

  /* ---------- 注入样式（全部限定在 .mascot-dock 下，不污染登录页） ---------- */
  var css = [
    '.mascot-dock{position:fixed;right:16px;bottom:16px;z-index:60;width:96px;height:124px;',
    'cursor:grab;touch-action:none;user-select:none;-webkit-user-select:none;',
    'filter:drop-shadow(0 10px 20px rgba(0,0,0,.18));}',
    '.mascot-dock:focus-visible{outline:2px solid var(--clay,#B4542A);outline-offset:4px;}',
    /* 拖拽中冻结一切动效，避免合成层反复重绘造成闪烁 */
    '.mascot-dock.dragging{cursor:grabbing;}',
    '.mascot-dock.dragging .mascot-svg{animation:none!important;}',
    /* rig：指针跟随 + 滚轮缩放层，独占 transform。
       origin 取 bottom center：放大时底边贴地、左右对称外扩，右下角不会被裁掉。 */
    '.mascot-rig{position:absolute;inset:0;will-change:transform;',
    'transform-origin:bottom center;}',
    /* svg：表情层，独占 animation */
    '.mascot-dock .mascot-svg{position:absolute;inset:0;display:block;}',
    /* 立绘始终 object-fit:contain 完整显示，绝不裁身体；
       pointer-events:none 让整个 dock 矩形都是拖拽热区。
       差分帧的显隐规则一律写成 img.m-* 形式：基础 img 规则特异性 (0,2,1)
       会压过 .mascot-dock .m-user (0,2,0)，一旦漏掉 img 就会出现
       「6 帧全不透明叠在一起」——叠帧正是闪烁与表情错乱的根因。 */
    '.mascot-dock .mascot-svg img{position:absolute;inset:0;width:100%;height:100%;',
    'object-fit:contain;pointer-events:none;transition:none!important;}',
    '.mascot-dock .mascot-svg img.m-idle{opacity:1;}',
    '.mascot-dock .mascot-svg img.m-user,',
    '.mascot-dock .mascot-svg img.m-pass,',
    '.mascot-dock .mascot-svg img.m-blink,',
    '.mascot-dock .mascot-svg img.m-annoyed,',
    '.mascot-dock .mascot-svg img.m-angry{opacity:0;}',
    '.mascot-dock .mascot-svg.look-user img.m-idle{opacity:0;}',
    '.mascot-dock .mascot-svg.look-user img.m-user{opacity:1;}',
    '.mascot-dock .mascot-svg.look-pass img.m-idle{opacity:0;}',
    '.mascot-dock .mascot-svg.look-pass img.m-pass{opacity:1;}',
    '.mascot-dock .mascot-svg.look-annoyed img.m-idle{opacity:0;}',
    '.mascot-dock .mascot-svg.look-annoyed img.m-annoyed{opacity:1;}',
    '.mascot-dock .mascot-svg.look-angry img.m-idle{opacity:0;}',
    '.mascot-dock .mascot-svg.look-angry img.m-angry{opacity:1;}',
    /* 眨眼硬切：先压掉其它帧再显示闭眼帧，杜绝叠帧露底 */
    '.mascot-dock .mascot-svg.blink img.m-idle,.mascot-dock .mascot-svg.blink img.m-user,',
    '.mascot-dock .mascot-svg.blink img.m-pass,.mascot-dock .mascot-svg.blink img.m-annoyed,',
    '.mascot-dock .mascot-svg.blink img.m-angry{opacity:0;}',
    '.mascot-dock .mascot-svg.blink img.m-blink{opacity:1;}',
    '@media (max-width:900px){',
    '.mascot-dock{width:76px;height:98px;right:10px;bottom:96px;}}',
    '@media (prefers-reduced-motion:no-preference){',
    '@keyframes mgBreath{0%,100%{transform:translateY(0)}50%{transform:translateY(-3px)}}',
    '@keyframes mgNod{0%{transform:translateY(0) rotate(0)}35%{transform:translateY(4px) rotate(2deg)}',
    '70%{transform:translateY(-2px) rotate(-1deg)}100%{transform:translateY(0) rotate(0)}}',
    '@keyframes mgShake{0%,100%{transform:translateX(0) rotate(0)}20%{transform:translateX(-3px) rotate(-1.5deg)}',
    '40%{transform:translateX(3px) rotate(1.5deg)}60%{transform:translateX(-2px) rotate(-1deg)}',
    '80%{transform:translateX(2px) rotate(1deg)}}',
    '.mascot-dock .mascot-svg{animation:mgBreath 5.6s ease-in-out infinite;}',
    '.mascot-dock .mascot-svg.nod{animation:mgNod .55s cubic-bezier(.16,1,.3,1) both;}',
    '.mascot-dock .mascot-svg.shake{animation:mgShake .5s cubic-bezier(.16,1,.3,1) both;}}',
    '@media (prefers-reduced-motion:reduce){',
    '.mascot-dock .mascot-svg,.mascot-dock .mascot-svg img{',
    'animation:none!important;transition:none!important;}}',
    /* 台词气泡：只在点击后显示 2.6s；默认左上方，贴近视口左/上边缘时翻转，
       不常驻、不接收指针事件，避免压住地图点位或吞掉拖拽。 */
    '.mascot-cap{position:absolute;right:calc(100% + 10px);bottom:calc(100% + 34px);',
    'width:max-content;max-width:230px;padding:8px 12px;border-radius:14px;',
    'background:var(--surface,#F7F4EE);border:1px solid var(--line,rgba(30,74,62,.18));',
    'color:var(--ink,#1E293B);font-size:12px;line-height:1.6;',
    'box-shadow:0 8px 20px rgba(0,0,0,.16);opacity:0;transform:translateY(6px);',
    'transition:opacity .18s ease,transform .18s ease;pointer-events:none;}',
    '.mascot-cap.show{opacity:1;transform:translateY(0);}',
    '.mascot-cap.flip-x{right:auto;left:calc(100% + 10px);}',
    '.mascot-cap.flip-y{bottom:auto;top:calc(100% + 10px);}'
  ].join('');
  var style = document.createElement('style');
  style.id = 'mascotWidgetStyle';
  style.textContent = css;
  document.head.appendChild(style);

  /* ---------- 构建 DOM：dock > rig > svg > 6 帧 ---------- */
  var dock = document.createElement('div');
  dock.id = 'mascotDock';
  dock.className = 'mascot-dock';
  dock.setAttribute('role', 'button');
  dock.setAttribute('tabindex', '0');
  dock.setAttribute('aria-label', '看板娘 游小满（点击弹台词，拖动挪位，滚轮缩放，按 Esc 回到角落）');
  dock.title = '拖动挪位 · 点击聊一句 · 滚轮缩放 · Esc 回到角落';

  var rig = document.createElement('div');
  rig.className = 'mascot-rig';

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
  rig.appendChild(svg);
  dock.appendChild(rig);

  /* 台词气泡挂在 dock 上（不进 rig），缩放只影响立绘，文字始终按原字号可读。 */
  var cap = document.createElement('div');
  cap.className = 'mascot-cap';
  cap.setAttribute('role', 'status');
  cap.setAttribute('aria-live', 'polite');
  dock.appendChild(cap);
  document.body.appendChild(dock);

  /* ---------- 滚轮缩放：0.6~2.0，尺寸与位置分开持久化 ----------
     缩放只写进 rig 的 transform；dock 保持未缩放的定位尺寸，
     getBoundingClientRect / offsetWidth 才不会被放大后的尺寸带偏。 */
  var SCALE_KEY = 'mascotScale', MIN_SCALE = 0.6, MAX_SCALE = 2;
  var scale = 1;
  try {
    var savedScale = parseFloat(localStorage.getItem(SCALE_KEY));
    if (isFinite(savedScale) && savedScale > 0) scale = clamp(savedScale, MIN_SCALE, MAX_SCALE);
  } catch (e) {}
  function saveScale() {
    try { localStorage.setItem(SCALE_KEY, scale.toFixed(3)); } catch (e) {}
  }
  function rigTransform() {
    return 'translate(' + cx.toFixed(2) + 'px,' + cy.toFixed(2) + 'px) scale(' + scale.toFixed(3) + ')';
  }
  function updateHint() {
    dock.title = '拖动挪位 · 点击聊一句 · 滚轮缩放（当前 '
      + Math.round(scale * 100) + '%）· Esc 回到角落';
  }
  rig.style.transform = 'translate(0px,0px) scale(' + scale.toFixed(3) + ')';
  updateHint();

  /* ---------- 情绪 + 台词气泡 ---------- */
  var CAP = {
    idle: '需要我帮你想想行程吗？戳我一下～',
    user: '这页有看不懂的，直接问我就行。',
    pass: '我不看，你慢慢输。',
    annoyed: '别老戳我啦……',
    angry: '再戳我可要真生气了！'
  };
  var capTimer = null;
  function flipCap() {
    var r = dock.getBoundingClientRect();
    cap.classList.toggle('flip-x', r.left < 250);
    cap.classList.toggle('flip-y', r.top < 130);
  }
  function showCap(text) {
    if (!text) return;
    flipCap();
    cap.textContent = text;
    cap.classList.add('show');
    if (capTimer) clearTimeout(capTimer);
    capTimer = setTimeout(function () { cap.classList.remove('show'); }, 2600);
  }
  function hideCap() {
    if (capTimer) { clearTimeout(capTimer); capTimer = null; }
    cap.classList.remove('show');
  }
  var idx = 0;
  function setMood(mood) {
    if (dragging) return;
    svg.classList.remove('look-user', 'look-pass', 'look-annoyed', 'look-angry');
    if (mood !== 'idle') svg.classList.add('look-' + mood);
    svg.classList.remove('nod', 'shake');
    /* 强制重排让连点时动画每次都重新起跳，而不是卡在上一帧 */
    void svg.offsetWidth;
    svg.classList.add(ANIM[mood]);
    showCap(CAP[mood] || CAP.idle);
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

  /* 没有双击判定、没有档位切换：连续快速点击只会循环表情，立绘始终完整 */
  var suppressClick = false;
  dock.addEventListener('click', function (e) {
    if (suppressClick) { suppressClick = false; e.preventDefault(); e.stopPropagation(); return; }
    advance();
  });
  /* Esc 一键归位：清掉旧版误拖到地图中央的位置记忆，回到右下预留位。
     不改点击行为、不改尺寸，是可发现性之外的兜底手段。 */
  function resetPos() {
    if (dragging) return;
    try { localStorage.removeItem('mascotPos'); } catch (e2) {}
    dock.style.left = '';
    dock.style.top = '';
    dock.style.right = '';
    dock.style.bottom = '';
    dock.style.transform = '';
    /* 归位只清位置记忆，不重置用户调好的立绘大小 */
    rig.style.transform = 'translate(0px,0px) scale(' + scale.toFixed(3) + ')';
    hideCap();
  }
  dock.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { e.preventDefault(); resetPos(); return; }
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); advance(); }
  });

  /* ---------- 周期眨眼（硬切；拖拽期间跳过本次并重排） ---------- */
  function scheduleBlink() {
    var wait = 4000 + Math.random() * 4000;
    setTimeout(function () {
      if (document.hidden || dragging) { scheduleBlink(); return; }
      svg.classList.add('blink');
      setTimeout(function () { svg.classList.remove('blink'); }, 130);
      scheduleBlink();
    }, wait);
  }
  scheduleBlink();

  /* ---------- 拖拽：dock 独占位移，window 级事件，数值落位 ---------- */
  var dragging = false, moved = false, pointerId = null;
  var startX = 0, startY = 0, baseL = 0, baseT = 0, curL = 0, curT = 0;
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(v, hi)); }
  /* 放大后视觉框比 dock 大：transform-origin:bottom center 时左右各外扩 (sw-w)/2、
     上方外扩 sh-h、底边不动。钳位按视觉框算，立绘才不会被视口裁掉。 */
  function bounds() {
    var w = dock.offsetWidth, h = dock.offsetHeight;
    var sw = w * scale, sh = h * scale;
    var padX = Math.max(0, (sw - w) / 2);
    var padY = Math.max(0, sh - h);
    return {
      minL: padX,
      maxL: Math.max(padX, window.innerWidth - sw + padX),
      minT: padY,
      maxT: Math.max(padY, window.innerHeight - h)
    };
  }
  function clampPos() {
    var l = parseFloat(dock.style.left), t = parseFloat(dock.style.top);
    if (isNaN(l) || isNaN(t)) return;
    var b = bounds();
    dock.style.left = clamp(l, b.minL, b.maxL) + 'px';
    dock.style.top = clamp(t, b.minT, b.maxT) + 'px';
  }
  function persist() {
    try { localStorage.setItem('mascotPos', JSON.stringify({ left: curL, top: curT })); }
    catch (e) {}
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

  dock.addEventListener('pointerdown', function (e) {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    e.preventDefault();
    dragging = true; moved = false; pointerId = e.pointerId;
    var r = dock.getBoundingClientRect();
    baseL = r.left; baseT = r.top; curL = baseL; curT = baseT;
    /* 一次性固化定位：从 right/bottom 切到 left/top，避免首帧跳位 */
    dock.style.left = baseL + 'px';
    dock.style.top = baseT + 'px';
    dock.style.right = 'auto';
    dock.style.bottom = 'auto';
    dock.style.transform = '';
    rig.style.transform = 'translate(0px,0px) scale(' + scale.toFixed(3) + ')';
    hideCap();
    if (raf) { cancelAnimationFrame(raf); raf = null; }
    tx = ty = cx = cy = 0;
    startX = e.clientX; startY = e.clientY;
    dock.classList.add('dragging');
    try { dock.setPointerCapture(e.pointerId); } catch (e2) {}
  });
  function onPointerMove(e) {
    if (!dragging) return;
    if (pointerId !== null && e.pointerId !== undefined && e.pointerId !== pointerId) return;
    var dx = e.clientX - startX, dy = e.clientY - startY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
    var b = bounds();
    curL = clamp(baseL + dx, b.minL, b.maxL);
    curT = clamp(baseT + dy, b.minT, b.maxT);
    dock.style.left = curL + 'px';
    dock.style.top = curT + 'px';
  }
  function onPointerUp(e) {
    if (!dragging) return;
    /* 失焦/取消等合成事件没有可靠 pointerId，视为强制收尾，不能卡死拖拽 */
    var forced = !e || e.pointerId === undefined || e.pointerId === null;
    if (!forced && pointerId !== null && e.pointerId !== pointerId) return;
    dragging = false;
    pointerId = null;
    dock.classList.remove('dragging');
    dock.style.left = curL + 'px';
    dock.style.top = curT + 'px';
    dock.style.transform = '';
    rig.style.transform = 'translate(0px,0px) scale(' + scale.toFixed(3) + ')';
    persist();
    if (moved) {
      suppressClick = true;
      setTimeout(function () { suppressClick = false; }, 120);
    }
    if (!forced && e && typeof e.pointerId === 'number') {
      try { dock.releasePointerCapture(e.pointerId); } catch (e2) {}
    }
  }
  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);
  window.addEventListener('blur', function () { if (dragging) onPointerUp(null); });

  /* ---------- 指针跟随：只写 rig 的 transform，拖拽期间冻结 ---------- */
  var tx = 0, ty = 0, cx = 0, cy = 0, raf = null;
  function loop() {
    cx += (tx - cx) * 0.12;
    cy += (ty - cy) * 0.12;
    rig.style.transform = rigTransform();
    if (Math.abs(tx - cx) > 0.1 || Math.abs(ty - cy) > 0.1) {
      raf = requestAnimationFrame(loop);
    } else { raf = null; }
  }
  window.addEventListener('mousemove', function (e) {
    if (dragging) return;
    var r = dock.getBoundingClientRect();
    var dx = (e.clientX - (r.left + r.width / 2)) / (window.innerWidth / 2);
    var dy = (e.clientY - (r.top + r.height / 2)) / (window.innerHeight / 2);
    tx = clamp(dx * 6, -6, 6);
    ty = clamp(dy * 6, -6, 6);
    if (!raf) raf = requestAnimationFrame(loop);
  }, { passive: true });

  /* 鼠标悬停在立绘上时滚轮缩放。wheel 必须 passive:false 才能阻止页面滚动；
     缩放后重新钳位，避免放大后的视觉框被视口边缘裁掉。 */
  dock.addEventListener('wheel', function (e) {
    e.preventDefault();
    var factor = e.deltaY < 0 ? 1.08 : 1 / 1.08;
    var next = clamp(scale * factor, MIN_SCALE, MAX_SCALE);
    if (Math.abs(next - scale) < 0.0005) return;
    scale = next;
    saveScale();
    rig.style.transform = rigTransform();
    clampPos();
    var l = parseFloat(dock.style.left), t = parseFloat(dock.style.top);
    if (!isNaN(l) && !isNaN(t)) { curL = l; curT = t; persist(); }
    updateHint();
  }, { passive: false });
})();
