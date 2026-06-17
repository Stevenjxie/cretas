package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.dto.report.KpiMetricsDTO;
import com.cretas.aims.dto.report.OeeReportDTO;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.dto.report.SalesProductProfitRowDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表统计控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/reports")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write", "procurement:price:view"})
@Tag(name = "报表统计管理")
public class ReportController {

    private final ReportService reportService;
    private final PriceMaskResolver priceMaskResolver;

    /**
     * 运营 Dashboard 端点的方法级权限集 (OR / hasAnyPermission).
     *
     * <p>类级 {@code @RequirePermission} 只放 finance/price 权限, 因为
     * ReportController 大部分端点是真财务报表 (成本/利润/应收应付/凭证). 但
     * 三个"运营 Dashboard"端点 (overview / production / quality) 给的是
     * <b>各操作角色首页用的运营统计</b> (今日产量/批次/在岗工人/库存预警/合格率
     * 等计数与率值), 不该被 finance/price 闸挡住 — 否则仓管员 / 生产工等操作
     * 角色首页 403, 静默显示 0/默认值 (假数据, RN E2E LIUSHANMEN 真工厂实测确认).
     *
     * <p>方法级注解 <b>覆盖</b>类级 (见 {@code PermissionInterceptor}:
     * {@code methodAnnotation != null ? methodAnnotation : classAnnotation}).
     * 放宽到运营角色 + 保留财务角色 (OR), 让所有操作角色首页 (每个角色都有
     * {@code dashboard:read}) 都能通, 财务角色仍能看完整成本字段.
     *
     * <p>金额脱敏: 这几个端点返回的 {@code Map<String, Object>} 里仍带少量成本
     * 字段 ({@code kpi.unitCost} / 顶层 {@code totalCost}), 对无
     * {@code procurement:price:view} 的角色必须脱敏 — 见
     * {@link #maskOperationalDashboardCosts}. 因为这些是 hand-built Map,
     * {@code PriceFieldResponseAdvice} 的 @PriceSensitive 反射 strip 走不到, 且
     * {@code unitCost}/{@code totalCost} 不在它的 PRICE_VALUE_KEYS / price-container
     * path 自动匹配里, 所以必须在 controller 层显式脱敏.
     *
     * <p>注意: {@code @RequirePermission} 注解的 value 必须是编译期常量数组字面量,
     * 不能引用一个 {@code static final String[]} 常量 (Java 注解约束). 所以下面这组
     * 权限在每个端点的注解里 <b>内联</b>书写, 这里仅以 JavaDoc 记录权威集合, 三处必须
     * 保持一致:
     * <pre>
     *   {"dashboard:read_write","dashboard:read",
     *    "warehouse:read_write","warehouse:read",
     *    "production:read_write","production:read",
     *    "inventory:read_write","inventory:read",
     *    "quality:read_write","quality:read",
     *    "finance:read_write","finance:read",
     *    "procurement:price:view"}
     * </pre>
     */

    /**
     * 运营 Dashboard Map 里需要脱敏的成本/金额语义 key (无 price 权限角色见不到).
     * 大小写不敏感后缀/包含匹配 — 见 {@link #maskOperationalDashboardCosts}.
     */
    private static final Set<String> OPERATIONAL_COST_KEYS = Set.of(
            "unitCost", "cost", "totalCost", "materialCost", "laborCost",
            "overheadCost", "amount", "totalAmount", "price", "unitPrice"
    );

    // ============================================================
    // Dashboard 统一入口 (委托 ProcessingService)
    // 这是 ReportController 作为报表/Dashboard 统一入口的实现
    // ProcessingController 的 dashboard 端点已标记为 @Deprecated
    // ============================================================

    /**
     * 获取生产概览 Dashboard (运营首页统计 — 操作角色首页调用).
     *
     * <p>方法级 {@code @RequirePermission} 覆盖类级 finance/price 闸, 放宽到运营
     * 角色 (warehouse / production / inventory / quality / dashboard) + 保留财务
     * 角色. 金额字段 ({@code kpi.unitCost}) 对无 price 权限角色脱敏.
     */
    @GetMapping("/dashboard/overview")
    @RequirePermission({"dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view"})
    @Operation(summary = "生产概览Dashboard", description = "获取生产概览数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getDashboardOverview(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "today") @Parameter(description = "时间周期: today, week, month") String period,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("获取生产概览Dashboard: factoryId={}, period={}", factoryId, period);
        Map<String, Object> overview = reportService.getDashboardOverview(factoryId, period);
        if (priceMaskResolver.shouldMaskOperationalCost(authorization)) {
            maskOperationalDashboardCosts(overview);
        }
        return ApiResponse.success(overview);
    }

