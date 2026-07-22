#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

import pandas as pd

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

DEMO_REST = "DEMO_REST"
SHEET_NAME = "大众点评评价"

EXPECTED_REVIEW_KEYS = (
    "评价ID",
    "评价时间",
    "time_period",
    "城市",
    "评价门店",
    "平台",
    "评价详情",
    "星级分",
    "口味分",
    "环境分",
    "服务分",
    "评价来源",
    "评价质量",
    "菜品标签",
    "服务标签",
    "环境标签",
    "用户昵称",
    "是否vip",
    "用户等级",
    "回复状态",
    "最新回复内容",
    "最新回复时间",
    "投诉状态",
    "投诉时间",
    "投诉类型",
    "投诉理由",
)

NUMERIC_COLUMNS = ("星级分", "口味分", "环境分", "服务分")


def repo_root() -> Path:
    p = Path(__file__).resolve()
    for parent in p.parents:
        if (parent / "backend").is_dir() and (parent / "web-admin").is_dir():
            return parent
    raise RuntimeError(f"Cannot locate repo root from {__file__}")


def find_review_files(root: Path) -> list[Path]:
    markers = ("2025.07.01-2025.09.30", "2025.10.01-2025.12.31")
    files = [
        p for p in root.rglob("*.xlsx")
        if any(marker in str(p) for marker in markers)
    ]
    return sorted(files, key=lambda p: str(p))


def _clean_value(value: Any) -> Any:
    if value is None:
        return None
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    if isinstance(value, pd.Timestamp):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    return value


def _numeric(value: Any) -> float | None:
    value = _clean_value(value)
    if value in (None, ""):
        return None
    try:
        return round(float(value), 3)
    except (TypeError, ValueError):
        return None


def _stable_demo_id(raw_id: Any, row_index: int) -> str:
    raw = str(_clean_value(raw_id) or row_index)
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]
    return f"DEMO_REVIEW_{digest}"


def _store_mapper() -> Any:
    mapping: dict[str, str] = {}

    def map_store(value: Any) -> str:
        raw = str(_clean_value(value) or "未标注门店").strip()
        if raw not in mapping:
            mapping[raw] = f"示范门店{len(mapping) + 1:02d}"
        return mapping[raw]

    return map_store


def normalize_review_dataframe(df: pd.DataFrame, *, limit: int | None = None) -> list[dict[str, Any]]:
    store_name = _store_mapper()
    if limit is not None and limit > 0:
        df = df.head(limit)

    rows: list[dict[str, Any]] = []
    for i, source in enumerate(df.to_dict("records"), start=1):
        review_id = _stable_demo_id(source.get("评价ID"), i)
        review_time = _clean_value(source.get("评价时间"))
        row = {key: _clean_value(source.get(key)) for key in EXPECTED_REVIEW_KEYS}
        row["评价ID"] = review_id
        row["评价时间"] = review_time
        row["time_period"] = review_time
        row["评价门店"] = store_name(source.get("评价门店"))
        row["城市"] = row.get("城市") or "上海市"
        row["平台"] = row.get("平台") or "点评"
        row["用户昵称"] = f"顾客{i:05d}"
        for col in NUMERIC_COLUMNS:
            row[col] = _numeric(source.get(col))
        if row["星级分"] is None:
            continue
        rows.append(row)
    return rows


def load_review_rows(paths: Iterable[Path], *, limit: int | None = None) -> list[dict[str, Any]]:
    frames = []
    for path in paths:
        frames.append(pd.read_excel(path))
    if not frames:
        return []
    df = pd.concat(frames, ignore_index=True)
    return normalize_review_dataframe(df, limit=limit)


async def seed_reviews(factory_id: str, rows: list[dict[str, Any]], *, delete_existing: bool = True) -> int:
    if not rows:
        raise RuntimeError("No review rows to seed")

    import asyncpg
    from smartbi.config import get_settings

    settings = get_settings()
    if not settings.postgres_url:
        raise RuntimeError("PostgreSQL is not configured; set POSTGRES_* env vars")

    conn = await asyncpg.connect(settings.postgres_url)
    try:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            if delete_existing:
                await conn.execute(
                    """
                    DELETE FROM smart_bi_dynamic_data
                     WHERE factory_id = $1
                       AND row_data ? '星级分'
                       AND row_data->>'评价ID' IS NOT NULL
                    """,
                    factory_id,
                )
            upload_id = await conn.fetchval(
                """
                INSERT INTO smart_bi_pg_excel_uploads
                    (factory_id, file_name, sheet_name, detected_table_type,
                     detected_structure, field_mappings, context_info,
                     row_count, column_count, upload_status, created_at, updated_at)
                VALUES
                    ($1, $2, $3, 'review', $4::jsonb, $5::jsonb, $6::jsonb,
                     $7, $8, 'COMPLETED', now(), now())
                RETURNING id
                """,
                factory_id,
                "demo-rest-dianping-reviews.xlsx",
                SHEET_NAME,
                json.dumps({"source": "大众点评评价下载", "demoSeed": True}, ensure_ascii=False),
                json.dumps({key: key for key in EXPECTED_REVIEW_KEYS}, ensure_ascii=False),
                json.dumps({"demoSeed": True, "sourceRows": len(rows)}, ensure_ascii=False),
                len(rows),
                len(EXPECTED_REVIEW_KEYS),
            )
            records = [
                (
                    factory_id,
                    upload_id,
                    SHEET_NAME,
                    idx,
                    json.dumps(row, ensure_ascii=False),
                    str(row.get("time_period") or "")[:10] or None,
                    row.get("平台") or "点评",
                )
                for idx, row in enumerate(rows)
            ]
            await conn.executemany(
                """
                INSERT INTO smart_bi_dynamic_data
                    (factory_id, upload_id, sheet_name, row_index, row_data, period, category)
                VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7)
                """,
                records,
            )
        return len(rows)
    finally:
        await conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed DEMO_REST Dianping review rows into SmartBI")
    parser.add_argument("--factory-id", default=DEMO_REST)
    parser.add_argument("--repo-root", type=Path, default=repo_root())
    parser.add_argument("--limit", type=int, default=0, help="0 means all rows")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-existing", action="store_true")
    args = parser.parse_args()

    files = find_review_files(args.repo_root)
    if not files:
        raise SystemExit("No Dianping review xlsx files found")

    rows = load_review_rows(files, limit=(args.limit or None))
    print(f"files={len(files)} rows={len(rows)} factory={args.factory_id}")
    print(f"stores={sorted({row['评价门店'] for row in rows})[:10]}")
    print(f"platforms={sorted({row['平台'] for row in rows})}")
    if args.dry_run:
        print(json.dumps(rows[0], ensure_ascii=False, indent=2))
        return

    inserted = asyncio.run(
        seed_reviews(args.factory_id, rows, delete_existing=not args.keep_existing)
    )
    print(f"seeded {inserted} review rows for {args.factory_id}")


if __name__ == "__main__":
    main()
