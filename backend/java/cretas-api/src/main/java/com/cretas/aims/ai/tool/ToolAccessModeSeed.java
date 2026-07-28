package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;

import java.util.Map;

/**
 * 迁移期种子表: 尚未完成<b>类内</b> {@code getAccessMode()} 回填的工具声明 (spec §8.2)。
 *
 * <h2>为什么存在这张表</h2>
 * 存量工具里有 103 个位于 {@code ai/tool/impl/restaurant/}, 而餐饮包在本次回填期间有并行
 * worktree 在改 ({@code feat/restaurant-monthly-report} / {@code feat/restaurant-session-summary} 等),
 * 属于本任务的禁改范围, 不能往这些文件里插方法。
 *
 * <p>但"不碰"不等于"可以不声明": {@link ToolExecutor#getAccessMode()} 的默认值是
 * {@link AccessMode#WRITE} (fail-closed), 漏声明的餐饮<b>只读</b>工具会被当成写工具 ——
 * 咨询 tab 直接拦掉、每次查询都要写确认, 那是把一个安全加固变成餐饮线上事故。所以这里按工具名
 * 把这 103 个 (91 READ / 12 WRITE) 全部补齐, 让声明覆盖率达到 100%%。
 *
 * <h2>这不是永久形态</h2>
 * 优先级是 <b>类内声明 &gt; 本表 &gt; 默认 WRITE</b>, 所以餐饮 session 后续在类里补
 * {@code getAccessMode()} 时无需先删本表条目 —— 类内声明自动生效, 本表条目退化为无害冗余, 之后清理即可。
 * {@code ToolAccessModeDeclarationTest} 盯住两件事: 覆盖率必须 100%%, 且本表只允许出现餐饮包的工具
 * (新工具一律在类里声明, 不许再往这张表加)。
 */
public final class ToolAccessModeSeed {

    private ToolAccessModeSeed() {
    }

