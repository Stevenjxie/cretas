#!/usr/bin/env python3
"""Serve an existing tray queue on loopback without changing network state."""
from __future__ import annotations

import argparse
import html
import importlib.util
import os
from pathlib import Path


DEFAULT_ANNOTATOR = Path(
    r"D:\Temp\cretas-liushanmen-qc-synthetic-v2-20260728"
    r"\targeted-v3-20260803\tray_local_annotator.py"
)


def apply_precision_box_style(page: str, queue_name: str = "tray-queue") -> str:
    """Keep box borders visible without covering the tray edge underneath."""
    replacements = {
        "function handleRadius(){const r=cv.getBoundingClientRect();return Math.max(13,24*cv.width/Math.max(r.width,1))}":
            "function handleRadius(){const r=cv.getBoundingClientRect();return Math.max(8,9*cv.width/Math.max(r.width,1))}",
        "function handleSize(){const r=cv.getBoundingClientRect();return Math.max(7,10*cv.width/Math.max(r.width,1))}":
            "function handleSize(){const r=cv.getBoundingClientRect();return Math.max(2.5,3.5*cv.width/Math.max(r.width,1))}",
        """    ctx.lineWidth = i===sel ? 5 : 3;
    ctx.strokeStyle = i===sel ? '#ffd23f' : '#ff3b30';
    ctx.strokeRect(x0,y0,x1-x0,y1-y0);
    ctx.fillStyle = i===sel ? 'rgba(255,210,63,.16)' : 'rgba(255,59,48,.10)';
    ctx.fillRect(x0,y0,x1-x0,y1-y0);""":
            """    const cssPx=cv.width/Math.max(cv.getBoundingClientRect().width,1);
    ctx.setLineDash(i===sel ? [9*cssPx,6*cssPx] : []);
    ctx.lineWidth = (i===sel ? 1.25 : 1)*cssPx;
    ctx.strokeStyle = i===sel ? '#ffd23f' : '#ff3b30';
    ctx.strokeRect(x0,y0,x1-x0,y1-y0);
    ctx.setLineDash([]);
    ctx.fillStyle = i===sel ? 'rgba(255,210,63,.16)' : 'rgba(255,59,48,.10)';
    ctx.fillRect(x0,y0,x1-x0,y1-y0);""",
        """    if(i===sel){
      const hs=handleSize();
      for(const [hx,hy] of handlesOf(b)){
        ctx.fillStyle='#ffd23f'; ctx.fillRect(hx-hs,hy-hs,hs*2,hs*2);
        ctx.strokeStyle='#1b1e24'; ctx.lineWidth=2; ctx.strokeRect(hx-hs,hy-hs,hs*2,hs*2);
      }
    }""":
            """    if(i===sel){
      // Resize hit areas stay active, but handles are intentionally invisible.
    }""",
    }
    for old, new in replacements.items():
        if old not in page:
            raise RuntimeError("legacy annotator UI changed; refusing an unverified precision-style patch")
        page = page.replace(old, new, 1)
    old_save = """  await fetch('/api/ann/'+it.id, {method:'POST',headers:{'Content-Type':'application/json'},
    body: JSON.stringify({boxes})});"""
    new_save = """  const response = await fetch('/api/ann/'+it.id, {method:'POST',headers:{'Content-Type':'application/json'},
    body: JSON.stringify({boxes})});
  if(!response.ok){ throw new Error('保存失败 HTTP '+response.status+'；请刷新页面后重试'); }"""
    old_confirm = """async function confirmOk(){
  await save();                       // marks reviewed=true server-side
  items[cur].confirmed = true;"""
    new_confirm = """async function confirmOk(){
  try { await save(); } catch(error) { alert(error.message || '保存失败；请刷新页面后重试'); return; }
  items[cur].confirmed = true;"""
    if old_save not in page or old_confirm not in page or "<header>" not in page:
        raise RuntimeError("legacy annotator save flow changed; refusing an unsafe compatibility patch")
    page = page.replace(old_save, new_save, 1).replace(old_confirm, new_confirm, 1)
    page = page.replace(
        "<header>",
        f'<header>\n  <span class="badge" id="queue-id">队列 {html.escape(queue_name)}</span>',
        1,
    )
    return page.replace(
        "<script>",
        '<script>\nconst precisionBoxStyle="thin-dashed-filled-v3"; const boxFillMode="red-10-yellow-16";',
        1,
    )


def load_module(path: Path):
    spec = importlib.util.spec_from_file_location("liushanmen_tray_annotator", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load tray annotator: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--annotator-script", type=Path, default=DEFAULT_ANNOTATOR)
    args = parser.parse_args()

    queue = args.queue.resolve()
    if not (queue / "manifest.json").is_file():
        raise FileNotFoundError(queue / "manifest.json")
    if not args.annotator_script.is_file():
        raise FileNotFoundError(args.annotator_script)
    os.environ["CRETAS_TRAY_ANNOTATION_ROOT"] = str(queue)
    os.environ["CRETAS_TRAY_ANNOTATION_PORT"] = str(args.port)
    bridge = load_module(args.annotator_script)
    app = bridge.load_legacy_module()
    app.SRC = bridge.SRC
    app.BASE = bridge.ROOT
    app.CACHE = bridge.CACHE
    app.ANN = bridge.ANN
    app.PORT = args.port
    app.DISPLAY_W = 1600
    app.SEED = bridge.initialise_queue()
    app.ITEMS = app.build_cache()
    app.PAGE = apply_precision_box_style(app.PAGE, queue.name)
    handler = bridge.build_public_handler(app)
    server = app.ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"tray annotator: http://127.0.0.1:{args.port}")
    print(f"queue: {queue}")
    print(f"pending: {sum(not item['confirmed'] for item in app.SEED)}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
