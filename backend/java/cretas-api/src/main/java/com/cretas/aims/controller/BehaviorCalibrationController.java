package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.calibration.CalibrationDashboardDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.calibration.BehaviorCalibrationMetrics;
import com.cretas.aims.entity.calibration.BehaviorCalibrationMetrics.PeriodType;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.calibration.ToolReliabilityStats;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.calibration.ToolCallRecordRepository;
import com.cretas.aims.service.calibration.BehaviorCalibrationService;
import com.cretas.aims.service.intent.impl.IntentMetricsDumpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * 行为校准控制器
 * 提供 ET-Agent 行为校准系统的管理 API
 *
 * 基于 ET-Agent 论文 (arXiv:2601.06860) 的设计
 *
 * 功能：
 * - 仪表盘综合数据展示
 * - 指标趋势查询
 * - 工具可靠性排名
 * - 工具调用记录分页查询
 * - 工厂排名
 * - 手动触发指标计算
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/calibration")
@RequiredArgsConstructor
@Validated
@Tag(name = "行为校准管理", description = "ET-Agent 行为校准系统管理 API（仅平台管理员）")
public class BehaviorCalibrationController {

    private final BehaviorCalibrationService behaviorCalibrationService;
    private final ToolCallRecordRepository toolCallRecordRepository;
    private final IntentMetricsDumpService intentMetricsDumpService;

    // ==================== 仪表盘 API ====================

