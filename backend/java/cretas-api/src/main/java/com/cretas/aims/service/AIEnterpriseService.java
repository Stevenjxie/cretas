package com.cretas.aims.service;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.AIAuditLog;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.entity.config.AIQuotaConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import com.cretas.aims.repository.config.AIQuotaConfigRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.entity.config.AIReportPromptConfig;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AI企业级服务 - 完整的AI成本分析服务
 *
 * 核心功能：
 * 1. 智能路由：根据报告类型选择合适的处理逻辑
 * 2. 缓存管理：多层级报告缓存（batch/weekly/monthly/historical）
 * 3. 配额管理：每周100次配额，follow-up消耗1次，historical消耗5次
 * 4. 审计日志：完整的请求追踪和分析
 * 5. 定时任务支持：为AIReportScheduler提供报告生成方法
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-04
 */
@Service
public class AIEnterpriseService {
    private static final Logger log = LoggerFactory.getLogger(AIEnterpriseService.class);

    @Autowired
    private AIAnalysisService basicAIService;

    @Autowired
    private AIAnalysisResultRepository analysisResultRepository;

    @Autowired
    private AIQuotaUsageRepository quotaUsageRepository;

    @Autowired
    private AIAuditLogRepository auditLogRepository;

    @Autowired
    private AIQuotaConfigRepository quotaConfigRepository;

    @Autowired
    private AIReportPromptConfigRepository promptConfigRepository;

    @Autowired
    private ProcessingService processingService;

    @Autowired
    private AIIntentService aiIntentService;

    @Autowired
    private com.cretas.aims.utils.JwtUtil jwtUtil;

    @Value("${cretas.ai.service.url:http://localhost:8083}")
    private String aiServiceUrl;

