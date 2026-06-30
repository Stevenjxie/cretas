#!/usr/bin/env python3
"""Seed DEMO_REST operational records used by restaurant demo analysis.

This script is intentionally narrow and repeatable:
- target factory is hard-coded to DEMO_REST
- row IDs are deterministic, so reruns update the same demo rows
- no real customer tenant can be passed by argument

It supplements the operational tables that the demo UI and completeness API
read directly: wastage_records and stocktaking_records.

Production usage on the 47 server:
  sudo -u postgres /www/wwwroot/cretas/code/backend/python/venv38/bin/python \
    /www/wwwroot/cretas/code/backend/python/smartbi/scripts/seed_demo_rest_ops.py \
    --dsn "dbname=cretas_prod_db"
"""
from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, List, Sequence, Tuple

import psycopg2
from psycopg2.extras import RealDictCursor, execute_values

TARGET_FACTORY = "DEMO_REST"

SECTION_CODES = ["SEAFOOD", "HOT_DISH", "COLD_DISH", "FRONT_HOUSE", "OTHER"]
WASTAGE_TYPES = ["PROCESSING", "SPOILED", "DAMAGED", "EXPIRED", "OTHER"]
WASTAGE_REASONS = [
    "加工修边损耗，需复核标准出成率",
    "来料鲜度波动，建议追踪供应批次",
    "备货过量导致临期报损，建议调整预估量",
    "门店高峰期操作破损，建议复盘班次动线",
    "冷藏周转偏慢，建议检查先进先出执行",
]


def _money(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _qty(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def _connect(dsn: str):
    return psycopg2.connect(dsn)


def _load_materials(conn) -> List[Dict[str, Any]]:
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            """
            SELECT id, name, unit,
                   COALESCE(NULLIF(unit_price, 0), NULLIF(moving_avg_price, 0), 20) AS price
              FROM raw_material_types
             WHERE factory_id = %s
               AND deleted_at IS NULL
             ORDER BY id
             LIMIT 12
            """,
            (TARGET_FACTORY,),
        )
        rows = [dict(r) for r in cur.fetchall()]
    if not rows:
        raise RuntimeError("DEMO_REST has no raw_material_types; cannot seed ops data")
    return rows


def _load_users(conn) -> List[int]:
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            """
            SELECT id
              FROM users
             WHERE factory_id = %s
             ORDER BY CASE WHEN username = 'demo_rest' THEN 0 ELSE 1 END, id
             LIMIT 8
            """,
            (TARGET_FACTORY,),
        )
        rows = [int(r["id"]) for r in cur.fetchall()]
    return rows or [None]  # type: ignore[list-item]


def _build_wastage_rows(
    materials: Sequence[Dict[str, Any]],
    users: Sequence[int],
    end_day: date,
) -> List[Tuple[Any, ...]]:
    rows: List[Tuple[Any, ...]] = []
    for offset in range(30):
        day = end_day - timedelta(days=offset)
        mat = materials[offset % len(materials)]
        qty = _qty(Decimal("0.60") + Decimal(offset % 7) * Decimal("0.35"))
        price = Decimal(str(mat["price"] or 20))
        cost = _money(qty * price)
        section = SECTION_CODES[offset % len(SECTION_CODES)]
        operator_id = users[offset % len(users)]
        approved_by = users[(offset + 1) % len(users)]
        rows.append(
            (
                f"demo_rest_wst_{day:%Y%m%d}_{offset % 3}",
                TARGET_FACTORY,
                f"WST-DEMO-{day:%Y%m%d}-{offset % 3 + 1:02d}",
                day,
                WASTAGE_TYPES[offset % len(WASTAGE_TYPES)],
                "APPROVED",
                mat["id"],
                qty,
                mat["unit"] or "kg",
                cost,
                WASTAGE_REASONS[offset % len(WASTAGE_REASONS)],
                operator_id,
                approved_by,
                datetime.combine(day, datetime.min.time()) + timedelta(hours=16),
                "演示数据：用于损耗责任、档口和食材维度分析",
                operator_id,
                section,
            )
        )
    return rows


