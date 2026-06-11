package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.tool.ToolEmbedding;
import com.cretas.aims.repository.ToolEmbeddingRepository;
import com.cretas.aims.config.ArenaRLConfig;
import com.cretas.aims.dto.arena.TournamentResult;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.arena.ArenaRLTournamentService;
import com.cretas.aims.util.VectorUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 动态工具选择服务实现 (模块D)
 *
 * 核心功能:
 * 1. 向量检索: 使用 EmbeddingClient 将查询向量化，与工具向量计算相似度
 * 2. LLM 精选: 使用 DashScopeClient 从候选工具中选择最合适的组合
 * 3. 工具执行: 按照选定顺序执行工具链
 * 4. 向量缓存: 内存缓存工具向量，减少数据库访问
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Service
@Slf4j
public class ToolRouterServiceImpl implements ToolRouterService {

    @Autowired
    private ToolEmbeddingRepository toolEmbeddingRepository;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private ObjectMapper objectMapper;

    // W0 write-guard (intent-w0) — SITE E. executeToolChain → executeParallel/executeSequential →
    // executeSingleTool calls ToolExecutor.execute() DIRECTLY, bypassing ToolDispatchService
    // (Site B). Guard here so a dynamically-selected write tool cannot silently execute without
    // confirm. WriteGuardService is stateless → safe to call from the executorService worker pool.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE E: central RBAC enforce alongside the W0 write-guard.
    @Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    @Autowired(required = false)
    private ArenaRLTournamentService arenaRLTournamentService;

    @Autowired(required = false)
    private ArenaRLConfig arenaRLConfig;

    // ==================== 配置项 ====================

    @Value("${ai.tool_router.enabled:true}")
    private boolean enabled;

    @Value("${ai.tool_router.candidate_top_k:10}")
    private int candidateTopK;

    @Value("${ai.tool_router.min_similarity:0.5}")
    private double minSimilarity;

    @Value("${ai.tool_router.dynamic_selection_enabled:true}")
    private boolean dynamicSelectionEnabled;

    // ==================== 内存缓存 ====================

