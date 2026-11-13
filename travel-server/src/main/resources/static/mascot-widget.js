/* ==========================================================================
   浮动看板娘 · 游小满 v4.1（前端组件）
   --------------------------------------------------------------------------
   自包含：脚本注入所需样式，挂在任何页面都能用，不依赖 tokens.css 是否已加载。
   行为：
     · 周期眨眼（m-blink 差分帧，约 4–8s 一次，120ms）
     · 点击循环四段情绪 idle → 看你了(user) → 哼(annoyed) → 生气(angry) → idle
       idle/user 点头，annoyed/angry 抖动
     · 指针跟随：整个浮窗朝光标方向轻微倾斜（±10px，rAF 缓动）
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
    '.mascot-dock{position:fixed;right:18px;bottom:18px;z-index:60;width:118px;height:150px;' +
    'cursor:pointer;filter:drop-shadow(0 10px 20px rgba(0,0,0,.18));transition:transform .25s cubic-bezier(.16,1,.3,1);}' +
    '.mascot-dock:focus-visible{outline:2px solid var(--clay,#B4542A);outline-offset:4px;}' +
    '.mascot-dock .mascot-svg{position:relative;display:block;width:100%;height:100%;}' +
    '.mascot-dock .mascot-svg img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;transition:opacity .24s ease;}' +
    '.mascot-dock .m-user,.mascot-dock .m-pass,.mascot-dock .m-blink,.mascot-dock .m-annoyed,.mascot-dock .m-angry{opacity:0;}' +
    '.mascot-dock .mascot-svg.look-user .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-user .m-user{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-pass .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-pass .m-pass{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-annoyed .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-annoyed .m-annoyed{opacity:1;}' +
    '.mascot-dock .mascot-svg.look-angry .m-idle{opacity:0;}.mascot-dock .mascot-svg.look-angry .m-angry{opacity:1;}' +
    '.mascot-dock .mascot-svg .m-blink{transition:opacity .09s ease;}' +
    '.mascot-dock .mascot-svg.blink .m-idle{opacity:0;transition:opacity .09s ease;}.mascot-dock .mascot-svg.blink .m-blink{opacity:1;}' +
    '.mascot-cap{position:absolute;right:6px;bottom:calc(100% + 8px);max-width:240px;padding:8px 12px;' +
    'border-radius:14px;border:1px solid var(--pine-line,#BBD0C4);background:var(--surface,#fff);' +
    'color:var(--ink,#20241E);font-size:12px;line-height:1.6;box-shadow:var(--shadow-2,0 4px 14px rgba(0,0,0,.1));' +
    'opacity:0;transform:translateY(6px);pointer-events:none;transition:opacity .2s ease,transform .2s ease;}' +
    '.mascot-cap.show{opacity:1;transform:translateY(0);}' +
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
  dock.setAttribute('aria-label', '看板娘 游小满');

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
    svg.appendChild(img);
  });

  dock.appendChild(cap);
  dock.appendChild(svg);
  document.body.appendChild(dock);

  /* ---------- 台词气泡显隐 ---------- */
  var capTimer = null;
  function showCap(text, sticky) {
    cap.textContent = text;
    cap.classList.add('show');
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
  dock.addEventListener('click', advance);
  dock.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); advance(); }
  });

  /* ---------- 周期眨眼 ---------- */
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

  /* ---------- 指针跟随（整窗轻微倾斜） ---------- */
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
    var r = dock.getBoundingClientRect();
    var dx = (e.clientX - (r.left + r.width / 2)) / (window.innerWidth / 2);
    var dy = (e.clientY - (r.top + r.height / 2)) / (window.innerHeight / 2);
    tx = Math.max(-10, Math.min(10, dx * 10));
    ty = Math.max(-10, Math.min(10, dy * 10));
    if (!raf) raf = requestAnimationFrame(loop);
  }, { passive: true });

  /* 首次进场稍候提示 */
  setTimeout(function () { showCap(CAP.idle); }, 900);
})();
