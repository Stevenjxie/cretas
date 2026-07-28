"""AI Gold Tool: TARGET_ACHIEVEMENT_QUERY — 查询餐饮目标达成率.

Registered in ai_intent_config via:
  tool_name = 'restaurant_target_achievement'
  intent_code = 'TARGET_ACHIEVEMENT_QUERY'

Answers natural language questions like:
  - "本周达成率多少"
  - "本月目标完成了吗"
  - "最近7天达成情况"

Intent binding SQL (apply once against cretas_db / cretas_prod_db):
  INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category,
    tool_name, keywords, is_active, sensitivity_level)
  VALUES (gen_random_uuid(), 'TARGET_ACHIEVEMENT_QUERY', '目标达成率查询', 'DATA_QUERY',
    'restaurant_target_achievement',
    '["达成率","目标完成","本周目标","本月目标","完成情况","预警"]',
    true, 'LOW');
"""
from __future__ import annotations

import logging
from datetime import date, timedelta

logger = logging.getLogger(__name__)

_LEVEL_MAP = {
    "week": "week", "周": "week", "本周": "week",
    "month": "month", "月": "month", "本月": "month",
    "day": "day", "日": "day", "今天": "day", "本日": "day",
    "year": "year", "年": "year", "本年": "year",
}


class RestaurantTargetAchievementTool:
    """Gold Tool for TARGET_ACHIEVEMENT_QUERY — 查当前/近期达成率.

    Implements the tool_name / description / execute contract expected by the
    Python AI tool dispatch layer (equivalent of Java AbstractBusinessTool).
    """

    tool_name: str = "restaurant_target_achievement"
    description: str = (
        "查询餐饮门店营业额目标达成率，包括本日/本周/本月达成率、"
        "7天预警时间线和目标完成情况分析。"
        "适用于: '本周达成率多少' / '目标完成了吗' / '最近达成情况'。"
    )

    async def execute(self, factory_id: str, params: dict) -> dict:
        """Execute achievement query. Returns message + structured data."""
        params = params or {}
        raw_level = str(params.get("level", "day")).lower()
        level = _LEVEL_MAP.get(raw_level, "day")
        kpi_kind = params.get("kpi_kind", "revenue")
        try:
            lookback_days = int(params.get("lookback_days", 7))
        except (TypeError, ValueError):
            lookback_days = 7

        end = date.today()
        start = end - timedelta(days=lookback_days - 1)

        try:
            from smartbi.config import get_pg_pool
            from smartbi.gold.queries import daily_achievement_summary, alert_preview

            pool = await get_pg_pool()
            if pool is None:
                return {
                    "success": False,
                    "message": "数据库连接不可用，请稍后重试",
                }

            achievement = await daily_achievement_summary(
                pool, factory_id, (start, end),
                kpi_kind=kpi_kind, level=level,
            )
            alert = await alert_preview(
                pool, factory_id, lookback_days, kpi_kind=kpi_kind,
            )

            points = achievement.get("points", [])
            if not points:
                return {
                    "success": True,
                    "message": (
                        f"{factory_id} 的 {level} 级别目标尚未设置，"
                        f"请先前往目标管理页配置目标值。"
                    ),
                    "data": achievement,
                }

            # Build human-readable summary (Rule 2: include context)
            summary_lines = []
            for pt in points[-3:]:  # show last 3 periods
                pk = pt["period_key"]
                if pt["data_missing"]:
                    summary_lines.append(f"{pk}: 数据缺失（POS 未上报）")
                elif pt["achievement_rate"] is None:
                    summary_lines.append(f"{pk}: 无目标配置")
                else:
                    rate_pct = f"{pt['achievement_rate'] * 100:.1f}%"
                    if kpi_kind == "revenue":
                        target_str = (
                            f"¥{pt['target']:,.0f}" if pt["target"] is not None else "—"
                        )
                        actual_str = (
                            f"¥{pt['actual']:,.0f}" if pt["actual"] is not None else "—"
                        )
                    else:
                        target_str = str(pt.get("target"))
                        actual_str = str(pt.get("actual"))
                    summary_lines.append(
                        f"{pk}: 目标 {target_str} · 实际 {actual_str} · 达成率 {rate_pct}"
                    )

            alert_summary = alert.get("summary", {})
            warn_count = alert_summary.get("WARN", 0) + alert_summary.get("CRITICAL", 0)
            alert_note = (
                f"（近 {lookback_days} 天有 {warn_count} 天未达标）" if warn_count > 0 else ""
            )

            message = f"目标达成情况{alert_note}：\n" + "\n".join(summary_lines)

            return {
                "success": True,
                "message": message,
                "data": {
                    "achievement": achievement,
                    "alert": alert,
                },
            }

        except Exception as exc:  # pragma: no cover - defensive
            logger.exception("RestaurantTargetAchievementTool.execute failed: %s", exc)
            return {
                "success": False,
                "message": f"查询目标达成率失败：{exc}",
            }