    /**
     * 工具向量内存缓存: toolName -> float[]
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentHashMap<String, float[]> toolEmbeddingCache = new ConcurrentHashMap<>();

    /**
     * 工具执行线程池 (用于并行执行)
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    // ==================== 核心方法实现 ====================

    @Override
    public boolean requiresDynamicSelection(IntentMatchResult intentResult) {
        if (!enabled || !dynamicSelectionEnabled) {
            return false;
        }

        // 条件1: 没有绑定的工具
        if (intentResult.getBestMatch() == null ||
            intentResult.getBestMatch().getToolName() == null ||
            intentResult.getBestMatch().getToolName().isEmpty()) {
            log.debug("Dynamic selection required: no bound tool");
            return true;
        }

        // 条件2: 匹配置信度过低 (可能需要多工具)
        if (intentResult.getConfidence() != null && intentResult.getConfidence() < 0.5) {
            log.debug("Dynamic selection required: low confidence {}", intentResult.getConfidence());
            return true;
        }

        // 条件3: 多候选意图且差距小 (可能是复杂意图)
        if (intentResult.getTopCandidates() != null && intentResult.getTopCandidates().size() >= 2) {
            var top1 = intentResult.getTopCandidates().get(0);
            var top2 = intentResult.getTopCandidates().get(1);
            if (top1.getConfidence() - top2.getConfidence() < 0.15) {
                log.debug("Dynamic selection required: multiple candidates with small gap");
                return true;
            }
        }

        return false;
    }

    @Override
    public List<ToolCandidate> retrieveCandidateTools(String query, int topK) {
        if (toolEmbeddingCache.isEmpty()) {
            log.warn("Tool embedding cache is empty, returning empty candidates");
            return Collections.emptyList();
        }

        // 1. 生成查询向量
        float[] queryEmbedding;
        try {
            queryEmbedding = embeddingClient.encode(query);
        } catch (Exception e) {
            log.error("Failed to encode query: {}", query, e);
            return Collections.emptyList();
        }

        // 2. 计算与所有工具的相似度
        List<ToolCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : toolEmbeddingCache.entrySet()) {
            String toolName = entry.getKey();
            float[] toolEmbedding = entry.getValue();

            double similarity = VectorUtils.cosineSimilarity(queryEmbedding, toolEmbedding);

            // 过滤低相似度工具
            if (similarity >= minSimilarity) {
                // 从数据库获取工具详情
                ToolEmbedding toolInfo = toolEmbeddingRepository.findByToolName(toolName).orElse(null);
                if (toolInfo != null) {
                    candidates.add(ToolCandidate.builder()
                        .toolName(toolName)
                        .toolDescription(toolInfo.getToolDescription())
                        .toolCategory(toolInfo.getToolCategory())
                        .similarity(similarity)
                        .keywords(toolInfo.getKeywords())
                        .build());
                }
            }
        }

        // 3. 排序并返回 Top K
        List<ToolCandidate> result = candidates.stream()
            .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
            .limit(topK > 0 ? topK : candidateTopK)
            .collect(Collectors.toList());

        log.info("Retrieved {} candidate tools for query: '{}' (topK={})",
                result.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query, topK);

        return result;
    }

    @Override
    public SelectedTools selectTools(String query, IntentMatchResult intentResult,
                                     List<ToolCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            log.warn("No candidates provided for tool selection");
            return SelectedTools.builder()
                .tools(Collections.emptyList())
                .executionOrder(SelectedTools.ExecutionOrder.SEQUENTIAL)
                .toolChainDescription("No tools available")
                .build();
        }

        // 构建 LLM Prompt
        String prompt = buildToolSelectionPrompt(query, intentResult, candidates);

        // 调用 LLM
        String response;
        try {
            response = dashScopeClient.chat(prompt, query);
        } catch (Exception e) {
            log.error("LLM tool selection failed", e);
            // Fallback: 返回相似度最高的工具
            return fallbackToTopCandidate(candidates);
        }

        // 解析响应
        return parseToolSelectionResponse(response, candidates);
    }

    @Override
    @Transactional
    public Object executeToolChain(SelectedTools selectedTools, Map<String, Object> context) {
        if (selectedTools == null || selectedTools.getTools() == null || selectedTools.getTools().isEmpty()) {
            return Map.of("success", false, "message", "No tools to execute");
        }

        List<SelectedTools.SelectedTool> tools = selectedTools.getTools();
        Map<String, Object> results = new LinkedHashMap<>();

        if (selectedTools.getExecutionOrder() == SelectedTools.ExecutionOrder.PARALLEL) {
            // 并行执行
            results = executeParallel(tools, context);
        } else {
            // 串行执行
            results = executeSequential(tools, context);
        }

        // 更新使用统计
        for (SelectedTools.SelectedTool tool : tools) {
            try {
                toolEmbeddingRepository.incrementUsage(tool.getToolName(), LocalDateTime.now());
            } catch (Exception e) {
                log.warn("Failed to update usage stats for tool: {}", tool.getToolName(), e);
            }
        }

        return results;
    }

    @Override
    @PostConstruct
    public void initializeToolEmbeddings() {
        log.info("Initializing tool embeddings...");

        // 获取所有注册的工具
        List<Tool> tools = toolRegistry.getAllToolDefinitions();
        if (tools.isEmpty()) {
            log.warn("No tools registered in ToolRegistry");
            return;
        }

        int newCount = 0;
        int cachedCount = 0;

        for (Tool tool : tools) {
            String toolName = tool.getFunction().getName();
            String description = tool.getFunction().getDescription();

            try {
                // 检查是否已有向量
                Optional<ToolEmbedding> existing = toolEmbeddingRepository.findByToolName(toolName);

                if (existing.isEmpty()) {
                    // 新工具: 生成向量并保存
                    float[] embedding = embeddingClient.encode(description);

                    ToolEmbedding toolEmbedding = ToolEmbedding.builder()
                        .toolName(toolName)
                        .toolDescription(description)
                        .toolCategory(extractCategory(toolName))
                        .build();
                    toolEmbedding.setEmbeddingFromFloats(embedding);

                    toolEmbeddingRepository.save(toolEmbedding);
                    toolEmbeddingCache.put(toolName, embedding);
                    newCount++;

                    log.debug("Generated embedding for new tool: {}", toolName);

                } else if (existing.get().getEmbeddingVector() == null) {
                    // 已有记录但无向量: 生成向量
                    float[] embedding = embeddingClient.encode(description);

                    ToolEmbedding toolEmbedding = existing.get();
                    toolEmbedding.setToolDescription(description);
                    toolEmbedding.setEmbeddingFromFloats(embedding);

                    toolEmbeddingRepository.save(toolEmbedding);
                    toolEmbeddingCache.put(toolName, embedding);
                    newCount++;

                    log.debug("Generated embedding for existing tool: {}", toolName);

                } else {
                    // 已有向量: 加载到缓存
                    toolEmbeddingCache.put(toolName, existing.get().getEmbeddingAsFloats());
                    cachedCount++;
                }

            } catch (Exception e) {
                log.error("Failed to initialize embedding for tool: {}", toolName, e);
            }
        }

        log.info("Tool embeddings initialized: total={}, new={}, cached={}",
                toolEmbeddingCache.size(), newCount, cachedCount);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建工具选择 Prompt
     */
    private String buildToolSelectionPrompt(String query, IntentMatchResult intentResult,
                                           List<ToolCandidate> candidates) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 角色\n");
        sb.append("你是工具选择助手。根据用户需求，从候选工具中选择最合适的工具组合。\n\n");

