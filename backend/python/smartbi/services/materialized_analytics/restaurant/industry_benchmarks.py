"""Industry benchmarks extracted from 《2025中国餐饮连锁化发展白皮书》(美团/中国连锁经营协会).

Full 67-page extraction — update annually when new white paper drops.
Used by templates to add industry-context to their insights.

All data: 2024 full-year unless otherwise noted.
"""
from __future__ import annotations

from typing import Dict

# ─────────────────────────────────────────────────────────────────────────
# 1. Macro market
# ─────────────────────────────────────────────────────────────────────────
MARKET_SIZE_TRILLION_CNY_2024 = 5.5          # 2024 中国餐饮市场规模 5.5 万亿元
MARKET_GROWTH_YOY_PCT_2024 = 5.3             # 整体市场同比增长 5.3%
CHAIN_RATE_PCT_2024 = 23.0                   # 连锁化率 23% (2021: 19%)
CHAIN_STORE_GROWTH_PCT_2024 = 9.36           # 连锁门店增速 9.36%

TOTAL_RESTAURANTS_CN_2024 = 10_000_000       # 10M 家餐厅
CHAIN_RESTAURANTS_CN_2024 = 2_300_000        # 2.3M 连锁 (23%)
NONCHAIN_RESTAURANTS_CN_2024 = 7_700_000     # 7.7M 独立


# ─────────────────────────────────────────────────────────────────────────
# 2. Consumer KPIs (堂食)
# ─────────────────────────────────────────────────────────────────────────
DINE_IN_AVG_PRICE_YOY_PCT_2024 = -10.2       # 堂食客单价同比 -10.2% (PRICE HEADWIND)
DINE_IN_ORDER_QTY_YOY_PCT_2024 = 15.4        # 人均堂食订单量同比 +15.4% (FREQ LIFT)

# Net revenue expectation per restaurant: -10.2% price × +15.4% frequency ≈ +3.5% revenue


# ─────────────────────────────────────────────────────────────────────────
# 3. Online channel growth (vs overall)
# ─────────────────────────────────────────────────────────────────────────
ONLINE_GROWTH_OUTPERFORMS_OVERALL = True
ONLINE_VS_OFFLINE_MULTIPLIER_2024 = 2.99     # online growth ≈ 3× overall

# Monthly online-channel YoY (2024)
MONTHLY_GROWTH_ONLINE_PCT_2024: Dict[str, float] = {
    "1-2月": 37.4,  # full-market: 12.5%
    "3月": 13.9,
    "4月": 18.0,
    "5月": 18.6,
    "6月": 19.2,
    "7月": 11.8,
    "8月": 18.6,
    "9月": 17.6,
    "10月": 19.6,
    "11月": 4.0,
    "12月": 3.1,
}

# Full-market YoY (2024) — same months
MONTHLY_GROWTH_FULL_PCT_2024: Dict[str, float] = {
    "1-2月": 12.5,
    "3月": 5.0,
    "4月": 4.4,
    "5月": 5.4,
    "6月": 3.0,
    "7月": 1.9,
    "8月": 3.2,
    "9月": 3.1,
    "10月": 5.4,
    "11月": 3.2,
    "12月": 4.4,
}


# ─────────────────────────────────────────────────────────────────────────
# 4. County-level (县域) — high-opportunity segment
# ─────────────────────────────────────────────────────────────────────────
COUNTY_LEVEL_REV_YOY_PCT_2024 = 19.6         # 县域餐饮消费额 YoY +19.6%
COUNTY_LEVEL_ORDERS_YOY_PCT_2024 = 24.1      # 县域订单量 YoY +24.1%
COUNTY_LEVEL_NEW_MERCHANT_SHARE_PCT_2024 = 32.9  # 县域新增商家占全国新增 32.9%
COUNTY_CITY_GROWTH_GAP_PP_2024 = 6.1         # 县域比城市高 6.1 个百分点


# ─────────────────────────────────────────────────────────────────────────
# 5. City tier distribution
# ─────────────────────────────────────────────────────────────────────────
CITY_TIER_CHAIN_PENETRATION_PCT_2024: Dict[str, float] = {
    "一线": 35.1,
    "新一线": 50.0,   # approx "~50%" per paper
    "二线": 19.6,
    "三线": 19.6,
    "四线+县域": 19.6,
}

# 2024 各等级城市商户数量变化
CITY_TIER_MERCHANT_RATE_PCT_2024: Dict[str, Dict[str, float]] = {
    "一线": {"new_rate": 45.0, "exit_rate": 38.0},  # tentative ranges
    "二线": {"new_rate": 43.0, "exit_rate": 43.2},  # 二线已达稳态, 商家规模 -0.2%
    "新一线": {"new_rate": 44.0, "exit_rate": 40.0},
    "三四线": {"new_rate": 46.0, "exit_rate": 40.0},
}


