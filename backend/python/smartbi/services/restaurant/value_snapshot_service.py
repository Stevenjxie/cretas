from __future__ import annotations

"""#56 价值可视化回馈回路 — 价值快照 service (幂等 upsert + 读取)。

compute_and_upsert_snapshot: 把 ValueSignal 列表聚合成一行快照, 幂等 upsert 进
restaurant_value_snapshots (ON CONFLICT DO UPDATE — 重复触发不增行, 满足 D1 双触发)。
get_value_summary: 读取最新快照, 返回 {month, annual} 两口径 (D3); 未命中返 None。

诚实规则 (per spec + fool-proof-design R1):
  - total_est_month / total_est_annual: 无任何有金额信号 → None (禁降级填 0)。
  - 年化 = 月度 estimate 类 × 12; measured 类 (本月实测损溢) 不年化 (避免夸大)。
  - compute 异常 → try/except 只记日志返 success:False, 不抛 (fire-and-forget 安全)。

RLS: caller (hook / cron / API) 已 SET app.factory_id; 本 service 不重复设
(与既有 restaurant_ops_gold 端点一致, 由调用栈负责 GUC)。upsert SQL 自带
factory_id 列, RLS WITH CHECK 用 GUC 校验。
"""

import json
import logging
from typing import Any, Optional

from smartbi.services.restaurant.value_signal_extractor import ValueSignal

logger = logging.getLogger(__name__)


# 比率类预估信号 (可年化); 损溢是本月实测 (不年化)。
_ANNUALIZABLE_KINDS = frozenset({"estimate"})


# ── 聚合 helpers ──────────────────────────────────────────


def _aggregate_total(signals: list[ValueSignal]) -> Optional[float]:
    """月度合计 = sum(有金额的信号)。全无金额 → None (禁降级填 0)。"""
    amounts = [s.amount for s in signals if s.amount is not None]
    if not amounts:
        return None
    return round(sum(amounts), 2)


def _aggregate_annual_total(signals: list[ValueSignal]) -> Optional[float]:
    """年化合计 = estimate 类 × 12 + measured 类原值。全无金额 → None。"""
    total = 0.0
    has_any = False
    for s in signals:
        if s.amount is None:
            continue
        has_any = True
        if s.kind in _ANNUALIZABLE_KINDS:
            total += s.amount * 12.0
        else:
            total += s.amount
    if not has_any:
        return None
    return round(total, 2)


def _signal_amount(signals: list[ValueSignal], signal_id: str) -> Optional[float]:
    """取某信号的金额 (取第一个有金额的同名信号)。无 → None。"""
    for s in signals:
        if s.signal == signal_id and s.amount is not None:
            return s.amount
    return None


def _labor_annual(signals: list[ValueSignal]) -> Optional[float]:
    """人工刚性年化 = 月度人工节省 × 12 (estimate)。无 → None。"""
    monthly = _signal_amount(signals, "labor_rigidity")
    if monthly is None:
        return None
    return round(monthly * 12.0, 2)


def _confidence_note(signals: list[ValueSignal]) -> Optional[str]:
    """生成口径说明文案。无信号 → None。"""
    if not signals:
        return None
    has_measured = any(s.kind == "measured" and s.amount is not None for s in signals)
    has_estimate = any(s.kind == "estimate" and s.amount is not None for s in signals)
    parts: list[str] = []
    if has_estimate:
        parts.append("预估金额为达标后可节省空间, 年化口径")
    if has_measured:
        parts.append("损溢为本月实测")
    return "; ".join(parts) if parts else None


# ── 幂等 upsert ───────────────────────────────────────────

_UPSERT_SQL = """
INSERT INTO restaurant_value_snapshots (
    factory_id, period_month, store_id,
    labor_rigidity_annual_est, shrinkage_variance_amount,
    food_cost_savings_est, discount_savings_est,
    total_est_month, total_est_annual,
    diagnosis_count, critical_count, rx_action_count,
    signal_sources, confidence_note, computed_at, updated_at
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13::jsonb, $14, NOW(), NOW()
)
ON CONFLICT (factory_id, period_month, COALESCE(store_id, '')) DO UPDATE SET
    labor_rigidity_annual_est = EXCLUDED.labor_rigidity_annual_est,
    shrinkage_variance_amount = EXCLUDED.shrinkage_variance_amount,
    food_cost_savings_est     = EXCLUDED.food_cost_savings_est,
    discount_savings_est      = EXCLUDED.discount_savings_est,
    total_est_month           = EXCLUDED.total_est_month,
    total_est_annual          = EXCLUDED.total_est_annual,
    diagnosis_count           = EXCLUDED.diagnosis_count,
    critical_count            = EXCLUDED.critical_count,
    rx_action_count           = EXCLUDED.rx_action_count,
    signal_sources            = EXCLUDED.signal_sources,
    confidence_note           = EXCLUDED.confidence_note,
    computed_at               = NOW(),
    updated_at                = NOW()
"""


