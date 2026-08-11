from __future__ import annotations

import argparse
import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIR))
MODULE_PATH = MODULE_DIR / "locateanything_teacher_admin.py"
SPEC = importlib.util.spec_from_file_location("locateanything_teacher_admin", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class TrayTeacherAdminTests(unittest.TestCase):
    def test_parse_crop_requires_explicit_positive_pixel_region(self):
        self.assertEqual(module.parse_crop("10,20,310,220"), (10, 20, 310, 220))
        for value in ("10,20,30", "10,20,5,30", "-1,0,20,20", "x,0,20,20"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                module.parse_crop(value)


if __name__ == "__main__":
    unittest.main()
