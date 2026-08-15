package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.slot.RequiredSlot;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.SlotFillingService;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.config.TimeNormalizationRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Slot Filling 服务实现
 *
 * 实现渐进式参数收集（Slot Filling）机制：
 * 1. 在意图识别后检查必需参数
 * 2. 使用正则表达式和学习规则提取参数
 * 3. 主动创建参数收集会话
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotFillingServiceImpl implements SlotFillingService {

    private final IntentSlotConfiguration intentSlotConfiguration;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final ParameterExtractionLearningService parameterExtractionLearningService;

    // 产品类型仓库（缺参时提供可选项）
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;

    @Autowired(required = false)
    public void setProductTypeRepository(com.cretas.aims.repository.ProductTypeRepository productTypeRepository) {
        this.productTypeRepository = productTypeRepository;
    }

    // LLM 客户端 (用于第 5 步: extract 缺失参数的兜底). Optional — 不可用时静默退化为 NEED_MORE_INFO。
    private PythonLLMClient pythonLLMClient;

    @Autowired(required = false)
    public void setPythonLLMClient(PythonLLMClient pythonLLMClient) {
        this.pythonLLMClient = pythonLLMClient;
    }

    private static final ObjectMapper SLOT_OBJECT_MAPPER = new ObjectMapper();

    /**
     * 实体引用型槽位 —— 追问时**只能说名称，不许问 ID**。
     *
     * <p>后端本来就接受名称：填槽续轮把「55厂 牛腩排」解析成 materialTypeId 后可以走到
     * WRITE_CONFIRM_REQUIRED。但槽位标签取自 Tool schema 的 description（"原材料类型ID"），
     * 于是手机端操作员看到的是「请提供原材料类型ID」——一个他既拿不到也不该知道的 UUID。
     *
     * <p>value 是给人看的词，不带「ID」。
     */
    private static final Map<String, String> ENTITY_REFERENCE_LABELS = Map.ofEntries(
            Map.entry("materialTypeId", "原材料"),
            Map.entry("rawMaterialTypeId", "原材料"),
            Map.entry("productId", "产品"),
            Map.entry("productTypeId", "产品"),
            Map.entry("supplierId", "供应商"),
            Map.entry("customerId", "客户"),
            Map.entry("warehouseId", "仓库"),
            Map.entry("equipmentId", "设备")
    );

    @Override
    public IntentExecuteResponse checkAndStartSlotFilling(
            String factoryId,
            Long userId,
            AIIntentConfig intent,
            IntentExecuteRequest request,
            IntentMatchResult matchResult) {

        try {
            // 1. 获取意图的必需槽位定义
            List<RequiredSlot> requiredSlots = getRequiredSlots(intent);
            if (requiredSlots.isEmpty()) {
                log.debug("意图 {} 无必需槽位配置，跳过 Slot Filling", intent.getIntentCode());
                return null;
            }

            // 2. 从用户输入和上下文中提取已有参数
            Map<String, Object> extractedParams = extractParameters(
                    request.getUserInput(),
                    requiredSlots,
                    request.getContext(),
                    matchResult);

            log.debug("从用户输入提取参数: intentCode={}, extracted={}",
                    intent.getIntentCode(), extractedParams.keySet());

            // 3. 找出缺失的必需槽位
            List<RequiredSlot> missingSlots = findMissingSlots(requiredSlots, extractedParams);
            if (missingSlots.isEmpty()) {
                log.debug("所有必需参数已提取，跳过 Slot Filling: intentCode={}", intent.getIntentCode());
                // 将提取的参数合并到请求上下文
                if (request.getContext() == null) {
                    request.setContext(new HashMap<>());
                }
                request.getContext().putAll(extractedParams);
                return null;
            }

            log.info("检测到缺失参数，启动 Slot Filling: intentCode={}, missing={}",
                    intent.getIntentCode(),
                    missingSlots.stream().map(RequiredSlot::getName).collect(Collectors.toList()));

            // 4. 启动参数收集会话
            return startSlotFilling(factoryId, userId, intent, missingSlots, extractedParams);

        } catch (Exception e) {
            log.warn("Slot Filling 检查失败，继续执行: intentCode={}, error={}",
                    intent.getIntentCode(), e.getMessage());
            return null;
        }
    }

    @Override
    public List<RequiredSlot> getRequiredSlots(AIIntentConfig intent) {
        // 1. 优先从配置中获取
        List<RequiredSlot> configuredSlots = intentSlotConfiguration.getMandatorySlots(intent.getIntentCode());
        if (!configuredSlots.isEmpty()) {
            return configuredSlots;
        }

        // 2. 尝试从绑定的 Tool 推断
        String toolName = intent.getToolName();
        if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                return inferSlotsFromTool(toolOpt.get());
            }
        }

        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<RequiredSlot> inferSlotsFromTool(ToolExecutor tool) {
        List<RequiredSlot> slots = new ArrayList<>();

        Map<String, Object> schema = tool.getParametersSchema();
        if (schema == null) return slots;

        List<String> required = (List<String>) schema.get("required");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        if (required == null || properties == null) return slots;

        for (String paramName : required) {
            Map<String, Object> propDef = (Map<String, Object>) properties.get(paramName);
            if (propDef == null) continue;

            String type = (String) propDef.get("type");
            String description = (String) propDef.get("description");

            // 实体引用型槽位优先用人话标签: Tool schema 的 description 普遍写成
            // "原材料类型ID" / "供应商ID", 直接当标签会把 UUID 甩给操作员。
            String entityLabel = ENTITY_REFERENCE_LABELS.get(paramName);

            RequiredSlot slot = RequiredSlot.builder()
                    .name(paramName)
                    .label(entityLabel != null ? entityLabel
                            : (description != null ? description : getParameterLabel(paramName)))
                    .type(mapJsonTypeToSlotType(type))
                    .required(true)
                    .validationHint(getParameterValidationHint(paramName))
                    .build();
            slots.add(slot);
        }

        return slots;
    }

    private String mapJsonTypeToSlotType(String jsonType) {
        if (jsonType == null) return "TEXT";
        switch (jsonType.toLowerCase()) {
            case "integer": return "INTEGER";
            case "number": return "NUMBER";
            case "boolean": return "BOOLEAN";
            default: return "TEXT";
        }
    }

    @Override
    public Map<String, Object> extractParameters(
            String userInput,
            List<RequiredSlot> slots,
            Map<String, Object> context,
            IntentMatchResult matchResult) {

        Map<String, Object> extracted = new HashMap<>();

        // 1. 从请求上下文获取
        if (context != null) {
            for (RequiredSlot slot : slots) {
                Object value = context.get(slot.getName());
                if (value != null && !value.toString().isEmpty()) {
                    extracted.put(slot.getName(), value);
                }
            }
        }

        // 2. 从预处理结果获取（实体引用、时间范围等）
        if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
            extractFromPreprocessedQuery(matchResult.getPreprocessedQuery(), extracted);
        }

        // 3. 使用正则模式从用户输入提取
        if (userInput != null && !userInput.isEmpty()) {
            extractWithPatterns(userInput, slots, extracted);
        }

        // 4. 使用学习规则提取剩余参数
        if (matchResult != null && matchResult.getBestMatch() != null) {
            extractWithLearnedRules(userInput, slots, extracted, matchResult, context);
        }

        // 5. LLM 兜底抽参 — 让 LLM 处理 "今天/本月/上月/2025-03/上周" 等 NLP 短语，
        //    填补前 4 步抽不到的 slot。不可用时静默退化为 NEED_MORE_INFO。
        if (pythonLLMClient != null && userInput != null && !userInput.isEmpty()) {
            List<RequiredSlot> stillMissing = slots.stream()
                .filter(s -> !extracted.containsKey(s.getName()))
                .collect(Collectors.toList());
            if (!stillMissing.isEmpty()) {
                Map<String, Object> llmExtracted = llmExtractMissingSlots(userInput, stillMissing);
                for (Map.Entry<String, Object> e : llmExtracted.entrySet()) {
                    if (!extracted.containsKey(e.getKey()) && e.getValue() != null) {
                        extracted.put(e.getKey(), e.getValue());
                        log.debug("LLM 抽参: {}={}", e.getKey(), e.getValue());
                    }
                }
            }
        }

        return extracted;
    }

    /**
     * Step 5: LLM 兜底抽参。
     *
     * <p>把 user input + slot schema 喂给 LLM (低温度), 让它返回严格 JSON {name: value}。
     * 主要解决 regex pattern 抓不到的相对时间短语 — "今天/本月/上月/上周/2025-03/3月"。
     *
     * <p>失败模式静默 (返空 map) → 上层逻辑走原 NEED_MORE_INFO path。
     */
    private Map<String, Object> llmExtractMissingSlots(String userInput, List<RequiredSlot> missing) {
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate yesterday = today.minusDays(1);
            LocalDate firstOfThisMonth = today.withDayOfMonth(1);
            LocalDate lastOfThisMonth = today.withDayOfMonth(today.lengthOfMonth());
            LocalDate firstOfLastMonth = firstOfThisMonth.minusMonths(1);
            LocalDate lastOfLastMonth = firstOfThisMonth.minusDays(1);
            LocalDate mondayOfThisWeek = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            LocalDate sundayOfThisWeek = mondayOfThisWeek.plusDays(6);
            LocalDate mondayOfLastWeek = mondayOfThisWeek.minusDays(7);
            LocalDate sundayOfLastWeek = mondayOfThisWeek.minusDays(1);

            StringBuilder slotList = new StringBuilder();
            for (RequiredSlot s : missing) {
                slotList.append("- ").append(s.getName())
                    .append(" (").append(s.getType() == null ? "TEXT" : s.getType()).append(")");
                if (s.getLabel() != null && !s.getLabel().isEmpty()) {
                    slotList.append(": ").append(s.getLabel());
                }
                slotList.append("\n");
            }

            String systemPrompt = String.join("\n",
                "你是参数提取助手。从用户输入提取以下参数, 输出**严格 JSON**。",
                "",
                "今天: " + today.format(df),
                "昨天: " + yesterday.format(df),
                "本月: " + firstOfThisMonth.format(df) + " ~ " + lastOfThisMonth.format(df),
                "上月: " + firstOfLastMonth.format(df) + " ~ " + lastOfLastMonth.format(df),
                "本周: " + mondayOfThisWeek.format(df) + " ~ " + sundayOfThisWeek.format(df),
                "上周: " + mondayOfLastWeek.format(df) + " ~ " + sundayOfLastWeek.format(df),
                "",
                "需提取的参数:",
                slotList.toString(),
                "规则:",
                "1. 提取不到的参数, JSON 值设 null",
                "2. DATE 类型必须 YYYY-MM-DD 格式",
                "3. 短月份格式 'YYYY-MM' 表示当月范围 (e.g. '2025-03' 配 date_from/date_to 类参数 → date_from=2025-03-01, date_to=2025-03-31)",
                "4. 单一日期 'X月Y日' / 'YYYY-MM-DD' 配 date_from+date_to → 同日",
                "5. 参数名形如 date_from/date_to/start_date/end_date 时智能配对",
                "6. 输出**纯 JSON**, 无 markdown 代码块, 无解释文字",
                "",
                "输出格式: {\"param1\": \"value1\", \"param2\": \"value2\"}"
            );

            String response = pythonLLMClient.chatLowTemp(systemPrompt, userInput);
            if (response == null || response.isEmpty()) return Collections.emptyMap();

            // 去掉 markdown code fence (有些模型不听话)
            String json = response.trim();
            if (json.startsWith("```")) {
                int firstNl = json.indexOf('\n');
                if (firstNl > 0) json = json.substring(firstNl + 1);
                int lastFence = json.lastIndexOf("```");
                if (lastFence >= 0) json = json.substring(0, lastFence);
                json = json.trim();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = SLOT_OBJECT_MAPPER.readValue(json, Map.class);
            log.info("LLM 抽参成功: input='{}', extracted={}", userInput, parsed);
            return parsed;
        } catch (Exception e) {
            log.warn("LLM 抽参失败 (退化为 NEED_MORE_INFO): input='{}', error={}",
                userInput, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void extractFromPreprocessedQuery(PreprocessedQuery pq, Map<String, Object> extracted) {
        // 提取已解析的实体引用
        if (pq.getResolvedReferences() != null) {
            for (Map.Entry<String, PreprocessedQuery.ResolvedReference> entry : pq.getResolvedReferences().entrySet()) {
                PreprocessedQuery.ResolvedReference ref = entry.getValue();
                if (ref != null && ref.getEntityType() != null) {
                    String paramName = entityTypeToParamName(ref.getEntityType());
                    if (paramName != null && !extracted.containsKey(paramName)) {
                        extracted.put(paramName, ref.getEntityId());
                    }
                }
            }
        }

        // 提取时间范围
        if (pq.hasTimeRange()) {
            TimeNormalizationRules.TimeRange timeRange = pq.getPrimaryTimeRange();
            if (timeRange != null && timeRange.isValid()) {
                if (!extracted.containsKey("startDate")) {
                    extracted.put("startDate", timeRange.getStart().toLocalDate().toString());
                }
                if (!extracted.containsKey("endDate")) {
                    extracted.put("endDate", timeRange.getEnd().toLocalDate().toString());
                }
            }
        }
    }

    private void extractWithPatterns(String userInput, List<RequiredSlot> slots, Map<String, Object> extracted) {
        for (RequiredSlot slot : slots) {
            if (extracted.containsKey(slot.getName())) continue;

            String pattern = slot.getPattern();
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    Matcher matcher = Pattern.compile(pattern).matcher(userInput);
                    if (matcher.find()) {
                        String value = matcher.group(1);
                        if (value != null && !value.isEmpty()) {
                            extracted.put(slot.getName(), normalizeSlotValue(value, slot.getType()));
                            log.debug("正则提取参数: {}={}", slot.getName(), value);
                        }
                    }
                } catch (Exception e) {
                    log.debug("正则提取失败: slot={}, error={}", slot.getName(), e.getMessage());
                }
            }
        }
    }

    private void extractWithLearnedRules(String userInput, List<RequiredSlot> slots,
                                          Map<String, Object> extracted, IntentMatchResult matchResult,
                                          Map<String, Object> context) {
        List<String> remainingParams = slots.stream()
                .map(RequiredSlot::getName)
                .filter(name -> !extracted.containsKey(name))
                .collect(Collectors.toList());

        if (!remainingParams.isEmpty()) {
            try {
                String factoryId = context != null && context.containsKey("factoryId")
                        ? context.get("factoryId").toString() : null;
                Map<String, Object> learnedExtracted = parameterExtractionLearningService.extractWithLearnedRules(
                        factoryId,
                        matchResult.getBestMatch().getIntentCode(),
                        userInput,
                        remainingParams);
                extracted.putAll(learnedExtracted);
            } catch (Exception e) {
                log.debug("学习规则提取失败: {}", e.getMessage());
            }
        }
    }

    private String entityTypeToParamName(String entityType) {
        if (entityType == null) return null;
        switch (entityType.toUpperCase()) {
            case "BATCH": return "batchId";
            case "SUPPLIER": return "supplierId";
            case "PRODUCT": return "productId";
            case "CUSTOMER": return "customerId";
            case "MATERIAL_TYPE": return "materialTypeId";
            default: return null;
        }
    }

    private Object normalizeSlotValue(String value, String slotType) {
        if (value == null || slotType == null) return value;

        switch (slotType.toUpperCase()) {
            case "INTEGER":
                try {
                    return Integer.parseInt(value.replaceAll("[^\\d]", ""));
                } catch (NumberFormatException e) {
                    return value;
                }
            case "NUMBER":
                try {
                    return Double.parseDouble(value.replaceAll("[^\\d.]", ""));
                } catch (NumberFormatException e) {
                    return value;
                }
            case "BOOLEAN":
                return value.matches("(?i)true|yes|是|1");
            case "DATE":
                if (value.equals("今天")) return LocalDate.now().toString();
                if (value.equals("明天")) return LocalDate.now().plusDays(1).toString();
                if (value.equals("昨天")) return LocalDate.now().minusDays(1).toString();
                return value;
            default:
                return value;
        }
    }

    @Override
    public List<RequiredSlot> findMissingSlots(List<RequiredSlot> requiredSlots, Map<String, Object> extractedParams) {
        List<RequiredSlot> missing = new ArrayList<>();

        for (RequiredSlot slot : requiredSlots) {
            if (!slot.isRequired()) continue;

            Object value = extractedParams.get(slot.getName());
            if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                missing.add(slot);
            }
        }

        return missing;
    }

    @Override
    public IntentExecuteResponse startSlotFilling(
            String factoryId,
            Long userId,
            AIIntentConfig intent,
            List<RequiredSlot> missingSlots,
            Map<String, Object> extractedParams) {

        // 1. 生成澄清问题
        List<String> clarificationQuestions = generateClarificationQuestions(missingSlots, intent);

        // 2. 转换为 ConversationService 的参数格式
        List<ConversationService.RequiredParameter> requiredParams = missingSlots.stream()
                .map(slot -> ConversationService.RequiredParameter.builder()
                        .name(slot.getName())
                        .label(slot.getLabel())
                        .type(slot.getType())
                        .validationHint(slot.getValidationHint())
                        .collected(false)
                        .build())
                .collect(Collectors.toList());

        // 3. 创建参数收集会话
        String sessionId = null;
        Integer conversationRound = 1;
        Integer maxConversationRounds = 5;

        try {
            ConversationService.ConversationResponse conversationResp =
                    conversationService.startParameterCollection(
                            factoryId, userId, intent.getIntentCode(), intent.getIntentName(),
                            requiredParams, clarificationQuestions);

            if (conversationResp != null && conversationResp.getSessionId() != null) {
                sessionId = conversationResp.getSessionId();
                conversationRound = conversationResp.getCurrentRound();
                maxConversationRounds = conversationResp.getMaxRounds();
                log.info("Slot Filling 会话已创建: sessionId={}, intentCode={}, missingParams={}",
                        sessionId, intent.getIntentCode(),
                        missingSlots.stream().map(RequiredSlot::getName).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            log.warn("创建 Slot Filling 会话失败: {}", e.getMessage());
        }

        // 4. 构建响应消息
        String message = buildSlotFillingMessage(intent.getIntentName(), clarificationQuestions);

        // 5. 构建响应元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("slotFilling", true);
        metadata.put("missingSlots", missingSlots.stream()
                .map(s -> Map.of("name", s.getName(), "label", s.getLabel(), "type", s.getType()))
                .collect(Collectors.toList()));
        if (!extractedParams.isEmpty()) {
            metadata.put("extractedParams", extractedParams);
        }

        // 6. 为缺失参数提供可选项（如产品列表）
        List<IntentExecuteResponse.SuggestedAction> suggestedActions = buildSlotOptions(
                missingSlots, factoryId);

        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("NEED_MORE_INFO")
                .message(message)
                .clarificationQuestions(clarificationQuestions)
                .suggestedActions(suggestedActions.isEmpty() ? null : suggestedActions)
                .sessionId(sessionId)
                .conversationRound(conversationRound)
                .maxConversationRounds(maxConversationRounds)
                .metadata(metadata)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private List<String> generateClarificationQuestions(List<RequiredSlot> missingSlots, AIIntentConfig intent) {
        List<String> questions = new ArrayList<>();
        for (RequiredSlot slot : missingSlots) {
            questions.add(generateQuestionForSlot(slot, intent));
        }
        return questions;
    }

    private String generateQuestionForSlot(RequiredSlot slot, AIIntentConfig intent) {
        // 如果有验证提示，优先使用
        if (slot.getValidationHint() != null && !slot.getValidationHint().isEmpty()) {
            return slot.getValidationHint();
        }

        String label = slot.getLabel() != null ? slot.getLabel() : slot.getName();
        String type = slot.getType() != null ? slot.getType().toUpperCase() : "TEXT";

        // 实体引用型: 明确告诉用户说名称就行 —— 后端续轮会把名称解析成 ID。
        String entityLabel = ENTITY_REFERENCE_LABELS.get(slot.getName());
        if (entityLabel != null) {
            return "请问是哪个" + entityLabel + "？说名称就行";
        }

        switch (type) {
            case "BATCH_ID":
                return "请问您要操作哪个批次？请提供批次号或批次ID。";
            case "QUANTITY":
            case "NUMBER":
                return "请问" + label + "是多少？";
            case "DATE":
                return "请提供" + label + "（格式：YYYY-MM-DD 或 今天/明天）";
            case "SELECT":
                return "请选择" + label;
            case "BOOLEAN":
                return label + "？（是/否）";
            default:
                return "请提供" + label;
        }
    }

    /**
     * 为缺失的 slot 查询后端数据，构建可选项列表
     */
    private List<IntentExecuteResponse.SuggestedAction> buildSlotOptions(
            List<RequiredSlot> missingSlots, String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();

        for (RequiredSlot slot : missingSlots) {
            String name = slot.getName().toLowerCase();

            // productId → 查询产品列表
            if (name.contains("productid") && productTypeRepository != null) {
                try {
                    var products = productTypeRepository.findByFactoryId(factoryId);
                    if (products != null && !products.isEmpty()) {
                        int limit = Math.min(products.size(), 10);
                        for (int i = 0; i < limit; i++) {
                            var p = products.get(i);
                            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                                    .actionCode("SELECT_PARAM_productId_" + p.getId())
                                    .actionName(p.getName())
                                    .description(p.getCode() != null ? "编码: " + p.getCode() : null)
                                    .build());
                        }
                    }
                } catch (Exception e) {
                    log.warn("查询产品列表失败: {}", e.getMessage());
                }
            }
        }
        return actions;
    }

    private String buildSlotFillingMessage(String intentName, List<String> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("好的，我来帮您执行「").append(intentName != null ? intentName : "操作").append("」。\n\n");
        sb.append("需要您提供以下信息：\n");

        for (int i = 0; i < questions.size(); i++) {
            sb.append(i + 1).append(". ").append(questions.get(i)).append("\n");
        }

        sb.append("\n请直接告诉我，我会帮您完成操作。");
        return sb.toString();
    }

    private String getParameterLabel(String paramName) {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("batchId", "批次号"),
                Map.entry("batchNumber", "批次号"),
                Map.entry("quantity", "数量"),
                Map.entry("materialTypeId", "原材料类型"),
                Map.entry("productId", "产品"),
                Map.entry("supplierId", "供应商"),
                Map.entry("customerId", "客户"),
                Map.entry("reason", "原因"),
                Map.entry("status", "状态"),
                Map.entry("startDate", "开始日期"),
                Map.entry("endDate", "结束日期")
        );
        return labels.getOrDefault(paramName, paramName);
    }

    private String getParameterValidationHint(String paramName) {
        Map<String, String> hints = Map.ofEntries(
                Map.entry("batchId", "请提供批次号，例如：MT-20240115-001"),
                Map.entry("quantity", "请输入数量，可以带单位如 100kg"),
                Map.entry("startDate", "请提供日期，格式：YYYY-MM-DD 或 今天/明天"),
                Map.entry("endDate", "请提供日期，格式：YYYY-MM-DD 或 今天/明天")
        );
        return hints.get(paramName);
    }
}
