package com.cretas.aims.controller;

import com.cretas.aims.dto.AIRequestDTO;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.AIResponseDTO;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.ai.AIBusinessDataRequest;
import com.cretas.aims.dto.ai.AIBusinessDataResponse;
import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.ai.ProductionAIContext;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.AIContextService;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AI统一接口控制器
 *
 * 整合所有AI相关功能：
 * - 成本分析（批次/时间范围/对比）
 * - 配额管理
 * - 对话历史
 * - 报告管理
 * - 健康检查
 *
 * 统一路径结构：/api/mobile/{factoryId}/ai/{resource}/{action}
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-04
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai")
@Tag(name = "AI智能分析", description = "AI成本分析、配额管理、报告生成等统一接口")
@Validated
public class AIController {

    @Autowired
    private AIEnterpriseService aiEnterpriseService;

    @Autowired
    private AIAnalysisService basicAIService;

    @Autowired
    private MobileService mobileService;

    // --- Merged from AIContextController ---
    @Autowired
    private AIContextService aiContextService;

    // --- Merged from AIBusinessDataController ---
    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private ConversionRepository conversionRepository;

    // ========== 成本分析接口 ==========

    /**
     * AI批次成本分析
     *
     * 支持三种分析模式：
     * 1. 默认分析（无question）- 首次分析，消耗配额
     * 2. Follow-up对话（有question + sessionId）- 追问，少量消耗配额
     * 3. 历史综合报告（历史批次）- 深度分析，较多消耗配额
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/cost/batch")
    @Operation(summary = "AI批次成本分析",
               description = "对指定批次进行AI成本分析，支持默认分析、follow-up对话和历史综合报告")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> analyzeBatchCost(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "批次成本分析请求")
            MobileDTO.AICostAnalysisRequest request,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long userId = (long) mobileService.getUserFromToken(token).getId();

        log.info("AI批次成本分析: factoryId={}, userId={}, batchId={}, question={}",
                factoryId, userId, request.getBatchId(), request.getQuestion());

        // 调用企业级AI服务（包含配额管理、缓存、审计日志）
        MobileDTO.AICostAnalysisResponse response = aiEnterpriseService.analyzeCost(
                factoryId, userId, request, httpRequest);

        return ApiResponse.success(response);
    }

    /**
     * AI时间范围成本分析
     *
     * 分析指定时间段内的成本数据
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/cost/time-range")
    @Operation(summary = "AI时间范围成本分析",
               description = "分析指定时间范围内的成本数据，支持日/周/月等不同维度")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> analyzeTimeRangeCost(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "时间范围分析请求")
            AIRequestDTO.TimeRangeAnalysisRequest request,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long userId = (long) mobileService.getUserFromToken(token).getId();

        log.info("AI时间范围成本分析: factoryId={}, userId={}, startDate={}, endDate={}",
                factoryId, userId, request.getStartDate(), request.getEndDate());

        // 转换LocalDate为LocalDateTime
        LocalDateTime startDateTime = request.getStartDate().atStartOfDay();
        LocalDateTime endDateTime = request.getEndDate().atTime(23, 59, 59);

        // 调用企业级AI服务进行时间范围成本分析
        MobileDTO.AICostAnalysisResponse response = aiEnterpriseService.analyzeTimeRangeCost(
                factoryId,
                userId,
                startDateTime,
                endDateTime,
                request.getDimension(),
                request.getQuestion(),
                httpRequest
        );

        return ApiResponse.success(response);
    }

    /**
     * AI时间范围成本分析 - 流式响应版本 (SSE)
     *
     * 实时返回AI分析过程，包括思考过程和最终答案
     * 适用于需要实时展示分析进度的场景
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/cost/time-range/stream")
    @Operation(summary = "AI时间范围成本分析 - 流式响应",
               description = "流式返回AI分析过程，支持实时显示思考过程和答案")
    public SseEmitter analyzeTimeRangeCostStream(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "时间范围分析请求")
            AIRequestDTO.TimeRangeAnalysisRequest request,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long userId = (long) mobileService.getUserFromToken(token).getId();

        log.info("AI时间范围成本分析(流式): factoryId={}, userId={}, startDate={}, endDate={}, dimension={}",
                factoryId, userId, request.getStartDate(), request.getEndDate(), request.getDimension());

        // 转换LocalDate为LocalDateTime
        LocalDateTime startDateTime = request.getStartDate().atStartOfDay();
        LocalDateTime endDateTime = request.getEndDate().atTime(23, 59, 59);

        // 调用企业级AI服务进行流式时间范围成本分析
        // enableThinking=false 时关闭 AI 思考过程，可显著加速响应（推荐默认关闭）
        SseEmitter emitter = aiEnterpriseService.analyzeTimeRangeCostStream(
                factoryId,
                userId,
                startDateTime,
                endDateTime,
                request.getDimension(),
                request.getQuestion(),
                Boolean.TRUE.equals(request.getEnableThinking()),
                request.getThinkingBudget() != null ? request.getThinkingBudget() : 20,
                httpRequest
        );

        return emitter;
    }

    /**
     * AI批次对比分析
     *
     * 对比多个批次的成本效率
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/cost/compare")
    @Operation(summary = "AI批次对比分析",
               description = "对比2-5个批次的成本、效率、质量等指标")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> compareBatchCosts(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @Valid @RequestBody @Parameter(description = "批次对比分析请求")
            AIRequestDTO.ComparativeAnalysisRequest request,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long userId = (long) mobileService.getUserFromToken(token).getId();

        log.info("AI批次对比分析: factoryId={}, userId={}, batchIds={}",
                factoryId, userId, request.getBatchIds());

        // 调用企业级AI服务进行批次对比分析
        MobileDTO.AICostAnalysisResponse response = aiEnterpriseService.compareBatchCosts(
                factoryId, userId, request.getBatchIds(), request.getQuestion(), httpRequest);

        return ApiResponse.success(response);
    }

    // ========== 配额管理接口 ==========

    /**
     * 查询AI配额信息
     *
     * 统一配额查询接口，替代原有的多个重复端点
     */
    @GetMapping("/quota")
    @Operation(summary = "查询AI配额信息",
               description = "获取工厂的AI配额使用情况、剩余额度、使用记录等")
    public ApiResponse<MobileDTO.AIQuotaInfo> getQuotaInfo(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            HttpServletRequest httpRequest) {

        log.info("查询AI配额信息: factoryId={}", factoryId);

        // 调用AIEnterpriseService获取配额信息
        MobileDTO.AIQuotaInfo quotaInfo = aiEnterpriseService.getQuotaInfo(factoryId);

        return ApiResponse.success(quotaInfo);
    }

