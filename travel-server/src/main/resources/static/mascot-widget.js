/* ==========================================================================
   浮动看板娘 · 游小满 v4.3.1（前端组件）
   --------------------------------------------------------------------------
   自包含：脚本注入所需样式，挂在任何页面都能用，不依赖 tokens.css 是否已加载。

   ★ v4.3 定调：她始终是【完整立绘】。没有头像档、没有徽章档、不会缩小，
     点击永远不会改变尺寸或裁切身体。要避开地图，靠拖动（位置记忆），不靠变形。
     v4.2 的 compact 圆形裁切会把腿裁掉，是设计错误，已整体移除。
     v4.3.1 修差分帧叠帧：基础 img 规则特异性压过隐藏规则，导致 6 帧全不透明
     叠在一起（闪烁 + 表情永远显示最后一帧），显隐规则统一加 img.m-* 前缀。
     另加 Esc 一键归位：旧版误拖到地图中央的位置会被记忆，聚焦后按 Esc 可清掉
     位置记忆并回到右下预留位，避免立绘长期盖住地图点位。

   通道分层（解决「只有脚能拖 / 拖完闪烁回弹」）：
     dock = 定位层。left/top 落位，拖拽期间独占位移。
     rig  = 指针跟随层。独占 transform 轻微倾斜，拖拽期间冻结清零。
     svg  = 表情层。独占 animation（呼吸/点头/抖动），拖拽期间 animation:none。
     img  = 差分帧。opacity 硬切，不做交叉淡入（半透明叠帧就是闪烁源）。
   所有 img 设 pointer-events:none，事件直接落到 dock 全矩形；
   pointermove/up/cancel 挂 window，配合 setPointerCapture 双保险。
   落位用数值变量，不再正则解析 transform 字符串（解析失败会弹回原位）。

   不遮地图：不自动弹气泡（首帧/情绪切换都不弹），不显示任何白色浮层；
   立绘 96x124 停靠右下，可拖到任意位置并写入 localStorage 记忆。
   拖拽期间冻结眨眼调度、情绪切换与指针跟随，松手一次性恢复。

   契约（勿改名，登录页 / kanban.html 共用）：
     类名 .mascot-svg + .m-idle/.m-user/.m-pass/.m-blink/.m-annoyed/.m-angry；
     切换类 .look-user/.look-pass/.look-annoyed/.look-angry；
     资源 /mascot/mascot-v4-front-*.png；localStorage 键 mascotPos；
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
    /* rig：指针跟随层，独占 transform */
    '.mascot-rig{position:absolute;inset:0;will-change:transform;}',
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
    'animation:none!important;transition:none!important;}}'
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
  dock.setAttribute('aria-label', '看板娘 游小满（点击换情绪，拖动挪位，按 Esc 回到角落）');
  dock.title = '拖动挪位 · 点击换表情 · 按 Esc 回到角落（立绘大小不变）';

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
  document.body.appendChild(dock);

  /* ---------- 情绪：单击永远只换情绪，绝不改变尺寸/形态 ---------- */
  var idx = 0;
  function setMood(mood) {
    if (dragging) return;
    svg.classList.remove('look-user', 'look-pass', 'look-annoyed', 'look-angry');
    if (mood !== 'idle') svg.classList.add('look-' + mood);
    svg.classList.remove('nod', 'shake');
    /* 强制重排让连点时动画每次都重新起跳，而不是卡在上一帧 */
    void svg.offsetWidth;
    svg.classList.add(ANIM[mood]);
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
    rig.style.transform = '';
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
  function clampPos() {
    var l = parseFloat(dock.style.left), t = parseFloat(dock.style.top);
    if (isNaN(l) || isNaN(t)) return;
    dock.style.left = clamp(l, 0, Math.max(0, window.innerWidth - dock.offsetWidth)) + 'px';
    dock.style.top = clamp(t, 0, Math.max(0, window.innerHeight - dock.offsetHeight)) + 'px';
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
    rig.style.transform = '';
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
    curL = clamp(baseL + dx, 0, Math.max(0, window.innerWidth - dock.offsetWidth));
    curT = clamp(baseT + dy, 0, Math.max(0, window.innerHeight - dock.offsetHeight));
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
    rig.style.transform = '';
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
    rig.style.transform = 'translate(' + cx.toFixed(2) + 'px,' + cy.toFixed(2) + 'px)';
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
})();