def _money(v) -> str:
    """Format a numeric amount as ¥ with thousands separators (None → —)."""
    if v is None:
        return "—"
    try:
        return f"¥{float(v):,.0f}"
    except (TypeError, ValueError):
        return str(v)


class RestaurantTargetForecastTool:
    """Gold Tool: 滚动营收预测 — '下个月预计能做多少' / '能达标吗'.

    Wraps smartbi.services.target_forecast.forecast_revenue (linear-trend on
    gold agg_daily trailing 90d + 80% CI). Returns a human-readable horizon
    summary plus structured forecast points.

    Intent binding SQL (apply once against cretas_db / cretas_prod_db):
      INSERT INTO ai_intent_config (id, intent_code, intent_name,
        intent_category, tool_name, keywords, is_active, sensitivity_level)
      VALUES (gen_random_uuid(), 'RESTAURANT_REVENUE_FORECAST', '营收预测',
        'DATA_QUERY', 'restaurant_revenue_forecast',
        '["预测","预计","下个月","未来","能做多少","趋势预测","能达标吗"]',
        true, 'LOW');
    """

    tool_name: str = "restaurant_revenue_forecast"
    description: str = (
        "预测餐饮门店未来营业额（基于历史趋势的滚动预测，含 80% 置信区间）。"
        "适用于: '下个月预计能做多少' / '未来30天营收预测' / '能达标吗'。"
    )

    async def execute(self, factory_id: str, params: dict) -> dict:
        params = params or {}
        try:
            horizon = int(params.get("horizon_days", 30))
        except (TypeError, ValueError):
            horizon = 30
        store_id = params.get("store_id")
        try:
            from smartbi.config import get_pg_pool
            from smartbi.services.target_forecast import forecast_revenue

            pool = await get_pg_pool()
            if pool is None:
                return {"success": False, "message": "数据库连接不可用，请稍后重试"}

            result = await forecast_revenue(
                pool, factory_id, horizon_days=horizon, window_days=90,
                store_id=store_id, persist=False,
            )
            points = result.get("points", [])
            if not points:
                return {
                    "success": True,
                    "message": (
                        f"{factory_id} 暂无足够历史营收数据，无法生成预测。"
                        "请先上传/对接 POS 流水。"
                    ),
                    "data": result,
                }
            total = sum(p["forecast_amount"] for p in points)
            lo = sum(p["lower_bound"] for p in points)
            hi = sum(p["upper_bound"] for p in points)
            model = (
                "线性趋势" if result["model_type"] == "linear_trend"
                else "近期均值（数据较少）"
            )
            message = (
                f"未来 {horizon} 天营收预测（{model}）：\n"
                f"预计合计 {_money(total)}（区间 {_money(lo)} ~ {_money(hi)}，80% 置信）\n"
                f"锚定日期 {result.get('anchor_date')}"
            )
            return {"success": True, "message": message, "data": result}
        except Exception as exc:  # pragma: no cover - defensive
            logger.exception("RestaurantTargetForecastTool failed: %s", exc)
            return {"success": False, "message": f"营收预测失败：{exc}"}