    /**
     * 更新AI配额（仅供平台管理员使用）
     */
    @RequirePermission({"analytics:read_write"})
    @PutMapping("/quota")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "更新AI配额",
               description = "平台管理员更新工厂的AI配额（仅限平台超级管理员）")
    public ApiResponse<Void> updateQuota(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "新配额限制") Integer newQuotaLimit,
            HttpServletRequest httpRequest) {

        // 从Token获取用户信息
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Object user = mobileService.getUserFromToken(token);

        log.info("更新AI配额请求: factoryId={}, newQuotaLimit={}, user={}",
                factoryId, newQuotaLimit, user.getClass().getSimpleName());

        // 调用服务更新配额
        aiEnterpriseService.updateQuotaLimit(factoryId, newQuotaLimit);

        log.info("AI配额更新成功: factoryId={}, newQuotaLimit={}", factoryId, newQuotaLimit);

        return ApiResponse.success();
    }

    // ========== 对话管理接口 ==========

    /**
     * 获取AI对话历史列表
     */
    @GetMapping("/conversations")
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    @Operation(summary = "获取AI对话历史列表",
               description = "获取工厂的所有AI对话会话列表")
    public ApiResponse<List<AIResponseDTO.ConversationResponse>> getConversationList(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "10") @Parameter(description = "限制数量") Integer limit) {

        return ApiResponse.error(501, "FEATURE_NOT_AVAILABLE");
    }

    /**
     * 获取AI对话历史
     */
    @GetMapping("/conversations/{sessionId}")
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    @Operation(summary = "获取AI对话历史",
               description = "获取指定会话的完整对话历史记录")
    public ApiResponse<AIResponseDTO.ConversationResponse> getConversation(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId) {

        return ApiResponse.error(501, "FEATURE_NOT_AVAILABLE");
    }

    /**
     * 关闭AI对话会话
     */
    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/conversations/{sessionId}")
    @Operation(summary = "关闭AI对话会话",
               description = "结束指定的AI对话会话")
    public ApiResponse<Void> closeConversation(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId) {

        log.info("关闭AI对话会话: factoryId={}, sessionId={}", factoryId, sessionId);

        // LLM服务会自动管理会话生命周期
        // 会话超时后会自动清理，无需额外操作
        // 这里仅记录日志用于审计追踪

        log.info("AI会话已标记关闭（LLM自动管理）: factoryId={}, sessionId={}",
                factoryId, sessionId);

        return ApiResponse.success();
    }

    // ========== 报告管理接口 ==========

    /**
     * 获取AI报告列表
     *
     * 统一报告查询接口，支持按类型和时间范围筛选
     */
    @GetMapping("/reports")
    @Operation(summary = "获取AI报告列表",
               description = "获取工厂的AI成本分析报告列表，支持按类型和时间筛选")
    public ApiResponse<MobileDTO.AIReportListResponse> getReports(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "报告类型") String reportType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @Parameter(description = "开始日期") LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @Parameter(description = "结束日期") LocalDateTime endDate) {

        log.info("获取AI报告列表: factoryId={}, reportType={}", factoryId, reportType);

        // 构建请求对象
        MobileDTO.AIReportListRequest request = MobileDTO.AIReportListRequest.builder()
                .reportType(reportType)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        // 调用企业级AI服务获取报告列表
        MobileDTO.AIReportListResponse reports = aiEnterpriseService.getReportList(factoryId, request);

        return ApiResponse.success(reports);
    }

    /**
     * 获取AI报告详情
     */
    @GetMapping("/reports/{reportId}")
    @Operation(summary = "获取AI报告详情",
               description = "获取指定AI报告的完整内容")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> getReportDetail(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "报告ID") Long reportId) {

        log.info("获取AI报告详情: factoryId={}, reportId={}", factoryId, reportId);

        // 调用企业级AI服务获取报告详情
        MobileDTO.AICostAnalysisResponse response = aiEnterpriseService.getReportDetail(factoryId, reportId);

        return ApiResponse.success(response);
    }

    /**
     * 生成新报告（手动触发）
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/reports/generate")
    @Operation(summary = "生成AI报告",
               description = "手动触发生成AI分析报告（周报/月报等）")
    public ApiResponse<MobileDTO.AICostAnalysisResponse> generateReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @Valid @RequestBody @Parameter(description = "报告生成请求")
            AIRequestDTO.ReportGenerationRequest request,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long userId = (long) mobileService.getUserFromToken(token).getId();

        log.info("生成AI报告: factoryId={}, userId={}, reportType={}",
                factoryId, userId, request.getReportType());

        // 根据报告类型路由到相应的生成方法
        MobileDTO.AICostAnalysisResponse response;

        switch (request.getReportType()) {
            case "batch":
                // 批次报告 - 路由到批次成本分析
                if (request.getBatchId() == null) {
                    throw new IllegalArgumentException("批次报告需要提供batchId");
                }
                MobileDTO.AICostAnalysisRequest batchRequest = new MobileDTO.AICostAnalysisRequest();
                batchRequest.setBatchId(String.valueOf(request.getBatchId()));
                batchRequest.setReportType("batch");
                response = aiEnterpriseService.analyzeCost(factoryId, userId, batchRequest, httpRequest);
                break;

            case "weekly":
            case "monthly":
            case "custom":
                // 时间范围报告 - 路由到时间范围成本分析
                if (request.getStartDate() == null || request.getEndDate() == null) {
                    throw new IllegalArgumentException("时间范围报告需要提供startDate和endDate");
                }
                response = aiEnterpriseService.analyzeTimeRangeCost(
                        factoryId,
                        userId,
                        request.getStartDate().atStartOfDay(),
                        request.getEndDate().atTime(23, 59, 59),
                        request.getReportType(),
                        null,  // 无自定义问题
                        httpRequest
                );
                break;

            default:
                throw new IllegalArgumentException("不支持的报告类型: " + request.getReportType());
        }

        log.info("AI报告生成完成: factoryId={}, reportType={}, reportId={}",
                factoryId, request.getReportType(), response.getReportId());

        return ApiResponse.success(response);
    }

    // ========== 健康检查接口 ==========

    /**
     * AI服务健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "AI服务健康检查",
               description = "检查AI服务和LLM API的可用性")
    public ApiResponse<AIResponseDTO.HealthCheckResponse> checkHealth(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {

        log.info("AI服务健康检查: factoryId={}", factoryId);

        // 调用基础AI服务进行健康检查
        Map<String, Object> healthData = basicAIService.healthCheck();

        AIResponseDTO.HealthCheckResponse response = AIResponseDTO.HealthCheckResponse.builder()
                .status((Boolean) healthData.get("available") ? "healthy" : "unavailable")
                .llmAvailable((Boolean) healthData.get("available"))
                .responseTime(100L)  // 简化处理
                .lastCheckTime(LocalDateTime.now())
                .errorMessage(healthData.get("error") != null ?
                        healthData.get("error").toString() : null)
                .build();

        return ApiResponse.success(response);
    }

    // ========== 员工AI分析接口 ==========

    /**
     * 员工AI综合分析
     *
     * 分析维度：
     * 1. 考勤表现 - 出勤率、迟到、早退、缺勤
     * 2. 工时效率 - 日均工时、加班、工作类型分布
     * 3. 生产贡献 - 参与批次、产量、良品率
     * 4. 技能分布 - 各工序参与度和熟练程度
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/employee/{employeeId}")
    @Operation(summary = "员工AI综合分析",
               description = "对指定员工进行AI综合绩效分析，包含考勤、工时、生产贡献、技能等多维度")
    public ApiResponse<AIResponseDTO.EmployeeAnalysisResponse> analyzeEmployee(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "员工ID") Long employeeId,
            @RequestParam(defaultValue = "90") @Parameter(description = "分析天数") Integer days,
            @RequestParam(required = false) @Parameter(description = "追问问题") String question,
            @RequestParam(required = false) @Parameter(description = "会话ID") String sessionId,
            HttpServletRequest httpRequest) {

        // 从Token获取用户ID
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long requesterId = (long) mobileService.getUserFromToken(token).getId();

        log.info("员工AI综合分析: factoryId={}, employeeId={}, days={}, requester={}",
                factoryId, employeeId, days, requesterId);

        // 调用AI服务进行员工分析
        AIResponseDTO.EmployeeAnalysisResponse response = basicAIService.analyzeEmployee(
                factoryId, requesterId, employeeId, days, question, sessionId);

        return ApiResponse.success(response);
    }

    /**
     * 员工AI追问
     *
     * 基于已有分析结果进行追问
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/analysis/employee/{employeeId}/followup")
    @Operation(summary = "员工AI追问",
               description = "基于已有的员工分析结果进行追问")
    public ApiResponse<AIResponseDTO.EmployeeAnalysisResponse> employeeFollowUp(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "员工ID") Long employeeId,
            @RequestParam @Parameter(description = "会话ID") String sessionId,
            @RequestBody @Parameter(description = "追问内容") Map<String, String> body,
            HttpServletRequest httpRequest) {

        String question = body.get("question");
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("追问内容不能为空");
        }

        log.info("员工AI追问: factoryId={}, employeeId={}, sessionId={}, question={}",
                factoryId, employeeId, sessionId, question);

        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        Long requesterId = (long) mobileService.getUserFromToken(token).getId();

        // 调用AI服务进行追问分析
        AIResponseDTO.EmployeeAnalysisResponse response = basicAIService.analyzeEmployee(
                factoryId, requesterId, employeeId, 90, question, sessionId);

        return ApiResponse.success(response);
    }

    // ========== AI 上下文接口 (merged from AIContextController) ==========

    /**
     * 获取生产统计 AI 上下文
     *
     * 返回指定时间范围内的生产统计数据，包含：
     * - 按产品分组的产量统计
     * - BOM 理论成本 vs 实际成本对比
     * - 成本结构分析
     * - 产量/成本排名
     */
    @GetMapping("/context/production-summary")
    @Operation(summary = "获取生产统计 AI 上下文",
               description = "返回预计算的生产统计数据，用于AI分析时减少Token消耗")
    public ApiResponse<ProductionAIContext> getProductionContext(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期，默认本周开始") LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期，默认今天") LocalDate endDate) {

        log.info("获取生产统计AI上下文: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        if (endDate == null) {
            endDate = LocalDate.now();
        }
        if (startDate == null) {
            startDate = endDate.minusDays(6);
        }

        ProductionAIContext context = aiContextService.buildProductionContext(
                factoryId, startDate, endDate);

        return ApiResponse.success(context);
    }

    /**
     * 获取产品成本 AI 上下文
     *
     * 返回指定产品的 BOM 成本与实际成本对比分析
     */
    @GetMapping("/context/cost-analysis")
    @Operation(summary = "获取产品成本 AI 上下文",
               description = "返回产品的BOM理论成本与实际成本对比分析")
    public ApiResponse<CostAIContext> getCostContext(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "产品类型ID", example = "PT001") String productTypeId,
            @RequestParam(required = false, defaultValue = "10")
            @Parameter(description = "分析的最近批次数") Integer recentBatchCount) {

        if (productTypeId == null || productTypeId.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少必需参数: productTypeId (产品类型ID)");
        }

        log.info("获取产品成本AI上下文: factoryId={}, productTypeId={}, recentBatchCount={}",
                factoryId, productTypeId, recentBatchCount);

        CostAIContext context = aiContextService.buildCostContext(
                factoryId, productTypeId.trim(), recentBatchCount);

        return ApiResponse.success(context);
    }

    /**
     * 获取成本差异汇总
     *
     * 返回工厂所有产品的 BOM vs 实际成本差异汇总
     */
    @GetMapping("/context/cost-variance-summary")
    @Operation(summary = "获取成本差异汇总",
               description = "返回所有产品的BOM vs 实际成本差异，用于异常检测")
    public ApiResponse<?> getCostVarianceSummary(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {

        log.info("获取成本差异汇总: factoryId={}", factoryId);

        return ApiResponse.success(aiContextService.getCostVarianceSummary(factoryId));
    }

    // ========== AI 业务数据初始化接口 (merged from AIBusinessDataController) ==========

    /**
     * 初始化业务数据
     * 根据 AI 建议的数据创建产品类型、原材料类型和转换率配置
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/business-data/initialize")
    @Operation(summary = "初始化业务数据", description = "根据AI建议的数据批量创建产品类型、原材料类型和转换率配置")
    public ResponseEntity<AIBusinessDataResponse> initializeBusinessData(
            @Parameter(description = "工厂ID", example = "F001") @PathVariable String factoryId,
            @RequestBody AIBusinessDataRequest request,
            @RequestAttribute("userId") Long userId) {

        log.info("开始 AI 业务数据初始化 - 工厂: {}, 用户: {}", factoryId, userId);

        try {
            int productTypesCreated = 0;
            int productTypesSkipped = 0;
            int materialTypesCreated = 0;
            int materialTypesSkipped = 0;
            int conversionsCreated = 0;
            int conversionsSkipped = 0;

            List<String> createdProductTypeIds = new ArrayList<>();
            List<String> createdMaterialTypeIds = new ArrayList<>();
            List<String> createdConversionIds = new ArrayList<>();

            Map<String, String> productTypeCodeToId = new HashMap<>();
            Map<String, String> materialTypeCodeToId = new HashMap<>();

            // 1. 创建产品类型
            if (request.getProductTypes() != null) {
                for (AIBusinessDataRequest.ProductTypeData ptData : request.getProductTypes()) {
                    if (productTypeRepository.existsByFactoryIdAndCode(factoryId, ptData.getCode())) {
                        log.info("产品类型已存在，跳过: {}", ptData.getCode());
                        productTypesSkipped++;
                        productTypeRepository.findByFactoryIdAndCode(factoryId, ptData.getCode())
                                .ifPresent(pt -> productTypeCodeToId.put(ptData.getCode(), pt.getId()));
                        continue;
                    }

                    ProductType productType = new ProductType();
                    productType.setId(UUID.randomUUID().toString());
                    productType.setFactoryId(factoryId);
                    productType.setCode(ptData.getCode());
                    productType.setName(ptData.getName());
                    productType.setCategory(ptData.getCategory() != null ? ptData.getCategory() : "通用");
                    productType.setUnit(ptData.getUnit() != null ? ptData.getUnit() : "kg");
                    productType.setNotes(ptData.getDescription());
                    productType.setProductionTimeMinutes(ptData.getProductionTimeMinutes());
                    productType.setShelfLifeDays(ptData.getShelfLifeDays());
                    productType.setIsActive(true);
                    productType.setCreatedBy(userId);

                    ProductType saved = productTypeRepository.save(productType);
                    createdProductTypeIds.add(saved.getId());
                    productTypeCodeToId.put(ptData.getCode(), saved.getId());
                    productTypesCreated++;
                    log.info("创建产品类型: {} - {}", ptData.getCode(), ptData.getName());
                }
            }

            // 2. 创建原材料类型
            if (request.getMaterialTypes() != null && !request.getMaterialTypes().isEmpty()) {
                throw new BusinessException(400,
                        "AI批量建档暂不接受原料类型：每个原料必须先选择有效的L1、L2、L3编码分类")
                        .withHint("请先在原料类型字典完成16位编码分类，再让AI引用已建原料")
                        .withHintTarget("materialTypes")
                        .withSeverity("BLOCKING");
            }

            // 3. 创建转换率配置
            if (request.getConversionRates() != null) {
                for (AIBusinessDataRequest.ConversionRateData crData : request.getConversionRates()) {
                    String ptId = productTypeCodeToId.get(crData.getProductTypeCode());
                    String mtId = materialTypeCodeToId.get(crData.getMaterialTypeCode());

                    if (ptId == null || mtId == null) {
                        log.warn("转换率配置缺少关联类型: 产品={}, 原材料={}",
                                crData.getProductTypeCode(), crData.getMaterialTypeCode());
                        conversionsSkipped++;
                        continue;
                    }

                    if (conversionRepository.existsByFactoryIdAndMaterialTypeIdAndProductTypeId(
                            factoryId, mtId, ptId)) {
                        log.info("转换率配置已存在，跳过: {} -> {}", crData.getMaterialTypeCode(), crData.getProductTypeCode());
                        conversionsSkipped++;
                        continue;
                    }

                    MaterialProductConversion conversion = new MaterialProductConversion();
                    conversion.setId(UUID.randomUUID().toString());
                    conversion.setFactoryId(factoryId);
                    conversion.setMaterialTypeId(mtId);
                    conversion.setProductTypeId(ptId);
                    conversion.setConversionRate(BigDecimal.valueOf(crData.getRate() != null ? crData.getRate() : 1.0));
                    conversion.setWastageRate(BigDecimal.valueOf(crData.getWastageRate() != null ? crData.getWastageRate() : 0.0));
                    conversion.setNotes(crData.getDescription());
                    conversion.setIsActive(true);
                    conversion.setCreatedBy(userId);

                    MaterialProductConversion saved = conversionRepository.save(conversion);
                    createdConversionIds.add(saved.getId());
                    conversionsCreated++;
                    log.info("创建转换率配置: {} -> {}, 转换率: {}",
                            crData.getMaterialTypeCode(), crData.getProductTypeCode(), crData.getRate());
                }
            }

            AIBusinessDataResponse.CreationStats stats = AIBusinessDataResponse.CreationStats.builder()
                    .productTypesCreated(productTypesCreated)
                    .productTypesSkipped(productTypesSkipped)
                    .materialTypesCreated(materialTypesCreated)
                    .materialTypesSkipped(materialTypesSkipped)
                    .conversionsCreated(conversionsCreated)
                    .conversionsSkipped(conversionsSkipped)
                    .build();

            AIBusinessDataResponse response = AIBusinessDataResponse.builder()
                    .success(true)
                    .message(String.format("初始化完成: 创建 %d 个产品类型, %d 个原材料类型, %d 个转换率配置",
                            productTypesCreated, materialTypesCreated, conversionsCreated))
                    .stats(stats)
                    .createdProductTypeIds(createdProductTypeIds)
                    .createdMaterialTypeIds(createdMaterialTypeIds)
                    .createdConversionIds(createdConversionIds)
                    .build();

            log.info("AI 业务数据初始化完成 - 工厂: {}, 统计: {}", factoryId, stats);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("AI 业务数据初始化失败 - 工厂: {}", factoryId, e);
            return ResponseEntity.ok(AIBusinessDataResponse.builder()
                    .success(false)
                    .message("初始化失败: " + ErrorSanitizer.sanitize(e))
                    .build());
        }
    }

    /**
     * 预览业务数据
     * 检查哪些数据会被创建，哪些会被跳过
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/business-data/preview")
    @Operation(summary = "预览业务数据", description = "预检AI建议的业务数据，返回哪些数据会被创建、哪些会被跳过")
    public ResponseEntity<Map<String, Object>> previewBusinessData(
            @Parameter(description = "工厂ID", example = "F001") @PathVariable String factoryId,
            @RequestBody AIBusinessDataRequest request) {

        log.info("预览 AI 业务数据 - 工厂: {}", factoryId);

        Map<String, Object> preview = new HashMap<>();

        List<Map<String, Object>> productTypePreview = new ArrayList<>();
        if (request.getProductTypes() != null) {
            for (AIBusinessDataRequest.ProductTypeData ptData : request.getProductTypes()) {
                Map<String, Object> item = new HashMap<>();
                item.put("code", ptData.getCode());
                item.put("name", ptData.getName());
                item.put("exists", productTypeRepository.existsByFactoryIdAndCode(factoryId, ptData.getCode()));
                productTypePreview.add(item);
            }
        }
        preview.put("productTypes", productTypePreview);

        List<Map<String, Object>> materialTypePreview = new ArrayList<>();
        if (request.getMaterialTypes() != null) {
            for (AIBusinessDataRequest.MaterialTypeData mtData : request.getMaterialTypes()) {
                Map<String, Object> item = new HashMap<>();
                item.put("code", mtData.getCode());
                item.put("name", mtData.getName());
                item.put("exists", rawMaterialTypeRepository.existsByFactoryIdAndCode(factoryId, mtData.getCode()));
                materialTypePreview.add(item);
            }
        }
        preview.put("materialTypes", materialTypePreview);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProductTypes", request.getProductTypes() != null ? request.getProductTypes().size() : 0);
        stats.put("totalMaterialTypes", request.getMaterialTypes() != null ? request.getMaterialTypes().size() : 0);
        stats.put("totalConversions", request.getConversionRates() != null ? request.getConversionRates().size() : 0);
        preview.put("stats", stats);

        return ResponseEntity.ok(preview);
    }
}
