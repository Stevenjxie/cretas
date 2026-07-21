/* 开场序章「AI 开机」— homepage only.
   Sequence: 4 logo blocks light up (green last) → AI lock-box snaps onto the logo
   → three mono self-check lines → "scroll to enter" hint. Any wheel / touch /
   click / key reveals the site (overlay clips upward, hero settles).
   Plays once per session; skipped for reduced-motion / no-JS / repeat visits. */
(function(){
  var el = document.getElementById('v6Intro');
  if(!el) return;
  var RM = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var seen = false;
  try { seen = sessionStorage.getItem('cretasIntroSeen') === '1'; } catch(e){}
  if(seen || RM || !window.gsap){ el.remove(); return; }

  document.documentElement.classList.add('v6-lock');
  var revealed = false;

  var tl = gsap.timeline({defaults:{ease:'power4.out'}});
  tl.from('#v6Intro .iblk', {scale:0, opacity:0, duration:.55, stagger:.14, ease:'back.out(2.2)'}, .3)
    .from('#v6Intro .ibox', {scale:1.6, opacity:0, duration:.6, ease:'expo.out'}, '-=.15')
    .from('#v6Intro .ibox .c', {scale:0, duration:.35, stagger:.05, ease:'back.out(3)'}, '<')
    .from('#v6Intro .itag', {opacity:0, y:8, duration:.4}, '-=.2')
    .from('#v6Intro .icheck li', {opacity:0, x:-14, duration:.4, stagger:.22}, '+=.05')
    .from('#v6Intro .ihint', {opacity:0, y:10, duration:.5}, '+=.1');

  function reveal(){
    if(revealed) return;
    revealed = true;
    try { sessionStorage.setItem('cretasIntroSeen', '1'); } catch(e){}
    ['wheel','touchmove','keydown'].forEach(function(ev){ window.removeEventListener(ev, reveal); });
    tl.kill();
    var out = gsap.timeline({onComplete: function(){
      el.remove();
      document.documentElement.classList.remove('v6-lock');
      if(window.__v6lenis) window.__v6lenis.start();
      if(window.ScrollTrigger) ScrollTrigger.refresh();
    }});
    out.to('#v6Intro .icore', {y:-40, opacity:0, duration:.45, ease:'power3.in'})
       .to(el, {clipPath:'inset(0 0 100% 0)', duration:.85, ease:'expo.inOut'}, '-=.15')
       .from('.v6-hero-mask img', {scale:1.1, duration:1.1, ease:'expo.out'}, '-=.5');
  }

  window.addEventListener('wheel', reveal, {passive:true});
  window.addEventListener('touchmove', reveal, {passive:true});
  window.addEventListener('keydown', reveal);
  el.addEventListener('click', reveal);
  /* never trap the visitor: auto-enter after 7s idle */
  setTimeout(reveal, 7000);
})();
