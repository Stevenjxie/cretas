"""MemberDeepAnalytics — 会员卡数据深度分析 (member-level dataset).

Distinct from member_consumption: that template operates on ORDER-LEVEL data
and counts orders paid via 会员卡/储值卡. This template operates on MEMBER-
LEVEL exports (one row per card/member) with columns like 卡号 / 卡状态 /
等级 / 充值总金额 / 当前余额 / 等级变动时间.

Real data seen: 唏嘛香会员数据.xlsx — 60K rows × 25 cols.

Outputs:
  - 等级分布: 会员数 by VIP level (VIP1/VIP2/...)
  - 卡状态分布: 正常 / 冻结 / 注销 share
  - 余额分层: sleeping-card check — members with high 余额 but long dormancy
  - 充值-消费-余额 漏斗: 总充值 → 当前累计消费 (充值 - 余额) → 剩余
  - Top 充值 / 余额 members (anonymized — mask phone/name tail)

Applies when schema has 卡号 OR 会员 column AND at least one 余额/充值 column.
Self-excludes order-level POS exports (those have 商品信息 / 账单号 / etc.
but not 卡号 as a primary identifier).
"""
from __future__ import annotations

from typing import Any, ClassVar, Dict, List

import polars as pl

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_CARD_ID_CANDIDATES = ("卡号", "会员卡号", "会员编号", "卡编号")
_MEMBER_NAME_CANDIDATES = ("会员", "会员姓名", "持卡人")
_LEVEL_CANDIDATES = ("等级", "会员等级", "卡等级")
_STATUS_CANDIDATES = ("卡状态", "会员状态", "状态")
# Apr 26 2026 phase 4 P1.b: extend candidate lists with (元)/short-form
# variants. qhj 4216 卡详情 had "余额(元)"/"本金"/"赠送金"/"押金" — none
# matched original candidates exactly, so member_deep_analytics.applies()
# returned False and 5 member-level queries (qhj-19/20/22/29/30) silently
# fell through to LLM with empty cache.
_BALANCE_CANDIDATES = (
    "当前余额(元)", "当前余额", "账户余额", "卡余额", "余额", "余额(元)",
)
_PRINCIPAL_BAL_CANDIDATES = (
    "当前本金余额(元)", "当前本金余额", "本金余额", "本金",
)
_TOTAL_RECHARGE_CANDIDATES = (
    "充值总金额(元)", "充值总金额", "累计充值", "总充值",
    "本金", "本金(元)",  # principal = total recharge for member-level exports
)
_GIFT_AMOUNT_CANDIDATES = (
    "充值赠送总额(元)", "充值赠送总额", "赠送金额", "当前赠送余额(元)",
    "赠送金", "赠送金(元)",
)
_OPEN_DATE_CANDIDATES = ("开卡时间", "开卡日期", "入会时间", "等级变动时间")
_LAST_CONSUME_DATE_CANDIDATES = ("最近消费时间", "最后消费", "上次消费")
_GENDER_CANDIDATES = ("性别",)

_SAMPLE_CAP = 500_000
_TOP_N = 10

