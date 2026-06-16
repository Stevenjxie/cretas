#!/usr/bin/env python3
"""
seed_demo_raw_tables.py
=======================
Direct-DB seeder that writes synthetic data into the three raw tables that the
SmartBI dashboard actually reads.  The original seed_demo_data.py pipeline
writes to smart_bi_timeseries (via xlsx upload) which the dashboard ignores.

Tables written:
  cretas_db / cretas_prod_db:
    • smart_bi_sales_data    (legacy KPI path + data-date-range endpoint)
    • smart_bi_finance_data  (finance panel KPIs)
  smartbi_db / smartbi_prod_db:
    • agg_daily              (gold-primary KPI path, used by test env)

Tenants fixed:
  DEMO_FACTORY  – sales_data already present; extends agg_daily 2026-01→now
  DEMO_FACTORY2 – no raw data; seeded from scratch
  DEMO_REST     – finance/agg_daily present; adds sales_data + extends to now
  F004          – minimal finance only; full seed

Usage:
  python seed_demo_raw_tables.py [--env prod|test]  [--dry-run]

Run on server (requires psycopg2 in the venv):
  cd /www/wwwroot/cretas/code/backend/python
  source venv38/bin/activate
  python smartbi/scripts/seed_demo_raw_tables.py --env prod

Author: feat/demo-dashboard-light worktree
"""
from __future__ import annotations

import argparse
import random
import sys
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, List, Optional, Tuple

try:
    import psycopg2
    from psycopg2.extras import execute_values
except ImportError:
    print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
    sys.exit(1)

# ---------------------------------------------------------------------------
# ENV-SPECIFIC CONFIG
# ---------------------------------------------------------------------------

ENVS: Dict[str, Dict[str, Any]] = {
    "prod": {
        "cretas": {
            "host": "127.0.0.1",
            "port": 5432,
            "dbname": "cretas_prod_db",
            "user": "cretas_user",
            "password": "cretas123",
        },
        "smartbi": {
            "host": "127.0.0.1",
            "port": 5432,
            "dbname": "smartbi_prod_db",
            "user": "smartbi_user",
            "password": "smartbi_secure_password_2025",
        },
    },
    "test": {
        "cretas": {
            "host": "127.0.0.1",
            "port": 5432,
            "dbname": "cretas_db",
            "user": "cretas_user",
            "password": "cretas123",
        },
        "smartbi": {
            "host": "127.0.0.1",
            "port": 5432,
            "dbname": "smartbi_db",
            "user": "smartbi_user",
            "password": "smartbi_secure_password_2025",
        },
    },
}

# ---------------------------------------------------------------------------
# DATA GENERATORS
# ---------------------------------------------------------------------------

MFG_CATEGORIES = ["复合调味粉", "川味底料", "预制菜", "团餐", "中式汤底", "调味汁", "火锅底料", "香辛料"]
MFG_REGIONS = ["上海分部", "江苏分部", "浙江分部", "赣皖区域", "华北区域", "西南区域"]
MFG_SALESPERSONS = [
    "陈燕", "浦丽", "吴帅", "王璐", "袁欢", "周倩", "王桂兰", "王丽娟", "陈玉珍", "王欢",
    "李明", "张伟", "刘芳", "赵磊", "黄丽", "吴静", "林峰", "徐洋", "何倩", "冯波",
]
MFG_CUSTOMERS = [
    "黄石金承网络有限公司", "数字100传媒有限公司", "双敏电子网络有限公司", "九方信息有限公司",
    "新宇龙信息科技有限公司", "昂歌信息科技有限公司", "趋势信息有限公司", "东方峻景信息有限公司",
    "盟新信息有限公司", "创汇传媒有限公司", "华联超市有限公司", "大润发商业有限公司",
    "沃尔玛中国有限公司", "家乐福商业有限公司", "永辉超市股份有限公司",
]
REST_CATEGORIES = ["堂食", "外卖", "团餐", "外卖平台", "自营外卖"]
REST_STORES = ["总店", "分店1", "分店2", "旗舰店"]
REST_DEPARTMENTS = ["厨房部", "服务部", "外卖部", "运营部"]

rng = random.Random(42)


def _dates_in_range(start: date, end: date) -> List[date]:
    """Return all dates from start to end inclusive."""
    result = []
    d = start
    while d <= end:
        result.append(d)
        d += timedelta(days=1)
    return result


