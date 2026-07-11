"""G4 — 餐饮 AI 经营体检报告 endpoint.

GET /api/smartbi/restaurant/{factory_id}/health-check-report?month=&sub_sector=&table_count=

Pipeline:
  validate tenant (request.state.factory_id == path factory_id)
    → resolve sub_sector (FACTORY_SUBSECTOR_MAP, query override, else "")
    → check 5-min cache (factory_id + period + upload_id)
    → HealthCheckMetricsBuilder.build() (finance + POS + 点评)
    → DiagnosticsEngine(domain="restaurant", sub_sector=...).run(metrics)
    → build report {reportMeta, summary, diagnoses}
    → cache + return {success, data, message}

Honest failure (禁降级假数据):
  - tenant mismatch → 403
  - pool unavailable → 503
  - no data → 200 with empty diagnoses + coverageNote (NOT fabricated)
"""
from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from smartbi.config import get_pg_pool
from smartbi.services.restaurant.health_check_metrics import (
    HealthCheckMetricsBuilder,
    _resolve_month_range,
)
from smartbi.shared.diagnostics_engine import DiagnosticsEngine

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantHealthCheck"])

# D3: factory_id → sub_sector benchmark mapping. qhj 固定鱼类餐饮; 未配置工厂
# 回退空串(走 _common.yaml 通用 benchmark)。
FACTORY_SUBSECTOR_MAP: Dict[str, str] = {
    "RES_3101_009": "鱼类餐饮",   # 青花椒酸菜鱼 (qhj)
}

# Metric key → 中文名 (报告卡片展示用; 与 diagnostics_registry name_zh 对齐)。
_METRIC_NAME_ZH = {
    "food_cost_ratio": "食材成本率",
    "labor_cost_ratio": "人力成本率",
    "discount_rate": "折扣率",
    "cost_rigidity": "成本弹性指数",
    "channel_collection_rate": "渠道收款率",
    "delivery_dependency": "外卖依赖度",
    "review_score_decline": "评分趋势下滑",
    "ingredient_waste_rate": "食材损耗率",
    "avg_ticket_vs_target": "客单价达标率",
}

# Coverage skip → 友好中文短语 (拼 coverageNote)。
_SKIP_LABEL = {
    "cost_rigidity": "成本弹性",
    "food_cost_ratio": "食材成本率",
    "labor_cost_ratio": "人力成本率",
    "discount_rate": "折扣率",
    "channel_collection_rate": "渠道收款率",
    "delivery_dependency": "外卖依赖度",
    "review_score_decline": "评分趋势",
    "ingredient_waste_rate": "食材损耗率",
    "avg_ticket_vs_target": "客单价达标率",
    "channel_gross_margin": "渠道毛利率",
    "gross_margin_per_dish": "菜品毛利率",
    "recipe_coverage_rate": "配方覆盖率",
    "table_turnover": "翻台率",
    "stored_value_dependency": "充卡赠送依赖度",
}

_CACHE_TTL_SECONDS = 300
# In-process cache: key → (expiry_epoch, response_data_dict).
_REPORT_CACHE: Dict[str, tuple[float, dict]] = {}


def _err(status: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"success": False, "data": None, "message": message},
    )


def _resolve_sub_sector(factory_id: str, query_sub_sector: str) -> str:
    """Query param wins; else FACTORY_SUBSECTOR_MAP; else "" (通用)."""
    if query_sub_sector and query_sub_sector.strip():
        return query_sub_sector.strip()
    return FACTORY_SUBSECTOR_MAP.get(factory_id, "")


def _build_coverage_note(coverage: Dict[str, str]) -> str:
    """拼接被跳过指标的中文说明 (Rule 5 dead-end 文案在前端补按钮)。"""
    skipped = []
    for key, status in coverage.items():
        if status and status.startswith("skipped"):
            label = _SKIP_LABEL.get(key, key)
            reason = status.split(":", 1)[1] if ":" in status else "数据不足"
            skipped.append(f"{label}因{reason}跳过")
    return "; ".join(skipped) if skipped else ""