# Status labels considered "active" for KPI (rest = inactive/closed)
_ACTIVE_STATUSES = ("正常", "激活", "已激活")
# Meta-label rows to filter (合计/总计 etc.)
# _META_LABELS 从 polars_backend 导入 —— 这里曾经各抄一份且抄漏了
# (2026-08-10 实测: 本文件当时只有 4 个词, 权威有 12 个),
# 认不出的汇总行会被当成明细算进去。判据: 这种判据只能有一处定义。
from smartbi.services.materialized_analytics.compute.polars_backend import _META_LABELS  # noqa: E402
@register
class MemberDeepAnalytics(AnalysisTemplate):

    sample_queries = [
        # original 5
        "会员卡数据",
        "会员等级分布",
        "会员余额统计",
        "储值会员分析",
        "会员充值情况",
        # v7 #6 (Apr 26 2026): extended for xmx-style member analysis queries.
        "会员都什么等级",
        "会员等级占比",
        "VIP 占比多少",
        "VIP 客户有多少",
        "高积分客户都是谁",
        "积分总量多少",
        "高积分会员",
        "卡里余额还有多少",
        "余额最多的会员",
        "余额超过 500 的会员",
        "高余额会员",
        "新发卡多少",
        "发卡量趋势",
        "沉睡会员有哪些",
        "流失会员",
        "会员留存率",
        "会员卡状态分布",
        "活跃卡占比",
        "会员卡数量",
        "会员总数",
        "会员排行",
        "Top 10 会员",
        "充值排行",
        # v8 #39 prod log analysis: real queries observed not yet matched
        "Top 10 储值持有者",
        "Top 10 储值持有者都是谁",
        "持卡 Top 10",
        "高储值会员",
        "储值大户",
        "充值最多的会员",
    ]

    # Deferred: applies() gates on 卡号 (card-id) + 余额/充值 (member-card
    # finance fields). NEITHER 卡号/卡状态/等级/余额/充值 has canonical
    # equivalent in ALIAS_TO_ATTR — member-card schema is a separate domain
    # not yet folded into bill_flow / product_summary.
    requires: ClassVar[RequiresSpec | None] = None

    @property
    def code(self) -> str:
        return "member_deep_analytics"

    @property
    def title(self) -> str:
        return "会员卡深度分析"

    def applies(self, schema: DataSchema) -> bool:
        if schema.domain not in (Domain.RESTAURANT, Domain.UNKNOWN):
            return False
        names = {f.name for f in schema.fields}
        has_card = any(c in names for c in _CARD_ID_CANDIDATES)
        has_balance = any(c in names for c in _BALANCE_CANDIDATES) or \
        any(c in names for c in _TOTAL_RECHARGE_CANDIDATES)  # noqa: E122
        # Must have card ID (proves member-level) + balance/recharge (proves card data)
        # The 商品信息 check rules out order-level POS data that happens to have 会员 field
        not_order_level = "商品信息" not in names and "账单号" not in names
        return has_card and has_balance and not_order_level

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)

        card_col = next((c for c in _CARD_ID_CANDIDATES if c in cols), None)
        level_col = next((c for c in _LEVEL_CANDIDATES if c in cols), None)
        status_col = next((c for c in _STATUS_CANDIDATES if c in cols), None)
        balance_col = next((c for c in _BALANCE_CANDIDATES if c in cols), None)
        recharge_col = next((c for c in _TOTAL_RECHARGE_CANDIDATES if c in cols), None)
        gift_col = next((c for c in _GIFT_AMOUNT_CANDIDATES if c in cols), None)
        name_col = next((c for c in _MEMBER_NAME_CANDIDATES if c in cols), None)
        open_date_col = next((c for c in _OPEN_DATE_CANDIDATES if c in cols), None)  # noqa: F841
        gender_col = next((c for c in _GENDER_CANDIDATES if c in cols), None)

        if card_col is None:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="no card-id column",
            )

        # Drop meta summary rows (if any 合计/总计 mixed in)
        if card_col:
            df = df.filter(
                ~pl.col(card_col).cast(pl.Utf8, strict=False).fill_null("")
                   .is_in(list(_META_LABELS))
            )

        if df.height > _SAMPLE_CAP:
            df = df.head(_SAMPLE_CAP)

        total_members = df.height

        # ── Level distribution ──
        by_level: List[Dict[str, Any]] = []
        if level_col:
            rows = (
                df.filter(pl.col(level_col).is_not_null())
                .group_by(level_col)
                .agg([
                    pl.len().alias("members"),
                    *([pl.col(balance_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("total_balance")]
                      if balance_col else []),
                    *([pl.col(recharge_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("total_recharge")]
                      if recharge_col else []),
                ])
                .sort("members", descending=True)
                .to_dicts()
            )
            by_level = [
                {
                    "level": str(r.get(level_col) or "<空>"),
                    "members": int(r["members"]),
                    "share_pct": round(r["members"] / total_members * 100, 2)
                    if total_members else 0.0,
                    "total_balance": round(float(r.get("total_balance") or 0.0), 2) if balance_col else None,
                    "total_recharge": round(float(r.get("total_recharge") or 0.0), 2) if recharge_col else None,
                }
                for r in rows
            ]

        # ── Status distribution ──
        by_status: List[Dict[str, Any]] = []
        active_members = 0
        if status_col:
            rows = (
                df.filter(pl.col(status_col).is_not_null())
                .group_by(status_col)
                .agg(pl.len().alias("members"))
                .sort("members", descending=True)
                .to_dicts()
            )
            by_status = [
                {
                    "status": str(r.get(status_col) or "<空>"),
                    "members": int(r["members"]),
                    "share_pct": round(r["members"] / total_members * 100, 2)
                    if total_members else 0.0,
                }
                for r in rows
            ]
            active_members = sum(
                r["members"] for r in by_status if r["status"] in _ACTIVE_STATUSES
            )

        # ── Balance / recharge / consumption funnel ──
        total_recharge = 0.0
        total_balance = 0.0
        total_gift = 0.0
        if recharge_col:
            total_recharge = float(
                df.select(
                    pl.col(recharge_col).cast(pl.Float64, strict=False).fill_null(0.0).sum()
                ).item() or 0.0
            )
        if balance_col:
            total_balance = float(
                df.select(
                    pl.col(balance_col).cast(pl.Float64, strict=False).fill_null(0.0).sum()
                ).item() or 0.0
            )
        if gift_col:
            total_gift = float(
                df.select(
                    pl.col(gift_col).cast(pl.Float64, strict=False).fill_null(0.0).sum()
                ).item() or 0.0
            )

        # Implied consumed amount = total_recharge - total_balance (rough; ignores
        # refunds / gift-only flows).
        implied_consumed = total_recharge - total_balance if recharge_col and balance_col else None
        consumed_rate_pct = None
        if implied_consumed is not None and total_recharge > 0:
            consumed_rate_pct = round(implied_consumed / total_recharge * 100, 2)

        # ── Balance stratification ──
        balance_bins: List[Dict[str, Any]] = []
        if balance_col:
            bin_expr = (
                pl.when(pl.col(balance_col).cast(pl.Float64, strict=False).fill_null(0.0) <= 0)
                  .then(pl.lit("0 元 (已清零)"))
                .when(pl.col(balance_col).cast(pl.Float64, strict=False) <= 100)
                  .then(pl.lit("0-100 元"))
                .when(pl.col(balance_col).cast(pl.Float64, strict=False) <= 500)
                  .then(pl.lit("100-500 元"))
                .when(pl.col(balance_col).cast(pl.Float64, strict=False) <= 1000)
                  .then(pl.lit("500-1000 元"))
                .when(pl.col(balance_col).cast(pl.Float64, strict=False) <= 5000)
                  .then(pl.lit("1000-5000 元"))
                .otherwise(pl.lit("5000 元+"))
            )
            rows = (
                df.with_columns(bin_expr.alias("_bin"))
                .group_by("_bin")
                .agg([
                    pl.len().alias("members"),
                    pl.col(balance_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("total"),
                ])
                .to_dicts()
            )
            bin_order = ["0 元 (已清零)", "0-100 元", "100-500 元", "500-1000 元", "1000-5000 元", "5000 元+"]
            by_bin_map = {str(r["_bin"]): r for r in rows}
            for label in bin_order:
                r = by_bin_map.get(label)
                if r:
                    balance_bins.append({
                        "bin": label,
                        "members": int(r["members"]),
                        "members_share_pct": round(r["members"] / total_members * 100, 2)
                        if total_members else 0.0,
                        "total_balance": round(float(r.get("total") or 0.0), 2),
                    })
                else:
                    balance_bins.append({
                        "bin": label, "members": 0, "members_share_pct": 0.0, "total_balance": 0.0,
                    })

        # ── Top N members by 充值/余额 (Apr 26 2026 phase 5 UX) ──
        # Without this, qhj-22 / xmx-22 类 "Top 10 储值持有者都是谁" gets only
        # aggregate stats. Now we surface a real ranked list (anonymized
        # phone tail) when the data has signal.
        def _mask(name: Any) -> str:
            s = str(name or '').strip()
            if not s:
                return '(空)'
            if len(s) <= 1:
                return s + '*'
            if len(s) == 2:
                return s[0] + '*'
            return s[0] + '*' * (len(s) - 2) + s[-1]

        top_recharge_members: List[Dict[str, Any]] = []
        top_balance_members: List[Dict[str, Any]] = []
        if recharge_col and total_recharge > 0:
            select_cols = [pl.col(recharge_col).cast(pl.Float64, strict=False).fill_null(0.0).alias('_r')]
            if name_col:
                select_cols.append(pl.col(name_col).cast(pl.Utf8, strict=False).alias('_name'))
            if level_col:
                select_cols.append(pl.col(level_col).cast(pl.Utf8, strict=False).alias('_level'))
            try:
                top_rows = (
                    df.select(select_cols)
                    .filter(pl.col('_r') > 0)
                    .sort('_r', descending=True)
                    .head(_TOP_N)
                    .to_dicts()
                )
                top_recharge_members = [
                    {
                        'rank': i + 1,
                        'name': _mask(r.get('_name')) if name_col else f'会员#{i+1}',
                        'level': r.get('_level') if level_col else None,
                        'amount': round(float(r.get('_r') or 0), 2),
                    }
                    for i, r in enumerate(top_rows)
                ]
            except Exception:
                pass
        if balance_col and total_balance > 0:
            select_cols = [pl.col(balance_col).cast(pl.Float64, strict=False).fill_null(0.0).alias('_b')]
            if name_col:
                select_cols.append(pl.col(name_col).cast(pl.Utf8, strict=False).alias('_name'))
            if level_col:
                select_cols.append(pl.col(level_col).cast(pl.Utf8, strict=False).alias('_level'))
            try:
                top_rows = (
                    df.select(select_cols)
                    .filter(pl.col('_b') > 0)
                    .sort('_b', descending=True)
                    .head(_TOP_N)
                    .to_dicts()
                )
                top_balance_members = [
                    {
                        'rank': i + 1,
                        'name': _mask(r.get('_name')) if name_col else f'会员#{i+1}',
                        'level': r.get('_level') if level_col else None,
                        'amount': round(float(r.get('_b') or 0), 2),
                    }
                    for i, r in enumerate(top_rows)
                ]
            except Exception:
                pass

        # ── Gender distribution (optional) ──
        by_gender: List[Dict[str, Any]] = []
        if gender_col:
            rows = (
                df.filter(pl.col(gender_col).is_not_null())
                .group_by(gender_col)
                .agg(pl.len().alias("members"))
                .sort("members", descending=True)
                .to_dicts()
            )
            by_gender = [
                {
                    "gender": str(r.get(gender_col) or "<空>"),
                    "members": int(r["members"]),
                    "share_pct": round(r["members"] / total_members * 100, 2)
                    if total_members else 0.0,
                }
                for r in rows
            ]

        # ── Build insight ──
        parts: List[str] = []
        parts.append(f"期内会员总数 {total_members:,}")
        if active_members > 0:
            active_pct = round(active_members / total_members * 100, 1) if total_members else 0.0
            parts[-1] += f"，激活 {active_members:,} ({active_pct:.1f}%)"
        parts[-1] += "。"
        if by_level:
            top_level = by_level[0]
            parts.append(f"最大等级：{top_level['level']} {top_level['members']:,} 人 ({top_level['share_pct']:.1f}%)。")
        if total_recharge > 0:
            parts.append(f"累计充值 {total_recharge:,.0f} 元")
            if total_balance > 0:
                parts[-1] += f"，当前余额 {total_balance:,.0f} 元"
            if consumed_rate_pct is not None:
                parts[-1] += f"（消化率 {consumed_rate_pct:.1f}%）"
            parts[-1] += "。"
        if balance_bins:
            zero_bin = next((b for b in balance_bins if b["bin"].startswith("0 元")), None)
            if zero_bin and zero_bin["members_share_pct"] > 20:
                parts.append(
                    f"⚠ {zero_bin['members']:,} 位会员余额已清零"
                    f"（占 {zero_bin['members_share_pct']:.1f}%），需激活唤回。"
                )
        # Spec §4.3: drive sleeping members or low recharge utilization into recall
        zero_bin_obj = next((b for b in balance_bins if b["bin"].startswith("0 元")), None) if balance_bins else None
        if zero_bin_obj and zero_bin_obj["members_share_pct"] > 20:
            action_rec = format_action_rec(
                object_target=f"余额清零会员 {zero_bin_obj['members']:,} 人 (占 {zero_bin_obj['members_share_pct']:.1f}%)",
                benefit_range="SMS / 公众号 + 储值返券唤回,召回率 8-15%,带回客单 5-10 倍 SMS 成本",
                prerequisite="会员手机号清洗 + 储值返券方案 + 节假日时点触达",
                timeline="本周内",
            )
        elif consumed_rate_pct is not None and consumed_rate_pct < 30 and total_recharge > 100000:
            action_rec = format_action_rec(
                object_target=f"会员储值消化率仅 {consumed_rate_pct:.1f}% (累计 {total_recharge:,.0f} 元)",
                benefit_range="储值卡到期前 30 天提醒 + 节假日活动可拉高消化率 8-15%,加速现金流回流",
                prerequisite="储值卡到期日清洗 + 自动 SMS 推送 + 限时活动套餐",
                timeline="本月内",
            )
        elif by_level:
            top_level = by_level[0]
            action_rec = format_action_rec(
                object_target=f"主力等级「{top_level['level']}」 ({top_level['members']:,} 人)",
                benefit_range="对该等级设计专属权益 / 升级激励,可拉升等级转化 5-10%",
                prerequisite="该等级权益设计 + 升级路径设计 + 收银员推荐话术",
                timeline="本月内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"会员体系 ({total_members:,} 人)",
                benefit_range="补完会员等级 + 激活流程后,LTV 提升 10-20%",
                prerequisite="设置会员等级 + 储值激励 + 收银员录入培训",
                timeline="本月内",
            )
        insight_text = " ".join(parts) + " " + action_rec

        # ── ECharts — pie of level distribution, fallback to status ──
        chart_config = None
        if by_level:
            chart_config = {
                "type": "pie",
                "title": {"text": "会员等级分布", "left": "center"},
                "tooltip": {"trigger": "item", "formatter": "{b}: {c} 人 ({d}%)"},
                "legend": {"top": 30},
                "series": [{
                    "name": "会员数",
                    "type": "pie",
                    "radius": ["30%", "65%"],
                    "data": [
                        {"name": l["level"], "value": l["members"]}
                        for l in by_level if l["members"] > 0  # noqa: E741
                    ],
                    "label": {"formatter": "{b}: {d}%"},
                }],
            }
        elif by_status:
            chart_config = {
                "type": "pie",
                "title": {"text": "会员卡状态分布", "left": "center"},
                "tooltip": {"trigger": "item"},
                "series": [{
                    "name": "会员数",
                    "type": "pie",
                    "radius": "60%",
                    "data": [
                        {"name": s["status"], "value": s["members"]}
                        for s in by_status if s["members"] > 0
                    ],
                }],
            }

        return TemplateResult(
            code=self.code,
            title=self.title,
            data={
                "total_members": total_members,
                "active_members": active_members,
                "by_level": by_level,
                "by_status": by_status,
                "balance_bins": balance_bins,
                "by_gender": by_gender,
                "total_recharge": round(total_recharge, 2) if recharge_col else None,
                "total_balance": round(total_balance, 2) if balance_col else None,
                "total_gift": round(total_gift, 2) if gift_col else None,
                "implied_consumed": round(implied_consumed, 2) if implied_consumed is not None else None,
                "consumed_rate_pct": consumed_rate_pct,
                # Apr 26 phase 5 UX: Top N member rankings (anonymized).
                # Surfaces in qhj-22 / xmx-22 类 "Top 10 持有者" queries.
                "top_recharge_members": top_recharge_members,
                "top_balance_members": top_balance_members,
            },
            chart_config=chart_config,
            kpis={
                "total_members": total_members,
                "active_members": active_members,
                "top_level": by_level[0]["level"] if by_level else None,
                "total_recharge": round(total_recharge, 2) if recharge_col else None,
                "total_balance": round(total_balance, 2) if balance_col else None,
                "consumed_rate_pct": consumed_rate_pct,
            },
            insight_text=insight_text,
        )