def _months_in_range(start: date, end: date) -> List[date]:
    """Return first-of-month dates from start month to end month."""
    result = []
    d = date(start.year, start.month, 1)
    while d <= end:
        result.append(d)
        # advance one month
        if d.month == 12:
            d = date(d.year + 1, 1, 1)
        else:
            d = date(d.year, d.month + 1, 1)
    return result


def _sales_row_mfg(factory_id: str, d: date, upload_id: Optional[int] = None) -> tuple:
    """Generate one manufacturing-style smart_bi_sales_data row."""
    cat = rng.choice(MFG_CATEGORIES)
    region = rng.choice(MFG_REGIONS)
    sp = rng.choice(MFG_SALESPERSONS)
    cust = rng.choice(MFG_CUSTOMERS)
    qty = rng.randint(100, 5000)
    unit_price = rng.uniform(50, 300)
    amount = round(qty * unit_price, 2)
    gm = round(rng.uniform(0.25, 0.42), 4)
    cost = round(amount * (1 - gm), 2)
    profit = round(amount - cost, 2)
    now = "NOW()"  # placeholder for SQL
    return (
        factory_id,          # factory_id
        d,                    # order_date
        cat,                  # product_category
        f"{cat[:3]}SKU-{rng.randint(1000,9999)}",  # product_id
        cat,                  # product_name
        region,               # region
        sp,                   # salesperson_name
        f"SP{rng.randint(100,999)}",  # salesperson_id
        cust,                 # customer_name
        "企业客户",             # customer_type
        round(float(amount), 2),  # amount
        round(float(qty), 4),     # quantity
        round(float(unit_price), 4),  # unit_price
        round(float(cost), 2),    # cost
        round(float(profit), 2),  # profit
        float(gm),                # gross_margin
        None,                     # department
        None,                     # city
        None,                     # province
        None,                     # monthly_target
        upload_id,                # upload_id
    )


def _sales_row_rest(factory_id: str, d: date, upload_id: Optional[int] = None) -> tuple:
    """Generate one restaurant-style smart_bi_sales_data row."""
    cat = rng.choice(REST_CATEGORIES)
    region = rng.choice(REST_STORES)
    sp = f"收银员{rng.randint(1,8)}"
    cust = rng.choice(["散客", "会员客户", "企业团餐", "外卖平台"])
    # Restaurant ticket sizes are smaller
    qty = rng.randint(5, 80)
    unit_price = rng.uniform(30, 200)
    amount = round(qty * unit_price, 2)
    gm = round(rng.uniform(0.55, 0.72), 4)
    cost = round(amount * (1 - gm), 2)
    profit = round(amount - cost, 2)
    return (
        factory_id,
        d,
        cat,
        f"DISH-{rng.randint(1000,9999)}",
        cat,
        region,
        sp,
        f"CA{rng.randint(100,999)}",
        cust,
        "散客",
        round(float(amount), 2),
        round(float(qty), 4),
        round(float(unit_price), 4),
        round(float(cost), 2),
        round(float(profit), 2),
        float(gm),
        rng.choice(REST_DEPARTMENTS),
        None, None, None,
        upload_id,
    )


def _finance_row(
    factory_id: str,
    record_date: date,
    record_type: str,
    amount: float,
    category: str = "营业收入",
    dept: str = "销售部",
) -> tuple:
    """Generate one smart_bi_finance_data row."""
    if record_type == "REVENUE":
        return (
            factory_id, record_date, record_type, amount, category,
            None, None, None, None, None, None, None, None, None, None,
        )
    elif record_type == "COST":
        labor = round(amount * 0.25, 2)
        material = round(amount * 0.55, 2)
        overhead = round(amount * 0.20, 2)
        return (
            factory_id, record_date, record_type, amount, category,
            dept, material, labor, overhead, amount, None, None, None, None, None,
        )
    else:  # AR, BUDGET
        return (
            factory_id, record_date, record_type, amount, category,
            dept, None, None, None, None, None, None, None, None, None,
        )


