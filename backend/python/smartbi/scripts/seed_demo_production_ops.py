#!/usr/bin/env python3
"""
seed_demo_production_ops.py
===========================
Seeds **factory production operations data** for the 3 manufacturing demo tenants
(DEMO_FACTORY, DEMO_FACTORY2, F004) into the cretas_db transactional tables that
the 5 empty analytics pages read from:

  cretas_db:
    * production_batches         — production_reports page, KPI (OEE/FPY/throughput)
    * production_reports         — 人效分析, 分析概览 production/trends
    * quality_inspections        — 分析概览 quality dashboard, KPI FPY
    * production_plans           — KPI outputCompletionRate (actualQuantity / plannedQuantity)
    * factory_equipment          — KPI equipmentAvailability, 分析概览 equipment dashboard

  smartbi_db (Gold path for 成本分析 page):
    * fact_production_batch      — Silver stage (ETL source for Gold)
    * agg_factory_batch_daily    — Gold page (cost-structure / yield-trend / product-cost-compare)

DEMO_REST is SKIPPED — it is a restaurant tenant; these manufacturing pages don't apply.

Date range: 2025-01-01 → --end (default today, never future).
Strategy: DELETE demo tenant rows first, then bulk INSERT.
RLS: SET app.factory_id before any write to RLS-guarded tables.

Usage (server):
  cd /www/wwwroot/cretas/code/backend/python
  source venv38/bin/activate
  python smartbi/scripts/seed_demo_production_ops.py --env prod --end 2026-06-17
  python smartbi/scripts/seed_demo_production_ops.py --env test --end 2026-06-17 --dry-run

Author: feat/demo-factory-production-ops-data worktree
"""
from __future__ import annotations

import argparse
import random
import sys
import uuid
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Any, Dict, List

try:
    import psycopg2
    from psycopg2.extras import execute_values
except ImportError:
    print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
    sys.exit(1)

# ---------------------------------------------------------------------------
# ENV CONFIG  (mirrors seed_demo_raw_tables.py)
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
# TENANT REGISTRY  (manufacturing tenants only — DEMO_REST skipped)
# ---------------------------------------------------------------------------

DEMO_TENANT_IDS = frozenset({"DEMO_FACTORY", "DEMO_FACTORY2", "DEMO_REST", "F004"})
MFG_TENANT_IDS = frozenset({"DEMO_FACTORY", "DEMO_FACTORY2", "F004"})

# Per-tenant profiles
TENANT_PROFILES: Dict[str, Dict[str, Any]] = {
    "DEMO_FACTORY": {
        "monthly_batches": 40,       # avg batches per month
        "products": [
            ("PT-DF-001", "复合调味粉500g"),
            ("PT-DF-002", "川味火锅底料200g"),
            ("PT-DF-003", "预制卤肉150g"),
            ("PT-DF-004", "中式汤底粉800g"),
            ("PT-DF-005", "香辛料复配1kg"),
        ],
        "workers": ["张伟", "李明", "王芳", "陈洁", "刘强", "赵磊", "黄丽"],
        "processes": ["称量混合", "研磨粉碎", "高温灭菌", "包装封装", "检验出厂"],
        "equipment_count": 6,
        "planned_qty_range": (500, 2000),   # kg per batch
        "unit_cost_base": 18.5,             # ¥/kg
        "yield_rate_range": (0.88, 0.96),
    },
    "DEMO_FACTORY2": {
        "monthly_batches": 28,
        "products": [
            ("PT-DF2-001", "团餐预制菜500g"),
            ("PT-DF2-002", "调味汁瓶装300ml"),
            ("PT-DF2-003", "火锅底料袋装"),
            ("PT-DF2-004", "速食粉丝200g"),
        ],
        "workers": ["孙磊", "周倩", "吴帅", "袁欢", "林峰"],
        "processes": ["原料预处理", "配比调制", "热处理杀菌", "灌装封口", "质检入库"],
        "equipment_count": 4,
        "planned_qty_range": (300, 1500),
        "unit_cost_base": 22.0,
        "yield_rate_range": (0.85, 0.94),
    },
    "F004": {
        "monthly_batches": 18,
        "products": [
            ("PT-F4-001", "香辛料混合粉200g"),
            ("PT-F4-002", "辣椒酱500g"),
            ("PT-F4-003", "酱料包装50g"),
        ],
        "workers": ["徐洋", "何倩", "冯波", "邓涛"],
        "processes": ["清洗分拣", "研磨配料", "调制加工", "包装检验"],
        "equipment_count": 3,
        "planned_qty_range": (200, 800),
        "unit_cost_base": 15.0,
        "yield_rate_range": (0.87, 0.95),
    },
}

rng = random.Random(2025_01_01)  # fixed seed for reproducibility