        sb.append("## 用户需求\n");
        sb.append(query).append("\n\n");

        if (intentResult != null && intentResult.getBestMatch() != null) {
            sb.append("## 已识别意图\n");
            sb.append("- 意图代码: ").append(intentResult.getBestMatch().getIntentCode()).append("\n");
            sb.append("- 意图名称: ").append(intentResult.getBestMatch().getIntentName()).append("\n");
            sb.append("- 置信度: ").append(String.format("%.2f", intentResult.getConfidence())).append("\n\n");
        }

        sb.append("## 候选工具 (按相关性排序)\n");
        int i = 1;
        for (ToolCandidate candidate : candidates) {
            sb.append(i++).append(". **").append(candidate.getToolName()).append("** ");
            sb.append("(相似度: ").append(String.format("%.2f", candidate.getSimilarity())).append(")\n");
            sb.append("   描述: ").append(candidate.getToolDescription()).append("\n");
            if (candidate.getKeywords() != null && !candidate.getKeywords().isEmpty()) {
                sb.append("   关键词: ").append(String.join(", ", candidate.getKeywords())).append("\n");
            }
        }

        sb.append("\n## 选择规则\n");
        sb.append("1. 选择能满足用户需求的最少工具数量\n");
        sb.append("2. 如果工具之间有依赖，使用 sequential 顺序；否则使用 parallel\n");
        sb.append("3. 为每个选中的工具说明选择理由\n");

