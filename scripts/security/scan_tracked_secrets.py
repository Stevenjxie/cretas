#!/usr/bin/env python3
"""Fail closed when tracked files contain production-shaped credentials.

The scanner never prints a matched value. Findings contain only path, line,
rule and a short SHA-256 fingerprint so CI logs cannot create a second leak.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


COMPROMISED_FINGERPRINTS = {
    "c7eaa37716fc": "retired aliyun-a LLM key",
    "b99bd5fe9fc3": "retired aliyun-b LLM key",
    "b1fb467998b0": "retired aliyun-c LLM key",
    "03e355daaf28": "retired zhipu LLM key",
    "2647e1d43a16": "exposed deepseek LLM key",
    "d01b0c5f5f83": "exposed internal API secret",
    "edf614d04235": "exposed aliyun root access-key id",
    "33b27390b2be": "exposed aliyun root access-key secret",
    "eb6c0582c264": "exposed baota panel API key",
    "36ae9dae6a54": "committed legacy JWT",
}

# These disabled Superpowers documents are immutable under repository policy.
# The referenced internal secret is revoked during SEC-CREDENTIAL-ROTATION-20260719.
LEGACY_REVOKED_ALLOWLIST = {
    "docs/superpowers/plans/2026-04-10-canvas-v3-lifecycle-test-execution.md",
    "docs/superpowers/plans/2026-04-23-week6-template-surfacing.md",
    "docs/superpowers/specs/2026-04-10-canvas-v3-lifecycle-test-design.md",
}

TOKEN_RE = re.compile(rb"[A-Za-z0-9_./+=:@-]{12,}")
ALIYUN_AK_RE = re.compile(r"\bLTAI[A-Za-z0-9]{16,28}\b")
PROVIDER_KEY_RE = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
PRIVATE_KEY_RE = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
ASSIGNMENT_RE = re.compile(
    r"^\s*(?:export\s+|Environment=\"?)?"
    r"[A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY|ACCESS_KEY_SECRET|TOKEN)"
    r"\s*=\s*[\"']?([^\s\"'#;]{16,})"
)

OPERATIONAL_PREFIXES = (
    ".github/workflows/",
    "scripts/",
    "backend/java/cretas-api/src/main/resources/",
    "backend/python/",
)
PLACEHOLDER_MARKERS = (
    "placeholder",
    "replace_with",
    "change-me",
    "change_me",
    "changeme",
    "dummy",
    "example",
    "test-only",
    "not-for-production",
    "ci_",
    "your_",
    "fresh_db",
    "$",
    "${",
    "$(",
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    fingerprint: str


def fingerprint(value: bytes | str) -> str:
    raw = value.encode("utf-8") if isinstance(value, str) else value
    return hashlib.sha256(raw).hexdigest()[:12]


def is_operational(path: str) -> bool:
    normalized = path.replace("\\", "/")
    name = normalized.rsplit("/", 1)[-1]
    if (
        "/tests/" in normalized
        or "/testing/" in normalized
        or normalized.startswith("tests/")
        or name.startswith("test-")
        or name.startswith("test_")
    ):
        return False
    if normalized.endswith("scan_tracked_secrets.py"):
        return False
    return normalized.startswith(OPERATIONAL_PREFIXES)


def supports_literal_assignment_scan(path: str) -> bool:
    normalized = path.replace("\\", "/")
    name = normalized.rsplit("/", 1)[-1]
    return (
        name.startswith(".env")
        or normalized.endswith((".service", ".properties", ".yml", ".yaml", ".sh"))
    )


def line_number(content: bytes, offset: int) -> int:
    return content.count(b"\n", 0, offset) + 1


def scan_content(path: str, content: bytes) -> list[Finding]:
    if b"\0" in content:
        return []

    findings: set[Finding] = set()
    allow_revoked = path.replace("\\", "/") in LEGACY_REVOKED_ALLOWLIST

    for match in TOKEN_RE.finditer(content):
        fp = fingerprint(match.group(0))
        if fp in COMPROMISED_FINGERPRINTS and not allow_revoked:
            findings.add(
                Finding(path, line_number(content, match.start()), "compromised-fingerprint", fp)
            )

    text = content.decode("utf-8", errors="ignore")
    for rule, pattern in (
        ("aliyun-access-key", ALIYUN_AK_RE),
        ("provider-api-key", PROVIDER_KEY_RE),
        ("private-key", PRIVATE_KEY_RE),
    ):
        for match in pattern.finditer(text):
            findings.add(
                Finding(path, text.count("\n", 0, match.start()) + 1, rule, fingerprint(match.group(0)))
            )

    if is_operational(path) and supports_literal_assignment_scan(path):
        for index, line in enumerate(text.splitlines(), start=1):
            for match in ASSIGNMENT_RE.finditer(line):
                value = match.group(1)
                lowered = value.lower()
                if any(marker in lowered for marker in PLACEHOLDER_MARKERS):
                    continue
                findings.add(Finding(path, index, "literal-secret-assignment", fingerprint(value)))

    return sorted(findings, key=lambda item: (item.path, item.line, item.rule))


def tracked_files(root: Path) -> Iterable[str]:
    result = subprocess.run(
        ["git", "-c", f"safe.directory={root.as_posix()}", "-C", str(root), "ls-files", "-z"],
        check=True,
        stdout=subprocess.PIPE,
    )
    for raw in result.stdout.split(b"\0"):
        if raw:
            yield raw.decode("utf-8", errors="surrogateescape")


def scan_repository(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for relative in tracked_files(root):
        path = root / relative
        # A deletion staged or pending in the working tree is not part of the
        # resulting tracked tree and must not make local pre-commit scans crash.
        if not path.exists():
            continue
        try:
            findings.extend(scan_content(relative, path.read_bytes()))
        except OSError as exc:
            print(f"secret-scan: cannot read tracked file {relative}: {exc}", file=sys.stderr)
            raise
    return sorted(findings, key=lambda item: (item.path, item.line, item.rule))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    root = args.root.resolve()
    findings = scan_repository(root)
    if findings:
        print(f"secret-scan: FAIL ({len(findings)} finding(s)); values are never printed")
        for item in findings:
            print(f"{item.path}:{item.line} rule={item.rule} fp={item.fingerprint}")
        return 1

    print("secret-scan: PASS (tracked tree contains no production-shaped credentials)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