@router.get("/restaurant/{factory_id}/health-check-report", summary="餐饮经营体检报告")
async def get_health_check_report(
    factory_id: str,
    request: Request,
    month: Optional[str] = Query(None, description="YYYY-MM / 上月 / 本月, 默认上月"),
    sub_sector: str = Query("", description="行业子品类, 留空走映射或通用 benchmark"),
    table_count: Optional[int] = Query(None, description="桌台数 (Phase 2 翻台率, 当前未使用)"),
) -> Any:
    # ── 1. tenant guard ──────────────────────────────────────────
    caller = getattr(request.state, "factory_id", None)
    if not caller:
        return _err(403, "缺少租户上下文, 无法验证访问权限, 请重新登录")
    if caller != "INTERNAL" and caller != factory_id:
        return _err(403, f"无权访问 {factory_id} 的体检报告")

    resolved_sub_sector = _resolve_sub_sector(factory_id, sub_sector)

    # ── 2. cache check (resolve period cheaply; TTL handles upload freshness) ──
    start_d, _end_d = _resolve_month_range(month)
    resolved_period = f"{start_d.year:04d}-{start_d.month:02d}"
    cache_key = f"health_check:{factory_id}:{resolved_period}:{resolved_sub_sector}"
    now = time.time()
    cached = _REPORT_CACHE.get(cache_key)
    if cached and cached[0] > now:
        data = dict(cached[1])
        data["reportMeta"] = dict(data["reportMeta"])
        data["reportMeta"]["cacheHit"] = True
        return {"success": True, "data": data, "message": "体检报告生成成功 (缓存)"}

    # ── 3. pool ──────────────────────────────────────────────────
    pool = await get_pg_pool()
    if pool is None:
        return _err(503, "数据库连接不可用, 请稍后重试")

    # ── 4. build metrics ─────────────────────────────────────────
    builder = HealthCheckMetricsBuilder()
    started = time.time()
    try:
        bundle = await builder.build(
            factory_id=factory_id,
            period=month,
            sub_sector=resolved_sub_sector,
            table_count=table_count,
            smartbi_pool=pool,
            finance_metrics=None,
        )
    except Exception as e:  # noqa: BLE001
        logger.exception("[health-check] build failed for %s", factory_id)
        return _err(500, f"体检报告生成失败: {e}")

    # ── 5. diagnostics ───────────────────────────────────────────
    engine = DiagnosticsEngine(domain="restaurant", sub_sector=resolved_sub_sector)
    # 🔒 F2: DiagnosticsEngine only auto-loads benchmarks when sub_sector is
    # truthy. FACTORY_SUBSECTOR_MAP maps just ONE tenant (RES_3101_009), so
    # every other tenant (incl. DEMO_REST) resolves to "" → benchmark-sourced
    # metrics (food_cost_ratio / labor_cost_ratio / discount_rate /
    # ingredient_waste_rate) would silently NEVER fire, and summary would still
    # claim "均无异常" — a fabricated clean bill of health on a 反回扣 board.
    # Force the 通用 (_common.yaml) benchmark load so generic tenants DO get
    # alerted (same grounded 通用 thresholds already accepted for avg_ticket).
    if not resolved_sub_sector:
        engine._load_benchmarks()
    diagnoses = engine.run(bundle.metrics)

    diag_dicts = []
    for d in diagnoses:
        dd = d.to_dict()
        # 透出 builder 的额外标注 (D2 佣金估算说明)。
        note = bundle.notes.get(d.metric_key)
        if note:
            base = dd.get("descriptionZh") or ""
            dd["descriptionZh"] = f"{base} ({note})" if base else note
        if d.metric_key == "channel_collection_rate" and bundle.channel_collection_estimated:
            dd["estimated"] = True
        # 🔒 F3: avg_ticket judged against the generic 通用 median (no
        # tenant-set target) → cap severity at warning (never critical) and mark
        # estimated. A CRITICAL "客单价严重偏低" against a target nobody set
        # (e.g. 快餐/奶茶 real ¥20-30 vs national ¥75 median) is a misleading
        # accusation, not a grounded alert.
        if d.metric_key == "avg_ticket_vs_target" and bundle.avg_ticket_target_estimated:
            dd["estimated"] = True
            if dd.get("severity") == "critical":
                dd["severity"] = "warning"
                if dd.get("status") == "严重偏低":
                    dd["status"] = "偏低"
        # 保证 metricNameZh 不为空
        if not dd.get("metricNameZh"):
            dd["metricNameZh"] = _METRIC_NAME_ZH.get(d.metric_key, d.metric_key)
        diag_dicts.append(dd)

    # ── 5b. 反回扣: 供应商进价异常 → pushable standing diagnosis ─────────
    # DiagnosticsEngine 是 "1 指标 key → 1 阈值" 的标量引擎, 装不下 "N 条
    # (食材×供应商) 异常事件"。供应商进价异常是 反回扣 的核心采购端信号 (零理论
    # 依赖 — 只看 agg_supplier_price 真实成交价环比), 且已被合成路径 vetted
    # (detect_price_anomalies)。这里把它作为独立诊断 append 进 diag_dicts, 让它
    # 走 #1354 桥接 → standing alert → 推送 (老板/管理员), 而不只停留在 AI 聊天。
    #
    # 只报 direction==UP (进价上涨才是虚高/回扣风险; 降价不是反回扣信号)。
    # 措辞 威慑非处罚 ("请核对是否合理", 非 "存在回扣" 的指控)。riskLevel HIGH
    # (连续 3+ 次同向) → critical(→SMS), MEDIUM → warning(仅站内)。metricKey 按
    # (食材, 供应商) 唯一, 否则第 2 条会 dedup 覆盖第 1 条 (businessEntityId)。
    try:
        from smartbi.gold.price_anomaly import detect_price_anomalies
        anomalies = await detect_price_anomalies(pool, factory_id)
        for a in anomalies:
            if str(a.get("direction")) != "UP":
                continue  # 反回扣: 只关注进价上涨
            delta = a.get("deltaPct")
            if delta is None:
                continue
            ingredient = str(a.get("ingredientName") or a.get("normalizedName") or "食材")
            supplier = str(a.get("supplierName") or "供应商")
            supplier_id = str(a.get("supplierId") or "")
            norm = str(a.get("normalizedName") or ingredient)
            new_price = a.get("newPrice")
            trailing = a.get("trailingAvg")
            is_high = str(a.get("riskLevel")) == "HIGH"
            desc = (
                f"{ingredient}（{supplier}）最新进价 {new_price} 元，"
                f"较近期均价上涨 {abs(float(delta)):.1f}%"
                + (f"（近期均价 {trailing} 元）" if trailing is not None else "")
                + "。请核对该供应商报价是否合理、是否偏离合同价，避免虚高进价/回扣。"
            )
            diag_dicts.append({
                "metricKey": f"supplier_price_anomaly:{norm}:{supplier_id}",
                "metricNameZh": "供应商进价异常",
                "severity": "critical" if is_high else "warning",
                "status": "进价上涨",
                "descriptionZh": desc,
                "estimated": False,
                "rxActions": [{
                    "actionZh": "核对该供应商近期报价与合同价，必要时多方询价比价",
                }],
            })
    except Exception:  # noqa: BLE001 — best-effort, 不因供应商异常查询失败拖垮体检
        logger.exception("[health-check] supplier price anomaly append failed for %s", factory_id)

    # counts from the post-adjustment dicts so the F3 severity cap is reflected.
    critical_count = sum(1 for dd in diag_dicts if dd.get("severity") == "critical")
    warning_count = sum(1 for dd in diag_dicts if dd.get("severity") == "warning")
    info_count = sum(1 for dd in diag_dicts if dd.get("severity") == "info")

    checked = sum(1 for v in bundle.coverage.values() if v == "ok")
    coverage_note = _build_coverage_note(bundle.coverage)
    if not diagnoses and not coverage_note:
        coverage_note = f"已检查 {checked} 项指标, 均无异常"
    elif not diagnoses and coverage_note:
        coverage_note = f"已检查 {checked} 项指标无异常; {coverage_note}"

    data = {
        "reportMeta": {
            "factoryId": factory_id,
            "period": bundle.period,
            "snapshotAt": datetime.now().isoformat(),
            "subSector": resolved_sub_sector or "通用行业",
            "uploadId": bundle.upload_id,
            "cacheHit": False,
        },
        "summary": {
            "criticalCount": critical_count,
            "warningCount": warning_count,
            "infoCount": info_count,
            "checkedCount": checked,
            "coverageNote": coverage_note,
            "coverage": bundle.coverage,
        },
        "diagnoses": diag_dicts,
    }

    # ── 6. cache store ───────────────────────────────────────────
    _REPORT_CACHE[cache_key] = (now + _CACHE_TTL_SECONDS, data)
    # opportunistic eviction of expired keys (keeps dict small)
    if len(_REPORT_CACHE) > 256:
        for k in [k for k, (exp, _) in _REPORT_CACHE.items() if exp <= now]:
            _REPORT_CACHE.pop(k, None)

    logger.info(
        "[health-check] factory=%s period=%s sub_sector=%s diagnoses=%d (C%d/W%d) in %.0fms",
        factory_id, bundle.period, resolved_sub_sector or "通用",
        len(diagnoses), critical_count, warning_count, (time.time() - started) * 1000,
    )
    return {"success": True, "data": data, "message": "体检报告生成成功"}
