"""Tests for scripts/ota/*.sh — the operator-facing OTA push pipeline.

Three layers of coverage:
1. `bash -n` syntax check (catches typos / unbalanced quotes).
2. Regex consistency — the bash SAFE_COMPONENT pattern in each script must
   match `ota.services.storage._VALID_PATH_COMPONENT.pattern` exactly, so a
   sister chat that tightens server validation can't silently desync the
   client-side pre-check.
3. Argument validation — invoke each script with deliberately-bad args
   and assert non-zero exit + descriptive stderr.

Tests do NOT exercise the SSH / scp / curl I/O paths (those depend on a live
server 47 + an admin token; that's Phase 6 emulator E2E scope).
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from ota.services import storage

# repo root from .../backend/python/ota/tests/test_scripts.py
REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPT_DIR = REPO_ROOT / "scripts" / "ota"
CDN_HELPER = SCRIPT_DIR / "lib" / "ota-cdn.sh"


def _resolve_bash() -> str:
    """Prefer Git Bash on Windows, where system32/bash.exe may be a broken WSL shim."""
    if os.name == "nt":
        program_files = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
        git_bash = program_files / "Git" / "bin" / "bash.exe"
        if git_bash.is_file():
            return str(git_bash)
    return shutil.which("bash") or "bash"


BASH = _resolve_bash()


@pytest.fixture(scope="session")
def script_paths() -> list[Path]:
    return [
        SCRIPT_DIR / "push-bundle.sh",
        SCRIPT_DIR / "rollback.sh",
        SCRIPT_DIR / "prune-bundles.sh",
    ]


def test_all_scripts_exist(script_paths):
    for p in script_paths:
        assert p.is_file(), f"missing script: {p}"


def test_all_scripts_pass_bash_n_syntax_check(script_paths):
    """`bash -n` parses the script without executing — catches syntax errors."""
    for p in script_paths:
        result = subprocess.run(
            [BASH, "-n", str(p)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert result.returncode == 0, (
            f"{p.name} failed `bash -n`:\nstdout={result.stdout}\nstderr={result.stderr}"
        )


def test_cdn_helper_exists_and_passes_bash_n_syntax_check():
    assert CDN_HELPER.is_file(), f"missing script: {CDN_HELPER}"
    result = subprocess.run(
        [BASH, "-n", str(CDN_HELPER)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert result.returncode == 0, (
        f"{CDN_HELPER.name} failed `bash -n`:\n"
        f"stdout={result.stdout}\nstderr={result.stderr}"
    )


def test_bash_safe_component_regex_matches_storage_regex(script_paths):
    """The bash-side SAFE_COMPONENT must equal Python's _VALID_PATH_COMPONENT.

    Prevents a sister-chat from tightening the server regex without also
    updating these scripts, which would make pushes fail late with HTTP 422
    instead of failing fast locally.
    """
    expected = storage._VALID_PATH_COMPONENT.pattern
    # bash POSIX regex doesn't use Python's ^...$ anchors interchangeably,
    # but our pattern happens to be portable. Check literal substring match.
    for p in script_paths:
        text = p.read_text(encoding="utf-8")
        assert expected in text, (
            f"{p.name} does not contain the storage regex {expected!r}. "
            "Did you tighten storage._VALID_PATH_COMPONENT without updating the scripts?"
        )


# --- argument-validation behaviour --------------------------------------------


def _run(script: Path, args: list[str], env_extra: dict | None = None) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    # Provide a fake admin token so OTA_ADMIN_TOKEN-required scripts don't bail
    # on env validation; arg-validation should fail BEFORE any network call.
    env["OTA_ADMIN_TOKEN"] = "dummy-token-for-arg-validation"
    if env_extra:
        env.update(env_extra)
    return subprocess.run(
        [BASH, str(script), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        cwd=str(REPO_ROOT),
        timeout=15,
    )


def test_push_bundle_rejects_invalid_channel():
    result = _run(SCRIPT_DIR / "push-bundle.sh", ["invalid-channel", "android"])
    assert result.returncode == 2
    assert "channel must be" in result.stderr


def test_push_bundle_rejects_invalid_platform():
    result = _run(SCRIPT_DIR / "push-bundle.sh", ["production", "windows"])
    assert result.returncode == 2
    assert "platform must be" in result.stderr


def test_push_bundle_requires_admin_token():
    # Strip the dummy token and verify bash bails on the :? error.
    env = os.environ.copy()
    env.pop("OTA_ADMIN_TOKEN", None)
    result = subprocess.run(
        [BASH, str(SCRIPT_DIR / "push-bundle.sh"), "production", "android"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        cwd=str(REPO_ROOT),
        timeout=15,
    )
    assert result.returncode != 0
    assert "OTA_ADMIN_TOKEN" in result.stderr


def test_rollback_rejects_path_traversal_in_args():
    result = _run(
        SCRIPT_DIR / "rollback.sh",
        ["1.0.0", "production", "../../etc/passwd"],
    )
    assert result.returncode == 2
    assert "fails" in result.stderr


def test_rollback_rejects_dot_alias():
    """Defense-in-depth: even though `..` would match alphanumeric-prefix
    failures, also confirm the regex catches bare `..`."""
    result = _run(SCRIPT_DIR / "rollback.sh", ["..", "production", "200"])
    assert result.returncode == 2


def test_rollback_requires_three_args():
    result = _run(SCRIPT_DIR / "rollback.sh", ["1.0.0", "production"])
    assert result.returncode != 0


def test_prune_rejects_invalid_keep_count():
    result = _run(SCRIPT_DIR / "prune-bundles.sh", ["1.0.0", "production", "not-a-number"])
    assert result.returncode == 2
    assert "non-negative integer" in result.stderr


def test_prune_rejects_path_traversal_in_runtime_version():
    result = _run(SCRIPT_DIR / "prune-bundles.sh", ["../etc", "production", "10"])
    assert result.returncode == 2


def test_prune_keep_zero_promotes_to_one():
    """Per spec §16 Q4: latest must always be preserved. KEEP=0 → WARN + keep 1.

    We can verify the WARN message is emitted without actually SSHing to a
    server by making the script fail on the ssh step (no OTA_SERVER reachable)
    and grepping stderr for the warning ahead of the ssh call.
    """
    # Use an unreachable OTA_SERVER so the ssh call fails fast.
    result = _run(
        SCRIPT_DIR / "prune-bundles.sh",
        ["1.0.0", "production", "0"],
        env_extra={"OTA_SERVER": "root@127.0.0.1:1"},  # invalid port → ssh fails
    )
    # Script may exit non-zero on the ssh step, but the WARN must be in stderr.
    assert "KEEP=0 would delete everything" in result.stderr
