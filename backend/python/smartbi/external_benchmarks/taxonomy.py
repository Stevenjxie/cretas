from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Iterable, List


@dataclass(frozen=True)
class SegmentProfile:
    profile_code: str
    profile_type: str
    display_name: str
    description: str
    dimension_weights: Dict[str, float]
    external_signal_plan: List[str]
    analysis_questions: List[str]
    action_templates: List[str]
    source_code: str = "internal_methodology_seed"


CATEGORY_PROFILES: List[SegmentProfile] = [
    SegmentProfile(
        profile_code="hotpot",
        profile_type="category",
        display_name="Hotpot",
        description="High dwell-time, high ingredient visibility, table-turn and service-stability driven.",
        dimension_weights={
            "competition_density": 0.16,
            "table_turnover": 0.18,
            "food_cost": 0.18,
            "service_stability": 0.14,
            "review_taste": 0.12,
            "peak_capacity": 0.12,
            "supply_volatility": 0.10,
        },
        external_signal_plan=[
            "Amap 3km hotpot POI density and mall cluster density",
            "Authorized platform export for taste/service/environment score trend",
            "Internal table-turn, queue and food-cost comparison by store",
        ],
        analysis_questions=[
            "Is table-turn loss caused by long meal duration, slow clearing, or weak off-peak traffic?",
            "Is food cost pressure coming from beef/seafood volatility or portioning leakage?",
            "Does platform reputation point to taste stability, service speed, or environment ventilation?",
        ],
        action_templates=[
            "Separate peak-hour seating speed actions from food-cost procurement actions.",
            "Use weekday lunch and late-night packages only if table-turn and margin remain above threshold.",
        ],
    ),
    SegmentProfile(
        profile_code="qsr_fast_food",
        profile_type="category",
        display_name="Quick service / fast food",
        description="Throughput, repeat purchase, delivery speed and combo attachment driven.",
        dimension_weights={
            "order_throughput": 0.20,
            "labor_efficiency": 0.16,
            "delivery_readiness": 0.16,
            "combo_attachment": 0.14,
            "price_band_fit": 0.12,
            "store_density": 0.12,
            "stockout_rate": 0.10,
        },
        external_signal_plan=[
            "Amap competitor POI density near office/residential nodes",
            "Authorized delivery export for timeout, refund and repurchase",
            "Internal SKU speed and combo attachment by daypart",
        ],
        analysis_questions=[
            "Is revenue limited by traffic, kitchen throughput, or low attachment rate?",
            "Are delivery refunds concentrated in specific dayparts or SKUs?",
            "Does the price band fit nearby office/residential demand?",
        ],
        action_templates=[
            "Prioritize menu simplification before traffic buying when throughput is below target.",
            "Bundle only high-speed, low-error SKUs into delivery combos.",
        ],
    ),
    SegmentProfile(
        profile_code="tea_drinks",
        profile_type="category",
        display_name="Tea drinks",
        description="Cup speed, promotion rhythm, ingredient waste and location traffic driven.",
        dimension_weights={
            "cup_throughput": 0.18,
            "promotion_efficiency": 0.16,
            "ingredient_waste": 0.14,
            "delivery_share": 0.12,
            "new_product_pull": 0.12,
            "queue_conversion": 0.12,
            "competitor_density": 0.16,
        },
        external_signal_plan=[
            "Amap beverage/tea POI density and mall/metro proximity",
            "Authorized delivery export for promotion ROI and refund reasons",
            "Internal cup speed, stock loss and new-product sales share",
        ],
        analysis_questions=[
            "Is promotion increasing incremental cups or just discounting existing demand?",
            "Is ingredient waste driven by forecast error, SKU complexity, or preparation rules?",
            "Does competitor density require product differentiation or location-specific pricing?",
        ],
        action_templates=[
            "Treat new-product launch as a traffic test with waste ceiling and repurchase threshold.",
            "Cut low-volume toppings if they slow cup throughput without lifting ticket size.",
        ],
    ),
    SegmentProfile(
        profile_code="coffee",
        profile_type="category",
        display_name="Coffee",
        description="Daypart traffic, membership repeat, attach rate and office-scene density driven.",
        dimension_weights={
            "morning_traffic": 0.16,
            "membership_repeat": 0.18,
            "cup_throughput": 0.14,
            "bakery_attach_rate": 0.12,
            "office_density": 0.16,
            "delivery_share": 0.10,
            "gross_margin": 0.14,
        },
        external_signal_plan=[
            "Amap cafe and office-building density within walking radius",
            "Authorized platform export for rating and search exposure",
            "Internal member repeat, breakfast attach and daypart gross margin",
        ],
        analysis_questions=[
            "Is morning demand underperforming because of location, speed, or membership penetration?",
            "Does bakery attach increase gross profit or create spoilage?",
            "Does delivery share cannibalize store pickup margin?",
        ],
        action_templates=[
            "Use morning pickup bundles only when cup speed and attach margin are both healthy.",
            "Differentiate office stores by repeat and pickup convenience, not broad discounts.",
        ],
    ),
    SegmentProfile(
        profile_code="bbq_late_night",
        profile_type="category",
        display_name="BBQ / late-night dining",
        description="Late-night demand, alcohol attachment, environment comfort and labor scheduling driven.",
        dimension_weights={
            "late_night_traffic": 0.18,
            "alcohol_attach_rate": 0.16,
            "table_turnover": 0.12,
            "environment_score": 0.14,
            "labor_schedule_fit": 0.14,
            "food_cost": 0.12,
            "competition_density": 0.14,
        },
        external_signal_plan=[
            "Amap BBQ/night-food POI density near nightlife zones",
            "Authorized review export for environment and service complaints",
            "Internal late-night revenue, alcohol attach and labor-hour ROI",
        ],
        analysis_questions=[
            "Is late-night labor producing incremental profit or only extending fixed cost?",
            "Are environment issues limiting repeat despite good taste scores?",
            "Does alcohol attachment justify the SKU and compliance complexity?",
        ],
        action_templates=[
            "Schedule late-night labor from marginal gross profit by hour, not revenue alone.",
            "Treat ventilation/noise complaints as revenue-risk signals, not cosmetic feedback.",
        ],
    ),
    SegmentProfile(
        profile_code="fish_seafood",
        profile_type="category",
        display_name="Fish / seafood",
        description="Freshness, procurement volatility, spoilage and trust-sensitive review driven.",
        dimension_weights={
            "freshness_reputation": 0.18,
            "procurement_volatility": 0.18,
            "spoilage_rate": 0.16,
            "food_cost": 0.16,
            "review_taste": 0.12,
            "ticket_band_fit": 0.10,
            "store_chain_comparison": 0.10,
        },
        external_signal_plan=[
            "Authorized review export for freshness/taste/service tags",
            "Internal supplier price and yield by fish/seafood SKU",
            "Amap seafood/fish restaurant density by trade area",
        ],
        analysis_questions=[
            "Is gross margin below peers because purchase price, yield, spoilage, or pricing is wrong?",
            "Are freshness complaints correlated with supplier batches or store handling?",
            "Does ticket size match nearby seafood/fish competitors?",
        ],
        action_templates=[
            "Separate supplier renegotiation from kitchen-yield actions before changing menu price.",
            "Use store-level freshness complaint alerts as an operations KPI.",
        ],
    ),
    SegmentProfile(
        profile_code="casual_chinese",
        profile_type="category",
        display_name="Casual Chinese dining",
        description="Menu structure, banquet/private-room utilization, gross margin mix and review stability driven.",
        dimension_weights={
            "gross_margin_mix": 0.16,
            "table_turnover": 0.14,
            "private_room_utilization": 0.12,
            "review_stability": 0.14,
            "staff_service": 0.12,
            "category_fit": 0.12,
            "competitor_density": 0.20,
        },
        external_signal_plan=[
            "Amap Chinese restaurant density by business district",
            "Authorized review export for taste/service/environment trend",
            "Internal dish-margin mix and private-room utilization",
        ],
        analysis_questions=[
            "Is profit led by high-margin signature dishes or diluted by low-margin traffic dishes?",
            "Are private rooms increasing revenue per labor hour or blocking higher-turn tables?",
            "Does the local business district require family, banquet, or work-meal positioning?",
        ],
        action_templates=[
            "Build menu actions around signature dish margin and repeat, not only sales rank.",
            "Measure private-room value by gross profit per hour.",
        ],
    ),
]