async def compute_and_upsert_snapshot(
    pool: Any,
    factory_id: str,
    period_month: str,
    store_id: Optional[str],
    signals: list[ValueSignal],
    diagnosis_count: int,
    critical_count: int,
    rx_action_count: int,
) -> dict[str, Any]:
    """幂等 upsert 一行价值快照。

    Args:
        pool: asyncpg pool。
        factory_id / period_month ('YYYY-MM') / store_id (None = 全店汇总)。
        signals: ValueSignalExtractor.extract_from_diagnosis 的结果 (月度口径)。
        diagnosis_count / critical_count / rx_action_count: 元信息。

    Returns:
        {success, message, totalMonth, totalAnnual} — 永不抛 (fire-and-forget 安全)。
    """
    try:
        total_month = _aggregate_total(signals)
        total_annual = _aggregate_annual_total(signals)
        labor_annual = _labor_annual(signals)
        shrinkage = _signal_amount(signals, "shrinkage_variance")
        food = _signal_amount(signals, "food_cost_savings")
        discount = _signal_amount(signals, "discount_savings")
        note = _confidence_note(signals)
        signal_sources = json.dumps([s.to_dict() for s in signals], ensure_ascii=False)

        async with pool.acquire() as conn:
            await conn.execute(
                _UPSERT_SQL,
                factory_id, period_month, store_id,
                labor_annual, shrinkage, food, discount,
                total_month, total_annual,
                int(diagnosis_count), int(critical_count), int(rx_action_count),
                signal_sources, note,
            )
        logger.info(
            "[value-snapshot] upserted factory=%s period=%s store=%s "
            "month=%s annual=%s signals=%d",
            factory_id, period_month, store_id, total_month, total_annual, len(signals),
        )
        return {
            "success": True,
            "message": "快照已更新",
            "totalMonth": total_month,
            "totalAnnual": total_annual,
        }
    except Exception as e:  # noqa: BLE001 — fire-and-forget, never propagate
        logger.error(
            "[value-snapshot] upsert failed factory=%s period=%s: %s",
            factory_id, period_month, e, exc_info=True,
        )
        return {"success": False, "message": f"快照计算失败: {e}"}


# ── 读取 ──────────────────────────────────────────────────

_SELECT_SQL = """
SELECT factory_id, period_month, store_id,
       labor_rigidity_annual_est, shrinkage_variance_amount,
       food_cost_savings_est, discount_savings_est,
       total_est_month, total_est_annual,
       diagnosis_count, critical_count, rx_action_count,
       signal_sources, confidence_note, computed_at
  FROM restaurant_value_snapshots
 WHERE factory_id = $1
   AND ($2::text IS NULL OR period_month = $2)
   AND COALESCE(store_id, '') = COALESCE($3, '')
 ORDER BY period_month DESC, computed_at DESC
 LIMIT 1
"""


def _f(v: Any) -> Optional[float]:
    """Decimal/None → float/None (保留 None, 禁降级填 0)。"""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


async def get_value_summary(
    pool: Any,
    factory_id: str,
    period_month: Optional[str] = None,
    store_id: Optional[str] = None,
    set_tenant_guc: bool = True,
) -> Optional[dict[str, Any]]:
    """读取最新价值快照, 返回 {month, annual} 两口径 (D3)。

    Args:
        period_month: 'YYYY-MM' 或 None (取最新)。
        store_id: None = 全店汇总。
        set_tenant_guc: True → 在同一连接上 SET app.factory_id (RLS 隔离, 单 conn
            事务局部)。section/内部调用栈未设 GUC 时必须 True; API 层若已由
            middleware 设好可传 False。fake pool 测试无 set_config 时容错跳过。

    Returns:
        {periodMonth, storeId, month:{total, ...}, annual:{total, labor},
         diagnosisCount, criticalCount, rxActionCount, signalSources,
         confidenceNote, computedAt} 或 None (未命中, 正常空态)。
    """
    async with pool.acquire() as conn:
        if set_tenant_guc:
            try:
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
            except Exception:  # noqa: BLE001 — fake conn in tests / GUC already set
                pass
        row = await conn.fetchrow(_SELECT_SQL, factory_id, period_month, store_id)
    if row is None:
        return None

    signal_sources_raw = row["signal_sources"]
    if isinstance(signal_sources_raw, str):
        try:
            signal_sources = json.loads(signal_sources_raw)
        except (ValueError, TypeError):
            signal_sources = []
    else:
        signal_sources = signal_sources_raw or []

    computed_at = row["computed_at"]
    computed_at_iso = computed_at.isoformat() if computed_at is not None else None

    return {
        "periodMonth": row["period_month"],
        "storeId": row["store_id"],
        "month": {
            "total": _f(row["total_est_month"]),
            "shrinkageVariance": _f(row["shrinkage_variance_amount"]),
            "foodCostSavings": _f(row["food_cost_savings_est"]),
            "discountSavings": _f(row["discount_savings_est"]),
        },
        "annual": {
            "total": _f(row["total_est_annual"]),
            "laborRigidity": _f(row["labor_rigidity_annual_est"]),
        },
        "diagnosisCount": int(row["diagnosis_count"] or 0),
        "criticalCount": int(row["critical_count"] or 0),
        "rxActionCount": int(row["rx_action_count"] or 0),
        "signalSources": signal_sources,
        "confidenceNote": row["confidence_note"],
        "computedAt": computed_at_iso,
    }
