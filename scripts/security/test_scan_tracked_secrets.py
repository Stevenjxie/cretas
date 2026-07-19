import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = Path(__file__).with_name("scan_tracked_secrets.py")
SPEC = importlib.util.spec_from_file_location("scan_tracked_secrets", MODULE_PATH)
assert SPEC and SPEC.loader
SCANNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SCANNER
SPEC.loader.exec_module(SCANNER)


class SecretScannerTest(unittest.TestCase):
    def test_reports_fingerprint_without_secret_value(self):
        value = b"sk-" + b"live-example0123456789abcdef"
        findings = SCANNER.scan_content("scripts/deploy/example.sh", b"API_KEY=" + value)
        self.assertTrue(findings)
        rendered = "\n".join(str(item) for item in findings)
        self.assertNotIn(value.decode(), rendered)
        self.assertIn(SCANNER.fingerprint(value), rendered)

    def test_placeholder_is_allowed(self):
        findings = SCANNER.scan_content(
            "scripts/systemd/.env.template",
            b"JWT_SECRET=REPLACE_WITH_RANDOM_VALUE\n",
        )
        self.assertEqual([], findings)

    def test_known_compromised_fingerprint_is_detected(self):
        value = b"known-compromised-fixture"
        old = SCANNER.COMPROMISED_FINGERPRINTS
        try:
            SCANNER.COMPROMISED_FINGERPRINTS = {
                SCANNER.fingerprint(value): "unit-test fixture"
            }
            findings = SCANNER.scan_content("scripts/example.sh", value)
        finally:
            SCANNER.COMPROMISED_FINGERPRINTS = old
        self.assertEqual("compromised-fingerprint", findings[0].rule)

    def test_known_compromised_fingerprint_after_assignment_is_detected(self):
        value = b"known-compromised-assignment-fixture"
        old = SCANNER.COMPROMISED_FINGERPRINTS
        try:
            SCANNER.COMPROMISED_FINGERPRINTS = {
                SCANNER.fingerprint(value): "unit-test assignment fixture"
            }
            findings = SCANNER.scan_content("docs/example.txt", b"API_KEY=" + value)
        finally:
            SCANNER.COMPROMISED_FINGERPRINTS = old
        self.assertEqual("compromised-fingerprint", findings[0].rule)
        rendered = "\n".join(str(item) for item in findings)
        self.assertNotIn(value.decode(), rendered)


if __name__ == "__main__":
    unittest.main()