    /** 餐饮包待回填的工具声明; 注释为相对 {@code ai/tool/impl/} 的路径。 */
    public static final Map<String, AccessMode> PENDING_IN_CLASS_BACKFILL = Map.ofEntries(
            Map.entry("restaurant_arap_payable_query", AccessMode.READ),         // restaurant/RestaurantArApPayableTool.java
            Map.entry("restaurant_at_risk_guest_query", AccessMode.READ),        // restaurant/RestaurantAtRiskGuestQueryTool.java
            Map.entry("restaurant_avg_ticket", AccessMode.READ),                 // restaurant/RestaurantAvgTicketTool.java
            Map.entry("restaurant_benchmark_alert", AccessMode.READ),            // restaurant/diagnostic/RestaurantBenchmarkAlertTool.java
            Map.entry("restaurant_bestseller_query", AccessMode.READ),           // restaurant/RestaurantBestsellerQueryTool.java
            Map.entry("restaurant_bom_layer_status", AccessMode.READ),           // restaurant/diagnostic/RestaurantBomLayerStatusTool.java
            Map.entry("restaurant_bom_variance", AccessMode.READ),               // restaurant/diagnostic/BomVarianceTool.java
            Map.entry("restaurant_calibration_history", AccessMode.READ),        // restaurant/diagnostic/RestaurantCalibrationHistoryTool.java
            Map.entry("restaurant_channel_margin", AccessMode.READ),             // restaurant/diagnostic/RestaurantChannelMarginTool.java
            Map.entry("restaurant_combo_split", AccessMode.WRITE),               // restaurant/diagnostic/ComboSplitTool.java
            Map.entry("restaurant_comprehensive_synthesis_gold", AccessMode.READ), // restaurant/gold/RestaurantComprehensiveSynthesisGoldTool.java
            Map.entry("restaurant_cost_rigidity_analysis", AccessMode.READ),     // restaurant/diagnostic/RestaurantCostRigidityAnalysisTool.java
            Map.entry("restaurant_cross_chain_benchmark", AccessMode.READ),      // restaurant/diagnostic/RestaurantCrossChainBenchmarkTool.java
            Map.entry("restaurant_daily_reconciliation", AccessMode.READ),       // restaurant/diagnostic/DailyReconciliationTool.java
            Map.entry("restaurant_daily_revenue", AccessMode.READ),              // restaurant/RestaurantDailyRevenueTool.java
            Map.entry("restaurant_department_pnl", AccessMode.READ),             // restaurant/diagnostic/RestaurantDepartmentPnlTool.java
            Map.entry("restaurant_dining_heatmap", AccessMode.READ),             // restaurant/diagnostic/RestaurantDiningHeatmapTool.java
            Map.entry("restaurant_discount_usage_gold", AccessMode.READ),        // restaurant/gold/RestaurantDiscountUsageGoldTool.java
            Map.entry("restaurant_dish_bestseller_gold", AccessMode.READ),       // restaurant/gold/RestaurantDishBestsellerGoldTool.java
            Map.entry("restaurant_dish_cost_analysis", AccessMode.READ),         // restaurant/RestaurantDishCostAnalysisTool.java
            Map.entry("restaurant_dish_cost_query", AccessMode.READ),            // restaurant/RestaurantDishCostQueryTool.java
            Map.entry("restaurant_dish_create", AccessMode.WRITE),               // restaurant/RestaurantDishCreateTool.java
            Map.entry("restaurant_dish_delete", AccessMode.WRITE),               // restaurant/RestaurantDishDeleteTool.java
            Map.entry("restaurant_dish_list", AccessMode.READ),                  // restaurant/RestaurantDishListTool.java
            Map.entry("restaurant_dish_product_sales_ranking", AccessMode.READ), // restaurant/RestaurantDishProductSalesRankingTool.java
            Map.entry("restaurant_dish_sales_ranking", AccessMode.READ),         // restaurant/RestaurantDishSalesRankingTool.java
            Map.entry("restaurant_dish_slowseller_gold", AccessMode.READ),       // restaurant/gold/RestaurantDishSlowsellerGoldTool.java
            Map.entry("restaurant_dish_update", AccessMode.WRITE),               // restaurant/RestaurantDishUpdateTool.java
            Map.entry("restaurant_economics_analysis", AccessMode.READ),         // restaurant/RestaurantEconomicsAnalysisTool.java
            Map.entry("restaurant_forecast", AccessMode.READ),                   // restaurant/diagnostic/RestaurantForecastTool.java
            Map.entry("restaurant_ingredient_cost_trend", AccessMode.READ),      // restaurant/RestaurantIngredientCostTrendTool.java
            Map.entry("restaurant_ingredient_expiry_alert", AccessMode.READ),    // restaurant/RestaurantIngredientExpiryAlertTool.java
            Map.entry("restaurant_ingredient_low_stock", AccessMode.READ),       // restaurant/RestaurantIngredientLowStockTool.java
            Map.entry("restaurant_ingredient_stock", AccessMode.READ),           // restaurant/RestaurantIngredientStockTool.java
            Map.entry("restaurant_labor_productivity", AccessMode.READ),         // restaurant/diagnostic/LaborProductivityTool.java
            Map.entry("restaurant_long_tail_sku", AccessMode.READ),              // restaurant/diagnostic/RestaurantLongTailSkuTool.java
            Map.entry("restaurant_margin_analysis", AccessMode.READ),            // restaurant/RestaurantMarginAnalysisTool.java
            Map.entry("restaurant_member_rfm", AccessMode.READ),                 // restaurant/diagnostic/RestaurantMemberRfmTool.java
            Map.entry("restaurant_menu_engineering", AccessMode.READ),           // restaurant/diagnostic/RestaurantMenuEngineeringTool.java
            Map.entry("restaurant_menu_normalization", AccessMode.READ),         // restaurant/diagnostic/RestaurantMenuNormalizationTool.java
            Map.entry("restaurant_monthly_ppt_export", AccessMode.WRITE),        // restaurant/diagnostic/RestaurantMonthlyPptExportTool.java
            Map.entry("restaurant_multi_store_comparison", AccessMode.READ),     // restaurant/diagnostic/RestaurantMultiStoreComparisonTool.java
            Map.entry("restaurant_ops_gold_analysis", AccessMode.READ),          // restaurant/gold/RestaurantOpsGoldAnalysisTool.java
            Map.entry("restaurant_order_statistics", AccessMode.READ),           // restaurant/RestaurantOrderStatisticsTool.java
            Map.entry("restaurant_order_type_mix_gold", AccessMode.READ),        // restaurant/gold/RestaurantOrderTypeMixGoldTool.java
            Map.entry("restaurant_owner_action_advisor", AccessMode.READ),       // restaurant/RestaurantOwnerActionAdvisorTool.java
            Map.entry("restaurant_peak_hours_analysis", AccessMode.READ),        // restaurant/RestaurantPeakHoursAnalysisTool.java
            Map.entry("restaurant_peak_month_gold", AccessMode.READ),            // restaurant/gold/RestaurantPeakMonthGoldTool.java
            Map.entry("restaurant_performance_eval", AccessMode.READ),           // restaurant/diagnostic/PerformanceEvalTool.java
            Map.entry("restaurant_performance_rule_manage", AccessMode.WRITE),   // restaurant/PerformanceRuleManageTool.java
            Map.entry("restaurant_piecework_calc", AccessMode.READ),             // restaurant/diagnostic/PieceworkCalcTool.java
            Map.entry("restaurant_piecework_config", AccessMode.WRITE),          // restaurant/PieceworkConfigTool.java
            Map.entry("restaurant_procurement_create", AccessMode.WRITE),        // restaurant/RestaurantProcurementCreateTool.java
            Map.entry("restaurant_procurement_forecast", AccessMode.READ),       // restaurant/diagnostic/ProcurementForecastTool.java
            Map.entry("restaurant_procurement_suggestion", AccessMode.READ),     // restaurant/RestaurantProcurementSuggestionTool.java
            Map.entry("restaurant_rep_commission_query", AccessMode.READ),       // restaurant/RestaurantRepCommissionQueryTool.java
            Map.entry("restaurant_return_anomaly", AccessMode.READ),             // restaurant/diagnostic/ReturnAnomalyTool.java
            Map.entry("restaurant_return_rate", AccessMode.READ),                // restaurant/RestaurantReturnRateTool.java
            Map.entry("restaurant_revenue_trend", AccessMode.READ),              // restaurant/RestaurantRevenueTrendTool.java
            Map.entry("restaurant_revenue_trend_gold", AccessMode.READ),         // restaurant/gold/RestaurantRevenueTrendGoldTool.java
            Map.entry("restaurant_review_analysis", AccessMode.READ),            // restaurant/diagnostic/RestaurantReviewAnalysisTool.java
            Map.entry("restaurant_review_city", AccessMode.READ),                // restaurant/gold/review/RestaurantReviewCityTool.java
            Map.entry("restaurant_review_competitive", AccessMode.READ),         // restaurant/diagnostic/ReviewCompetitiveTool.java
            Map.entry("restaurant_review_complaint", AccessMode.READ),           // restaurant/gold/review/RestaurantReviewComplaintTool.java
            Map.entry("restaurant_review_dish_issue", AccessMode.READ),          // restaurant/gold/review/RestaurantReviewDishTool.java
            Map.entry("restaurant_review_env_score", AccessMode.READ),           // restaurant/gold/review/RestaurantReviewEnvScoreTool.java
            Map.entry("restaurant_review_good_tags", AccessMode.READ),           // restaurant/gold/review/RestaurantReviewGoodTagsTool.java
            Map.entry("restaurant_review_platform", AccessMode.READ),            // restaurant/gold/review/RestaurantReviewPlatformTool.java
            Map.entry("restaurant_review_reply_rate", AccessMode.READ),          // restaurant/gold/review/RestaurantReviewReplyRateTool.java
            Map.entry("restaurant_review_score_tags", AccessMode.READ),          // restaurant/gold/review/RestaurantReviewScoreTagsTool.java
            Map.entry("restaurant_review_service_score", AccessMode.READ),       // restaurant/gold/review/RestaurantReviewServiceScoreTool.java
            Map.entry("restaurant_review_store_rank", AccessMode.READ),          // restaurant/gold/review/RestaurantReviewStoreRankTool.java
            Map.entry("restaurant_review_summary", AccessMode.READ),             // restaurant/gold/review/RestaurantReviewSummaryTool.java
            Map.entry("restaurant_review_time_period", AccessMode.READ),         // restaurant/gold/review/RestaurantReviewTimePeriodTool.java
            Map.entry("restaurant_review_trend", AccessMode.READ),               // restaurant/gold/review/RestaurantReviewTrendTool.java
            Map.entry("restaurant_review_vip", AccessMode.READ),                 // restaurant/gold/review/RestaurantReviewVipTool.java
            Map.entry("restaurant_review_vip_tags", AccessMode.READ),            // restaurant/gold/review/RestaurantReviewVipTagsTool.java
            Map.entry("restaurant_sales_plan_create", AccessMode.WRITE),         // restaurant/SalesPlanCreateTool.java
            Map.entry("restaurant_sales_plan_track", AccessMode.READ),           // restaurant/SalesPlanTrackTool.java
            Map.entry("restaurant_seat_config_manage", AccessMode.WRITE),        // restaurant/SeatConfigManageTool.java
            Map.entry("restaurant_seat_occupancy", AccessMode.READ),             // restaurant/diagnostic/SeatOccupancyTool.java
            Map.entry("restaurant_shift_analysis", AccessMode.READ),             // restaurant/diagnostic/ShiftAnalysisTool.java
            Map.entry("restaurant_shift_create", AccessMode.WRITE),              // restaurant/ShiftScheduleCreateTool.java
            Map.entry("restaurant_shrinkage_analysis", AccessMode.READ),         // restaurant/diagnostic/RestaurantShrinkageAnalysisTool.java
            Map.entry("restaurant_slow_seller_query", AccessMode.READ),          // restaurant/RestaurantSlowSellerQueryTool.java
            Map.entry("restaurant_smart_reorder", AccessMode.READ),              // restaurant/diagnostic/SmartReorderTool.java
            Map.entry("restaurant_staff_ranking_gold", AccessMode.READ),         // restaurant/gold/RestaurantStaffRankingGoldTool.java
            Map.entry("restaurant_store_kpi_dashboard", AccessMode.READ),        // restaurant/diagnostic/StoreKpiDashboardTool.java
            Map.entry("restaurant_store_pnl_one_pager", AccessMode.READ),        // restaurant/diagnostic/RestaurantStorePnlOnePagerTool.java
            Map.entry("restaurant_store_revenue_rank_gold", AccessMode.READ),    // restaurant/gold/RestaurantStoreRevenueRankGoldTool.java
            Map.entry("restaurant_stored_value", AccessMode.READ),               // restaurant/diagnostic/RestaurantStoredValueTool.java
            Map.entry("restaurant_table_turnover", AccessMode.READ),             // restaurant/RestaurantTableTurnoverTool.java
            Map.entry("restaurant_temporal_comparison", AccessMode.READ),        // restaurant/diagnostic/RestaurantTemporalComparisonTool.java
            Map.entry("restaurant_value_summary", AccessMode.READ),              // restaurant/diagnostic/RestaurantValueSummaryTool.java
            Map.entry("restaurant_vip_guest_query", AccessMode.READ),            // restaurant/RestaurantVipGuestQueryTool.java
            Map.entry("restaurant_voice_requisition", AccessMode.READ),          // restaurant/RestaurantVoiceRequisitionTool.java
            Map.entry("restaurant_wastage_anomaly", AccessMode.READ),            // restaurant/RestaurantWastageAnomalyTool.java
            Map.entry("restaurant_wastage_rate", AccessMode.READ),               // restaurant/RestaurantWastageRateTool.java
            Map.entry("restaurant_wastage_record", AccessMode.WRITE),            // restaurant/RestaurantWastageRecordTool.java
            Map.entry("restaurant_wastage_summary", AccessMode.READ),            // restaurant/RestaurantWastageSummaryTool.java
            Map.entry("restaurant_weekday_weekend_gold", AccessMode.READ),       // restaurant/gold/RestaurantWeekdayWeekendGoldTool.java
            Map.entry("revenue_report_generate", AccessMode.READ),               // restaurant/RevenueReportGenerateTool.java
            Map.entry("store_review_revenue", AccessMode.READ)                   // restaurant/gold/review/StoreReviewRevenueTool.java
    );
}