        sb.append("\n## 输出格式 (严格 JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"selected_tools\": [\n");
        sb.append("    {\n");
        sb.append("      \"tool_name\": \"工具名称\",\n");
        sb.append("      \"reason\": \"选择理由\",\n");
        sb.append("      \"order\": 1\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"execution_order\": \"parallel 或 sequential\",\n");
        sb.append("  \"description\": \"工具链说明\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 工具选择响应
     */
    private SelectedTools parseToolSelectionResponse(String response, List<ToolCandidate> candidates) {
        try {
            // 提取 JSON 部分
            String json = extractJson(response);
            if (json == null) {
                log.warn("No JSON found in LLM response, using fallback");
                return fallbackToTopCandidate(candidates);
            }

            JsonNode root = objectMapper.readTree(json);

            // 解析 selected_tools
            List<SelectedTools.SelectedTool> tools = new ArrayList<>();
            JsonNode toolsNode = root.get("selected_tools");
            if (toolsNode != null && toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.has("tool_name") ?
                        toolNode.get("tool_name").asText() : null;
                    String reason = toolNode.has("reason") ?
                        toolNode.get("reason").asText() : "";
                    int order = toolNode.has("order") ?
                        toolNode.get("order").asInt() : tools.size() + 1;

                    if (toolName != null && !toolName.isEmpty()) {
                        // 验证工具存在
                        if (toolRegistry.hasExecutor(toolName)) {
                            tools.add(SelectedTools.SelectedTool.builder()
                                .toolName(toolName)
                                .reason(reason)
                                .order(order)
                                .build());
                        } else {
                            log.warn("LLM selected non-existent tool: {}", toolName);
                        }
                    }
                }
            }

            // 解析 execution_order
            SelectedTools.ExecutionOrder order = SelectedTools.ExecutionOrder.SEQUENTIAL;
            if (root.has("execution_order")) {
                String orderStr = root.get("execution_order").asText().toLowerCase();
                if ("parallel".equals(orderStr)) {
                    order = SelectedTools.ExecutionOrder.PARALLEL;
                }
            }

            // 解析 description
            String description = root.has("description") ?
                root.get("description").asText() : "Tool chain selected by LLM";

            // 如果没有选中任何工具，fallback
            if (tools.isEmpty()) {
                return fallbackToTopCandidate(candidates);
            }

            return SelectedTools.builder()
                .tools(tools)
                .executionOrder(order)
                .toolChainDescription(description)
                .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM response: {}", response, e);
            return fallbackToTopCandidate(candidates);
        }
    }

    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // 尝试找到 JSON 代码块
        int start = response.indexOf("```json");
        if (start != -1) {
            start = response.indexOf("\n", start) + 1;
            int end = response.indexOf("```", start);
            if (end != -1) {
                return response.substring(start, end).trim();
            }
        }

        // 尝试找到 { } 括号
        start = response.indexOf("{");
        if (start != -1) {
            int end = response.lastIndexOf("}");
            if (end != -1 && end > start) {
                return response.substring(start, end + 1);
            }
        }

