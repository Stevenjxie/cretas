/* V6 motion system.
   Reveal philosophy: elements glide in early and softly (no pop), siblings cascade
   with a small stagger instead of section-blocks appearing one by one.
   Everything degrades to fully-visible static under prefers-reduced-motion / no-JS. */

function v6ReducedMotion(){
  return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function v6RevealInit(){
  var els = document.querySelectorAll('.v6-reveal');
  if(!('IntersectionObserver' in window) || v6ReducedMotion()){
    els.forEach(function(e){ e.classList.add('in'); });
    return;
  }
  /* Auto-stagger: siblings that reveal together cascade 60ms apart (capped),
     so a group reads as one gesture instead of separate blocks. */
  var groups = new Map();
  els.forEach(function(e){
    var p = e.parentElement;
    if(!groups.has(p)) groups.set(p, []);
    e.__v6i = groups.get(p).length;
    groups.get(p).push(e);
  });
  var io = new IntersectionObserver(function(entries){
    entries.forEach(function(en){
      if(!en.isIntersecting) return;
      var el = en.target;
      var d = Math.min(el.__v6i || 0, 7) * 60;
      el.style.transitionDelay = d + 'ms';
      el.classList.add('in');
      io.unobserve(el);
      /* clear delay after it has played so hover/other transitions aren't lagged */
      setTimeout(function(){ el.style.transitionDelay = ''; }, d + 1100);
    });
  }, { threshold: 0.01, rootMargin: '0px 0px -8% 0px' });
  els.forEach(function(e){ io.observe(e); });
}

/* Ambient glow drifts slightly with scroll — continuity between sections. */
function v6GlowDrift(){
  if(v6ReducedMotion()) return;
  var glows = document.querySelectorAll('.v6-glow');
  if(!glows.length) return;
  var ticking = false;
  function frame(){
    ticking = false;
    var y = window.scrollY || 0;
    var t = Math.min(y * 0.06, 48);
    glows.forEach(function(g){ g.style.transform = 'translateY(' + t + 'px)'; });
  }
  window.addEventListener('scroll', function(){
    if(!ticking){ ticking = true; requestAnimationFrame(frame); }
  }, { passive: true });
}

function v6LiveTicker(el, items){
  if(!el || !items.length) return;
  var i = 0;
  if(v6ReducedMotion()){ el.textContent = items.join(' · '); return; }
  setInterval(function(){
    i = (i + 1) % items.length;
    el.style.opacity = 0;
    setTimeout(function(){ el.textContent = items[i]; el.style.opacity = 1; }, 260);
  }, 4200);
}

document.addEventListener('DOMContentLoaded', function(){
  v6RevealInit();
  v6GlowDrift();
});