def _agg_daily_rows(factory_id: str, d: date, store_id: int, revenue_scale: float) -> tuple:
    """Generate one agg_daily row (smartbi_db)."""
    gross = round(revenue_scale * rng.uniform(0.85, 1.15), 2)
    discount = round(gross * rng.uniform(0.03, 0.08), 2)
    net = round(gross - discount, 2)
    actual = round(net * rng.uniform(0.95, 1.0), 2)
    bills = rng.randint(40, 200)
    customers = bills + rng.randint(0, 20)
    items = bills * rng.randint(2, 5)
    return (
        factory_id,
        d,
        store_id,
        gross,
        discount,
        net,
        actual,
        bills,
        customers,
        items,
        1,            # version
    )


# ---------------------------------------------------------------------------
# SALES DATA GENERATION
# ---------------------------------------------------------------------------

def gen_sales_data_mfg(factory_id: str, start: date, end: date) -> List[tuple]:
    """Manufacturing sales: ~3-6 rows per day."""
    rows = []
    for d in _dates_in_range(start, end):
        n = rng.randint(3, 6)
        for _ in range(n):
            rows.append(_sales_row_mfg(factory_id, d))
    return rows


def gen_sales_data_rest(factory_id: str, start: date, end: date) -> List[tuple]:
    """Restaurant sales: ~8-15 rows per day (more transactions)."""
    rows = []
    for d in _dates_in_range(start, end):
        n = rng.randint(8, 15)
        for _ in range(n):
            rows.append(_sales_row_rest(factory_id, d))
    return rows


# ---------------------------------------------------------------------------
# FINANCE DATA GENERATION
# ---------------------------------------------------------------------------

def gen_finance_data(
    factory_id: str,
    start: date,
    end: date,
    monthly_revenue: float,
    revenue_growth: float = 0.005,  # 0.5% MoM growth
    margin: float = 0.33,
    is_restaurant: bool = False,
) -> List[tuple]:
    """
    Generate monthly REVENUE and COST records for smart_bi_finance_data.
    Monthly granularity mirrors how SalesAnalysisServiceImpl aggregates finance.
    """
    rows = []
    months = _months_in_range(start, end)
    rev = monthly_revenue
    for i, m in enumerate(months):
        rev_month = round(rev * (1 + revenue_growth) ** i, 2)
        cost_month = round(rev_month * (1 - margin), 2)
        cat_rev = "餐饮收入" if is_restaurant else "产品销售收入"
        cat_cost = "餐饮成本" if is_restaurant else "生产成本"
        rows.append(_finance_row(factory_id, m, "REVENUE", rev_month, cat_rev))
        rows.append(_finance_row(factory_id, m, "COST", cost_month, cat_cost))
    return rows


# ---------------------------------------------------------------------------
# AGG_DAILY GENERATION
# ---------------------------------------------------------------------------

def gen_agg_daily(
    factory_id: str,
    start: date,
    end: date,
    stores: List[Tuple[int, float]],  # (store_id, daily_revenue_base)
) -> List[tuple]:
    """
    Generate agg_daily rows for the gold path.
    Each store gets one row per day.
    """
    rows = []
    for d in _dates_in_range(start, end):
        for store_id, rev_base in stores:
            rows.append(_agg_daily_rows(factory_id, d, store_id, rev_base))
    return rows


# ---------------------------------------------------------------------------
# DB OPERATIONS
# ---------------------------------------------------------------------------

SALES_INSERT = """
INSERT INTO smart_bi_sales_data (
    factory_id, order_date, product_category, product_id, product_name,
    region, salesperson_name, salesperson_id, customer_name, customer_type,
    amount, quantity, unit_price, cost, profit, gross_margin,
    department, city, province, monthly_target, upload_id,
    created_at, updated_at
) VALUES %s
"""

FINANCE_INSERT = """
INSERT INTO smart_bi_finance_data (
    factory_id, record_date, record_type, actual_amount, category,
    department, material_cost, labor_cost, overhead_cost, total_cost,
    receivable_amount, payable_amount, collection_amount, payment_amount, variance_amount,
    created_at, updated_at
) VALUES %s
"""

AGG_DAILY_INSERT = """
INSERT INTO agg_daily (
    factory_id, date, store_id, gross_amount, discount_amount, net_amount,
    actual_receive, bill_count, customer_count, item_count, version, computed_at
) VALUES %s
ON CONFLICT (factory_id, date, store_id) DO UPDATE SET
    gross_amount = EXCLUDED.gross_amount,
    discount_amount = EXCLUDED.discount_amount,
    net_amount = EXCLUDED.net_amount,
    actual_receive = EXCLUDED.actual_receive,
    bill_count = EXCLUDED.bill_count,
    customer_count = EXCLUDED.customer_count,
    item_count = EXCLUDED.item_count,
    version = EXCLUDED.version,
    computed_at = NOW()
"""

