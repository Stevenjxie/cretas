from __future__ import annotations

"""#56 价值可视化回馈回路 — value_summary section handler。

供 Java RestaurantValueSummaryTool 经 callRestaurantSection("value_summary")
调用。读取最新价值快照 (restaurant_value_snapshots), 返回月度+年化两口径。

数据不足 (无快照) → SKIPPED, warnings 含"未检测到本月价值快照, 请先上传月度
经营数据" (诚实空态, 不编假金额)。AI Tool 收到 SKIPPED → buildError 诚实提示。

注意: section 是同步 compute() 但底层读快照是 async — 用 asyncio.run 在
section 线程里跑 (sections 路由是同步 def, 与 restaurant_sections.py 一致)。
"""

import asyncio
import time
from typing import Any

from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class ValueSummaryHandler(AbstractSectionHandler):
    """读取价值快照并返回。不计算 (计算在 snapshot service / hook / cron 完成)。"""

    section_name = "value_summary"

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()
        factory_id = request.factory_id
        if not factory_id:
            return self.skipped(request, "缺少 factory_id", started)

        # period 标签: SectionRequest.period 可携带 'YYYY-MM' (Java month 透传);
        # "current" / 空 → None (取最新快照)。
        period = request.period
        period_month = period if (period and period != "current" and "-" in period) else None
        store_id = request.store_id

        try:
            summary = asyncio.run(self._load(factory_id, period_month, store_id))
        except Exception as e:  # noqa: BLE001
            return self.skipped(request, f"读取价值快照失败: {e}", started)

        if summary is None:
            return self.skipped(
                request,
                "未检测到价值快照, 请先上传月度经营数据 (诊断引擎需财务/POS 数据才能算出可节省金额)",
                started,
            )

        return self.ok(request, data=summary, started=started)

    async def _load(self, factory_id: str, period_month, store_id):
        from smartbi.config import get_pg_pool
        from smartbi.services.restaurant.value_snapshot_service import get_value_summary

        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError("smartbi_db pool unavailable")
        # get_value_summary 内部在同一连接上 SET app.factory_id (RLS 隔离),
        # section 内部调用栈无 middleware GUC, 故必须 set_tenant_guc=True。
        return await get_value_summary(
            pool, factory_id, period_month, store_id, set_tenant_guc=True
        )
