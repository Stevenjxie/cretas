"""餐饮 outlier 检测服务 (Phase B-1).

复用 Phase A pattern:
- get_pg_pool() 单例 (smartbi/config.py)
- RLS GUC + conn.transaction() 强制 (W0.4 finding 3)

Reviewer R5: KPI_KINDS 配置驱动, 后续加信号是 append list.
"""
from __future__ import annotations
import logging
import asyncio
from dataclasses import dataclass
from typing import List, Literal, Optional, Tuple
from datetime import date

from smartbi.utils.outlier_stats import (
    iqr_fence, find_outliers_iqr, IQRFence,
)

logger = logging.getLogger(__name__)

# Reviewer R5: 配置驱动, 不 hard-code 4 信号
DEFAULT_KPI_KINDS = [
    "requisition_cost_total",
    "wastage_cost_total",
    "stocktaking_shortage_total",
    "stocktaking_surplus_total",
]

KPI_LABELS = {
    "requisition_cost_total": "领料成本",
    "wastage_cost_total": "损耗成本",
    "stocktaking_shortage_total": "盘亏",
    "stocktaking_surplus_total": "盘盈",
}

LOCAL_N_THRESHOLD = 10        # N>=10 用本地, 否则 fallback global
DEFAULT_WINDOW_DAYS = 30
IQR_MULTIPLIER = 1.5


@dataclass
class DetectedOutlier:
    anomaly_date: date
    kpi_kind: str
    value: float
    q1: float
    q3: float
    iqr: float
    lower_fence: float
    upper_fence: float
    deviation_x: float
    severity: Literal['high', 'medium']
    direction: Literal['above', 'below']
    baseline_source: Literal['self', 'global']
    baseline_n: str    # '<10' | '10-49' | ...