DIM_STORE_UPSERT = """
INSERT INTO dim_store (store_id, factory_id, name, created_at, updated_at)
VALUES %s
ON CONFLICT (store_id) DO UPDATE SET
    factory_id = EXCLUDED.factory_id,
    name = EXCLUDED.name,
    updated_at = NOW()
"""

# Demo store definitions per factory (store_id, factory_id, name)
# Using high IDs to avoid collisions with real data
DEMO_STORES: Dict[str, List[Tuple[int, str, str]]] = {
    "DEMO_FACTORY": [
        (1000000001, "DEMO_FACTORY", "示范门店01"),
        (1000000002, "DEMO_FACTORY", "示范门店02"),
        (1000000003, "DEMO_FACTORY", "示范门店03"),
        (1000000004, "DEMO_FACTORY", "示范门店04"),
        (1000000005, "DEMO_FACTORY", "示范门店05"),
        (1000000006, "DEMO_FACTORY", "示范门店06"),
        (1000000007, "DEMO_FACTORY", "示范门店07"),
        (1000000008, "DEMO_FACTORY", "示范门店08"),
    ],
    "DEMO_FACTORY2": [
        (2100000000, "DEMO_FACTORY2", "新宇龙信息信息有限公司"),
        (2100000001, "DEMO_FACTORY2", "创亿信息有限公司"),
        (2100000002, "DEMO_FACTORY2", "昊嘉传媒有限公司"),
        (2100000003, "DEMO_FACTORY2", "戴硕电子传媒有限公司"),
        (2100000004, "DEMO_FACTORY2", "MBP软件网络有限公司"),
        (2100000005, "DEMO_FACTORY2", "太极科技有限公司"),
        (2100000006, "DEMO_FACTORY2", "巨奥传媒有限公司"),
    ],
    "DEMO_REST": [
        (1000000009, "DEMO_REST", "示范门店01"),
        (1000000010, "DEMO_REST", "示范门店02"),
        (1000000011, "DEMO_REST", "示范门店03"),
        (1000000012, "DEMO_REST", "示范门店04"),
        (1000000013, "DEMO_REST", "示范门店05"),
        (1000000014, "DEMO_REST", "示范门店06"),
        (1000000015, "DEMO_REST", "示范门店07"),
        (1000000016, "DEMO_REST", "示范门店08"),
    ],
    "F004": [
        (4100000000, "F004", "F004门店01"),
        (4100000001, "F004", "F004门店02"),
    ],
}


