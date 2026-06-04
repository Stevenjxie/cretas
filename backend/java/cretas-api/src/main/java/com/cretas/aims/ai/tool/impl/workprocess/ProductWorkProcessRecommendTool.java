package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductWorkProcessRecommendTool extends AbstractBusinessTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final String REVIEW_NOTICE = "AI 建议，请核对";

    private final ProductTypeRepository productTypeRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;
    private final PythonLLMClient pythonLLMClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "canvas_product_work_process_recommend";
    }

    @Override
    public String getDescription() {
        return "新建产品后推荐产品工序链。按同 productCategory 的相似产品历史工序频次打分；无历史时基于产品名/类别调用 LLM 给建议草稿。只返回草稿，不写库。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("productTypeId", Map.of("type", "string", "description", "新建或待配置的产品类型ID"));
        properties.put("limit", Map.of("type", "integer", "description", "最多推荐几道工序，默认 5"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("productTypeId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productTypeId");
    }

    @Override
    @Transactional(readOnly = true)
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        RecommendationResult result = recommend(factoryId, getString(params, "productTypeId"), getInteger(params, "limit", DEFAULT_LIMIT));
        return buildSimpleResult(buildMessage(result), result);
    }

    @Transactional(readOnly = true)
    public RecommendationResult recommend(String factoryId, String productTypeId, int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 10);
        ProductType target = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + productTypeId));

        RecommendationResult historyResult = recommendFromHistory(factoryId, target, safeLimit);
        if (!historyResult.recommendations().isEmpty()) {
            return historyResult;
        }

        return recommendFromLlm(factoryId, target, safeLimit);
    }

    private RecommendationResult recommendFromHistory(String factoryId, ProductType target, int limit) {
        List<ProductType> similarProducts = productTypeRepository.findByFactoryId(factoryId).stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .filter(p -> !Objects.equals(p.getId(), target.getId()))
                .filter(p -> sameText(p.getProductCategory(), target.getProductCategory()))
                .toList();

        Map<String, ProcessStats> statsByProcessId = new LinkedHashMap<>();
        for (ProductType product : similarProducts) {
            List<ProductWorkProcess> chain = productWorkProcessRepository
                    .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, product.getId());
            for (ProductWorkProcess item : chain) {
                if (!Boolean.TRUE.equals(item.getIsActive())) {
                    continue;
                }
                statsByProcessId
                        .computeIfAbsent(item.getWorkProcessId(), ProcessStats::new)
                        .addOrder(item.getProcessOrder());
            }
        }

        if (statsByProcessId.isEmpty()) {
            return emptyResult(target.getId(), "NO_HISTORY", "未找到同产品大类的历史工序链，准备使用 LLM 冷启动建议");
        }

        List<ProcessStats> topStats = statsByProcessId.values().stream()
                .sorted(Comparator
                        .comparingInt(ProcessStats::frequency).reversed()
                        .thenComparingDouble(ProcessStats::averageOrder)
                        .thenComparing(ProcessStats::workProcessId))
                .limit(limit)
                .sorted(Comparator
                        .comparingDouble(ProcessStats::averageOrder)
                        .thenComparing(Comparator.comparingInt(ProcessStats::frequency).reversed())
                        .thenComparing(ProcessStats::workProcessId))
                .toList();

        List<String> ids = topStats.stream().map(ProcessStats::workProcessId).toList();
        Map<String, WorkProcess> workProcessById = workProcessRepository.findByFactoryIdAndIdIn(factoryId, ids).stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp, (a, b) -> a));

        List<RecommendedProcess> recommendations = new ArrayList<>();
        for (int i = 0; i < topStats.size(); i++) {
            ProcessStats stats = topStats.get(i);
            WorkProcess workProcess = workProcessById.get(stats.workProcessId());
            if (workProcess == null || !Boolean.TRUE.equals(workProcess.getIsActive())) {
                continue;
            }
            recommendations.add(toRecommendedProcess(workProcess, i + 1, stats.frequency(), "HISTORY"));
        }

        return new RecommendationResult(
                target.getId(),
                "HISTORY",
                REVIEW_NOTICE,
                String.format("基于 %d 个同产品大类历史产品聚合生成", similarProducts.size()),
                recommendations);
    }

    private RecommendationResult recommendFromLlm(String factoryId, ProductType target, int limit) {
        if (pythonLLMClient == null) {
            return emptyResult(target.getId(), "LLM_UNAVAILABLE", "没有历史工序链，且 LLM 客户端未配置，无法生成冷启动建议");
        }

        List<WorkProcess> catalog = workProcessRepository.findByFactoryIdAndIsActiveTrueOrderBySortOrderAsc(factoryId);
        if (catalog.isEmpty()) {
            return emptyResult(target.getId(), "NO_CATALOG", "本厂还没有可用工序定义，请先到工序管理创建工序");
        }

        String systemPrompt = "你是食品工厂工序配置助手。只能从给定工序 catalog 中选择，返回严格 JSON 数组，格式为 [{\"processName\":\"修整\"}]。不要解释。";
        String userInput = """
                产品名: %s
                产品大类: %s
                温区: %s
                单位: %s
                最多推荐 %d 道。
                可选工序 catalog:
                %s
                """.formatted(
                safeText(target.getName()),
                safeText(target.getProductCategory()),
                safeText(target.getTemperatureZone()),
                safeText(target.getUnit()),
                limit,
                catalog.stream()
                        .map(wp -> "- " + wp.getProcessName() + " / " + safeText(wp.getProcessCategory()) + " / " + safeText(wp.getUnit()))
                        .collect(Collectors.joining("\n")));

        ChatCompletionResponse response = pythonLLMClient.chatCompletion(
                ChatCompletionRequest.builder()
                        .model("qwen-plus")
                        .temperature(0.2)
                        .maxTokens(800)
                        .enableThinking(false)
                        .messages(List.of(
                                com.cretas.aims.ai.dto.ChatMessage.system(systemPrompt),
                                com.cretas.aims.ai.dto.ChatMessage.user(userInput)))
                        .build());

        if (response == null || response.hasError() || response.getContent() == null || response.getContent().isBlank()) {
            String reason = response != null && response.hasError() ? response.getErrorMessage() : "LLM 无有效响应";
            return emptyResult(target.getId(), "LLM_UNAVAILABLE", "没有历史工序链，LLM 冷启动建议失败: " + safeText(reason));
        }

        List<String> suggestedNames = parseProcessNames(response.getContent());
        if (suggestedNames.isEmpty()) {
            return emptyResult(target.getId(), "LLM_EMPTY", "LLM 未返回可识别的工序名称，请手动配置");
        }

        Map<String, WorkProcess> catalogByName = catalog.stream()
                .collect(Collectors.toMap(WorkProcess::getProcessName, wp -> wp, (a, b) -> a, LinkedHashMap::new));
        List<RecommendedProcess> recommendations = new ArrayList<>();
        for (String name : suggestedNames) {
            WorkProcess workProcess = catalogByName.get(name);
            if (workProcess == null || recommendations.stream().anyMatch(r -> r.workProcessId().equals(workProcess.getId()))) {
                continue;
            }
            recommendations.add(toRecommendedProcess(workProcess, recommendations.size() + 1, 1, "LLM"));
            if (recommendations.size() >= limit) {
                break;
            }
        }

        if (recommendations.isEmpty()) {
            return emptyResult(target.getId(), "LLM_NO_MATCH", "LLM 建议没有命中本厂工序 catalog，请手动配置或先补充工序定义");
        }

        return new RecommendationResult(target.getId(), "LLM", REVIEW_NOTICE, "无历史产品时由 LLM 根据产品名和类别生成", recommendations);
    }

    private List<String> parseProcessNames(String content) {
        String json = stripCodeFence(content.trim());
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode item : node) {
                String name = Optional.ofNullable(item.get("processName"))
                        .map(JsonNode::asText)
                        .orElse("");
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("解析 LLM 工序推荐失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstLineEnd = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
            return content.substring(firstLineEnd + 1, lastFence).trim();
        }
        return content;
    }

    private RecommendedProcess toRecommendedProcess(WorkProcess workProcess, int order, int score, String reason) {
        return new RecommendedProcess(
                workProcess.getId(),
                workProcess.getProcessName(),
                workProcess.getProcessCategory(),
                workProcess.getUnit(),
                workProcess.getEstimatedMinutes(),
                order,
                score,
                reason);
    }

    private RecommendationResult emptyResult(String productTypeId, String source, String message) {
        return new RecommendationResult(productTypeId, source, REVIEW_NOTICE, message, List.of());
    }

    private String buildMessage(RecommendationResult result) {
        int count = result.recommendations().size();
        if (count == 0) {
            return result.message();
        }
        return String.format("AI 已推荐 %d 道工序，去查看前请核对", count);
    }

    private boolean sameText(String left, String right) {
        return safeText(left).equalsIgnoreCase(safeText(right));
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    public record RecommendationResult(
            String productTypeId,
            String source,
            String notice,
            String message,
            List<RecommendedProcess> recommendations) {
    }

    public record RecommendedProcess(
            String workProcessId,
            String processName,
            String processCategory,
            String unit,
            Integer estimatedMinutes,
            int processOrder,
            int score,
            String reason) {
    }

    private static final class ProcessStats {
        private final String workProcessId;
        private int frequency;
        private int orderTotal;

        private ProcessStats(String workProcessId) {
            this.workProcessId = workProcessId;
        }

        private void addOrder(Integer processOrder) {
            frequency++;
            orderTotal += processOrder == null ? frequency : processOrder;
        }

        private String workProcessId() {
            return workProcessId;
        }

        private int frequency() {
            return frequency;
        }

        private double averageOrder() {
            return frequency == 0 ? 0 : (double) orderTotal / frequency;
        }
    }
}