class OutlierService:
    """检测 outlier + 处理 fallback + 排除 dismissed."""

    async def detect_totals(
        self,
        factory_id: str,
        window_days: int = DEFAULT_WINDOW_DAYS,
        kpi_kinds: Optional[List[str]] = None,
    ) -> Tuple[List[DetectedOutlier], List[str]]:
        """主入口. 返 (outliers, insufficient_kpis).

        insufficient_kpis = 全网都 N<10 的 kpi_kind list, 前端显示 "样本不足".
        """
        kpi_kinds = kpi_kinds or DEFAULT_KPI_KINDS
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("SmartBI pool unavailable")

        # 并行 4 个 kpi
        tasks = [
            self._detect_one_kpi(pool, factory_id, kpi, window_days)
            for kpi in kpi_kinds
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        all_outliers = []
        insufficient = []
        for kpi, res in zip(kpi_kinds, results):
            if isinstance(res, Exception):
                logger.warning(f"[outlier] kpi={kpi} failed: {res}")
                continue
            outliers, was_insufficient = res
            if was_insufficient:
                insufficient.append(kpi)
            all_outliers.extend(outliers)

        # 按日期降序
        all_outliers.sort(key=lambda o: o.anomaly_date, reverse=True)
        return all_outliers, insufficient

    async def _detect_one_kpi(
        self, pool, factory_id: str, kpi_kind: str, window_days: int
    ) -> Tuple[List[DetectedOutlier], bool]:
        """检测单个 kpi. 返 (outliers, was_insufficient_locally_AND_globally)."""
        # Step 1: query 本工厂 30 天
        local_data = await self._query_local(pool, factory_id, kpi_kind, window_days)

        if len(local_data) >= LOCAL_N_THRESHOLD:
            # Step 2: 用本地 IQR
            return self._compute_outliers(
                local_data, kpi_kind, baseline_source='self',
                baseline_n=self._bucket_n(len(local_data)),
            ), False

        # Step 3: fallback global
        global_stats = await self._query_global_baseline(pool, kpi_kind, window_days)
        if global_stats is None or global_stats['n_bucket'] == '<10':
            # 全网也 N<10, 跳过
            return [], True

        # Step 4: 用全网 baseline 算 outlier (但点是本工厂的)
        return self._compute_outliers_with_baseline(
            local_data, kpi_kind,
            q1=global_stats['q1'], q3=global_stats['q3'],
            baseline_source='global', baseline_n=global_stats['n_bucket'],
        ), False

    async def _query_local(
        self, pool, factory_id: str, kpi_kind: str, window_days: int
    ) -> List[Tuple[date, float]]:
        """查询本工厂 N 天数据. 返 [(date, value), ...].

        ⚠️ W0.4 finding 3: RLS FORCE, 必须 GUC + transaction.
        """
        # Defense: kpi_kind comes from whitelist DEFAULT_KPI_KINDS (safe f-string),
        # but assert as final guard against accidental misuse.
        assert kpi_kind in DEFAULT_KPI_KINDS, f"kpi_kind {kpi_kind!r} not in whitelist"
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                rows = await conn.fetch(
                    f"""
                    SELECT date, {kpi_kind} AS value
                    FROM agg_restaurant_daily_totals
                    WHERE factory_id = $1
                      AND date >= CURRENT_DATE - ($2 || ' days')::interval
                      AND {kpi_kind} IS NOT NULL
                    ORDER BY date
                    """,
                    factory_id, str(window_days),
                )
        return [(r['date'], float(r['value'])) for r in rows]

    async def _query_global_baseline(
        self, pool, kpi_kind: str, window_days: int
    ) -> Optional[dict]:
        """调用 SECURITY DEFINER function. 不需 GUC (function bypass RLS)."""
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT q1, q3, median, n_bucket FROM get_global_kpi_stats($1, $2)",
                kpi_kind, window_days,
            )
        if row is None or row['q1'] is None:
            return None
        return dict(row)

    def _compute_outliers(
        self, local_data, kpi_kind, baseline_source, baseline_n,
    ) -> List[DetectedOutlier]:
        """用本地数据算 IQR + 检测."""
        values = [v for _, v in local_data]
        fence = iqr_fence(values, multiplier=IQR_MULTIPLIER)
        if fence is None:
            return []
        return self._build_outliers(local_data, fence, kpi_kind, baseline_source, baseline_n)

    def _compute_outliers_with_baseline(
        self, local_data, kpi_kind, q1, q3, baseline_source, baseline_n,
    ) -> List[DetectedOutlier]:
        """用 global q1/q3 算 fence + 检测本工厂数据."""
        if not local_data:
            return []
        iqr = float(q3 - q1)
        fence = IQRFence(
            q1=float(q1), q3=float(q3), iqr=iqr,
            lower=float(q1) - IQR_MULTIPLIER * iqr,
            upper=float(q3) + IQR_MULTIPLIER * iqr,
            multiplier=IQR_MULTIPLIER,
        )
        return self._build_outliers(local_data, fence, kpi_kind, baseline_source, baseline_n)

    def _build_outliers(
        self, local_data, fence, kpi_kind, baseline_source, baseline_n,
    ) -> List[DetectedOutlier]:
        """Use find_outliers_iqr from utils to detect outliers, avoiding DRY violation."""
        values = [v for _, v in local_data]
        raw_outliers = find_outliers_iqr(values, fence)
        return [
            self._make_outlier(
                anomaly_date=local_data[o.index][0],
                kpi_kind=kpi_kind,
                value=o.value,
                fence=fence,
                dev=o.deviation_x,
                direction=o.direction,
                source=baseline_source,
                n=baseline_n,
            )
            for o in raw_outliers
        ]

    def _make_outlier(
        self,
        anomaly_date: date,
        kpi_kind: str,
        value: float,
        fence: IQRFence,
        dev: float,
        direction: str,
        source: str,
        n: str,
    ) -> DetectedOutlier:
        """Create a DetectedOutlier from raw outlier data."""
        return DetectedOutlier(
            anomaly_date=anomaly_date, kpi_kind=kpi_kind, value=value,
            q1=fence.q1, q3=fence.q3, iqr=fence.iqr,
            lower_fence=fence.lower, upper_fence=fence.upper,
            deviation_x=dev,
            severity='high' if dev > 2.0 else 'medium',
            direction=direction,
            baseline_source=source, baseline_n=n,
        )

    @staticmethod
    def _bucket_n(n: int) -> str:
        if n < 10:
            return '<10'  # noqa: E701
        if n < 50:
            return '10-49'  # noqa: E701
        if n < 100:
            return '50-99'  # noqa: E701
        if n < 500:
            return '100-499'  # noqa: E701
        return '500+'

    async def detect_per_dim(self, *args, **kwargs):
        """EAV per-dim 下钻 — Phase B-N backlog.

        接口预留, 让 B-3 dashboard 加点击下钻时无需改 service signature.
        """
        raise NotImplementedError(
            "Per-dim outlier detection (EAV) is a Phase B-N item, "
            "see docs/数据织网/implementation/restaurant-phase-b1-outlier-filter-2026-04-28-design.md backlog"
        )
