"""Schema helpers — detect equivalent columns across different POS brands.

Different POS systems name the same concept differently. Templates should
reach for these helpers instead of hardcoding one name, so a template that
works on qhj (门店名称) also works on 东门口/桂满陇 (店铺名称).
"""
from __future__ import annotations

from typing import Iterable, Optional, Tuple


# Ordered by specificity — first match wins.
# Apr 24 P0-3 fix: expand aliases — 不二君 CSV used column name 分店 which
# the narrower list missed, returning 0 stores despite having 营收 5024.5万.
# Add common variants from real 大众点评 merchant exports (桂满陇/唏嘛香/
# IL TEATRO/上马火锅/御九井/不二君 all tested).
_STORE_COL_CANDIDATES: Tuple[str, ...] = (
    "门店名称", "店铺名称", "分店名称", "店名称",
    "门店", "店铺", "分店", "店名",
    "门市", "branch_name", "store_name", "shop_name",
    "Store", "Branch", "Outlet", "Shop",
)

_DATE_COL_CANDIDATES: Tuple[str, ...] = (
    # Business day (authoritative for daily aggregation)
    "营业日期", "交易日期", "业务日期", "单据业务日期", "日期",
    # Finer timestamps (fallback; slice to day when aggregating)
    "开单时间", "结单时间", "下单时间", "单据操作日期",
)

# Monetary columns by semantic role.
_GROSS_REVENUE_CANDIDATES: Tuple[str, ...] = (
    "应收金额", "营业额", "销售金额", "收款金额", "销售额",
)
_NET_REVENUE_CANDIDATES: Tuple[str, ...] = (
    "实收额", "实收金额", "实收", "实付", "折后金额",
)
_DISCOUNT_CANDIDATES: Tuple[str, ...] = (
    "折扣额", "优惠额", "优惠折扣", "分摊优惠",
)

# Order / customer count column candidates.
_CUSTOMER_COL_CANDIDATES: Tuple[str, ...] = (
    "客流量", "客数", "客单人数", "用餐人数", "就餐人数", "人数",
)

# Staff column candidates (extended beyond 服务员/销售员/收银员).
_STAFF_COL_CANDIDATES: Tuple[str, ...] = (
    "服务员", "销售员", "收银员", "店员", "员工",
)

# Table/channel column — qhj splits 桌位 and 订单来源; other POS exports
# (e.g., 大张鸽) combine them into 桌位或订单来源.
_TABLE_COL_CANDIDATES: Tuple[str, ...] = (
    "桌位", "桌位或订单来源", "订单来源", "桌号",
)


# ─── Review-domain candidates (大众点评 / 美团 / 抖音 / 小红书 export naming) ───
# Ordered by specificity; first match wins via _first_present.
# Excludes deliberately:
#   _TASTE_SCORE_CANDIDATES does NOT contain bare "口味" (overlaps 口味标签 csv list)
#   _VIP_FLAG_CANDIDATES does NOT contain "会员" (matches 会员卡 measure col on POS)

_REVIEW_STORE_CANDIDATES: Tuple[str, ...] = (
    "具体门店",            # 大众点评 standard (qhj Q3/Q4 exports)
    "评价门店",            # 大众点评 alternate
    "门店名称", "店铺名称",  # POS-style names some merchants reuse
    "分店", "门市",          # 美团 / regional variants
    # Post-mapper internal standard — Java/Python excel parser renames
    # several store-like vendor names (e.g. 分店) to "region" before
    # persistence. T6 multi-merchant test (Apr 24 2026) revealed this:
    # row_data keys are "region", not the original 分店. Fallback last.
    "region",
)

_STAR_CANDIDATES: Tuple[str, ...] = ("星级分", "评分", "星级")
_TASTE_SCORE_CANDIDATES: Tuple[str, ...] = ("口味分",)
_ENV_SCORE_CANDIDATES: Tuple[str, ...] = ("环境分",)
_SERVICE_SCORE_CANDIDATES: Tuple[str, ...] = ("服务分",)

