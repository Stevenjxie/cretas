"""训练授权管理 CLI — upsert 一条 training_consent 行 (数据主权管理工具).

设置某工厂/餐饮租户的训练授权范围 (consent_scope):
  - none         默认; 真实数据不进任何训练集 (default-deny)
  - private      授权单租户私有模型: 原始数据, 仅自己的私有模型
  - shared_anon  授权跨租户共享模型: 脱敏后进共享训练集

授权 / 撤销:
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python -m scripts.set_training_consent \
        --factory F001 --scope private --by 张三 --note "合同 2026-001 已签"
    # 撤销 (回到 default-deny):
    PYTHONPATH=.:smartbi python -m scripts.set_training_consent \
        --factory F001 --scope none --by 张三

强制点: export_distillation_dataset.py 导出语料时按本表 consent_scope 门控。
合成语料 (SEED_R/SEED_F) 不受本表管理, 无条件可用。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import Optional

# Path-bootstrap: allow `python scripts/set_training_consent.py` (not only -m)
# by putting backend/python and backend/python/smartbi on sys.path.
_HERE = os.path.dirname(os.path.abspath(__file__))
_BACKEND_PY = os.path.dirname(_HERE)               # backend/python
for _p in (_BACKEND_PY, os.path.join(_BACKEND_PY, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("set_consent")

_VALID_SCOPES = ("none", "private", "shared_anon")

_UPSERT_SQL = """
    INSERT INTO training_consent
        (factory_id, consent_scope, authorized_at, authorized_by, note, updated_at)
    VALUES
        ($1, $2, $3, $4, $5, NOW())
    ON CONFLICT (factory_id) DO UPDATE SET
        consent_scope = EXCLUDED.consent_scope,
        authorized_at = EXCLUDED.authorized_at,
        authorized_by = EXCLUDED.authorized_by,
        note          = EXCLUDED.note,
        updated_at    = NOW()
"""


async def _run(
    factory: str,
    scope: str,
    by: Optional[str],
    note: Optional[str],
    dsn: Optional[str],
) -> None:
    if dsn:
        import asyncpg
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    else:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    # 'none' / revoke → clear authorized_at (no longer authorized).
    from datetime import datetime
    authorized_at = None if scope == "none" else datetime.now()

    async with pool.acquire() as conn:
        await conn.execute(_UPSERT_SQL, factory, scope, authorized_at, by, note)

    if scope == "none":
        logger.info(
            "REVOKED: factory %s → consent_scope='none' (default-deny; real data "
            "excluded from all training).", factory,
        )
    else:
        logger.info(
            "SET: factory %s → consent_scope=%r by %s%s",
            factory, scope, (by or "<unspecified>"),
            (f" — {note}" if note else ""),
        )


def main() -> None:
    ap = argparse.ArgumentParser(description="Upsert a training_consent row (data-sovereignty admin)")
    ap.add_argument("--factory", required=True, help="tenant factory_id")
    ap.add_argument("--scope", required=True, choices=_VALID_SCOPES,
                    help="none | private | shared_anon")
    ap.add_argument("--by", default=None, help="authorizing operator name (audit)")
    ap.add_argument("--note", default=None, help="optional note (contract ref etc.)")
    ap.add_argument("--dsn", default=None, help="optional asyncpg DSN; defaults to app DB env")
    args = ap.parse_args()
    asyncio.run(_run(args.factory, args.scope, args.by, args.note, args.dsn))


if __name__ == "__main__":
    main()
