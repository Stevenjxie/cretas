/* V6 premium motion layer — Lenis + GSAP ScrollTrigger.
   Foundations: inertia smooth-scroll, line-mask headings, magnetic CTAs, card tilt.
   Signatures (one per page, each different):
     home    — card deck fans out under pin; band materializes; marquee flows
     factory — vision section pinned 3-chapter story; trace pulse scroll-driven
     restau  — attribution ladder drills down with scroll
     logi    — truck position bound to scroll progress
     ai      — capability wall ripples in from center
     custom  — manifesto reveals char-by-char; ledger rows alternate sides
   Decorative layers only; body copy never scrubs. reduced-motion: none of this runs. */
(function(){
  if (!window.gsap || !window.ScrollTrigger) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  gsap.registerPlugin(ScrollTrigger);
  var DESKTOP = window.matchMedia('(min-width: 900px)').matches;
  var FINE = window.matchMedia('(pointer: fine)').matches;

  /* ============ Lenis smooth scroll (the premium-feel foundation) ========= */
  if (window.Lenis) {
    var lenis = new Lenis({ autoRaf: false, lerp: 0.1 });
    gsap.ticker.add(function(t){ lenis.raf(t * 1000); });
    gsap.ticker.lagSmoothing(0);
    lenis.on('scroll', ScrollTrigger.update);
    window.__v6lenis = lenis;
    if (document.documentElement.classList.contains('v6-lock')) lenis.stop();
  }

  /* ============ Foundations ============================================== */

  /* Line-mask heading reveal (all section h2, incl. <br> multi-line) */
  document.querySelectorAll('h2:not(.no-lines)').forEach(function(h){
    if (h.closest('.v6-nav,.v6-foot')) return;
    var parts = h.innerHTML.split(/<br\s*\/?>/i);
    h.innerHTML = parts.map(function(p){
      return '<span class="v6-lm"><span class="v6-li">' + p + '</span></span>';
    }).join('');
    h.classList.add('v6-lines');
    h.classList.remove('v6-reveal');
    gsap.from(h.querySelectorAll('.v6-li'), {
      yPercent: 115, duration: .7, ease: 'power4.out', stagger: .08,
      scrollTrigger: { trigger: h, start: 'top 88%', once: true }
    });
  });

  /* Magnetic CTAs (desktop) */
  if (FINE) document.querySelectorAll('.v6-btn').forEach(function(btn){
    var xTo = gsap.quickTo(btn, 'x', {duration:.4, ease:'power3.out'});
    var yTo = gsap.quickTo(btn, 'y', {duration:.4, ease:'power3.out'});
    btn.addEventListener('pointermove', function(e){
      var r = btn.getBoundingClientRect();
      xTo((e.clientX - r.left - r.width/2) * .3);
      yTo((e.clientY - r.top - r.height/2) * .35);
    });
    btn.addEventListener('pointerleave', function(){
      gsap.to(btn, {x:0, y:0, duration:.7, ease:'elastic.out(1,.45)'});
    });
  });

  /* Photo-card 3D tilt (desktop) */
  if (FINE) document.querySelectorAll('.v6-photo-card').forEach(function(card){
    var rx = gsap.quickTo(card, 'rotationX', {duration:.5, ease:'power3.out'});
    var ry = gsap.quickTo(card, 'rotationY', {duration:.5, ease:'power3.out'});
    gsap.set(card, {transformPerspective: 900});
    card.addEventListener('pointermove', function(e){
      var r = card.getBoundingClientRect();
      ry(((e.clientX - r.left) / r.width - .5) * 6);
      rx(-((e.clientY - r.top) / r.height - .5) * 6);
    });
    card.addEventListener('pointerleave', function(){ rx(0); ry(0); });
    var img = card.querySelector('img');
    if (img) {
      card.addEventListener('pointerenter', function(){ gsap.to(img, {scale: 1.24, duration: .8, ease: 'power3.out'}); });
      card.addEventListener('pointerleave', function(){ gsap.to(img, {scale: 1.18, duration: .8, ease: 'power3.out'}); });
    }
  });

  /* Hero image settle + drift; copy drifts away; full-bleed band drift */
  document.querySelectorAll('.v6-hero-mask img').forEach(function(img){
    gsap.fromTo(img, { scale: 1.14, yPercent: -5 }, { scale: 1, yPercent: 9, ease: 'none',
      scrollTrigger: { trigger: img.closest('.v6-hero'), start: 'top top', end: 'bottom top', scrub: 0.8 } });
  });
  document.querySelectorAll('.v6-hero .hero-copy').forEach(function(copy){
    gsap.to(copy, { yPercent: -34, opacity: 0.1, ease: 'none',
      scrollTrigger: { trigger: copy.closest('.v6-hero'), start: 'top top', end: 'bottom 22%', scrub: 0.8 } });
  });
  document.querySelectorAll('.v6-photo-card img').forEach(function(img){
    gsap.set(img, { scale: 1.18 });
    gsap.fromTo(img, { yPercent: -9 }, { yPercent: 9, ease: 'none',
      scrollTrigger: { trigger: img.closest('.v6-photo-card'), start: 'top bottom', end: 'bottom top', scrub: 0.8 } });
  });
  document.querySelectorAll('.band .bmask img').forEach(function(img){
    gsap.fromTo(img, { yPercent: -9 }, { yPercent: 9, ease: 'none',
      scrollTrigger: { trigger: img.closest('.band'), start: 'top bottom', end: 'bottom top', scrub: 0.8 } });
  });

  /* Pointer spotlight in heroes (desktop) */
  if (FINE) document.querySelectorAll('.hero-wrap, .ai-hero').forEach(function(hero){
    var spot = document.createElement('div');
    spot.className = 'v6-spot';
    hero.appendChild(spot);
    var tx=0, ty=0, cx=0, cy=0, raf=null, inside=false;
    function tick(){
      cx += (tx-cx)*.08; cy += (ty-cy)*.08;
      spot.style.transform = 'translate3d(' + cx + 'px,' + cy + 'px,0)';
      if (Math.abs(tx-cx) > .5 || Math.abs(ty-cy) > .5 || inside) raf = requestAnimationFrame(tick);
      else raf = null;
    }
    hero.addEventListener('pointermove', function(e){
      var r = hero.getBoundingClientRect();
      tx = e.clientX - r.left - 300; ty = e.clientY - r.top - 300;
      inside = true; spot.style.opacity = 1;
      if (!raf) raf = requestAnimationFrame(tick);
    });
    hero.addEventListener('pointerleave', function(){ inside = false; spot.style.opacity = 0; });
  });

  /* Pixel-eye particle canvas on heroes that ask for it */
  if (window.v6Eye) document.querySelectorAll('[data-v6-eye]').forEach(function(host){
    window.v6Eye(host, { gap: host.classList.contains('ai-hero') ? 34 : 30 });
  });

  /* ============ HOME — pinned horizontal gallery ========================= */
  var bizGrid = document.querySelector('.biz-grid');
  if (bizGrid && DESKTOP) {
    var cards = gsap.utils.toArray(bizGrid.children);
    cards.forEach(function(c){ c.classList.remove('v6-reveal'); c.classList.add('in'); });
    document.querySelector('.biz').classList.add('biz-h');
    var travel = function(){ return Math.max(0, bizGrid.scrollWidth - bizGrid.clientWidth); };
    function spotlight(){
      /* the card nearest viewport center is "lit"; others recede slightly */
      var vc = window.innerWidth / 2;
      cards.forEach(function(c){
        var r = c.getBoundingClientRect();
        var off = (r.left + r.width/2) - vc;
        var d = Math.min(1, Math.abs(off) / (window.innerWidth * .7));
        gsap.set(c, { scale: 1 - d * .06, filter: 'brightness(' + (1.04 - d * .18) + ')' });
        var im = c.querySelector('img');
        if (im) gsap.set(im, { xPercent: Math.max(-5, Math.min(5, off / window.innerWidth * -10)) });
      });
    }
    gsap.to(bizGrid, {
      x: function(){ return -travel(); }, ease: 'none',
      scrollTrigger: {
        trigger: '.biz', start: 'top top', end: function(){ return '+=' + (travel() + 200); },
        pin: true, scrub: 0.8, invalidateOnRefresh: true,
        onUpdate: spotlight, onRefresh: spotlight
      }
    });
    spotlight();
  }
  var band = document.querySelector('.band');
  if (band) {
    gsap.fromTo(band, { scale: .94, borderRadius: '28px' }, { scale: 1, borderRadius: '0px', ease: 'none',
      scrollTrigger: { trigger: band, start: 'top 85%', end: 'top 25%', scrub: 0.8 } });
    gsap.from(band.querySelectorAll('.bcopy > *'), { y: 26, opacity: 0, duration: .8,
      ease: 'power4.out', stagger: .09,
      scrollTrigger: { trigger: band, start: 'top 55%', once: true } });
  }

  /* ============ FACTORY — pinned 3-chapter vision story ================== */
  var stage = document.querySelector('.eye-stage');
  if (stage && DESKTOP) {
    var cap = stage.querySelector('.eye-hero-cap');
    var d1 = stage.querySelector('.det.d1 span');
    var d2 = stage.querySelector('.det.d2 span');
    var d2box = stage.querySelector('.det.d2');
    var CH = [
      { t:'① 认得出工序', d:'画面里正在做哪道工序，AI 自己判断 — 装盘、分割、包装，11 类工序无需人工登记，产线节奏第一次自动留痕。',
        m:['11 类工序自动识别','无需人工登记','节奏留痕'], a:'工人 · 装盘工序', b:'工序置信度 97.6%', warn:false },
      { t:'② 数得清动作', d:'每一次装盘、每一件出品，AI 从画面里数出来当产量。谁在干活谁在空，节拍多少秒一件，车间产能看得见。',
        m:['动作计件','干活 / 空闲','节拍 6.2s/件','效率评分'], a:'出品 ×12 · 已计件', b:'在岗 4 · 空闲 1', warn:false },
      { t:'③ 盯得住合规', d:'帽子、口罩、手套没戴齐，AI 当场识别当场记录；画面异物实时报警 — 食品安全的红线，不靠人盯。',
        m:['穿戴合规检测','异物检测','当场记录'], a:'未戴手套 · 已记录', b:'合规复查已推送', warn:true }
    ];
    var cur = -1;
    function chapter(i){
      if (i === cur || !cap) return;
      cur = i;
      var c = CH[i];
      gsap.to(cap, { opacity: 0, y: 10, duration: .22, ease: 'power2.in', onComplete: function(){
        cap.querySelector('.t').textContent = c.t;
        cap.querySelector('.d').textContent = c.d;
        cap.querySelector('.metrics').innerHTML = c.m.map(function(x){ return '<span>' + x + '</span>'; }).join('');
        if (d1) d1.textContent = c.a;
        if (d2) d2.textContent = c.b;
        if (d2box) d2box.classList.toggle('warn', c.warn);
        gsap.to(cap, { opacity: 1, y: 0, duration: .38, ease: 'power3.out' });
      }});
    }
    ScrollTrigger.create({
      trigger: stage, start: 'top 12%', end: '+=140%', pin: true, scrub: true,
      onUpdate: function(self){ chapter(Math.min(2, Math.floor(self.progress * 3))); }
    });
    chapter(0);
  }

  /* Factory trace chain: pulse follows scroll, then free-runs */
  var pins = document.querySelectorAll('.trace-chain .tnode .pin');
  if (pins.length) {
    var pi = -1, ploop = null;
    function plight(i){
      if (i === pi) return;
      if (pi >= 0) pins[pi].classList.remove('lit');
      pi = i; if (pi >= 0) pins[pi].classList.add('lit');
    }
    ScrollTrigger.create({
      trigger: '.trace-chain', start: 'top 85%', end: 'bottom 45%', scrub: true,
      onUpdate: function(self){ if (!ploop) plight(Math.min(pins.length-1, Math.floor(self.progress * pins.length))); },
      onLeave: function(){ if (!ploop) ploop = setInterval(function(){ plight((pi+1) % pins.length); }, 1100); }
    });
  }

  /* ============ RESTAURANT — scroll-driven drill-down ==================== */
  var lsteps = document.querySelectorAll('.ladder .lstep');
  if (lsteps.length) {
    var lit = -1, lloop = null, lnodes = [];
    lsteps.forEach(function(s){ lnodes.push(s.querySelector('.node')); gsap.set(s, {opacity:.3}); });
    function drill(n){ /* cumulative: light everything up to n */
      lsteps.forEach(function(s, i){
        gsap.to(s, { opacity: i <= n ? 1 : .3, duration: .35, ease: 'power2.out' });
        if (lnodes[i]) lnodes[i].classList.toggle('lit', i === n);
      });
      lit = n;
    }
    ScrollTrigger.create({
      trigger: '.ladder', start: 'top 80%', end: 'bottom 45%', scrub: true,
      onUpdate: function(self){ if (!lloop) drill(Math.min(lsteps.length-1, Math.floor(self.progress * lsteps.length))); },
      onLeave: function(){
        lsteps.forEach(function(s){ gsap.to(s, {opacity:1, duration:.3}); });
        if (!lloop) lloop = setInterval(function(){
          var n = (lit+1) % lsteps.length;
          lnodes.forEach(function(nd, i){ if (nd) nd.classList.toggle('lit', i === n); });
          lit = n;
        }, 1300);
      }
    });
  }

  /* ============ LOGISTICS — truck: scroll-eased, then free cruise ======== */
  var route = document.querySelector('.route');
  if (route) {
    var truck = route.querySelector('.truckpos');
    var stops = [].slice.call(route.querySelectorAll('.stop')).filter(function(s){ return s !== truck; });
    var spos = stops.map(function(s){ return parseFloat(s.style.left); });
    var tstate = { p: 5 }, cruise = null;
    function paint(){
      truck.style.left = tstate.p + '%';
      route.style.background = 'linear-gradient(90deg, var(--v6-cta) 0%, var(--v6-cta) ' + tstate.p + '%, rgba(255,255,255,.16) ' + tstate.p + '%)';
      stops.forEach(function(s, i){ s.classList.toggle('todo', spos[i] > tstate.p); });
    }
    /* one smoothed tween retargeted by scroll — no CSS transition to fight, no jumps */
    var glide = gsap.quickTo(tstate, 'p', { duration: .55, ease: 'power2.out', onUpdate: paint });
    ScrollTrigger.create({
      trigger: route.closest('.m3') || route, start: 'top 78%', end: 'bottom 40%', scrub: true,
      onUpdate: function(self){
        if (cruise) return;
        glide(5 + self.progress * 88);
      },
      onLeave: function(){
        if (cruise) return;
        cruise = gsap.to(tstate, { p: 96, duration: 14, ease: 'none', repeat: -1, onUpdate: paint,
          onRepeat: function(){ tstate.p = 5; } });
      },
      onEnterBack: function(){ if (cruise) { cruise.kill(); cruise = null; } }
    });
    paint();
  }
  /* load bars grow + count up (kept) */
  var trips = document.querySelector('.m2 .trips');
  if (trips) {
    var bars = trips.querySelectorAll('.tbar');
    gsap.from(bars, { scaleX: 0, transformOrigin: 'left center', duration: 1.1,
      ease: 'power3.out', stagger: .12,
      scrollTrigger: { trigger: trips, start: 'top 80%', once: true,
        onEnter: function(){
          bars.forEach(function(bar, bi){
            var m = bar.textContent.match(/(\d+)%/);
            if (!m) return;
            var target = +m[1], obj = {v:0}, tpl = bar.textContent;
            gsap.to(obj, { v: target, duration: 1.1, delay: bi*.12, ease: 'power3.out',
              onUpdate: function(){ bar.textContent = tpl.replace(/\d+%/, Math.round(obj.v) + '%'); } });
          });
        } } });
  }

  /* LOGISTICS — Excel mapping demo: rows get scanned across, forever ------ */
  var flow = document.querySelector('.m4 .flow');
  if (flow) {
    var rawRows = [].slice.call(flow.querySelectorAll('.raw tr')).slice(1);
    var cleanRows = [].slice.call(flow.querySelectorAll('.clean tr')).slice(1);
    var core = flow.querySelector('.core');
    if (rawRows.length && cleanRows.length) {
      gsap.set(cleanRows, { opacity: 0, x: -14 });
      var mtl = gsap.timeline({ paused: true, repeat: -1, repeatDelay: 2.2 });
      rawRows.forEach(function(rr, i){
        var cr = cleanRows[i];
        if (!cr) return;
        mtl.fromTo(rr, { backgroundColor: 'rgba(180,83,9,0)' },
            { backgroundColor: 'rgba(180,83,9,.12)', duration: .3, ease: 'power1.inOut' }, i * 1.15)
           .to(core, { scale: 1.12, duration: .18, yoyo: true, repeat: 1, ease: 'power2.out' }, i * 1.15 + .3)
           .to(cr, { opacity: 1, x: 0, duration: .5, ease: 'power3.out' }, i * 1.15 + .5)
           .to(rr, { backgroundColor: 'rgba(180,83,9,0)', duration: .4 }, i * 1.15 + .8);
      });
      mtl.to(cleanRows, { opacity: 0, x: -14, duration: .4, delay: 1.6 });
      ScrollTrigger.create({ trigger: flow, start: 'top 80%', once: true,
        onEnter: function(){ mtl.play(0); } });
    }
  }

  /* ============ AI — capability wall ripples from center ================= */
  var wall = document.querySelector('.dwall');
  if (wall) {
    var chips = gsap.utils.toArray(wall.children);
    var wr = wall.getBoundingClientRect();
    var wcx = wr.width/2, wcy = wr.height/2;
    var order = chips.map(function(c){
      var r = c.getBoundingClientRect();
      var dx = (r.left - wr.left + r.width/2) - wcx, dy = (r.top - wr.top + r.height/2) - wcy;
      return Math.sqrt(dx*dx + dy*dy);
    });
    gsap.from(chips, {
      opacity: 0, scale: .5, y: 8, duration: .55, ease: 'back.out(1.8)',
      stagger: function(i){ return order[i] * .0022; },
      scrollTrigger: { trigger: wall, start: 'top 82%', once: true }
    });
  }
  var pcard = document.querySelector('.preview-card');
  if (pcard) {
    gsap.from(pcard.querySelectorAll('.row, .btns'), { opacity: 0, y: 10, duration: .7,
      ease: 'power3.out', stagger: .1,
      scrollTrigger: { trigger: pcard, start: 'top 80%', once: true } });
  }

  /* ============ CUSTOM — manifesto char reveal + alternating ledger ====== */
  var mani = document.querySelector('.c-hero h1');
  if (mani) {
    var frag = [];
    mani.childNodes.forEach(function(n){
      if (n.nodeType === 3) frag.push(splitChars(n.textContent, ''));
      else if (n.tagName === 'BR') frag.push('<br>');
      else frag.push(splitChars(n.textContent, n.className || ''));
    });
    function splitChars(text, cls){
      return text.split('').map(function(ch){
        return '<span class="v6-ch' + (cls ? ' ' + cls : '') + '">' + ch + '</span>';
      }).join('');
    }
    mani.innerHTML = frag.join('');
    mani.classList.remove('v6-reveal'); mani.classList.add('in');
    gsap.from(mani.querySelectorAll('.v6-ch'), {
      yPercent: 60, opacity: 0, duration: .7, ease: 'power4.out', stagger: .028, delay: .15
    });
  }
  document.querySelectorAll('.crow').forEach(function(row, i){
    row.classList.remove('v6-reveal'); row.classList.add('in');
    gsap.from(row, { x: i % 2 ? 56 : -56, opacity: 0, duration: .9, ease: 'power4.out',
      scrollTrigger: { trigger: row, start: 'top 88%', once: true } });
  });
})();
