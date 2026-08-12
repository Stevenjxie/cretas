from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("evaluate_tray_candidate", ROOT / "evaluate_tray_candidate.py")
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class _Models:
    available = True
    load_error = None

    def __init__(self, model_dir):
        self.model_dir = model_dir


class _Yolo:
    LabelQcYoloModels = _Models


class _Params:
    def __init__(self, **kwargs):
        self.kwargs = kwargs


class _Screening:
    ScreeningParams = _Params

    def __init__(self, results):
        self.results = iter(results)

    def screen_image(self, frame, models, params):
        return next(self.results)


def _tray(box, verdict="MISSING_WHITE_LABEL"):
    return SimpleNamespace(box=box, verdict=verdict)


class EvaluateTrayCandidateWorkAreaTests(unittest.TestCase):
    def test_evaluation_splits_inside_outside_without_dropping_trays(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            defect_image = root / "defect.jpg"
            normal_image = root / "normal.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(defect_image)
            Image.new("RGB", (100, 100), (60, 70, 80)).save(normal_image)
            records = [
                {
                    "photo_id": "defect", "image": str(defect_image),
                    "image_sha256": module.sha256_file(defect_image),
                    "human_label": "MISSING_WHITE_LABEL", "bbox": [0.7, 0.2, 0.9, 0.4],
                    "group": "new_blind_defect",
                },
                {
                    "photo_id": "normal", "image": str(normal_image),
                    "image_sha256": module.sha256_file(normal_image),
                    "human_label": "NORMAL", "bbox": None, "group": "normal",
                },
            ]
            annotations = {
                photo_id: module.work_area.validate_human_annotation({
                    "photo_id": photo_id, "source_photo_id": photo_id,
                    "source_sha256": row["image_sha256"],
                    "format": module.work_area.FORMAT, "reviewed": True, "source": "human",
                    "judgeable": True,
                    "polygon": [[0.05, 0.05], [0.6, 0.05], [0.6, 0.9], [0.05, 0.9]],
                })
                for photo_id, row in (("defect", records[0]), ("normal", records[1]))
            }
            outside = _tray([70, 20, 90, 40])
            inside_false = _tray([10, 10, 30, 30])
            screening = _Screening([
                SimpleNamespace(trays=[outside], suspects=[outside]),
                SimpleNamespace(trays=[inside_false], suspects=[inside_false]),
            ])
            result = module.evaluate(root, records, screening, _Yolo, 0.2, annotations)
            self.assertEqual(result["defect_hits"], 1)
            self.assertEqual(result["work_area"]["groups"]["outside_work_area"]["defect_hits"], 1)
            self.assertEqual(result["work_area"]["groups"]["inside_work_area"]["false_flags"], 1)
            self.assertEqual(result["work_area"]["groups"]["unknown_work_area"]["detected_trays"], 0)
            self.assertEqual(result["details"][0]["work_area"], "outside_work_area")

    def test_missing_roi_fails_closed_to_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            image = root / "defect.jpg"
            Image.new("RGB", (100, 100), (30, 40, 50)).save(image)
            tray = _tray([70, 20, 90, 40])
            result = module.evaluate(
                root,
                [{
                    "photo_id": "defect", "image": str(image),
                    "image_sha256": module.sha256_file(image),
                    "human_label": "MISSING_WHITE_LABEL", "bbox": [0.7, 0.2, 0.9, 0.4],
                    "group": "new_blind_defect",
                }],
                _Screening([SimpleNamespace(trays=[tray], suspects=[tray])]), _Yolo, 0.2, {},
            )
            unknown = result["work_area"]["groups"]["unknown_work_area"]
            self.assertEqual(unknown["defect_total"], 1)
            self.assertEqual(unknown["detected_trays"], 1)
            self.assertEqual(result["work_area"]["records"]["without_human_roi"], 1)


if __name__ == "__main__":
    unittest.main()
