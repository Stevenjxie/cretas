#!/usr/bin/env python3
"""Annotate one independent four-corner metal work-area ROI per tray photo."""
from __future__ import annotations

import argparse
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from PIL import Image, ImageOps

from work_area import FORMAT, classify_boxes, validate_human_annotation, validate_polygon


MAX_BODY_BYTES = 64 * 1024
SAVE_LOCK = threading.Lock()
DISPLAY_WIDTH = 1600


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_queue(queue: Path) -> dict:
    manifest_path = queue / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(manifest_path)
    manifest = load_json(manifest_path)
    rows = manifest.get("rows") if isinstance(manifest, dict) else None
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("work-area queue requires a non-empty tray manifest")
    if manifest.get("protected_holdout_included"):
        raise RuntimeError("training work-area queue must not contain the protected holdout")
    if any(not isinstance(row, dict) or not row.get("packed_stem") or not row.get("packed_image") for row in rows):
        raise RuntimeError("work-area queue requires packed tray images")
    return manifest


def _safe_queue_path(queue: Path, relative: str) -> Path:
    path = (queue / relative).resolve()
    if queue.resolve() not in path.parents:
        raise RuntimeError("queue image path escapes the queue root")
    return path


def build_items(queue: Path, manifest: dict, *, display_width: int = DISPLAY_WIDTH) -> list[dict]:
    cache = queue / "display-cache-work-area"
    annotations = queue / "annotations-human"
    work_areas = queue / "work-area-human"
    cache.mkdir(parents=True, exist_ok=True)
    work_areas.mkdir(parents=True, exist_ok=True)
    items: list[dict] = []
    for row in manifest["rows"]:
        stem = str(row["packed_stem"])
        image_path = _safe_queue_path(queue, str(row["packed_image"]))
        tray_path = annotations / f"{stem}.json"
        if not image_path.is_file() or not tray_path.is_file():
            raise RuntimeError(f"work-area context is incomplete for {stem}")
        tray = load_json(tray_path)
        if tray.get("reviewed") is not True or tray.get("source") != "human":
            raise RuntimeError(f"tray boxes must be human-reviewed before work-area annotation: {stem}")
        boxes = tray.get("boxes")
        if not isinstance(boxes, list) or not boxes:
            raise RuntimeError(f"tray boxes are missing for work-area annotation: {stem}")
        display_path = cache / f"{stem}.jpg"
        if not display_path.is_file():
            with Image.open(image_path) as opened:
                frame = ImageOps.exif_transpose(opened).convert("RGB")
            ratio = min(1.0, display_width / frame.width)
            if ratio < 1.0:
                frame = frame.resize(
                    (display_width, max(1, round(frame.height * ratio))), Image.Resampling.LANCZOS,
                )
            frame.save(display_path, quality=90, optimize=True)
        with Image.open(display_path) as opened:
            width, height = opened.size
        roi_path = work_areas / f"{stem}.json"
        reviewed = False
        if roi_path.is_file():
            reviewed = bool(validate_human_annotation(load_json(roi_path), expected_photo_id=stem)["reviewed"])
        items.append({
            "id": stem,
            "source_photo_id": str(row.get("source_photo_id") or row.get("photo_id") or stem),
            "source_sha256": str(row.get("source_sha256") or ""),
            "packed_image_sha256": str(row.get("packed_image_sha256") or ""),
            "task_id": str(row.get("task_id") or "unknown"),
            "sku_code": str(row.get("sku_code") or "unknown"),
            "display_w": width,
            "display_h": height,
            "tray_boxes": boxes,
            "confirmed": reviewed,
            "selection_tags": row.get("selection_tags") or [],
        })
    return items


