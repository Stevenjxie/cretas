#!/usr/bin/env python3
"""
Sprint 11 D2 — Layer 4 Mock Generator for F999_MOCK Indicator Center.

Per docs/sprint-11/data-source-decision.md: Layer 1 真数据严重不足
(全部源表 0-5 行, 远低于 ≥30 阈值), Layer 4 mock 是默认数据源。

Generates 30 天 × 7 indicator = 210 行 indicator_versions for F999_MOCK
(isolated from F006 真数据, Sprint 12 切回 prod 后只换数据源即可)。

Usage:
    # 本地 (POSTGRES_HOST + PASSWORD 从 env 取):
    PGPASSWORD=cretas_pass python3 scripts/mock/generate-f006-indicator-data.py

    # 47 服务器:
    ssh root@47.100.235.168 \
      "PGPASSWORD=cretas123 python3 /www/wwwroot/cretas/code/scripts/mock/generate-f006-indicator-data.py"

Validates:
    SELECT COUNT(*) FROM indicators WHERE factory_id='F999_MOCK';        -- 7
    SELECT COUNT(*) FROM indicator_versions iv
        JOIN indicators i ON iv.indicator_id=i.id
        WHERE i.factory_id='F999_MOCK';                                  -- 210
"""

import os
import random
import uuid
from datetime import date, datetime, timedelta
from decimal import Decimal

import psycopg2
import psycopg2.extras

# ---- Configuration ----
FACTORY_ID = "F999_MOCK"
DAYS = 30
TODAY = date.today()

# Per Sprint 11 brief data 分布
INDICATORS = [
    {
        "code": "AVG_TICKET_PRICE",
        "name": "客单价",
        "category": "RESTAURANT",
        "unit": "元",
        "thresholds": {"warning_low": 25, "alert_low": 20},
        "compute": lambda d, wknd, holiday: max(
            15,
            35
            + (7 if wknd else 0)
            + (11 if holiday else 0)
            + random.gauss(0, 3),
        ),
    },
    {
        "code": "TABLE_TURNOVER",
        "name": "翻台率",
        "category": "RESTAURANT",
        "unit": "次",
        "thresholds": {"warning_low": 1.2, "alert_low": 0.8},
        "compute": lambda d, wknd, holiday: max(
            0.3,
            (2.8 if wknd else 1.8)
            + (0.3 if holiday else 0)
            - (0.5 if random.random() < 0.1 else 0)  # 偶发雨天
            + random.gauss(0, 0.15),
        ),
    },
    {
        "code": "RAW_WASTAGE_RATE",
        "name": "食材损耗率",
        "category": "RESTAURANT",
        "unit": "%",
        "thresholds": {"warning_high": 6, "alert_high": 8},
        "compute": lambda d, wknd, holiday: max(
            1.0,
            (8.5 if random.random() < 0.07 else 4.5)  # 偶发 8% 异常
            + random.gauss(0, 0.8),
        ),
    },
    {
        "code": "FACTORY_YIELD_RATE",
        "name": "良品率",
        "category": "FACTORY",
        "unit": "%",
        "thresholds": {"warning_low": 93, "alert_low": 90},
        # D15-D17 (3 days ago) 设故意触发 Alert
        "compute": lambda d, wknd, holiday: (
            88 + random.gauss(0, 1.5)
            if (TODAY - d).days in (15, 16, 17)
            else max(90, 96.5 + random.gauss(0, 0.8))
        ),
    },
    {
        "code": "FOOD_SAFETY_PASS_RATE",
        "name": "食安通过率",
        "category": "QUALITY",
        "unit": "%",
        "thresholds": {"warning_low": 98, "alert_low": 96},
        # 月底 D28 设 95% 异常
        "compute": lambda d, wknd, holiday: (
            95.0 + random.gauss(0, 0.5)
            if (TODAY - d).days == 28
            else max(96.5, 99.2 + random.gauss(0, 0.3))
        ),
    },
    {
        "code": "FACTORY_PLAN_ACHIEVE_RATE",
        "name": "计划达成率",
        "category": "FACTORY",
        "unit": "%",
        "thresholds": {"warning_low": 95, "alert_low": 85},
        # 大单日 (随机 10%) 设 85% 异常 (产能跟不上)
        "compute": lambda d, wknd, holiday: (
            85 + random.gauss(0, 2)
            if random.random() < 0.1
            else max(92, 102 + random.gauss(0, 3))
        ),
    },
    {
        "code": "DISH_GROSS_MARGIN",
        "name": "菜品毛利",
        "category": "RESTAURANT",
        "unit": "%",
        "thresholds": {"warning_low": 32, "alert_low": 28},
        "compute": lambda d, wknd, holiday: max(
            25,
            (43 if (wknd or holiday) else 38) + random.gauss(0, 1.5),
        ),
    },
]


def is_weekend(d: date) -> bool:
    return d.weekday() in (5, 6)


def is_holiday(d: date) -> bool:
    # Mock: 假节假日 = 月底 1 号 (简化)
    return d.day == 1


