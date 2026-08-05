/* ============================================================================
   v6-motion.js — 首页七个签名动效
   在 v6-premium.js 之后加载: 基础层(Lenis 惯性滚动 / 行遮罩标题 / 磁吸按钮)
   由它提供, 这里只做首页特有的七件事。

     ① 交互式流体扭曲     视觉 AI 带 —— 指针划过, 画面像水一样晕开
     ② 滚动驱动的场景切换 产品实拍带 —— 页面不跟着滑, 直接换成下一张界面
     ③ 滚动驱动 3D 环形轮播 四业务卡 —— 随滚轮从右往左绕轴旋转
     ④ 逐字错峰入场       hero 标题 —— 从下往上逐字进入
     ⑤ 滚动吸附           四业务卡 —— 不停在两张卡中间
     ⑥ 滚动叠层转场       护城河 → 案例 → 上线三步 —— 后一段盖上来
     ⑦ 滚动驱动全屏扩展   视觉 AI 带 —— 内嵌卡片撑满视口后变成另一个区域
     ⑧ 动态边界光束       AI 中枢卡 / 主 CTA —— 一束光沿边界跑
     ⑨ 动态思考球         AI 卡品牌行 / 运营流带 —— AI 真在想时球才急转

   约束:
   - 装饰层。正文永远不随滚动 scrub, 不靠动画才可读。
   - prefers-reduced-motion 下整个文件直接 return, 页面回到静态排版。
   - 重效果(①③⑤⑥⑦)只在 ≥900px + 精确指针时启用, 手机保持原有轻量排版。
     ⑧⑨ 是组件级的, 手机上照常显示。
   ========================================================================= */