    /**
     * 获取生产统计 Dashboard (运营统计 — 操作角色首页调用).
     *
     * <p>权限/脱敏说明同 {@link #getDashboardOverview}. 金额字段
     * (顶层 {@code totalCost}) 对无 price 权限角色脱敏.
     */
    @GetMapping("/dashboard/production")
    @RequirePermission({"dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view"})
    @Operation(summary = "生产统计Dashboard", description = "获取生产统计数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getProductionDashboard(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "today") @Parameter(description = "时间周期: today, week, month") String period,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("获取生产统计Dashboard: factoryId={}, period={}", factoryId, period);
        Map<String, Object> stats = reportService.getProductionDashboard(factoryId, period);
        if (priceMaskResolver.shouldMaskOperationalCost(authorization)) {
            maskOperationalDashboardCosts(stats);
        }
        return ApiResponse.success(stats);
    }

    /**
     * 获取质量 Dashboard (运营质量统计 — 操作角色首页调用).
     *
     * <p>权限说明同 {@link #getDashboardOverview}. 该端点不返回金额字段
     * (纯质量计数/合格率), 仍走脱敏 helper 作防御性兜底 (未来若加成本字段自动覆盖).
     */
    @GetMapping("/dashboard/quality")
    @RequirePermission({"dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view"})
    @Operation(summary = "质量Dashboard", description = "获取质量统计数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getQualityDashboard(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("获取质量Dashboard: factoryId={}", factoryId);
        Map<String, Object> dashboard = reportService.getQualityDashboard(factoryId);
        if (priceMaskResolver.shouldMaskOperationalCost(authorization)) {
            maskOperationalDashboardCosts(dashboard);
        }
        return ApiResponse.success(dashboard);
    }

    /**
     * 获取设备 Dashboard
     */
    @GetMapping("/dashboard/equipment")
    @Operation(summary = "设备Dashboard", description = "获取设备统计数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getEquipmentDashboard(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("获取设备Dashboard: factoryId={}", factoryId);
        return ApiResponse.success(reportService.getEquipmentDashboard(factoryId));
    }

    /**
     * 获取告警 Dashboard
     */
    @GetMapping("/dashboard/alerts")
    @Operation(summary = "告警Dashboard", description = "获取告警统计数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getAlertsDashboard(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "week") @Parameter(description = "时间周期: today, week, month") String period) {
        log.info("获取告警Dashboard: factoryId={}, period={}", factoryId, period);
        return ApiResponse.success(reportService.getAlertsDashboard(factoryId, period));
    }

