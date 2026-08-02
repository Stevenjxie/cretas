"""Grounded restaurant reservation, demand-forecast and staffing service.

The numerical path is deterministic.  The LLM receives a compact FactBook and
may only explain evidence or suggest adjustable actions; it never calculates or
authors staffing numbers.  Simulated data is labelled at every boundary.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import math
import re
from collections import defaultdict
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple
from zoneinfo import ZoneInfo

from common.llm_metrics import llm_caller_context
from common.llm_redactor import (
    redaction_scope,
    register_values_for_egress,
    restore_in_scope,
)
from common.llm_router import SLOT, call_chain

logger = logging.getLogger(__name__)

TARGET_FACTORIES: Tuple[str, ...] = ("MOCK_REST", "RES_3101_009")
SIMULATION_SOURCE = "cretas_daily_simulator"
DAYPARTS: Tuple[str, ...] = ("午市", "下午茶", "晚市", "夜宵")
HORIZONS: Tuple[str, ...] = ("tomorrow", "week", "month")
HORIZON_LABELS = {"tomorrow": "明天", "week": "下周", "month": "下个月"}
STATUS_WEIGHT = {
    "PENDING": 0.5,
    "CONFIRMED": 1.0,
    "SEATED": 1.0,
    "COMPLETED": 1.0,
    "CANCELLED": 0.0,
    "NO_SHOW": 0.0,
}

ROLE_DEFAULTS: Tuple[Dict[str, Any], ...] = (
    {
        "role_code": "host", "role_name": "迎宾",
        "required_skill": "预订接待与排队分流", "target": 16.0,
        "minimum": 1, "current": 1, "available": 2, "max_week": 40.0,
    },
    {
        "role_code": "service", "role_name": "服务员",
        "required_skill": "堂食服务与翻台", "target": 8.0,
        "minimum": 2, "current": 3, "available": 4, "max_week": 40.0,
    },
    {
        "role_code": "kitchen", "role_name": "后厨",
        "required_skill": "备餐与出餐", "target": 7.0,
        "minimum": 2, "current": 3, "available": 4, "max_week": 40.0,
    },
    {
        "role_code": "cashier", "role_name": "收银",
        "required_skill": "收银与订单核对", "target": 20.0,
        "minimum": 1, "current": 1, "available": 2, "max_week": 40.0,
    },
)

DAYPART_SHIFT_HOURS = {"午市": 4.0, "下午茶": 3.0, "晚市": 4.0, "夜宵": 3.0}
DAYPART_CURRENT_FACTOR = {"午市": 1.0, "下午茶": 0.7, "晚市": 1.15, "夜宵": 0.65}


def _round(value: float, digits: int = 2) -> float:
    quantum = Decimal("1").scaleb(-digits)
    return float(Decimal(str(value)).quantize(quantum, rounding=ROUND_HALF_UP))


def singapore_today() -> date:
    return datetime.now(ZoneInfo("Asia/Singapore")).date()


def horizon_window(horizon: str, as_of: date) -> Tuple[date, date]:
    if horizon == "tomorrow":
        target = as_of + timedelta(days=1)
        return target, target
    if horizon == "week":
        days_until_next_monday = 7 - as_of.weekday()
        start = as_of + timedelta(days=days_until_next_monday)
        return start, start + timedelta(days=6)
    if horizon == "month":
        if as_of.month == 12:
            start = date(as_of.year + 1, 1, 1)
        else:
            start = date(as_of.year, as_of.month + 1, 1)
        if start.month == 12:
            following = date(start.year + 1, 1, 1)
        else:
            following = date(start.year, start.month + 1, 1)
        return start, following - timedelta(days=1)
    raise ValueError(f"unsupported horizon: {horizon}")


def horizon_from_question(question: str) -> str:
    normalized = (question or "").strip().lower()
    if any(token in normalized for token in ("下个月", "下月", "未来一个月", "未来30天")):
        return "month"
    if any(token in normalized for token in ("下周", "未来一周", "未来7天", "兼职")):
        return "week"
    return "tomorrow"


def _mean(values: Sequence[float]) -> Optional[float]:
    return sum(values) / len(values) if values else None


def trend_metrics(
    daily_rows: Sequence[Tuple[date, float]],
    as_of: date,
) -> Dict[str, Any]:
    """Return independent 7/30/365-day guest trends for one store/daypart."""
    metrics: Dict[str, Any] = {"data_days": len({row[0] for row in daily_rows})}
    for days in (7, 30, 365):
        floor = as_of - timedelta(days=days - 1)
        values = [float(value) for row_date, value in daily_rows if floor <= row_date <= as_of]
        metrics[f"avg_{days}"] = _round(_mean(values), 2) if values else None
        metrics[f"days_{days}"] = len(values)
    avg7 = metrics["avg_7"]
    avg30 = metrics["avg_30"]
    avg365 = metrics["avg_365"]
    metrics["trend_7_vs_30_pct"] = (
        _round((avg7 / avg30 - 1) * 100, 1) if avg7 is not None and avg30 else None
    )
    metrics["trend_30_vs_365_pct"] = (
        _round((avg30 / avg365 - 1) * 100, 1) if avg30 is not None and avg365 else None
    )
    return metrics


def trend_direction_label(metrics: Mapping[str, Any], subject: str) -> str:
    """Describe a three-window trend without asking the LLM to infer numbers."""
    avg7 = metrics.get("avg_7")
    avg30 = metrics.get("avg_30")
    avg365 = metrics.get("avg_365")
    if avg7 is None and avg30 is None and avg365 is None:
        return f"{subject}证据不足"
    labels: List[str] = []
    for current, baseline, current_name, baseline_name in (
        (avg7, avg30, "短期", "中期"),
        (avg30, avg365, "中期", "长期"),
    ):
        if current is None or baseline is None or float(baseline) == 0:
            continue
        ratio = float(current) / float(baseline)
        direction = "高于" if ratio > 1.05 else "低于" if ratio < 0.95 else "接近"
        labels.append(f"{subject}{current_name}{direction}{baseline_name}")
    return "、".join(labels) if labels else f"{subject}窗口不可比"


def _weighted_baseline(metrics: Mapping[str, Any]) -> Optional[float]:
    pairs = (
        (metrics.get("avg_7"), 0.50),
        (metrics.get("avg_30"), 0.30),
        (metrics.get("avg_365"), 0.20),
    )
    available = [(float(value), weight) for value, weight in pairs if value is not None]
    if not available:
        return None
    weight_sum = sum(weight for _, weight in available)
    return sum(value * weight for value, weight in available) / weight_sum


def daily_demand_forecast(
    metrics: Mapping[str, Any],
    weighted_reserved_guests: float,
    expected_reservation_share: float,
    horizon: str,
) -> Dict[str, Any]:
    """Forecast one service day without using historical actual/target direction.

    Historical productivity is deliberately absent from this function.  Demand
    comes from POS/traffic windows and current bookings.  Staffing constraints
    are applied later to this predicted demand.
    """
    baseline = _weighted_baseline(metrics)
    reservation_implied = (
        weighted_reserved_guests / expected_reservation_share
        if weighted_reserved_guests > 0 and expected_reservation_share > 0
        else 0.0
    )
    if baseline is None and reservation_implied <= 0:
        return {
            "available": False,
            "predicted_guests": None,
            "baseline_guests": None,
            "reservation_implied_guests": None,
            "reservation_coverage": None,
            "confidence": 0.0,
            "confidence_label": "缺少数据",
        }
    predicted = max(float(baseline or 0.0), reservation_implied)
    predicted = max(predicted, weighted_reserved_guests)
    coverage = weighted_reserved_guests / predicted if predicted > 0 else 0.0
    data_days = int(metrics.get("data_days") or 0)
    data_score = min(data_days / 180.0, 1.0) * 0.45
    reservation_score = min(coverage / max(expected_reservation_share, 0.01), 1.0) * 0.35
    horizon_penalty = {"tomorrow": 0.0, "week": 0.08, "month": 0.18}[horizon]
    confidence = max(0.10, min(0.95, 0.25 + data_score + reservation_score - horizon_penalty))
    label = "高" if confidence >= 0.75 else "中" if confidence >= 0.50 else "低"
    return {
        "available": True,
        "predicted_guests": int(round(predicted)),
        "baseline_guests": _round(float(baseline), 1) if baseline is not None else None,
        "reservation_implied_guests": _round(reservation_implied, 1) if reservation_implied else 0.0,
        "reservation_coverage": _round(coverage, 4),
        "confidence": _round(confidence, 4),
        "confidence_label": label,
    }


def role_recommendation(predicted_guests: int, policy: Mapping[str, Any]) -> Dict[str, Any]:
    target = float(policy["target_guests_per_labor_hour"])
    shift_hours = float(policy["shift_hours"])
    minimum = int(policy["minimum_staff"])
    current = int(policy["current_staff"])
    available = int(policy["available_skilled_staff"])
    required = max(minimum, math.ceil(predicted_guests / (target * shift_hours)))
    return {
        "role_code": policy["role_code"],
        "role_name": policy["role_name"],
        "required_skill": policy["required_skill"],
        "shift_hours": shift_hours,
        "target_guests_per_labor_hour": target,
        "minimum_staff": minimum,
        "current_staff": current,
        "available_skilled_staff": available,
        "recommended_staff": required,
        "gap": required - current,
        "skill_gap": max(0, required - available),
        "max_hours_per_person_week": float(policy["max_hours_per_person_week"]),
        "policy_source": policy.get("source"),
        "policy_is_simulated": bool(policy.get("is_simulated")),
        "policy_version": int(policy.get("version") or 1),
    }


def work_hour_capacity_plan(
    daily_role_rows: Sequence[Sequence[Mapping[str, Any]]],
    service_days: int,
    horizon: str,
) -> Dict[str, Any]:
    """Apply weekly-hour and skill capacity after daily concurrency planning."""
    grouped: Dict[str, List[Mapping[str, Any]]] = defaultdict(list)
    for day_roles in daily_role_rows:
        for role in day_roles:
            grouped[str(role["role_code"])].append(role)
    week_units = max(1, math.ceil(service_days / 7)) if horizon == "month" else 1
    total_gap_hours = 0.0
    total_capacity_gap_hours = 0.0
    part_time_people = 0
    for rows in grouped.values():
        first = rows[0]
        shift_hours = float(first["shift_hours"])
        max_week = float(first["max_hours_per_person_week"])
        required_hours = sum(float(row["recommended_staff"]) * shift_hours for row in rows)
        full_time_capacity = float(first["current_staff"]) * max_week * week_units
        capacity_gap_hours = max(0.0, required_hours - full_time_capacity)
        concurrent_gap_hours = sum(max(0, int(row["gap"])) * shift_hours for row in rows)
        skill_gap_hours = sum(max(0, int(row["skill_gap"])) * shift_hours for row in rows)
        role_gap_hours = max(capacity_gap_hours, concurrent_gap_hours, skill_gap_hours)
        if role_gap_hours <= 0:
            continue
        part_time_capacity = shift_hours if horizon == "tomorrow" else 24.0 * week_units
        total_gap_hours += role_gap_hours
        total_capacity_gap_hours += capacity_gap_hours
        part_time_people += math.ceil(role_gap_hours / part_time_capacity)
    return {
        "workload_gap_hours": _round(total_gap_hours, 1),
        "weekly_capacity_gap_hours": _round(total_capacity_gap_hours, 1),
        "part_time_people": part_time_people,
        "week_units": week_units,
        "rule": "daily_concurrency_plus_skill_and_weekly_hour_caps",
    }


def make_plan_fingerprint(
    factory_id: str,
    store_id: int,
    target_date: date,
    daypart: str,
    role_code: str,
    predicted_guests: int,
    recommended_staff: int,
    policy_version: int,
    fact_context: str = "",
) -> str:
    raw = "|".join(map(str, (
        factory_id, store_id, target_date.isoformat(), daypart, role_code,
        predicted_guests, recommended_staff, policy_version, fact_context,
    )))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _stable_factor(*parts: object) -> float:
    digest = hashlib.sha256("|".join(map(str, parts)).encode("utf-8")).digest()
    value = int.from_bytes(digest[:4], "big") / 0xFFFFFFFF
    return 0.82 + value * 0.36


def _numeric_free_validator(content: str) -> Optional[str]:
    """LLM prose must contain no authored numbers; numeric facts are rendered by code."""
    if re.search(r"\d", content or ""):
        return "llm_authored_number"
    if re.search(
        r"(?:第[零〇一二两三四五六七八九十百千万亿]+|"
        r"[零〇一二两三四五六七八九十百千万亿]+(?:人|名|个|天|周|月|小时|班|桌|位|成))",
        content or "",
    ):
        return "llm_authored_number"
    if any(term in (content or "") for term in ("天气导致", "活动导致", "一定是", "必然")):
        return "unsupported_causal_claim"
    return None


@dataclass
class StaffingFactBook:
    factory_id: str
    horizon: str
    window_start: date
    window_end: date
    rows: List[Dict[str, Any]]
    summary: Dict[str, Any]

    def prompt_text(self) -> str:
        lines = [
            f"范围：{HORIZON_LABELS[self.horizon]}，{self.window_start} 至 {self.window_end}",
            "以下数字由预测 FactBook 生成，大模型不得计算、改写或补充数字。",
            f"连锁汇总：预测客流 {self.summary['predicted_guests']}，"
            f"加权预订 {self.summary['reserved_guests']}，"
            f"建议人数 {self.summary['recommended_staff']}，"
            f"现有人数 {self.summary['current_staff']}，"
            f"正向缺口 {self.summary['positive_gap']}，"
            f"兼职人数 {self.summary['part_time_people']}。",
        ]
        for row in self.rows:
            lines.append(
                f"{row['store_name']} / {row['daypart']}：预测客流 {row['predicted_guests']}，"
                f"预订 {row['reserved_guests']}，覆盖率 {row['reservation_coverage_pct']}%，"
                f"建议 {row['recommended_staff']} 人，现有 {row['current_staff']} 人，"
                f"缺口 {row['gap']}，置信度 {row['confidence_pct']}%；"
                f"证据标签 {row['evidence_label']}。"
            )
        return "\n".join(lines)


class RestaurantStaffingService:
    def __init__(self, pool):
        self.pool = pool

    @staticmethod
    async def _set_tenant(conn, factory_id: str) -> None:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)

    @asynccontextmanager
    async def _tenant_connection(self, factory_id: str):
        """Keep the RLS tenant setting transaction-local on pooled connections."""
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await self._set_tenant(conn, factory_id)
                yield conn

    async def ensure_simulated_policies(self, factory_id: str) -> int:
        if factory_id not in TARGET_FACTORIES:
            return 0
        inserted = 0
        async with self._tenant_connection(factory_id) as conn:
            stores = await conn.fetch(
                "SELECT store_id FROM dim_store WHERE factory_id=$1 ORDER BY store_id", factory_id,
            )
            for store in stores:
                store_id = int(store["store_id"])
                for daypart in DAYPARTS:
                    for role in ROLE_DEFAULTS:
                        factor = DAYPART_CURRENT_FACTOR[daypart]
                        current = max(role["minimum"], round(role["current"] * factor))
                        result = await conn.execute(
                            """
                            INSERT INTO restaurant_staffing_policy(
                                factory_id, store_id, daypart, role_code, role_name,
                                required_skill, shift_hours, target_guests_per_labor_hour,
                                minimum_staff, current_staff, available_skilled_staff,
                                max_hours_per_person_week, expected_reservation_share,
                                source, is_simulated
                            ) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,0.35,$13,TRUE)
                            ON CONFLICT(factory_id,store_id,daypart,role_code) DO NOTHING
                            """,
                            factory_id, store_id, daypart, role["role_code"], role["role_name"],
                            role["required_skill"], DAYPART_SHIFT_HOURS[daypart], role["target"],
                            role["minimum"], current, max(current + 1, role["available"]),
                            role["max_week"], SIMULATION_SOURCE,
                        )
                        if result.endswith("1"):
                            inserted += 1
        return inserted

    async def roll_simulated_reservations(
        self,
        factory_id: str,
        *,
        as_of: Optional[date] = None,
        days_ahead: int = 45,
        force: bool = False,
    ) -> Dict[str, Any]:
        if factory_id not in TARGET_FACTORIES:
            raise ValueError("simulation is limited to the two authorized restaurant tenants")
        today = as_of or singapore_today()
        end = today + timedelta(days=days_ahead)
        policy_rows = await self.ensure_simulated_policies(factory_id)
        async with self._tenant_connection(factory_id) as conn:
            if not force:
                existing_audit = await conn.fetchrow(
                    """SELECT inserted_rows, updated_rows, deleted_rows, policy_rows
                         FROM restaurant_reservation_roll_audit
                        WHERE factory_id=$1 AND run_date=$2 AND source=$3""",
                    factory_id, today, SIMULATION_SOURCE,
                )
                if existing_audit:
                    return {
                        "factory_id": factory_id, "run_date": today.isoformat(),
                        "skipped": True, **dict(existing_audit),
                    }
            stores = await conn.fetch(
                "SELECT store_id, name FROM dim_store WHERE factory_id=$1 ORDER BY store_id",
                factory_id,
            )
            pos_rows = await self._fetch_pos_daily(conn, factory_id, today)
            history: Dict[Tuple[int, str], List[Tuple[date, float]]] = defaultdict(list)
            for row in pos_rows:
                history[(int(row["store_id"]), str(row["daypart"]))].append(
                    (row["date"], float(row["guests"] or 0))
                )

            payloads: List[Tuple[Any, ...]] = []
            for store in stores:
                store_id = int(store["store_id"])
                for offset in range(days_ahead + 1):
                    target = today + timedelta(days=offset)
                    lead_ratio = max(0.18, 1.0 - offset / max(days_ahead, 1))
                    for daypart in DAYPARTS:
                        metrics = trend_metrics(history[(store_id, daypart)], today)
                        base = _weighted_baseline(metrics) or (88.0 if daypart == "晚市" else 58.0)
                        weekend = 1.18 if target.weekday() >= 5 else 1.0
                        demand = base * weekend * _stable_factor(factory_id, store_id, target, daypart)
                        reserved_total = max(2, round(demand * 0.35 * (0.45 + 0.55 * lead_ratio)))
                        confirmed = max(1, round(reserved_total * 0.82))
                        pending = max(0, reserved_total - confirmed)
                        for status, guests in (("CONFIRMED", confirmed), ("PENDING", pending)):
                            if guests <= 0:
                                continue
                            external_ref = f"{store_id}:{target}:{daypart}:{status}"
                            payloads.append((
                                factory_id, SIMULATION_SOURCE, external_ref, store_id,
                                target, daypart, max(1, math.ceil(guests / 4)), guests,
                                status, True, datetime.now(ZoneInfo("Asia/Singapore")),
                            ))

            existing_refs = {
                str(row["external_ref"])
                for row in await conn.fetch(
                    """SELECT external_ref FROM fact_restaurant_reservation
                    WHERE factory_id=$1 AND source=$2
                      AND reservation_date BETWEEN $3 AND $4""",
                    factory_id, SIMULATION_SOURCE, today, end,
                )
            }
            await conn.executemany(
                """
                INSERT INTO fact_restaurant_reservation(
                    factory_id,source,external_ref,store_id,reservation_date,daypart,
                    table_count,guest_count,status,is_simulated,source_updated_at
                ) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
                ON CONFLICT(factory_id,source,external_ref) DO UPDATE SET
                    store_id=EXCLUDED.store_id,
                    reservation_date=EXCLUDED.reservation_date,
                    daypart=EXCLUDED.daypart,
                    table_count=EXCLUDED.table_count,
                    guest_count=EXCLUDED.guest_count,
                    status=EXCLUDED.status,
                    is_simulated=EXCLUDED.is_simulated,
                    source_updated_at=EXCLUDED.source_updated_at,
                    updated_at=NOW()
                """,
                payloads,
            )
            deleted = int((await conn.execute(
                """DELETE FROM fact_restaurant_reservation
                     WHERE factory_id=$1 AND source=$2
                       AND (reservation_date < $3 OR reservation_date > $4)""",
                factory_id, SIMULATION_SOURCE, today - timedelta(days=30), end,
            )).split()[-1])
            payload_refs = {str(payload[2]) for payload in payloads}
            inserted = len(payload_refs - existing_refs)
            updated = len(payload_refs & existing_refs)
            await conn.execute(
                """
                INSERT INTO restaurant_reservation_roll_audit(
                    factory_id,run_date,window_start,window_end,inserted_rows,
                    updated_rows,deleted_rows,policy_rows,source
                ) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)
                ON CONFLICT(factory_id,run_date,source) DO UPDATE SET
                    window_start=EXCLUDED.window_start,
                    window_end=EXCLUDED.window_end,
                    inserted_rows=EXCLUDED.inserted_rows,
                    updated_rows=EXCLUDED.updated_rows,
                    deleted_rows=EXCLUDED.deleted_rows,
                    policy_rows=EXCLUDED.policy_rows,
                    created_at=NOW()
                """,
                factory_id, today, today, end, inserted, updated, deleted,
                policy_rows, SIMULATION_SOURCE,
            )
        result = {
            "factory_id": factory_id, "run_date": today.isoformat(), "skipped": False,
            "inserted_rows": inserted, "updated_rows": updated,
            "deleted_rows": deleted, "policy_rows": policy_rows,
            "window_start": today.isoformat(), "window_end": end.isoformat(),
            "source": SIMULATION_SOURCE,
        }
        logger.info("[restaurant-reservation-roll] %s", json.dumps(result, ensure_ascii=False))
        return result

    async def import_reservations(
        self,
        factory_id: str,
        records: Sequence[Mapping[str, Any]],
    ) -> Dict[str, Any]:
        received = len(records)
        async with self._tenant_connection(factory_id) as conn:
            store_ids = {
                int(row["store_id"])
                for row in await conn.fetch("SELECT store_id FROM dim_store WHERE factory_id=$1", factory_id)
            }
            latest_by_key: Dict[Tuple[str, str], Tuple[Any, ...]] = {}
            for record in records:
                store_id = int(record["store_id"])
                if store_id not in store_ids:
                    raise ValueError(f"store {store_id} does not belong to current tenant")
                source = str(record["source"])
                external_ref = str(record["external_ref"])
                candidate = (
                    factory_id, source, external_ref, store_id,
                    record["reservation_date"], str(record["daypart"]),
                    int(record["table_count"]), int(record["guest_count"]),
                    str(record["status"]), bool(record.get("is_simulated", False)),
                    record["source_updated_at"],
                )
                key = (source, external_ref)
                current = latest_by_key.get(key)
                if current is None or current[10] <= candidate[10]:
                    latest_by_key[key] = candidate
            payloads = list(latest_by_key.values())
            existing = {
                (str(row["source"]), str(row["external_ref"])): row["source_updated_at"]
                for row in await conn.fetch(
                    """SELECT source,external_ref,source_updated_at
                         FROM fact_restaurant_reservation
                        WHERE factory_id=$1
                          AND source=ANY($2::text[])
                          AND external_ref=ANY($3::text[])""",
                    factory_id,
                    list({payload[1] for payload in payloads}),
                    list({payload[2] for payload in payloads}),
                )
            }
            inserted = 0
            updated = 0
            stale_ignored = 0
            replay_ignored = 0
            for payload in payloads:
                previous = existing.get((str(payload[1]), str(payload[2])))
                if previous is None:
                    inserted += 1
                elif previous < payload[10]:
                    updated += 1
                elif previous == payload[10]:
                    replay_ignored += 1
                else:
                    stale_ignored += 1
            await conn.executemany(
                """
                INSERT INTO fact_restaurant_reservation(
                    factory_id,source,external_ref,store_id,reservation_date,daypart,
                    table_count,guest_count,status,is_simulated,source_updated_at
                ) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
                ON CONFLICT(factory_id,source,external_ref) DO UPDATE SET
                    store_id=EXCLUDED.store_id,reservation_date=EXCLUDED.reservation_date,
                    daypart=EXCLUDED.daypart,table_count=EXCLUDED.table_count,
                    guest_count=EXCLUDED.guest_count,status=EXCLUDED.status,
                    is_simulated=EXCLUDED.is_simulated,
                    source_updated_at=EXCLUDED.source_updated_at,updated_at=NOW()
                WHERE fact_restaurant_reservation.source_updated_at < EXCLUDED.source_updated_at
                """,
                payloads,
            )
        return {
            "received": received,
            "deduplicated": len(payloads),
            "inserted_rows": inserted,
            "updated_rows": updated,
            "stale_ignored_rows": stale_ignored,
            "replay_ignored_rows": replay_ignored,
            "business_write_rows": inserted + updated,
        }

    async def _fetch_pos_daily(self, conn, factory_id: str, as_of: date):
        start = as_of - timedelta(days=364)
        gold_rows = await conn.fetch(
            """
            SELECT store_id,date,meal_period AS daypart,
                   SUM(COALESCE(customer_count,0))::float AS guests,
                   SUM(COALESCE(bill_count,0))::int AS orders
              FROM agg_daily_order_type_meal
             WHERE factory_id=$1 AND date BETWEEN $2 AND $3
               AND meal_period IN ('午市','下午茶','晚市','夜宵')
             GROUP BY store_id,date,meal_period
            """,
            factory_id, start, as_of,
        )
        if gold_rows:
            return gold_rows
        return await conn.fetch(
            """
            SELECT store_id,date,
                   CASE
                     WHEN EXTRACT(HOUR FROM time) BETWEEN 10 AND 13 THEN '午市'
                     WHEN EXTRACT(HOUR FROM time) BETWEEN 14 AND 16 THEN '下午茶'
                     WHEN EXTRACT(HOUR FROM time) BETWEEN 17 AND 20 THEN '晚市'
                     ELSE '夜宵'
                   END AS daypart,
                   SUM(COALESCE(customer_count,1))::float AS guests,
                   COUNT(*)::int AS orders
              FROM fact_pos_transaction
             WHERE factory_id=$1 AND date BETWEEN $2 AND $3 AND time IS NOT NULL
             GROUP BY store_id,date,daypart
            """,
            factory_id, start, as_of,
        )

    async def build_dashboard(
        self,
        factory_id: str,
        horizon: str = "tomorrow",
        *,
        store_id: Optional[int] = None,
        as_of: Optional[date] = None,
    ) -> Dict[str, Any]:
        if horizon not in HORIZONS:
            raise ValueError("horizon must be tomorrow, week or month")
        today = as_of or singapore_today()
        start, end = horizon_window(horizon, today)
        async with self._tenant_connection(factory_id) as conn:
            stores = await conn.fetch(
                """SELECT store_id,name FROM dim_store
                    WHERE factory_id=$1 AND ($2::bigint IS NULL OR store_id=$2)
                    ORDER BY name""",
                factory_id, store_id,
            )
            pos_rows = await self._fetch_pos_daily(conn, factory_id, today)
            reservations = await conn.fetch(
                """
                SELECT store_id,reservation_date,daypart,status,source,is_simulated,
                       SUM(table_count)::int AS table_count,
                       SUM(guest_count)::int AS guest_count,
                       MAX(source_updated_at) AS source_updated_at
                  FROM fact_restaurant_reservation
                 WHERE factory_id=$1 AND reservation_date BETWEEN $2 AND $3
                   AND ($4::bigint IS NULL OR store_id=$4)
                 GROUP BY store_id,reservation_date,daypart,status,source,is_simulated
                """,
                factory_id, start, end, store_id,
            )
            policies = await conn.fetch(
                """SELECT * FROM restaurant_staffing_policy
                    WHERE factory_id=$1 AND ($2::bigint IS NULL OR store_id=$2)
                    ORDER BY store_id,daypart,role_code""",
                factory_id, store_id,
            )
            historical_staffing = await conn.fetch(
                """
                SELECT store_id,daypart,weekday_type,avg_orders::float AS avg_orders,
                       staff_on_duty,target_orders_per_staff::float AS target
                  FROM fact_staffing_daypart
                 WHERE factory_id=$1
                """,
                factory_id,
            )
            adjustments = await conn.fetch(
                """
                SELECT DISTINCT ON (store_id,target_date,daypart,role_code)
                       id,store_id,target_date,daypart,role_code,adjusted_staff,
                       plan_fingerprint,created_at
                  FROM restaurant_staffing_adjustment
                 WHERE factory_id=$1 AND target_date BETWEEN $2 AND $3
                   AND ($4::bigint IS NULL OR store_id=$4)
                 ORDER BY store_id,target_date,daypart,role_code,created_at DESC,id DESC
                """,
                factory_id, start, end, store_id,
            )

        history: Dict[Tuple[int, str], List[Tuple[date, float]]] = defaultdict(list)
        orders_history: Dict[Tuple[int, str], List[Tuple[date, float]]] = defaultdict(list)
        for row in pos_rows:
            key = (int(row["store_id"]), str(row["daypart"]))
            history[key].append((row["date"], float(row["guests"] or 0)))
            orders_history[key].append((row["date"], float(row["orders"] or 0)))

        reservations_by_key: Dict[Tuple[int, date, str], List[Mapping[str, Any]]] = defaultdict(list)
        sources: Dict[str, Dict[str, Any]] = {}
        for row in reservations:
            key = (int(row["store_id"]), row["reservation_date"], str(row["daypart"]))
            reservations_by_key[key].append(row)
            sources[str(row["source"])] = {
                "source": str(row["source"]),
                "is_simulated": bool(row["is_simulated"]),
                "updated_at": row["source_updated_at"].isoformat() if row["source_updated_at"] else None,
            }

        policies_by_key: Dict[Tuple[int, str], List[Mapping[str, Any]]] = defaultdict(list)
        for policy in policies:
            policies_by_key[(int(policy["store_id"]), str(policy["daypart"]))].append(policy)

        adjustments_by_key = {
            (
                int(row["store_id"]), row["target_date"], str(row["daypart"]),
                str(row["role_code"]),
            ): row
            for row in adjustments
        }

        historical_evidence: Dict[Tuple[Optional[int], str], List[Dict[str, Any]]] = defaultdict(list)
        staffing_denominators: Dict[Tuple[Optional[int], str, str], int] = {}
        for row in historical_staffing:
            staff = int(row["staff_on_duty"] or 0)
            staffing_denominators[(
                row["store_id"], str(row["daypart"]), str(row["weekday_type"]),
            )] = staff
            actual = float(row["avg_orders"] or 0) / staff if staff else None
            historical_evidence[(row["store_id"], str(row["daypart"]))].append({
                "weekday_type": row["weekday_type"],
                "historical_orders": row["avg_orders"],
                "historical_staff": staff,
                "historical_actual_orders_per_staff": _round(actual, 2) if actual is not None else None,
                "historical_target_orders_per_staff": row["target"],
                "direction_rule": "evidence_only_not_gap_input",
            })

        daily_rows: List[Dict[str, Any]] = []
        summary_rows: List[Dict[str, Any]] = []
        for store in stores:
            sid = int(store["store_id"])
            store_name = str(store["name"])
            for daypart in DAYPARTS:
                day_policies = policies_by_key[(sid, daypart)]
                if not day_policies:
                    continue
                metrics = trend_metrics(history[(sid, daypart)], today)
                order_metrics = trend_metrics(orders_history[(sid, daypart)], today)
                productivity_history: List[Tuple[date, float]] = []
                for row_date, order_count in orders_history[(sid, daypart)]:
                    weekday_type = "weekend" if row_date.weekday() >= 5 else "weekday"
                    staff = (
                        staffing_denominators.get((sid, daypart, weekday_type))
                        or staffing_denominators.get((None, daypart, weekday_type))
                    )
                    if staff:
                        productivity_history.append((row_date, float(order_count) / staff))
                productivity_metrics = trend_metrics(productivity_history, today)
                daily_for_group: List[Dict[str, Any]] = []
                target = start
                while target <= end:
                    booking_rows = reservations_by_key[(sid, target, daypart)]
                    active_reserved = sum(
                        int(row["guest_count"] or 0)
                        for row in booking_rows
                        if STATUS_WEIGHT.get(str(row["status"]), 0.0) > 0
                    )
                    tables = sum(
                        int(row["table_count"] or 0)
                        for row in booking_rows
                        if STATUS_WEIGHT.get(str(row["status"]), 0.0) > 0
                    )
                    weighted_reserved = sum(
                        int(row["guest_count"] or 0) * STATUS_WEIGHT.get(str(row["status"]), 0.0)
                        for row in booking_rows
                    )
                    expected_share = float(day_policies[0]["expected_reservation_share"])
                    demand = daily_demand_forecast(metrics, weighted_reserved, expected_share, horizon)
                    if not demand["available"]:
                        target += timedelta(days=1)
                        continue
                    predicted = int(demand["predicted_guests"])
                    role_rows = [role_recommendation(predicted, policy) for policy in day_policies]
                    fact_context = json.dumps({
                        "weighted_reserved_guests": _round(weighted_reserved, 1),
                        "reserved_tables": tables,
                        "baseline_guests": demand["baseline_guests"],
                        "reservation_implied_guests": demand["reservation_implied_guests"],
                        "confidence": demand["confidence"],
                        "guest_trends": metrics,
                        "order_trends": order_metrics,
                        "productivity_trends": productivity_metrics,
                    }, ensure_ascii=False, sort_keys=True, default=str)
                    for role_row in role_rows:
                        role_row["plan_fingerprint"] = make_plan_fingerprint(
                            factory_id, sid, target, daypart, role_row["role_code"],
                            predicted, role_row["recommended_staff"], role_row["policy_version"],
                            fact_context,
                        )
                        applied = adjustments_by_key.get(
                            (sid, target, daypart, str(role_row["role_code"]))
                        )
                        if applied:
                            role_row["adjusted_staff"] = int(applied["adjusted_staff"])
                            role_row["effective_staff"] = int(applied["adjusted_staff"])
                            role_row["adjustment_id"] = int(applied["id"])
                            role_row["adjusted_at"] = applied["created_at"].isoformat()
                        else:
                            role_row["adjusted_staff"] = None
                            role_row["effective_staff"] = role_row["current_staff"]
                            role_row["adjustment_id"] = None
                            role_row["adjusted_at"] = None
                    current_staff = sum(row["current_staff"] for row in role_rows)
                    recommended_staff = sum(row["recommended_staff"] for row in role_rows)
                    effective_staff = sum(row["effective_staff"] for row in role_rows)
                    positive_gap = sum(max(0, row["gap"]) for row in role_rows)
                    skill_gap = sum(row["skill_gap"] for row in role_rows)
                    daily = {
                        "date": target.isoformat(), "store_id": sid, "store_name": store_name,
                        "daypart": daypart,
                        "reserved_guests": _round(weighted_reserved, 1),
                        "active_reserved_guests": active_reserved,
                        "weighted_reserved_guests": _round(weighted_reserved, 1),
                        "reserved_tables": tables, **demand,
                        "reservation_coverage_pct": _round(demand["reservation_coverage"] * 100, 1),
                        "confidence_pct": _round(demand["confidence"] * 100, 1),
                        "current_staff": current_staff,
                        "effective_staff": effective_staff,
                        "recommended_staff": recommended_staff,
                        "gap": recommended_staff - current_staff,
                        "positive_gap": positive_gap,
                        "skill_gap": skill_gap,
                        "roles": role_rows,
                    }
                    daily_for_group.append(daily)
                    daily_rows.append(daily)
                    target += timedelta(days=1)
                if not daily_for_group:
                    continue
                peak = max(daily_for_group, key=lambda row: row["predicted_guests"])
                work_hours = work_hour_capacity_plan(
                    [item["roles"] for item in daily_for_group],
                    len(daily_for_group),
                    horizon,
                )
                reservation_evidence = (
                    "当前预订已覆盖" if peak["reserved_guests"] else "当前预订未覆盖"
                )
                evidence = "；".join((
                    reservation_evidence,
                    trend_direction_label(metrics, "客流"),
                    trend_direction_label(order_metrics, "订单"),
                    trend_direction_label(productivity_metrics, "历史人效"),
                ))
                hist = (
                    historical_evidence.get((sid, daypart))
                    or historical_evidence.get((None, daypart))
                    or []
                )
                summary_rows.append({
                    "store_id": sid, "store_name": store_name, "daypart": daypart,
                    "service_days": len(daily_for_group),
                    "predicted_guests": sum(item["predicted_guests"] for item in daily_for_group),
                    "avg_daily_predicted_guests": _round(
                        sum(item["predicted_guests"] for item in daily_for_group) / len(daily_for_group), 1,
                    ),
                    "peak_daily_guests": peak["predicted_guests"],
                    "reserved_guests": sum(item["reserved_guests"] for item in daily_for_group),
                    "reserved_tables": sum(item["reserved_tables"] for item in daily_for_group),
                    "reservation_coverage_pct": _round(
                        sum(item["weighted_reserved_guests"] for item in daily_for_group)
                        / max(sum(item["predicted_guests"] for item in daily_for_group), 1) * 100, 1,
                    ),
                    "recommended_staff": peak["recommended_staff"],
                    "current_staff": peak["current_staff"],
                    "gap": peak["recommended_staff"] - peak["current_staff"],
                    "positive_gap": peak["positive_gap"],
                    "skill_gap": max(item["skill_gap"] for item in daily_for_group),
                    "confidence_pct": _round(
                        sum(item["confidence_pct"] for item in daily_for_group) / len(daily_for_group), 1,
                    ),
                    "confidence_label": peak["confidence_label"],
                    "part_time_shift_hours": work_hours["workload_gap_hours"],
                    "weekly_capacity_gap_hours": work_hours["weekly_capacity_gap_hours"],
                    "work_hour_rule": work_hours["rule"],
                    "part_time_people": work_hours["part_time_people"],
                    "trend_7_vs_30_pct": metrics["trend_7_vs_30_pct"],
                    "trend_30_vs_365_pct": metrics["trend_30_vs_365_pct"],
                    "trends": {
                        "guest_traffic": metrics,
                        "pos_orders": order_metrics,
                        "historical_productivity": {
                            **productivity_metrics,
                            "direction_rule": "evidence_only_not_gap_input",
                            "denominator": "fact_staffing_daypart staff_on_duty",
                        },
                    },
                    "historical_productivity": hist,
                    "historical_productivity_rule": "依据与置信度证据，不直接推导缺人",
                    "evidence_label": evidence,
                    "roles": peak["roles"],
                })

        total_predicted = sum(row["predicted_guests"] for row in summary_rows)
        total_reserved = sum(row["reserved_guests"] for row in summary_rows)
        summary = {
            "store_count": len(stores),
            "row_count": len(summary_rows),
            "predicted_guests": total_predicted,
            "reserved_guests": total_reserved,
            "reservation_coverage_pct": _round(total_reserved / max(total_predicted, 1) * 100, 1),
            "recommended_staff": sum(row["recommended_staff"] for row in summary_rows),
            "current_staff": sum(row["current_staff"] for row in summary_rows),
            "positive_gap": sum(row["positive_gap"] for row in summary_rows),
            "skill_gap": sum(row["skill_gap"] for row in summary_rows),
            "part_time_people": sum(row["part_time_people"] for row in summary_rows),
            "confidence_pct": _round(
                sum(row["confidence_pct"] for row in summary_rows) / len(summary_rows), 1,
            ) if summary_rows else 0.0,
        }
        return {
            "factory_id": factory_id,
            "horizon": horizon,
            "horizon_label": HORIZON_LABELS[horizon],
            "as_of": today.isoformat(),
            "generated_at": datetime.now(ZoneInfo("Asia/Singapore")).isoformat(),
            "numeric_source": "forecast_factbook_only",
            "historical_productivity_rule": "evidence_only_not_gap_input",
            "window_start": start.isoformat(),
            "window_end": end.isoformat(),
            "summary": summary,
            # Public contract names intentionally match the Web DTO after the
            # shared snake_case -> camelCase transform.
            "summary_rows": summary_rows,
            "daily_rows": daily_rows,
            "sources": list(sources.values()),
            "method": {
                "demand": "weighted current reservations plus 7/30/365-day POS guest trends",
                "staffing": "predicted guests / role target guests per labor hour / shift hours, then skill minimums",
                "historical_productivity": "evidence only; actual below target never directly means understaffed",
                "simulated_data_visible": True,
            },
        }

    async def answer_question(
        self,
        factory_id: str,
        question: str,
        *,
        role: Optional[str] = None,
        as_of: Optional[date] = None,
    ) -> Dict[str, Any]:
        horizon = horizon_from_question(question)
        dashboard = await self.build_dashboard(factory_id, horizon, as_of=as_of)
        rows = sorted(
            dashboard["summary_rows"],
            key=lambda row: (row["positive_gap"], row["predicted_guests"]),
            reverse=True,
        )
        factbook = StaffingFactBook(
            factory_id=factory_id,
            horizon=horizon,
            window_start=date.fromisoformat(dashboard["window_start"]),
            window_end=date.fromisoformat(dashboard["window_end"]),
            rows=rows,
            summary=dashboard["summary"],
        )
        system = (
            "你是餐饮预测排班分析助手。必须理解用户问题并基于给定 FactBook 解释原因、"
            "指出技能或工时风险、提出可调整建议。禁止输出任何阿拉伯数字、百分数、日期、"
            "金额或人数；数字由程序在回答前半段展示。禁止把历史实际人效低于目标解释成缺人。"
            "不得声称已执行调整；动作必须写成可预览、待确认、可回滚的建议。"
        )
        payload = {
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": f"用户角色：{role or '未提供'}\n用户问题：{question}\n\n{factbook.prompt_text()}"},
            ],
            "temperature": 0.2,
            "max_tokens": 500,
        }
        with redaction_scope():
            register_values_for_egress([row["store_name"] for row in rows])
            with llm_caller_context("restaurant_staffing_forecast", factory_id=factory_id):
                body = await call_chain(
                    SLOT.INSIGHTS,
                    payload,
                    timeout=45.0,
                    total_timeout=55.0,
                    content_validator=_numeric_free_validator,
                )
            narrative = (
                body.get("choices", [{}])[0].get("message", {}).get("content", "") or ""
            ).strip()
            narrative = restore_in_scope(narrative)
        validation_error = _numeric_free_validator(narrative)
        if validation_error or not narrative:
            raise RuntimeError(f"LLM staffing narrative failed grounding: {validation_error or 'empty'}")

        summary = dashboard["summary"]
        deterministic = [
            f"**{dashboard['horizon_label']}预测排班 FactBook**",
            f"- 范围：{dashboard['window_start']} 至 {dashboard['window_end']}",
            f"- 预测客流：{summary['predicted_guests']} 人；当前预订：{summary['reserved_guests']} 人；"
            f"预订覆盖：{summary['reservation_coverage_pct']}%",
            f"- 峰值建议人数合计：{summary['recommended_staff']}；现有人数合计："
            f"{summary['current_staff']}；正向缺口：{summary['positive_gap']}",
            f"- 兼职人数建议：{summary['part_time_people']}；平均置信度：{summary['confidence_pct']}%",
        ]
        for row in rows:
            deterministic.append(
                f"- {row['store_name']} / {row['daypart']}：预测 {row['predicted_guests']} 人，"
                f"预订 {row['reserved_guests']} 人，建议 {row['recommended_staff']} 人，"
                f"现有 {row['current_staff']} 人，缺口 {row['gap']}，置信度 {row['confidence_pct']}%"
            )
        deterministic.extend([
            "",
            "**大模型解读（只解释 FactBook，不生成数字）**",
            narrative,
            "",
            "历史人效仅作为趋势证据；缺口由未来预测需求、岗位技能最低覆盖与工时约束计算。",
        ])
        return {
            "answer_text": "\n".join(deterministic),
            "dashboard": dashboard,
            "factbook": factbook.prompt_text(),
            "llm_used": True,
            "llm_numeric_authorship": False,
            "horizon": horizon,
        }

    async def apply_adjustment(
        self,
        factory_id: str,
        payload: Mapping[str, Any],
        *,
        actor_user_id: str,
        actor_role: str,
    ) -> Dict[str, Any]:
        allowed_roles = {
            "restaurant_owner", "restaurant_manager", "hr_admin",
            "factory_super_admin", "platform_admin", "permission_admin",
        }
        if actor_role not in allowed_roles:
            raise PermissionError("current role cannot adjust restaurant staffing")
        target_date = payload["target_date"]
        if isinstance(target_date, str):
            target_date = date.fromisoformat(target_date)
        adjusted = int(payload["adjusted_staff"])
        if adjusted < 0:
            raise ValueError("adjusted_staff must be non-negative")
        reason = str(payload.get("reason") or "").strip()
        if not reason:
            raise ValueError("adjustment reason is required")
        today = singapore_today()
        horizon: Optional[str] = None
        for candidate in HORIZONS:
            candidate_start, candidate_end = horizon_window(candidate, today)
            if candidate_start <= target_date <= candidate_end:
                horizon = candidate
                break
        if horizon is None:
            raise ValueError("target date is outside the current forecast horizons")
        fresh_dashboard = await self.build_dashboard(
            factory_id, horizon, store_id=int(payload["store_id"]), as_of=today
        )
        fresh_daily = next(
            (
                row for row in fresh_dashboard["daily_rows"]
                if row["date"] == target_date.isoformat()
                and row["daypart"] == str(payload["daypart"])
            ),
            None,
        )
        if fresh_daily is None:
            raise ValueError("forecast plan no longer exists; refresh the preview")
        fresh_role = next(
            (
                row for row in fresh_daily["roles"]
                if row["role_code"] == str(payload["role_code"])
            ),
            None,
        )
        if fresh_role is None:
            raise ValueError("forecast role no longer exists; refresh the preview")
        if int(payload["predicted_guests"]) != int(fresh_daily["predicted_guests"]):
            raise ValueError("predicted demand changed; refresh the preview")
        if int(payload["policy_version"]) != int(fresh_role["policy_version"]):
            raise ValueError("staffing policy changed; refresh the preview")
        if int(payload["recommended_staff"]) != int(fresh_role["recommended_staff"]):
            raise ValueError("recommended staff changed; refresh the preview")
        if int(payload["prior_staff"]) != int(fresh_role["current_staff"]):
            raise ValueError("current staffing changed; refresh the preview")
        if not hmac.compare_digest(
            str(payload["plan_fingerprint"]), str(fresh_role["plan_fingerprint"])
        ):
            raise ValueError("forecast plan changed; refresh the preview")
        created = False
        async with self._tenant_connection(factory_id) as conn:
            policy = await conn.fetchrow(
                """
                SELECT * FROM restaurant_staffing_policy
                 WHERE factory_id=$1 AND store_id=$2 AND daypart=$3 AND role_code=$4
                """,
                factory_id, int(payload["store_id"]), str(payload["daypart"]),
                str(payload["role_code"]),
            )
            if not policy:
                raise ValueError("staffing policy no longer exists")
            predicted_guests = int(payload["predicted_guests"])
            policy_version = int(payload["policy_version"])
            if int(policy["version"]) != policy_version:
                raise ValueError("staffing policy changed; refresh the preview")
            recommendation = role_recommendation(predicted_guests, policy)
            recommended_staff = int(payload["recommended_staff"])
            if recommendation["recommended_staff"] != recommended_staff:
                raise ValueError("recommended staff changed; refresh the preview")
            if recommendation["current_staff"] != int(payload["prior_staff"]):
                raise ValueError("current staffing changed; refresh the preview")
            expected_fingerprint = str(fresh_role["plan_fingerprint"])
            if not hmac.compare_digest(
                expected_fingerprint, str(payload["plan_fingerprint"])
            ):
                raise ValueError("forecast plan changed; refresh the preview")
            existing = await conn.fetchrow(
                """
                SELECT * FROM restaurant_staffing_adjustment
                 WHERE factory_id=$1 AND idempotency_key=$2
                """,
                factory_id, str(payload["idempotency_key"]),
            )
            if existing:
                same_request = all((
                    int(existing["store_id"]) == int(payload["store_id"]),
                    existing["target_date"] == target_date,
                    str(existing["daypart"]) == str(payload["daypart"]),
                    str(existing["role_code"]) == str(payload["role_code"]),
                    int(existing["adjusted_staff"]) == adjusted,
                    str(existing["plan_fingerprint"]) == expected_fingerprint,
                ))
                if not same_request:
                    raise ValueError("idempotency key was already used for another adjustment")
                row = existing
            else:
                created = True
                row = await conn.fetchrow(
                """
                INSERT INTO restaurant_staffing_adjustment(
                    factory_id,store_id,target_date,daypart,role_code,
                    predicted_guests,policy_version,prior_staff,
                    recommended_staff,adjusted_staff,plan_fingerprint,reason,
                    actor_user_id,actor_role,idempotency_key
                ) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
                RETURNING *
                """,
                factory_id, int(payload["store_id"]), target_date, str(payload["daypart"]),
                str(payload["role_code"]), predicted_guests, policy_version,
                int(payload["prior_staff"]), recommended_staff, adjusted,
                str(payload["plan_fingerprint"]), reason, actor_user_id, actor_role,
                str(payload["idempotency_key"]),
                )
        return {
            "adjustment_id": int(row["id"]),
            "created_at": row["created_at"].isoformat(),
            "business_write": created,
            "idempotent_replay": not created,
            "factory_id": factory_id,
            "store_id": int(payload["store_id"]),
            "target_date": target_date.isoformat(),
            "daypart": str(payload["daypart"]),
            "role_code": str(payload["role_code"]),
            "predicted_guests": int(payload["predicted_guests"]),
            "policy_version": int(payload["policy_version"]),
            "recommended_staff": int(payload["recommended_staff"]),
            "prior_staff": int(payload["prior_staff"]),
            "adjusted_staff": adjusted,
        }
