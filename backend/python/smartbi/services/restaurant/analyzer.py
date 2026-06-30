"""餐饮 SmartBI V2 主入口 — 编排层

设计原则:
  - 不替代 legacy services/restaurant_analyzer.py (1751 行), 而是作为**新业务编排层**
  - 专注 Week 2-3 的"邓总救命组合": cost_rigidity + 渠道毛利率 + 对标预警 + 命名归一
  - 调用 Week 1-2 的所有底层组件 (menu_normalizer + bom_resolver + diagnostics_engine + benchmark_alert_engine + channel_margin_calculator)  # noqa: E501
  - 输出 unified report dict, 含 section 标签便于前端渲染

数据流:
                ┌──────────┐
   pos_df ──────►│  V2 主入口 │
                │ analyzer.py│
   financial ──►│           │
                │  - 数据完整性 (Week 3 加)
                │  - 命名归一 (menu_normalizer.apply)
                │  - 渠道毛利率 (channel_margin_calculator)
                │  - 诊断 (diagnostics_engine)
                │  - 对标预警 (benchmark_alert_engine)
                │  - 同店同比 (Week 3 加)
                └──┬────────┘
                   │
                   ▼
              unified report dict

使用示例 (邓总场景):
    >>> v2 = RestaurantAnalyzerV2(
    ...     factory_id="DENG_HUOGUO",
    ...     sub_sector="火锅",
    ... )
    >>> report = v2.analyze(
    ...     pos_df=feb_orders_df,
    ...     financial_data={
    ...         "current": {"revenue": 731047, "food_cost": 335212, "labor_cost": 237660, ...},
    ...         "previous": {"revenue": 1390503, "food_cost": 578603, "labor_cost": 323805, ...},
    ...         "monthly_revenue": 731047,
    ...     },
    ...     store_id="DENG-001",
    ...     period="2026-02",
    ... )
    >>> print(report["sections"]["financialMetrics"]["cost_rigidity"])
    0.561
    >>> print(len(report["sections"]["diagnostics"]))
    3  # cost_rigidity warning + food_cost warning + labor_cost warning
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Optional

if TYPE_CHECKING:
    from smartbi.services.finance.margin_spec import MarginSpec

from sqlalchemy.orm import Session

from shared.benchmark_alert_engine import BenchmarkAlertEngine
from shared.diagnostics_engine import DiagnosticsEngine
from shared.dynamic_config_resolver import DynamicConfigResolver
from shared.temporal_comparator import TemporalComparator

from .bom_resolver import RestaurantBomResolver
from .channel_margin_calculator import ChannelMarginCalculator
from .dining_period_heatmap import DiningPeriodHeatmap
from .long_tail_sku_detector import LongTailSkuDetector
from .member_rfm import MemberRfmAnalyzer
from .menu_normalizer import RestaurantMenuNormalizer
from .monthly_calibration_report import MonthlyCalibrationReporter
from .monthly_purchase_calibrator import MonthlyPurchaseCalibrator
from .review_analyzer import ReviewAnalyzer
from .review_analyzer_llm import LlmReviewAnalyzer
from .sku_form_manager import SkuFormManager
from .multi_store_comparator import MultiStoreComparator
from .store_pnl_one_pager import StorePnlOnePager
from .stored_value_analyzer import StoredValueAnalyzer

# ── Section handlers (Task 1.7 orchestrator delegation) ─────
# Each section's logic now lives in a dedicated handler under
# smartbi.services.restaurant.sections.*. The orchestrator below
# delegates to these handlers in the legacy section order while
# preserving byte-identical output.
#
# NOTE: Several handlers (menu_normalization, channel_margin,
# long_tail_sku, bom_layer_status) lazy-import RestaurantAnalyzerV2
# inside their methods to bridge back into the legacy helper layer.
# We import them at module top because their modules don't pull the
# analyzer at import time — only inside _get_analyzer() — so the
# circular reference resolves cleanly.
from .sections.base import SectionRequest, SectionStatus
from .sections.benchmark_alerts import BenchmarkAlertsHandler
from .sections.bom_layer_status import BomLayerStatusHandler
from .sections.calibration_history import CalibrationHistoryHandler
from .sections.channel_margin import ChannelMarginHandler
from .sections.diagnostics import DiagnosticsHandler
from .sections.dining_heatmap import DiningHeatmapHandler
from .sections.long_tail_sku import LongTailSkuHandler
from .sections.member_rfm import MemberRfmHandler
from .sections.menu_normalization import MenuNormalizationHandler
from .sections.multi_store_comparison import MultiStoreComparisonHandler
from .sections.review_analysis import ReviewAnalysisHandler
from .sections.store_pnl_one_pager import StorePnlOnePagerHandler
from .sections.stored_value import StoredValueHandler
from .sections.temporal_comparison import TemporalComparisonHandler
from .sections.advanced_traffic_persona import AdvancedTrafficPersonaHandler

logger = logging.getLogger(__name__)


# ── Result types ────────────────────────────────────────────


@dataclass
class FinancialMetrics:
    """从 financial_data 提取的标准化 metrics

    所有 ratio 字段都是 0-1 比率 (非百分比), 便于跟 benchmark 对比.
    """
    revenue: float
    food_cost: Optional[float] = None
    labor_cost: Optional[float] = None
    rent: Optional[float] = None
    other_cost: Optional[float] = None
    net_profit: Optional[float] = None

    food_cost_ratio: Optional[float] = None
    labor_cost_ratio: Optional[float] = None
    rent_ratio: Optional[float] = None
    restaurant_net_margin: Optional[float] = None

    cost_rigidity: Optional[float] = None
    revenue_change_pct: Optional[float] = None
    labor_cost_change_pct: Optional[float] = None
    food_cost_change_pct: Optional[float] = None

    # P3.5B F2: dual margin computation (折前 + 折后)
    gross_revenue: Optional[float] = None
    gross_margin_folded: Optional[float] = None     # (revenue - food_cost) / revenue * 100
    gross_margin_unfolded: Optional[float] = None   # (gross_revenue - food_cost) / gross_revenue * 100

    # Phase 1 #60: 诊断指标深化 (gold-available, 无 #57 依赖)
    # 仅在源数据存在时计算; None 表示数据缺失, 不参与诊断.
    ingredient_waste_rate: Optional[float] = None    # 报废损耗 / (采购食材成本 + 损耗) * 100 (百分比)
    actual_avg_ticket: Optional[float] = None        # 实际客单价 (元)
    target_avg_ticket: Optional[float] = None        # 目标客单价 (config → benchmark median)
    avg_ticket_vs_target: Optional[float] = None      # actual / target - 1.0 (偏差率)

    # Phase 2 #60: BOM 依赖诊断指标 (依赖 #57 agg_restaurant_product_cost)
    # 由逐菜 food_cost + has_price_data + POS 营收聚合. GUARD: 有价菜 < 3 道时不计算.
    gross_margin_per_dish: Optional[float] = None    # (Σ有价菜营收 - Σ有价菜成本) / Σ有价菜营收 (0-1)
    recipe_coverage_rate: Optional[float] = None     # 有价菜 / 在售菜 (0-1)
    priced_dish_count: Optional[int] = None          # has_price_data=true 的菜数 (诊断/UI 透明度)
    active_dish_count: Optional[int] = None          # 在售菜总数

    def to_dict(self) -> dict:
        return {
            "revenue": self.revenue,
            "foodCost": self.food_cost,
            "laborCost": self.labor_cost,
            "rent": self.rent,
            "otherCost": self.other_cost,
            "netProfit": self.net_profit,
            "foodCostRatio": self.food_cost_ratio,
            "laborCostRatio": self.labor_cost_ratio,
            "rentRatio": self.rent_ratio,
            "restaurantNetMargin": self.restaurant_net_margin,
            "costRigidity": self.cost_rigidity,
            "revenueChangePct": self.revenue_change_pct,
            "laborCostChangePct": self.labor_cost_change_pct,
            "foodCostChangePct": self.food_cost_change_pct,
            "grossRevenue": self.gross_revenue,
            "grossMarginFolded": self.gross_margin_folded,
            "grossMarginUnfolded": self.gross_margin_unfolded,
            "ingredientWasteRate": self.ingredient_waste_rate,
            "actualAvgTicket": self.actual_avg_ticket,
            "targetAvgTicket": self.target_avg_ticket,
            "avgTicketVsTarget": self.avg_ticket_vs_target,
            "grossMarginPerDish": self.gross_margin_per_dish,
            "recipeCoverageRate": self.recipe_coverage_rate,
            "pricedDishCount": self.priced_dish_count,
            "activeDishCount": self.active_dish_count,
        }


# ── Main V2 analyzer ────────────────────────────────────────


class RestaurantAnalyzerV2:
    """餐饮 SmartBI V2 主分析器 (编排层)

    每次 web-admin 上传新数据时, 实例化一次, 调用 analyze() 一次.
    """

    def __init__(
        self,
        factory_id: str,
        sub_sector: str,
        db_session: Optional[Session] = None,
        sku_form_manager: Optional[SkuFormManager] = None,
        monthly_calibrator: Optional[MonthlyPurchaseCalibrator] = None,
        margin_spec: Optional["MarginSpec"] = None,
        expense_account_tree_id: Optional[str] = None,  # P3.5B F5
    ):
        if not factory_id:
            raise ValueError("factory_id 不能为空")
        if not sub_sector:
            raise ValueError("sub_sector 必须指定 (例 '火锅' / '鱼类餐饮')")

        self.factory_id = factory_id
        self.sub_sector = sub_sector
        self.db_session = db_session

        # Week 4.4 BOM Layer 2 + 3 (可选)
        self.sku_form_manager = sku_form_manager
        self.monthly_calibrator = monthly_calibrator

        # P3.5B F1: MarginSpec controls boundary decisions for _extract_financial_metrics
        from smartbi.services.finance.margin_spec import MarginSpec as _MarginSpec
        self.margin_spec = margin_spec if margin_spec is not None else _MarginSpec()

        # P3.5B F5: expense account tree lazy-loaded on first access
        self.expense_account_tree_id = expense_account_tree_id or "default"
        self._expense_tree_cache = None  # Optional[ExpenseAccountTree]

        # 初始化所有底层组件
        self.config_resolver = DynamicConfigResolver(
            factory_id=factory_id, domain="restaurant", db_session=db_session
        )
        self.menu_normalizer = RestaurantMenuNormalizer(
            factory_id=factory_id, db_session=db_session
        )
        self.bom_resolver = RestaurantBomResolver(
            factory_id=factory_id,
            sub_sector=sub_sector,
            config_resolver=self.config_resolver,
            sku_form_manager=sku_form_manager,
            monthly_calibrator=monthly_calibrator,
        )
        self.diagnostics_engine = DiagnosticsEngine(
            domain="restaurant", sub_sector=sub_sector
        )
        self.benchmark_alert_engine = BenchmarkAlertEngine(
            domain="restaurant", sub_sector=sub_sector
        )
        self.channel_margin_calc = ChannelMarginCalculator(
            factory_id=factory_id,
            sub_sector=sub_sector,
            bom_resolver=self.bom_resolver,
            config_resolver=self.config_resolver,
        )

        # Week 4 新增 analyzers (stateless, 每次分析实例化即可)
        self.store_pnl_one_pager = StorePnlOnePager()
        self.dining_heatmap = DiningPeriodHeatmap()
        self.stored_value_analyzer = StoredValueAnalyzer()
        self.long_tail_detector = LongTailSkuDetector()
        self.review_analyzer = ReviewAnalyzer()
        # W5.5: LLM-based review analyzer (falls back to regex on failure)
        self.llm_review_analyzer = LlmReviewAnalyzer(fallback_analyzer=self.review_analyzer)
        self.member_rfm_analyzer = MemberRfmAnalyzer()

        # W6.4: 多店对比分析器 (stateless)
        self.multi_store_comparator = MultiStoreComparator()

        # W5.7: Layer A 月度校准历史报告 (lazy: 需要 db_session)
        self.calibration_reporter: Optional[MonthlyCalibrationReporter] = None
        if db_session is not None:
            self.calibration_reporter = MonthlyCalibrationReporter(
                db_session=db_session
            )

        # W5.6 同店同比 (Week 3 写的 module, W5 集成进来)
        self.temporal_comparator = TemporalComparator(group_col="门店名称")

        # ── Section handler instances (Task 1.7) ────────────
        # Instantiated once per analyzer so each handler's internal cache
        # (e.g. ChannelMarginHandler caches a per-(factory, sub_sector)
        # analyzer to avoid rebuilding BomResolver) survives across calls.
        self._menu_normalization_handler = MenuNormalizationHandler()
        self._channel_margin_handler = ChannelMarginHandler()
        self._diagnostics_handler = DiagnosticsHandler()
        self._benchmark_alerts_handler = BenchmarkAlertsHandler()
        self._store_pnl_handler = StorePnlOnePagerHandler()
        self._dining_heatmap_handler = DiningHeatmapHandler()
        self._stored_value_handler = StoredValueHandler()
        self._long_tail_handler = LongTailSkuHandler()
        self._review_analysis_handler = ReviewAnalysisHandler()
        self._member_rfm_handler = MemberRfmHandler()
        self._temporal_handler = TemporalComparisonHandler()
        self._multi_store_handler = MultiStoreComparisonHandler()
        self._calibration_handler = CalibrationHistoryHandler()
        self._bom_layer_status_handler = BomLayerStatusHandler()
        self._advanced_traffic_persona_handler = AdvancedTrafficPersonaHandler()

    # ── P3.5B F5: expense account tree ─────────────────

    def get_expense_account_tree(self):
        """Lazy-load the expense account tree by id.

        Tree YAML files live in knowledge/restaurant/expense_account_tree/{id}.yaml.
        Default id is 'default' (5-bucket fallback). Raises FileNotFoundError
        if the id doesn't match a YAML file.

        Part of P3.5B F5. Consumers: expense_breakdown section handler (F6),
        future leaf-level diagnostic rules.
        """
        if self._expense_tree_cache is None:
            from pathlib import Path
            from smartbi.services.finance.expense_account_tree import load_tree_from_yaml

            yaml_path = (
                Path(__file__).parents[2]
                / "knowledge"
                / "restaurant"
                / "expense_account_tree"
                / f"{self.expense_account_tree_id}.yaml"
            )
            if not yaml_path.exists():
                raise FileNotFoundError(
                    f"Expense account tree {self.expense_account_tree_id!r} not found at {yaml_path}"
                )
            self._expense_tree_cache = load_tree_from_yaml(yaml_path)
        return self._expense_tree_cache

    # ── 主入口 ─────────────────────────────────────────

    def analyze(
        self,
        pos_df=None,
        financial_data: Optional[dict] = None,
        store_id: Optional[str] = None,
        store_name: Optional[str] = None,
        period: str = "current",
        product_col: str = "商品名称",
        order_method_col: str = "订单来源",
        revenue_col: str = "实收",
        datetime_col: str = "开单时间",
        quantity_col: str = "数量",
        reviews: Optional[list[dict]] = None,
        members: Optional[list[dict]] = None,  # W5.4 会员 RFM
        use_llm_reviews: bool = True,          # W5.5+: LLM default (auto-fallback to regex)
    ) -> dict:
        """V2 主分析入口

        Args:
            pos_df: POS 销售 DataFrame (可选)
            financial_data: 财务数据 dict, 结构 {
                "current": {revenue, food_cost, labor_cost, rent, ...},
                "previous": {同上},  # 可选, 用于 cost_rigidity 同环比
                "monthly_revenue": float,  # 用于 benchmark 影响估算
            }
            store_id: 门店 ID
            store_name: 门店中文名
            period: 期间标签
            product_col / order_method_col / revenue_col: POS 列名 (默认中文)

        Returns:
            完整报告 dict, 含 sections + summary + warnings
        """
        report: dict = {
            "factoryId": self.factory_id,
            "subSector": self.sub_sector,
            "storeId": store_id,
            "storeName": store_name,
            "period": period,
            "sections": {},
            "warnings": [],
            "executiveSummary": [],
        }

        # ── W6: Auto-load reviews from DB if not provided in request ──
        # Done up-front so the loaded reviews can flow into both review_analysis
        # and multi_store_comparison handlers via context.
        if not reviews and self.db_session:
            try:
                from smartbi.database.models import RestaurantReview
                db_reviews = (
                    self.db_session.query(RestaurantReview)
                    .filter(RestaurantReview.factory_id == self.factory_id)
                    .order_by(RestaurantReview.review_time.desc())
                    .limit(500)
                    .all()
                )
                if db_reviews:
                    reviews = [
                        {
                            "id": r.review_id or r.id,
                            "rating": float(r.rating),
                            "content": r.content,
                            "created_at": r.review_time.isoformat() if r.review_time else "",
                            "store_name": r.store_name,
                            "platform": r.platform,
                        }
                        for r in db_reviews
                    ]
                    logger.info(
                        f"Auto-loaded {len(reviews)} reviews from DB for factory {self.factory_id}"
                    )
            except Exception as e:
                logger.warning(f"Failed to auto-load reviews from DB: {e}")

        if pos_df is not None and revenue_col not in pos_df.columns:
            for alias in ("实收额", "实收金额", "销售金额", "营业额"):
                if alias in pos_df.columns:
                    revenue_col = alias
                    break

        # ── Build the per-section request template ──
        # Each handler gets the same factory/sub_sector/store metadata; the
        # `params` dict carries section-specific inputs (POS DataFrame,
        # financial data, column overrides, etc).
        base_params: dict[str, Any] = {
            "pos_df": pos_df,
            "financial_data": financial_data,
            "product_col": product_col,
            "order_method_col": order_method_col,
            "revenue_col": revenue_col,
            "datetime_col": datetime_col,
            "quantity_col": quantity_col,
            "store_col": "门店名称",
            "reviews": reviews,
            "members": members,
            "use_llm": use_llm_reviews,
            "city": "上海",
            "business_district": "人民广场",
            "mall_name": None,
        }

        def _make_req(extra: Optional[dict[str, Any]] = None) -> SectionRequest:
            params = dict(base_params)
            if extra:
                params.update(extra)
            return SectionRequest(
                factory_id=self.factory_id,
                upload_id=None,
                sub_sector=self.sub_sector,
                store_id=store_id,
                store_name=store_name,
                period=period,
                params=params,
            )

        # ── Shared context for handlers in the batch ──
        # Some handlers read pre-staged values from context (e.g. financial
        # metrics) instead of recomputing. We mutate this dict between phases
        # because diagnostics+benchmark_alerts need a snake_case ratio dict
        # while store_pnl_one_pager needs the camelCase FinancialMetrics dict.
        context: dict[str, Any] = {}
        if pos_df is not None:
            context["pos_df"] = pos_df
        if reviews:
            context["reviews"] = reviews
        if members:
            context["members"] = members
        if self.db_session is not None:
            context["db_session"] = self.db_session
        # P3.5B F8: propagate stored_value treatment from margin_spec to stored_value handler
        context["stored_value_mode"] = self.margin_spec.stored_value_treatment

        # ── Section 1: 命名归一 (POS only) ──
        # NOTE: explicit `is None` checks — pandas DataFrames raise ValueError on truthiness.
        # DIRECT CALL: MenuNormalizationHandler.lazy-builds an inner V2 without
        # forwarding db_session, so its menu_normalizer.apply() can't read the
        # restaurant_dish_alias table — silently dropping customer-confirmed
        # alias merges. self.menu_normalizer carries the correct db_session
        # because __init__ wired it. See TODO(P2): refactor handlers to accept
        # state via context.
        if pos_df is not None and product_col in pos_df.columns:
            report["sections"]["menuNormalization"] = self._normalize_menu(pos_df, product_col)

        # ── Section 2: 渠道毛利率 (POS only) ──
        # Legacy emits a warning if POS is provided but the required columns
        # are missing — preserve that exact warning string for byte identity.
        # DIRECT CALL: ChannelMarginHandler.lazy-builds an inner V2 without
        # forwarding sku_form_manager / monthly_calibrator / db_session, so
        # its bom_resolver runs at Layer 1 — silently downgrading the precision
        # of channel-level COGS / gross_profit / gross_margin_pct. self.channel_margin_calc
        # carries the correct bom_resolver with full state. See TODO(P2):
        # refactor handlers to accept state via context.
        if (
            pos_df is not None
            and order_method_col in pos_df.columns
            and revenue_col in pos_df.columns
        ):
            channel_section = self._compute_channel_margin(
                pos_df, order_method_col, revenue_col, store_id, period
            )
            report["sections"]["channelMargin"] = channel_section
            # 渠道分析的 advice 进 executive summary
            for advice in channel_section.get("adviceZh", []):
                report["executiveSummary"].append(advice)
        else:
            if pos_df is not None:
                report["warnings"].append(
                    f"POS 数据缺少列 {order_method_col!r} 或 {revenue_col!r}, 跳过渠道毛利率分析"
                )

        # ── Section 3: 财务诊断 + 对标预警 (financial only) ──
        # The DiagnosticsHandler / BenchmarkAlertsHandler both expect a
        # snake_case ratio dict in context["financial_metrics"]. The legacy
        # _run_diagnostics() builds exactly that dict from a FinancialMetrics
        # instance — we reuse it via _diagnostics_metric_dict() so the
        # engine sees identical keys.
        if financial_data:
            metrics = self._extract_financial_metrics(financial_data)
            report["sections"]["financialMetrics"] = metrics.to_dict()

            ratio_metric_dict = self._diagnostics_metric_dict(metrics)

            # Stage snake_case ratios in context for diagnostics + benchmark_alerts.
            context["financial_metrics"] = ratio_metric_dict
            context["financial_data"] = financial_data

            # 跑诊断
            diagnostics_resp = self._diagnostics_handler.compute(_make_req(), context)
            if diagnostics_resp.status == SectionStatus.OK:
                # DiagnosticsHandler.data wraps the list in a dict
                # ({"diagnoses": [...], "criticalCount": N, "totalCount": N}),
                # while the legacy report stores the raw list. Unwrap.
                diagnostics_list = diagnostics_resp.data.get("diagnoses", [])
            else:
                diagnostics_list = []
            report["sections"]["diagnostics"] = diagnostics_list

            # 跑对标预警
            alerts_resp = self._benchmark_alerts_handler.compute(_make_req(), context)
            if alerts_resp.status == SectionStatus.OK:
                # BenchmarkAlertsHandler.data wraps the list in a dict
                # ({"alerts": [...], "redCount": N, ...}); legacy stores the
                # raw list. Unwrap.
                alerts_list = alerts_resp.data.get("alerts", [])
            else:
                alerts_list = []
            report["sections"]["benchmarkAlerts"] = alerts_list

            # 把 critical/red 诊断 + 对标进 executive summary
            # NOTE: legacy iterates the Diagnosis dataclass list; here we
            # iterate the unwrapped dict list. Field names are camelCase
            # because Diagnosis.to_dict() / Alert.to_dict() use camelCase.
            for d in diagnostics_list:
                if d.get("severity") == "critical":
                    metric_name_zh = d.get("metricNameZh", "")
                    description_zh = d.get("descriptionZh") or d.get("status", "")
                    truncated = description_zh[:80] if description_zh else d.get("status", "")
                    report["executiveSummary"].insert(
                        0, f"🔴 [{metric_name_zh}] {truncated}"
                    )
            for a in alerts_list[:3]:
                if a.get("severity") in ("red", "yellow"):
                    report["executiveSummary"].append(a.get("messageZh", ""))
        else:
            report["warnings"].append(
                "未提供财务数据 (financial_data), 跳过 cost_rigidity / 食材率 / 人力率 等财务诊断"
            )

        # ── Week 4.1: 单店 P&L 一页纸 (需 financial + optional channel/pos) ──
        # The handler reads context["financial_metrics"], so we swap the
        # snake_case ratio dict (used by diagnostics above) for the
        # camelCase FinancialMetrics.to_dict() that StorePnlOnePager expects.
        # Also stage the unwrapped diagnostics + benchmark_alerts lists for
        # the handler's optional inputs.
        if financial_data:
            context["financial_metrics"] = report["sections"]["financialMetrics"]
            context["diagnostics"] = report["sections"].get("diagnostics", [])
            context["benchmark_alerts"] = report["sections"].get("benchmarkAlerts", [])
            context["channel_margin"] = report["sections"].get("channelMargin")
            # Legacy passes temporal_comparison=None here regardless of whether
            # the temporal section runs later — preserve that.
            context["temporal_comparison"] = None

            pnl_resp = self._store_pnl_handler.compute(_make_req(), context)
            if pnl_resp.status == SectionStatus.OK:
                pnl_section = pnl_resp.data
                report["sections"]["storePnlOnePager"] = pnl_section
                headline = pnl_section.get("headline")
                if headline:
                    report["executiveSummary"].insert(0, f"📋 {headline}")
            else:
                # Legacy logs a warning + adds to report.warnings on exception.
                # The handler maps exceptions to SKIPPED, so we synthesize the
                # warning text here when the skip reason indicates a build error.
                for w in pnl_resp.warnings:
                    if "store_pnl 构建失败" in w:
                        # Strip the handler prefix and reuse the original
                        # legacy phrasing for byte identity.
                        err = w.replace("store_pnl 构建失败: ", "")
                        logger.warning(f"store_pnl_one_pager 失败: {err}")
                        report["warnings"].append(f"单店 P&L 一页纸生成失败: {err}")

        # ── Week 4.2: 营业时段热力图 (需 POS 含时间列) ──
        if (
            pos_df is not None
            and datetime_col in pos_df.columns
            and revenue_col in pos_df.columns
        ):
            heatmap_resp = self._dining_heatmap_handler.compute(_make_req(), context)
            if heatmap_resp.status == SectionStatus.OK:
                report["sections"]["diningHeatmap"] = heatmap_resp.data
            else:
                # Legacy: warns + appends to report.warnings on exception only.
                for w in heatmap_resp.warnings:
                    if "dining_heatmap 生成失败" in w:
                        err = w.replace("dining_heatmap 生成失败: ", "")
                        logger.warning(f"dining_heatmap 失败: {err}")
                        report["warnings"].append(f"营业时段热力图生成失败: {err}")

        # ── Week 4.3a: 充卡依赖度 (需 financial 含 stored_value) ──
        # Legacy gates this section on giveaway > 0 BEFORE running. The handler
        # also gates internally and returns SKIPPED otherwise. To preserve the
        # legacy "no warnings on missing giveaway" behavior we mirror the
        # outer guard here so the SKIPPED response never triggers a warning.
        if financial_data:
            current = financial_data.get("current") or financial_data
            giveaway = self._safe_float(current.get("stored_value_giveaway"))
            if giveaway is not None and giveaway > 0:
                sv_resp = self._stored_value_handler.compute(_make_req(), context)
                if sv_resp.status == SectionStatus.OK:
                    sv_section = sv_resp.data
                    report["sections"]["storedValueDependency"] = sv_section
                    severity = sv_section.get("severity")
                    if severity in ("warning", "critical"):
                        message_zh = sv_section.get("messageZh") or sv_section.get("message_zh", "")
                        if message_zh:
                            report["executiveSummary"].append(message_zh)
                else:
                    # Legacy only logs (no warnings.append) on stored_value failure
                    for w in sv_resp.warnings:
                        if "stored_value_analyzer" in w:
                            logger.warning(f"stored_value_analyzer 失败: {w}")

        # ── Week 4.3b: 长尾 SKU 识别 (需 POS 含商品 + quantity + revenue) ──
        if (
            pos_df is not None
            and product_col in pos_df.columns
            and revenue_col in pos_df.columns
        ):
            lt_resp = self._long_tail_handler.compute(_make_req(), context)
            if lt_resp.status == SectionStatus.OK:
                lt_section = lt_resp.data
                report["sections"]["longTailSku"] = lt_section
                recommended = lt_section.get("recommendedDelistCount", 0)
                if recommended and recommended > 0:
                    saving = lt_section.get("estimatedCostSaving", 0)
                    report["executiveSummary"].append(
                        f"🔻 长尾 SKU: 建议下架 {recommended} 个, "
                        f"预计年省 ¥{saving:,.0f}"
                    )
            else:
                # Legacy only logs (no warnings.append) on long_tail failure
                for w in lt_resp.warnings:
                    if "long_tail" in w:
                        logger.warning(f"long_tail_detector 失败: {w}")

        # ── Week 4.5 / W5.5: 大众点评评论分析 (需 reviews 输入) ──
        if reviews:
            review_resp = self._review_analysis_handler.compute(_make_req(), context)
            if review_resp.status == SectionStatus.OK:
                review_section = review_resp.data
                report["sections"]["reviewAnalysis"] = review_section
                # Risk alerts (top 2) into executive summary
                for alert in review_section.get("riskAlerts", [])[:2]:
                    report["executiveSummary"].append(alert)
            else:
                # Legacy: appends "评论分析失败: ..." to warnings on regex failure
                for w in review_resp.warnings:
                    if "review_analyzer" in w:
                        report["warnings"].append(f"评论分析失败: {w}")

        # ── W5.4: 会员 RFM 分析 (需 members 或 POS 含 member_id) ──
        if members:
            rfm_resp = self._member_rfm_handler.compute(_make_req(), context)
            if rfm_resp.status == SectionStatus.OK:
                rfm_section = rfm_resp.data
                report["sections"]["memberRfm"] = rfm_section
                segment_counts = rfm_section.get("segmentCounts", {}) or {}
                segment_revenue = rfm_section.get("segmentRevenue", {}) or {}
                champions = segment_counts.get("Champions", 0)
                analyzed = rfm_section.get("analyzedMembers", 0)
                if champions > 0 and analyzed > 0:
                    champ_rev = segment_revenue.get("Champions", 0)
                    total_rev = sum(segment_revenue.values()) or 1
                    report["executiveSummary"].append(
                        f"🌟 Champions {champions} 人贡献 {champ_rev / total_rev * 100:.0f}% 营收"
                    )
            else:
                for w in rfm_resp.warnings:
                    if "member_rfm" in w:
                        report["warnings"].append(f"会员 RFM 失败: {w}")

        # ── W5.6: 同店同比 (需 POS 含时间 + 门店名称 + 实收额) ──
        if (
            pos_df is not None
            and datetime_col in pos_df.columns
            and revenue_col in pos_df.columns
            and "门店名称" in pos_df.columns
        ):
            temporal_resp = self._temporal_handler.compute(_make_req(), context)
            if temporal_resp.status == SectionStatus.OK:
                temporal_section = temporal_resp.data
                report["sections"]["temporalComparison"] = temporal_section
                if temporal_section.get("mode") != "insufficient":
                    msg = temporal_section.get("messageZh", "")
                    report["executiveSummary"].append(f"📊 {msg}")
            else:
                for w in temporal_resp.warnings:
                    if "temporal" in w:
                        report["warnings"].append(f"同店同比生成失败: {w}")

        # ── W6.4: 多店对比 (需 POS 含 门店名称 且 ≥2 店) ──
        store_col = "门店名称"
        if pos_df is not None and store_col in pos_df.columns:
            unique_stores = pos_df[store_col].dropna().nunique()
            if unique_stores >= 2:
                multi_resp = self._multi_store_handler.compute(_make_req(), context)
                if multi_resp.status == SectionStatus.OK:
                    multi_section = multi_resp.data
                    report["sections"]["multiStoreComparison"] = multi_section
                    insights = multi_section.get("insights", [])
                    if insights:
                        report["executiveSummary"].append(f"🏪 {insights[0]}")
                    for anomaly in multi_section.get("anomalies", [])[:2]:
                        if anomaly.get("severity") == "critical":
                            report["executiveSummary"].append(
                                f"🔴 {anomaly.get('messageZh', '')}"
                            )
                else:
                    for w in multi_resp.warnings:
                        if "multi_store" in w:
                            report["warnings"].append(f"多店对比分析失败: {w}")

        # ── W5.7: Layer A 月度校准历史 (需 db_session + ≥1 月数据) ──
        if self.calibration_reporter is not None:
            calibration_resp = self._calibration_handler.compute(_make_req(), context)
            if calibration_resp.status == SectionStatus.OK:
                history_section = calibration_resp.data
                report["sections"]["calibrationHistory"] = history_section
                anomalies = history_section.get("anomalies", []) or []
                critical_anomalies = [
                    a for a in anomalies if a.get("severity") == "critical"
                ]
                total_periods = history_section.get("totalPeriods", 0)
                if critical_anomalies:
                    report["executiveSummary"].append(
                        f"📅 BOM 校准历史: {len(critical_anomalies)} 个严重月份异常, "
                        "建议复盘"
                    )
                elif total_periods >= 6 and not anomalies:
                    report["executiveSummary"].append(
                        f"✅ BOM 校准历史: {total_periods} 月走势稳定"
                    )
            else:
                for w in calibration_resp.warnings:
                    if "calibration history" in w:
                        report["warnings"].append(f"月度校准历史报告失败: {w}")

        # ── Week 4.4: BOM Layer status (报告当前精度状态) ──
        # Always emitted (legacy line: report["sections"]["bomLayerStatus"] = ...).
        # DIRECT CALL: BomLayerStatusHandler.lazy-builds an inner V2 without
        # forwarding sku_form_manager / monthly_calibrator, so its
        # _build_bom_layer_status() always reports Layer 1 — silently masking
        # any Layer 2/3 precision the outer analyzer was configured for.
        # self._build_bom_layer_status() inspects the outer analyzer's state
        # correctly. See TODO(P2): refactor handlers to accept state via context.
        report["sections"]["bomLayerStatus"] = self._build_bom_layer_status()

        traffic_resp = self._advanced_traffic_persona_handler.compute(
            _make_req(),
            context,
        )
        if traffic_resp.status == SectionStatus.OK:
            traffic_section = traffic_resp.data
            report["sections"]["advancedTrafficPersona"] = traffic_section
            headline = traffic_section.get("analysis", {}).get("headline")
            if headline:
                report["executiveSummary"].append(f"高级客流画像: {headline}")
        else:
            for w in traffic_resp.warnings:
                report["warnings"].append(f"高级客流画像生成失败: {w}")

        # ─── 总结统计 ───
        report["summary"] = {
            "sectionsGenerated": list(report["sections"].keys()),
            "totalDiagnoses": len(report["sections"].get("diagnostics", [])),
            "totalAlerts": len(report["sections"].get("benchmarkAlerts", [])),
            "criticalIssues": sum(
                1 for d in report["sections"].get("diagnostics", [])
                if d.get("severity") == "critical"
            ),
            "redAlerts": sum(
                1 for a in report["sections"].get("benchmarkAlerts", [])
                if a.get("severity") == "red"
            ),
        }

        return report

    # ── Section 1: 命名归一 ───────────────────────────

    def _normalize_menu(self, pos_df, product_col: str) -> dict:
        """对 POS DataFrame 应用 menu_normalizer

        Returns:
            { 原始 unique SKU 数, 归一后 unique 数, 减少 % }
        """
        original_count = pos_df[product_col].nunique()

        # 应用已确认的别名 (从 restaurant_dish_alias 表读)
        pos_df = self.menu_normalizer.apply(pos_df, name_column=product_col)
        normalized_count = pos_df[product_col].nunique()

        reduction = original_count - normalized_count
        reduction_pct = reduction / original_count * 100 if original_count > 0 else 0

        return {
            "originalUniqueCount": int(original_count),
            "normalizedUniqueCount": int(normalized_count),
            "reduction": int(reduction),
            "reductionPct": round(reduction_pct, 2),
            "appliedAliasTable": "restaurant_dish_alias",
            "note": (
                f"已根据 dish_alias 表归并 {reduction} 个变体. "
                f"如需更多归一, 请到审核 UI 处理 propose_merges 提议."
            ),
        }

    # ── Section 2: 渠道毛利率 ─────────────────────────

    def _compute_channel_margin(
        self,
        pos_df,
        order_method_col: str,
        revenue_col: str,
        store_id: Optional[str],
        period: str,
        venue_list: Optional[list[str]] = None,
    ) -> dict:
        """渠道毛利率 (改进 6 真名版)"""
        report = self.channel_margin_calc.calculate(
            df=pos_df,
            order_method_col=order_method_col,
            revenue_col=revenue_col,
            store_id=store_id,
            period=period,
            venue_list=venue_list,
        )
        return report.to_dict()

    # ── Section 3: 财务诊断 ───────────────────────────

    def _extract_financial_metrics(
        self, financial_data: dict
    ) -> FinancialMetrics:
        """从 financial_data 提取 + 计算所有 metrics

        Args:
            financial_data: {
                "current": {revenue, food_cost, labor_cost, rent, net_profit, ...},
                "previous": {同上},  # 可选
                ...
            }
        """
        current = financial_data.get("current") or financial_data
        previous = financial_data.get("previous")

        revenue = float(current.get("revenue", 0))
        food_cost = self._safe_float(current.get("food_cost"))
        labor_cost = self._safe_float(current.get("labor_cost"))
        rent = self._safe_float(current.get("rent"))
        other_cost = self._safe_float(current.get("other_cost"))
        net_profit = self._safe_float(current.get("net_profit"))

        # P3.5B F1: Apply MarginSpec boundary decisions
        if food_cost is not None:
            if self.margin_spec.include_staff_meal_in_cogs:
                staff_meal = self._safe_float(current.get("staff_meal_cost"))
                if staff_meal is not None:
                    food_cost = food_cost + staff_meal
            if self.margin_spec.include_gas_in_cogs:
                gas_cost = self._safe_float(current.get("gas_cost"))
                if gas_cost is not None:
                    food_cost = food_cost + gas_cost

        # 比率
        food_cost_ratio = food_cost / revenue * 100 if food_cost and revenue > 0 else None
        labor_cost_ratio = labor_cost / revenue * 100 if labor_cost and revenue > 0 else None
        rent_ratio = rent / revenue * 100 if rent and revenue > 0 else None
        net_margin = net_profit / revenue * 100 if net_profit is not None and revenue > 0 else None

        metrics = FinancialMetrics(
            revenue=revenue,
            food_cost=food_cost,
            labor_cost=labor_cost,
            rent=rent,
            other_cost=other_cost,
            net_profit=net_profit,
            food_cost_ratio=food_cost_ratio,
            labor_cost_ratio=labor_cost_ratio,
            rent_ratio=rent_ratio,
            restaurant_net_margin=net_margin,
        )

        # P3.5B F2: dual margin (折前 + 折后)
        gross_revenue = self._safe_float(current.get("gross_revenue"))
        if gross_revenue is None or gross_revenue <= 0:
            gross_revenue = revenue  # fallback: 折前 == 折后 when no discount data
        metrics.gross_revenue = gross_revenue

        if food_cost is not None:
            if revenue > 0:
                metrics.gross_margin_folded = (revenue - food_cost) / revenue * 100
            if gross_revenue > 0:
                metrics.gross_margin_unfolded = (gross_revenue - food_cost) / gross_revenue * 100

        # cost_rigidity (需要 previous)
        if previous:
            prev_revenue = self._safe_float(previous.get("revenue"))
            prev_labor = self._safe_float(previous.get("labor_cost"))
            prev_food = self._safe_float(previous.get("food_cost"))

            if prev_revenue and prev_revenue > 0 and revenue > 0:
                rev_change = (revenue - prev_revenue) / prev_revenue
                metrics.revenue_change_pct = rev_change

                if prev_labor and prev_labor > 0 and labor_cost is not None:
                    labor_change = (labor_cost - prev_labor) / prev_labor
                    metrics.labor_cost_change_pct = labor_change

                    # cost_rigidity = labor_change / revenue_change
                    # 健康 = 0.85+ (人工跟随营收变化)
                    # 关键: 只在营收下滑时计算 (营收上升时弹性概念不适用)
                    if rev_change < -0.05:  # 营收下滑 5%+ 才计算
                        if rev_change != 0:
                            metrics.cost_rigidity = labor_change / rev_change
                        else:
                            metrics.cost_rigidity = None

                if prev_food and prev_food > 0 and food_cost is not None:
                    metrics.food_cost_change_pct = (food_cost - prev_food) / prev_food

        # ── Phase 1 #60: 食材损耗率 + 客单价达标率 ──
        # GUARD: 源数据缺失时不计算, 不写入 metric_dict (引擎跳过未知 key, 无错误).
        # ingredient_waste_rate 以百分比 (0-100) 计算, 与 benchmarks waste_loss_rate
        # 单位及 food_cost_ratio 的 *100 约定一致.
        wastage_cost = self._safe_float(current.get("wastage_cost"))
        food_cost_purchase = self._safe_float(current.get("food_cost_purchase"))
        if wastage_cost is not None and food_cost_purchase is not None:
            denom = food_cost_purchase + wastage_cost
            if denom > 0:
                metrics.ingredient_waste_rate = wastage_cost / denom * 100

        # avg_ticket_vs_target: target 来自 DynamicConfigResolver (config → benchmark median).
        actual_avg_ticket = self._safe_float(current.get("actual_avg_ticket"))
        if actual_avg_ticket is not None and actual_avg_ticket > 0:
            metrics.actual_avg_ticket = actual_avg_ticket
            target = self._resolve_target_avg_ticket()
            if target is not None and target > 0:
                metrics.target_avg_ticket = target
                metrics.avg_ticket_vs_target = actual_avg_ticket / target - 1.0

        # ── Phase 2 #60: 菜品毛利率 + 配方覆盖率 (依赖 #57 BOM 成本卡) ──
        # 数据来源: financial_data["product_costs"] = [{food_cost, revenue, qty,
        # has_price_data}, ...], 由调用方从 agg_restaurant_product_cost (逐菜 food_cost
        # + has_price_data) join POS 营收得到. GUARD: 有价菜 < 3 道时两个指标都不计算
        # (引擎跳过未知 key → 诚实不触发, 而非编造健康值).
        self._compute_bom_dish_metrics(metrics, financial_data.get("product_costs"))

        return metrics

    # Minimum priced dishes required before BOM-derived metrics fire. Below this
    # the aggregate is too thin to be trustworthy → honest no-fire (skip key).
    _MIN_PRICED_DISHES_FOR_DIAGNOSIS = 3

    def _compute_bom_dish_metrics(
        self, metrics: FinancialMetrics, product_costs: Optional[list]
    ) -> None:
        """Phase 2 #60: compute gross_margin_per_dish + recipe_coverage_rate from a
        dish-level cost list (#57 agg_restaurant_product_cost rows joined with POS
        revenue).

        Each dish dict: {food_cost, revenue, qty, has_price_data}.
        - recipe_coverage_rate = priced dishes / total active dishes
        - gross_margin_per_dish = (Σ priced revenue - Σ priced food_cost) / Σ priced revenue
          (only priced dishes — unpriced dishes have no real cost so would inflate margin)

        GUARD: skip BOTH metrics (leave None → key absent in metric_dict) when fewer
        than _MIN_PRICED_DISHES_FOR_DIAGNOSIS dishes carry has_price_data. The engine
        skips unknown keys, so this is an honest no-fire, not a fabricated reading.
        """
        if not product_costs:
            return

        active_dishes = [d for d in product_costs if isinstance(d, dict)]
        active_count = len(active_dishes)
        if active_count == 0:
            return

        priced = [d for d in active_dishes if d.get("has_price_data")]
        priced_count = len(priced)

        # Always surface the raw counts for UI transparency even when the GUARD
        # suppresses the diagnostic metrics (so the customer sees "2/8 dishes
        # priced — need ≥3 to diagnose" rather than nothing).
        metrics.active_dish_count = active_count
        metrics.priced_dish_count = priced_count

        if priced_count < self._MIN_PRICED_DISHES_FOR_DIAGNOSIS:
            # Honest no-fire: too few priced dishes to trust the aggregate.
            return

        # recipe_coverage_rate (0-1): priced / active.
        metrics.recipe_coverage_rate = priced_count / active_count

        # gross_margin_per_dish (0-1): aggregate over priced dishes only, weighted
        # by qty so high-volume dishes dominate (mirrors resolve_gross_margin in the
        # gold router: revenue is total amount, cost is unit food_cost × qty).
        total_revenue = 0.0
        total_cost = 0.0
        for d in priced:
            revenue = self._safe_float(d.get("revenue")) or 0.0
            food_cost = self._safe_float(d.get("food_cost")) or 0.0
            qty = self._safe_float(d.get("qty"))
            qty = qty if qty is not None else 1.0
            total_revenue += revenue
            total_cost += food_cost * qty
        if total_revenue > 0:
            metrics.gross_margin_per_dish = (total_revenue - total_cost) / total_revenue

    def _resolve_target_avg_ticket(self) -> Optional[float]:
        """解析目标客单价: config override → 子行业 benchmark average_ticket 中位数.

        优先 DynamicConfigResolver 的 restaurant.avg_ticket_target 配置 (门店/工厂级),
        未配置时回退到 diagnostics_engine 已加载的子行业 benchmark average_ticket median.
        Phase 1 #60.
        """
        benchmark_median = self._benchmark_avg_ticket_median()
        try:
            cfg = self.config_resolver.resolve(
                "restaurant.avg_ticket_target",
                yaml_fallback=benchmark_median,
            )
            target = self._safe_float(cfg.value)
            if target is not None and target > 0:
                return target
        except Exception as e:  # noqa: BLE001 — config 解析失败回退 benchmark
            logger.debug(f"avg_ticket_target 配置解析失败, 回退 benchmark: {e}")
        return benchmark_median

    def _benchmark_avg_ticket_median(self) -> Optional[float]:
        """读取子行业 benchmark average_ticket 中位数 (diagnostics_engine 已合并加载)."""
        bm = (
            self.diagnostics_engine._benchmarks.get("metrics", {})
            .get("average_ticket", {})
        )
        return self._safe_float(bm.get("median"))

    def _diagnostics_metric_dict(self, metrics: FinancialMetrics) -> dict[str, float]:
        """Build the snake_case ratio dict that DiagnosticsEngine /
        BenchmarkAlertEngine consume.

        This is the canonical shape passed via context["financial_metrics"]
        to the section handlers in the orchestrator. Mirrors the legacy
        _run_diagnostics() / _run_benchmark_alerts() metric_dict construction.
        """
        metric_dict: dict[str, float] = {}
        if metrics.food_cost_ratio is not None:
            metric_dict["food_cost_ratio"] = metrics.food_cost_ratio
        if metrics.labor_cost_ratio is not None:
            metric_dict["labor_cost_ratio"] = metrics.labor_cost_ratio
        if metrics.rent_ratio is not None:
            metric_dict["rent_ratio"] = metrics.rent_ratio
        if metrics.restaurant_net_margin is not None:
            metric_dict["restaurant_net_margin"] = metrics.restaurant_net_margin
        if metrics.cost_rigidity is not None:
            metric_dict["cost_rigidity"] = metrics.cost_rigidity
        # Phase 1 #60: only add when source data present (engine skips unknown keys).
        if metrics.ingredient_waste_rate is not None:
            metric_dict["ingredient_waste_rate"] = metrics.ingredient_waste_rate
        if metrics.avg_ticket_vs_target is not None:
            metric_dict["avg_ticket_vs_target"] = metrics.avg_ticket_vs_target
        # Phase 2 #60: BOM-dependent. None when GUARD suppresses (<3 priced dishes).
        if metrics.gross_margin_per_dish is not None:
            metric_dict["gross_margin_per_dish"] = metrics.gross_margin_per_dish
        if metrics.recipe_coverage_rate is not None:
            metric_dict["recipe_coverage_rate"] = metrics.recipe_coverage_rate
        return metric_dict

    def _run_diagnostics(self, metrics: FinancialMetrics) -> list:
        """跑 DiagnosticsEngine (legacy helper, kept for backwards compat /
        ad-hoc reuse outside the orchestrator). The orchestrator now invokes
        DiagnosticsHandler directly.
        """
        return self.diagnostics_engine.run(self._diagnostics_metric_dict(metrics))

    def _run_benchmark_alerts(
        self,
        metrics: FinancialMetrics,
        store_name: str,
        monthly_revenue: float,
    ) -> list:
        """跑 BenchmarkAlertEngine"""
        metric_dict: dict[str, float] = {}

        if metrics.food_cost_ratio is not None:
            metric_dict["food_cost_ratio"] = metrics.food_cost_ratio
        if metrics.labor_cost_ratio is not None:
            metric_dict["labor_cost_ratio"] = metrics.labor_cost_ratio
        if metrics.rent_ratio is not None:
            metric_dict["rent_ratio"] = metrics.rent_ratio
        if metrics.restaurant_net_margin is not None:
            metric_dict["restaurant_net_margin"] = metrics.restaurant_net_margin

        return self.benchmark_alert_engine.alert_for_store(
            store_name=store_name,
            metrics=metric_dict,
            monthly_revenue=monthly_revenue,
        )

    @staticmethod
    def _safe_float(v: Any) -> Optional[float]:
        """安全转换为 float, None/空字符串/异常都返回 None"""
        if v is None or v == "":
            return None
        try:
            return float(v)
        except (ValueError, TypeError):
            return None

    # ── Week 4 新增: helper 方法 ────────────────────────

    def _build_menu_items_for_long_tail(
        self,
        pos_df,
        product_col: str,
        quantity_col: str,
        revenue_col: str,
    ) -> list[dict]:
        """Week 4.3b: 把 POS DF 聚合为 menu_items list 供 LongTailSkuDetector"""

        if quantity_col not in pos_df.columns:
            # 没有 quantity 列, 用 count 代替
            grouped = pos_df.groupby(product_col).agg(
                quantity=(revenue_col, "count"),
                revenue=(revenue_col, "sum"),
            ).reset_index()
        else:
            grouped = pos_df.groupby(product_col).agg(
                quantity=(quantity_col, "sum"),
                revenue=(revenue_col, "sum"),
            ).reset_index()

        items: list[dict] = []
        for _, row in grouped.iterrows():
            name = str(row[product_col])
            qty = float(row.get("quantity", 0) or 0)
            rev = float(row.get("revenue", 0) or 0)
            if qty <= 0 or rev <= 0:
                continue
            unit_price = rev / qty if qty > 0 else 0
            items.append({
                "name": name,
                "quantity": qty,
                "revenue": rev,
                "unitProfit": unit_price * 0.5,  # 粗估 50% 毛利 (长尾检测不需精确)
            })
        return items

    def _build_bom_layer_status(self) -> dict:
        """Week 4.4: 报告当前 BOM 精度层级状态 (用于前端展示)"""
        layer2_sku_count = 0
        layer3_period_count = 0

        if self.sku_form_manager:
            layer2_sku_count = self.sku_form_manager.count(self.factory_id)
        if self.monthly_calibrator:
            layer3_period_count = self.monthly_calibrator.count(self.factory_id)

        current_layer = "Layer 1"
        current_accuracy = 15.0
        if layer3_period_count > 0:
            current_layer = "Layer 3"
            current_accuracy = 5.0 if layer3_period_count >= 3 else 8.0
        elif layer2_sku_count > 0:
            current_layer = "Layer 2"
            current_accuracy = 8.0

        return {
            "currentLayer": current_layer,
            "currentAccuracyPp": current_accuracy,
            "layer2SkuCount": layer2_sku_count,
            "layer3PeriodCount": layer3_period_count,
            "upgradeHint": (
                "上传 TOP 20 SKU 主料清单 → Layer 2 (±8%)"
                if layer2_sku_count == 0
                else "上传 3 月采购汇总 → Layer 3 (±5%)"
                if layer3_period_count < 3
                else "已达最优 Layer 3 精度 (±5%)"
            ),
        }