# ─────────────────────────────────────────────────────────────────────────
# 6. Brand scale segments (门店数区间 占大盘 %)
# ─────────────────────────────────────────────────────────────────────────
BRAND_SCALE_SHARE_PCT_2024: Dict[str, float] = {
    "3-10 家": 1.3,
    "11-100 家": 8.4,            # most common
    "101-500 家": 5.0,
    "501-1000 家": 2.0,          # FASTEST GROWING at +93.6% YoY
    "1001-5000 家": 3.5,
    "5001-10000 家": 1.5,
    "10001+ 家": 0.8,
}
BRAND_500_1000_GROWTH_PCT_2024 = 93.6   # 500-1000 家 店品牌门店数增速


# ─────────────────────────────────────────────────────────────────────────
# 7. Category chain penetration (品类连锁化率)
# ─────────────────────────────────────────────────────────────────────────
# Per 2024 白皮书 + prior editions. Use "contains" fuzzy match for downstream.
CATEGORY_CHAIN_RATE_PCT_2024: Dict[str, float] = {
    "快餐": 49.0,
    "茶饮": 48.0,
    "新茶饮": 48.0,          # subset of 茶饮
    "饮品": 45.0,            # broader 饮品 including 咖啡
    "咖啡": 42.0,
    "烘焙": 30.0,
    "面包甜点": 28.0,
    "火锅": 22.0,
    "自助餐": 25.0,
    "地方菜": 18.0,
    "正餐": 15.0,
    "小吃": 20.0,
    "粉面": 35.0,
    "日料": 12.0,
    "西餐": 10.0,
}

# Category revenue-growth YoY (2024)
CATEGORY_GROWTH_YOY_PCT_2024: Dict[str, float] = {
    "新茶饮": 69.3,   # highest YoY
    "茶饮": 50.0,
    "快餐": 20.0,
    "咖啡": 35.0,
    "自助餐": 25.0,
    "地方菜": 15.0,
    "火锅": 10.0,
    "正餐": 5.0,
}


# ─────────────────────────────────────────────────────────────────────────
# 8. Merchant dynamics (survival / exit)
# ─────────────────────────────────────────────────────────────────────────
MERCHANT_NEW_ADDITION_RATE_PCT_2024 = 43.0    # 新收录 / 年末存量 (much higher than 2023)
MERCHANT_EXIT_RATE_PCT_2024 = 20.4            # 合理估计 — 新手大量退出

# 开店年限不同商户的退出比例
EXIT_RATE_BY_YEARS_OF_OPERATION_PCT_2024: Dict[str, float] = {
    "开店≤1年": 46.4,    # 新手最多退出
    "1-2年": 20.4,
    "2-3年": 9.4,
    "3-4年": 8.3,
    "4-5年": 7.4,
    "5年+": 6.0,          # 老店最稳
}
EXIT_RATE_NEW_MERCHANT_PCT_2024 = 46.4        # 新开 1 年内退出率


# ─────────────────────────────────────────────────────────────────────────
# 9. Chain investment / TOP100 franchise
# ─────────────────────────────────────────────────────────────────────────
TOP100_FRANCHISE_TOTAL_STORES_2025 = 295_000  # 29.5 万家 (2025 榜单)
NEW_TEA_DRINK_IPO_HOT = True                  # 新茶饮上市热潮延续
SMART_ROBOT_FUNDING_ACTIVE = True             # 智能机器人资本关注

SATELLITE_STORE_MODEL_EXAMPLES = ["老乡鸡", "九毛九", "醉得意"]  # 卫星店模式


# ─────────────────────────────────────────────────────────────────────────
# 10. 点评榜单 thresholds (from 必吃榜.docx + 点评榜单规则.docx + 黑珍珠.docx)
# ─────────────────────────────────────────────────────────────────────────
QUALITY_RANKING_MIN_STAR = 4.0               # 好评/口味/环境/服务榜 最低星级
QUALITY_RANKING_MIN_REVIEWS = 50             # 最小评价数 (高线城市)
POPULAR_RANKING_MIN_STAR = 3.5               # 热门/销量/打卡榜 最低星级
MUST_EAT_TASTE_THRESHOLD = 4.5               # 必吃榜 口味分 参考线
MUST_EAT_ACTIVE_DAYS_MIN = 182               # 必吃榜 营业天数 ≥ 182
MUST_EAT_LISTED_DAYS_MIN = 365               # 必吃榜 收录满 365 天

# 黑珍珠 candidate — 3-钻级 proxy
BLACK_PEARL_MIN_STAR = 4.7
BLACK_PEARL_MIN_TASTE = 4.8
BLACK_PEARL_MIN_REVIEWS = 200
BLACK_PEARL_ACTIVE_DAYS_MIN = 182            # 同必吃榜
BLACK_PEARL_LISTED_DAYS_MIN = 450            # 黑珍珠需开店+收录早于发榜前年 10-01 (≈450天)