_REVIEW_TIME_CANDIDATES: Tuple[str, ...] = (
    "评价时间", "评论时间", "发表时间", "创建时间",
    # Post-mapper internal standard — Java/Python excel parser renames
    # the primary review-time vendor variant to "time_period". Other
    # time-like cols (投诉时间, 最新回复时间) become time_period_2/_3 but
    # are NOT review timestamps so deliberately excluded.
    "time_period",
)
_REVIEW_CONTENT_CANDIDATES: Tuple[str, ...] = (
    "评价详情", "评价内容", "评论详情", "评论内容",
)

_VIP_FLAG_CANDIDATES: Tuple[str, ...] = ("是否vip", "是否VIP", "VIP")
_COMPLAINT_STATUS_CANDIDATES: Tuple[str, ...] = ("投诉状态",)
_REVIEW_PLATFORM_CANDIDATES: Tuple[str, ...] = (
    "平台", "评价来源", "渠道", "来源",
)
_DISH_TAG_CANDIDATES: Tuple[str, ...] = ("菜品标签",)
_COMPLAINT_TITLE_CANDIDATES: Tuple[str, ...] = ("投诉标题",)


def _first_present(cols: Iterable[str], candidates: Iterable[str]) -> Optional[str]:
    """Return first candidate that appears in cols, else None.

    Match tiers (Apr 24 P0-3 v2, 不二君 case — POS exports multi-line report
    metadata got concatenated into header cells, so real column "门店名称"
    lives at the suffix of a 500-char string):
      1. Exact equality  (fast path for well-formed exports)
      2. Suffix match "_<candidate>"  (embedded metadata prefix case)
      3. Substring presence  (last resort; may over-match)

    Returns the actual column name found in cols (not the candidate), so the
    caller can use it for DataFrame slicing directly.
    """
    cols_list = list(cols)
    col_set = set(cols_list)
    # Tier 1: exact match
    for c in candidates:
        if c in col_set:
            return c
    # Tier 2: suffix match on "_<candidate>" — P0-3 v2 (不二君 POS header quirk)
    for c in candidates:
        suffix = f"_{c}"
        for col in cols_list:
            if col and col.endswith(suffix):
                return col
    # Tier 3: substring (last resort)
    for c in candidates:
        for col in cols_list:
            if col and c in col:
                return col
    return None


def find_store_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding store identity, or None.

    Searches 门店名称 → 店铺名称 → 门店 → 店铺 → 店名.
    """
    return _first_present(cols, _STORE_COL_CANDIDATES)


def find_date_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding business date, or None.

    Prefers explicit business-day columns (营业日期 / 交易日期) before
    falling back to operation/timestamp columns (开单时间 etc.).
    """
    return _first_present(cols, _DATE_COL_CANDIDATES)


def find_gross_revenue_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding gross revenue (before deductions)."""
    return _first_present(cols, _GROSS_REVENUE_CANDIDATES)


def find_net_revenue_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding net revenue (after deductions)."""
    return _first_present(cols, _NET_REVENUE_CANDIDATES)


def find_discount_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding discount amount."""
    return _first_present(cols, _DISCOUNT_CANDIDATES)


def find_customer_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column name holding per-order customer count."""
    return _first_present(cols, _CUSTOMER_COL_CANDIDATES)


def find_staff_col(cols: Iterable[str]) -> Optional[str]:
    """Return the first staff-role column present (服务员 → 销售员 → ...)."""
    return _first_present(cols, _STAFF_COL_CANDIDATES)


def find_table_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding table/channel info.

    qhj splits this into 桌位 (physical table) and 订单来源 (channel). Other
    POS exports (大张鸽/dzp) combine them into 桌位或订单来源 which carries
    both semantics in one column. The classify_table() heuristic recognizes
    both formats (无桌位(外卖)/纯数字/包厢/VIP), so a single column is fine.
    """
    return _first_present(cols, _TABLE_COL_CANDIDATES)


# ─── Review-domain helpers (added 2026-04-24, Slice 4 D-1 POC) ───

def find_review_store_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding the reviewed store identity, or None.

    Searches 具体门店 → 评价门店 → 门店名称 → 店铺名称 → 分店 → 门市.
    Distinct from find_store_col() which is POS-domain (transaction store).
    """
    return _first_present(cols, _REVIEW_STORE_CANDIDATES)