        return null;
    }

    /**
     * Fallback: 返回相似度最高的工具
     */
    private SelectedTools fallbackToTopCandidate(List<ToolCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return SelectedTools.builder()
                .tools(Collections.emptyList())
                .executionOrder(SelectedTools.ExecutionOrder.SEQUENTIAL)
                .toolChainDescription("No tools available")
                .build();
        }

        ToolCandidate top = candidates.get(0);
        return SelectedTools.builder()
            .tools(List.of(SelectedTools.SelectedTool.builder()
                .toolName(top.getToolName())
                .reason("Fallback: highest similarity (" + String.format("%.2f", top.getSimilarity()) + ")")
                .order(1)
                .build()))
            .executionOrder(SelectedTools.ExecutionOrder.SEQUENTIAL)
            .toolChainDescription("Fallback selection based on similarity")
            .build();
    }

    /**
     * 并行执行工具
     */
    private Map<String, Object> executeParallel(List<SelectedTools.SelectedTool> tools,
                                                 Map<String, Object> context) {
        Map<String, Future<Object>> futures = new HashMap<>();

        for (SelectedTools.SelectedTool tool : tools) {
            futures.put(tool.getToolName(), executorService.submit(() -> {
                return executeSingleTool(tool.getToolName(), context);
            }));
        }

        Map<String, Object> results = new LinkedHashMap<>();
        for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.error("Tool execution failed: {}", entry.getKey(), e);
                results.put(entry.getKey(), Map.of("error", e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 串行执行工具
     */
    private Map<String, Object> executeSequential(List<SelectedTools.SelectedTool> tools,
                                                   Map<String, Object> context) {
        // 按 order 排序
        List<SelectedTools.SelectedTool> sorted = tools.stream()
            .sorted(Comparator.comparingInt(SelectedTools.SelectedTool::getOrder))
            .collect(Collectors.toList());

        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, Object> chainContext = new HashMap<>(context);

        for (SelectedTools.SelectedTool tool : sorted) {
            try {
                Object result = executeSingleTool(tool.getToolName(), chainContext);
                results.put(tool.getToolName(), result);

                // 将结果添加到上下文供后续工具使用
                chainContext.put("previous_" + tool.getToolName(), result);
            } catch (Exception e) {
                log.error("Tool execution failed, stopping chain: {}", tool.getToolName(), e);
                results.put(tool.getToolName(), Map.of("error", e.getMessage()));
                break;
            }
        }

        return results;
    }

    /**
     * 执行单个工具
     */
    private Object executeSingleTool(String toolName, Map<String, Object> context) throws Exception {
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }

        ToolExecutor executor = executorOpt.get();

        // W0 write-guard (intent-w0) — SITE E: this path calls executor.execute() directly, bypassing
        // ToolDispatchService (Site B). Block a write tool unless the chain context carries a
        // confirmed=true signal. Exception-based (mirrors the tool-not-found throw above); callers
        // (executeParallel/executeSequential) catch Exception → record per-tool error + stop chain.
        if (writeGuardService.isWriteTool(executor) && !writeGuardService.isConfirmed(context)) {
            log.info("W0 write-guard (tool-chain): blocked write tool {} (confirmed=false)", toolName);
            throw new IllegalStateException("W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
        }

        // W9 红线 (AI-RBAC 系统性收口) — SITE E: central RBAC enforce. This path calls executor.execute()
        // directly, bypassing ToolDispatchService (Site B). Exception-based (mirrors W0 above); callers
        // catch Exception → per-tool error + stop chain.
        if (!toolRbacEnforcer.isAllowed(executor, context)) {
            log.warn("W9 AI-RBAC (tool-chain): denied tool={}", toolName);
            throw new IllegalStateException(toolRbacEnforcer.denyMessage(executor, context));
        }

        // 构建 ToolCall (简化版，实际参数需要从上下文提取)
        ToolCall toolCall = ToolCall.builder()
            .id(UUID.randomUUID().toString())
            .type("function")
            .function(ToolCall.FunctionCall.builder()
                .name(toolName)
                .arguments(objectMapper.writeValueAsString(context))
                .build())
            .build();

        long startTime = System.currentTimeMillis();
        String result = executor.execute(toolCall, context);
        long duration = System.currentTimeMillis() - startTime;

        // 更新执行时间统计
        try {
            toolEmbeddingRepository.updateAvgExecutionTime(toolName, (int) duration);
        } catch (Exception e) {
            log.warn("Failed to update execution time for tool: {}", toolName);
        }

        log.info("Tool {} executed in {}ms", toolName, duration);

        // 尝试解析为 JSON
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            return result;
        }
    }

    /**
     * 从工具名称提取分类
     */
    private String extractCategory(String toolName) {
        if (toolName == null) {
            return "unknown";
        }

        // 根据命名约定推断分类
        if (toolName.contains("query") || toolName.contains("get") || toolName.contains("list")) {
            return "data_query";
        } else if (toolName.contains("create") || toolName.contains("add")) {
            return "data_create";
        } else if (toolName.contains("update") || toolName.contains("modify")) {
            return "data_update";
        } else if (toolName.contains("delete") || toolName.contains("remove")) {
            return "data_delete";
        } else if (toolName.contains("form")) {
            return "form_assist";
        } else if (toolName.contains("intent")) {
            return "intent_management";
        } else if (toolName.contains("quality") || toolName.contains("check")) {
            return "quality_control";
        } else if (toolName.contains("production") || toolName.contains("plan")) {
            return "production";
        } else if (toolName.contains("material") || toolName.contains("batch")) {
            return "material";
        } else {
            return "general";
        }
    }

    /**
     * 刷新工具向量缓存 (外部调用)
     */
    public void refreshToolEmbeddingCache() {
        log.info("Refreshing tool embedding cache...");
        toolEmbeddingCache.clear();
        initializeToolEmbeddings();
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cachedTools", toolEmbeddingCache.size(),
            "registeredTools", toolRegistry.getToolCount(),
            "enabled", enabled,
            "dynamicSelectionEnabled", dynamicSelectionEnabled,
            "candidateTopK", candidateTopK,
            "minSimilarity", minSimilarity
        );
    }

    // ==================== ArenaRL 锦标赛裁决实现 ====================

    /**
     * 使用 ArenaRL 锦标赛进行工具选择歧义裁决
     *
     * 触发条件: top1-top2 相似度差 < 0.10 且 top1 < 0.80
     *
     * @param query 用户查询
     * @param candidates 歧义候选工具列表 (已按相似度排序)
     * @return ArenaRL 裁决结果
     */
    @Override
    public ArenaRLToolResult disambiguateToolsWithArenaRL(String query, List<ToolCandidate> candidates) {
        log.info("[ArenaRL] Starting tool disambiguation for query: '{}', candidates: {}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query, candidates.size());

        // 1. 检查 ArenaRL 是否启用
        if (arenaRLConfig == null || !arenaRLConfig.isToolSelectionEnabled()) {
            log.debug("[ArenaRL] Tool selection not enabled, falling back to top candidate");
            if (candidates == null || candidates.isEmpty()) {
                return ArenaRLToolResult.failure("No candidates provided");
            }
            ToolCandidate top = candidates.get(0);
            return ArenaRLToolResult.success(top.getToolName(), top.getSimilarity(),
                    "ArenaRL disabled, using top candidate", 0);
        }

        // 2. 检查 ArenaRL 服务可用性
        if (arenaRLTournamentService == null) {
            log.warn("[ArenaRL] Tournament service not available, falling back to top candidate");
            ToolCandidate top = candidates.get(0);
            return ArenaRLToolResult.success(top.getToolName(), top.getSimilarity(),
                    "ArenaRL service unavailable, using top candidate", 0);
        }

        // 3. 验证候选数量
        if (candidates == null || candidates.size() < 2) {
            log.debug("[ArenaRL] Not enough candidates (<2), using top candidate");
            if (candidates != null && !candidates.isEmpty()) {
                ToolCandidate top = candidates.get(0);
                return ArenaRLToolResult.success(top.getToolName(), top.getSimilarity(),
                        "Only one candidate, no tournament needed", 0);
            }
            return ArenaRLToolResult.failure("No candidates provided");
        }

        // 4. 检查是否需要触发锦标赛
        double top1Similarity = candidates.get(0).getSimilarity();
        double top2Similarity = candidates.get(1).getSimilarity();
        if (!arenaRLConfig.shouldTriggerToolTournament(top1Similarity, top2Similarity)) {
            log.debug("[ArenaRL] Trigger conditions not met (gap={:.2f}), using top candidate",
                    top1Similarity - top2Similarity);
            ToolCandidate top = candidates.get(0);
            return ArenaRLToolResult.success(top.getToolName(), top.getSimilarity(),
                    "Ambiguity threshold not met, using top candidate", 0);
        }

        try {
            // 5. 转换为 ArenaRL 候选格式
            List<ArenaRLTournamentService.ToolCandidate> arenaCandidates = candidates.stream()
                    .limit(arenaRLConfig.getToolMaxCandidates())
                    .map(c -> ArenaRLTournamentService.ToolCandidate.builder()
                            .id(c.getToolName())
                            .name(c.getToolName())
                            .description(c.getToolDescription())
                            .score(c.getSimilarity())
                            .metadata(Map.of("category", c.getToolCategory() != null ? c.getToolCategory() : ""))
                            .build())
                    .collect(Collectors.toList());

            // 6. 执行锦标赛
            log.info("[ArenaRL] Executing tool tournament with {} candidates", arenaCandidates.size());
            long startTime = System.currentTimeMillis();

            TournamentResult tournamentResult = arenaRLTournamentService.runToolTournament(
                    query, arenaCandidates);

            long latencyMs = System.currentTimeMillis() - startTime;

            // 7. 处理锦标赛结果
            if (tournamentResult == null || !Boolean.TRUE.equals(tournamentResult.getSuccess())) {
                log.warn("[ArenaRL] Tool tournament failed: {}",
                        tournamentResult != null ? tournamentResult.getErrorMessage() : "null result");
                ToolCandidate top = candidates.get(0);
                return ArenaRLToolResult.builder()
                        .success(true)
                        .winnerToolName(top.getToolName())
                        .winnerConfidence(top.getSimilarity())
                        .reasoning("Tournament failed, falling back to top candidate: " +
                                (tournamentResult != null ? tournamentResult.getErrorMessage() : "null result"))
                        .comparisonCount(0)
                        .totalLatencyMs(latencyMs)
                        .build();
            }

            // 8. 构建成功结果
            log.info("[ArenaRL] Tool tournament completed: winner={}, confidence={:.2f}, comparisons={}, latency={}ms",
                    tournamentResult.getWinnerId(),
                    tournamentResult.getWinnerConfidence(),
                    tournamentResult.getTotalComparisons(),
                    latencyMs);

            // 构建推理说明
            String reasoning = buildToolArenaRLReasoning(tournamentResult, candidates);

            return ArenaRLToolResult.builder()
                    .success(true)
                    .winnerToolName(tournamentResult.getWinnerId())
                    .winnerConfidence(tournamentResult.getWinnerConfidence())
                    .reasoning(reasoning)
                    .comparisonCount(tournamentResult.getTotalComparisons())
                    .totalLatencyMs(latencyMs)
                    .tournamentId(tournamentResult.getTournamentId())
                    .build();

        } catch (Exception e) {
            log.error("[ArenaRL] Tool tournament execution failed", e);
            ToolCandidate top = candidates.get(0);
            return ArenaRLToolResult.builder()
                    .success(true)
                    .winnerToolName(top.getToolName())
                    .winnerConfidence(top.getSimilarity())
                    .reasoning("Tournament exception, falling back to top candidate: " + e.getMessage())
                    .comparisonCount(0)
                    .build();
        }
    }

    /**
     * 构建 ArenaRL 工具选择推理说明
     */
    private String buildToolArenaRLReasoning(TournamentResult result, List<ToolCandidate> originalCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("ArenaRL 工具锦标赛裁决结果：\n");

        // 原始排名
        sb.append("原始候选排名：");
        for (int i = 0; i < Math.min(3, originalCandidates.size()); i++) {
            ToolCandidate c = originalCandidates.get(i);
            sb.append(String.format("%s(%.2f)", c.getToolName(), c.getSimilarity()));
            if (i < Math.min(3, originalCandidates.size()) - 1) sb.append(" > ");
        }
        sb.append("\n");

        // 锦标赛结果
        sb.append(String.format("锦标赛冠军：%s (置信度提升至 %.2f)\n",
                result.getWinnerId(), result.getWinnerConfidence()));
        sb.append(String.format("比较次数：%d，总耗时：%dms",
                result.getTotalComparisons(), result.getTotalLatencyMs()));

        // 如果冠军与原始 top1 不同，标注
        if (!originalCandidates.isEmpty() &&
                !originalCandidates.get(0).getToolName().equals(result.getWinnerId())) {
            sb.append("\n[注意] 锦标赛结果与原始排名不同，已调整工具选择");
        }

        return sb.toString();
    }
}