(function(){
  if (!window.gsap || !window.ScrollTrigger) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  var DESKTOP = window.matchMedia('(min-width: 900px)').matches;
  var FINE    = window.matchMedia('(pointer: fine)').matches;
  var clamp   = gsap.utils.clamp;

  /* =======================================================================
     ④ 逐字错峰入场 — hero 标题从下往上逐字进入
     无障碍: 整句写进 aria-label, 拆出来的字全部 aria-hidden, 读屏读到的
     仍然是一句完整的话, 不是一串单字。
     ===================================================================== */
  var heroPlayed = false;

  function splitHeroTitles(){
    document.querySelectorAll('.hero-copy h1').forEach(function(h){
      if (h.classList.contains('v6-split')) return;
      var text = h.textContent.trim();
      if (!text) return;
      h.setAttribute('aria-label', text);
      /* 连续的拉丁字母/数字当成一个整体("AI" 不该被拆成 A / I), 其余逐字 */
      var tokens = text.match(/[A-Za-z0-9]+|\s+|[\s\S]/g) || [];
      h.innerHTML = tokens.map(function(tk){
        if (/^\s+$/.test(tk)) return tk;
        var safe = tk.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        return '<span class="v6-chm" aria-hidden="true"><span class="v6-ch">' + safe + '</span></span>';
      }).join('');
      h.classList.add('v6-split');
      gsap.set(h.querySelectorAll('.v6-ch'), { yPercent: 120, opacity: 0 });
    });
  }

  function playHeroTitles(){
    if (heroPlayed) return;
    heroPlayed = true;
    document.querySelectorAll('.hero-copy h1.v6-split').forEach(function(h){
      gsap.to(h.querySelectorAll('.v6-ch'), {
        yPercent: 0, opacity: 1, duration: .95, ease: 'expo.out', stagger: .035
      });
    });
  }

  splitHeroTitles();
  /* 有开场序章就等它让位再逐字, 没有就立刻播; 兜底定时器保证任何情况下都不会
     停在「字全藏着」的状态 —— 入场动画不能变成内容的门。 */
  if (document.getElementById('v6Intro')) {
    window.addEventListener('v6:enter', playHeroTitles, { once: true });
    setTimeout(playHeroTitles, 8200);
  } else {
    playHeroTitles();
  }

  /* =======================================================================
     ③ + ⑤  四业务卡: 3D 环形轮播 + 滚动吸附
     卡片在 pin 住的横向轨道上从右往左走, 同时绕 Y 轴旋转 —— 靠近视口中心
     角度回正、变亮、走到最前; 两侧的卡后仰、后退、变暗, 像绕着一个筒。
     滚动停下后吸附到最近一张整卡, 不会卡在两张之间。
     ===================================================================== */
  function initBizRing(){
    var grid = document.querySelector('.biz-grid');
    var sec  = document.querySelector('.biz');
    if (!grid || !sec || !DESKTOP) return;

    var cards = gsap.utils.toArray(grid.children);
    if (!cards.length) return;
    /* 卡片交给滚动轨道接管, 撤掉 IntersectionObserver 的进场类 */
    cards.forEach(function(c){ c.classList.remove('v6-reveal'); c.classList.add('in'); });
    sec.classList.add('biz-h', 'is-ring');

    function travel(){ return Math.max(0, grid.scrollWidth - grid.clientWidth); }

    /* 环形姿态: n 是卡片中心相对视口中心的归一化偏移 (-1 左 … 0 中 … 1 右) */
    function paint(){
      var vc = window.innerWidth / 2;
      cards.forEach(function(c){
        var r = c.getBoundingClientRect();
        var n = clamp(-1, 1, ((r.left + r.width / 2) - vc) / (window.innerWidth * 0.62));
        var a = Math.abs(n);
        gsap.set(c, {
          rotationY: -n * 34,
          z:        -a * 300,
          scale:    1 - a * 0.07,
          opacity:  1 - a * 0.20,
          filter:   'brightness(' + (1.05 - a * 0.30).toFixed(3) + ')'
        });
        var im = c.querySelector('img');
        if (im) gsap.set(im, { xPercent: clamp(-5, 5, n * -7) });
      });
    }
    function relax(){
      gsap.to(cards, { rotationY: 0, z: 0, scale: 1, opacity: 1,
        filter: 'brightness(1)', duration: .5, ease: 'power2.out' });
      cards.forEach(function(c){
        var im = c.querySelector('img');
        if (im) gsap.to(im, { xPercent: 0, duration: .5, ease: 'power2.out' });
      });
    }

    /* --- ⑤ 吸附点: 每张卡「正好居中」时对应的进度 ---------------------- */
    /* 测量必须在中性姿态下做 —— paint() 施加的 rotationY/scale 会污染
       getBoundingClientRect, 拿污染过的宽度算出来的吸附点会整体偏移。 */
    var snaps = [];
    function computeSnaps(){
      var t = travel();
      if (!t) { snaps = [0]; return; }
      gsap.set(cards, { rotationY: 0, z: 0, scale: 1, opacity: 1, filter: 'none' });
      var gx = gsap.getProperty(grid, 'x') || 0;
      var vc = window.innerWidth / 2;
      snaps = cards.map(function(c){
        var r = c.getBoundingClientRect();
        var centerAtZero = (r.left + r.width / 2) - gx;   /* 轨道未平移时的卡心 */
        return clamp(0, 1, (centerAtZero - vc) / t);      /* x = -p*t 时居中 */
      });
      paint();
    }

    var snapTimer = null, snapping = false;
    function requestSnap(self){
      if (snapping) return;
      if (snapTimer) clearTimeout(snapTimer);
      snapTimer = setTimeout(function(){
        if (snapping || !self.isActive || snaps.length < 2) return;
        var p = self.progress;
        var best = snaps.reduce(function(a, b){
          return Math.abs(b - p) < Math.abs(a - p) ? b : a;
        });
        if (Math.abs(best - p) < 0.004) return;           /* 已经对齐, 别抖 */
        var target = self.start + (self.end - self.start) * best;
        snapping = true;
        var done = function(){ snapping = false; };
        /* Lenis 接管了滚动, 必须用它自己的 scrollTo, 否则两套滚动互相拉扯 */
        if (window.__v6lenis) {
          window.__v6lenis.scrollTo(target, {
            duration: .55,
            easing: function(x){ return 1 - Math.pow(1 - x, 3); },
            onComplete: done
          });
          setTimeout(done, 900);                          /* onComplete 兜底 */
        } else {
          window.scrollTo({ top: target, behavior: 'smooth' });
          setTimeout(done, 700);
        }
      }, 150);
    }

    gsap.to(grid, {
      x: function(){ return -travel(); }, ease: 'none',
      scrollTrigger: {
        trigger: sec, start: 'top top',
        end: function(){ return '+=' + (travel() + 220); },
        pin: true, scrub: 0.8, anticipatePin: 1, invalidateOnRefresh: true,
        onUpdate:  function(self){ paint(); requestSnap(self); },
        onRefresh: computeSnaps,
        onLeave: relax, onLeaveBack: relax
      }
    });
    computeSnaps();
  }

  /* =======================================================================
     ② 滚动驱动的场景切换 — 产品实拍带
     三张真实界面叠在同一个舞台上。滚轮下滑时页面被 pin 住不动, 内容直接
     切到下一张 —— 看的是"作品", 不是"页面在走"。
     ===================================================================== */
  function initShotsStage(){
    var shots = document.querySelector('.shots');
    if (!shots || !DESKTOP) return;
    var figs = gsap.utils.toArray(shots.querySelectorAll('.srow figure'));
    if (figs.length < 2) return;

    shots.classList.add('is-stage');
    /* v6.js 的变体分配给 .srow figure 挂了 rv-tilt 进场类(默认 opacity:0 +
       位移), 会和舞台自己的显隐互相抵消 —— 舞台接管后必须先摘掉。 */
    figs.forEach(function(f){
      f.className = f.className.replace(/(^|\s)(v6-reveal|rv-[a-z]+)(?=\s|$)/g, '').trim();
      f.style.transitionDelay = '';
    });

    var rail = document.createElement('div');
    rail.className = 'shots-rail';
    rail.setAttribute('aria-hidden', 'true');
    figs.forEach(function(_, i){
      var b = document.createElement('b');
      b.textContent = (i + 1 < 10 ? '0' : '') + (i + 1);
      rail.appendChild(b);
    });
    shots.appendChild(rail);
    var pips = rail.querySelectorAll('b');

    var cur = -1;
    function show(i){
      if (i === cur) return;
      cur = i;
      figs.forEach(function(f, k){ f.classList.toggle('on', k === i); });
      pips.forEach(function(b, k){ b.classList.toggle('on', k === i); });
    }
    show(0);

    ScrollTrigger.create({
      trigger: shots, start: 'center center',
      end: '+=' + (figs.length * 72) + '%',
      pin: true, scrub: true, anticipatePin: 1,
      onUpdate: function(self){
        show(Math.min(figs.length - 1, Math.floor(self.progress * figs.length)));
      }
    });
  }

  /* =======================================================================
     ⑥ 滚动叠层转场 — 护城河 → 案例 → 上线三步
     当前这段不划走: 它 sticky 在视口顶, 后一段带着圆角和投影直接盖上来,
     被盖住的那段同步后退变暗, 像一叠卡片。
     ===================================================================== */
  function initStack(){
    var stack = document.querySelector('.v6-stack');
    if (!stack || !DESKTOP) return;
    var secs = gsap.utils.toArray(stack.children);
    if (secs.length < 2) return;

    secs.forEach(function(s, i){
      s.style.zIndex = i + 1;                    /* 后面的盖前面的 */
      if (i === secs.length - 1) return;
      gsap.to(s, {
        scale: .94, opacity: .42, transformOrigin: '50% 26%', ease: 'none',
        scrollTrigger: {
          trigger: secs[i + 1], start: 'top bottom', end: 'top top', scrub: true
        }
      });
    });
  }

  /* =======================================================================
     ⑦ 滚动驱动的全屏扩展转场 — 视觉 AI 带
     一开始是页面里一张内嵌的圆角卡片, 随滚动逐渐放大, 撑满整个视口之后
     识别框、扫描线、文案才浮现 —— 卡片"变成"了一个新区域。
     用 clip-path 的 inset 做扩展: 不改 width/height 就不会触发重排。
     ===================================================================== */
  function initBandExpand(){
    var band = document.querySelector('.band');
    if (!band || !DESKTOP) return;

    band.classList.add('is-expand');
    gsap.set(band, { '--exp': 1 });

    var copy = band.querySelectorAll('.bcopy > *');
    var dets = band.querySelectorAll('.bdet');
    var scan = band.querySelector('.bscan');
    gsap.set(copy, { opacity: 0, y: 36 });
    gsap.set(dets, { opacity: 0, scale: .86 });
    if (scan) gsap.set(scan, { opacity: 0 });

    var tl = gsap.timeline({
      scrollTrigger: {
        trigger: band, start: 'top top', end: '+=170%',
        pin: true, scrub: .7, anticipatePin: 1
      }
    });
    /* 0 → .45 撑满; .42 起识别框咬合; .5 起文案逐条浮现; 末段留白让人读完 */
    tl.to(band, { '--exp': 0, duration: .45, ease: 'power2.inOut' }, 0)
      .to(dets, { opacity: 1, scale: 1, duration: .20, stagger: .06, ease: 'power3.out' }, .42)
      .to(scan, { opacity: 1, duration: .15 }, .45)
      .to(copy, { opacity: 1, y: 0, duration: .28, stagger: .07, ease: 'power3.out' }, .50)
      .to({}, { duration: .28 }, .72);
  }

  /* =======================================================================
     ① 交互式流体扭曲 — 视觉 AI 带的水波透镜
     指针处开一个圆形"透镜", 里面是同一张底图的副本, 经 SVG feDisplacementMap
     用分形噪声推挤 —— 于是画面在指针周围像水一样晕开。副本与底图共用
     `.bmask img` 的全部规则并放在同一个容器盒里, 逐像素对齐, 所以看到的
     不是多了一个圆, 而是圆里的画面被搅动。
     滤镜只作用在 ~400px 见方的区域; 指针离开就停, 不在后台空转。
     ===================================================================== */
  function initFluidLens(){
    var band = document.querySelector('.band');
    if (!band || !DESKTOP || !FINE) return;
    var mask = band.querySelector('.bmask');
    var base = mask && mask.querySelector('img');
    if (!mask || !base) return;

    var defs = document.createElement('div');
    defs.className = 'v6-fluid-defs';
    defs.setAttribute('aria-hidden', 'true');
    defs.innerHTML =
      '<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0"><defs>' +
        '<filter id="v6Liquid" x="-25%" y="-25%" width="150%" height="150%" ' +
                'color-interpolation-filters="sRGB">' +
          '<feTurbulence id="v6LiquidNoise" type="fractalNoise" ' +
            'baseFrequency="0.0140 0.0220" numOctaves="2" seed="9" result="n"/>' +
          '<feDisplacementMap id="v6LiquidDisp" in="SourceGraphic" in2="n" ' +
            'scale="0" xChannelSelector="R" yChannelSelector="G"/>' +
        '</filter>' +
      '</defs></svg>';
    document.body.appendChild(defs);
    var disp  = defs.querySelector('#v6LiquidDisp');
    var noise = defs.querySelector('#v6LiquidNoise');

    var R = 200;
    var lens = document.createElement('div');
    lens.className = 'v6-lens';
    lens.setAttribute('aria-hidden', 'true');
    lens.style.width = lens.style.height = (R * 2) + 'px';

    var src = document.createElement('div');
    src.className = 'lens-src';
    var clone = base.cloneNode(true);
    clone.alt = '';
    clone.setAttribute('aria-hidden', 'true');
    clone.removeAttribute('loading');
    clone.removeAttribute('id');
    src.appendChild(clone);

    var tint = document.createElement('div');
    tint.className = 'lens-tint';
    lens.appendChild(src);
    lens.appendChild(tint);
    mask.appendChild(lens);

    /* 副本容器必须与底图容器等大, 内部 img 才会命中同一套 object-fit 规则 */
    function sizeSrc(){
      var r = mask.getBoundingClientRect();
      src.style.width  = r.width + 'px';
      src.style.height = r.height + 'px';
    }
    sizeSrc();
    window.addEventListener('resize', sizeSrc);
    ScrollTrigger.addEventListener('refresh', sizeSrc);

    var st = { x: -9999, y: -9999 };
    function paint(){
      lens.style.transform = 'translate3d(' + (st.x - R) + 'px,' + (st.y - R) + 'px,0)';
      src.style.transform  = 'translate3d(' + (R - st.x) + 'px,' + (R - st.y) + 'px,0)';
    }
    var qx = gsap.quickTo(st, 'x', { duration: .42, ease: 'power3.out', onUpdate: paint });
    var qy = gsap.quickTo(st, 'y', { duration: .42, ease: 'power3.out' });

    /* 扭曲强度跟着指针速度走: 停下来水面渐平, 划得快搅得狠 */
    var ds = { s: 0 };
    var qs = gsap.quickTo(ds, 's', { duration: .5, ease: 'power2.out',
      onUpdate: function(){ disp.setAttribute('scale', ds.s.toFixed(1)); } });

    /* 静止时的自流动: 噪声场缓慢漂移, 水面不会死住 */
    var nf = { a: 0.0140, b: 0.0220 };
    var wobble = gsap.to(nf, {
      a: 0.0215, b: 0.0155, duration: 6, ease: 'sine.inOut',
      yoyo: true, repeat: -1, paused: true,
      onUpdate: function(){
        noise.setAttribute('baseFrequency', nf.a.toFixed(4) + ' ' + nf.b.toFixed(4));
      }
    });

    var lastX = 0, lastY = 0, primed = false;
    band.addEventListener('pointermove', function(e){
      var r = mask.getBoundingClientRect();
      var x = e.clientX - r.left, y = e.clientY - r.top;
      if (!primed) {                 /* 第一帧别从屏幕外飞进来 */
        primed = true;
        st.x = x; st.y = y; paint();
        gsap.set(st, { x: x, y: y });
      }
      var speed = Math.hypot(x - lastX, y - lastY);
      lastX = x; lastY = y;
      qx(x); qy(y);
      qs(clamp(16, 52, 16 + speed * 1.7));
    });
    band.addEventListener('pointerenter', function(){
      lens.style.opacity = '1';
      wobble.play();
    });
    band.addEventListener('pointerleave', function(){
      lens.style.opacity = '0';
      primed = false;
      qs(0);
      gsap.delayedCall(.45, function(){ wobble.pause(); });
    });
  }

  /* =======================================================================
     ⑧ 动态边界光束
     只给三个「AI 正在待命」的表面加光束, 不是每张卡都发光 —— 满屏都在闪等于
     没有重点。样式全在 CSS, 这里只负责挂类。
     ===================================================================== */
  function initBeams(){
    [
      ['.ai-card',           ''],              /* AI 中枢卡: 常转 */
      ['.preview-card',      ''],              /* AI 页写操作预览卡 */
      ['.hero-cta .v6-btn',  'v6-beam--pulse'] /* 首屏主 CTA: 脉搏式 */
    ].forEach(function(pair){
      document.querySelectorAll(pair[0]).forEach(function(el){
        el.classList.add('v6-beam');
        if (pair[1]) el.classList.add(pair[1]);
      });
    });
  }

  /* =======================================================================
     ⑨ 动态思考球
     AI 卡的球挂在 v6ChatLoop 真实的「正在输入」气泡上: 它出现球就急转, 它被
     替换成答案球就回到常态 —— 动效表示的是真状态, 不是常年空转的装饰。
     ===================================================================== */
  function makeOrb(){
    var o = document.createElement('span');
    o.className = 'v6-orb';
    o.setAttribute('aria-hidden', 'true');
    o.innerHTML = '<i></i><i></i><i></i>';
    return o;
  }

  function initOrbs(){
    var brand = document.querySelector('.ai-card .brandline');
    if (brand) {
      var orb = makeOrb();
      brand.insertBefore(orb, brand.firstChild);
      var card = brand.closest('.ai-card');
      if (card && window.MutationObserver) {
        new MutationObserver(function(){
          orb.classList.toggle('thinking', !!card.querySelector('.v6-typing'));
        }).observe(card, { childList: true, subtree: true });
      }
    }
    var ops = document.querySelector('.opsband');
    if (ops && DESKTOP) {
      var big = makeOrb();
      big.classList.add('v6-orb--ambient');
      ops.insertBefore(big, ops.firstChild);
    }
  }

  /* ---- 启动 ------------------------------------------------------------ */
  /* 必须等到 DOMContentLoaded 之后再建。v6.js 把 v6RevealVariants() 挂在
     DOMContentLoaded 上, 它会给 `.srow figure` 补上 v6-reveal + rv-tilt;
     本文件是同步执行的, 早于那一步 —— 在这之前摘掉的进场类会被原样加回来,
     实拍带舞台的显隐就多出一个抢同一批属性的所有者。
     v6.js 的监听先注册, 所以同一事件里它一定先跑, 这里拿到的是最终态。 */
  function boot(){
    initBizRing();
    initShotsStage();
    initBandExpand();
    initFluidLens();
    initStack();
    initBeams();
    initOrbs();

    /* 上面新增了 pin 与 sticky, 之前建好的触发器(含 v6-premium 的离场层)起止
       位置全部作废 —— 排序后整体重算一次。图片解码完尺寸还会变, load 再算。 */
    ScrollTrigger.sort();
    ScrollTrigger.refresh();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
  window.addEventListener('load', function(){ ScrollTrigger.refresh(); });
})();
