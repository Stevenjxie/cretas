/* 「AI 像素之眼」— interactive dot-grid canvas.
   Metaphor: the camera sensor / AI vision. Dots breathe in slow waves;
   near the pointer they brighten and pull slightly toward it (the eye "focuses").
   Cheap: one rAF, DPR-aware, paused when offscreen or tab hidden.
   reduced-motion: draws one static faint frame, no loop. */
(function(){
  var RM = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function mount(host, opts){
    if(!host) return;
    opts = opts || {};
    var canvas = document.createElement('canvas');
    canvas.className = 'v6-eye';
    canvas.setAttribute('aria-hidden', 'true');
    host.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var W = 0, H = 0, dots = [], gap = opts.gap || 30;
    var px = -9999, py = -9999, t0 = performance.now();
    var running = false, visible = false, raf = null;
    var color = opts.color || '0,226,138';
    var baseA = opts.baseAlpha || 0.16;

    function resize(){
      var r = host.getBoundingClientRect();
      W = r.width; H = r.height;
      canvas.width = W * dpr; canvas.height = H * dpr;
      canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      dots = [];
      for(var y = gap/2; y < H; y += gap)
        for(var x = gap/2; x < W; x += gap)
          dots.push({x:x, y:y});
    }

    function frame(){
      raf = null;
      if(!running || !visible) return;
      var t = (performance.now() - t0) / 1000;
      ctx.clearRect(0, 0, W, H);
      for(var i = 0; i < dots.length; i++){
        var d = dots[i];
        var dx = px - d.x, dy = py - d.y;
        var dist = Math.sqrt(dx*dx + dy*dy);
        /* slow breathing wave across the grid */
        var wave = Math.sin(t*0.9 + d.x*0.012 + d.y*0.016) * 0.5 + 0.5;
        var a = baseA * (0.35 + wave*0.65);
        var ox = 0, oy = 0, r = 1.1;
        if(dist < 130){
          var f = 1 - dist/130;
          a = Math.min(a + f*0.55, 0.85);
          ox = dx/(dist||1) * f * 7;
          oy = dy/(dist||1) * f * 7;
          r = 1.1 + f*1.1;
        }
        ctx.fillStyle = 'rgba(' + color + ',' + a.toFixed(3) + ')';
        ctx.beginPath();
        ctx.arc(d.x + ox, d.y + oy, r, 0, 6.2832);
        ctx.fill();
      }
      raf = requestAnimationFrame(frame);
    }
    function kick(){ if(!raf && running && visible) raf = requestAnimationFrame(frame); }

    resize();
    if(RM){ /* one static faint frame */
      running = true; visible = true;
      px = -9999; frame(); running = false;
      return;
    }
    window.addEventListener('resize', function(){ resize(); kick(); });
    host.addEventListener('pointermove', function(e){
      var r = canvas.getBoundingClientRect();
      px = e.clientX - r.left; py = e.clientY - r.top;
    });
    host.addEventListener('pointerleave', function(){ px = -9999; py = -9999; });
    if('IntersectionObserver' in window){
      new IntersectionObserver(function(es){
        visible = es[0].isIntersecting; kick();
      }, {threshold: 0}).observe(host);
    } else { visible = true; }
    document.addEventListener('visibilitychange', function(){
      running = !document.hidden; kick();
    });
    running = !document.hidden;
    kick();
  }

  window.v6Eye = mount;
})();