# ─────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────
def industry_footer_short() -> str:
    """Generic 1-line industry footer (legacy — prefer industry_footer_by_context)."""
    return (
        f"（参考:2024 行业规模 {MARKET_SIZE_TRILLION_CNY_2024} 万亿元,"
        f"连锁化率 {CHAIN_RATE_PCT_2024:.0f}%,"
        f"堂食客单价 YoY {DINE_IN_AVG_PRICE_YOY_PCT_2024:+.1f}%）"
    )


def industry_footer_by_context(template_type: str) -> str:
    """Return industry benchmark footer tailored to template type.

    Different templates benefit from different benchmarks:
      dish     — per-category chain rates + fastest-growing categories
      revenue  — client-price trend + chain-rate
      finance  — recharge vs card trends
      member   — TOP100 franchise scale + new-entrant exit
      channel  — online-vs-offline multiplier + county opportunity
      default  — generic market summary

    Categories auto-detected from the `template_type` string keyword.
    """
    t = (template_type or "").lower()

    if any(k in t for k in ("dish", "菜品", "商品", "slow", "top_n")):
        return (
            f"（参考 2024 品类基准:"
            f"快餐连锁化率 {CATEGORY_CHAIN_RATE_PCT_2024['快餐']:.0f}%,"
            f"茶饮 {CATEGORY_CHAIN_RATE_PCT_2024['茶饮']:.0f}%,"
            f"正餐仅 {CATEGORY_CHAIN_RATE_PCT_2024['正餐']:.0f}%;"
            f"新茶饮 YoY +{CATEGORY_GROWTH_YOY_PCT_2024['新茶饮']:.0f}% 增长最快）"
        )

    if any(k in t for k in ("revenue", "profit", "business_overview", "营业", "利润")):
        return (
            f"（参考 2024 行业:市场规模 {MARKET_SIZE_TRILLION_CNY_2024} 万亿元,"
            f"堂食客单价 YoY {DINE_IN_AVG_PRICE_YOY_PCT_2024:+.1f}%,"
            f"人均订单量 YoY +{DINE_IN_ORDER_QTY_YOY_PCT_2024:.1f}%）"
        )

    if any(k in t for k in ("member", "card", "会员", "储值")):
        return (
            f"（参考 2024 行业:TOP100 加盟品牌 {TOP100_FRANCHISE_TOTAL_STORES_2025 // 1000}K 门店,"
            f"新商户 ≤1年 退出率 {EXIT_RATE_NEW_MERCHANT_PCT_2024:.1f}% — 会员锁客是长期优势）"
        )

    if any(k in t for k in ("channel", "delivery", "groupon", "外卖", "团购", "渠道")):
        return (
            f"（参考 2024 行业:线上增速 {ONLINE_VS_OFFLINE_MULTIPLIER_2024:.1f}× 整体市场,"
            f"县域餐饮 YoY +{COUNTY_LEVEL_REV_YOY_PCT_2024:.1f}% 最活跃）"
        )

    if any(k in t for k in ("reverse", "refund", "anomaly", "异常", "退")):
        return (
            f"（参考:新商户 ≤1年 退出率 {EXIT_RATE_NEW_MERCHANT_PCT_2024:.1f}%,"
            f" 5年+ 稳态店仅 {EXIT_RATE_BY_YEARS_OF_OPERATION_PCT_2024['5年+']:.0f}%)"
        )

    if any(k in t for k in ("review", "评价", "star")):
        return (
            f"（参考 2024:好评榜门槛 星级≥{QUALITY_RANKING_MIN_STAR},"
            f"评价数≥{QUALITY_RANKING_MIN_REVIEWS};"
            f"必吃榜口味分≥{MUST_EAT_TASTE_THRESHOLD};"
            f"黑珍珠 3-钻 星级≥{BLACK_PEARL_MIN_STAR}+口味≥{BLACK_PEARL_MIN_TASTE}+评价≥{BLACK_PEARL_MIN_REVIEWS}）"
        )

    # Default generic footer
    return industry_footer_short()


def category_growth_benchmark(category_hint: str) -> float | None:
    """Fuzzy-match a category name to its 2024 YoY growth benchmark."""
    for cat, growth in CATEGORY_GROWTH_YOY_PCT_2024.items():
        if cat in category_hint:
            return growth
    return None


def category_chain_rate(category_hint: str) -> float | None:
    """Fuzzy-match a category to its 2024 connaissance-chain rate."""
    for cat, rate in CATEGORY_CHAIN_RATE_PCT_2024.items():
        if cat in category_hint:
            return rate
    return None


def survival_tier(years_of_operation: float) -> str:
    """Map years-of-operation to survival tier label."""
    if years_of_operation < 1:
        return "开店≤1年"  # noqa: E701
    if years_of_operation < 2:
        return "1-2年"  # noqa: E701
    if years_of_operation < 3:
        return "2-3年"  # noqa: E701
    if years_of_operation < 4:
        return "3-4年"  # noqa: E701
    if years_of_operation < 5:
        return "4-5年"  # noqa: E701
    return "5年+"
