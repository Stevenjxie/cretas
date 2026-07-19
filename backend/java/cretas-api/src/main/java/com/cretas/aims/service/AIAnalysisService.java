package com.cretas.aims.service;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.AIResponseDTO;
import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.ai.ProductionAIContext;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.cretas.aims.repository.QualityInspectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AI成本分析服务
 * 负责调用AI服务进行批次成本分析
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class AIAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AIAnalysisService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TimeClockRecordRepository timeClockRecordRepository;

    @Autowired
    private EmployeeWorkSessionRepository employeeWorkSessionRepository;

    @Autowired
    private BatchWorkSessionRepository batchWorkSessionRepository;

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Autowired
    private PythonLLMClient pythonLLMClient;

    @Autowired
    private DashScopeConfig dashScopeConfig;

    @Autowired
    private PythonSmartBIClient pythonSmartBIClient;

    @Lazy
    @Autowired(required = false)
    private AIContextService aiContextService;

    /**
     * 判断是否使用 DashScope 直接调用
     */
    private boolean shouldUseDashScopeDirect() {
        return dashScopeConfig != null
                && dashScopeConfig.isAvailable()
                && dashScopeConfig.getMigration().isCostAnalysis();
    }

    /**
     * 调用AI分析批次成本
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param costData 成本数据
     * @param sessionId 会话ID（可选，用于多轮对话）
     * @param customMessage 自定义问题（可选）
     * @return AI分析结果
     */
    public Map<String, Object> analyzeCost(String factoryId, String batchId,
                                           Map<String, Object> costData,
                                           String sessionId,
                                           String customMessage) {
        // 默认开启思考模式
        return analyzeCost(factoryId, batchId, costData, sessionId, customMessage, true, 50);
    }

    /**
     * 调用AI分析批次成本（支持思考模式）
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param costData 成本数据
     * @param sessionId 会话ID（可选，用于多轮对话）
     * @param customMessage 自定义问题（可选）
     * @param enableThinking 是否启用思考模式（默认true）
     * @param thinkingBudget 思考预算 10-100（默认50）
     * @return AI分析结果
     */
    public Map<String, Object> analyzeCost(String factoryId, String batchId,
                                           Map<String, Object> costData,
                                           String sessionId,
                                           String customMessage,
                                           Boolean enableThinking,
                                           Integer thinkingBudget) {
        return analyzeCost(factoryId, null, batchId, costData, sessionId,
                customMessage, enableThinking, thinkingBudget);
    }

    public Map<String, Object> analyzeCost(String factoryId, Long userId, String batchId,
                                           Map<String, Object> costData,
                                           String sessionId,
                                           String customMessage,
                                           Boolean enableThinking,
                                           Integer thinkingBudget) {
        // 路由：优先使用 DashScope 直接调用
        if (shouldUseDashScopeDirect()) {
            log.info("[DashScope Direct] 使用 DashScope 直接调用进行成本分析: factoryId={}, batchId={}", factoryId, batchId);
            try {
                return analyzeCostDirect(factoryId, batchId, costData, customMessage, enableThinking, thinkingBudget);
            } catch (Exception e) {
                log.warn("[DashScope Direct] 直接调用失败，回退到 Python 服务: {}", e.getMessage());
                // 回退到 Python 服务
            }
        }

        return analyzeCostViaPython(factoryId, userId, batchId, costData, sessionId,
                customMessage, enableThinking, thinkingBudget);
    }

    /**
     * 通过 DashScope 直接调用进行成本分析
     */
    private Map<String, Object> analyzeCostDirect(String factoryId, String batchId,
                                                   Map<String, Object> costData,
                                                   String customMessage,
                                                   Boolean enableThinking,
                                                   Integer thinkingBudget) {
        // 1. 格式化成本数据为 AI 提示词
        String userMessage = customMessage != null && !customMessage.trim().isEmpty()
                ? customMessage
                : formatCostDataForAI(factoryId, batchId, costData);

        // 1.1 注入预计算的上下文以减少 Token 消耗
        String preComputedContext = buildPreComputedContext(factoryId, costData);
        if (preComputedContext != null && !preComputedContext.isEmpty()) {
            userMessage = preComputedContext + "\n\n" + userMessage;
            log.info("[DashScope Direct] 已注入预计算上下文: factoryId={}", factoryId);
        }

        // 2. 构建系统提示词
        String systemPrompt = buildCostAnalysisSystemPrompt();

        // 3. 调用 DashScope
        boolean useThinking = enableThinking != null ? enableThinking : true;
        int budget = thinkingBudget != null ? thinkingBudget : dashScopeConfig.getDefaultThinkingBudget();

        ChatCompletionResponse response;
        if (useThinking && dashScopeConfig.isThinkingEnabled()) {
            log.info("[DashScope Direct] 使用思考模式分析: budget={}", budget);
            response = pythonLLMClient.chatWithThinking(systemPrompt, userMessage, budget);
        } else {
            log.info("[DashScope Direct] 使用普通模式分析");
            String content = pythonLLMClient.chat(systemPrompt, userMessage);
            response = new ChatCompletionResponse();
            ChatCompletionResponse.Message message = new ChatCompletionResponse.Message();
            message.setRole("assistant");
            message.setContent(content);
            ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
            choice.setMessage(message);
            response.setChoices(List.of(choice));
        }

        // 4. 处理响应
        if (response.hasError()) {
            throw new RuntimeException("DashScope API 错误: " + response.getErrorMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("aiAnalysis", response.getContent());

        // 如果有思考过程
        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            ChatCompletionResponse.Message msg = response.getChoices().get(0).getMessage();
            if (msg != null && msg.getReasoningContent() != null) {
                result.put("reasoningContent", msg.getReasoningContent());
                result.put("thinkingEnabled", true);
            }
        }

        result.put("sessionId", factoryId + "_batch_" + batchId + "_" + System.currentTimeMillis());
        result.put("directCall", true);  // 标记为直接调用

        log.info("[DashScope Direct] 成本分析完成: factoryId={}, batchId={}", factoryId, batchId);
        return result;
    }

    /**
     * 构建成本分析系统提示词
     */
    private String buildCostAnalysisSystemPrompt() {
        return """
            你是一位专业的食品加工行业成本分析师，擅长分析生产批次的成本结构和效率问题。

            分析要求：
            1. 仔细分析提供的成本数据，识别成本异常点
            2. 对比行业基准，评估各项指标是否达标
            3. 从原材料、人工、设备、质量、时间五个维度进行分析
            4. 给出具体可行的优化建议，包括预估节省金额
            5. 识别潜在风险并提出预警

            输出格式：
            1. 成本概览 - 总成本、结构占比
            2. 异常分析 - 偏离基准的指标
            3. 问题诊断 - 根本原因分析
            4. 优化建议 - 具体措施和预期效果
            5. 风险预警 - 需要关注的问题

            请用简洁专业的中文回复，重点突出关键发现和可执行建议。
            """;
    }

    /**
     * 构建精简版成本分析系统提示词（SSE 流式用，减少 Token）
     */
    private String buildCompactSystemPrompt() {
        return """
            食品成本分析师。根据数据识别异常，给出优化建议。
            基准：原料50-60%，人工15-25%，设备10-15%；良品率>95%，效率>85%。
            输出：1.概览 2.问题 3.建议（含节省金额）
            简洁回复，突出关键发现。
            """;
    }

    /**
     * 构建预计算上下文
     *
     * 从 AIContextService 获取预计算的成本/生产数据，注入到 Prompt 中，
     * 减少 LLM Token 消耗（数据聚合已由后端完成）。
     *
     * @param factoryId 工厂ID
     * @param costData 成本数据（用于提取产品类型ID）
     * @return 格式化的预计算上下文字符串
     */
    @SuppressWarnings("unchecked")
    private String buildPreComputedContext(String factoryId, Map<String, Object> costData) {
        if (aiContextService == null) {
            log.debug("[AIContext] AIContextService 未注入，跳过预计算上下文");
            return null;
        }

        StringBuilder context = new StringBuilder();

        try {
            // 1. 尝试从 costData 提取产品类型ID
            String productTypeId = extractProductTypeId(costData);

            // 2. 如果有产品类型ID，获取成本上下文
            if (productTypeId != null && !productTypeId.isEmpty()) {
                CostAIContext costContext = aiContextService.buildCostContext(factoryId, productTypeId, 10);
                if (costContext != null) {
                    String costPrompt = aiContextService.formatCostContextForPrompt(costContext);
                    if (costPrompt != null && !costPrompt.isEmpty()) {
                        context.append("【预计算成本分析】\n");
                        context.append(costPrompt);
                        context.append("\n");
                        log.debug("[AIContext] 已添加成本上下文: productTypeId={}", productTypeId);
                    }
                }
            }

            // 3. 获取生产上下文（最近30天的数据）
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(30);
            ProductionAIContext productionContext = aiContextService.buildProductionContext(
                    factoryId, startDate, endDate);

            if (productionContext != null) {
                String productionPrompt = aiContextService.formatProductionContextForPrompt(productionContext);
                if (productionPrompt != null && !productionPrompt.isEmpty()) {
                    context.append("【预计算生产统计】\n");
                    context.append(productionPrompt);
                    log.debug("[AIContext] 已添加生产上下文: factoryId={}, 产品数={}",
                            factoryId, productionContext.getProductCount());
                }
            }

        } catch (Exception e) {
            log.warn("[AIContext] 构建预计算上下文失败: {}", e.getMessage());
            // 失败不影响主流程，返回空上下文
        }

        return context.length() > 0 ? context.toString() : null;
    }

    /**
     * 从成本数据中提取产品类型ID
     */
    @SuppressWarnings("unchecked")
    private String extractProductTypeId(Map<String, Object> costData) {
        if (costData == null) {
            return null;
        }

        // 尝试从 batchInfo 中获取
        Map<String, Object> batchInfo = (Map<String, Object>) costData.get("batchInfo");
        if (batchInfo != null) {
            Object productTypeId = batchInfo.get("productTypeId");
            if (productTypeId != null) {
                return productTypeId.toString();
            }
        }

        // 尝试从 batch 中获取（兼容旧格式）
        Map<String, Object> batch = (Map<String, Object>) costData.get("batch");
        if (batch != null) {
            Object productTypeId = batch.get("productTypeId");
            if (productTypeId != null) {
                return productTypeId.toString();
            }
        }

        // 直接获取
        Object productTypeId = costData.get("productTypeId");
        return productTypeId != null ? productTypeId.toString() : null;
    }

    /**
     * 注入生产上下文到 Prompt
     *
     * 用于在 LLM 调用前自动添加预计算的生产统计数据，
     * 提供给 AI 更多上下文信息以提高分析质量。
     *
     * @param factoryId 工厂ID
     * @param originalPrompt 原始 Prompt
     * @param days 统计天数（默认30天）
     * @return 注入上下文后的 Prompt
     */
    public String injectProductionContextIntoPrompt(String factoryId, String originalPrompt, Integer days) {
        if (aiContextService == null) {
            log.debug("[AIContext] AIContextService 未注入，返回原始 Prompt");
            return originalPrompt;
        }

        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days != null ? days : 30);

            ProductionAIContext context = aiContextService.buildProductionContext(factoryId, startDate, endDate);
            if (context == null) {
                return originalPrompt;
            }

            String contextPrompt = aiContextService.formatProductionContextForPrompt(context);
            if (contextPrompt == null || contextPrompt.isEmpty()) {
                return originalPrompt;
            }

            StringBuilder enhanced = new StringBuilder();
            enhanced.append("【预计算生产数据上下文】\n");
            enhanced.append(contextPrompt);
            enhanced.append("\n---\n\n");
            enhanced.append(originalPrompt);

            log.info("[AIContext] 已注入生产上下文: factoryId={}, 统计天数={}, 产品数={}",
                    factoryId, days, context.getProductCount());

            return enhanced.toString();

        } catch (Exception e) {
            log.warn("[AIContext] 注入生产上下文失败: {}", e.getMessage());
            return originalPrompt;
        }
    }

    /**
     * 注入成本上下文到 Prompt
     *
     * 用于在 LLM 调用前添加特定产品的成本分析数据。
     *
     * @param factoryId 工厂ID
     * @param productTypeId 产品类型ID
     * @param originalPrompt 原始 Prompt
     * @return 注入上下文后的 Prompt
     */
    public String injectCostContextIntoPrompt(String factoryId, String productTypeId, String originalPrompt) {
        if (aiContextService == null || productTypeId == null || productTypeId.isEmpty()) {
            return originalPrompt;
        }

        try {
            CostAIContext context = aiContextService.buildCostContext(factoryId, productTypeId, 10);
            if (context == null) {
                return originalPrompt;
            }

            String contextPrompt = aiContextService.formatCostContextForPrompt(context);
            if (contextPrompt == null || contextPrompt.isEmpty()) {
                return originalPrompt;
            }

            StringBuilder enhanced = new StringBuilder();
            enhanced.append("【预计算成本分析上下文】\n");
            enhanced.append(contextPrompt);
            enhanced.append("\n---\n\n");
            enhanced.append(originalPrompt);

            log.info("[AIContext] 已注入成本上下文: factoryId={}, productTypeId={}, 差异状态={}",
                    factoryId, productTypeId, context.getVarianceStatus());

            return enhanced.toString();

        } catch (Exception e) {
            log.warn("[AIContext] 注入成本上下文失败: {}", e.getMessage());
            return originalPrompt;
        }
    }

    /**
     * 获取成本差异汇总
     *
     * 便捷方法，用于获取工厂所有产品的成本差异情况。
     *
     * @param factoryId 工厂ID
     * @return 成本差异明细列表
     */
    public List<CostAIContext.CostVarianceDetail> getCostVarianceSummary(String factoryId) {
        if (aiContextService == null) {
            log.warn("[AIContext] AIContextService 未注入，无法获取成本差异汇总");
            return List.of();
        }

        try {
            return aiContextService.getCostVarianceSummary(factoryId);
        } catch (Exception e) {
            log.warn("[AIContext] 获取成本差异汇总失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 通过 Python 服务调用进行成本分析（原有逻辑）
     */
    private Map<String, Object> analyzeCostViaPython(String factoryId, Long userId, String batchId,
                                                      Map<String, Object> costData,
                                                      String sessionId,
                                                      String customMessage,
                                                      Boolean enableThinking,
                                                      Integer thinkingBudget) {
        try {
            // 1. 格式化成本数据为AI提示词
            String message = customMessage != null && !customMessage.trim().isEmpty()
                ? customMessage
                : formatCostDataForAI(factoryId, batchId, costData);

            // 1.1 注入预计算的上下文以减少 Token 消耗
            String preComputedContext = buildPreComputedContext(factoryId, costData);
            if (preComputedContext != null && !preComputedContext.isEmpty()) {
                message = preComputedContext + "\n\n" + message;
                log.info("[Python Service] 已注入预计算上下文: factoryId={}", factoryId);
            }

            PythonGeneralAnalysisRequest request = PythonGeneralAnalysisRequest.builder()
                    .message(message)
                    .sessionId(sessionId != null && !sessionId.isBlank() ? sessionId : null)
                    .enableThinking(enableThinking != null ? enableThinking : true)
                    .thinkingBudget(thinkingBudget != null ? thinkingBudget : 50)
                    .allowTenantDataFallback(false)
                    .build();

            PythonGeneralAnalysisResponse response = pythonSmartBIClient.analyzeGeneral(
                    factoryId, userId != null ? userId.toString() : null, request);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("aiAnalysis", response.getEffectiveAnalysis());
            result.put("reasoningContent", response.getReasoningContent());
            result.put("thinkingEnabled", response.getThinkingEnabled());
            result.put("sessionId", response.getSessionId());
            result.put("messageCount", response.getMessageCount());
            return result;

        } catch (Exception e) {
            log.error("AI分析失败 (Python): factoryId={}, batchId={}, upstream unavailable",
                    factoryId, batchId);

            // 返回友好的错误信息
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", "AI服务暂时不可用，请稍后重试");
            return errorResult;
        }
    }

    /**
     * 格式化成本数据为AI提示词（增强版 - 包含完整业务链数据）
     */
    @SuppressWarnings("unchecked")
    private String formatCostDataForAI(String factoryId, String batchId, Map<String, Object> costData) {
        StringBuilder sb = new StringBuilder();

        // 判断是否为增强版数据
        boolean isEnhanced = costData.containsKey("batchInfo") && costData.containsKey("materialConsumptions");

        if (isEnhanced) {
            return formatEnhancedCostData(costData);
        } else {
            // 使用原有简化格式（向后兼容）
            return formatBasicCostData(costData);
        }
    }

    /**
     * 格式化增强版成本数据（完整业务链数据）
     */
    @SuppressWarnings("unchecked")
    private String formatEnhancedCostData(Map<String, Object> costData) {
        StringBuilder sb = new StringBuilder();

        // ========== 1. 基本信息 ==========
        Map<String, Object> batchInfo = (Map<String, Object>) costData.get("batchInfo");
        if (batchInfo != null) {
            sb.append("【批次信息】\n");
            sb.append(getStringValue(batchInfo, "batchNumber", "N/A")).append(" - ");
            sb.append(getStringValue(batchInfo, "productName", "N/A"));
            sb.append(" | 状态: ").append(getStringValue(batchInfo, "status", "N/A")).append("\n");
            sb.append("计划: ").append(formatMoney(getBigDecimalValue(batchInfo, "plannedQuantity"))).append("kg");
            sb.append(" | 实际: ").append(formatMoney(getBigDecimalValue(batchInfo, "actualQuantity"))).append("kg");
            sb.append(" | 良品: ").append(formatMoney(getBigDecimalValue(batchInfo, "goodQuantity"))).append("kg");
            sb.append(" | 次品: ").append(formatMoney(getBigDecimalValue(batchInfo, "defectQuantity"))).append("kg\n");
            sb.append("良品率: ").append(formatPercent(batchInfo.get("yieldRate"))).append("%");
            sb.append(" | 效率: ").append(formatPercent(batchInfo.get("efficiency"))).append("%\n\n");
        }

        // ========== 2. 生产计划对比 ==========
        Map<String, Object> planComparison = (Map<String, Object>) costData.get("productionPlanComparison");
        if (planComparison != null) {
            sb.append("【生产计划】\n");
            sb.append("计划号: ").append(getStringValue(planComparison, "planNumber", "N/A"));
            sb.append(" | 完成率: ").append(formatPercent(planComparison.get("completionRate"))).append("%\n\n");
        }

        // ========== 3. 原材料消耗 ==========
        List<Map<String, Object>> materials = (List<Map<String, Object>>) costData.get("materialConsumptions");
        int materialCount = getIntValue(costData, "materialConsumptionCount");
        if (materials != null && !materials.isEmpty()) {
            sb.append("【原材料消耗】共").append(materialCount).append("种\n");
            for (int i = 0; i < Math.min(materials.size(), 5); i++) {  // 最多显示5种
                Map<String, Object> m = materials.get(i);
                sb.append("• ").append(getStringValue(m, "materialName", "N/A"));
                sb.append(": ").append(formatMoney(getBigDecimalValue(m, "quantity")));
                sb.append(getStringValue(m, "unit", "kg"));
                sb.append(" ¥").append(formatMoney(getBigDecimalValue(m, "cost")));

                Map<String, Object> supplier = (Map<String, Object>) m.get("supplier");
                if (supplier != null) {
                    sb.append(" (").append(getStringValue(supplier, "name", "")).append(")");
                }
                sb.append("\n");
            }
            if (materials.size() > 5) {
                sb.append("... 还有").append(materials.size() - 5).append("种原材料\n");
            }
            sb.append("原材料总成本: ¥").append(formatMoney(getBigDecimalValue(costData, "totalMaterialCost"))).append("\n\n");
        }

        // ========== 4. 设备使用 ==========
        List<Map<String, Object>> equipment = (List<Map<String, Object>>) costData.get("equipmentUsages");
        int equipmentCount = getIntValue(costData, "equipmentUsageCount");
        int totalHours = getIntValue(costData, "totalEquipmentHours");
        if (equipment != null && !equipment.isEmpty()) {
            sb.append("【设备使用】共").append(equipmentCount).append("台, ").append(totalHours).append("小时\n");
            for (int i = 0; i < Math.min(equipment.size(), 3); i++) {  // 最多显示3台
                Map<String, Object> e = equipment.get(i);
                sb.append("• ").append(getStringValue(e, "equipmentName", "N/A"));
                sb.append(": ").append(getIntValue(e, "durationHours")).append("h");
                sb.append(" ¥").append(formatMoney(getBigDecimalValue(e, "cost"))).append("\n");
            }
            sb.append("设备总成本: ¥").append(formatMoney(getBigDecimalValue(costData, "totalEquipmentCost"))).append("\n\n");
        }

        // ========== 5. 人工工时 ==========
        List<Map<String, Object>> labor = (List<Map<String, Object>>) costData.get("laborSessions");
        int laborCount = getIntValue(costData, "laborSessionCount");
        Object totalHoursObj = costData.get("totalWorkHours");
        if (labor != null && !labor.isEmpty()) {
            sb.append("【人工工时】共").append(laborCount).append("人次, ");
            sb.append(String.format("%.1f", totalHoursObj != null ? ((Number)totalHoursObj).doubleValue() : 0.0)).append("小时\n");
            for (int i = 0; i < Math.min(labor.size(), 3); i++) {  // 最多显示3人
                Map<String, Object> l = labor.get(i);
                Map<String, Object> emp = (Map<String, Object>) l.get("employee");
                Map<String, Object> workType = (Map<String, Object>) l.get("workType");

                sb.append("• ");
                if (emp != null) {
                    sb.append(getStringValue(emp, "fullName", "N/A"));
                }
                if (workType != null) {
                    sb.append(" (").append(getStringValue(workType, "name", "")).append(")");
                }
                sb.append(": ").append(getIntValue(l, "workMinutes")).append("分钟");
                sb.append(" ¥").append(formatMoney(getBigDecimalValue(l, "laborCost"))).append("\n");
            }
            sb.append("人工总成本: ¥").append(formatMoney(getBigDecimalValue(costData, "totalLaborCost"))).append("\n\n");
        }

        // ========== 6. 质量检验 ==========
        List<Map<String, Object>> quality = (List<Map<String, Object>>) costData.get("qualityInspections");
        int qualityCount = getIntValue(costData, "qualityInspectionCount");
        if (quality != null && !quality.isEmpty()) {
            sb.append("【质量检验】共").append(qualityCount).append("次");
            BigDecimal avgPassRate = getBigDecimalValue(costData, "averagePassRate");
            if (avgPassRate.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(" | 平均合格率: ").append(formatPercent(avgPassRate)).append("%");
            }
            sb.append("\n\n");
        }

        // ========== 7. 成本汇总 ==========
        Map<String, Object> costSummary = (Map<String, Object>) costData.get("costSummary");
        if (costSummary != null) {
            sb.append("【成本汇总】\n");
            sb.append("总成本: ¥").append(formatMoney(getBigDecimalValue(costSummary, "totalCost"))).append("\n");
            sb.append("• 原料: ").append(formatPercent(costSummary.get("materialCostRatio"))).append("%");
            sb.append(" | 人工: ").append(formatPercent(costSummary.get("laborCostRatio"))).append("%");
            sb.append(" | 设备: ").append(formatPercent(costSummary.get("equipmentCostRatio"))).append("%\n");
            sb.append("单位成本: ¥").append(formatMoney(getBigDecimalValue(costSummary, "unitCost"))).append("/kg\n\n");
        }

        // ========== 8. 风险预警 ==========
        List<String> risks = (List<String>) costData.get("risks");
        int riskCount = getIntValue(costData, "riskCount");
        if (risks != null && !risks.isEmpty()) {
            sb.append("【风险预警】").append(riskCount).append("项\n");
            for (int i = 0; i < Math.min(risks.size(), 3); i++) {
                sb.append("⚠️ ").append(risks.get(i)).append("\n");
            }
            sb.append("\n");
        }

        // ========== 9. 行业基准对比（核心：辅助AI定位问题维度） ==========
        sb.append("【行业基准对比】\n");
        sb.append("以下是食品加工行业标准，用于判断当前数据是否异常：\n\n");

        // 9.1 成本结构基准
        if (costSummary != null) {
            sb.append("▶ 成本结构基准：\n");
            BigDecimal totalCost = getBigDecimalValue(costSummary, "totalCost");
            BigDecimal materialCostRatio = getBigDecimalValue(costSummary, "materialCostRatio");
            BigDecimal laborCostRatio = getBigDecimalValue(costSummary, "laborCostRatio");
            BigDecimal equipmentCostRatio = getBigDecimalValue(costSummary, "equipmentCostRatio");

            if (materialCostRatio.compareTo(BigDecimal.ZERO) > 0) {
                String status = materialCostRatio.compareTo(new BigDecimal("60")) > 0 ? "⚠️偏高" :
                               materialCostRatio.compareTo(new BigDecimal("50")) < 0 ? "⚠️偏低" : "✓正常";
                sb.append("  • 原材料成本占比: ").append(formatPercent(materialCostRatio)).append("% ")
                  .append("(基准: 50-60%) ").append(status).append("\n");
            }
            if (laborCostRatio.compareTo(BigDecimal.ZERO) > 0) {
                String status = laborCostRatio.compareTo(new BigDecimal("25")) > 0 ? "⚠️偏高" :
                               laborCostRatio.compareTo(new BigDecimal("15")) < 0 ? "⚠️偏低" : "✓正常";
                sb.append("  • 人工成本占比: ").append(formatPercent(laborCostRatio)).append("% ")
                  .append("(基准: 15-25%) ").append(status).append("\n");
            }
            if (equipmentCostRatio.compareTo(BigDecimal.ZERO) > 0) {
                String status = equipmentCostRatio.compareTo(new BigDecimal("15")) > 0 ? "⚠️偏高" :
                               equipmentCostRatio.compareTo(new BigDecimal("10")) < 0 ? "⚠️偏低" : "✓正常";
                sb.append("  • 设备成本占比: ").append(formatPercent(equipmentCostRatio)).append("% ")
                  .append("(基准: 10-15%) ").append(status).append("\n");
            }
            sb.append("\n");
        }

        // 9.2 质量效率基准
        if (batchInfo != null) {
            sb.append("▶ 质量效率基准：\n");

            BigDecimal yieldRate = getBigDecimalValue(batchInfo, "yieldRate");
            if (yieldRate.compareTo(BigDecimal.ZERO) > 0) {
                String status = yieldRate.compareTo(new BigDecimal("98")) >= 0 ? "✓优秀" :
                               yieldRate.compareTo(new BigDecimal("95")) >= 0 ? "✓达标" :
                               yieldRate.compareTo(new BigDecimal("90")) >= 0 ? "⚠️需改进" : "❌严重不足";
                sb.append("  • 良品率: ").append(formatPercent(yieldRate)).append("% ")
                  .append("(基准: >95%, 优秀: >98%) ").append(status).append("\n");
            }

            BigDecimal efficiency = getBigDecimalValue(batchInfo, "efficiency");
            if (efficiency.compareTo(BigDecimal.ZERO) > 0) {
                String status = efficiency.compareTo(new BigDecimal("90")) >= 0 ? "✓优秀" :
                               efficiency.compareTo(new BigDecimal("85")) >= 0 ? "✓达标" :
                               efficiency.compareTo(new BigDecimal("75")) >= 0 ? "⚠️需改进" : "❌严重不足";
                sb.append("  • 生产效率(OEE): ").append(formatPercent(efficiency)).append("% ")
                  .append("(基准: >85%, 优秀: >90%) ").append(status).append("\n");
            }

            // 计算次品率
            BigDecimal actualQty = getBigDecimalValue(batchInfo, "actualQuantity");
            BigDecimal defectQty = getBigDecimalValue(batchInfo, "defectQuantity");
            if (actualQty.compareTo(BigDecimal.ZERO) > 0 && defectQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal defectRate = defectQty.divide(actualQty, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                String status = defectRate.compareTo(new BigDecimal("1")) <= 0 ? "✓达标" :
                               defectRate.compareTo(new BigDecimal("3")) <= 0 ? "⚠️需改进" : "❌严重超标";
                sb.append("  • 次品率: ").append(formatPercent(defectRate)).append("% ")
                  .append("(基准: <1%, 可接受: <3%) ").append(status).append("\n");
            }
            sb.append("\n");
        }

        // 9.3 人均产能基准
        Object totalWorkHoursObj = costData.get("totalWorkHours");
        if (totalWorkHoursObj != null && batchInfo != null) {
            double totalWorkHours = ((Number) totalWorkHoursObj).doubleValue();
            BigDecimal actualQty = getBigDecimalValue(batchInfo, "actualQuantity");
            int workerCount = laborCount > 0 ? laborCount : 1;

            if (totalWorkHours > 0 && actualQty.compareTo(BigDecimal.ZERO) > 0) {
                double productivity = actualQty.doubleValue() / totalWorkHours;
                String status = productivity >= 50 ? "✓达标" :
                               productivity >= 40 ? "⚠️略低" : "❌严重不足";
                sb.append("▶ 人均产能基准：\n");
                sb.append("  • 人均产能: ").append(String.format("%.1f", productivity)).append(" kg/人/小时 ")
                  .append("(基准: >50 kg/人/小时) ").append(status).append("\n\n");
            }
        }

        // 9.4 分析指导
        sb.append("▶ 分析指导：\n");
        sb.append("  请根据以上数据和基准对比，识别成本问题属于哪个维度：\n");
        sb.append("  1️⃣ 原材料维度 - 成本占比、损耗率、采购价格\n");
        sb.append("  2️⃣ 人工维度 - 成本占比、人均产能、工时效率\n");
        sb.append("  3️⃣ 设备维度 - 成本占比、OEE、停机时间\n");
        sb.append("  4️⃣ 质量维度 - 良品率、次品率、返工率\n");
        sb.append("  5️⃣ 时间维度 - 生产周期、瓶颈环节\n");

        return sb.toString();
    }

    /**
     * 格式化基础成本数据（兼容旧版）
     */
    @SuppressWarnings("unchecked")
    private String formatBasicCostData(Map<String, Object> costData) {
        StringBuilder sb = new StringBuilder();

        // 提取批次对象
        Map<String, Object> batch = (Map<String, Object>) costData.get("batch");
        if (batch == null) {
            batch = costData;
        }

        // 基础信息（精简）
        sb.append(getStringValue(batch, "batchNumber", "批次")).append(" - ");
        sb.append(getStringValue(batch, "productName", "产品")).append("\n\n");

        // 成本结构（紧凑格式）
        sb.append("成本: ¥").append(formatMoney(getBigDecimalValue(costData, "totalCost"))).append("\n");
        sb.append("原料 ").append(formatPercent(costData.get("materialCostRatio"))).append("% | ");
        sb.append("人工 ").append(formatPercent(costData.get("laborCostRatio"))).append("% | ");
        sb.append("设备 ").append(formatPercent(costData.get("equipmentCostRatio"))).append("%\n\n");

        // 生产指标（仅关键数据）
        BigDecimal actualQty = getBigDecimalValue(batch, "actualQuantity");
        BigDecimal yieldRate = getBigDecimalValue(batch, "yieldRate");

        if (actualQty != null) {
            sb.append("产量: ").append(actualQty).append("kg | ");
        }
        if (yieldRate != null) {
            sb.append("良品率: ").append(yieldRate).append("%");
        }

        return sb.toString();
    }

    /**
     * 格式化精简版成本数据（SSE流式响应专用 - 减少约50% Token）
     *
     * 移除冗长的行业基准对比（Section 9），基准已内置于系统提示词。
     * 所有部分压缩为单行格式，仅保留核心数值。
     */
    @SuppressWarnings("unchecked")
    private String formatCompactCostData(Map<String, Object> costData) {
        StringBuilder sb = new StringBuilder();

        // 1. 批次信息（单行）
        Map<String, Object> batchInfo = (Map<String, Object>) costData.get("batchInfo");
        if (batchInfo != null) {
            sb.append("批次: ").append(getStringValue(batchInfo, "batchNumber", "N/A"));
            sb.append(" | ").append(getStringValue(batchInfo, "productName", "N/A"));
            sb.append(" | 计划").append(formatMoney(getBigDecimalValue(batchInfo, "plannedQuantity"))).append("kg");
            sb.append(" 实际").append(formatMoney(getBigDecimalValue(batchInfo, "actualQuantity"))).append("kg");
            sb.append(" | 良品率").append(formatPercent(batchInfo.get("yieldRate"))).append("%");
            sb.append(" 效率").append(formatPercent(batchInfo.get("efficiency"))).append("%\n");
        }

        // 2. 成本汇总（核心数据）
        Map<String, Object> costSummary = (Map<String, Object>) costData.get("costSummary");
        if (costSummary != null) {
            sb.append("成本: ¥").append(formatMoney(getBigDecimalValue(costSummary, "totalCost")));
            sb.append(" (原料").append(formatPercent(costSummary.get("materialCostRatio"))).append("%");
            sb.append(" 人工").append(formatPercent(costSummary.get("laborCostRatio"))).append("%");
            sb.append(" 设备").append(formatPercent(costSummary.get("equipmentCostRatio"))).append("%)");
            sb.append(" | 单位¥").append(formatMoney(getBigDecimalValue(costSummary, "unitCost"))).append("/kg\n");
        }

        // 3. 原材料（汇总+Top3）
        List<Map<String, Object>> materials = (List<Map<String, Object>>) costData.get("materialConsumptions");
        if (materials != null && !materials.isEmpty()) {
            sb.append("原料: ¥").append(formatMoney(getBigDecimalValue(costData, "totalMaterialCost")));
            sb.append(" (").append(materials.size()).append("种) → ");
            for (int i = 0; i < Math.min(materials.size(), 3); i++) {
                Map<String, Object> m = materials.get(i);
                if (i > 0) sb.append(", ");
                sb.append(getStringValue(m, "materialName", ""));
                sb.append(" ¥").append(formatMoney(getBigDecimalValue(m, "cost")));
            }
            sb.append("\n");
        }

        // 4. 设备+人工（合并一行）
        sb.append("设备: ¥").append(formatMoney(getBigDecimalValue(costData, "totalEquipmentCost")));
        sb.append(" (").append(getIntValue(costData, "equipmentUsageCount")).append("台");
        sb.append(" ").append(getIntValue(costData, "totalEquipmentHours")).append("h)");
        sb.append(" | 人工: ¥").append(formatMoney(getBigDecimalValue(costData, "totalLaborCost")));
        Object totalHours = costData.get("totalWorkHours");
        if (totalHours != null) {
            sb.append(" (").append(String.format("%.1f", ((Number)totalHours).doubleValue())).append("h)");
        }
        sb.append("\n");

        // 5. 质量检验（简化）
        int qualityCount = getIntValue(costData, "qualityInspectionCount");
        if (qualityCount > 0) {
            sb.append("质检: ").append(qualityCount).append("次");
            BigDecimal avgPassRate = getBigDecimalValue(costData, "averagePassRate");
            if (avgPassRate.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(" | 合格率").append(formatPercent(avgPassRate)).append("%");
            }
            sb.append("\n");
        }

        // 6. 风险预警（仅显示1条最重要的）
        List<String> risks = (List<String>) costData.get("risks");
        if (risks != null && !risks.isEmpty()) {
            sb.append("⚠️ ").append(risks.get(0));
            if (risks.size() > 1) {
                sb.append(" (+").append(risks.size() - 1).append("项)");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 格式化成本数据（支持精简模式）
     *
     * @param costData 成本数据
     * @param compactMode true=使用精简格式(SSE)，false=使用完整格式
     * @return 格式化后的字符串
     */
    @SuppressWarnings("unchecked")
    public String formatCostDataWithMode(Map<String, Object> costData, boolean compactMode) {
        boolean isEnhanced = costData.containsKey("batchInfo") && costData.containsKey("materialConsumptions");

        if (!isEnhanced) {
            return formatBasicCostData(costData);
        }

        return compactMode ? formatCompactCostData(costData) : formatEnhancedCostData(costData);
    }

    /**
     * 健康检查 - 测试AI服务是否可用
     */
    public Map<String, Object> healthCheck() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("available", pythonSmartBIClient.health().isHealthy());
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("available", false);
            result.put("error", "AI_SERVICE_UNAVAILABLE");
            return result;
        }
    }

    // ==================== 员工AI分析方法 ====================

    /**
     * AI员工综合分析
     *
     * @param factoryId 工厂ID
     * @param employeeId 员工ID
     * @param days 分析天数（默认90天）
     * @param question 自定义问题（可选）
     * @param sessionId 会话ID（可选，用于追问）
     * @return 员工分析响应
     */
    public AIResponseDTO.EmployeeAnalysisResponse analyzeEmployee(
            String factoryId, Long requesterUserId, Long employeeId, Integer days,
            String question, String sessionId) {

        log.info("开始员工AI分析: factoryId={}, employeeId={}, days={}", factoryId, employeeId, days);

        try {
            // 1. 获取员工基本信息
            User employee = userRepository.findByIdAndFactoryId(employeeId, factoryId)
                    .orElseThrow(() -> new RuntimeException("员工不存在"));

            // 2. 计算时间范围
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days != null ? days : 90);

            // 3. 收集员工数据
            Map<String, Object> employeeData = collectEmployeeData(factoryId, employee, startTime, endTime);

            // 4. 格式化为AI提示词
            String factPrompt = formatEmployeeDataForAI(employeeData);
            String message = question != null && !question.trim().isEmpty()
                    ? "【用户补充问题（不改变事实约束）】\n" + question.trim() + "\n\n" + factPrompt
                    : factPrompt;

            PythonGeneralAnalysisRequest request = PythonGeneralAnalysisRequest.builder()
                    .message(message)
                    .sessionId(sessionId != null && !sessionId.isBlank() ? sessionId : null)
                    .enableThinking(true)
                    .thinkingBudget(60)
                    .allowTenantDataFallback(false)
                    .build();
            PythonGeneralAnalysisResponse response = pythonSmartBIClient.analyzeGeneral(
                    factoryId,
                    requesterUserId != null ? requesterUserId.toString() : null,
                    request);
            return buildEmployeeAnalysisResponse(employee, employeeData, response, startTime, endTime);

        } catch (Exception e) {
            log.error("员工AI分析失败: factoryId={}, employeeId={}, upstream unavailable",
                    factoryId, employeeId);
            throw new RuntimeException("员工AI分析失败");
        }
    }

    /**
     * 收集员工分析数据
     */
    private Map<String, Object> collectEmployeeData(String factoryId, User employee,
                                                     LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> data = new HashMap<>();
        Long userId = employee.getId();

        // 1. 考勤数据
        List<TimeClockRecord> attendanceRecords = timeClockRecordRepository
                .findByFactoryIdAndUserIdAndClockDateBetween(factoryId, userId, startTime, endTime);

        int attendanceDays = 0;
        int lateCount = 0;
        int earlyLeaveCount = 0;
        int absentDays = 0;
        int totalWorkMinutes = 0;

        for (TimeClockRecord record : attendanceRecords) {
            if (record.getWorkDurationMinutes() != null) {
                totalWorkMinutes += record.getWorkDurationMinutes();
            }

            // 只使用持久化的考勤状态；排班时间不在本服务的可信数据范围内。
            String attendanceStatus = record.getAttendanceStatus();
            if ("ABSENT".equalsIgnoreCase(attendanceStatus)) {
                absentDays++;
            } else if (attendanceStatus != null) {
                attendanceDays++;
            }
            if (attendanceStatus != null && attendanceStatus.toUpperCase(Locale.ROOT).contains("LATE")) {
                lateCount++;
            }
            if (attendanceStatus != null && attendanceStatus.toUpperCase(Locale.ROOT).contains("EARLY_LEAVE")) {
                earlyLeaveCount++;
            }
        }

        Map<String, Object> attendanceData = new HashMap<>();
        attendanceData.put("recordCount", attendanceRecords.size());
        attendanceData.put("attendanceDays", attendanceDays);
        attendanceData.put("attendanceRate", null);
        attendanceData.put("lateCount", lateCount);
        attendanceData.put("earlyLeaveCount", earlyLeaveCount);
        attendanceData.put("absentDays", absentDays);
        attendanceData.put("totalWorkMinutes", totalWorkMinutes);
        data.put("attendance", attendanceData);

        // 2. 工作会话数据（工时效率）
        Integer workSessionMinutes = employeeWorkSessionRepository
                .sumActualWorkMinutesByFactoryIdAndUserIdAndTimeRange(factoryId, userId, startTime, endTime);
        long sessionCount = employeeWorkSessionRepository
                .countByFactoryIdAndUserIdAndTimeRange(factoryId, userId, startTime, endTime);

        Map<String, Object> workHoursData = new HashMap<>();
        int actualWorkMinutes = workSessionMinutes != null ? workSessionMinutes : 0;
        workHoursData.put("totalMinutes", actualWorkMinutes);
        workHoursData.put("sessionCount", sessionCount);
        workHoursData.put("avgDailyHours", null);
        workHoursData.put("overtimeHours", null);
        workHoursData.put("efficiency", null);
        data.put("workHours", workHoursData);

        // 3. 生产贡献数据（从BatchWorkSession获取真实数据）
        Map<String, Object> productionData = new HashMap<>();

        // 使用真实的批次工作会话数据
        long batchCount = batchWorkSessionRepository.countDistinctBatchesByFactoryIdAndEmployeeAndTimeRange(
                factoryId, userId, startTime, endTime);
        long batchWorkSessionCount = batchWorkSessionRepository.countByFactoryIdAndEmployeeAndTimeRange(
                factoryId, userId, startTime, endTime);
        Integer batchWorkMinutes = batchWorkSessionRepository.sumWorkMinutesByFactoryIdAndEmployeeAndTimeRange(
                factoryId, userId, startTime, endTime);
        long completedBatchWorkSessionCount =
                batchWorkSessionRepository.countCompletedByFactoryIdAndEmployeeAndTimeRange(
                        factoryId, userId, startTime, endTime);

        int totalBatchMinutes = batchWorkMinutes != null ? batchWorkMinutes : 0;

        // 获取质检数据计算良品率
        long totalInspections = qualityInspectionRepository.countByFactoryIdAndInspectorIdAndDateRange(
                factoryId, userId, startTime.toLocalDate(), endTime.toLocalDate());
        long passedInspections = qualityInspectionRepository.countPassedByFactoryIdAndInspectorIdAndDateRange(
                factoryId, userId, startTime.toLocalDate(), endTime.toLocalDate());
        Double qualityRate = totalInspections > 0
                ? Math.round(passedInspections * 1000.0 / totalInspections) / 10.0
                : null;

        productionData.put("batchCount", (int) batchCount);
        productionData.put("batchWorkSessionCount", batchWorkSessionCount);
        productionData.put("completedBatchWorkSessionCount", completedBatchWorkSessionCount);
        productionData.put("batchWorkMinutes", totalBatchMinutes);
        productionData.put("outputQuantity", null);
        productionData.put("qualityRate", qualityRate);
        productionData.put("productivityRate", null);
        productionData.put("totalInspections", totalInspections);
        productionData.put("passedInspections", passedInspections);
        data.put("production", productionData);

        // BatchWorkSession.notes 是自由文本备注，不是受控技能类型，不能用于技能推断。
        data.put("skills", Collections.emptyList());

        // 5. 员工基本信息
        Map<String, Object> employeeInfo = new HashMap<>();
        employeeInfo.put("id", employee.getId());
        employeeInfo.put("name", employee.getFullName() != null ? employee.getFullName() : employee.getUsername());
        employeeInfo.put("department", employee.getDepartment());
        employeeInfo.put("position", employee.getPosition());
        employeeInfo.put("tenureMonths", employee.getWorkMonths());
        data.put("employee", employeeInfo);

        return data;
    }

    /**
     * 格式化员工数据为AI提示词
     */
    @SuppressWarnings("unchecked")
    private String formatEmployeeDataForAI(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();

        Map<String, Object> employee = (Map<String, Object>) data.get("employee");
        Map<String, Object> attendance = (Map<String, Object>) data.get("attendance");
        Map<String, Object> workHours = (Map<String, Object>) data.get("workHours");
        Map<String, Object> production = (Map<String, Object>) data.get("production");
        sb.append("【员工事实数据分析请求】\n");
        sb.append("以下仅列出持久化记录或由记录直接计算的事实。\n\n");

        // 员工基本信息
        sb.append("▶ 员工信息\n");
        sb.append("  姓名: ").append(employee.get("name")).append("\n");
        if (employee.get("department") != null) {
            sb.append("  部门: ").append(employee.get("department")).append("\n");
        }
        if (employee.get("position") != null) {
            sb.append("  职位: ").append(employee.get("position")).append("\n");
        }
        if (employee.get("tenureMonths") != null) {
            sb.append("  入职时长: ").append(employee.get("tenureMonths")).append("个月\n");
        }
        sb.append("\n");

        // 考勤记录事实，不从固定上下班时间或工作日估算排班结果。
        sb.append("▶ 考勤记录事实\n");
        sb.append("  记录数: ").append(attendance.get("recordCount")).append("\n");
        sb.append("  非ABSENT状态记录数: ").append(attendance.get("attendanceDays")).append("\n");
        sb.append("  持久化LATE状态记录数: ").append(attendance.get("lateCount")).append("\n");
        sb.append("  持久化EARLY_LEAVE状态记录数: ").append(attendance.get("earlyLeaveCount")).append("\n");
        sb.append("  持久化ABSENT状态记录数: ").append(attendance.get("absentDays")).append("\n");
        sb.append("  打卡记录工作分钟数合计: ").append(attendance.get("totalWorkMinutes")).append("\n\n");

        sb.append("▶ 工作会话事实\n");
        sb.append("  会话数: ").append(workHours.get("sessionCount")).append("\n");
        sb.append("  实际工作分钟数合计: ").append(workHours.get("totalMinutes")).append("\n\n");

        sb.append("▶ 生产与质检记录事实\n");
        sb.append("  参与批次数: ").append(production.get("batchCount")).append("个\n");
        sb.append("  批次工作会话数: ").append(production.get("batchWorkSessionCount")).append("个\n");
        sb.append("  已完成批次工作会话数: ")
                .append(production.get("completedBatchWorkSessionCount")).append("个\n");
        sb.append("  批次工作分钟数合计: ").append(production.get("batchWorkMinutes")).append("\n");
        sb.append("  质检记录数: ").append(production.get("totalInspections")).append("\n");
        sb.append("  质检通过记录数: ").append(production.get("passedInspections")).append("\n");
        if (production.get("qualityRate") != null) {
            sb.append("  质检通过率: ").append(production.get("qualityRate")).append("%\n");
        }
        sb.append("\n");

        sb.append("【事实约束】\n");
        sb.append("排班期望天数、出勤率、考勤/工时/生产评分、综合评分、等级、部门均值、部门排名、产量、产能、技能和历史趋势均不可计算。\n");
        sb.append("不得推测、补值、套用固定上下班时间、行业平均、默认良品率或虚构趋势；缺失项必须明确写‘不可计算’。\n");
        sb.append("仅基于以上事实回答并区分事实与解释，不得把自由文本备注当作技能类型。");

        return sb.toString();
    }

    /**
     * 构建员工分析响应DTO
     */
    @SuppressWarnings("unchecked")
    private AIResponseDTO.EmployeeAnalysisResponse buildEmployeeAnalysisResponse(
            User employee, Map<String, Object> data, PythonGeneralAnalysisResponse aiResponse,
            LocalDateTime startTime, LocalDateTime endTime) {

        Map<String, Object> employeeInfo = (Map<String, Object>) data.get("employee");
        Map<String, Object> attendanceData = (Map<String, Object>) data.get("attendance");
        Map<String, Object> workHoursData = (Map<String, Object>) data.get("workHours");
        Map<String, Object> productionData = (Map<String, Object>) data.get("production");
        if (aiResponse == null || !aiResponse.hasAnalysis()) {
            throw new IllegalStateException("Python employee analysis response is invalid");
        }

        AIResponseDTO.EmployeeAnalysisResponse response = new AIResponseDTO.EmployeeAnalysisResponse();
        List<String> notComputableMetrics = new ArrayList<>();

        // 基本信息
        response.setEmployeeId(employee.getId());
        response.setEmployeeName(employee.getFullName() != null ? employee.getFullName() : employee.getUsername());
        response.setDepartment(employee.getDepartment());
        response.setPosition(employee.getPosition());
        Integer tenureMonths = (Integer) employeeInfo.get("tenureMonths");
        response.setTenureMonths(tenureMonths);
        if (tenureMonths == null) {
            notComputableMetrics.add("tenureMonths");
        }
        response.setPeriodStart(startTime.toLocalDate().toString());
        response.setPeriodEnd(endTime.toLocalDate().toString());

        // 原始记录数合计；四张互不重叠的事实表各计一次。
        long dataPoints = ((Number) attendanceData.get("recordCount")).longValue() +
                ((Number) workHoursData.get("sessionCount")).longValue() +
                ((Number) productionData.get("batchWorkSessionCount")).longValue() +
                ((Number) productionData.get("totalInspections")).longValue();
        response.setDataPoints(dataPoints);

        // 仓库中没有租户配置的评分规则、部门基准或历史可比序列，必须 fail closed。
        response.setOverallScore(null);
        response.setOverallGrade(null);
        response.setScoreChange(null);
        response.setDepartmentRankPercent(null);
        notComputableMetrics.addAll(List.of(
                "overallScore",
                "overallGrade",
                "scoreChange",
                "departmentRankPercent"));

        // 考勤分析
        AIResponseDTO.AttendanceAnalysis attendance = new AIResponseDTO.AttendanceAnalysis();
        attendance.setScore(null);
        attendance.setAttendanceRate(null);
        attendance.setRecordCount(((Number) attendanceData.get("recordCount")).intValue());
        attendance.setAttendanceDays(((Number) attendanceData.get("attendanceDays")).intValue());
        attendance.setLateCount(((Number) attendanceData.get("lateCount")).intValue());
        attendance.setEarlyLeaveCount(((Number) attendanceData.get("earlyLeaveCount")).intValue());
        attendance.setAbsentDays(((Number) attendanceData.get("absentDays")).intValue());
        attendance.setClockedWorkMinutes(((Number) attendanceData.get("totalWorkMinutes")).intValue());
        attendance.setDepartmentAvgRate(null);
        attendance.setInsight(null);
        attendance.setInsightType(null);
        response.setAttendance(attendance);
        notComputableMetrics.addAll(List.of(
                "attendance.score",
                "attendance.attendanceRate",
                "attendance.departmentAvgRate",
                "attendance.insight",
                "attendance.insightType"));

        // 工时分析
        AIResponseDTO.WorkHoursAnalysis workHours = new AIResponseDTO.WorkHoursAnalysis();
        workHours.setScore(null);
        workHours.setTotalMinutes(((Number) workHoursData.get("totalMinutes")).intValue());
        workHours.setSessionCount(((Number) workHoursData.get("sessionCount")).longValue());
        workHours.setAvgDailyHours(null);
        workHours.setOvertimeHours(null);
        workHours.setEfficiency(null);
        workHours.setWorkTypeCount(null);
        workHours.setDepartmentAvgHours(null);
        workHours.setInsight(null);
        workHours.setInsightType(null);
        response.setWorkHours(workHours);
        notComputableMetrics.addAll(List.of(
                "workHours.score",
                "workHours.avgDailyHours",
                "workHours.overtimeHours",
                "workHours.efficiency",
                "workHours.workTypeCount",
                "workHours.departmentAvgHours",
                "workHours.insight",
                "workHours.insightType"));

        // 生产分析只暴露真实计数，以及存在质检记录时的直接通过率。
        AIResponseDTO.ProductionAnalysis production = new AIResponseDTO.ProductionAnalysis();
        production.setScore(null);
        production.setBatchCount(((Number) productionData.get("batchCount")).intValue());
        production.setBatchWorkSessionCount(
                ((Number) productionData.get("batchWorkSessionCount")).longValue());
        production.setCompletedBatchWorkSessionCount(
                ((Number) productionData.get("completedBatchWorkSessionCount")).longValue());
        production.setBatchWorkMinutes(((Number) productionData.get("batchWorkMinutes")).intValue());
        production.setTotalInspections(((Number) productionData.get("totalInspections")).longValue());
        production.setPassedInspections(((Number) productionData.get("passedInspections")).longValue());
        production.setOutputQuantity(null);
        Number qualityRate = (Number) productionData.get("qualityRate");
        production.setQualityRate(qualityRate != null ? qualityRate.doubleValue() : null);
        production.setProductivityRate(null);
        production.setDepartmentAvgProductivity(null);
        production.setTopProductLine(null);
        production.setInsight(null);
        production.setInsightType(null);
        response.setProduction(production);
        notComputableMetrics.add("production.score");
        notComputableMetrics.add("production.outputQuantity");
        if (qualityRate == null) {
            notComputableMetrics.add("production.qualityRate");
        }
        notComputableMetrics.addAll(List.of(
                "production.productivityRate",
                "production.departmentAvgProductivity",
                "production.topProductLine",
                "production.insight",
                "production.insightType"));

        response.setSkills(Collections.emptyList());
        response.setSuggestions(Collections.emptyList());
        response.setTrends(Collections.emptyList());
        notComputableMetrics.addAll(List.of("skills", "suggestions", "trends"));

        response.setAiInsight(aiResponse.getEffectiveAnalysis());

        String upstreamSessionId = aiResponse.getSessionId();
        response.setSessionId(upstreamSessionId != null && !upstreamSessionId.isBlank()
                ? upstreamSessionId
                : null);
        response.setAnalyzedAt(LocalDateTime.now());
        response.setTokensUsed(aiResponse.getTokensUsed());
        response.setNotComputableMetrics(notComputableMetrics);

        return response;
    }

    // ========== 辅助方法 ==========

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).toString();
    }

    private String formatPercent(Object value) {
        if (value == null) return "0.00";
        try {
            BigDecimal decimal = value instanceof BigDecimal
                ? (BigDecimal) value
                : new BigDecimal(value.toString());
            return decimal.setScale(2, RoundingMode.HALF_UP).toString();
        } catch (NumberFormatException e) {
            return "0.00";
        }
    }
}