def find_star_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding overall star rating (1-5 scale), or None.

    Priority: 星级分 (qualified) → 评分 (synonym) → 星级 (bare).
    """
    return _first_present(cols, _STAR_CANDIDATES)


def find_taste_score_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding 口味 rating score, or None.

    Deliberately does NOT match bare 口味 — that overlaps with 口味标签
    (csv tag list); we want the rating score column only.
    """
    return _first_present(cols, _TASTE_SCORE_CANDIDATES)


def find_env_score_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding 环境 rating score, or None."""
    return _first_present(cols, _ENV_SCORE_CANDIDATES)


def find_service_score_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding 服务 rating score, or None.

    `_score` suffix disambiguates from POS 服务费 (service charge measure).
    """
    return _first_present(cols, _SERVICE_SCORE_CANDIDATES)


def find_review_time_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding review/comment timestamp, or None.

    Distinct from find_date_col() which is POS business-date semantics.
    """
    return _first_present(cols, _REVIEW_TIME_CANDIDATES)


def find_review_content_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding review free-text content, or None."""
    return _first_present(cols, _REVIEW_CONTENT_CANDIDATES)


def find_vip_flag_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding VIP boolean flag, or None.

    Boolean-flag column only — does NOT match 会员卡 (measure col on POS).
    """
    return _first_present(cols, _VIP_FLAG_CANDIDATES)


def find_complaint_status_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding complaint status, or None.

    Strict literal — only 投诉状态. Does NOT match 投诉时间 / 投诉内容 /
    投诉标题 which are different complaint columns.
    """
    return _first_present(cols, _COMPLAINT_STATUS_CANDIDATES)


def find_review_platform_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding review platform/source, or None."""
    return _first_present(cols, _REVIEW_PLATFORM_CANDIDATES)


def find_dish_tag_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding csv-comma-separated dish tags, or None.

    Strict literal — only 菜品标签. Does NOT match 口味标签 / 服务标签.
    """
    return _first_present(cols, _DISH_TAG_CANDIDATES)


def find_complaint_title_col(cols: Iterable[str]) -> Optional[str]:
    """Return the column holding complaint title text, or None."""
    return _first_present(cols, _COMPLAINT_TITLE_CANDIDATES)


def preferred_revenue_col(cols: Iterable[str], primary_measure: Optional[str] = None) -> Optional[str]:
    """Return the best revenue column for user-facing questions.

    User asks "营业额"/"销售额"/"业绩" etc. For actionable insights, **actually
    received** (实收额) is usually more honest than gross (应收金额). qhj data
    has a ~25-30% gap: 应收 vs 实收 after platform commission + discounts.

    Preference order:
      1. 实收额 / 实收 / 实收金额 (net — what the restaurant actually received)
      2. primary_measure from schema (whatever domain_detector picked)
      3. 应收金额 / 营业额 (gross — listed price before deductions)

    Returns None if none of these exist.
    """
    col_set = set(cols)
    net = _first_present(col_set, _NET_REVENUE_CANDIDATES)
    if net:
        return net
    if primary_measure and primary_measure in col_set:
        return primary_measure
    gross = _first_present(col_set, _GROSS_REVENUE_CANDIDATES)
    if gross:
        return gross
    return None


def measure_annotation(col: Optional[str]) -> str:
    """Return a short annotation string for user-facing insights noting which
    measure was used. Example: '(按实收额统计)' or '(按应收金额统计)'."""
    if col is None:
        return ""
    if col in _NET_REVENUE_CANDIDATES:
        return f"(按{col}统计)"
    if col in _GROSS_REVENUE_CANDIDATES:
        return f"(按{col}统计 - 含扣减前)"
    return f"(按{col}统计)"


# Apr 26 2026 phase 5 (UX): non-monetary measure detection. Used by
# revenue-oriented templates (store_performance, monthly_trend) to skip
# materialization when measure is e.g. 星级分 / 评分 / 数量, since their
# insight_text hardcodes "销售额 X 元 / 客单价 Y 元/单" which mislabels
# rating data and confuses users.
_NON_REVENUE_MEASURE_PATTERNS = (
    '星级', '评分', '分数', '打分', '得分',  # ratings
    '人数', '位数', '次数',  # counts (but allow 笔数 / 单数 for revenue ctx)
)