def ensure_dim_store(conn, factory_id: str, dry_run: bool) -> List[Tuple[int, float]]:
    """Insert dim_store entries for factory if FK constraint exists. Returns store list."""
    stores_def = DEMO_STORES.get(factory_id, [])
    if not stores_def:
        return []

    # Check if FK constraint exists (test env has it, prod doesn't)
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*) FROM information_schema.table_constraints
            WHERE table_name = 'agg_daily'
              AND constraint_type = 'FOREIGN KEY'
            """
        )
        has_fk = cur.fetchone()[0] > 0

    if has_fk:
        from datetime import datetime
        now = datetime.now()
        rows = [(s[0], s[1], s[2], now, now) for s in stores_def]
        if dry_run:
            print(f"  [DRY] Would upsert {len(rows)} dim_store rows for {factory_id}")
        else:
            with conn.cursor() as cur:
                # dim_store also has FORCE ROW SECURITY tenant_isolation policy
                _set_factory_guc(cur, factory_id)
                execute_values(cur, DIM_STORE_UPSERT, rows)
            conn.commit()
            print(f"  dim_store {factory_id}: upserted {len(rows)} stores")

    # Return (store_id, revenue_base) pairs from definition
    revenue_bases = {
        "DEMO_FACTORY": 80000.0,
        "DEMO_FACTORY2": 65000.0,
        "DEMO_REST": 30000.0,
        "F004": 40000.0,
    }
    base = revenue_bases.get(factory_id, 50000.0)
    return [(s[0], base) for s in stores_def]


def _add_timestamps(rows: List[tuple]) -> List[tuple]:
    """Append NOW()-style placeholders for created_at and updated_at."""
    from datetime import datetime
    now = datetime.now()
    return [r + (now, now) for r in rows]


def upsert_sales_data(conn, factory_id: str, rows: List[tuple], dry_run: bool) -> int:
    """Delete existing and insert new sales data for factory."""
    if not rows:
        return 0
    rows_with_ts = _add_timestamps(rows)
    if dry_run:
        print(f"  [DRY] Would delete+insert {len(rows)} sales rows for {factory_id}")
        return len(rows)
    with conn.cursor() as cur:
        cur.execute(
            "DELETE FROM smart_bi_sales_data WHERE factory_id = %s",
            (factory_id,)
        )
        deleted = cur.rowcount
        execute_values(cur, SALES_INSERT, rows_with_ts)
        inserted = len(rows)
    conn.commit()
    print(f"  sales_data {factory_id}: deleted {deleted}, inserted {inserted}")
    return inserted


def upsert_finance_data(conn, factory_id: str, rows: List[tuple], dry_run: bool) -> int:
    """Delete existing and insert new finance data for factory."""
    if not rows:
        return 0
    rows_with_ts = _add_timestamps(rows)
    if dry_run:
        print(f"  [DRY] Would delete+insert {len(rows)} finance rows for {factory_id}")
        return len(rows)
    with conn.cursor() as cur:
        cur.execute(
            "DELETE FROM smart_bi_finance_data WHERE factory_id = %s AND record_type IN ('REVENUE','COST')",
            (factory_id,)
        )
        deleted = cur.rowcount
        execute_values(cur, FINANCE_INSERT, rows_with_ts)
        inserted = len(rows)
    conn.commit()
    print(f"  finance_data {factory_id}: deleted {deleted}, inserted {inserted}")
    return inserted


def _set_factory_guc(cur, factory_id: str) -> None:
    """Set app.factory_id GUC so RLS policy on agg_daily allows access."""
    cur.execute("SET app.factory_id = %s", (factory_id,))


def upsert_agg_daily(conn, factory_id: str, rows: List[tuple], dry_run: bool) -> int:
    """Upsert agg_daily rows (ON CONFLICT UPDATE, safe to run multiple times).

    agg_daily has FORCE ROW SECURITY with USING (factory_id = current_setting('app.factory_id')).
    We must SET app.factory_id within each session before touching the table.
    """
    if not rows:
        return 0
    # Add computed_at=NOW() to each row (already in column order for AGG_DAILY_INSERT)
    from datetime import datetime
    now = datetime.now()
    rows_with_ts = [r + (now,) for r in rows]
    if dry_run:
        print(f"  [DRY] Would upsert {len(rows)} agg_daily rows for {factory_id}")
        return len(rows)
    with conn.cursor() as cur:
        _set_factory_guc(cur, factory_id)
        execute_values(cur, AGG_DAILY_INSERT, rows_with_ts)
        inserted = len(rows)
    conn.commit()
    print(f"  agg_daily {factory_id}: upserted {inserted}")
    return inserted


def get_agg_daily_date_range(conn, factory_id: str) -> Tuple[Optional[date], Optional[date]]:
    """Return (min_date, max_date) for factory in agg_daily.

    Sets app.factory_id GUC first so RLS allows the SELECT.
    """
    with conn.cursor() as cur:
        _set_factory_guc(cur, factory_id)
        cur.execute(
            "SELECT MIN(date), MAX(date) FROM agg_daily WHERE factory_id = %s",
            (factory_id,)
        )
        row = cur.fetchone()
    conn.commit()  # flush GUC session state
    if row and row[0]:
        return row[0], row[1]
    return None, None


def get_agg_daily_stores(conn, factory_id: str) -> List[Tuple[int, float]]:
    """Get existing store_ids and their avg daily net_amount.

    Sets app.factory_id GUC first so RLS allows the SELECT.
    """
    with conn.cursor() as cur:
        _set_factory_guc(cur, factory_id)
        cur.execute(
            """
            SELECT store_id, AVG(net_amount)
            FROM agg_daily WHERE factory_id = %s
            GROUP BY store_id
            ORDER BY store_id
            """,
            (factory_id,)
        )
        rows = cur.fetchall()
    conn.commit()
    if rows:
        return [(int(r[0]), float(r[1])) for r in rows]
    return []


# ---------------------------------------------------------------------------
# MAIN SEED LOGIC
# ---------------------------------------------------------------------------

def seed(env: str, dry_run: bool) -> None:
    cfg = ENVS[env]
    print(f"\n{'='*60}")
    print(f"Seeding demo raw tables — env={env}  dry_run={dry_run}")
    print(f"{'='*60}")

    # Date ranges
    seed_start = date(2025, 1, 1)
    seed_end = date.today()  # Include current month

    cretas_conn = psycopg2.connect(**cfg["cretas"])
    smartbi_conn = psycopg2.connect(**cfg["smartbi"])

    total_rows = 0

    # -----------------------------------------------------------------------
    # DEMO_FACTORY: already has smart_bi_sales_data and smart_bi_finance_data.
    # Only need to extend agg_daily from its current end date to today.
    # -----------------------------------------------------------------------
    print("\n[DEMO_FACTORY] Extending agg_daily to current month...")
    default_df_stores = ensure_dim_store(smartbi_conn, "DEMO_FACTORY", dry_run)
    min_d, max_d = get_agg_daily_date_range(smartbi_conn, "DEMO_FACTORY")
    if max_d is None:
        print("  No existing agg_daily — seeding from scratch")
        df_stores = default_df_stores
        agg_start = seed_start
    else:
        print(f"  Existing agg_daily: {min_d} to {max_d}")
        df_stores = get_agg_daily_stores(smartbi_conn, "DEMO_FACTORY") or default_df_stores
        # Only fill the gap
        agg_start = max_d + timedelta(days=1)

    if agg_start <= seed_end:
        print(f"  Filling gap: {agg_start} to {seed_end} ({(seed_end - agg_start).days + 1} days)")
        df_agg_rows = gen_agg_daily("DEMO_FACTORY", agg_start, seed_end, df_stores)
        total_rows += upsert_agg_daily(smartbi_conn, "DEMO_FACTORY", df_agg_rows, dry_run)
    else:
        print(f"  Already up to date (max_d={max_d})")

    # -----------------------------------------------------------------------
    # DEMO_FACTORY2: No sales data, no finance data. Seed all from scratch.
    # -----------------------------------------------------------------------
    print("\n[DEMO_FACTORY2] Seeding sales + finance + agg_daily...")
    df2_sales = gen_sales_data_mfg("DEMO_FACTORY2", seed_start, seed_end)
    total_rows += upsert_sales_data(cretas_conn, "DEMO_FACTORY2", df2_sales, dry_run)

    df2_finance = gen_finance_data(
        "DEMO_FACTORY2", seed_start, seed_end,
        monthly_revenue=8_500_000.0, revenue_growth=0.003, margin=0.32
    )
    total_rows += upsert_finance_data(cretas_conn, "DEMO_FACTORY2", df2_finance, dry_run)

    # agg_daily for DEMO_FACTORY2: ensure dim_store, then fill gap
    default_df2_stores = ensure_dim_store(smartbi_conn, "DEMO_FACTORY2", dry_run)
    min_d2, max_d2 = get_agg_daily_date_range(smartbi_conn, "DEMO_FACTORY2")
    if max_d2 is None:
        df2_stores = default_df2_stores
        agg2_start = seed_start
    else:
        df2_stores = get_agg_daily_stores(smartbi_conn, "DEMO_FACTORY2") or default_df2_stores
        agg2_start = max_d2 + timedelta(days=1)
    if agg2_start <= seed_end:
        df2_agg_rows = gen_agg_daily("DEMO_FACTORY2", agg2_start, seed_end, df2_stores)
        total_rows += upsert_agg_daily(smartbi_conn, "DEMO_FACTORY2", df2_agg_rows, dry_run)

    # -----------------------------------------------------------------------
    # DEMO_REST: Has finance data to 2026-04; agg_daily to 2026-06-06.
    # Add sales_data (was missing entirely) and extend finance to current month.
    # -----------------------------------------------------------------------
    print("\n[DEMO_REST] Adding sales_data + extending finance to current month...")
    dr_sales = gen_sales_data_rest("DEMO_REST", seed_start, seed_end)
    total_rows += upsert_sales_data(cretas_conn, "DEMO_REST", dr_sales, dry_run)

    # Finance: extend existing data (delete REVENUE+COST and reseed full range)
    dr_finance = gen_finance_data(
        "DEMO_REST", seed_start, seed_end,
        monthly_revenue=850_000.0, revenue_growth=0.006, margin=0.62,
        is_restaurant=True,
    )
    total_rows += upsert_finance_data(cretas_conn, "DEMO_REST", dr_finance, dry_run)

    # agg_daily: ensure dim_store, then fill gap from max_d + 1
    default_dr_stores = ensure_dim_store(smartbi_conn, "DEMO_REST", dry_run)
    min_dr, max_dr = get_agg_daily_date_range(smartbi_conn, "DEMO_REST")
    if max_dr is None:
        dr_stores = default_dr_stores
        agg_dr_start = seed_start
    else:
        dr_stores = get_agg_daily_stores(smartbi_conn, "DEMO_REST") or default_dr_stores
        agg_dr_start = max_dr + timedelta(days=1)
    if agg_dr_start <= seed_end:
        print(f"  DEMO_REST agg_daily gap: {agg_dr_start} to {seed_end}")
        dr_agg_rows = gen_agg_daily("DEMO_REST", agg_dr_start, seed_end, dr_stores)
        total_rows += upsert_agg_daily(smartbi_conn, "DEMO_REST", dr_agg_rows, dry_run)
    else:
        print(f"  DEMO_REST agg_daily already up to date (max_d={max_dr})")

    # -----------------------------------------------------------------------
    # F004: Has finance data only for one day (2026-02-21). Full reseed.
    # -----------------------------------------------------------------------
    print("\n[F004] Seeding sales + finance + agg_daily...")
    f4_sales = gen_sales_data_mfg("F004", seed_start, seed_end)
    total_rows += upsert_sales_data(cretas_conn, "F004", f4_sales, dry_run)

    f4_finance = gen_finance_data(
        "F004", seed_start, seed_end,
        monthly_revenue=3_200_000.0, revenue_growth=0.004, margin=0.29
    )
    total_rows += upsert_finance_data(cretas_conn, "F004", f4_finance, dry_run)

    # agg_daily for F004 (no existing data)
    default_f4_stores = ensure_dim_store(smartbi_conn, "F004", dry_run)
    min_f4, max_f4 = get_agg_daily_date_range(smartbi_conn, "F004")
    if max_f4 is None:
        f4_stores = default_f4_stores
        agg_f4_start = seed_start
    else:
        f4_stores = get_agg_daily_stores(smartbi_conn, "F004") or default_f4_stores
        agg_f4_start = max_f4 + timedelta(days=1)
    if agg_f4_start <= seed_end:
        f4_agg_rows = gen_agg_daily("F004", agg_f4_start, seed_end, f4_stores)
        total_rows += upsert_agg_daily(smartbi_conn, "F004", f4_agg_rows, dry_run)

    cretas_conn.close()
    smartbi_conn.close()

    print(f"\n{'='*60}")
    print(f"Done. Total rows written: {total_rows}")
    print(f"{'='*60}")

    # Verification summary
    print("\nVerification (run these queries to confirm):")
    print(f"  psql -h 127.0.0.1 -U cretas_user -d {cfg['cretas']['dbname']} -c \\")
    print("    \"SELECT factory_id, COUNT(*), MIN(order_date), MAX(order_date) FROM smart_bi_sales_data")
    print("     WHERE factory_id IN ('DEMO_FACTORY','DEMO_FACTORY2','DEMO_REST','F004')")
    print("     GROUP BY factory_id ORDER BY 1;\"")
    print()
    print(f"  psql -h 127.0.0.1 -U smartbi_user -d {cfg['smartbi']['dbname']} -c \\")
    print("    \"SELECT factory_id, COUNT(*), MIN(date), MAX(date) FROM agg_daily")
    print("     WHERE factory_id IN ('DEMO_FACTORY','DEMO_FACTORY2','DEMO_REST','F004')")
    print("     GROUP BY factory_id ORDER BY 1;\"")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(description="Seed SmartBI raw dashboard tables for demo tenants")
    ap.add_argument("--env", choices=["prod", "test"], default="prod",
                    help="Target environment (default: prod)")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print what would be done without writing to DB")
    args = ap.parse_args()
    seed(args.env, args.dry_run)


if __name__ == "__main__":
    main()
