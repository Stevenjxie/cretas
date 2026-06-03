"""G4 — HealthCheckMetricsBuilder.

Aggregates three data sources into one flat ``metrics`` dict for
``DiagnosticsEngine.run()``:

  1. finance (smart_bi_finance_data, REVENUE/COST) — mirrors Java
     ``RestaurantFinancialMetricsFetcher``: food/labor cost ratios + cost
     rigidity. Either injected by the caller (``finance_metrics``) or queried
     directly via asyncpg.
  2. POS (agg_daily_order_type_meal + fact_pos_transaction, gold layer) —
     discount_rate / delivery_dependency / channel_collection_rate.
  3. reviews (smart_bi_dynamic_data JSONB, 大众点评) — 5-star pct MoM change.

⛔ Scale invariants (must NOT be converted):
  - food_cost_ratio / labor_cost_ratio / discount_rate → 0-100 scale
    (benchmark YAML ranges are %-scale, e.g. [35,45]).
  - cost_rigidity / delivery_dependency / channel_collection_rate /
    review_score_decline → 0-1 scale (inline thresholds, e.g. "< 0.70").

Honest failure: a missing/insufficient source records a ``coverage`` skip
reason — it never fabricates a metric value. Partial bundles are valid.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

# Keyword buckets — 1:1 mirror of Java RestaurantFinancialMetricsFetcher.
_FOOD_KEYWORDS = ("食材", "原材料", "食品", "饮料", "酒水", "菜品")
_LABOR_KEYWORDS = ("人工", "工资", "薪", "员工", "劳务")
_RENT_KEYWORDS = ("租金", "房租", "店租", "场地")

# 外卖类 order_type / channel buckets for delivery_dependency.
_DELIVERY_ORDER_TYPES = ("外卖", "外送", "配送")

# channel_origin canonicalization → commission_rates.yaml key space.
# fact_pos_transaction.channel_origin stores SHORT forms (美团/抖音/饿了么/...),
# but commission_rates.yaml keys are FULL names (美团外卖/抖音外卖/...). Without
# this mapping, default_rates.get('美团') → None → 0% commission → false-healthy
# channel_collection_rate. Keys here are the short raw values; values are the
# canonical yaml key. (饿了么/微信小程序/京东外卖 already match yaml verbatim.)
_CHANNEL_ALIAS = {
    "美团": "美团外卖",
    "美团外卖": "美团外卖",
    "抖音": "抖音外卖",
    "抖音外卖": "抖音外卖",
    "抖音团购": "抖音团购",
    "饿了么": "饿了么",
    "饿了么外卖": "饿了么",
    "京东": "京东外卖",
    "京东外卖": "京东外卖",
    "微信": "微信小程序",
    "微信小程序": "微信小程序",
    "小程序": "微信小程序",
    # dine-in / self-operated → 0% commission canonical keys
    "堂食": "堂食",
    "自点": "堂食",
    "自营": "自营外卖",
    "自营外卖": "自营外卖",
    "自提": "外卖自提",
    "外卖自提": "外卖自提",
    "企业团餐": "企业团餐",
    "团餐": "企业团餐",
}

# commission_rates.yaml path (for D2 estimate of channel_collection_rate).
_KB_ROOT = Path(__file__).resolve().parents[2] / "knowledge" / "restaurant"


@dataclass
class HealthCheckBundle:
    metrics: dict[str, float]            # flat dict for DiagnosticsEngine.run()
    coverage: dict[str, str]             # metric_key → "ok" | "skipped:reason"
    period: str                          # resolved "YYYY-MM"
    upload_id: Optional[int] = None
    channel_collection_estimated: bool = False  # D2 commission-estimate flag
    notes: dict[str, str] = field(default_factory=dict)  # extra per-metric annotations


# All metric keys this builder is responsible for (used to default-skip).
_FINANCE_KEYS = ("food_cost_ratio", "labor_cost_ratio", "cost_rigidity")
_POS_KEYS = ("discount_rate", "delivery_dependency", "channel_collection_rate")
_REVIEW_KEYS = ("review_score_decline",)


def _resolve_month_range(raw: Optional[str]) -> tuple[date, date]:
    """Resolve "上月"/"本月"/"YYYY-MM"/"YYYY年M月"/None → (start, end).

    Default (None / 上月) = previous calendar month, mirroring Java
    RestaurantFinancialMetricsFetcher.resolveMonthRange.
    """
    import calendar
    import re

    today = date.today()

    def _month_bounds(year: int, month: int) -> tuple[date, date]:
        last_day = calendar.monthrange(year, month)[1]
        return date(year, month, 1), date(year, month, last_day)

    if raw is None or not str(raw).strip() or str(raw).strip() == "上月":
        prev_month = today.month - 1 or 12
        prev_year = today.year if today.month > 1 else today.year - 1
        return _month_bounds(prev_year, prev_month)

    s = str(raw).strip()
    if s in ("本月", "this month", "This Month"):
        return _month_bounds(today.year, today.month)

    m = re.match(r"^\s*(\d{4})\s*[年\-/]\s*(\d{1,2})\s*月?\s*$", s)
    if m:
        try:
            year = int(m.group(1))
            month = int(m.group(2))
            if 1 <= month <= 12:
                return _month_bounds(year, month)
        except (ValueError, OverflowError):
            pass

    # Fallback: previous month.
    prev_month = today.month - 1 or 12
    prev_year = today.year if today.month > 1 else today.year - 1
    return _month_bounds(prev_year, prev_month)


def _prev_month_range(start: date) -> tuple[date, date]:
    import calendar
    prev_month = start.month - 1 or 12
    prev_year = start.year if start.month > 1 else start.year - 1
    last_day = calendar.monthrange(prev_year, prev_month)[1]
    return date(prev_year, prev_month, 1), date(prev_year, prev_month, last_day)


class HealthCheckMetricsBuilder:
    """聚合 finance + POS + 点评 三路数据 → 统一指标 dict 供 DiagnosticsEngine.run()。"""

    def __init__(self, kb_root: Optional[Path] = None) -> None:
        self.kb_root = kb_root or _KB_ROOT
        self._commission_yaml: Optional[dict] = None

    async def build(
        self,
        factory_id: str,
        period: Optional[str],
        sub_sector: str = "",
        table_count: Optional[int] = None,  # noqa: ARG002 — table_turnover deferred (Phase 2)
        cretas_pool: Any = None,
        smartbi_pool: Any = None,
        finance_metrics: Optional[dict] = None,
    ) -> HealthCheckBundle:
        start, end = _resolve_month_range(period)
        resolved_period = f"{start.year:04d}-{start.month:02d}"

        metrics: dict[str, float] = {}
        coverage: dict[str, str] = {}
        notes: dict[str, str] = {}
        upload_id: Optional[int] = None
        channel_estimated = False

        # ── 1. finance ────────────────────────────────────────────
        if finance_metrics is None and smartbi_pool is not None:
            try:
                prev_start, prev_end = _prev_month_range(start)
                finance_metrics, upload_id = await self._fetch_finance_metrics(
                    smartbi_pool, factory_id, start, end, prev_start, prev_end
                )
            except Exception as e:  # noqa: BLE001
                logger.warning("[health-check] finance query failed for %s: %s", factory_id, e)
                finance_metrics = None

        self._merge_finance(metrics, coverage, finance_metrics)

        # ── 2. POS (gold) ─────────────────────────────────────────
        pos_metrics: dict[str, float] = {}
        try:
            pos_metrics = await self._fetch_pos_metrics(
                factory_id, start, end, smartbi_pool=smartbi_pool, sub_sector=sub_sector
            ) or {}
        except Exception as e:  # noqa: BLE001
            logger.warning("[health-check] POS query failed for %s: %s", factory_id, e)
            pos_metrics = {}

        channel_estimated = bool(pos_metrics.pop("_channel_estimated", False))
        if pos_metrics.get("_channel_note"):
            notes["channel_collection_rate"] = str(pos_metrics.pop("_channel_note"))
        self._merge_pos(metrics, coverage, pos_metrics)

        # ── 3. reviews ────────────────────────────────────────────
        try:
            review_decline = await self._fetch_review_decline(
                factory_id, start, end, smartbi_pool=smartbi_pool
            )
        except Exception as e:  # noqa: BLE001
            logger.warning("[health-check] review query failed for %s: %s", factory_id, e)
            review_decline = None

        if review_decline is not None:
            metrics["review_score_decline"] = review_decline
            coverage["review_score_decline"] = "ok"
        else:
            coverage["review_score_decline"] = "skipped:暂无点评数据"

        return HealthCheckBundle(
            metrics=metrics,
            coverage=coverage,
            period=resolved_period,
            upload_id=upload_id,
            channel_collection_estimated=channel_estimated,
            notes=notes,
        )

    # ── finance ───────────────────────────────────────────────────

    def _merge_finance(
        self, metrics: dict, coverage: dict, finance: Optional[dict]
    ) -> None:
        if not finance:
            for k in _FINANCE_KEYS:
                coverage[k] = "skipped:无财务数据(请上传财务 Excel)"
            return

        # food/labor cost ratio — 0-100 scale, passed through unchanged.
        food = finance.get("foodCostRatio")
        if food is not None:
            metrics["food_cost_ratio"] = float(food)
            coverage["food_cost_ratio"] = "ok"
        else:
            coverage["food_cost_ratio"] = "skipped:无食材成本数据"

        labor = finance.get("laborCostRatio")
        if labor is not None:
            metrics["labor_cost_ratio"] = float(labor)
            coverage["labor_cost_ratio"] = "ok"
        else:
            coverage["labor_cost_ratio"] = "skipped:无人力成本数据"

        # cost_rigidity — only meaningful when revenue declined (Java rule).
        rigidity = finance.get("costRigidity")
        rev_change = finance.get("revenueChangePct")
        if rigidity is not None:
            metrics["cost_rigidity"] = float(rigidity)
            coverage["cost_rigidity"] = "ok"
        elif rev_change is not None and rev_change >= -0.05:
            coverage["cost_rigidity"] = "skipped:营收未明显下滑(无需弹性诊断)"
        else:
            coverage["cost_rigidity"] = "skipped:环比数据不足"

    async def _fetch_finance_metrics(
        self,
        pool: Any,
        factory_id: str,
        start: date,
        end: date,
        prev_start: date,
        prev_end: date,
    ) -> tuple[dict, Optional[int]]:
        """Python mirror of Java RestaurantFinancialMetricsFetcher.fetch().

        Returns (camelCase finance dict, latest upload_id). Empty dict if no
        REVENUE+COST rows.
        """
        if pool is None:
            raise ValueError(
                "_fetch_finance_metrics: smartbi_pool required (got None)"
            )

        async def _rows(rec_type: str, s: date, e: date):
            async with pool.acquire() as conn:
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                return await conn.fetch(
                    """
                    SELECT category, actual_amount, total_cost, upload_id
                      FROM smart_bi_finance_data
                     WHERE factory_id = $1 AND record_type = $2
                       AND record_date BETWEEN $3 AND $4
                    """,
                    factory_id, rec_type, s, e,
                )

        revenue_rows = self._latest_upload(await _rows("REVENUE", start, end))
        cost_rows = self._latest_upload(await _rows("COST", start, end))
        if not revenue_rows and not cost_rows:
            return {}, None

        upload_id = self._max_upload(revenue_rows + cost_rows)

        revenue = self._sum_revenue_category(revenue_rows, "收入")
        food_cost = self._sum_cost_by_keywords(cost_rows, _FOOD_KEYWORDS)
        labor_cost = self._sum_cost_by_keywords(cost_rows, _LABOR_KEYWORDS)

        # previous period (for change ratios / cost_rigidity)
        prev_revenue_rows = self._latest_upload(await _rows("REVENUE", prev_start, prev_end))
        prev_cost_rows = self._latest_upload(await _rows("COST", prev_start, prev_end))
        prev_revenue = self._sum_revenue_category(prev_revenue_rows, "收入")
        prev_labor = self._sum_cost_by_keywords(prev_cost_rows, _LABOR_KEYWORDS)

        food_ratio = self._ratio_pct(food_cost, revenue)
        labor_ratio = self._ratio_pct(labor_cost, revenue)
        rev_change = self._change_ratio(revenue, prev_revenue)
        labor_change = self._change_ratio(labor_cost, prev_labor)

        cost_rigidity = None
        if (rev_change is not None and rev_change < -0.05
                and labor_change is not None and rev_change != 0):
            cost_rigidity = labor_change / rev_change

        return {
            "revenue": float(revenue),
            "foodCostRatio": food_ratio,
            "laborCostRatio": labor_ratio,
            "revenueChangePct": rev_change,
            "laborCostChangePct": labor_change,
            "costRigidity": cost_rigidity,
        }, upload_id

    @staticmethod
    def _latest_upload(rows: list) -> list:
        if not rows:
            return []
        uploads = [r["upload_id"] for r in rows if r["upload_id"] is not None]
        if not uploads:
            return list(rows)
        mx = max(uploads)
        return [r for r in rows if r["upload_id"] == mx]

    @staticmethod
    def _max_upload(rows: list) -> Optional[int]:
        uploads = [r["upload_id"] for r in rows if r["upload_id"] is not None]
        return max(uploads) if uploads else None

    @staticmethod
    def _sum_revenue_category(rows: list, keyword: str) -> float:
        total = 0.0
        for r in rows:
            cat = r["category"]
            if cat is not None and keyword in cat and r["actual_amount"] is not None:
                total += float(r["actual_amount"])
        return total

    @staticmethod
    def _sum_cost_by_keywords(rows: list, keywords: tuple) -> float:
        total = 0.0
        for r in rows:
            cat = r["category"]
            if cat is None:
                continue
            if any(kw in cat for kw in keywords):
                amt = r["total_cost"] if r["total_cost"] is not None else r["actual_amount"]
                if amt is not None:
                    total += abs(float(amt))
        return total

    @staticmethod
    def _ratio_pct(numerator: float, denominator: float) -> Optional[float]:
        if numerator == 0 or denominator <= 0:
            return None
        return round(numerator / denominator * 100, 4)

    @staticmethod
    def _change_ratio(current: float, previous: float) -> Optional[float]:
        if previous <= 0:
            return None
        return round((current - previous) / previous, 4)

    # ── POS (gold) ────────────────────────────────────────────────

    def _merge_pos(self, metrics: dict, coverage: dict, pos: dict) -> None:
        for k in _POS_KEYS:
            v = pos.get(k)
            if v is not None:
                metrics[k] = float(v)
                coverage[k] = "ok"
            else:
                coverage[k] = "skipped:无 POS 数据"

    async def _fetch_pos_metrics(
        self,
        factory_id: str,
        start: date,
        end: date,
        *,
        smartbi_pool: Any = None,
        sub_sector: str = "",
    ) -> dict:
        """Query gold layer for discount_rate / delivery_dependency /
        channel_collection_rate. Returns {} if no rows.

        discount_rate → 0-100 scale (benchmark range [5,20]).
        delivery_dependency / channel_collection_rate → 0-1 scale.
        """
        if smartbi_pool is None:
            return {}

        out: dict = {}

        # delivery_dependency from agg_daily_order_type_meal (clean 堂食/外卖).
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            ot_rows = await conn.fetch(
                """
                SELECT order_type,
                       COALESCE(SUM(actual_receive), 0)::numeric(18,2) AS amt
                  FROM agg_daily_order_type_meal
                 WHERE factory_id = $1 AND order_type IS NOT NULL
                   AND date BETWEEN $2 AND $3
                 GROUP BY order_type
                """,
                factory_id, start, end,
            )
        total_ot = sum(float(r["amt"]) for r in ot_rows)
        delivery_amt = sum(
            float(r["amt"]) for r in ot_rows
            if any(d in (r["order_type"] or "") for d in _DELIVERY_ORDER_TYPES)
        )
        if total_ot > 0:
            out["delivery_dependency"] = round(delivery_amt / total_ot, 4)

        # discount_rate + channel_collection_rate from fact_pos_transaction.
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            txn = await conn.fetchrow(
                """
                SELECT COALESCE(SUM(gross_amount), 0)::numeric(18,2)    AS gross,
                       COALESCE(SUM(discount_amount), 0)::numeric(18,2) AS discount,
                       COALESCE(SUM(net_amount), 0)::numeric(18,2)      AS net
                  FROM fact_pos_transaction
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                """,
                factory_id, start, end,
            )
            # per-channel delivery revenue for commission estimate
            chan_rows = await conn.fetch(
                """
                SELECT order_type, channel_origin,
                       COALESCE(SUM(net_amount), 0)::numeric(18,2) AS net
                  FROM fact_pos_transaction
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                 GROUP BY order_type, channel_origin
                """,
                factory_id, start, end,
            )

        gross = float(txn["gross"]) if txn else 0.0
        discount = float(txn["discount"]) if txn else 0.0
        net = float(txn["net"]) if txn else 0.0
        if gross > 0:
            out["discount_rate"] = round(discount / gross * 100, 4)  # 0-100 scale

        # channel_collection_rate (D2 estimate): (revenue - est_commission)/revenue.
        if net > 0:
            est_commission, coverage_note = self._estimate_commission_with_note(
                chan_rows, sub_sector
            )
            out["channel_collection_rate"] = round((net - est_commission) / net, 4)
            out["_channel_estimated"] = True
            base_note = "基于平台佣金估算 (commission_rates.yaml)"
            # Surface unmapped-delivery coverage gap so a falsely-healthy
            # collection rate is never presented as fact.
            out["_channel_note"] = (
                f"{base_note}; {coverage_note}" if coverage_note else base_note
            )

        return out

    @staticmethod
    def _canonicalize_channel(raw: Optional[str]) -> str:
        """Map a raw channel_origin/order_type short value to the
        commission_rates.yaml key space (e.g. '美团' → '美团外卖').

        Unknown values pass through unchanged so a verbatim yaml-key match
        still works (and so unmapped channels can be detected downstream).
        """
        if raw is None:
            return ""
        key = str(raw).strip()
        if not key:
            return ""
        return _CHANNEL_ALIAS.get(key, key)

    @staticmethod
    def _is_delivery(order_type: Optional[str], channel: str) -> bool:
        """True if this row is a delivery (外卖) channel — used to decide
        whether an unmapped channel is a real coverage gap (delivery with no
        configured rate) vs. an expected 0% channel (堂食/自营)."""
        ot = str(order_type or "")
        if any(d in ot for d in _DELIVERY_ORDER_TYPES):
            return True
        # canonical delivery yaml keys contain 外卖/团购/小程序
        return any(tok in channel for tok in ("外卖", "团购", "小程序"))

    def _estimate_commission(self, chan_rows: list, sub_sector: str) -> float:
        """Estimate platform commission from per-channel net revenue × rate.

        Thin wrapper over :meth:`_estimate_commission_with_note` that drops the
        coverage note (kept for callers that only need the amount).
        """
        commission, _note = self._estimate_commission_with_note(chan_rows, sub_sector)
        return commission

    def _estimate_commission_with_note(
        self, chan_rows: list, sub_sector: str
    ) -> tuple[float, Optional[str]]:
        """Estimate platform commission + flag unmapped delivery channels.

        Rate from commission_rates.yaml: per_sub_sector → default_rates.
        channel_origin short values are canonicalized to the yaml key space
        first (美团 → 美团外卖) — otherwise delivery revenue is silently
        estimated at 0% commission → channel_collection_rate ≈ 1.0 (false
        healthy). D2 decision: no hard-coded 21%.

        Returns (total_commission, coverage_note). The note is non-None when a
        DELIVERY channel had revenue but no configured rate — surfaced so the
        UI does not present a falsely-healthy collection rate as fact.
        """
        rates = self._load_commission_rates()
        default_rates = rates.get("default_rates", {})
        per_sub = (rates.get("per_sub_sector", {}) or {}).get(sub_sector, {}) if sub_sector else {}

        total_commission = 0.0
        unmapped_delivery: list[str] = []
        for r in chan_rows:
            raw = r["channel_origin"] or r["order_type"]
            channel = self._canonicalize_channel(raw)
            net = float(r["net"]) if r["net"] is not None else 0.0
            if net <= 0 or not channel:
                continue
            rate = per_sub.get(channel)
            if rate is None:
                rate = default_rates.get(channel)
            if rate is None:
                # No configured rate. If this is a delivery channel, a 0%
                # fallback would fabricate a healthy collection rate — flag it.
                if self._is_delivery(r["order_type"], channel):
                    label = str(raw).strip()
                    if label and label not in unmapped_delivery:
                        unmapped_delivery.append(label)
                continue  # do NOT add 0 commission silently
            total_commission += net * float(rate)

        note = None
        if unmapped_delivery:
            note = (
                "渠道收款率: 部分外卖渠道费率未配置 ("
                + "、".join(unmapped_delivery)
                + "), 估算未含其抽佣, 实际到手率可能偏低"
            )
        return total_commission, note

    def _load_commission_rates(self) -> dict:
        if self._commission_yaml is not None:
            return self._commission_yaml
        import yaml
        path = self.kb_root / "pos" / "commission_rates.yaml"
        if not path.exists():
            logger.warning("commission_rates.yaml 不存在: %s", path)
            self._commission_yaml = {}
            return self._commission_yaml
        try:
            with open(path, encoding="utf-8") as f:
                self._commission_yaml = yaml.safe_load(f) or {}
        except Exception as e:  # noqa: BLE001
            logger.error("加载 commission_rates.yaml 失败: %s", e)
            self._commission_yaml = {}
        return self._commission_yaml

    # ── reviews ───────────────────────────────────────────────────

    async def _fetch_review_decline(
        self,
        factory_id: str,
        start: date,
        end: date,
        *,
        smartbi_pool: Any = None,
    ) -> Optional[float]:
        """5-star pct of `period` minus 5-star pct of previous month.

        Returns 0-1 scale delta (negative = decline) or None if either month
        has no deduped review rows.
        """
        if smartbi_pool is None:
            return None

        prev_start, prev_end = _prev_month_range(start)

        async def _five_star_pct(s: date, e: date) -> Optional[float]:
            async with smartbi_pool.acquire() as conn:
                await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                row = await conn.fetchrow(
                    """
                    WITH r AS (
                        SELECT DISTINCT ON (row_data->>'评价ID') row_data
                          FROM smart_bi_dynamic_data
                         WHERE factory_id = $1
                           AND row_data ? '星级分'
                           AND row_data->>'评价ID' IS NOT NULL
                           AND NULLIF(row_data->>'time_period', '') ~ '^\\d{4}-\\d{2}-\\d{2}'
                           AND (row_data->>'time_period')::date BETWEEN $2 AND $3
                         ORDER BY row_data->>'评价ID'
                    )
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE (row_data->>'星级分')::numeric >= 5) AS five_star
                      FROM r
                    """,
                    factory_id, s, e,
                )
            if not row or not row["total"]:
                return None
            return float(row["five_star"]) / float(row["total"])

        cur = await _five_star_pct(start, end)
        prev = await _five_star_pct(prev_start, prev_end)
        if cur is None or prev is None:
            return None
        return round(cur - prev, 4)
