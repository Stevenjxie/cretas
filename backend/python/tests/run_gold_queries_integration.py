"""Run the legacy Gold integration suite against disposable PostgreSQL.

Usage from ``backend/python``::

    python tests/run_gold_queries_integration.py

Requires Docker. The runner starts a PostgreSQL 16 container on a Docker-chosen
loopback port, applies the exact schema dependencies, runs the 15 integration
tests, and removes the container in ``finally``.
"""

from __future__ import annotations

import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
from urllib.parse import quote


PYTHON_ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS_ROOT = PYTHON_ROOT / "smartbi" / "database" / "migrations"
BOOTSTRAP = Path(__file__).resolve().parent / "fixtures" / "gold_integration_bootstrap.sql"
MIGRATIONS = (
    "2026_04_28_silver_dimensions.sql",
    "2026_04_29_silver_facts.sql",
    "V20260430_01__c_field_provenance.sql",
    "2026_05_05_gold_aggregations.sql",
    "V20260513_01__qhj_revenue_silver_gold.sql",
    "2026_05_25_agg_discount.sql",
    "V20260602_03__p4_canonical_dish.sql",
    "V20260709_01__agg_daily_cost.sql",
)


def _run(
    args: list[str],
    *,
    input_text: str | None = None,
    capture_output: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=PYTHON_ROOT,
        input=input_text,
        text=True,
        capture_output=capture_output,
        check=check,
    )


def _wait_until_ready(container: str) -> None:
    deadline = time.monotonic() + 30
    consecutive_ready = 0
    while time.monotonic() < deadline:
        probe = _run(
            [
                "docker",
                "exec",
                container,
                "pg_isready",
                "-U",
                "smartbi_user",
                "-d",
                "smartbi_gold_test",
            ],
            capture_output=True,
            check=False,
        )
        if probe.returncode == 0:
            consecutive_ready += 1
            # The official image briefly accepts connections on its temporary
            # bootstrap server, then restarts PostgreSQL. Requiring consecutive
            # probes avoids applying migrations during that shutdown window.
            if consecutive_ready >= 3:
                return
        else:
            consecutive_ready = 0
        time.sleep(0.5)
    raise RuntimeError("disposable PostgreSQL did not become ready within 30 seconds")


def _published_port(container: str) -> int:
    result = _run(
        ["docker", "port", container, "5432/tcp"],
        capture_output=True,
    )
    match = re.search(r"127\.0\.0\.1:(\d+)", result.stdout)
    if not match:
        raise RuntimeError(f"cannot parse loopback PostgreSQL port: {result.stdout!r}")
    return int(match.group(1))


def _apply_sql(container: str, source: Path) -> None:
    result = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            container,
            "psql",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "smartbi_user",
            "-d",
            "smartbi_gold_test",
        ],
        cwd=PYTHON_ROOT,
        # Preserve the repository's UTF-8 bytes. Passing text through
        # subprocess on a Chinese Windows locale re-encodes it as GBK, which
        # PostgreSQL correctly rejects as invalid UTF-8.
        input=source.read_bytes(),
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"failed to apply {source.name}:\n"
            f"{result.stdout.decode('utf-8', errors='replace')}\n"
            f"{result.stderr.decode('utf-8', errors='replace')}"
        )


def main() -> int:
    suffix = secrets.token_hex(5)
    container = f"cretas-smartbi-gold-test-{suffix}"
    password = secrets.token_urlsafe(24)

    _run(
        [
            "docker",
            "run",
            "--rm",
            "-d",
            "--name",
            container,
            "-p",
            "127.0.0.1::5432",
            "-e",
            "POSTGRES_USER=smartbi_user",
            "-e",
            f"POSTGRES_PASSWORD={password}",
            "-e",
            "POSTGRES_DB=smartbi_gold_test",
            "postgres:16",
        ],
        capture_output=True,
    )
    try:
        _wait_until_ready(container)
        port = _published_port(container)
        _apply_sql(container, BOOTSTRAP)
        for migration in MIGRATIONS:
            _apply_sql(container, MIGRATIONS_ROOT / migration)

        env = os.environ.copy()
        env["SMARTBI_GOLD_PG_DSN"] = (
            "postgresql://smartbi_user:"
            f"{quote(password, safe='')}@127.0.0.1:{port}/smartbi_gold_test"
        )
        env["SMARTBI_GOLD_PG_DISPOSABLE_CONFIRM"] = "YES"
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "pytest",
                "-q",
                "tests/test_gold_queries.py",
                *sys.argv[1:],
            ],
            cwd=PYTHON_ROOT,
            env=env,
            check=False,
        )
        return completed.returncode
    finally:
        _run(
            ["docker", "stop", "--timeout", "0", container],
            capture_output=True,
            check=False,
        )


if __name__ == "__main__":
    raise SystemExit(main())