    /**
     * 获取行为校准仪表盘数据
     *
     * 包含：
     * - 当前指标卡片（4个核心指标）
     * - 趋势数据（最近30天）
     * - 工具可靠性排名（Top工具）
     * - 最近工具调用（最近20条）
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取行为校准仪表盘数据",
               description = "获取校准系统的综合仪表盘数据，包括核心指标、趋势、工具排名等（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<CalibrationDashboardDTO> getDashboard(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId) {

        log.info("API调用: 获取行为校准仪表盘 - factoryId={}", factoryId);

        try {
            CalibrationDashboardDTO dashboard = behaviorCalibrationService.getDashboardData(factoryId);
            return ApiResponse.success(dashboard);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取仪表盘数据失败: {}", e.getMessage(), e);
            // R76: catch return ApiResponse.error → throw BusinessException (HTTP 500 + actionable)
            // 之前返回 HTTP 200 + success:false, 前端 Promise.allSettled 看到 success:false 但
            // 拿不到 stack/code, GlobalExceptionHandler 才能映射成正确 HTTP 5xx + severity:"error"
            throw new BusinessException(500, "获取仪表盘数据失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 获取实时指标
     *
     * 与 Dashboard 的区别：
     * - Dashboard: 返回历史数据（需要定时任务预计算）
     * - Realtime: 返回当前实时数据（每次请求重新计算）
     *
     * 适用场景：
     * - 管理员实时监控系统状态
     * - 调试时查看即时效果
     */
    @GetMapping("/metrics/realtime")
    @Operation(summary = "获取实时指标",
               description = "获取当前实时的行为校准指标，每次请求重新计算（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<CalibrationDashboardDTO.CurrentMetrics> getRealtimeMetrics(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId) {

        log.info("API调用: 获取实时指标 - factoryId={}", factoryId);

        try {
            CalibrationDashboardDTO.CurrentMetrics realtimeMetrics = behaviorCalibrationService.getRealtimeMetrics(factoryId);
            return ApiResponse.success(realtimeMetrics);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取实时指标失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取实时指标失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 指标趋势 API ====================

    /**
     * 获取指标趋势数据
     *
     * 用于图表展示，返回指定时间范围内的所有指标数据
     */
    @GetMapping("/metrics/trend")
    @Operation(summary = "获取指标趋势数据",
               description = "获取指定时间范围和周期类型的指标趋势数据（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<List<BehaviorCalibrationMetrics>> getMetricsTrend(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId,
            @Parameter(description = "开始日期 (yyyy-MM-dd)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "周期类型 (DAILY, WEEKLY, MONTHLY)")
            @RequestParam(defaultValue = "DAILY") String periodType) {

        log.info("API调用: 获取指标趋势 - factoryId={}, startDate={}, endDate={}, periodType={}",
                factoryId, startDate, endDate, periodType);

        try {
            PeriodType period = PeriodType.valueOf(periodType.toUpperCase());
            List<BehaviorCalibrationMetrics> metrics = behaviorCalibrationService.getMetricsTrend(
                    factoryId, startDate, endDate, period);
            return ApiResponse.success(metrics);
        } catch (IllegalArgumentException e) {
            log.warn("无效的周期类型: {}", periodType);
            throw new BusinessException(400, "无效的周期类型: " + periodType + "，支持: DAILY, WEEKLY, MONTHLY")
                    .withHint("请从 DAILY / WEEKLY / MONTHLY 中选择");
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取指标趋势失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取指标趋势失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 工具可靠性 API ====================

    /**
     * 获取工具可靠性排名
     *
     * 按成功率降序排列，返回所有工具的可靠性统计
     */
    @GetMapping("/tools/reliability")
    @Operation(summary = "获取工具可靠性排名",
               description = "获取指定日期的工具可靠性排名，按成功率降序（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<List<ToolReliabilityStats>> getToolReliabilityRanking(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId,
            @Parameter(description = "统计日期 (yyyy-MM-dd)，默认今天")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("API调用: 获取工具可靠性排名 - factoryId={}, date={}", factoryId, targetDate);

        try {
            List<ToolReliabilityStats> ranking = behaviorCalibrationService.getToolReliabilityRanking(
                    factoryId, targetDate);
            return ApiResponse.success(ranking);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取工具可靠性排名失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取工具可靠性排名失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 工具调用记录 API ====================

    /**
     * 分页获取工具调用记录
     *
     * 支持按工具名、状态、冗余标志过滤
     */
    @GetMapping("/calls")
    @Operation(summary = "分页获取工具调用记录",
               description = "获取工具调用记录列表，支持多条件过滤和分页（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<Page<ToolCallRecord>> getToolCallRecords(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId,
            @Parameter(description = "页码，从0开始")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "工具名称过滤")
            @RequestParam(required = false) String toolName,
            @Parameter(description = "执行状态过滤 (SUCCESS, FAILED, SKIPPED, TIMEOUT)")
            @RequestParam(required = false) String status,
            @Parameter(description = "是否只查询冗余调用")
            @RequestParam(defaultValue = "false") boolean redundantOnly) {

        log.info("API调用: 获取工具调用记录 - factoryId={}, page={}, size={}, toolName={}, status={}, redundantOnly={}",
                factoryId, page, size, toolName, status, redundantOnly);

        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ToolCallRecord> records;

            // 根据过滤条件选择查询方法
            if (redundantOnly) {
                if (factoryId != null && !factoryId.isEmpty()) {
                    records = toolCallRecordRepository.findByFactoryIdAndIsRedundantTrue(factoryId, pageable);
                } else {
                    records = toolCallRecordRepository.findByIsRedundantTrue(pageable);
                }
            } else if (toolName != null && !toolName.isEmpty()) {
                records = toolCallRecordRepository.findByToolName(toolName, pageable);
            } else if (factoryId != null && !factoryId.isEmpty()) {
                records = toolCallRecordRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
            } else {
                records = toolCallRecordRepository.findAllByOrderByCreatedAtDesc(pageable);
            }

            return ApiResponse.success(records);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取工具调用记录失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取工具调用记录失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 工厂排名 API ====================

    /**
     * 获取工厂综合得分排名
     *
     * 返回所有工厂按综合得分降序排列
     */
    @GetMapping("/factory-ranking")
    @Operation(summary = "获取工厂综合得分排名",
               description = "获取所有工厂的综合得分排名，按得分降序（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<List<FactoryRankingItem>> getFactoryRanking(
            @Parameter(description = "统计日期 (yyyy-MM-dd)，默认今天")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("API调用: 获取工厂综合得分排名 - date={}", targetDate);

        try {
            // 获取所有工厂的当日指标
            List<BehaviorCalibrationMetrics> metrics = behaviorCalibrationService.getMetricsTrend(
                    null, targetDate, targetDate, PeriodType.DAILY);

            // 按综合得分排序并构建排名
            List<FactoryRankingItem> ranking = metrics.stream()
                    .filter(m -> m.getFactoryId() != null && !m.getFactoryId().isEmpty())
                    .sorted(Comparator.comparing(
                            m -> m.getCompositeScore() != null ? m.getCompositeScore() : BigDecimal.ZERO,
                            Comparator.reverseOrder()))
                    .map(m -> FactoryRankingItem.builder()
                            .rank(0) // 稍后设置
                            .factoryId(m.getFactoryId())
                            .compositeScore(m.getCompositeScore())
                            .concisenessScore(m.getConcisenessScore())
                            .successRate(m.getSuccessRate())
                            .reasoningEfficiency(m.getReasoningEfficiency())
                            .totalCalls(m.getTotalCalls())
                            .successfulCalls(m.getSuccessfulCalls())
                            .redundantCalls(m.getRedundantCalls())
                            .metricDate(m.getMetricDate())
                            .build())
                    .collect(Collectors.toList());

            // 设置排名
            for (int i = 0; i < ranking.size(); i++) {
                ranking.get(i).setRank(i + 1);
            }

            return ApiResponse.success(ranking);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取工厂排名失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取工厂排名失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 手动计算指标 API ====================

    /**
     * 手动触发指标计算
     *
     * 用于重新计算指定工厂和日期的指标
     */
    // Issue #718 (2026-05-17) + Issue #789 cleanup (2026-05-17): NO-OP @PreAuthorize
    // removed (Spring Security method-security disabled — see RequireRole.java:11),
    // @RequireRole sweep applied to entire controller. Path /api/admin/calibration
    // is JWT-protected via interceptor (auth required), role-tier now enforced.
    @PostMapping("/metrics/calculate")
    @Operation(summary = "手动触发指标计算",
               description = "手动重新计算指定工厂和日期的校准指标（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<BehaviorCalibrationMetrics> calculateMetrics(
            @Valid @RequestBody CalculateMetricsRequest request) {

        String factoryId = request.getFactoryId();
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now();

        log.info("API调用: 手动计算指标 - factoryId={}, date={}", factoryId, date);

        try {
            BehaviorCalibrationMetrics metrics = behaviorCalibrationService.calculateDailyMetrics(
                    factoryId, date);

            // 同时计算工具可靠性统计
            behaviorCalibrationService.calculateToolReliabilityStats(factoryId, date);

            log.info("指标计算完成: factoryId={}, date={}, compositeScore={}",
                    factoryId, date, metrics.getCompositeScore());

            return ApiResponse.success("指标计算完成", metrics);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("指标计算失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "指标计算失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 低可靠性工具 API ====================

    /**
     * 获取低可靠性工具列表
     *
     * 返回成功率低于指定阈值的工具
     */
    @GetMapping("/tools/low-reliability")
    @Operation(summary = "获取低可靠性工具列表",
               description = "获取成功率低于指定阈值的工具列表（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<List<ToolReliabilityStats>> getLowReliabilityTools(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId,
            @Parameter(description = "统计日期 (yyyy-MM-dd)，默认今天")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "成功率阈值 (0-100)，默认80")
            @RequestParam(defaultValue = "80") BigDecimal threshold) {

        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("API调用: 获取低可靠性工具 - factoryId={}, date={}, threshold={}",
                factoryId, targetDate, threshold);

        try {
            List<ToolReliabilityStats> lowReliabilityTools = behaviorCalibrationService.getLowReliabilityTools(
                    factoryId, targetDate, threshold);
            return ApiResponse.success(lowReliabilityTools);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取低可靠性工具失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取低可靠性工具失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== 平均得分 API ====================

    /**
     * 获取时间范围内的平均综合得分
     */
    @GetMapping("/metrics/average-score")
    @Operation(summary = "获取平均综合得分",
               description = "获取指定时间范围内的平均综合得分（仅平台管理员）")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<Map<String, Object>> getAverageCompositeScore(
            @Parameter(description = "工厂ID，不传则获取全平台数据")
            @RequestParam(required = false) String factoryId,
            @Parameter(description = "开始日期 (yyyy-MM-dd)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("API调用: 获取平均综合得分 - factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        try {
            Double averageScore = behaviorCalibrationService.getAverageCompositeScore(
                    factoryId, startDate, endDate);

            Map<String, Object> result = new HashMap<>();
            result.put("factoryId", factoryId);
            result.put("startDate", startDate);
            result.put("endDate", endDate);
            result.put("averageCompositeScore", averageScore);

            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取平均综合得分失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取平均综合得分失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== Intent Architecture Metrics API (Phase 0) ====================

    /**
     * 获取意图识别各层命中统计 (Phase 0 instrumentation)
     *
     * <p>返回 {@code intent.match.stage.hit} Micrometer 计数器在当前 JVM 进程内的累计值,
     * 按 stage (EXACT / PHRASE_MATCH / SEMANTIC / LLM / NONE 等) 分组。用于驱动 Sprint N+2
     * 之后的意图架构简化决策 — 数据足够前不做架构动作。
     *
     * <p>注意: stage hit 计数是 <b>JVM 启动后累计值</b>, 不是区间增量。重启清零, 用
     * {@code jvmStartedAt} 字段判定 series reset。持久化由 {@link IntentMetricsDumpService}
     * 按配置间隔写 JSONL 备份 (默认 5 分钟, 受 {@code cretas.intent.metrics-dump.*} 控制),
     * 默认路径含 {@code ${server.port}} 后缀避免 Blue/Green deploy 写冲突。
     *
     * <p>响应示例:
     * <pre>
     * {
     *   "success": true,
     *   "data": {
     *     "timestamp": "2026-05-20T18:45:00-04:00",
     *     "jvmStartedAt": "2026-05-20T16:30:15-04:00",
     *     "stageHits": {"PHRASE_MATCH": 1203, "SEMANTIC": 4821, "LLM": 312, "NONE": 42},
     *     "total": 6378
     *   }
     * }
     * </pre>
     *
     * <p>详细架构决策见 {@code docs/superpowers/specs/2026-05-20-intent-architecture-3-layer-redesign.md}。
     */
    @GetMapping("/metrics/intent-layers")
    @Operation(summary = "获取意图识别各层命中统计",
               description = "Phase 0 instrumentation - 返回 intent.match.stage.hit 计数器快照,驱动后续架构简化决策(仅平台管理员)")
    @RequireRole({"platform_admin", "super_admin", "developer"})
    public ApiResponse<Map<String, Object>> getIntentLayerMetrics() {
        log.info("API调用: 获取意图识别各层命中统计");
        try {
            return ApiResponse.success(intentMetricsDumpService.snapshot());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取意图层命中统计失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "获取意图层命中统计失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    // ==================== DTO Classes ====================

    /**
     * 计算指标请求
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CalculateMetricsRequest {
        /**
         * 工厂ID，不传则计算全平台指标
         */
        private String factoryId;

        /**
         * 统计日期，默认今天
         */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date;
    }

    /**
     * 工厂排名项
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FactoryRankingItem {
        /**
         * 排名
         */
        private Integer rank;

        /**
         * 工厂ID
         */
        private String factoryId;

        /**
         * 综合得分 (0-100)
         */
        private BigDecimal compositeScore;

        /**
         * 简洁性得分 (0-100)
         */
        private BigDecimal concisenessScore;

        /**
         * 成功率 (0-100)
         */
        private BigDecimal successRate;

        /**
         * 推理效率 (0-100)
         */
        private BigDecimal reasoningEfficiency;

        /**
         * 总调用次数
         */
        private Integer totalCalls;

        /**
         * 成功调用次数
         */
        private Integer successfulCalls;

        /**
         * 冗余调用次数
         */
        private Integer redundantCalls;

        /**
         * 指标日期
         */
        private LocalDate metricDate;
    }
}