# ---------------------------------------------------------------------------
# HELPERS
# ---------------------------------------------------------------------------

def _assert_mfg_tenant(factory_id: str) -> None:
    if factory_id not in DEMO_TENANT_IDS:
        raise ValueError(
            f"SAFETY: factory_id '{factory_id}' is not a demo tenant. "
            f"Allowed: {sorted(DEMO_TENANT_IDS)}"
        )
    # DEMO_REST is a demo tenant but not a manufacturing tenant
    if factory_id == "DEMO_REST":
        raise ValueError("DEMO_REST is a restaurant tenant — skipping manufacturing seed")


def _set_factory_guc(cur, factory_id: str) -> None:
    """Set app.factory_id GUC so RLS policy allows access (psycopg2 txn-scoped)."""
    cur.execute("SET app.factory_id = %s", (factory_id,))


def _add_ts(rows: List[tuple]) -> List[tuple]:
    """Append created_at, updated_at to each row tuple."""
    now = datetime.now()
    return [r + (now, now) for r in rows]


def _add_ts_with_creator(rows: List[tuple], creator_id: int) -> List[tuple]:
    """Append created_at, updated_at, created_by to each row tuple."""
    now = datetime.now()
    return [r + (now, now, creator_id) for r in rows]


def _dates_in_range(start: date, end: date) -> List[date]:
    result, d = [], start
    while d <= end:
        result.append(d)
        d += timedelta(days=1)
    return result


