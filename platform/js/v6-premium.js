/* V6 premium motion layer — GSAP ScrollTrigger driven.
   Philosophy: scroll-scrubbed depth on imagery, earned set-pieces per page,
   ambient pulses that reinforce "AI 一直在干活". Decorative layers only —
   body copy never moves with scroll. Fully disabled under reduced motion. */
(function(){
  if (!window.gsap || !window.ScrollTrigger) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  gsap.registerPlugin(ScrollTrigger);

  /* A. Hero image: slow settle + drift (photo pages) ---------------------- */
  document.querySelectorAll('.v6-hero-mask img').forEach(function(img){
    gsap.fromTo(img,
      { scale: 1.08, yPercent: -3 },
      { scale: 1, yPercent: 5, ease: 'none',
        scrollTrigger: { trigger: img.closest('.v6-hero'), start: 'top top', end: 'bottom top', scrub: 0.6 } });
  });

  /* B. Photo cards: inner parallax depth ---------------------------------- */
  document.querySelectorAll('.v6-photo-card img').forEach(function(img){
    gsap.set(img, { scale: 1.12 });
    gsap.fromTo(img, { yPercent: -5 }, { yPercent: 5, ease: 'none',
      scrollTrigger: { trigger: img.closest('.v6-photo-card'), start: 'top bottom', end: 'bottom top', scrub: 0.5 } });
  });

  /* C. Pointer-follow spotlight in hero (desktop only) -------------------- */
  if (window.matchMedia('(pointer: fine)').matches) {
    document.querySelectorAll('.hero-wrap, .ai-hero').forEach(function(hero){
      var spot = document.createElement('div');
      spot.className = 'v6-spot';
      hero.appendChild(spot);
      var tx = 0, ty = 0, cx = 0, cy = 0, raf = null, inside = false;
      function tick(){
        cx += (tx - cx) * 0.08; cy += (ty - cy) * 0.08;
        spot.style.transform = 'translate3d(' + cx + 'px,' + cy + 'px,0)';
        if (Math.abs(tx - cx) > 0.5 || Math.abs(ty - cy) > 0.5 || inside) raf = requestAnimationFrame(tick);
        else raf = null;
      }
      hero.addEventListener('pointermove', function(e){
        var r = hero.getBoundingClientRect();
        tx = e.clientX - r.left - 300; ty = e.clientY - r.top - 300;
        inside = true;
        spot.style.opacity = 1;
        if (!raf) raf = requestAnimationFrame(tick);
      });
      hero.addEventListener('pointerleave', function(){ inside = false; spot.style.opacity = 0; });
    });
  }

  /* D. Logistics: load bars grow + numbers count up ------------------------ */
  var trips = document.querySelector('.m2 .trips');
  if (trips) {
    var bars = trips.querySelectorAll('.tbar');
    gsap.from(bars, { scaleX: 0, transformOrigin: 'left center', duration: 1.1,
      ease: 'power3.out', stagger: 0.12,
      scrollTrigger: { trigger: trips, start: 'top 80%', once: true,
        onEnter: function(){
          bars.forEach(function(bar, bi){
            var m = bar.textContent.match(/(\d+)%/);
            if (!m) return;
            var target = +m[1], obj = { v: 0 }, tpl = bar.textContent;
            gsap.to(obj, { v: target, duration: 1.1, delay: bi * 0.12, ease: 'power3.out',
              onUpdate: function(){ bar.textContent = tpl.replace(/\d+%/, Math.round(obj.v) + '%'); } });
          });
        } } });
  }

  /* E. Restaurant: attribution ladder lights step by step, forever --------- */
  var lnodes = document.querySelectorAll('.ladder .lstep .node');
  if (lnodes.length) {
    var li = -1;
    ScrollTrigger.create({ trigger: '.ladder', start: 'top 85%', once: true, onEnter: function(){
      setInterval(function(){
        if (li >= 0) lnodes[li].classList.remove('lit');
        li = (li + 1) % lnodes.length;
        lnodes[li].classList.add('lit');
      }, 1300);
    }});
  }

  /* F. Factory: a pulse travels the traceability chain, forever ------------ */
  var pins = document.querySelectorAll('.trace-chain .tnode .pin');
  if (pins.length) {
    var pi = -1;
    ScrollTrigger.create({ trigger: '.trace-chain', start: 'top 85%', once: true, onEnter: function(){
      setInterval(function(){
        if (pi >= 0) pins[pi].classList.remove('lit');
        pi = (pi + 1) % pins.length;
        pins[pi].classList.add('lit');
      }, 1100);
    }});
  }

  /* G. AI page: preview rows cascade in; confirm button breathes ----------- */
  var pcard = document.querySelector('.preview-card');
  if (pcard) {
    gsap.from(pcard.querySelectorAll('.row, .btns'), { opacity: 0, y: 10, duration: 0.7,
      ease: 'power3.out', stagger: 0.1,
      scrollTrigger: { trigger: pcard, start: 'top 80%', once: true } });
  }
})();