    // SSE 流式处理线程池 — bounded to prevent thread exhaustion
    private final ExecutorService sseExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "enterprise-sse"); t.setDaemon(true); return t; });

    /**
     * AI成本分析 - 主入口（智能路由）
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param request 请求参数
     * @param httpRequest HTTP请求（用于审计）
     * @return AI分析响应
     */
    @Transactional
    public MobileDTO.AICostAnalysisResponse analyzeCost(String factoryId, Long userId,
                                                         MobileDTO.AICostAnalysisRequest request,
                                                         HttpServletRequest httpRequest) {
        long startTime = System.currentTimeMillis();
        String questionType = determineQuestionType(factoryId, userId, request, httpRequest);
        boolean cacheHit = false;
        boolean success = false;
        String errorMessage = null;
        AIAnalysisResult cachedResult = null;

        try {
            // 1. 检查缓存（default和followup类型）
            if ("default".equals(questionType) || "followup".equals(questionType)) {
                cachedResult = checkCache(factoryId, request.getBatchId(), questionType, request.getQuestion());
                if (cachedResult != null) {
                    cacheHit = true;
                    success = true;
                    log.info("AI分析缓存命中: factoryId={}, batchId={}, type={}",
                            factoryId, request.getBatchId(), questionType);

                    // 记录审计日志（缓存命中不消耗配额）
                    logAuditRecord(factoryId, userId, request, questionType, true, null,
                                   System.currentTimeMillis() - startTime, cacheHit, httpRequest);

                    return buildResponseFromCache(cachedResult, factoryId);
                }
            }

            // 2. 配额检查（followup和historical消耗配额）
            if ("followup".equals(questionType) || "historical".equals(questionType)) {
                checkQuotaOrThrow(factoryId, questionType);
            }

            // 3. 调用AI服务生成分析
            String aiAnalysis;
            String sessionId = request.getSession_id();
            Integer messageCount = 0;

            // 思考模式参数（默认开启）
            Boolean enableThinking = request.getEnableThinking() != null ? request.getEnableThinking() : true;
            Integer thinkingBudget = request.getThinkingBudget() != null ? request.getThinkingBudget() : 50;

            if ("historical".equals(questionType)) {
                // 生成历史综合报告
                aiAnalysis = generateHistoricalReport(
                        factoryId, userId, request.getStartDate(), request.getEndDate());
            } else {
                // 获取成本数据并调用AI（传递思考模式参数）
                Map<String, Object> costData = processingService.getBatchCostAnalysis(factoryId, request.getBatchId());
                Map<String, Object> aiResult = basicAIService.analyzeCost(
                        factoryId, userId, request.getBatchId(), costData,
                        sessionId, request.getQuestion(),
                        enableThinking, thinkingBudget);

                if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
                    aiAnalysis = (String) aiResult.get("aiAnalysis");
                    sessionId = (String) aiResult.get("sessionId");
                    messageCount = (Integer) aiResult.get("messageCount");
                    success = true;
                } else {
                    throw new RuntimeException("AI服务返回错误: " + aiResult.get("error"));
                }
            }

            // 4. 消耗配额
            if ("followup".equals(questionType) || "historical".equals(questionType)) {
                int quotaCost = getQuotaCostFromConfig(factoryId, questionType);
                consumeQuota(factoryId, quotaCost);
            }

            // 5. 保存结果到缓存
            AIAnalysisResult savedResult = saveAnalysisResult(
                    factoryId, request.getBatchId(), questionType, aiAnalysis, sessionId, request);

            // 6. 记录审计日志
            int quotaCost = ("followup".equals(questionType) || "historical".equals(questionType))
                    ? getQuotaCostFromConfig(factoryId, questionType) : 0;
            logAuditRecord(factoryId, userId, request, questionType, true, quotaCost,
                           System.currentTimeMillis() - startTime, false, httpRequest);

            // 7. 构建响应
            return buildSuccessResponse(savedResult, factoryId, sessionId, messageCount,
                                        System.currentTimeMillis() - startTime);

        } catch (QuotaExceededException e) {
            errorMessage = e.getMessage();
            logAuditRecord(factoryId, userId, request, questionType, false, 0,
                           System.currentTimeMillis() - startTime, cacheHit, httpRequest);
            throw e;
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("AI分析失败: factoryId={}, batchId={}, error={}",
                     factoryId, request.getBatchId(), e.getMessage(), e);
            logAuditRecord(factoryId, userId, request, questionType, false, 0,
                           System.currentTimeMillis() - startTime, cacheHit, httpRequest);

            return MobileDTO.AICostAnalysisResponse.builder()
                    .success(false)
                    .errorMessage("AI分析失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 生成周报告（定时任务调用）
     */
    @Transactional
    public void generateWeeklyReport(String factoryId, LocalDate weekStart, LocalDate weekEnd) {
        try {
            log.info("开始生成周报告: factoryId={}, week={} to {}",
                    factoryId, weekStart, weekEnd);

            // 1. 获取本周所有批次的成本数据
            List<Map<String, Object>> weeklyBatches = processingService.getWeeklyBatchesCost(
                    factoryId,
                    weekStart.atStartOfDay(),
                    weekEnd.atTime(23, 59, 59));

            // 2. 调用AI生成周报告
            String weeklyAnalysis = callAIForWeeklyReport(factoryId, weeklyBatches, weekStart, weekEnd);

            // 3. 保存周报告（30���有效期）
            AIAnalysisResult weeklyReport = AIAnalysisResult.builder()
                    .factoryId(factoryId)
                    .reportType("weekly")
                    .analysisText(weeklyAnalysis)
                    .periodStart(weekStart.atStartOfDay())
                    .periodEnd(weekEnd.atTime(23, 59, 59))
                    .expiresAt(LocalDateTime.now().plusDays(30))
                    .isAutoGenerated(true)
                    .build();
            analysisResultRepository.save(weeklyReport);

            log.info("周报告生成成功: factoryId={}, reportId={}", factoryId, weeklyReport.getId());

        } catch (Exception e) {
            log.error("周报告生成失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
        }
    }

    /**
     * 生成月报告（定时任务调用）
     */
    @Transactional
    public void generateMonthlyReport(String factoryId, LocalDate monthStart, LocalDate monthEnd) {
        try {
            log.info("开始生成月报告: factoryId={}, month={} to {}",
                    factoryId, monthStart, monthEnd);

            // 1. 获取本月所有批次的成本数据
            List<Map<String, Object>> monthlyBatches = processingService.getWeeklyBatchesCost(
                    factoryId,
                    monthStart.atStartOfDay(),
                    monthEnd.atTime(23, 59, 59));

            // 2. 调用AI生成月报告
            String monthlyAnalysis = callAIForMonthlyReport(factoryId, monthlyBatches, monthStart, monthEnd);

            // 3. 保存月报告（90天有效期）
            AIAnalysisResult monthlyReport = AIAnalysisResult.builder()
                    .factoryId(factoryId)
                    .reportType("monthly")
                    .analysisText(monthlyAnalysis)
                    .periodStart(monthStart.atStartOfDay())
                    .periodEnd(monthEnd.atTime(23, 59, 59))
                    .expiresAt(LocalDateTime.now().plusDays(90))
                    .isAutoGenerated(true)
                    .build();
            analysisResultRepository.save(monthlyReport);

            log.info("月报告生成成功: factoryId={}, reportId={}", factoryId, monthlyReport.getId());

        } catch (Exception e) {
            log.error("月报告生成失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
        }
    }

    /**
     * 生成日报告（定时任务调用）
     *
     * 聚焦当日生产运营数据，包括：
     * - 当日生产批次统计 (完成数、进行中、待处理)
     * - 当日质量检查结果 (合格率、不合格批次)
     * - 当日原料消耗和库存变动
     * - 当日设备运行状态和告警
     * - 当日出货统计
     *
     * @param factoryId 工厂ID
     */
    @Transactional
    public void generateDailyReport(String factoryId) {
        LocalDate today = LocalDate.now();
        generateDailyReport(factoryId, today);
    }

    /**
     * 生成指定日期的日报告
     *
     * @param factoryId 工厂ID
     * @param reportDate 报告日期
     */
    @Transactional
    public void generateDailyReport(String factoryId, LocalDate reportDate) {
        try {
            LocalDateTime dayStart = reportDate.atStartOfDay();
            LocalDateTime dayEnd = reportDate.atTime(23, 59, 59);

            log.info("开始生成日报告: factoryId={}, date={}",
                    factoryId, reportDate);

            // 1. 获取当日所有批次的数据
            List<Map<String, Object>> dailyBatches = processingService.getWeeklyBatchesCost(
                    factoryId,
                    dayStart,
                    dayEnd);

            // 2. 获取可配置的提示词模板
            AIReportPromptConfig config = promptConfigRepository
                    .findFirstByFactoryIdIsNullAndReportTypeAndIsActiveTrueOrderByPriorityDesc("daily")
                    .orElse(null);

            // 如果有工厂级别配置，优先使用
            AIReportPromptConfig factoryConfig = promptConfigRepository
                    .findFirstByFactoryIdAndReportTypeAndIsActiveTrueOrderByPriorityDesc(factoryId, "daily")
                    .orElse(null);
            if (factoryConfig != null) {
                config = factoryConfig;
            }

            String promptTemplate = config != null ? config.getPromptTemplate() : getDefaultDailyPrompt();

            // 3. 调用AI生成日报告
            String dailyAnalysis = callAIForDailyReport(factoryId, dailyBatches, reportDate, promptTemplate);

            // 4. 保存日报告（2天有效期，便于查看昨日报告）
            AIAnalysisResult dailyReport = AIAnalysisResult.builder()
                    .factoryId(factoryId)
                    .reportType("daily")
                    .analysisText(dailyAnalysis)
                    .periodStart(dayStart)
                    .periodEnd(dayEnd)
                    .dimension("daily")
                    .expiresAt(LocalDateTime.now().plusDays(2))
                    .isAutoGenerated(true)
                    .build();
            analysisResultRepository.save(dailyReport);

            log.info("日报告生成成功: factoryId={}, reportId={}, date={}",
                    factoryId, dailyReport.getId(), reportDate);

        } catch (Exception e) {
            log.error("日报告生成失败: factoryId={}, date={}, error={}",
                    factoryId, reportDate, e.getMessage(), e);
        }
    }

    /**
     * 获取默认的日报告提示词模板
     */
    private String getDefaultDailyPrompt() {
        return "请基于以上数据，生成一份简洁的日度运营报告，包括：\n" +
               "1. 今日生产概况（批次完成情况、产量）\n" +
               "2. 质量检查结果（合格率、异常批次）\n" +
               "3. 成本效率分析（单位成本、资源利用率）\n" +
               "4. 重点关注事项（需要立即处理的问题）\n" +
               "5. 明日生产建议";
    }

    /**
     * 调用AI生成日报告
     */
    @SuppressWarnings("unchecked")
    private String callAIForDailyReport(String factoryId, List<Map<String, Object>> batches,
                                        LocalDate reportDate, String promptTemplate) {
        try {
            // 1. 格式化日数据为AI Prompt
            String promptMessage = formatDailyReportPrompt(batches, reportDate, promptTemplate);

            // 2. 构建虚拟costData
            Map<String, Object> dailyData = new HashMap<>();
            dailyData.put("reportType", "daily");
            dailyData.put("batches", batches);
            dailyData.put("periodStart", reportDate.atStartOfDay());
            dailyData.put("periodEnd", reportDate.atTime(23, 59, 59));

            // 3. 调用AI分析
            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId,
                    null,  // 日报告没有单一batchId
                    dailyData,
                    null,  // 新会话
                    promptMessage
            );

            // 4. 返回AI分析文本
            if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
                return (String) aiResult.get("aiAnalysis");
            } else {
                log.error("AI日报告生成失败: {}", aiResult);
                throw new RuntimeException("AI日报告生成失败");
            }
        } catch (Exception e) {
            log.error("调用AI生成日报告失败: {}", e.getMessage(), e);
            throw new RuntimeException("日报告生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 格式化日报告Prompt
     */
    @SuppressWarnings("unchecked")
    private String formatDailyReportPrompt(List<Map<String, Object>> batches,
                                           LocalDate reportDate, String promptTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append("【今日成本分析报告】\n\n");
        sb.append("日期: ").append(reportDate).append("\n");
        sb.append("批次总数: ").append(batches.size()).append("\n\n");

        if (batches.isEmpty()) {
            sb.append("今日暂无生产数据\n");
            return sb.toString();
        }

        // 汇总统计
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal avgYieldRate = BigDecimal.ZERO;
        int completedCount = 0;
        int inProgressCount = 0;
        int pendingCount = 0;

        for (Map<String, Object> batch : batches) {
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");
            if (costSummary != null) {
                Object totalCostObj = costSummary.get("totalCost");
                if (totalCostObj != null) {
                    totalCost = totalCost.add((BigDecimal) totalCostObj);
                }
            }

            Object quantityObj = batch.get("actualQuantity");
            if (quantityObj != null) {
                totalQuantity = totalQuantity.add((BigDecimal) quantityObj);
            }

            Object yieldObj = batch.get("yieldRate");
            if (yieldObj != null) {
                avgYieldRate = avgYieldRate.add((BigDecimal) yieldObj);
            }

            // 统计批次状态
            Object statusObj = batch.get("status");
            if (statusObj != null) {
                String status = statusObj.toString().toLowerCase();
                if (status.contains("completed") || status.contains("完成")) {
                    completedCount++;
                } else if (status.contains("progress") || status.contains("进行中")) {
                    inProgressCount++;
                } else {
                    pendingCount++;
                }
            }
        }

        sb.append("【批次状态统计】\n");
        sb.append("已完成: ").append(completedCount).append(" 批次\n");
        sb.append("进行中: ").append(inProgressCount).append(" 批次\n");
        sb.append("待处理: ").append(pendingCount).append(" 批次\n\n");

        sb.append("【汇总数据】\n");
        sb.append("总成本: ").append(String.format("%.2f", totalCost)).append("\n");
        if (!batches.isEmpty()) {
            sb.append("平均批次成本: ").append(String.format("%.2f",
                    totalCost.divide(BigDecimal.valueOf(batches.size()), 2, RoundingMode.HALF_UP))).append("\n");
        }
        sb.append("总产量: ").append(String.format("%.2f", totalQuantity)).append(" kg\n");
        if (completedCount > 0) {
            sb.append("平均良品率: ").append(String.format("%.2f",
                    avgYieldRate.divide(BigDecimal.valueOf(completedCount), 2, RoundingMode.HALF_UP)))
                    .append("%\n");
        }

        // 批次列表（显示前8个，日报告更紧凑）
        sb.append("\n【批次详情】\n");
        int displayCount = Math.min(batches.size(), 8);
        for (int i = 0; i < displayCount; i++) {
            Map<String, Object> batch = batches.get(i);
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");

            sb.append((i + 1)).append(". ")
              .append(batch.get("batchNumber")).append(" - ")
              .append(batch.get("productName")).append("\n")
              .append("   状态: ").append(batch.get("status"))
              .append(", 成本: ").append(costSummary != null ? costSummary.get("totalCost") : "N/A")
              .append(", 良品率: ").append(batch.get("yieldRate")).append("%\n");
        }

        if (batches.size() > 8) {
            sb.append("... 还有 ").append(batches.size() - 8).append(" 个批次\n");
        }

        // 使用配置的提示词模板
        sb.append("\n").append(promptTemplate);

        return sb.toString();
    }

    /**
     * 获取工厂的AI报告列表
     */
    public MobileDTO.AIReportListResponse getReportList(String factoryId,
                                                        MobileDTO.AIReportListRequest request) {
        List<AIAnalysisResult> reports;

        if (request.getReportType() != null) {
            // 按类型查询
            reports = analysisResultRepository
                    .findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(factoryId, LocalDateTime.now())
                    .stream()
                    .filter(r -> request.getReportType().equals(r.getReportType()))
                    .collect(Collectors.toList());
        } else {
            // 查询所有有效报告
            reports = analysisResultRepository
                    .findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(factoryId, LocalDateTime.now());
        }

        // 转换为摘要格式
        List<MobileDTO.AIReportSummary> summaries = reports.stream()
                .map(this::toReportSummary)
                .collect(Collectors.toList());

        return MobileDTO.AIReportListResponse.builder()
                .reports(summaries)
                .total(summaries.size())
                .build();
    }

    /**
     * 获取工厂配额信息
     */
    public MobileDTO.AIQuotaInfo getQuotaInfo(String factoryId) {
        LocalDate weekStart = getWeekStart(LocalDate.now());
        Optional<AIQuotaUsage> quotaOpt = quotaUsageRepository.findByFactoryIdAndWeekStart(factoryId, weekStart);

        AIQuotaUsage quota = quotaOpt.orElseGet(() -> createNewQuota(factoryId, weekStart));

        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        return MobileDTO.AIQuotaInfo.builder()
                .total(quota.getQuotaLimit())
                .used(quota.getUsedCount())
                .remaining(quota.getRemainingQuota())
                .usageRate(quota.getUsageRate())
                .resetDate(nextMonday.atStartOfDay())
                .exceeded(quota.isExceeded())
                .build();
    }

    /**
     * 获取AI报告详情
     *
     * @param factoryId 工厂ID
     * @param reportId 报告ID
     * @return AI报告详情
     */
    public MobileDTO.AICostAnalysisResponse getReportDetail(String factoryId, Long reportId) {
        // 1. 查询报告
        AIAnalysisResult report = analysisResultRepository.findById(reportId)
                .orElseThrow(() -> new RuntimeException("报告不存在: reportId=" + reportId));

        // 2. 权限验证
        if (!report.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("无权访问此报告: factoryId=" + factoryId + ", reportFactoryId=" + report.getFactoryId());
        }

        // 3. 检查是否过期
        if (report.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("尝试访问已过期报告: reportId={}, expiresAt={}", reportId, report.getExpiresAt());
            throw new RuntimeException("报告已过期: expiresAt=" + report.getExpiresAt());
        }

        log.info("获取AI报告详情成功: factoryId={}, reportId={}, reportType={}",
                factoryId, reportId, report.getReportType());

        // 4. 构建响应
        return buildResponseFromCache(report, factoryId);
    }

    /**
     * AI时间范围成本分析
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param dimension 分析维度（overall/daily/weekly）
     * @param question 用户问题（可选）
     * @param httpRequest HTTP请求
     * @return AI分析结果
     */
    @Transactional
    public MobileDTO.AICostAnalysisResponse analyzeTimeRangeCost(
            String factoryId, Long userId,
            LocalDateTime startDate, LocalDateTime endDate,
            String dimension, String question,
            HttpServletRequest httpRequest) {

        long startTime = System.currentTimeMillis();

        try {
            log.info("开始时间范围成本分析: factoryId={}, userId={}, 时间段={} to {}, 维度={}",
                    factoryId, userId, startDate.toLocalDate(), endDate.toLocalDate(), dimension);

            // 1. 检查缓存（基于时间范围和维度，仅在无question时）
            String actualDimension = dimension != null ? dimension : "overall";
            if (question == null || question.trim().isEmpty()) {
                Optional<AIAnalysisResult> cachedResult = analysisResultRepository
                        .findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(factoryId, LocalDateTime.now())
                        .stream()
                        .filter(r -> "time_range".equals(r.getReportType()))
                        .filter(r -> r.getPeriodStart() != null && r.getPeriodEnd() != null)
                        .filter(r -> r.getPeriodStart().equals(startDate) && r.getPeriodEnd().equals(endDate))
                        .filter(r -> actualDimension.equals(r.getDimension() != null ? r.getDimension() : "overall"))
                        .findFirst();

                if (cachedResult.isPresent()) {
                    log.info("时间范围分析缓存命中: factoryId={}, startDate={}, endDate={}, dimension={}",
                            factoryId, startDate, endDate, actualDimension);

                    // 记录审计日志（缓存命中不消耗配额）
                    logAuditRecord(factoryId, userId, null, "time_range", true, 0,
                            System.currentTimeMillis() - startTime, true, httpRequest);

                    return buildResponseFromCache(cachedResult.get(), factoryId);
                }
            }

            // 2. 配额检查（时间范围分析消耗2次配额）
            checkQuotaOrThrow(factoryId, "time_range");

            // 3. 获取时间范围内的批次成本数据
            List<Map<String, Object>> batchesData = processingService
                    .getTimeRangeBatchesCostAnalysis(factoryId, startDate, endDate);

            if (batchesData.isEmpty()) {
                // 无数据时返回友好提示，而不是抛出异常
                log.info("时间范围内无批次数据，返回友好提示: factoryId={}, startDate={}, endDate={}",
                        factoryId, startDate, endDate);

                String noDataMessage = String.format(
                    "在 %s 至 %s 期间，系统未检索到任何生产批次记录。\n\n" +
                    "**可能的原因：**\n" +
                    "- 该时间段内工厂没有进行生产活动\n" +
                    "- 生产数据尚未录入系统\n\n" +
                    "**建议：**\n" +
                    "- 尝试选择其他时间范围进行查询\n" +
                    "- 确认生产记录是否已完整录入",
                    startDate.toLocalDate(), endDate.toLocalDate());

                return MobileDTO.AICostAnalysisResponse.builder()
                    .success(true)
                    .analysis(noDataMessage)
                    .sessionId(java.util.UUID.randomUUID().toString())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .cacheHit(false)
                    .quotaConsumed(0)
                    .build();
            }

            // 4. 格式化为AI Prompt
            String promptMessage = formatTimeRangePrompt(batchesData, startDate, endDate, dimension, question);

            // 5. 调用AI分析
            Map<String, Object> costData = new java.util.HashMap<>();
            costData.put("timeRangeBatches", batchesData);
            costData.put("startDate", startDate);
            costData.put("endDate", endDate);
            costData.put("dimension", dimension);

            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId, userId, null, costData, null, promptMessage, true, 50);

            if (aiResult == null || !Boolean.TRUE.equals(aiResult.get("success"))) {
                throw new RuntimeException("AI服务返回错误: " + (aiResult != null ? aiResult.get("error") : "unknown"));
            }

            String aiAnalysis = (String) aiResult.get("aiAnalysis");
            String sessionId = (String) aiResult.get("sessionId");

            // 6. 消耗配额（从配置读取）
            int quotaCost = getQuotaCostFromConfig(factoryId, "time_range");
            consumeQuota(factoryId, quotaCost);

            // 7. 保存结果到缓存（7天有效期，包含维度信息）
            AIAnalysisResult result = AIAnalysisResult.builder()
                    .factoryId(factoryId)
                    .reportType("time_range")
                    .analysisText(aiAnalysis)
                    .sessionId(sessionId)
                    .periodStart(startDate)
                    .periodEnd(endDate)
                    .dimension(actualDimension)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .isAutoGenerated(false)
                    .build();
            analysisResultRepository.save(result);

            // 8. 记录审计日志
            logAuditRecord(factoryId, userId, null, "time_range", true, quotaCost,
                    System.currentTimeMillis() - startTime, false, httpRequest);

            log.info("时间范围成本分析完成: factoryId={}, reportId={}, 批次数={}",
                    factoryId, result.getId(), batchesData.size());

            // 9. 构建响应
            return buildSuccessResponse(result, factoryId, sessionId, null,
                    System.currentTimeMillis() - startTime);

        } catch (QuotaExceededException e) {
            log.error("时间范围成本分析配额不足: factoryId={}, error={}", factoryId, e.getMessage());
            logAuditRecord(factoryId, userId, null, "time_range", false, 0,
                    System.currentTimeMillis() - startTime, false, httpRequest);
            throw e;
        } catch (Exception e) {
            log.error("时间范围成本分析失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            logAuditRecord(factoryId, userId, null, "time_range", false, 0,
                    System.currentTimeMillis() - startTime, false, httpRequest);

            return MobileDTO.AICostAnalysisResponse.builder()
                    .success(false)
                    .errorMessage("时间范围成本分析失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * AI时间范围成本分析 - 流式响应版本 (SSE)
     *
     * 实时返回AI分析过程，包括思考过程和最终答案
     * 适用于需要实时展示分析进度的场景
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param dimension 分析维度（overall/daily/weekly）
     * @param question 用户问题（可选）
     * @param enableThinking 是否启用 AI 思考过程（false 可显著加速响应，推荐默认关闭）
     * @param thinkingBudget 思考预算 Token 数（仅 enableThinking=true 时有效）
     * @param httpRequest HTTP请求
     * @return SseEmitter 流式响应发射器
     */
    public SseEmitter analyzeTimeRangeCostStream(
            String factoryId, Long userId,
            LocalDateTime startDate, LocalDateTime endDate,
            String dimension, String question,
            boolean enableThinking, int thinkingBudget,
            HttpServletRequest httpRequest) {

        // 创建 SSE Emitter，5分钟超时
        SseEmitter emitter = new SseEmitter(300_000L);
        long startTime = System.currentTimeMillis();

        log.info("开始流式时间范围成本分析: factoryId={}, userId={}, 时间段={} to {}, 维度={}",
                factoryId, userId, startDate.toLocalDate(), endDate.toLocalDate(), dimension);

        // 异步处理，避免阻塞主线程
        sseExecutor.execute(() -> {
            try {
                // 1. 发送开始事件
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data("{\"type\":\"start\",\"message\":\"开始AI分析...\"}"));

                // 2. 配额检查
                try {
                    checkQuotaOrThrow(factoryId, "time_range");
                } catch (QuotaExceededException e) {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"type\":\"error\",\"message\":\"配额不足: " + e.getMessage() + "\"}"));
                    emitter.complete();
                    return;
                }

                // 3. 获取批次数据
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data("{\"type\":\"progress\",\"message\":\"正在获取生产数据...\"}"));

                List<Map<String, Object>> batchesData = processingService
                        .getTimeRangeBatchesCostAnalysis(factoryId, startDate, endDate);

                if (batchesData.isEmpty()) {
                    // 无数据时返回友好提示，而不是错误
                    String noDataMessage = String.format(
                        "在 %s 至 %s 期间，系统未检索到任何生产批次记录。\n\n" +
                        "**可能的原因：**\n" +
                        "- 该时间段内工厂没有进行生产活动\n" +
                        "- 生产数据尚未录入系统\n\n" +
                        "**建议：**\n" +
                        "- 尝试选择其他时间范围进行查询\n" +
                        "- 确认生产记录是否已完整录入",
                        startDate.toLocalDate(), endDate.toLocalDate());

                    emitter.send(SseEmitter.event()
                            .name("content")
                            .data("{\"type\":\"content\",\"content\":\"" + noDataMessage.replace("\n", "\\n").replace("\"", "\\\"") + "\"}"));
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data("{\"type\":\"done\"}"));
                    emitter.complete();

                    log.info("时间范围内无数据，已返回友好提示: factoryId={}, startDate={}, endDate={}",
                            factoryId, startDate, endDate);
                    return;
                }

                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data("{\"type\":\"progress\",\"message\":\"获取到 " + batchesData.size() + " 个批次数据\"}"));

                // 4. 格式化 Prompt (使用精简版以减少Token，加速AI响应)
                String promptMessage = formatCompactTimeRangePrompt(batchesData, startDate, endDate, dimension, question);

                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data("{\"type\":\"progress\",\"message\":\"正在进行AI分析，请稍候...\"}"));

                // 5. 调用 Python SSE 端点（enableThinking 控制是否启用思考过程，关闭可加速响应）
                String streamUrl = aiServiceUrl + "/api/chat/stream";
                connectToPythonSSE(streamUrl, promptMessage, factoryId, emitter, enableThinking, thinkingBudget);

                // 6. 消耗配额
                int quotaCost = getQuotaCostFromConfig(factoryId, "time_range");
                consumeQuota(factoryId, quotaCost);

                // 7. 记录审计日志
                logAuditRecord(factoryId, userId, null, "time_range_stream", true, quotaCost,
                        System.currentTimeMillis() - startTime, false, httpRequest);

                log.info("流式时间范围成本分析完成: factoryId={}, 批次数={}, 耗时={}ms",
                        factoryId, batchesData.size(), System.currentTimeMillis() - startTime);

            } catch (Exception e) {
                log.error("流式分析失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"type\":\"error\",\"message\":\"分析失败: " + e.getMessage().replace("\"", "'") + "\"}"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        // 设置超时和完成回调
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: factoryId={}", factoryId);
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: factoryId={}", factoryId);
        });

        emitter.onError((ex) -> {
            log.error("SSE 连接错误: factoryId={}, error={}", factoryId, ex.getMessage());
        });

        return emitter;
    }

    /**
     * 连接到 Python SSE 端点并转发事件
     *
     * @param streamUrl Python SSE 端点 URL
     * @param message 发送给 AI 的消息内容
     * @param factoryId 工厂ID
     * @param emitter SSE 发射器
     * @param enableThinking 是否启用 AI 思考过程（false 可显著加速响应）
     * @param thinkingBudget 思考预算 Token 数（仅 enableThinking=true 时有效）
     */
    private void connectToPythonSSE(String streamUrl, String message, String factoryId, SseEmitter emitter,
                                    boolean enableThinking, int thinkingBudget) throws Exception {
        URL url = new URL(streamUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);  // 10秒连接超时
        connection.setReadTimeout(300_000);    // 5分钟读取超时

        // 构建请求体（enableThinking 由前端控制，false 时跳过思考过程，显著加速响应）
        String requestBody = String.format(
                "{\"message\":\"%s\",\"user_id\":\"%s\",\"enable_thinking\":%s,\"thinking_budget\":%d}",
                message.replace("\"", "\\\"").replace("\n", "\\n"),
                factoryId,
                enableThinking,
                thinkingBudget
        );

        // 发送请求
        try (java.io.OutputStream os = connection.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 读取 SSE 流
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            StringBuilder eventData = new StringBuilder();
            boolean completedNormally = false;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    eventData.append(data);
                } else if (line.isEmpty() && eventData.length() > 0) {
                    // 完整事件，转发到前端
                    String eventJson = eventData.toString();
                    try {
                        // 解析事件类型
                        String eventType = "message";
                        if (eventJson.contains("\"type\":\"thinking\"")) {
                            eventType = "thinking";
                        } else if (eventJson.contains("\"type\":\"answer\"")) {
                            eventType = "answer";
                        } else if (eventJson.contains("\"type\":\"complete\"")) {
                            eventType = "complete";
                        } else if (eventJson.contains("\"type\":\"error\"")) {
                            eventType = "error";
                        }

                        emitter.send(SseEmitter.event()
                                .name(eventType)
                                .data(eventJson));

                        // 如果是完成事件，结束连接
                        if ("complete".equals(eventType)) {
                            completedNormally = true;
                            emitter.complete();
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("转发SSE事件失败: {}", e.getMessage());
                    }
                    eventData.setLength(0);
                }
            }

            // 兜底：如果流结束但未收到 complete 事件，确保关闭 SSE 连接
            if (!completedNormally) {
                log.warn("Python SSE 流结束但未收到 complete 事件，执行兜底关闭: factoryId={}", factoryId);
                try {
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data("{\"type\":\"complete\",\"message\":\"分析完成\"}"));
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("兜底关闭 SSE 失败: {}", e.getMessage());
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 精简版时间范围Prompt - 用于SSE流式响应，减少Token提升AI响应速度
     * 相比完整版减少约50%的Token数量
     */
    private String formatCompactTimeRangePrompt(List<Map<String, Object>> batchesData,
                                                LocalDateTime startDate, LocalDateTime endDate,
                                                String dimension, String question) {
        StringBuilder sb = new StringBuilder();
        String actualDimension = dimension != null ? dimension : "overall";

        // 计算汇总数据
        java.math.BigDecimal totalCost = batchesData.stream()
                .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        java.math.BigDecimal avgCost = batchesData.isEmpty() ? java.math.BigDecimal.ZERO :
                totalCost.divide(java.math.BigDecimal.valueOf(batchesData.size()), 2, java.math.RoundingMode.HALF_UP);

        // 单行紧凑格式头部
        sb.append("成本分析|").append(startDate.toLocalDate()).append("~").append(endDate.toLocalDate())
          .append("|批次:").append(batchesData.size())
          .append("|总成本:¥").append(String.format("%.0f", totalCost))
          .append("|均值:¥").append(String.format("%.0f", avgCost)).append("\n");

        // 根据维度添加精简的数据视图
        switch (actualDimension) {
            case "daily":
                appendCompactDailyView(sb, batchesData);
                break;
            case "weekly":
                appendCompactWeeklyView(sb, batchesData);
                break;
            default:
                appendCompactOverallView(sb, batchesData);
                break;
        }

        // 用户问题
        if (question != null && !question.trim().isEmpty()) {
            sb.append("问:").append(question).append("\n");
        }

        // 简短分析指导
        sb.append("请简洁分析成本状况并给出建议。");

        return sb.toString();
    }

    /**
     * 精简版综合视图 - 仅显示Top3批次
     */
    private void appendCompactOverallView(StringBuilder sb, List<Map<String, Object>> batchesData) {
        int displayCount = Math.min(batchesData.size(), 3);
        sb.append("Top").append(displayCount).append("批次:");
        for (int i = 0; i < displayCount; i++) {
            Map<String, Object> batch = batchesData.get(i);
            Map costSummary = (Map) batch.get("costSummary");
            sb.append(batch.get("batchNumber")).append("/¥").append(costSummary.get("totalCost"));
            if (i < displayCount - 1) sb.append(",");
        }
        sb.append("\n");
    }

    /**
     * 精简版日分析视图 - 单行显示每日汇总
     */
    private void appendCompactDailyView(StringBuilder sb, List<Map<String, Object>> batchesData) {
        Map<java.time.LocalDate, java.math.BigDecimal> dailyCosts = batchesData.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        b -> {
                            Object createdAt = b.get("createdAt");
                            if (createdAt instanceof LocalDateTime) return ((LocalDateTime) createdAt).toLocalDate();
                            if (createdAt instanceof java.time.LocalDate) return (java.time.LocalDate) createdAt;
                            return java.time.LocalDate.now();
                        },
                        java.util.stream.Collectors.reducing(
                                java.math.BigDecimal.ZERO,
                                b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"),
                                java.math.BigDecimal::add
                        )
                ));

        sb.append("日成本:");
        dailyCosts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(7)
                .forEach(e -> sb.append(e.getKey().getDayOfMonth()).append("日/¥").append(String.format("%.0f", e.getValue())).append(","));
        sb.setLength(sb.length() - 1); // 移除最后逗号
        sb.append("\n");
    }

    /**
     * 精简版周分析视图 - 单行显示每周汇总
     */
    private void appendCompactWeeklyView(StringBuilder sb, List<Map<String, Object>> batchesData) {
        Map<Integer, java.math.BigDecimal> weeklyCosts = batchesData.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        b -> {
                            Object createdAt = b.get("createdAt");
                            java.time.LocalDate date;
                            if (createdAt instanceof LocalDateTime) date = ((LocalDateTime) createdAt).toLocalDate();
                            else if (createdAt instanceof java.time.LocalDate) date = (java.time.LocalDate) createdAt;
                            else date = java.time.LocalDate.now();
                            return date.get(java.time.temporal.WeekFields.ISO.weekOfYear());
                        },
                        java.util.stream.Collectors.reducing(
                                java.math.BigDecimal.ZERO,
                                b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"),
                                java.math.BigDecimal::add
                        )
                ));

        sb.append("周成本:");
        weeklyCosts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("W").append(e.getKey()).append("/¥").append(String.format("%.0f", e.getValue())).append(","));
        sb.setLength(sb.length() - 1);
        sb.append("\n");
    }

    /**
     * 格式化时间范围Prompt - 根据维度生成不同的分析视图
     */
    private String formatTimeRangePrompt(List<Map<String, Object>> batchesData,
                                        LocalDateTime startDate, LocalDateTime endDate,
                                        String dimension, String question) {
        StringBuilder sb = new StringBuilder();
        String actualDimension = dimension != null ? dimension : "overall";

        sb.append("【时间范围成本分析】\n\n");
        sb.append("时间范围: ").append(startDate.toLocalDate())
          .append(" 至 ").append(endDate.toLocalDate()).append("\n");
        sb.append("分析维度: ").append(getDimensionLabel(actualDimension)).append("\n");
        sb.append("批次数量: ").append(batchesData.size()).append("\n\n");

        // 根据维度生成不同的数据视图
        switch (actualDimension) {
            case "daily":
                appendDailyAnalysis(sb, batchesData);
                break;
            case "weekly":
                appendWeeklyAnalysis(sb, batchesData);
                break;
            case "overall":
            default:
                appendOverallAnalysis(sb, batchesData);
                break;
        }

        // 用户问题
        if (question != null && !question.trim().isEmpty()) {
            sb.append("\n【用户问题】\n").append(question).append("\n");
        }

        // 添加维度特定的分析指导
        sb.append("\n").append(getDimensionPrompt(actualDimension));

        return sb.toString();
    }

    /**
     * 获取维度的中文标签
     */
    private String getDimensionLabel(String dimension) {
        switch (dimension) {
            case "daily": return "按日分析";
            case "weekly": return "按周分析";
            default: return "综合分析";
        }
    }

    /**
     * 获取维度特定的分析指导Prompt
     */
    private String getDimensionPrompt(String dimension) {
        switch (dimension) {
            case "daily":
                return "请重点分析每日成本变化趋势，识别成本波动较大的日期和可能原因，提供日度成本优化建议。";
            case "weekly":
                return "请重点分析每周成本变化规律，对比不同周的成本效率和生产特征，提供周度成本管控建议。";
            default:
                return "请从整体角度分析成本结构，识别主要成本驱动因素，评估成本效率并提供综合优化建议。";
        }
    }

    /**
     * 综合分析视图 - 保持现有逻辑，展示汇总和批次详情
     */
    private void appendOverallAnalysis(StringBuilder sb, List<Map<String, Object>> batchesData) {
        // 汇总统计
        java.math.BigDecimal totalCost = batchesData.stream()
                .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        sb.append("【汇总数据】\n");
        sb.append("总成本: ¥").append(String.format("%.2f", totalCost)).append("\n");
        if (!batchesData.isEmpty()) {
            sb.append("平均批次成本: ¥").append(String.format("%.2f",
                    totalCost.divide(java.math.BigDecimal.valueOf(batchesData.size()), 2, java.math.RoundingMode.HALF_UP))).append("\n\n");
        }

        // 批次列表（显示前10个）
        sb.append("【批次详情】\n");
        int displayCount = Math.min(batchesData.size(), 10);
        for (int i = 0; i < displayCount; i++) {
            Map<String, Object> batch = batchesData.get(i);
            Map batchInfo = (Map) batch.get("batchInfo");
            Map costSummary = (Map) batch.get("costSummary");

            sb.append((i + 1)).append(". ")
              .append(batch.get("batchNumber")).append(" - ")
              .append(batch.get("productName")).append("\n")
              .append("   状态: ").append(batch.get("status"))
              .append(", 成本: ¥").append(costSummary.get("totalCost"))
              .append(", 良品率: ").append(batchInfo.get("yieldRate")).append("%\n");
        }

        if (batchesData.size() > 10) {
            sb.append("... 还有 ").append(batchesData.size() - 10).append(" 个批次\n");
        }
    }

    /**
     * 按日分析视图 - 按日期分组聚合，展示每日成本趋势
     */
    private void appendDailyAnalysis(StringBuilder sb, List<Map<String, Object>> batchesData) {
        // 按日期分组
        Map<java.time.LocalDate, List<Map<String, Object>>> byDate = batchesData.stream()
                .collect(java.util.stream.Collectors.groupingBy(b -> {
                    Object createdAt = b.get("createdAt");
                    if (createdAt instanceof LocalDateTime) {
                        return ((LocalDateTime) createdAt).toLocalDate();
                    } else if (createdAt instanceof java.time.LocalDate) {
                        return (java.time.LocalDate) createdAt;
                    }
                    return java.time.LocalDate.now();
                }));

        sb.append("【每日成本统计】\n");
        sb.append("| 日期 | 批次数 | 总成本(元) | 平均成本(元) |\n");
        sb.append("|------|--------|------------|------------|\n");

        // 按日期排序
        byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    java.time.LocalDate date = entry.getKey();
                    List<Map<String, Object>> dailyBatches = entry.getValue();

                    java.math.BigDecimal dailyTotal = dailyBatches.stream()
                            .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    java.math.BigDecimal avgCost = dailyBatches.isEmpty() ? java.math.BigDecimal.ZERO :
                            dailyTotal.divide(java.math.BigDecimal.valueOf(dailyBatches.size()), 2, java.math.RoundingMode.HALF_UP);

                    sb.append(String.format("| %s | %d | ¥%.2f | ¥%.2f |\n",
                            date, dailyBatches.size(), dailyTotal, avgCost));
                });

        // 显示日成本波动信息
        if (byDate.size() > 1) {
            sb.append("\n【日成本波动分析】\n");
            java.math.BigDecimal maxDailyCost = java.math.BigDecimal.ZERO;
            java.math.BigDecimal minDailyCost = null;
            java.time.LocalDate maxDate = null, minDate = null;

            for (Map.Entry<java.time.LocalDate, List<Map<String, Object>>> entry : byDate.entrySet()) {
                java.math.BigDecimal dailyTotal = entry.getValue().stream()
                        .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                if (dailyTotal.compareTo(maxDailyCost) > 0) {
                    maxDailyCost = dailyTotal;
                    maxDate = entry.getKey();
                }
                if (minDailyCost == null || dailyTotal.compareTo(minDailyCost) < 0) {
                    minDailyCost = dailyTotal;
                    minDate = entry.getKey();
                }
            }

            sb.append("最高日成本: ").append(maxDate).append(" - ¥").append(String.format("%.2f", maxDailyCost)).append("\n");
            sb.append("最低日成本: ").append(minDate).append(" - ¥").append(String.format("%.2f", minDailyCost)).append("\n");
        }
    }

    /**
     * 按周分析视图 - 按周分组聚合，展示每周成本对比
     */
    private void appendWeeklyAnalysis(StringBuilder sb, List<Map<String, Object>> batchesData) {
        // 按周分组 (使用 ISO 周)
        java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;

        Map<String, List<Map<String, Object>>> byWeek = batchesData.stream()
                .collect(java.util.stream.Collectors.groupingBy(b -> {
                    Object createdAt = b.get("createdAt");
                    java.time.LocalDate date;
                    if (createdAt instanceof LocalDateTime) {
                        date = ((LocalDateTime) createdAt).toLocalDate();
                    } else if (createdAt instanceof java.time.LocalDate) {
                        date = (java.time.LocalDate) createdAt;
                    } else {
                        date = java.time.LocalDate.now();
                    }
                    int year = date.getYear();
                    int week = date.get(weekFields.weekOfWeekBasedYear());
                    return String.format("%d-W%02d", year, week);
                }));

        sb.append("【每周成本统计】\n");
        sb.append("| 周次 | 批次数 | 总成本(元) | 平均成本(元) |\n");
        sb.append("|------|--------|------------|------------|\n");

        // 按周排序
        byWeek.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String weekLabel = entry.getKey();
                    List<Map<String, Object>> weeklyBatches = entry.getValue();

                    java.math.BigDecimal weeklyTotal = weeklyBatches.stream()
                            .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    java.math.BigDecimal avgCost = weeklyBatches.isEmpty() ? java.math.BigDecimal.ZERO :
                            weeklyTotal.divide(java.math.BigDecimal.valueOf(weeklyBatches.size()), 2, java.math.RoundingMode.HALF_UP);

                    sb.append(String.format("| %s | %d | ¥%.2f | ¥%.2f |\n",
                            weekLabel, weeklyBatches.size(), weeklyTotal, avgCost));
                });

        // 显示周成本对比信息
        if (byWeek.size() > 1) {
            sb.append("\n【周成本对比分析】\n");
            java.math.BigDecimal maxWeeklyCost = java.math.BigDecimal.ZERO;
            java.math.BigDecimal minWeeklyCost = null;
            String maxWeek = null, minWeek = null;

            for (Map.Entry<String, List<Map<String, Object>>> entry : byWeek.entrySet()) {
                java.math.BigDecimal weeklyTotal = entry.getValue().stream()
                        .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                if (weeklyTotal.compareTo(maxWeeklyCost) > 0) {
                    maxWeeklyCost = weeklyTotal;
                    maxWeek = entry.getKey();
                }
                if (minWeeklyCost == null || weeklyTotal.compareTo(minWeeklyCost) < 0) {
                    minWeeklyCost = weeklyTotal;
                    minWeek = entry.getKey();
                }
            }

            sb.append("最高周成本: ").append(maxWeek).append(" - ¥").append(String.format("%.2f", maxWeeklyCost)).append("\n");
            sb.append("最低周成本: ").append(minWeek).append(" - ¥").append(String.format("%.2f", minWeeklyCost)).append("\n");

            // 计算周环比变化
            if (byWeek.size() >= 2) {
                List<String> weeks = byWeek.keySet().stream().sorted().collect(java.util.stream.Collectors.toList());
                if (weeks.size() >= 2) {
                    String lastWeek = weeks.get(weeks.size() - 1);
                    String prevWeek = weeks.get(weeks.size() - 2);

                    java.math.BigDecimal lastWeekCost = byWeek.get(lastWeek).stream()
                            .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    java.math.BigDecimal prevWeekCost = byWeek.get(prevWeek).stream()
                            .map(b -> (java.math.BigDecimal) ((Map) b.get("costSummary")).get("totalCost"))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    if (prevWeekCost.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        java.math.BigDecimal changePercent = lastWeekCost.subtract(prevWeekCost)
                                .divide(prevWeekCost, 4, java.math.RoundingMode.HALF_UP)
                                .multiply(java.math.BigDecimal.valueOf(100));
                        sb.append("周环比变化: ").append(String.format("%+.1f%%", changePercent)).append("\n");
                    }
                }
            }
        }
    }

    /**
     * 批次对比分析
     */
    @Transactional
    public MobileDTO.AICostAnalysisResponse compareBatchCosts(
            String factoryId, Long userId, List<String> batchIds,
            String question, HttpServletRequest httpRequest) {

        long startTime = System.currentTimeMillis();

        try {
            log.info("开始批次对比分析: factoryId={}, userId={}, batchIds={}", factoryId, userId, batchIds);

            // 1. 参数校验
            if (batchIds == null || batchIds.size() < 2) {
                throw new IllegalArgumentException("至少需要2个批次进行对比分析");
            }
            if (batchIds.size() > 5) {
                throw new IllegalArgumentException("最多支持5个批次进行对比分析");
            }

            // 2. 检查缓存（仅在无question时）
            if (question == null || question.trim().isEmpty()) {
                String cacheKey = String.join(",", batchIds);
                Optional<AIAnalysisResult> cachedResult = analysisResultRepository
                        .findByFactoryIdAndExpiresAtAfterOrderByCreatedAtDesc(factoryId, LocalDateTime.now())
                        .stream()
                        .filter(r -> "comparison".equals(r.getReportType()))
                        .filter(r -> cacheKey.equals(r.getBatchId()))
                        .findFirst();

                if (cachedResult.isPresent()) {
                    log.info("命中缓存: 批次对比分析");
                    return buildResponseFromCache(cachedResult.get(), factoryId);
                }
            }

            // 3. 配额检查（对比分析消耗3次配额）
            checkQuotaOrThrow(factoryId, "comparison");

            // 4. 获取批次对比数据
            List<Map<String, Object>> batchesData = processingService
                    .getComparativeBatchesCostAnalysis(factoryId, batchIds);

            // 5. 格式化为AI Prompt（表格格式）
            String promptMessage = formatComparisonPrompt(batchesData, question);

            // 6. 构造虚拟costData用于调用AI (使用第一个批次ID作为主体)
            Map<String, Object> virtualCostData = new java.util.HashMap<>();
            virtualCostData.put("comparisonData", batchesData);
            virtualCostData.put("type", "comparison");

            // 7. 调用AI分析
            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId, userId, batchIds.get(0), virtualCostData,
                    null, promptMessage, true, 50);

            String aiAnalysis = (String) aiResult.get("aiAnalysis");
            String sessionId = (String) aiResult.get("sessionId");
            Integer messageCount = (Integer) aiResult.getOrDefault("messageCount", 1);

            // 8. 消耗配额（从配置读取）
            int quotaCost = getQuotaCostFromConfig(factoryId, "comparison");
            consumeQuota(factoryId, quotaCost);

            // 9. 保存结果到缓存（7天有效期）
            String cacheKey = String.join(",", batchIds);
            AIAnalysisResult result = AIAnalysisResult.builder()
                    .factoryId(factoryId)
                    .batchId(cacheKey)  // 使用批次ID组合作为key
                    .reportType("comparison")
                    .analysisText(aiAnalysis)
                    .sessionId(sessionId)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            analysisResultRepository.save(result);

            // 10. 记录审计日志
            MobileDTO.AICostAnalysisRequest dummyRequest = new MobileDTO.AICostAnalysisRequest();
            dummyRequest.setBatchId(cacheKey);
            dummyRequest.setQuestion(question);
            logAuditRecord(factoryId, userId, dummyRequest, "comparison", true, quotaCost,
                          System.currentTimeMillis() - startTime, false, httpRequest);

            log.info("批次对比分析完成: reportId={}", result.getId());

            return MobileDTO.AICostAnalysisResponse.builder()
                    .success(true)
                    .reportId(result.getId())
                    .analysis(aiAnalysis)
                    .sessionId(sessionId)
                    .messageCount(messageCount)
                    .generatedAt(LocalDateTime.now())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .quotaConsumed(quotaCost)
                    .build();

        } catch (QuotaExceededException e) {
            log.warn("配额不足: factoryId={}, error={}", factoryId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("批次对比分析失败: factoryId={}, batchIds={}, error={}",
                    factoryId, batchIds, e.getMessage(), e);

            return MobileDTO.AICostAnalysisResponse.builder()
                    .success(false)
                    .errorMessage("批次对比分析失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 格式化批次对比Prompt（表格格式）
     */
    private String formatComparisonPrompt(List<Map<String, Object>> batchesData, String question) {
        StringBuilder sb = new StringBuilder();

        sb.append("# 批次对比分析\n\n");
        sb.append("以下是").append(batchesData.size()).append("个批次的详细成本数据对比：\n\n");

        // 表头
        sb.append("| 指标 | ");
        for (int i = 0; i < batchesData.size(); i++) {
            Map<String, Object> batch = batchesData.get(i);
            sb.append(batch.get("batchNumber")).append(" | ");
        }
        sb.append("\n");

        // 分隔线
        sb.append("|------|");
        for (int i = 0; i < batchesData.size(); i++) {
            sb.append("------|");
        }
        sb.append("\n");

        // 数据行
        String[] metrics = {
            "产品名称:productName",
            "状态:status",
            "总成本:totalCost",
            "原材料成本:materialCost",
            "人工成本:laborCost",
            "设备成本:equipmentCost",
            "能源成本:energyCost",
            "包装成本:packagingCost",
            "单位成本:unitCost",
            "良品率:yieldRate",
            "生产效率:efficiency",
            "批次大小:batchSize"
        };

        for (String metric : metrics) {
            String[] parts = metric.split(":");
            String label = parts[0];
            String key = parts[1];

            sb.append("| ").append(label).append(" | ");
            for (Map<String, Object> batch : batchesData) {
                Object value = batch.get(key);
                sb.append(value != null ? value : "N/A").append(" | ");
            }
            sb.append("\n");
        }

        if (question != null && !question.trim().isEmpty()) {
            sb.append("\n用户问题：").append(question).append("\n");
        }

        sb.append("\n请基于以上数据，提供：\n");
        sb.append("1. 成本对比分析（哪个批次成本最优？原因是什么？）\n");
        sb.append("2. 效率对比分析（哪个批次效率最高？关键因素是什么？）\n");
        sb.append("3. 质量对比分析（良品率差异及改进建议）\n");
        sb.append("4. 综合优化建议（如何将低效批次改进到最优水平？）\n");

        return sb.toString();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 判断问题类型
     *
     * 增强版：使用AI意图识别服务进行智能路由
     * 1. 优先使用显式指定的reportType
     * 2. 尝试通过关键词/正则匹配意图
     * 3. 验证用户角色权限
     * 4. 根据敏感度决定是否需要审批
     *
     * @param factoryId 工厂ID (用于多租户隔离)
     * @param userId 用户ID (用于Tool Calling权限验证)
     * @param request 请求参数
     * @param httpRequest HTTP请求 (用于提取用户角色)
     * @return 问题类型/意图代码
     */
    private String determineQuestionType(String factoryId, Long userId, MobileDTO.AICostAnalysisRequest request, HttpServletRequest httpRequest) {
        // 1. 优先使用显式指定的reportType (向后兼容)
        if (request.getReportType() != null && !request.getReportType().isEmpty()) {
            return request.getReportType();
        }

        // 2. 如果没有问题，返回默认
        String question = request.getQuestion();
        if (question == null || question.trim().isEmpty()) {
            return "default";
        }

        // 3. 使用AI意图识别服务匹配意图 (带操作类型检测，BUG-001/002 修复)
        // 提取用户角色用于Tool Calling权限验证
        String userRole = extractUserRoleFromRequest(httpRequest);
        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(
                question, factoryId, 1, userId, userRole);
        if (!matchResult.hasMatch()) {
            log.debug("No intent matched for question: {}, falling back to 'followup'", question);
            return "followup";
        }

        AIIntentConfig intent = matchResult.getBestMatch();
        String intentCode = intent.getIntentCode();

        // 4. 验证用户角色权限 (userRole已在上面提取)
        if (userRole != null && !aiIntentService.hasPermission(intentCode, userRole)) {
            log.warn("User role '{}' does not have permission for intent: {}", userRole, intentCode);
            throw new BusinessException(403, "您没有权限执行此类AI操作: " + intent.getIntentName())
                    .withHint("请联系管理员申请所需权限");
        }

        // 5. 检查是否需要审批 (CRITICAL敏感度)
        if (aiIntentService.requiresApproval(intentCode)) {
            log.info("Intent '{}' requires approval, sensitivity level: {}",
                    intentCode, intent.getSensitivityLevel());
            // 这里可以触发审批流程，暂时记录日志
            // TODO: 集成 ApprovalChainService 触发审批
        }

        log.debug("Intent recognized: {} (category={}, sensitivity={}) for question: {}",
                intentCode, intent.getIntentCategory(), intent.getSensitivityLevel(), question);

        return intentCode;
    }

    /**
     * 从HTTP请求中提取用户角色
     */
    private String extractUserRoleFromRequest(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        try {
            String authorization = httpRequest.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                String token = authorization.substring(7);
                return jwtUtil.getRoleFromToken(token);
            }
        } catch (Exception e) {
            log.warn("Failed to extract user role from request: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 检查缓存
     */
    private AIAnalysisResult checkCache(String factoryId, String batchId, String type, String question) {
        if ("default".equals(type)) {
            return analysisResultRepository
                    .findFirstByFactoryIdAndBatchIdAndReportTypeOrderByCreatedAtDesc(
                            factoryId, batchId, "batch")
                    .filter(r -> r.getExpiresAt().isAfter(LocalDateTime.now()))
                    .orElse(null);
        }
        // followup不缓存，每次都调用AI
        return null;
    }

    /**
     * 检查配额
     */
    private void checkQuotaOrThrow(String factoryId, String questionType) {
        LocalDate weekStart = getWeekStart(LocalDate.now());
        AIQuotaUsage quota = quotaUsageRepository
                .findByFactoryIdAndWeekStart(factoryId, weekStart)
                .orElseGet(() -> createNewQuota(factoryId, weekStart));

        // 从数据库获取配额消耗规则
        int required = getQuotaCostFromConfig(factoryId, questionType);

        if (quota.getUsedCount() + required > quota.getQuotaLimit()) {
            throw new QuotaExceededException(
                    String.format("本周配额不足。已使用: %d/%d，需要: %d",
                            quota.getUsedCount(), quota.getQuotaLimit(), required));
        }
    }

    /**
     * 从数据库配置获取配额消耗次数
     * 优先使用工厂级别配置，否则使用全局默认配置
     */
    private int getQuotaCostFromConfig(String factoryId, String questionType) {
        List<AIQuotaConfig> configs = quotaConfigRepository
                .findByFactoryIdAndQuestionType(factoryId, questionType);

        if (!configs.isEmpty()) {
            // 返回第一个匹配的配置 (已按优先级排序)
            return configs.get(0).getQuotaCost();
        }

        // 如果没有工厂级别配置，查询全局默认配置
        return getGlobalDefaultQuotaCost(questionType);
    }

    /**
     * 获取全局默认配额成本 (从 factory_id='*' 的配置读取)
     * V-001 修复: 移除硬编码默认值，从数据库配置表读取
     */
    private int getGlobalDefaultQuotaCost(String questionType) {
        List<AIQuotaConfig> globalConfigs = quotaConfigRepository
                .findByFactoryIdAndQuestionType("*", questionType);

        if (!globalConfigs.isEmpty()) {
            return globalConfigs.get(0).getQuotaCost();
        }

        // 仅作为最后保底（理论上不应到达这里，因为初始化脚本已插入全局默认值）
        log.warn("未找到全局配额配置: questionType={}, 使用保底默认值1", questionType);
        return 1;
    }

    /**
     * 消耗配额
     */
    private void consumeQuota(String factoryId, int count) {
        LocalDate weekStart = getWeekStart(LocalDate.now());
        quotaUsageRepository.incrementUsedCount(factoryId, weekStart, count);
    }

    /**
     * 生成历史综合报告
     */
    @SuppressWarnings("unchecked")
    private String generateHistoricalReport(String factoryId, Long userId,
                                            LocalDateTime startDate, LocalDateTime endDate) {
        try {
            log.info("生成历史综合报告: factoryId={}, startDate={}, endDate={}",
                     factoryId, startDate, endDate);

            // 1. 获取历史批次数据
            List<Map<String, Object>> historicalBatches = processingService.getWeeklyBatchesCost(
                    factoryId, startDate, endDate
            );

            if (historicalBatches == null || historicalBatches.isEmpty()) {
                log.warn("没有历史批次数据: factoryId={}, startDate={}, endDate={}",
                         factoryId, startDate, endDate);
                return "该时间段内暂无生产数据";
            }

            // 2. 格式化为AI Prompt
            String promptMessage = formatHistoricalReportPrompt(historicalBatches, startDate, endDate);

            // 3. 构建数据
            Map<String, Object> historicalData = new HashMap<>();
            historicalData.put("reportType", "historical");
            historicalData.put("batches", historicalBatches);
            historicalData.put("periodStart", startDate);
            historicalData.put("periodEnd", endDate);

            // 4. 调用AI分析
            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId, userId, null, historicalData,
                    null, promptMessage, true, 50);

            // 5. 返回AI分析
            if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
                return (String) aiResult.get("aiAnalysis");
            } else {
                log.error("历史报告AI分析失败: {}", aiResult);
                throw new RuntimeException("历史报告生成失败");
            }
        } catch (Exception e) {
            log.error("生成历史综合报告失败: {}", e.getMessage(), e);
            throw new RuntimeException("历史报告生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用AI生成周报告
     */
    @SuppressWarnings("unchecked")
    private String callAIForWeeklyReport(String factoryId, List<Map<String, Object>> batches,
                                        LocalDate weekStart, LocalDate weekEnd) {
        try {
            // 1. 格式化周数据为AI Prompt
            String promptMessage = formatWeeklyReportPrompt(batches, weekStart, weekEnd);

            // 2. 构建虚拟costData
            Map<String, Object> weeklyData = new HashMap<>();
            weeklyData.put("reportType", "weekly");
            weeklyData.put("batches", batches);
            weeklyData.put("periodStart", weekStart.atStartOfDay());
            weeklyData.put("periodEnd", weekEnd.atTime(23, 59, 59));

            // 3. 调用AI分析
            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId,
                    null,  // 周报告没有单一batchId
                    weeklyData,
                    null,  // 新会话
                    promptMessage
            );

            // 4. 返回AI分析文本
            if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
                return (String) aiResult.get("aiAnalysis");
            } else {
                log.error("AI周报告生成失败: {}", aiResult);
                throw new RuntimeException("AI周报告生成失败");
            }
        } catch (Exception e) {
            log.error("调用AI生成周报告失败: {}", e.getMessage(), e);
            throw new RuntimeException("周报告生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用AI生成月报告
     */
    @SuppressWarnings("unchecked")
    private String callAIForMonthlyReport(String factoryId, List<Map<String, Object>> batches,
                                         LocalDate monthStart, LocalDate monthEnd) {
        try {
            // 1. 格式化月数据为AI Prompt
            String promptMessage = formatMonthlyReportPrompt(batches, monthStart, monthEnd);

            // 2. 构建虚拟costData
            Map<String, Object> monthlyData = new HashMap<>();
            monthlyData.put("reportType", "monthly");
            monthlyData.put("batches", batches);
            monthlyData.put("periodStart", monthStart.atStartOfDay());
            monthlyData.put("periodEnd", monthEnd.atTime(23, 59, 59));

            // 3. 调用AI分析
            Map<String, Object> aiResult = basicAIService.analyzeCost(
                    factoryId,
                    null,  // 月报告没有单一batchId
                    monthlyData,
                    null,  // 新会话
                    promptMessage
            );

            // 4. 返回AI分析文本
            if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
                return (String) aiResult.get("aiAnalysis");
            } else {
                log.error("AI月报告生成失败: {}", aiResult);
                throw new RuntimeException("AI月报告生成失败");
            }
        } catch (Exception e) {
            log.error("调用AI生成月报告失败: {}", e.getMessage(), e);
            throw new RuntimeException("月报告生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存分析结果
     */
    private AIAnalysisResult saveAnalysisResult(String factoryId, String batchId, String type,
                                               String analysis, String sessionId,
                                               MobileDTO.AICostAnalysisRequest request) {
        int expireDays = "batch".equals(type) ? 7 : 30;

        AIAnalysisResult result = AIAnalysisResult.builder()
                .factoryId(factoryId)
                .batchId(batchId)
                .reportType(type)
                .analysisText(analysis)
                .sessionId(sessionId)
                .expiresAt(LocalDateTime.now().plusDays(expireDays))
                .isAutoGenerated(false)
                .build();

        return analysisResultRepository.save(result);
    }

    /**
     * 记录审计日志
     */
    private void logAuditRecord(String factoryId, Long userId, MobileDTO.AICostAnalysisRequest request,
                               String questionType, boolean success, Integer quotaCost,
                               long responseTime, boolean cacheHit, HttpServletRequest httpRequest) {
        AIAuditLog log = AIAuditLog.builder()
                .factoryId(factoryId)
                .userId(userId)
                .batchId(request != null ? request.getBatchId() : null)
                .questionType(questionType)
                .question(request != null ? request.getQuestion() : null)
                .sessionId(request != null ? request.getSession_id() : null)
                .consumedQuota(quotaCost != null && quotaCost > 0)
                .quotaCost(quotaCost != null ? quotaCost : 0)
                .isSuccess(success)
                .responseTimeMs(responseTime)
                .cacheHit(cacheHit)
                .ipAddress(getClientIP(httpRequest))
                .userAgent(httpRequest != null ? httpRequest.getHeader("User-Agent") : null)
                .build();

        auditLogRepository.save(log);
    }

    /**
     * 从缓存构建响应
     */
    private MobileDTO.AICostAnalysisResponse buildResponseFromCache(AIAnalysisResult cached, String factoryId) {
        return MobileDTO.AICostAnalysisResponse.builder()
                .success(true)
                .analysis(cached.getAnalysisText())
                .sessionId(cached.getSessionId())
                .quota(getQuotaInfo(factoryId))
                .cacheHit(true)
                .processingTimeMs(0L)
                .generatedAt(cached.getCreatedAt())
                .expiresAt(cached.getExpiresAt())
                .build();
    }

    /**
     * 构建成功响应
     */
    private MobileDTO.AICostAnalysisResponse buildSuccessResponse(AIAnalysisResult result, String factoryId,
                                                                  String sessionId, Integer messageCount,
                                                                  long responseTime) {
        return MobileDTO.AICostAnalysisResponse.builder()
                .success(true)
                .analysis(result.getAnalysisText())
                .sessionId(sessionId)
                .messageCount(messageCount)
                .quota(getQuotaInfo(factoryId))
                .cacheHit(false)
                .processingTimeMs(responseTime)
                .generatedAt(result.getCreatedAt())
                .expiresAt(result.getExpiresAt())
                .build();
    }

    /**
     * 转换为报告摘要
     */
    private MobileDTO.AIReportSummary toReportSummary(AIAnalysisResult result) {
        String summary = result.getAnalysisText();
        if (summary != null && summary.length() > 200) {
            summary = summary.substring(0, 200) + "...";
        }

        return MobileDTO.AIReportSummary.builder()
                .id(result.getId())
                .batchId(result.getBatchId())
                .reportType(result.getReportType())
                .summaryText(summary)
                .periodStart(result.getPeriodStart())
                .periodEnd(result.getPeriodEnd())
                .createdAt(result.getCreatedAt())
                .expiresAt(result.getExpiresAt())
                .isAutoGenerated(result.getIsAutoGenerated())
                .build();
    }

    /**
     * 获取本周一日期
     */
    private LocalDate getWeekStart(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    /**
     * 创建新配额记录
     * V-001 修复: 从配置表读取默认周配额限制，而非硬编码
     */
    private AIQuotaUsage createNewQuota(String factoryId, LocalDate weekStart) {
        int weeklyLimit = getDefaultWeeklyLimit(factoryId);
        AIQuotaUsage newQuota = AIQuotaUsage.builder()
                .factoryId(factoryId)
                .weekStart(weekStart)
                .usedCount(0)
                .quotaLimit(weeklyLimit)
                .build();
        return quotaUsageRepository.save(newQuota);
    }

    /**
     * 获取默认周配额限制 (优先工厂级别，然后全局默认)
     * V-001 修复: 从数据库配置表读取，而非硬编码 100
     */
    private int getDefaultWeeklyLimit(String factoryId) {
        // 1. 先查工厂级别的 weeklyLimit 配置
        List<AIQuotaConfig> factoryConfigs = quotaConfigRepository
                .findByFactoryIdAndQuestionType(factoryId, "default");
        for (AIQuotaConfig config : factoryConfigs) {
            if (config.getWeeklyLimit() != null && config.getEnabled()) {
                return config.getWeeklyLimit();
            }
        }

        // 2. 再查全局默认配置
        List<AIQuotaConfig> globalConfigs = quotaConfigRepository
                .findByFactoryIdAndQuestionType("*", "default");
        for (AIQuotaConfig config : globalConfigs) {
            if (config.getWeeklyLimit() != null && config.getEnabled()) {
                return config.getWeeklyLimit();
            }
        }

        // 3. 仅作为最后保底
        log.warn("未找到周配额限制配置: factoryId={}, 使用保底默认值100", factoryId);
        return 100;
    }

    /**
     * 更新工厂的AI配额限制（平台管理员功能）
     *
     * @param factoryId 工厂ID
     * @param newQuotaLimit 新的配额限制
     */
    @Transactional
    public void updateQuotaLimit(String factoryId, Integer newQuotaLimit) {
        log.info("更新工厂AI配额: factoryId={}, newQuotaLimit={}", factoryId, newQuotaLimit);

        // 参数校验
        if (newQuotaLimit == null || newQuotaLimit < 0) {
            throw new IllegalArgumentException("配额限制不能为负数");
        }
        if (newQuotaLimit > 1000) {
            throw new IllegalArgumentException("配额限制不能超过1000");
        }

        // 获取当前周的配额记录
        LocalDate weekStart = getWeekStart(LocalDate.now());
        AIQuotaUsage quota = quotaUsageRepository
                .findByFactoryIdAndWeekStart(factoryId, weekStart)
                .orElseGet(() -> createNewQuota(factoryId, weekStart));

        // 更新配额限制
        int oldLimit = quota.getQuotaLimit();
        quota.setQuotaLimit(newQuotaLimit);
        quotaUsageRepository.save(quota);

        log.info("工厂AI配额已更新: factoryId={}, oldLimit={}, newLimit={}, usedCount={}",
                factoryId, oldLimit, newQuotaLimit, quota.getUsedCount());
    }

    /**
     * 获取客户端IP
     */
    private String getClientIP(HttpServletRequest request) {
        if (request == null) return null;

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * 格式化周报告Prompt
     */
    @SuppressWarnings("unchecked")
    private String formatWeeklyReportPrompt(List<Map<String, Object>> batches,
                                           LocalDate weekStart, LocalDate weekEnd) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本周成本分析报告】\n\n");
        sb.append("时间范围: ").append(weekStart).append(" 至 ").append(weekEnd).append("\n");
        sb.append("批次总数: ").append(batches.size()).append("\n\n");

        if (batches.isEmpty()) {
            sb.append("本周暂无生产数据\n");
            return sb.toString();
        }

        // 汇总统计
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal avgYieldRate = BigDecimal.ZERO;
        int completedCount = 0;

        for (Map<String, Object> batch : batches) {
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");
            if (costSummary != null) {
                Object totalCostObj = costSummary.get("totalCost");
                if (totalCostObj != null) {
                    totalCost = totalCost.add((BigDecimal) totalCostObj);
                }
            }

            Object quantityObj = batch.get("actualQuantity");
            if (quantityObj != null) {
                totalQuantity = totalQuantity.add((BigDecimal) quantityObj);
            }

            Object yieldObj = batch.get("yieldRate");
            if (yieldObj != null) {
                avgYieldRate = avgYieldRate.add((BigDecimal) yieldObj);
                completedCount++;
            }
        }

        sb.append("【汇总数据】\n");
        sb.append("总成本: ¥").append(String.format("%.2f", totalCost)).append("\n");
        sb.append("平均批次成本: ¥").append(String.format("%.2f",
                totalCost.divide(BigDecimal.valueOf(batches.size()), 2, java.math.RoundingMode.HALF_UP))).append("\n");
        sb.append("总产量: ").append(String.format("%.2f", totalQuantity)).append(" kg\n");
        if (completedCount > 0) {
            sb.append("平均良品率: ").append(String.format("%.2f",
                    avgYieldRate.divide(BigDecimal.valueOf(completedCount), 2, java.math.RoundingMode.HALF_UP)))
                    .append("%\n");
        }

        // 批次列表（显示前10个）
        sb.append("\n【批次详情】\n");
        int displayCount = Math.min(batches.size(), 10);
        for (int i = 0; i < displayCount; i++) {
            Map<String, Object> batch = batches.get(i);
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");

            sb.append((i + 1)).append(". ")
              .append(batch.get("batchNumber")).append(" - ")
              .append(batch.get("productName")).append("\n")
              .append("   状态: ").append(batch.get("status"))
              .append(", 成本: ¥").append(costSummary.get("totalCost"))
              .append(", 良品率: ").append(batch.get("yieldRate")).append("%\n");
        }

        if (batches.size() > 10) {
            sb.append("... 还有 ").append(batches.size() - 10).append(" 个批次\n");
        }

        sb.append("\n请基于以上数据，提供本周的成本分析和优化建议。");

        return sb.toString();
    }

    /**
     * 格式化月报告Prompt
     */
    @SuppressWarnings("unchecked")
    private String formatMonthlyReportPrompt(List<Map<String, Object>> batches,
                                            LocalDate monthStart, LocalDate monthEnd) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本月成本分析报告】\n\n");
        sb.append("时间范围: ").append(monthStart).append(" 至 ").append(monthEnd).append("\n");
        sb.append("批次总数: ").append(batches.size()).append("\n\n");

        if (batches.isEmpty()) {
            sb.append("本月暂无生产数据\n");
            return sb.toString();
        }

        // 汇总统计（与周报告相同逻辑）
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal avgYieldRate = BigDecimal.ZERO;
        int completedCount = 0;

        for (Map<String, Object> batch : batches) {
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");
            if (costSummary != null) {
                Object totalCostObj = costSummary.get("totalCost");
                if (totalCostObj != null) {
                    totalCost = totalCost.add((BigDecimal) totalCostObj);
                }
            }

            Object quantityObj = batch.get("actualQuantity");
            if (quantityObj != null) {
                totalQuantity = totalQuantity.add((BigDecimal) quantityObj);
            }

            Object yieldObj = batch.get("yieldRate");
            if (yieldObj != null) {
                avgYieldRate = avgYieldRate.add((BigDecimal) yieldObj);
                completedCount++;
            }
        }

        sb.append("【汇总数据】\n");
        sb.append("总成本: ¥").append(String.format("%.2f", totalCost)).append("\n");
        sb.append("平均批次成本: ¥").append(String.format("%.2f",
                totalCost.divide(BigDecimal.valueOf(batches.size()), 2, java.math.RoundingMode.HALF_UP))).append("\n");
        sb.append("总产量: ").append(String.format("%.2f", totalQuantity)).append(" kg\n");
        if (completedCount > 0) {
            sb.append("平均良品率: ").append(String.format("%.2f",
                    avgYieldRate.divide(BigDecimal.valueOf(completedCount), 2, java.math.RoundingMode.HALF_UP)))
                    .append("%\n");
        }

        // 批次列表
        sb.append("\n【批次详情】(显示前15个)\n");
        int displayCount = Math.min(batches.size(), 15);
        for (int i = 0; i < displayCount; i++) {
            Map<String, Object> batch = batches.get(i);
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");

            sb.append((i + 1)).append(". ")
              .append(batch.get("batchNumber")).append(" - ")
              .append(batch.get("productName")).append("\n")
              .append("   状态: ").append(batch.get("status"))
              .append(", 成本: ¥").append(costSummary.get("totalCost"))
              .append(", 良品率: ").append(batch.get("yieldRate")).append("%\n");
        }

        if (batches.size() > 15) {
            sb.append("... 还有 ").append(batches.size() - 15).append(" 个批次\n");
        }

        sb.append("\n请基于以上数据，提供本月的深度成本分析和战略性优化建议。");

        return sb.toString();
    }

    /**
     * 格式化历史报告Prompt
     */
    @SuppressWarnings("unchecked")
    private String formatHistoricalReportPrompt(List<Map<String, Object>> batches,
                                               LocalDateTime startDate, LocalDateTime endDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("【历史综合成本分析报告】\n\n");
        sb.append("时间范围: ").append(startDate.toLocalDate())
          .append(" 至 ").append(endDate.toLocalDate()).append("\n");
        sb.append("批次总数: ").append(batches.size()).append("\n\n");

        if (batches.isEmpty()) {
            sb.append("该时间段内暂无生产数据\n");
            return sb.toString();
        }

        // 统计和趋势分析
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal maxCost = BigDecimal.ZERO;
        BigDecimal minCost = null;
        BigDecimal avgYieldRate = BigDecimal.ZERO;
        int completedCount = 0;

        for (Map<String, Object> batch : batches) {
            Map<String, Object> costSummary = (Map<String, Object>) batch.get("costSummary");
            if (costSummary != null) {
                BigDecimal batchTotalCost = (BigDecimal) costSummary.get("totalCost");
                if (batchTotalCost != null) {
                    totalCost = totalCost.add(batchTotalCost);
                    if (batchTotalCost.compareTo(maxCost) > 0) {
                        maxCost = batchTotalCost;
                    }
                    if (minCost == null || batchTotalCost.compareTo(minCost) < 0) {
                        minCost = batchTotalCost;
                    }
                }
            }

            Object quantityObj = batch.get("actualQuantity");
            if (quantityObj != null) {
                totalQuantity = totalQuantity.add((BigDecimal) quantityObj);
            }

            Object yieldObj = batch.get("yieldRate");
            if (yieldObj != null) {
                avgYieldRate = avgYieldRate.add((BigDecimal) yieldObj);
                completedCount++;
            }
        }

        sb.append("【汇总统计】\n");
        sb.append("总批次数: ").append(batches.size()).append("\n");
        sb.append("总成本: ¥").append(String.format("%.2f", totalCost)).append("\n");
        sb.append("平均批次成本: ¥").append(String.format("%.2f",
                totalCost.divide(BigDecimal.valueOf(batches.size()), 2, java.math.RoundingMode.HALF_UP))).append("\n");
        sb.append("最高成本: ¥").append(String.format("%.2f", maxCost)).append("\n");
        if (minCost != null) {
            sb.append("最低成本: ¥").append(String.format("%.2f", minCost)).append("\n");
        }
        sb.append("总产量: ").append(String.format("%.2f", totalQuantity)).append(" kg\n");
        if (completedCount > 0) {
            sb.append("平均良品率: ").append(String.format("%.2f",
                    avgYieldRate.divide(BigDecimal.valueOf(completedCount), 2, java.math.RoundingMode.HALF_UP)))
                    .append("%\n");
        }

        sb.append("\n请基于历史数据进行深度分析，提供战略性的优化建议，包括：\n");
        sb.append("1. 成本变化趋势\n");
        sb.append("2. 主要成本驱动因素\n");
        sb.append("3. 成本优化的关键领域\n");
        sb.append("4. 长期改进计划\n");

        return sb.toString();
    }

    /**
     * 配额超额异常
     */
    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    // ========== AI 工厂批量初始化 ==========

    /**
     * AI 工厂批量初始化 - 根据自然语言描述生成完整配置
     *
     * 调用 Python AI 服务的 /api/ai/factory/batch-initialize 端点
     *
     * @param factoryId 工厂ID
     * @param factoryName 工厂名称
     * @param description 工厂描述
     * @param industryHint 行业提示
     * @param includeBusinessData 是否包含业务数据建议
     * @return AI 生成的完整配置
     */
    public Map<String, Object> batchInitializeFactory(
            String factoryId,
            String factoryName,
            String description,
            String industryHint,
            Boolean includeBusinessData) {

        log.info("AI工厂批量初始化: factoryId={}, description长度={}",
                factoryId, description != null ? description.length() : 0);

        try {
            // 1. 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("factory_description", description);
            requestBody.put("factory_id", factoryId);
            requestBody.put("factory_name", factoryName);
            if (industryHint != null) {
                requestBody.put("industry_hint", industryHint);
            }
            requestBody.put("include_business_data", includeBusinessData != null ? includeBusinessData : true);

            // 2. 调用 Python AI 服务
            String url = aiServiceUrl + "/api/ai/factory/batch-initialize";
            String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody);

            log.debug("调用AI批量初始化: url={}", url);

            URL urlObj = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30_000);  // 30秒连接超时
            connection.setReadTimeout(120_000);    // 2分钟读取超时（AI生成较慢）

            // 发送请求
            try (java.io.OutputStream os = connection.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // 读取响应
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readErrorStream(connection);
                log.error("AI批量初始化失败: responseCode={}, error={}", responseCode, errorBody);
                throw new RuntimeException("AI服务返回错误: " + responseCode);
            }

            String responseBody;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }

            // 3. 解析响应
            @SuppressWarnings("unchecked")
            Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(responseBody, Map.class);

            log.info("AI工厂批量初始化成功: factoryId={}, schemasCount={}",
                    factoryId, result.get("schemas") != null ? ((List<?>) result.get("schemas")).size() : 0);

            return result;

        } catch (Exception e) {
            log.error("AI工厂批量初始化失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            throw new RuntimeException("AI工厂初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取错误流
     */
    private String readErrorStream(HttpURLConnection connection) {
        try {
            java.io.InputStream errorStream = connection.getErrorStream();
            if (errorStream == null) return "No error body";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "Error reading error stream: " + e.getMessage();
        }
    }
}