    /**
     * 获取趋势分析 Dashboard (运营趋势 — 质检员/操作角色首页流程调用).
     *
     * <p>权限说明同 {@link #getDashboardOverview}: 质检员 (quality_inspector) 的
     * QITrendScreen 经 qualityInspectorApi 调本端点拿生产/质量趋势. 之前继承类级
     * finance/price 闸 → 质检员 403 → 趋势页空 (同一运营 Dashboard bug 类). 放宽到
     * 运营角色 + 保留财务角色.
     *
     * <p>金额脱敏: 返回的 {@code costTrend} 整列是成本数据 (每项 {@code value} +
     * {@code totalCost} 都是成本), 对无 price 权限角色由
     * {@link #maskOperationalDashboardCosts} 整列移除; production/quality 趋势保留.
     */
    @GetMapping("/dashboard/trends")
    @RequirePermission({"dashboard:read_write", "dashboard:read", "warehouse:read_write", "warehouse:read", "production:read_write", "production:read", "inventory:read_write", "inventory:read", "quality:read_write", "quality:read", "finance:read_write", "finance:read", "procurement:price:view"})
    @Operation(summary = "趋势分析Dashboard", description = "获取趋势分析数据 (委托ProcessingService)")
    public ApiResponse<Map<String, Object>> getTrendsDashboard(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "month") @Parameter(description = "时间周期: week, month, quarter, year") String period,
            @RequestParam(defaultValue = "production") @Parameter(description = "趋势类型: production, quality, equipment, cost") String metric,
            @RequestParam(defaultValue = "30") @Parameter(description = "趋势天数") Integer days,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("获取趋势分析Dashboard: factoryId={}, period={}, metric={}, days={}", factoryId, period, metric, days);
        Map<String, Object> trends = reportService.getTrendsDashboard(factoryId, period, metric, days);
        if (priceMaskResolver.shouldMaskOperationalCost(authorization)) {
            maskOperationalDashboardCosts(trends);
        }
        return ApiResponse.success(trends);
    }

    // ============================================================
    // 报表功能端点
    // ============================================================

    /**
     * 按产品统计生产数量
     */
    @GetMapping("/production-by-product")
    @Operation(summary = "按产品统计生产数量", description = "获取指定时间范围内各产品的生产统计数据，按产量降序排序")
    public ApiResponse<List<ProductionByProductDTO>> getProductionByProduct(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，格式yyyy-MM-dd，默认本周一") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，格式yyyy-MM-dd，默认今天") LocalDate endDate) {
        log.info("按产品统计生产数量: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        List<ProductionByProductDTO> result = reportService.getProductionByProduct(factoryId, startDate, endDate);
        return ApiResponse.success(result);
    }

    /**
     * 获取库存报表
     */
    @GetMapping("/inventory")
    @Operation(summary = "获取库存报表", description = "获取指定日期的库存报表")
    public ApiResponse<Map<String, Object>> getInventoryReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "报表日期") LocalDate date) {
        log.info("获取库存报表: factoryId={}, date={}", factoryId, date);
        LocalDate reportDate = date != null ? date : LocalDate.now();
        Map<String, Object> report = reportService.getInventoryReport(factoryId, reportDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取财务报表
     */
    @GetMapping("/finance")
    @Operation(summary = "获取财务报表", description = "获取指定日期范围的财务报表，默认近30天")
    public ApiResponse<Map<String, Object>> getFinanceReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，默认30天前") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，默认今天") LocalDate endDate) {
        // 默认值: 近30天
        LocalDate effectiveEndDate = endDate != null ? endDate : LocalDate.now();
        LocalDate effectiveStartDate = startDate != null ? startDate : effectiveEndDate.minusDays(30);
        log.info("获取财务报表: factoryId={}, startDate={}, endDate={}",
                factoryId, effectiveStartDate, effectiveEndDate);
        Map<String, Object> report = reportService.getFinanceReport(factoryId, effectiveStartDate, effectiveEndDate);
        return ApiResponse.success(report);
    }

    // quality 和 equipment 报表已移至 ProcessingController

    /**
     * 获取人员报表
     */
    @GetMapping("/personnel")
    @Operation(summary = "获取人员报表", description = "获取人员统计报表")
    public ApiResponse<Map<String, Object>> getPersonnelReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "报表日期") LocalDate date) {
        log.info("获取人员报表: factoryId={}, date={}", factoryId, date);
        LocalDate reportDate = date != null ? date : LocalDate.now();
        Map<String, Object> report = reportService.getPersonnelReport(factoryId, reportDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取销售报表
     */
    @GetMapping("/sales")
    @Operation(summary = "获取销售报表", description = "获取指定日期范围的销售报表，默认近30天")
    public ApiResponse<Map<String, Object>> getSalesReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，默认30天前") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，默认今天") LocalDate endDate) {
        // 默认值: 近30天
        LocalDate effectiveEndDate = endDate != null ? endDate : LocalDate.now();
        LocalDate effectiveStartDate = startDate != null ? startDate : effectiveEndDate.minusDays(30);
        log.info("获取销售报表: factoryId={}, startDate={}, endDate={}",
                factoryId, effectiveStartDate, effectiveEndDate);
        Map<String, Object> report = reportService.getSalesReport(factoryId, effectiveStartDate, effectiveEndDate);
        return ApiResponse.success(report);
    }

    // cost-analysis 已移至 AIController (通过 Python AI 服务计算)

    /**
     * 获取效率分析报表
     */
    @GetMapping("/efficiency-analysis")
    @Operation(summary = "获取效率分析报表", description = "分析生产效率指标")
    public ApiResponse<Map<String, Object>> getEfficiencyAnalysisReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.info("获取效率分析报表: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        Map<String, Object> report = reportService.getEfficiencyAnalysisReport(factoryId, startDate, endDate);
        return ApiResponse.success(report);
    }

    // trend-analysis 已移至 ProcessingController

    /**
     * 获取KPI指标
     */
    @GetMapping("/kpi")
    @Operation(summary = "获取KPI指标", description = "获取关键绩效指标")
    public ApiResponse<Map<String, Object>> getKPIMetrics(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "指标日期") LocalDate date) {
        log.info("获取KPI指标: factoryId={}, date={}", factoryId, date);
        LocalDate metricsDate = date != null ? date : LocalDate.now();
        Map<String, Object> metrics = reportService.getKPIMetrics(factoryId, metricsDate);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取周期对比报表
     */
    @GetMapping("/period-comparison")
    @Operation(summary = "获取周期对比报表", description = "对比两个时间周期的数据")
    public ApiResponse<Map<String, Object>> getPeriodComparisonReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间1开始日期") LocalDate period1Start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间1结束日期") LocalDate period1End,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间2开始日期") LocalDate period2Start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间2结束日期") LocalDate period2End) {
        log.info("获取周期对比报表: factoryId={}, period1={}-{}, period2={}-{}",
                factoryId, period1Start, period1End, period2Start, period2End);
        Map<String, Object> report = reportService.getPeriodComparisonReport(
                factoryId, period1Start, period1End, period2Start, period2End);
        return ApiResponse.success(report);
    }

    /**
     * 获取预测报表
     */
    @GetMapping("/forecast")
    @Operation(summary = "获取预测报表", description = "基于历史数据的预测分析")
    public ApiResponse<Map<String, Object>> getForecastReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "预测类型") String type,
            @RequestParam @Parameter(description = "预测天数") Integer days) {
        log.info("获取预测报表: factoryId={}, type={}, days={}", factoryId, type, days);
        Map<String, Object> report = reportService.getForecastReport(factoryId, type, days);
        return ApiResponse.success(report);
    }

    /**
     * 获取异常报告
     */
    @GetMapping("/anomalies")
    @Operation(summary = "获取异常报告", description = "检测并报告异常情况")
    public ApiResponse<Map<String, Object>> getAnomalyReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.info("获取异常报告: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusDays(30);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        Map<String, Object> report = reportService.getAnomalyReport(factoryId, start, end);
        return ApiResponse.success(report);
    }

    /**
     * 导出报表为Excel
     */
    @GetMapping("/export/excel")
    @Operation(summary = "导出Excel报表",
            description = "导出指定类型的报表为Excel文件. RBAC: maskPrice wired through (PR P0-C sweep), "
                    + "当前 stub 返 'not yet implemented' 不漏价格; 当 stub 替换为真实 export 时, 财务/成本/销售 类金额列 mask 为 '—'.")
    public void exportExcelReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "报表类型") String reportType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate,
            @RequestHeader("Authorization") String authorization,
            HttpServletResponse response) {
        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mirror PR #450 pattern.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出Excel报表: factoryId={}, type={}, startDate={}, endDate={}, maskPrice={}",
                factoryId, reportType, startDate, endDate, maskPrice);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                String.format("attachment; filename=\"report_%s_%s.xlsx\"", reportType, LocalDate.now()));

        reportService.exportReportAsExcel(factoryId, reportType, startDate, endDate, maskPrice, response);
    }

    /**
     * 导出报表为PDF
     */
    @GetMapping("/export/pdf")
    @Operation(summary = "导出PDF报表",
            description = "导出指定类型的报表为PDF文件. RBAC: see exportExcelReport above.")
    public void exportPdfReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "报表类型") String reportType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate,
            @RequestHeader("Authorization") String authorization,
            HttpServletResponse response) {
        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mirror PR #450 pattern.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出PDF报表: factoryId={}, type={}, startDate={}, endDate={}, maskPrice={}",
                factoryId, reportType, startDate, endDate, maskPrice);

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition",
                String.format("attachment; filename=\"report_%s_%s.pdf\"", reportType, LocalDate.now()));

        reportService.exportReportAsPdf(factoryId, reportType, startDate, endDate, maskPrice, response);
    }

    /**
     * 获取自定义报表
     */
    @PostMapping("/custom")
    @Operation(summary = "获取自定义报表", description = "根据自定义参数生成报表")
    public ApiResponse<Map<String, Object>> getCustomReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Parameter(description = "自定义报表参数") Map<String, Object> parameters) {
        log.info("获取自定义报表: factoryId={}, parameters={}", factoryId, parameters);
        Map<String, Object> report = reportService.getCustomReport(factoryId, parameters);
        return ApiResponse.success(report);
    }

    /**
     * 获取报表实时数据
     */
    @GetMapping("/realtime")
    @Operation(summary = "获取报表实时数据", description = "获取工厂实时运营报表数据")
    public ApiResponse<Map<String, Object>> getRealtimeReportData(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("获取报表实时数据: factoryId={}", factoryId);
        Map<String, Object> data = reportService.getRealtimeData(factoryId);
        return ApiResponse.success(data);
    }

    // ========== 新增报表端点 (2026-01-14) ==========

    /**
     * 获取 OEE (设备综合效率) 报表
     * OEE = 可用性 × 表现性 × 质量率
     * 行业标准: ≥85% 为世界级水平
     */
    @GetMapping("/oee")
    @Operation(summary = "获取OEE报表", description = "获取设备综合效率(OEE)报表，包含可用性、表现性、质量率分析")
    public ApiResponse<OeeReportDTO> getOeeReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，默认本周一") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，默认今天") LocalDate endDate) {
        log.info("获取OEE报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 默认日期范围：本周
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(6);
        }

        OeeReportDTO report = reportService.getOeeReport(factoryId, startDate, endDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取成本差异报表
     * 对比 BOM 理论成本与实际成本
     * 行业标准: 差异率 ≤5%
     */
    @GetMapping("/cost-variance")
    @Operation(summary = "获取成本差异报表", description = "获取BOM理论成本与实际成本差异分析报表")
    public ApiResponse<CostVarianceReportDTO> getCostVarianceReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，默认30天前") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，默认今天") LocalDate endDate) {
        log.info("获取成本差异报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 默认日期范围：近30天
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(30);
        }

        CostVarianceReportDTO report = reportService.getCostVarianceReport(factoryId, startDate, endDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取完整 KPI 指标集
     * 整合生产、质量、成本、交付、设备等多维度指标
     */
    @GetMapping("/kpi-metrics")
    @Operation(summary = "获取完整KPI指标", description = "获取制造业核心KPI指标完整集合")
    public ApiResponse<KpiMetricsDTO> getKpiMetrics(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "指标日期，默认今天") LocalDate date) {
        log.info("获取完整KPI指标: factoryId={}, date={}", factoryId, date);

        if (date == null) {
            date = LocalDate.now();
        }

        KpiMetricsDTO metrics = reportService.getKpiMetricsDTO(factoryId, date);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取产能利用率报表
     */
    @GetMapping("/capacity-utilization")
    @Operation(summary = "获取产能利用率报表", description = "获取产能利用率分析，包含热力图数据")
    public ApiResponse<Map<String, Object>> getCapacityUtilizationReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.info("获取产能利用率报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(30);
        }

        Map<String, Object> report = reportService.getCapacityUtilizationReport(factoryId, startDate, endDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取准时交付报表
     * OTIF (On-Time In-Full) 准时足量交付率
     * 行业标准: ≥95%
     */
    @GetMapping("/on-time-delivery")
    @Operation(summary = "获取准时交付报表", description = "获取OTIF准时足量交付率分析")
    public ApiResponse<Map<String, Object>> getOnTimeDeliveryReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.info("获取准时交付报表: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(30);
        }

        Map<String, Object> report = reportService.getOnTimeDeliveryReport(factoryId, startDate, endDate);
        return ApiResponse.success(report);
    }

    /**
     * 获取销售订单产品级利润详情 (Sprint 4 Wave 2 S-PROFIT-DETAIL-1).
     *
     * <p>Returns one row per line item with 11 fields:
     * product / quantity / unitPrice / costUnitPrice / grossProfit / grossMarginPct /
     * discountAmount / taxAmount / netProfit / historicalAvgPrice / priceTrend.
     *
     * <p>Price-sensitive fields are stripped to null for non-price roles
     * (warehouse_manager, etc.) by the framework. Frontend additionally hides those
     * columns via canViewPrice v-if (defense in depth, mirrors PR #520 35-view pattern).
     */
    @GetMapping("/sales/profit-detail/{salesOrderId}")
    @Operation(summary = "销售订单产品级利润详情",
               description = "11-col per-line: 产品/数量/单价/成本/毛利/毛利率/折让/税额/净利/历史均价/趋势")
    public ApiResponse<List<SalesProductProfitRowDTO>> getSalesOrderProductProfitDetail(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "销售订单ID") String salesOrderId,
            @RequestParam(required = false, defaultValue = "90")
            @Parameter(description = "历史均价回溯天数 (默认 90 天)") Integer lookbackDays) {
        log.info("获取销售订单利润详情: factoryId={}, salesOrderId={}, lookbackDays={}",
                factoryId, salesOrderId, lookbackDays);
        List<SalesProductProfitRowDTO> rows =
                reportService.getSalesOrderProductProfitDetail(factoryId, salesOrderId, lookbackDays);
        return ApiResponse.success(rows);
    }

    // ============================================================
    // 运营 Dashboard 金额脱敏 helper
    // ============================================================

    /**
     * 递归脱敏运营 Dashboard 返回 Map 里的成本/金额语义 key
     * ({@link #OPERATIONAL_COST_KEYS}), 给无 {@code procurement:price:view} 权限的
     * 运营角色 (放宽权限后能进这些端点) 使用.
     *
     * <p>策略: <b>移除</b> key (omit), 而不是设 null — 对齐项目既有 hand-built Map
     * 脱敏惯例 (见 {@code MaterialConsumptionController.enrichConsumption}: 无权限时
     * 价格字段不放入 Map). 前端拿不到 key → 显示空/占位, 不会泄露金额.
     *
     * <p>递归处理嵌套结构: {@code kpi.unitCost} (一层嵌套 Map) 与顶层
     * {@code totalCost} 都能命中. 同时遍历 Collection / 嵌套 List<Map> (如
     * batchStatusDistribution / productTypeStats) 作防御性兜底, 即便目前那些子项
     * 不含金额 key.
     *
     * <p>命中规则: key 大小写不敏感地 <b>等于</b>某个 cost key, 或以其结尾
     * (e.g. {@code monthlyTotalCost} 命中 {@code totalCost}). 避免过度匹配 (如
     * {@code costBasis} / {@code unitCostBasis} 这类非金额标注字段不应被误删) — 因此
     * 用 endsWith 而非 contains, 且对 {@code *Basis} 后缀显式放行.
     */
    @SuppressWarnings("unchecked")
    private void maskOperationalDashboardCosts(Object node) {
        if (node instanceof Map<?, ?>) {
            Map<String, Object> map = (Map<String, Object>) node;

            // Trends 端点特例: costTrend 整列是成本数据 (每项还有 totalCost 的 value
            // 孪生 key, 与 production/quality trend 的 value 含义冲突, 不能靠通用
            // key 名区分). 直接整列移除 — production/quality trend 仍保留.
            try {
                map.remove("costTrend");
            } catch (UnsupportedOperationException ignored) {
                // 不可变 Map (理论上顶层 analysis 是 HashMap, 这里只是防御).
            }

            // 通用: 移除命中的成本/金额 key. 不可变 Map (Map.of) 会抛
            // UnsupportedOperationException — 捕获后逐项重建为可变副本不现实
            // (key 已确定要删), 故对不可变 Map 静默跳过 (它们由上层 costTrend
            // 整列移除 / 或本就不含金额 key 兜底).
            try {
                map.keySet().removeIf(this::isCostKey);
            } catch (UnsupportedOperationException ignored) {
                // 不可变 Map — 见上.
            }

            for (Object value : map.values()) {
                maskOperationalDashboardCosts(value);
            }
        } else if (node instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) node) {
                maskOperationalDashboardCosts(item);
            }
        }
    }

    /** Returns true when a Map key is a cost/amount field that must be masked. */
    private boolean isCostKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        // 显式放行非金额标注字段 (单位成本计算口径标记, 非金额本身).
        if (lower.endsWith("basis")) {
            return false;
        }
        for (String costKey : OPERATIONAL_COST_KEYS) {
            String lc = costKey.toLowerCase();
            if (lower.equals(lc) || lower.endsWith(lc)) {
                return true;
            }
        }
        return false;
    }
}