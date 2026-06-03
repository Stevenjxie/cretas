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
