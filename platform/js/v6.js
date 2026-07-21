function v6RevealInit(){
  var els=document.querySelectorAll('.v6-reveal');
  if(!('IntersectionObserver' in window)){els.forEach(function(e){e.classList.add('in')});return}
  var io=new IntersectionObserver(function(es){es.forEach(function(en){
    if(en.isIntersecting){en.target.classList.add('in');io.unobserve(en.target)}})},{threshold:.12});
  els.forEach(function(e){io.observe(e)});
}
function v6LiveTicker(el,items){
  if(!el||!items.length)return; var i=0;
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches){el.textContent=items.join(' · ');return}
  setInterval(function(){i=(i+1)%items.length;el.style.opacity=0;
    setTimeout(function(){el.textContent=items[i];el.style.opacity=1},260)},4200);
}
document.addEventListener('DOMContentLoaded',v6RevealInit);
