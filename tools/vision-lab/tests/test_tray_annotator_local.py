from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "tray_annotator_local.py"
SPEC = importlib.util.spec_from_file_location("tray_annotator_local", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class TrayAnnotatorLocalTests(unittest.TestCase):
    def test_label_manifest_is_rejected_with_canonical_launcher(self):
        manifest = {
            "rows": [{"crop_id": "label-1", "image": "images/label-1.jpg"}],
        }
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary)
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "label_annotator_local.py"):
                module.validate_tray_queue(queue)

    def test_tray_manifest_is_accepted(self):
        manifest = {"rows": [{"packed_image": "packed/tray-1.jpg"}]}
        with tempfile.TemporaryDirectory() as temporary:
            queue = Path(temporary)
            (queue / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(manifest, module.validate_tray_queue(queue))

    def test_precision_style_keeps_edges_visible_and_handles_small(self):
        page = """<header>
<script>
function handleRadius(){const r=cv.getBoundingClientRect();return Math.max(13,24*cv.width/Math.max(r.width,1))}
function handleSize(){const r=cv.getBoundingClientRect();return Math.max(7,10*cv.width/Math.max(r.width,1))}
    ctx.lineWidth = i===sel ? 5 : 3;
    ctx.strokeStyle = i===sel ? '#ffd23f' : '#ff3b30';
    ctx.strokeRect(x0,y0,x1-x0,y1-y0);
    ctx.fillStyle = i===sel ? 'rgba(255,210,63,.16)' : 'rgba(255,59,48,.10)';
    ctx.fillRect(x0,y0,x1-x0,y1-y0);
    if(i===sel){
      const hs=handleSize();
      for(const [hx,hy] of handlesOf(b)){
        ctx.fillStyle='#ffd23f'; ctx.fillRect(hx-hs,hy-hs,hs*2,hs*2);
        ctx.strokeStyle='#1b1e24'; ctx.lineWidth=2; ctx.strokeRect(hx-hs,hy-hs,hs*2,hs*2);
      }
    }
async function save(){
  const it = items[cur];
  await fetch('/api/ann/'+it.id, {method:'POST',headers:{'Content-Type':'application/json'},
    body: JSON.stringify({boxes})});
}
async function confirmOk(){
  await save();                       // marks reviewed=true server-side
  items[cur].confirmed = true;
}
"""
        result = module.apply_precision_box_style(page, "tray-active-round3")
        self.assertIn('precisionBoxStyle="thin-dashed-filled-v4"', result)
        self.assertIn('boxFillMode="teal-26-yellow-30"', result)
        self.assertIn("队列 tray-active-round3", result)
        self.assertIn("if(!response.ok)", result)
        self.assertIn("alert(error.message", result)
        self.assertIn("[9*cssPx,6*cssPx]", result)
        self.assertIn("1.25 : 1", result)
        self.assertIn("3.5*cv.width", result)
        self.assertIn("rgba(255,210,63,.30)", result)
        self.assertIn("rgba(0,167,196,.26)", result)
        self.assertIn("'#00a7c4'", result)
        self.assertNotIn("'#ff3b30'", result)
        self.assertNotIn("ctx.fillRect(hx-hs", result)
        self.assertIn("handles are intentionally invisible", result)

    def test_precision_style_fails_closed_if_legacy_page_drifts(self):
        with self.assertRaisesRegex(RuntimeError, "legacy annotator UI changed"):
            module.apply_precision_box_style("<script>changed</script>")


if __name__ == "__main__":
    unittest.main()