CHANNEL_PROFILES: List[SegmentProfile] = [
    SegmentProfile(
        profile_code="dine_in",
        profile_type="channel",
        display_name="Dine-in",
        description="On-premise capacity, service experience and table-turn channel.",
        dimension_weights={
            "table_turnover": 0.22,
            "service_experience": 0.18,
            "environment_score": 0.16,
            "ticket_size": 0.14,
            "peak_capacity": 0.14,
            "repeat_visit": 0.16,
        },
        external_signal_plan=[
            "Authorized platform score export if merchant owns the account",
            "Amap trade-area competitor density",
            "Internal table-turn, queue and repeat-visit metrics",
        ],
        analysis_questions=[
            "Is dine-in bottleneck seat capacity, service speed, or reputation?",
            "Does high ticket size come with repeat or only one-time visitors?",
        ],
        action_templates=[
            "Optimize seat-hour gross profit before broad traffic campaigns.",
            "Use review dimension drops as leading indicators for repeat decline.",
        ],
    ),
    SegmentProfile(
        profile_code="delivery",
        profile_type="channel",
        display_name="Delivery",
        description="Delivery-ready SKU, fulfillment speed, refund and promotion efficiency channel.",
        dimension_weights={
            "fulfillment_speed": 0.20,
            "refund_rate": 0.18,
            "delivery_margin": 0.18,
            "promotion_roi": 0.16,
            "packing_quality": 0.12,
            "repurchase": 0.16,
        },
        external_signal_plan=[
            "Authorized delivery platform export for exposure, conversion, refund and timeout",
            "Internal SKU prep time, packing cost and delivery gross margin",
        ],
        analysis_questions=[
            "Is delivery growth profitable after platform fees, packing and promotion?",
            "Are refunds caused by kitchen errors, rider delay, or packaging?",
        ],
        action_templates=[
            "Keep delivery menu narrower than dine-in if prep-time variance is high.",
            "Judge campaigns by contribution margin and repurchase, not GMV alone.",
        ],
    ),
    SegmentProfile(
        profile_code="group_buy",
        profile_type="channel",
        display_name="Group-buy / deal packages",
        description="Traffic acquisition, conversion, margin and repeat conversion channel.",
        dimension_weights={
            "package_margin": 0.22,
            "new_customer_share": 0.18,
            "repeat_conversion": 0.18,
            "upsell_rate": 0.14,
            "redemption_distribution": 0.12,
            "rating_impact": 0.16,
        },
        external_signal_plan=[
            "Authorized platform package export for sales, redemption and reviews",
            "Internal package cost, upsell and repeat purchase",
        ],
        analysis_questions=[
            "Is the package acquiring new repeatable customers or discounting existing customers?",
            "Does redemption cluster at loss-making time slots?",
        ],
        action_templates=[
            "Use package design to protect core margin and create upsell paths.",
            "Stop packages that lift traffic but depress rating or repeat.",
        ],
    ),
    SegmentProfile(
        profile_code="mall_store",
        profile_type="channel",
        display_name="Mall store",
        description="Mall traffic, floor/category cluster, rent pressure and weekend peak channel.",
        dimension_weights={
            "mall_traffic_fit": 0.18,
            "rent_to_sales": 0.18,
            "weekend_peak_capture": 0.16,
            "competitor_cluster": 0.16,
            "ticket_band_fit": 0.14,
            "labor_schedule_fit": 0.18,
        },
        external_signal_plan=[
            "Amap mall and same-category POI density",
            "Internal rent-to-sales, weekend mix and labor schedule",
        ],
        analysis_questions=[
            "Does mall traffic convert for this category and price band?",
            "Is rent pressure offset by weekend peak and brand exposure?",
        ],
        action_templates=[
            "Use mall stores for peak capture and brand exposure only if rent-to-sales is controlled.",
            "Benchmark same-mall category density before increasing promotion spend.",
        ],
    ),
    SegmentProfile(
        profile_code="street_store",
        profile_type="channel",
        display_name="Street store",
        description="Neighborhood demand, visibility, repeat and local competition channel.",
        dimension_weights={
            "walkby_visibility": 0.14,
            "neighborhood_repeat": 0.18,
            "competitor_density": 0.18,
            "weekday_stability": 0.16,
            "rent_to_sales": 0.16,
            "private_domain": 0.18,
        },
        external_signal_plan=[
            "Amap nearby POI and residential/office proxies",
            "Internal repeat, private-domain orders and weekday stability",
        ],
        analysis_questions=[
            "Is the store surviving on repeat or one-off traffic?",
            "Does local competitor density require differentiation or private-domain retention?",
        ],
        action_templates=[
            "Invest in neighborhood retention before large platform discounts.",
            "Use weekday stability as the first health signal for street stores.",
        ],
    ),
    SegmentProfile(
        profile_code="private_domain",
        profile_type="channel",
        display_name="Private domain / member operations",
        description="Member retention, frequency, coupon efficiency and low-fee repeat channel.",
        dimension_weights={
            "member_repeat": 0.24,
            "coupon_efficiency": 0.16,
            "frequency_lift": 0.18,
            "gross_margin_protection": 0.16,
            "churn_risk": 0.14,
            "cross_store_migration": 0.12,
        },
        external_signal_plan=[
            "Internal CRM/member export and store-chain comparison",
            "No public raw personal data collection",
        ],
        analysis_questions=[
            "Are coupons increasing visit frequency or subsidizing already loyal customers?",
            "Which stores can share member traffic without cannibalizing each other?",
        ],
        action_templates=[
            "Segment members by expected incremental visit, not only last order date.",
            "Protect margin by sending benefits to churn-risk segments first.",
        ],
    ),
]


def profile_rows() -> Iterable[dict]:
    for profile in [*CATEGORY_PROFILES, *CHANNEL_PROFILES]:
        yield {
            "profile_code": profile.profile_code,
            "profile_type": profile.profile_type,
            "display_name": profile.display_name,
            "description": profile.description,
            "dimension_weights": profile.dimension_weights,
            "external_signal_plan": profile.external_signal_plan,
            "analysis_questions": profile.analysis_questions,
            "action_templates": profile.action_templates,
            "source_code": profile.source_code,
        }


def profiles_by_type(profile_type: str) -> List[SegmentProfile]:
    return [profile for profile in [*CATEGORY_PROFILES, *CHANNEL_PROFILES] if profile.profile_type == profile_type]