def _months_in_range(start: date, end: date) -> List[date]:
    """First-of-month dates from start to end inclusive."""
    result = []
    d = date(start.year, start.month, 1)
    while d <= end:
        result.append(d)
        d = date(d.year + (d.month // 12), ((d.month % 12) + 1), 1)
    return result


def _short_uuid() -> str:
    return str(uuid.uuid4()).replace("-", "")[:24]


# ---------------------------------------------------------------------------
# DATA GENERATORS: production_batches
# ---------------------------------------------------------------------------

def gen_production_batches(
    factory_id: str,
    start: date,
    end: date,
) -> List[tuple]:
    """Generate COMPLETED production_batches rows.

    Columns (in order — id is BIGSERIAL, omitted so DB auto-assigns):
      factory_id, batch_number, production_plan_id,
      product_type_id, product_name,
      planned_quantity, quantity, unit,
      actual_quantity, good_quantity, defect_quantity,
      status, quality_status,
      start_time, end_time,
      equipment_id, equipment_name, supervisor_id, supervisor_name,
      worker_count, work_duration_minutes,
      material_cost, labor_cost, equipment_cost, other_cost,
      total_cost, unit_cost, yield_rate,
      is_trial
    """
    prof = TENANT_PROFILES[factory_id]
    products = prof["products"]
    workers = prof["workers"]
    planned_lo, planned_hi = prof["planned_qty_range"]
    yr_lo, yr_hi = prof["yield_rate_range"]
    uc_base = prof["unit_cost_base"]
    monthly = prof["monthly_batches"]

    rows: List[tuple] = []
    # Use months to control batch density
    months = _months_in_range(start, end)
    batch_seq = 1

    for mo in months:
        # How many batches this month?
        n_batches = rng.randint(int(monthly * 0.8), int(monthly * 1.2))
        # Spread batches across the month
        month_end = date(
            mo.year + (mo.month // 12),
            (mo.month % 12) + 1,
            1
        ) - timedelta(days=1)
        # Clamp to our overall range
        mo_start = max(mo, start)
        mo_end = min(month_end, end)
        if mo_start > mo_end:
            continue

        for _ in range(n_batches):
            prod_id, prod_name = rng.choice(products)
            planned = round(rng.uniform(planned_lo, planned_hi), 2)
            yield_rate = round(rng.uniform(yr_lo, yr_hi), 4)
            actual = round(planned * rng.uniform(0.92, 1.0), 2)
            good = round(actual * yield_rate, 2)
            defect = round(actual - good, 2)

            # Random completion date within the month
            day_offset = rng.randint(0, (mo_end - mo_start).days)
            end_day = mo_start + timedelta(days=day_offset)
            start_day = end_day - timedelta(hours=rng.randint(6, 48))
            start_dt = datetime(start_day.year, start_day.month, start_day.day,
                                rng.randint(7, 16), rng.randint(0, 59))
            end_dt = start_dt + timedelta(hours=rng.randint(6, 48))

            # Cost calculation
            material_cost = round(actual * uc_base * 0.55, 2)
            labor_cost = round(actual * uc_base * 0.25, 2)
            equipment_cost = round(actual * uc_base * 0.15, 2)
            other_cost = round(actual * uc_base * 0.05, 2)
            total_cost = round(material_cost + labor_cost + equipment_cost + other_cost, 2)
            unit_cost = round(total_cost / actual, 4) if actual > 0 else uc_base

            worker_count = rng.randint(3, len(workers))
            work_minutes = int((end_dt - start_dt).total_seconds() / 60)

            eq_num = rng.randint(1, prof["equipment_count"])
            eq_name = f"生产线{eq_num:02d}"
            sup_name = rng.choice(workers)
            batch_num = f"PB-{factory_id[:4]}-{end_day.strftime('%Y%m')}-{batch_seq:04d}"
            batch_seq += 1

            rows.append((
                # id omitted — BIGSERIAL auto-assigns
                factory_id,
                batch_num,
                None,               # production_plan_id
                prod_id,
                prod_name,
                planned,            # planned_quantity
                planned,            # quantity (same as planned at creation)
                "kg",               # unit
                actual,
                good,
                defect,
                "COMPLETED",        # status
                "PASS" if yield_rate >= 0.90 else "CONDITIONAL",   # quality_status
                start_dt,
                end_dt,
                None,               # equipment_id
                eq_name,
                None,               # supervisor_id
                sup_name,
                worker_count,
                work_minutes,
                material_cost,
                labor_cost,
                equipment_cost,
                other_cost,
                total_cost,
                unit_cost,
                round(yield_rate * 100, 2),   # yield_rate stored as %, e.g. 92.5
                False,              # is_trial
            ))

    return rows


# ---------------------------------------------------------------------------
# DATA GENERATORS: production_reports (人效分析 needs PROGRESS + HOURS types)
# ---------------------------------------------------------------------------

def _worker_id(factory_id: str, worker_name: str) -> int:
    """Return a synthetic deterministic BIGINT worker_id for a demo worker.

    production_reports.worker_id is BIGINT NOT NULL. We use a hash-based
    numeric so the same worker always gets the same id within a factory,
    avoiding FK issues (users table FK is NOT enforced by seed; demo users
    may or may not exist, but the column constraint only requires a non-null int).
    """
    # Deterministic but unique enough per factory+name
    h = sum(ord(c) for c in (factory_id + worker_name))
    return 900000 + (h % 9000)  # range 900000-908999 — unlikely to collide with real users


def gen_production_reports(
    factory_id: str,
    start: date,
    end: date,
) -> List[tuple]:
    """Generate production_reports rows (PROGRESS + HOURS types).

    Columns (id omitted — BIGSERIAL):
      factory_id, batch_id,
      worker_id, report_type, report_mode, report_date,
      reporter_name, process_category, product_name, product_type_id,
      output_quantity, good_quantity, defect_quantity,
      total_work_minutes, total_workers, operation_volume,
      labor_cost, material_cost,
      work_process_task_id, process_order, input_quantity, input_unit, output_unit,
      report_kind, status, approval_status, settled,
      synced_to_smartbi
    """
    prof = TENANT_PROFILES[factory_id]
    workers = prof["workers"]
    processes = prof["processes"]
    products = prof["products"]

    rows: List[tuple] = []

    # Generate ~2-4 PROGRESS reports per working day + 1 HOURS report per working day
    for d in _dates_in_range(start, end):
        if d.weekday() >= 6:  # skip Sundays (light weekend)
            if rng.random() < 0.6:
                continue

        # Each PROGRESS report for the day must use a DISTINCT worker, because
        # production_reports has a partial unique index
        # uk_report_worker_type_date_no_batch ON (factory_id, worker_id,
        # report_type, report_date) WHERE batch_id IS NULL. With batch_id=NULL,
        # the same (worker, PROGRESS, date) can appear at most once. Sample
        # workers without replacement (n_progress capped to worker count).
        n_progress = min(rng.randint(2, 4), len(workers))
        progress_workers = rng.sample(workers, n_progress)
        for worker in progress_workers:
            prod_id, prod_name = rng.choice(products)
            process = rng.choice(processes)
            output = round(rng.uniform(50, 300), 2)
            yr = rng.uniform(0.88, 0.97)
            good = round(output * yr, 2)
            defect = round(output - good, 2)

            rows.append((
                # id omitted — BIGSERIAL
                factory_id,
                None,                  # batch_id
                _worker_id(factory_id, worker),  # worker_id (BIGINT)
                "PROGRESS",            # report_type
                "MODE_1",              # report_mode
                d,                     # report_date
                worker,                # reporter_name
                process,               # process_category
                prod_name,             # product_name
                prod_id,               # product_type_id
                output,                # output_quantity
                good,                  # good_quantity
                defect,                # defect_quantity
                None,                  # total_work_minutes
                None,                  # total_workers
                None,                  # operation_volume
                None,                  # labor_cost
                None,                  # material_cost
                None,                  # work_process_task_id
                None,                  # process_order
                None,                  # input_quantity
                None,                  # input_unit
                None,                  # output_unit
                "OUTPUT",              # report_kind
                "SUBMITTED",           # status
                "APPROVED",            # approval_status
                True,                  # settled
                False,                 # synced_to_smartbi
            ))

        # One HOURS report per working day
        workers_on_shift = rng.randint(4, len(workers))
        work_mins = workers_on_shift * rng.randint(360, 480)  # 6-8h each
        op_volume = round(rng.uniform(200, 800), 2)
        prod_id, prod_name = rng.choice(products)
        worker = rng.choice(workers)
        rows.append((
            # id omitted — BIGSERIAL
            factory_id,
            None,
            _worker_id(factory_id, worker),  # worker_id (BIGINT)
            "HOURS",               # report_type
            "MODE_1",
            d,
            worker,
            rng.choice(processes),
            prod_name,
            prod_id,
            None,                  # output_quantity
            None,                  # good_quantity
            None,                  # defect_quantity
            work_mins,             # total_work_minutes
            workers_on_shift,      # total_workers
            op_volume,             # operation_volume
            None,
            None,
            None, None, None, None, None,
            "OUTPUT",
            "SUBMITTED",
            "APPROVED",
            True,
            False,
        ))

    _assert_no_batch_report_uniqueness(factory_id, rows)
    return rows


def _assert_no_batch_report_uniqueness(factory_id: str, rows: List[tuple]) -> None:
    """Guard against the prod-only partial unique index
    uk_report_worker_type_date_no_batch ON
      (factory_id, worker_id, report_type, report_date) WHERE batch_id IS NULL.

    test DB lacks this index (schema drift), so this in-memory check is the
    real gate. Row tuple layout (see PROD_REPORT_INSERT / gen_production_reports):
      [0]=factory_id [1]=batch_id [2]=worker_id [3]=report_type [5]=report_date
    """
    seen = set()
    for r in rows:
        if r[1] is not None:  # batch_id not NULL → different partial index
            continue
        key = (r[0], r[2], r[3], r[5])  # factory_id, worker_id, report_type, report_date
        if key in seen:
            raise AssertionError(
                f"UNIQUENESS VIOLATION for {factory_id}: duplicate no-batch report "
                f"(factory_id, worker_id, report_type, report_date)={key} would violate "
                f"uk_report_worker_type_date_no_batch"
            )
        seen.add(key)


# ---------------------------------------------------------------------------
# DATA GENERATORS: quality_inspections
# ---------------------------------------------------------------------------

def gen_quality_inspections(
    factory_id: str,
    start: date,
    end: date,
) -> List[tuple]:
    """Generate quality_inspections rows.

    Columns (id omitted — BIGSERIAL not in original DDL but quality_inspections.id
    is VARCHAR(191) per bootstrap; inspector_id is BIGINT per DDL):
      id, factory_id, production_batch_id,
      inspector_id, inspection_date, sample_size, pass_count, fail_count,
      pass_rate, result, inspection_mode, notes
    """
    rows: List[tuple] = []

    # ~1 inspection per working day, higher pass rate = realistic
    for d in _dates_in_range(start, end):
        if d.weekday() == 6:  # no Sunday inspections
            continue
        n = rng.randint(1, 3)
        for _ in range(n):
            sample = rng.randint(20, 100)
            pass_rate_raw = rng.uniform(0.88, 0.99)
            pass_count = int(sample * pass_rate_raw)
            fail_count = sample - pass_count
            pass_rate = round(Decimal(str(pass_count)) / Decimal(str(sample)) * 100, 2)
            result = "PASS" if float(pass_rate) >= 90 else "CONDITIONAL"

            rows.append((
                _short_uuid(),           # id (VARCHAR(191) PK)
                factory_id,
                None,                    # production_batch_id (BIGINT, nullable)
                None,                    # inspector_id (BIGINT, nullable per DDL)
                d,                       # inspection_date
                Decimal(str(sample)),    # sample_size
                Decimal(str(pass_count)),
                Decimal(str(fail_count)),
                pass_rate,               # pass_rate (as Decimal)
                result,
                "SAMPLING",              # inspection_mode
                "示范质检记录",           # notes
            ))

    return rows


# ---------------------------------------------------------------------------
# DATA GENERATORS: production_plans (for KPI output completion rate)
# ---------------------------------------------------------------------------

def gen_production_plans(
    factory_id: str,
    start: date,
    end: date,
) -> List[tuple]:
    """Generate production_plans rows so KPI output completion rate calculates.

    Columns:
      id, factory_id, plan_number, product_type_id, product_name,
      planned_quantity, actual_quantity, unit,
      start_time, end_time, status
    """
    prof = TENANT_PROFILES[factory_id]
    products = prof["products"]
    planned_lo, planned_hi = prof["planned_qty_range"]
    monthly = prof["monthly_batches"]
    rows: List[tuple] = []

    months = _months_in_range(start, end)
    plan_seq = 1
    for mo in months:
        n_plans = rng.randint(int(monthly * 0.9), int(monthly * 1.1))
        month_end = date(
            mo.year + (mo.month // 12),
            (mo.month % 12) + 1,
            1
        ) - timedelta(days=1)
        mo_start = max(mo, start)
        mo_end = min(month_end, end)
        if mo_start > mo_end:
            continue

        for _ in range(n_plans):
            prod_id, prod_name = rng.choice(products)
            planned = round(rng.uniform(planned_lo, planned_hi), 2)
            actual = round(planned * rng.uniform(0.88, 1.02), 2)  # 88-102% completion

            day_offset = rng.randint(0, (mo_end - mo_start).days)
            end_day = mo_start + timedelta(days=day_offset)
            start_day = end_day - timedelta(days=rng.randint(1, 5))
            start_dt = datetime(start_day.year, start_day.month, start_day.day, 8, 0)
            end_dt = datetime(end_day.year, end_day.month, end_day.day, 17, 0)

            plan_num = f"PP-{factory_id[:4]}-{end_day.strftime('%Y%m')}-{plan_seq:04d}"
            plan_seq += 1

            rows.append((
                _short_uuid(),
                factory_id,
                plan_num,
                prod_id,
                # product_name column does not exist in production_plans DDL
                planned,
                actual,
                "kg",
                start_dt,
                end_dt,
                "COMPLETED",
            ))

    return rows


# ---------------------------------------------------------------------------
# DATA GENERATORS: factory_equipment
# ---------------------------------------------------------------------------

def gen_factory_equipment(factory_id: str) -> List[tuple]:
    """Generate factory_equipment rows.

    Columns (id omitted — BIGSERIAL auto-assigns):
      factory_id, code, equipment_code, equipment_name,
      type, model, status, location,
      total_running_hours, last_maintenance_date
    """
    prof = TENANT_PROFILES[factory_id]
    n = prof["equipment_count"]
    types = ["混合机", "研磨机", "灭菌设备", "包装机", "检验台", "输送带"]
    rows = []
    for i in range(1, n + 1):
        eq_type = types[(i - 1) % len(types)]
        rows.append((
            # id omitted — BIGSERIAL
            factory_id,
            f"EQ-{factory_id[:4]}-{i:02d}",       # code
            f"EQ-{factory_id[:4]}-{i:02d}",       # equipment_code
            f"{eq_type}0{i}",                      # equipment_name
            eq_type,                               # type
            f"MODEL-{eq_type[:2]}{i:03d}",         # model
            # status — matches equipmentAvailability filter in ProductionReportServiceImpl
            "RUNNING",
            f"{factory_id[:4]}生产车间",            # location
            rng.randint(1000, 8000),               # total_running_hours
            date(2026, 3, rng.randint(1, 28)),     # last_maintenance_date
            1,                                     # version (NOT NULL)
        ))
    return rows


# ---------------------------------------------------------------------------
# DATA GENERATORS: agg_factory_batch_daily (smartbi_db Gold)
# ---------------------------------------------------------------------------

def gen_agg_factory_batch_daily(
    factory_id: str,
    start: date,
    end: date,
) -> List[tuple]:
    """Generate agg_factory_batch_daily rows directly (bypass ETL for seeding).

    Columns:
      factory_id, stat_date, product_type_id,
      batch_count,
      total_planned_qty, total_actual_qty, total_good_qty,
      avg_yield_rate,
      total_material_cost, total_labor_cost, total_equipment_cost,
      total_other_cost, total_cost,
      version, updated_at
    """
    prof = TENANT_PROFILES[factory_id]
    products = prof["products"]
    yr_lo, yr_hi = prof["yield_rate_range"]
    uc_base = prof["unit_cost_base"]
    rows: List[tuple] = []
    now = datetime.now()

    for d in _dates_in_range(start, end):
        if d.weekday() == 6:  # less activity on Sundays
            if rng.random() < 0.6:
                continue

        batches_today = rng.randint(1, 4)
        # Per-product rows
        for prod_id, prod_name in rng.sample(products, k=min(batches_today, len(products))):
            planned = round(rng.uniform(300, 1500), 2)
            actual = round(planned * rng.uniform(0.90, 1.0), 2)
            yr = round(rng.uniform(yr_lo, yr_hi), 4)
            good = round(actual * yr, 2)
            bc = rng.randint(1, 3)

            material_cost = round(actual * uc_base * 0.55, 2)
            labor_cost = round(actual * uc_base * 0.25, 2)
            equipment_cost = round(actual * uc_base * 0.15, 2)
            other_cost = round(actual * uc_base * 0.05, 2)
            total_cost = round(material_cost + labor_cost + equipment_cost + other_cost, 2)

            rows.append((
                factory_id,
                d,
                prod_id,             # product_type_id (NOT NULL = per-product row)
                bc,                  # batch_count
                planned,
                actual,
                good,
                round(yr * 100, 2),  # avg_yield_rate as %
                material_cost,
                labor_cost,
                equipment_cost,
                other_cost,
                total_cost,
                1,                   # version
                now,                 # updated_at
            ))

        # All-products rollup row (product_type_id = NULL)
        # Sum from per-product rows added just above for this date
        day_rows = [r for r in rows if r[1] == d and r[0] == factory_id and r[2] is not None]
        if day_rows:
            total_planned = round(sum(r[4] for r in day_rows), 2)
            total_actual = round(sum(r[5] for r in day_rows), 2)
            total_good = round(sum(r[6] for r in day_rows), 2)
            avg_yr = round(sum(r[7] for r in day_rows) / len(day_rows), 2)
            total_mc = round(sum(r[8] for r in day_rows), 2)
            total_lc = round(sum(r[9] for r in day_rows), 2)
            total_ec = round(sum(r[10] for r in day_rows), 2)
            total_oc = round(sum(r[11] for r in day_rows), 2)
            total_tc = round(sum(r[12] for r in day_rows), 2)
            total_bc = sum(r[3] for r in day_rows)

            rows.append((
                factory_id,
                d,
                None,                # product_type_id = NULL → COALESCE to '__ALL__'
                total_bc,
                total_planned,
                total_actual,
                total_good,
                avg_yr,
                total_mc,
                total_lc,
                total_ec,
                total_oc,
                total_tc,
                1,
                now,
            ))

    return rows


# ---------------------------------------------------------------------------
# DB WRITE OPERATIONS
# ---------------------------------------------------------------------------

PROD_BATCH_INSERT = """
INSERT INTO production_batches (
    factory_id, batch_number, production_plan_id,
    product_type_id, product_name,
    planned_quantity, quantity, unit,
    actual_quantity, good_quantity, defect_quantity,
    status, quality_status,
    start_time, end_time,
    equipment_id, equipment_name, supervisor_id, supervisor_name,
    worker_count, work_duration_minutes,
    material_cost, labor_cost, equipment_cost, other_cost,
    total_cost, unit_cost, yield_rate,
    is_trial,
    created_at, updated_at
) VALUES %s
"""

PROD_REPORT_INSERT = """
INSERT INTO production_reports (
    factory_id, batch_id,
    worker_id, report_type, report_mode, report_date,
    reporter_name, process_category, product_name, product_type_id,
    output_quantity, good_quantity, defect_quantity,
    total_work_minutes, total_workers, operation_volume,
    labor_cost, material_cost,
    work_process_task_id, process_order, input_quantity, input_unit, output_unit,
    report_kind, status, approval_status, settled,
    synced_to_smartbi,
    created_at, updated_at
) VALUES %s
"""

QUALITY_INSERT = """
INSERT INTO quality_inspections (
    id, factory_id, production_batch_id,
    inspector_id, inspection_date, sample_size, pass_count, fail_count,
    pass_rate, result, inspection_mode, notes,
    created_at, updated_at
) VALUES %s
"""

PROD_PLAN_INSERT = """
INSERT INTO production_plans (
    id, factory_id, plan_number, product_type_id,
    planned_quantity, actual_quantity, unit,
    start_time, end_time, status,
    created_at, updated_at
) VALUES %s
"""

EQUIPMENT_INSERT = """
INSERT INTO factory_equipment (
    factory_id, code, equipment_code, equipment_name,
    type, model, status, location,
    total_running_hours, last_maintenance_date,
    version,
    created_at, updated_at, created_by
) VALUES %s
"""

# agg_factory_batch_daily ON CONFLICT uses COALESCE expression index
AGG_FACTORY_BATCH_INSERT = """
INSERT INTO agg_factory_batch_daily (
    factory_id, stat_date, product_type_id,
    batch_count,
    total_planned_qty, total_actual_qty, total_good_qty,
    avg_yield_rate,
    total_material_cost, total_labor_cost, total_equipment_cost,
    total_other_cost, total_cost,
    version, updated_at
) VALUES %s
ON CONFLICT (factory_id, stat_date, COALESCE(product_type_id, '__ALL__')) DO UPDATE SET
    batch_count          = EXCLUDED.batch_count,
    total_planned_qty    = EXCLUDED.total_planned_qty,
    total_actual_qty     = EXCLUDED.total_actual_qty,
    total_good_qty       = EXCLUDED.total_good_qty,
    avg_yield_rate       = EXCLUDED.avg_yield_rate,
    total_material_cost  = EXCLUDED.total_material_cost,
    total_labor_cost     = EXCLUDED.total_labor_cost,
    total_equipment_cost = EXCLUDED.total_equipment_cost,
    total_other_cost     = EXCLUDED.total_other_cost,
    total_cost           = EXCLUDED.total_cost,
    version              = agg_factory_batch_daily.version + 1,
    updated_at           = NOW()
"""


def seed_cretas_tables(
    conn,
    factory_id: str,
    start: date,
    end: date,
    dry_run: bool,
) -> Dict[str, int]:
    """Seed production_batches, production_reports, quality_inspections, production_plans, factory_equipment."""
    _assert_mfg_tenant(factory_id)
    stats: Dict[str, int] = {}

    # --- production_batches ---
    batches = _add_ts(gen_production_batches(factory_id, start, end))
    if dry_run:
        print(f"  [DRY] {factory_id}: would seed {len(batches)} production_batches")
    else:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM production_batches WHERE factory_id = %s "
                "AND batch_number LIKE 'PB-' || LEFT(%s,4) || '%%'",
                (factory_id, factory_id),
            )
            deleted = cur.rowcount
            execute_values(cur, PROD_BATCH_INSERT, batches)
            conn.commit()
        print(f"  {factory_id}: production_batches deleted={deleted}, inserted={len(batches)}")
    stats["production_batches"] = len(batches)

    # --- production_reports ---
    reports = _add_ts(gen_production_reports(factory_id, start, end))
    if dry_run:
        print(f"  [DRY] {factory_id}: would seed {len(reports)} production_reports")
    else:
        with conn.cursor() as cur:
            # Delete only demo-seeded rows (worker_id in our synthetic numeric range)
            prof = TENANT_PROFILES[factory_id]
            worker_ids = tuple(_worker_id(factory_id, w) for w in prof["workers"])
            if len(worker_ids) == 1:
                cur.execute(
                    "DELETE FROM production_reports WHERE factory_id = %s AND worker_id = %s",
                    (factory_id, worker_ids[0]),
                )
            else:
                cur.execute(
                    "DELETE FROM production_reports WHERE factory_id = %s AND worker_id IN %s",
                    (factory_id, worker_ids),
                )
            deleted = cur.rowcount
            execute_values(cur, PROD_REPORT_INSERT, reports)
            conn.commit()
        print(f"  {factory_id}: production_reports deleted={deleted}, inserted={len(reports)}")
    stats["production_reports"] = len(reports)

    # --- quality_inspections ---
    # NOTE: quality_inspections has FK constraints:
    #   inspector_id → users(id) NOT NULL,  production_batch_id → production_batches(id) NOT NULL
    # Demo tenants DEMO_FACTORY/DEMO_FACTORY2 have no users seeded.
    # Skipping quality_inspections; KPI FPY will use hardcoded fallback (96%).
    # To enable: seed users for demo tenants first, then run with --seed-quality flag.
    print(f"  {factory_id}: quality_inspections skipped (FK constraints; FPY fallback=96)")
    stats["quality_inspections"] = 0

    # --- production_plans ---
    # NOTE: production_plans has FK constraints:
    #   factory_id → factories(id) NOT NULL (DEMO_FACTORY/DEMO_FACTORY2 not in factories table)
    #   product_type_id → product_types(id) NOT NULL (synthetic IDs don't exist)
    #   created_by → users(id) NOT NULL (no demo users for DEMO_FACTORY/DEMO_FACTORY2)
    # Skipping production_plans; KPI outputCompletionRate will use hardcoded fallback (85%).
    print(f"  {factory_id}: production_plans skipped (FK constraints; outputCompletionRate fallback=85)")
    stats["production_plans"] = 0

    # --- factory_equipment ---
    # factory_equipment has FK: factory_id → factories(id), created_by → users(id) NOT NULL.
    # Only factories that exist in the factories table AND have users can be seeded here.
    # DEMO_FACTORY/DEMO_FACTORY2 are SmartBI-only demo tenants (no factory record).
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM factories WHERE id = %s", (factory_id,))
        factory_exists = cur.fetchone()[0] > 0
        cur.execute("SELECT id FROM users WHERE factory_id = %s AND deleted_at IS NULL LIMIT 1", (factory_id,))
        user_row = cur.fetchone()

    if not factory_exists or user_row is None:
        print(
            f"  {factory_id}: factory_equipment skipped "
            "(no factory/user record; KPI equipmentAvailability fallback=85)"
        )
        stats["factory_equipment"] = 0
    else:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM factory_equipment WHERE factory_id = %s AND deleted_at IS NULL",
                (factory_id,),
            )
            existing = cur.fetchone()[0]

        if existing > 0:
            print(f"  {factory_id}: factory_equipment already has {existing} rows, skipping")
            stats["factory_equipment"] = existing
        else:
            creator_id = user_row[0]
            equip = _add_ts_with_creator(gen_factory_equipment(factory_id), creator_id)
            if dry_run:
                print(f"  [DRY] {factory_id}: would seed {len(equip)} factory_equipment")
            else:
                with conn.cursor() as cur:
                    execute_values(cur, EQUIPMENT_INSERT, equip)
                    conn.commit()
                print(f"  {factory_id}: factory_equipment inserted={len(equip)}")
            stats["factory_equipment"] = len(equip)

    return stats


def seed_smartbi_tables(
    conn,
    factory_id: str,
    start: date,
    end: date,
    dry_run: bool,
) -> Dict[str, int]:
    """Seed agg_factory_batch_daily in smartbi_db (Gold path for 成本分析 page)."""
    _assert_mfg_tenant(factory_id)
    stats: Dict[str, int] = {}

    gold_rows = gen_agg_factory_batch_daily(factory_id, start, end)

    if dry_run:
        print(f"  [DRY] {factory_id}: would seed {len(gold_rows)} agg_factory_batch_daily")
    else:
        with conn.cursor() as cur:
            _set_factory_guc(cur, factory_id)
            cur.execute("DELETE FROM agg_factory_batch_daily WHERE factory_id = %s", (factory_id,))
            deleted = cur.rowcount
            execute_values(cur, AGG_FACTORY_BATCH_INSERT, gold_rows)
            conn.commit()
        print(f"  {factory_id}: agg_factory_batch_daily deleted={deleted}, inserted={len(gold_rows)}")
    stats["agg_factory_batch_daily"] = len(gold_rows)

    return stats


# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Seed factory production ops demo data")
    p.add_argument("--env", choices=["prod", "test"], default="test",
                   help="Database environment (default: test)")
    p.add_argument("--end", default=None,
                   help="End date YYYY-MM-DD (default: today)")
    p.add_argument("--dry-run", action="store_true",
                   help="Print what would be done without writing")
    p.add_argument("--tenant", default=None,
                   help="Seed only one tenant (DEMO_FACTORY, DEMO_FACTORY2, or F004)")
    return p.parse_args()


def main() -> None:
    args = parse_args()

    end_date = date.today() if args.end is None else date.fromisoformat(args.end)
    if end_date > date.today():
        print(f"WARNING: --end {end_date} is in the future, clamping to today {date.today()}")
        end_date = date.today()
    start_date = date(2025, 1, 1)

    env_cfg = ENVS[args.env]
    env_label = args.env.upper()

    tenants = [args.tenant] if args.tenant else sorted(MFG_TENANT_IDS)
    print(f"\n=== seed_demo_production_ops.py  env={args.env}  range={start_date}→{end_date} ===")
    if args.dry_run:
        print("  *** DRY RUN — no writes ***")
    print(f"  Tenants: {tenants}\n")

    # Connect to cretas_db
    print(f"[{env_label}] Connecting to cretas ({env_cfg['cretas']['dbname']})...")
    cretas_conn = psycopg2.connect(**env_cfg["cretas"])
    cretas_conn.autocommit = False

    # Connect to smartbi_db
    print(f"[{env_label}] Connecting to smartbi ({env_cfg['smartbi']['dbname']})...")
    smartbi_conn = psycopg2.connect(**env_cfg["smartbi"])
    smartbi_conn.autocommit = False

    grand_total: Dict[str, int] = {}

    for factory_id in tenants:
        if factory_id == "DEMO_REST":
            print("\n  Skipping DEMO_REST (restaurant tenant)")
            continue
        print(f"\n--- {factory_id} ---")
        try:
            cs = seed_cretas_tables(cretas_conn, factory_id, start_date, end_date, args.dry_run)
            ss = seed_smartbi_tables(smartbi_conn, factory_id, start_date, end_date, args.dry_run)
            for k, v in {**cs, **ss}.items():
                grand_total[k] = grand_total.get(k, 0) + v
        except Exception as exc:
            print(f"  ERROR seeding {factory_id}: {exc}")
            cretas_conn.rollback()
            smartbi_conn.rollback()
            raise

    cretas_conn.close()
    smartbi_conn.close()

    print("\n=== Summary ===")
    for table, count in sorted(grand_total.items()):
        print(f"  {table}: {count:,} rows")
    print("Done.\n")


if __name__ == "__main__":
    main()