def main():
    host = os.environ.get("PGHOST", "localhost")
    port = int(os.environ.get("PGPORT", "5432"))
    user = os.environ.get("PGUSER", "cretas_user")
    db = os.environ.get("PGDATABASE", "cretas_db")
    password = os.environ.get("PGPASSWORD") or os.environ.get("DB_PASSWORD", "cretas_pass")

    print(f"[mock] connecting {user}@{host}:{port}/{db}")
    conn = psycopg2.connect(host=host, port=port, user=user, dbname=db, password=password)
    conn.autocommit = False
    cur = conn.cursor()

    random.seed(20260522)  # Deterministic mock for reproducibility

    # --- 1) Upsert F999_MOCK factory row if needed ---
    # (Many tables FK to factories; only insert if missing)
    cur.execute(
        """
        INSERT INTO factories
          (id, name, type, is_active, ai_weekly_quota, manually_verified,
           created_at, updated_at)
        VALUES (%s, %s, 'RESTAURANT', TRUE, 1000, TRUE, NOW(), NOW())
        ON CONFLICT (id) DO NOTHING;
        """,
        (FACTORY_ID, "F999 Mock Restaurant (Sprint 11 D2)"),
    )
    print(f"[mock] factory {FACTORY_ID} upserted")

    # --- 2) Insert 7 Indicators ---
    indicator_ids = {}
    for ind in INDICATORS:
        ind_id = str(uuid.uuid4())
        indicator_ids[ind["code"]] = ind_id
        cur.execute(
            """
            INSERT INTO indicators
              (id, factory_id, code, name, category, unit,
               compute_strategy, is_active, display_order,
               last_value, last_computed_at,
               created_at, updated_at)
            VALUES
              (%s, %s, %s, %s, %s, %s,
               'PRECOMPUTED', TRUE, %s,
               NULL, NULL,
               NOW(), NOW())
            ON CONFLICT (factory_id, code) DO UPDATE
              SET name = EXCLUDED.name,
                  category = EXCLUDED.category,
                  unit = EXCLUDED.unit,
                  updated_at = NOW()
            RETURNING id;
            """,
            (
                ind_id,
                FACTORY_ID,
                ind["code"],
                ind["name"],
                ind["category"],
                ind["unit"],
                INDICATORS.index(ind) + 1,
            ),
        )
        row = cur.fetchone()
        if row and row[0] != ind_id:
            # ON CONFLICT UPDATE returned existing id
            indicator_ids[ind["code"]] = row[0]
    print(f"[mock] inserted/updated 7 indicators")

    # --- 3) Insert thresholds (2 per indicator: warning + alert) ---
    threshold_count = 0
    for ind in INDICATORS:
        ind_id = indicator_ids[ind["code"]]
        for kind, level in ind["thresholds"].items():
            # kind 形如 warning_low / alert_high
            alert_level = "ALERT" if "alert" in kind else "WARNING"
            operator = ">=" if "high" in kind else "<="
            cur.execute(
                """
                INSERT INTO indicator_thresholds
                  (id, indicator_id, factory_id, alert_level, operator,
                   threshold_value, is_active, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, TRUE, NOW(), NOW())
                ON CONFLICT DO NOTHING;
                """,
                (
                    str(uuid.uuid4()),
                    ind_id,
                    FACTORY_ID,
                    alert_level,
                    operator,
                    Decimal(str(level)),
                ),
            )
            threshold_count += 1
    print(f"[mock] inserted {threshold_count} thresholds")

    # --- 4) Generate 30 天 × 7 indicator = 210 rows of indicator_versions ---
    version_count = 0
    last_values = {}
    for ind in INDICATORS:
        ind_id = indicator_ids[ind["code"]]
        for offset in range(DAYS - 1, -1, -1):
            d = TODAY - timedelta(days=offset)
            wknd = is_weekend(d)
            holiday = is_holiday(d)
            value = round(Decimal(ind["compute"](d, wknd, holiday)), 4)
            cur.execute(
                """
                INSERT INTO indicator_versions
                  (id, indicator_id, factory_id, value,
                   period_start, period_end, computed_at,
                   created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, NOW(), NOW());
                """,
                (
                    str(uuid.uuid4()),
                    ind_id,
                    FACTORY_ID,
                    value,
                    d,
                    d,
                    datetime.combine(d, datetime.min.time().replace(hour=23, minute=59)),
                ),
            )
            version_count += 1
            last_values[ind["code"]] = (value, d)

    print(f"[mock] inserted {version_count} indicator_versions")

    # --- 5) Update Indicator.last_value / last_computed_at to most recent ---
    for code, (value, d) in last_values.items():
        cur.execute(
            """
            UPDATE indicators
               SET last_value = %s,
                   last_computed_at = %s
             WHERE factory_id = %s AND code = %s;
            """,
            (value, datetime.combine(d, datetime.min.time().replace(hour=23, minute=59)), FACTORY_ID, code),
        )

    conn.commit()
    print(f"[mock] ✅ committed. Verify:")
    print(f"  SELECT COUNT(*) FROM indicators WHERE factory_id='{FACTORY_ID}';  -- expect 7")
    print(
        f"  SELECT COUNT(*) FROM indicator_versions iv JOIN indicators i ON iv.indicator_id=i.id "
        f"WHERE i.factory_id='{FACTORY_ID}';  -- expect 210"
    )

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