def build_annotation(item: dict, payload: dict) -> dict:
    judgeable = payload.get("judgeable")
    candidate = {
        "photo_id": item["id"],
        "source_photo_id": item["source_photo_id"],
        "source_sha256": item["source_sha256"],
        "packed_image_sha256": item["packed_image_sha256"],
        "task_id": item["task_id"],
        "sku_code": item["sku_code"],
        "format": FORMAT,
        "reviewed": True,
        "source": "human",
        "judgeable": judgeable,
        "polygon": payload.get("polygon"),
        "scope_rule": "tray_center_in_polygon",
        "outside_samples_retained": True,
    }
    if judgeable is False:
        candidate["unjudgeable_reason"] = payload.get("unjudgeable_reason")
    result = validate_human_annotation(candidate, expected_photo_id=item["id"])
    result["tray_scope_counts"] = (
        classify_boxes(item["tray_boxes"], result["polygon"])
        if result["judgeable"] else {"unknown_work_area": len(item["tray_boxes"])}
    )
    return result


PAGE = r'''<!doctype html><html><head><meta charset="utf-8"><title>六扇门金属工作区 ROI</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#11151a;color:#e8edf2;font:14px system-ui,-apple-system,"Segoe UI",sans-serif}
header{position:sticky;top:0;z-index:5;display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 14px;background:#182029;border-bottom:1px solid #33404d}
button,.badge{border:1px solid #405060;border-radius:7px;background:#25313d;color:#edf3f8;padding:7px 11px}button{cursor:pointer}button:hover{background:#314152}button.primary{background:#1769d2;border-color:#2f83ed}button.warn{background:#6f3b13;border-color:#96541e}
#status{color:#64d494;font-weight:650}.hint{width:100%;color:#b9c6d2;font-size:12px}.hint strong{color:#ffd15c}#wrap{padding:14px;display:flex;justify-content:center}canvas{display:block;max-width:100%;height:auto;border:1px solid #34404b;border-radius:5px;touch-action:none;cursor:crosshair}
</style></head><body><header>
<button id="prev">◀ 上一张 (A)</button><span class="badge"><b id="idx">1</b>/<span id="total"></span></span><button id="next">下一张 (D) ▶</button>
<span class="badge" id="meta"></span><button id="draw">重新画四点 (R)</button><button id="undo">撤销一点</button><button id="clear">清空</button>
<button class="primary" id="save">保存并下一张 (S)</button><button class="warn" id="unknown">工作台不在图中/看不清 (U)</button>
<span id="status"></span><span class="badge">完成 <b id="reviewed">0</b>/<span id="queue">0</span></span>
<div class="hint"><strong>只标金属工作台的可用台面：</strong>按顺时针依次点 左上 → 右上 → 右下 → 左下。青色托盘框只读；台外托盘仍保留为真实样本，不会被删除。</div>
</header><div id="wrap"><canvas id="cv"></canvas></div><script>
const items=__ITEMS__; let cur=0, polygon=[], img=new Image(), dragging=-1;
const cv=document.getElementById('cv'),ctx=cv.getContext('2d'); document.getElementById('total').textContent=items.length;
function canvasPoint(e){const r=cv.getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))]}
function handleRadius(){return Math.max(10,14*cv.width/Math.max(1,cv.getBoundingClientRect().width))}
function draw(){ctx.clearRect(0,0,cv.width,cv.height);if(img.complete&&img.naturalWidth)ctx.drawImage(img,0,0,cv.width,cv.height);
 if(polygon.length){ctx.beginPath();polygon.forEach((p,i)=>{const x=p[0]*cv.width,y=p[1]*cv.height;i?ctx.lineTo(x,y):ctx.moveTo(x,y)});if(polygon.length===4)ctx.closePath();ctx.fillStyle='rgba(255,177,46,.20)';ctx.fill();ctx.strokeStyle='#ffb12e';ctx.lineWidth=Math.max(2,2*cv.width/Math.max(1,cv.getBoundingClientRect().width));ctx.setLineDash([12,8]);ctx.stroke();ctx.setLineDash([])}
 items[cur].tray_boxes.forEach(b=>{ctx.fillStyle='rgba(0,174,199,.16)';ctx.strokeStyle='#00aec7';ctx.lineWidth=Math.max(1,cv.width/Math.max(1,cv.getBoundingClientRect().width));ctx.fillRect(b[0]*cv.width,b[1]*cv.height,(b[2]-b[0])*cv.width,(b[3]-b[1])*cv.height);ctx.strokeRect(b[0]*cv.width,b[1]*cv.height,(b[2]-b[0])*cv.width,(b[3]-b[1])*cv.height)});
 polygon.forEach((p,i)=>{const x=p[0]*cv.width,y=p[1]*cv.height,r=handleRadius();ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fillStyle='#ffd15c';ctx.fill();ctx.strokeStyle='#17202a';ctx.lineWidth=2;ctx.stroke();ctx.fillStyle='#17202a';ctx.font=`bold ${Math.max(12,r)}px system-ui`;ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(String(i+1),x,y)});
}
async function refresh(){const s=await (await fetch('/api/stats')).json();document.getElementById('reviewed').textContent=s.reviewed;document.getElementById('queue').textContent=s.queue}
async function load(i){cur=(i+items.length)%items.length;dragging=-1;const it=items[cur];document.getElementById('idx').textContent=cur+1;document.getElementById('meta').textContent=`${it.sku_code} · task ${it.task_id.slice(0,8)} · 托盘 ${it.tray_boxes.length}`;cv.width=it.display_w;cv.height=it.display_h;const a=await (await fetch('/api/ann/'+it.id)).json();polygon=Array.isArray(a.polygon)?a.polygon.map(p=>p.slice()):[];img=new Image();img.onload=draw;img.src='/img/'+it.id+'.jpg';document.getElementById('status').textContent=it.confirmed?'已保存':'';draw();refresh()}
function hitHandle(p){const r=handleRadius()/cv.width;let best=-1,dist=Infinity;polygon.forEach((q,i)=>{const d=Math.hypot((p[0]-q[0]),(p[1]-q[1])*cv.height/cv.width);if(d<=r*1.6&&d<dist){best=i;dist=d}});return best}
cv.addEventListener('pointerdown',e=>{const p=canvasPoint(e),hit=hitHandle(p);if(hit>=0){dragging=hit}else if(polygon.length<4){polygon.push(p.map(v=>+v.toFixed(6)))}cv.setPointerCapture(e.pointerId);draw()});
cv.addEventListener('pointermove',e=>{if(dragging<0)return;polygon[dragging]=canvasPoint(e).map(v=>+v.toFixed(6));draw()});cv.addEventListener('pointerup',()=>{dragging=-1});
async function post(payload){const r=await fetch('/api/ann/'+items[cur].id,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});const data=await r.json();if(!r.ok)throw new Error(data.error||('HTTP '+r.status));items[cur].confirmed=true;document.getElementById('status').textContent=data.judgeable?`已保存 · 台内 ${data.tray_scope_counts.inside_work_area||0} · 台外 ${data.tray_scope_counts.outside_work_area||0}`:'已保存为无法判断';await refresh();return data}
async function saveNext(){if(polygon.length!==4){alert('请依次点满四个角；如果工作台不在图中或看不清，请点对应按钮。');return}try{await post({judgeable:true,polygon});await load(cur+1)}catch(e){alert('保存失败：'+e.message)}}
document.getElementById('prev').onclick=()=>load(cur-1);document.getElementById('next').onclick=()=>load(cur+1);document.getElementById('draw').onclick=()=>{polygon=[];draw()};document.getElementById('undo').onclick=()=>{polygon.pop();draw()};document.getElementById('clear').onclick=()=>{polygon=[];draw()};document.getElementById('save').onclick=saveNext;document.getElementById('unknown').onclick=async()=>{if(!confirm('确认图中没有可可靠标注的金属工作台范围？托盘与缺标样本仍会保留。'))return;try{polygon=[];await post({judgeable:false,polygon:null,unjudgeable_reason:'work_area_not_visible_or_unjudgeable'});await load(cur+1)}catch(e){alert('保存失败：'+e.message)}};
document.addEventListener('keydown',e=>{if(e.key==='a'||e.key==='A'||e.key==='ArrowLeft')load(cur-1);else if(e.key==='d'||e.key==='D'||e.key==='ArrowRight')load(cur+1);else if(e.key==='r'||e.key==='R'){polygon=[];draw()}else if(e.key==='s'||e.key==='S'){e.preventDefault();saveNext()}else if(e.key==='u'||e.key==='U')document.getElementById('unknown').click();else if(e.ctrlKey&&(e.key==='z'||e.key==='Z')){e.preventDefault();polygon.pop();draw()}});load(0);
</script></body></html>'''