def _build_stocktaking_rows(
    materials: Sequence[Dict[str, Any]],
    users: Sequence[int],
    end_day: date,
) -> List[Tuple[Any, ...]]:
    rows: List[Tuple[Any, ...]] = []
    # Five weekly snapshots x four materials = 20 rows in the 30-day window.
    for week in range(5):
        day = end_day - timedelta(days=week * 7)
        for idx, mat in enumerate(materials[:4]):
            system_qty = _qty(Decimal("36.0") + Decimal(idx * 7 + week * 2))
            diff = _qty(Decimal([0, -1, 1, -2][idx]) * Decimal("0.45"))
            actual_qty = _qty(system_qty + diff)
            if diff > 0:
                diff_type = "SURPLUS"
            elif diff < 0:
                diff_type = "SHORTAGE"
            else:
                diff_type = "MATCH"
            price = Decimal(str(mat["price"] or 20))
            amount = _money(abs(diff) * price)
            counter = users[(idx + week) % len(users)]
            verifier = users[(idx + week + 1) % len(users)]
            section = SECTION_CODES[idx % len(SECTION_CODES)]
            rows.append(
                (
                    f"demo_rest_stk_{day:%Y%m%d}_{idx}",
                    TARGET_FACTORY,
                    f"STK-DEMO-{day:%Y%m%d}-{idx + 1:02d}",
                    day,
                    "COMPLETED",
                    mat["id"],
                    mat["unit"] or "kg",
                    system_qty,
                    actual_qty,
                    diff,
                    diff_type,
                    amount,
                    "演示盘点：用于周度盘盈盘亏和档口责任分析",
                    counter,
                    verifier,
                    datetime.combine(day, datetime.min.time()) + timedelta(hours=21),
                    "演示数据：周度盘点快照",
                    section,
                )
            )
    return rows


def seed(dsn: str, end_day: date, dry_run: bool) -> None:
    with _connect(dsn) as conn:
        materials = _load_materials(conn)
        users = _load_users(conn)
        wastage_rows = _build_wastage_rows(materials, users, end_day)
        stocktaking_rows = _build_stocktaking_rows(materials, users, end_day)

        print(
            f"DEMO_REST ops seed end={end_day} dry_run={dry_run}: "
            f"wastage={len(wastage_rows)} stocktaking={len(stocktaking_rows)}"
        )

        if dry_run:
            conn.rollback()
            return

        with conn.cursor() as cur:
            execute_values(
                cur,
                """
                INSERT INTO wastage_records (
                    id, factory_id, wastage_number, wastage_date, type, status,
                    raw_material_type_id, quantity, unit, estimated_cost, reason,
                    reported_by, approved_by, approved_at, notes, operator_id,
                    section_code, created_at, updated_at
                ) VALUES %s
                ON CONFLICT (id) DO UPDATE SET
                    wastage_number = EXCLUDED.wastage_number,
                    wastage_date = EXCLUDED.wastage_date,
                    type = EXCLUDED.type,
                    status = EXCLUDED.status,
                    raw_material_type_id = EXCLUDED.raw_material_type_id,
                    quantity = EXCLUDED.quantity,
                    unit = EXCLUDED.unit,
                    estimated_cost = EXCLUDED.estimated_cost,
                    reason = EXCLUDED.reason,
                    reported_by = EXCLUDED.reported_by,
                    approved_by = EXCLUDED.approved_by,
                    approved_at = EXCLUDED.approved_at,
                    notes = EXCLUDED.notes,
                    operator_id = EXCLUDED.operator_id,
                    section_code = EXCLUDED.section_code,
                    updated_at = NOW()
                """,
                wastage_rows,
                template=(
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),NOW())"
                ),
            )
            execute_values(
                cur,
                """
                INSERT INTO stocktaking_records (
                    id, factory_id, stocktaking_number, stocktaking_date, status,
                    raw_material_type_id, unit, system_quantity, actual_quantity,
                    difference_quantity, difference_type, difference_amount,
                    adjustment_reason, counted_by, verified_by, completed_at,
                    notes, section_code, created_at, updated_at
                ) VALUES %s
                ON CONFLICT (id) DO UPDATE SET
                    stocktaking_number = EXCLUDED.stocktaking_number,
                    stocktaking_date = EXCLUDED.stocktaking_date,
                    status = EXCLUDED.status,
                    raw_material_type_id = EXCLUDED.raw_material_type_id,
                    unit = EXCLUDED.unit,
                    system_quantity = EXCLUDED.system_quantity,
                    actual_quantity = EXCLUDED.actual_quantity,
                    difference_quantity = EXCLUDED.difference_quantity,
                    difference_type = EXCLUDED.difference_type,
                    difference_amount = EXCLUDED.difference_amount,
                    adjustment_reason = EXCLUDED.adjustment_reason,
                    counted_by = EXCLUDED.counted_by,
                    verified_by = EXCLUDED.verified_by,
                    completed_at = EXCLUDED.completed_at,
                    notes = EXCLUDED.notes,
                    section_code = EXCLUDED.section_code,
                    updated_at = NOW()
                """,
                stocktaking_rows,
                template=(
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),NOW())"
                ),
            )
        conn.commit()


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed DEMO_REST ops demo data")
    parser.add_argument("--dsn", default="dbname=cretas_prod_db")
    parser.add_argument("--end", default=date.today().isoformat(), metavar="YYYY-MM-DD")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    seed(args.dsn, date.fromisoformat(args.end), args.dry_run)


if __name__ == "__main__":
    main()