def is_revenue_measure(col: Optional[str]) -> bool:
    """Return True if the measure is monetary (¥-denominated).

    Use to gate revenue-oriented templates that label values as "销售额 X 元"
    — those produce misleading output for non-monetary measures (e.g. 星级分,
    评分人次).
    """
    if not col:
        return False
    # Explicit monetary candidates first (priority signal)
    if col in _NET_REVENUE_CANDIDATES or col in _GROSS_REVENUE_CANDIDATES:
        return True
    if any(p in col for p in ('金额', '营业额', '营收', '收入', '销售额', '实收', '应收')):
        return True
    # Explicit non-revenue patterns
    if any(p in col for p in _NON_REVENUE_MEASURE_PATTERNS):
        return False
    # Conservative default: if name doesn't match either, allow (avoids
    # over-aggressive filtering on novel column names)
    return True


# May 30 2026: semantic aggregation per measure. The generic dashboard cards
# (monthly_trend / top_n_by_dim / category_distribution) historically SUM the
# primary_measure unconditionally. For an average-semantic measure like 星级分
# (1-5 stars) or a 率/% column, SUM produces nonsense ("星级分 累计 6.23万 分").
# qhj 评价下载 upload picked 星级分 as primary_measure → 星级趋势卡 showed an
# accumulating total instead of the meaningful ~4.8 average.
#
# Rule: revenue / count / quantity measures aggregate with SUM (additive);
# rating / score / rate / ratio / average measures aggregate with AVG (intensive).
_AVG_MEASURE_PATTERNS = (
    '星级', '评分', '分数', '打分', '得分', '评价分',  # ratings (intensive)
    '环境分', '服务分', '口味分',                        # 大众点评 review sub-scores
    '率', '比率', '占比', '百分比',                      # rates / ratios
    '均价', '客单', '平均', '人均',                      # per-unit averages
)

# Count-semantic measures: additive (SUM) but the unit is 个/份/次, not 元.
_COUNT_MEASURE_PATTERNS = (
    '销量', '销售数量', '数量', '笔数', '份数', '件数',
    '次数', '单数', '人次', '台数', '桌数',
)


def aggregation_for_measure(col: Optional[str]) -> str:
    """Return 'sum' or 'avg' — the correct aggregation for a measure column.

    'avg' for intensive measures (星级分/评分/率/客单价/人均) where summing is
    meaningless; 'sum' for additive measures (营业额/销量/笔数/数量) and as the
    conservative default. Answers HOW to aggregate (distinct from
    is_revenue_measure which answers WHETHER it is revenue).
    """
    if not col:
        return "sum"
    # AVG patterns (rating/rate/per-unit) take precedence — a 率/客单价 column
    # would otherwise hit is_revenue_measure's conservative True and be summed.
    if any(p in col for p in _AVG_MEASURE_PATTERNS):
        return "avg"
    # Explicit additive money (总额/营业额/销售额) sums.
    if is_revenue_measure(col):
        return "sum"
    # Conservative default: additive.
    return "sum"


def measure_unit(col: Optional[str]) -> str:
    """Short display unit for a measure: '元' (money), '分' (rating), '' otherwise.

    Used by trend/summary insight_text so a star-rating measure is never
    mislabelled with the money unit '元'.
    """
    if not col:
        return ""
    if any(p in col for p in ('星级', '评分', '分数', '打分', '得分', '评价分',
                              '环境分', '服务分', '口味分')):
        return "分"
    if any(p in col for p in ('率', '比率', '占比', '百分比')):
        return "%"
    # Count measures (additive but not money) — use a neutral count unit so a
    # 销量/笔数 trend is never mislabelled "X 元".
    if any(p in col for p in _COUNT_MEASURE_PATTERNS):
        return "份" if any(p in col for p in ('销量', '销售数量', '份数', '件数', '数量')) else "次"
    if is_revenue_measure(col):
        return "元"
    return ""