def make_handler(queue: Path, items: list[dict]):
    by_id = {item["id"]: item for item in items}
    roi_root = queue / "work-area-human"
    cache = queue / "display-cache-work-area"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, payload: dict) -> None:
            self._send(code, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/":
                page = PAGE.replace("__ITEMS__", json.dumps(items, ensure_ascii=False))
                return self._send(200, page.encode("utf-8"), "text/html; charset=utf-8")
            if path.startswith("/img/"):
                photo_id = Path(path).stem
                if photo_id in by_id:
                    return self._send(200, (cache / f"{photo_id}.jpg").read_bytes(), "image/jpeg")
            if path.startswith("/api/ann/"):
                photo_id = path.rsplit("/", 1)[-1]
                if photo_id not in by_id:
                    return self._json(404, {"ok": False, "error": "unknown photo"})
                annotation = roi_root / f"{photo_id}.json"
                return self._json(200, load_json(annotation) if annotation.is_file() else {
                    "photo_id": photo_id, "reviewed": False, "judgeable": None, "polygon": None,
                })
            if path == "/api/stats":
                reviewed = judgeable = unjudgeable = 0
                for item in items:
                    annotation = roi_root / f"{item['id']}.json"
                    if not annotation.is_file():
                        continue
                    data = validate_human_annotation(load_json(annotation), expected_photo_id=item["id"])
                    reviewed += 1
                    judgeable += int(data["judgeable"])
                    unjudgeable += int(not data["judgeable"])
                return self._json(200, {
                    "queue": len(items), "reviewed": reviewed,
                    "judgeable": judgeable, "unjudgeable": unjudgeable,
                })
            return self._json(404, {"ok": False, "error": "not found"})

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            if not path.startswith("/api/ann/"):
                return self._json(404, {"ok": False, "error": "not found"})
            photo_id = path.rsplit("/", 1)[-1]
            item = by_id.get(photo_id)
            if item is None:
                return self._json(404, {"ok": False, "error": "unknown photo"})
            if self.headers.get("Content-Type", "").split(";", 1)[0].lower() != "application/json":
                return self._json(415, {"ok": False, "error": "application/json required"})
            origin, host = self.headers.get("Origin"), self.headers.get("Host", "")
            if origin and urlparse(origin).netloc != host:
                return self._json(403, {"ok": False, "error": "origin rejected"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = -1
            if length < 0 or length > MAX_BODY_BYTES:
                return self._json(413, {"ok": False, "error": "payload too large"})
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                result = build_annotation(item, payload)
            except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
                return self._json(400, {"ok": False, "error": str(error)})
            destination = roi_root / f"{photo_id}.json"
            temporary = destination.with_suffix(".json.tmp")
            with SAVE_LOCK:
                temporary.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
                os.replace(temporary, destination)
            return self._json(200, {"ok": True, **result})

        def do_OPTIONS(self) -> None:
            self._json(405, {"ok": False, "error": "method not allowed"})

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--port", type=int, default=8774)
    args = parser.parse_args()
    queue = args.queue.resolve()
    manifest = validate_queue(queue)
    items = build_items(queue, manifest)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(queue, items))
    print(f"work-area annotator: http://127.0.0.1:{args.port}")
    print(f"queue: {queue}")
    print(f"pending: {sum(not item['confirmed'] for item in items)}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