class RestaurantPaceAlertTool:
    """Gold Tool: 实时 pace 预警 — '本月目标完成情况' / '还差多少'.

    Wraps smartbi.services.target_alert_scheduler.compute_current_period_alert
    (current-period progress vs time-elapsed). Returns whether the tenant is
    on-track / behind and the gap.

    Intent binding SQL (apply once against cretas_db / cretas_prod_db):
      INSERT INTO ai_intent_config (id, intent_code, intent_name,
        intent_category, tool_name, keywords, is_active, sensitivity_level)
      VALUES (gen_random_uuid(), 'RESTAURANT_PACE_ALERT', '目标进度预警',
        'DATA_QUERY', 'restaurant_pace_alert',
        '["本月目标","完成情况","还差多少","进度","落后了吗","跟上计划"]',
        true, 'LOW');
    """

    tool_name: str = "restaurant_pace_alert"
    description: str = (
        "查询餐饮门店当前周期（本月/本周/今日）目标进度 vs 时间进度的实时预警，"
        "告诉你是否跟上计划、还差多少。"
        "适用于: '本月目标完成情况' / '还差多少达标' / '进度落后了吗'。"
    )

    _PERIOD_MAP = {
        "month": "month", "月": "month", "本月": "month",
        "week": "week", "周": "week", "本周": "week",
        "day": "day", "日": "day", "今天": "day", "今日": "day",
        "year": "year", "年": "year", "本年": "year",
    }

    async def execute(self, factory_id: str, params: dict) -> dict:
        params = params or {}
        raw = str(params.get("period_type", "month")).lower()
        period_type = self._PERIOD_MAP.get(raw, "month")
        kpi_kind = params.get("kpi_kind", "revenue")
        store_id = params.get("store_id")
        try:
            from smartbi.config import get_pg_pool
            from smartbi.services.target_alert_scheduler import (
                compute_current_period_alert,
            )

            pool = await get_pg_pool()
            if pool is None:
                return {"success": False, "message": "数据库连接不可用，请稍后重试"}

            r = await compute_current_period_alert(
                pool, factory_id, kpi_kind=kpi_kind, store_id=store_id,
                period_type=period_type, persist=False,
            )
            label = {"month": "本月", "week": "本周", "day": "今日",
                     "year": "本年"}.get(period_type, period_type)
            if r["alert_level"] == "NO_TARGET":
                return {
                    "success": True,
                    "message": (
                        f"{label}{('营业额' if kpi_kind == 'revenue' else '单量')}"
                        f"目标未设置（{r['period_key']}），请先前往目标管理页配置。"
                    ),
                    "data": r,
                }
            _level_map = {
                "OK": "进度正常 ✅",
                "WARN": "略微落后 ⚠️",
                "CRIT": "明显落后 🔴",
            }
            level_text = _level_map.get(r["alert_level"], r["alert_level"])
            target = r.get("target_amount")
            actual = r.get("actual_amount")
            gap = None
            if target is not None and actual is not None:
                gap = max(target - actual, 0)
            comp = r.get("completion_pct")
            elapsed = r.get("elapsed_pct")
            comp_s = f"{comp * 100:.1f}%" if comp is not None else "—"
            elapsed_s = f"{elapsed * 100:.1f}%" if elapsed is not None else "—"
            if kpi_kind == "revenue":
                detail = (
                    f"目标 {_money(target)} · 已完成 {_money(actual)}"
                    f"（{comp_s}）· 时间已过 {elapsed_s}"
                )
                gap_s = f"\n距达标还差 {_money(gap)}" if gap else ""
            else:
                detail = (
                    f"目标 {target} · 已完成 {actual}"
                    f"（{comp_s}）· 时间已过 {elapsed_s}"
                )
                gap_s = f"\n距达标还差 {gap:.0f}" if gap else ""
            message = f"{label}目标进度：{level_text}\n{detail}{gap_s}"
            return {"success": True, "message": message, "data": r}
        except Exception as exc:  # pragma: no cover - defensive
            logger.exception("RestaurantPaceAlertTool failed: %s", exc)
            return {"success": False, "message": f"查询目标进度失败：{exc}"}
