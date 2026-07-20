package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recommends a traceable process chain by copying one complete published,
 * product-owned workflow. Legacy ProductWorkProcess rows and LLM guesses are
 * intentionally excluded because neither can prove a complete source chain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductWorkProcessRecommendTool extends AbstractBusinessTool {

    private static final String REVIEW_NOTICE = "AI 建议，请核对";
    private static final String SOURCE = "PUBLISHED_WORKFLOW";
    private static final String SOURCE_SCOPE = "PRODUCT_OWNED";

    private final ProductTypeRepository productTypeRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductProcessWorkflowValidator workflowValidator;
    private final ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "canvas_product_work_process_recommend";
    }

    @Override
    public String getDescription() {
        return "只从同 productCategory 的单一历史 SKU 最新已发布完整 Workflow 返回可追溯复制候选；只读且不写库。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "productTypeId", Map.of("type", "string", "description", "待配置产品类型 ID"),
                        "limit", Map.of("type", "integer", "description", "兼容字段；完整来源链不会被截断")),
                "required", List.of("productTypeId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productTypeId");
    }

    @Override
    @Transactional(readOnly = true)
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        RecommendationResult result = recommend(
                factoryId,
                getString(params, "productTypeId"),
                getInteger(params, "limit", 5));
        return buildSimpleResult(buildMessage(result), result);
    }

    @Transactional(readOnly = true)
    public RecommendationResult recommend(String factoryId, String productTypeId, int ignoredLimit) {
        ProductType target = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + productTypeId));

        List<WorkflowCandidate> candidates = productTypeRepository.findByFactoryId(factoryId).stream()
                .filter(product -> Boolean.TRUE.equals(product.getIsActive()))
                .filter(product -> !Objects.equals(product.getId(), target.getId()))
                .filter(product -> sameText(product.getProductCategory(), target.getProductCategory()))
                .filter(product -> sameProductFamily(target, product))
                .map(product -> candidate(factoryId, product))
                .flatMap(Optional::stream)
                .sorted(Comparator
                        .comparing((WorkflowCandidate value) -> value.workflow().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(value -> value.sourceProduct().getId()))
                .toList();

        if (candidates.isEmpty()) {
            return emptyResult(
                    target.getId(),
                    "NO_RELATED_COMPLETE_PUBLISHED_WORKFLOW",
                    "没有同源产品族且可证明完整的已发布 product-owned Workflow；未跨产品猜测或推荐");
        }

        ResolvedCandidate selected = candidates.stream()
                .map(candidate -> resolveCandidate(factoryId, candidate))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return emptyResult(
                    target.getId(),
                    "SOURCE_PROCESS_UNAVAILABLE",
                    "候选 Workflow 引用了不存在或已停用的工序定义，已按 fail closed 拒绝推荐");
        }

        List<RecommendedProcess> recommendations = new ArrayList<>();
        for (int index = 0; index < selected.candidate().processNodes().size(); index++) {
            ProductProcessWorkflowDTO.Node node = selected.candidate().processNodes().get(index);
            WorkProcess process = selected.workProcesses().get(workProcessId(node));
            recommendations.add(new RecommendedProcess(
                    process.getId(),
                    process.getProcessName(),
                    process.getProcessCategory(),
                    nodeOutputUnit(node),
                    process.getEstimatedMinutes(),
                    index + 1,
                    100,
                    "COPIED_FROM_SINGLE_PUBLISHED_WORKFLOW"));
        }

        ProductProcessWorkflow workflow = selected.candidate().workflow();
        ProductType sourceProduct = selected.candidate().sourceProduct();
        return new RecommendationResult(
                target.getId(),
                SOURCE,
                SOURCE_SCOPE,
                "COMPLETE_PUBLISHED_WORKFLOW",
                REVIEW_NOTICE,
                "同源产品族中最新发布且通过完整性校验的 product-owned Workflow",
                sourceProduct.getId(),
                sourceProduct.getName(),
                workflow.getId(),
                workflow.getDefinitionVersion(),
                recommendations);
    }

    private Optional<ResolvedCandidate> resolveCandidate(String factoryId, WorkflowCandidate candidate) {
        List<String> workProcessIds = candidate.processNodes().stream()
                .map(this::workProcessId)
                .toList();
        Map<String, WorkProcess> masters = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, workProcessIds).stream()
                .filter(WorkProcess::isSelectableForNew)
                .collect(Collectors.toMap(WorkProcess::getId, process -> process, (left, right) -> left));
        return workProcessIds.stream().allMatch(masters::containsKey)
                ? Optional.of(new ResolvedCandidate(candidate, masters))
                : Optional.empty();
    }

    private Optional<WorkflowCandidate> candidate(String factoryId, ProductType sourceProduct) {
        Optional<ProductProcessWorkflow> workflow = workflowRepository
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factoryId,
                        sourceProduct.getId(),
                        ProductProcessWorkflow.Status.PUBLISHED);
        if (workflow.isEmpty()) {
            return Optional.empty();
        }

        try {
            ProductProcessWorkflowDTO definition = toDefinition(workflow.get());
            workflowValidator.validateForPublish(definition);
            List<ProductProcessWorkflowDTO.Node> processNodes = completeProductOwnedProcessOrder(
                    sourceProduct,
                    definition);
            if (processNodes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new WorkflowCandidate(sourceProduct, workflow.get(), processNodes));
        } catch (BusinessException | IllegalArgumentException exception) {
            log.debug(
                    "Skip incomplete workflow recommendation source: factoryId={}, productTypeId={}, workflowId={}, reason={}",
                    factoryId,
                    sourceProduct.getId(),
                    workflow.get().getId(),
                    exception.getMessage());
            return Optional.empty();
        } catch (Exception exception) {
            log.warn(
                    "Skip unreadable workflow recommendation source: factoryId={}, productTypeId={}, workflowId={}",
                    factoryId,
                    sourceProduct.getId(),
                    workflow.get().getId(),
                    exception);
            return Optional.empty();
        }
    }

    private ProductProcessWorkflowDTO toDefinition(ProductProcessWorkflow workflow) throws Exception {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setId(workflow.getId());
        definition.setFactoryId(workflow.getFactoryId());
        definition.setProductTypeId(workflow.getProductTypeId());
        definition.setSchemaVersion(workflow.getSchemaVersion());
        definition.setStatus(workflow.getStatus().name());
        definition.setVersion(workflow.getDefinitionVersion());
        definition.setNodes(objectMapper.readValue(
                workflow.getNodesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
        definition.setEdges(objectMapper.readValue(
                workflow.getEdgesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));
        definition.setViewport(objectMapper.readValue(
                workflow.getViewportJson(),
                ProductProcessWorkflowDTO.Viewport.class));
        return definition;
    }

    /**
     * Adds the recommendation-specific completeness gate on top of the existing
     * publish validator. Every process must be reachable from a raw entry and be
     * able to reach the source product's terminal material cell.
     */
    private List<ProductProcessWorkflowDTO.Node> completeProductOwnedProcessOrder(
            ProductType sourceProduct,
            ProductProcessWorkflowDTO definition) {
        Map<String, ProductProcessWorkflowDTO.Node> nodes = definition.getNodes().stream()
                .collect(Collectors.toMap(
                        ProductProcessWorkflowDTO.Node::getId,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> indegree.put(id, 0));
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
            incoming.computeIfAbsent(edge.getTarget(), ignored -> new ArrayList<>()).add(edge.getSource());
            indegree.merge(edge.getTarget(), 1, Integer::sum);
        }

        Set<String> rawEntries = nodes.values().stream()
                .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                .filter(node -> incoming.getOrDefault(node.getId(), List.of()).isEmpty())
                .map(ProductProcessWorkflowDTO.Node::getId)
                .collect(Collectors.toSet());
        Set<String> matchingTerminals = nodes.values().stream()
                .filter(node -> expectedTerminalKind(sourceProduct).equals(node.getKind()))
                .filter(node -> outgoing.getOrDefault(node.getId(), List.of()).isEmpty())
                .filter(node -> sourceProduct.getId().equals(text(data(node).get("skuId"))))
                .map(ProductProcessWorkflowDTO.Node::getId)
                .collect(Collectors.toSet());
        if (rawEntries.isEmpty() || matchingTerminals.isEmpty()) {
            return List.of();
        }

        Set<String> reachableFromRaw = reachable(rawEntries, outgoing);
        Set<String> canReachTerminal = reachable(matchingTerminals, incoming);
        List<ProductProcessWorkflowDTO.Node> processes = nodes.values().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .toList();
        if (processes.isEmpty()
                || processes.stream().anyMatch(node -> !reachableFromRaw.contains(node.getId())
                || !canReachTerminal.contains(node.getId())
                || workProcessId(node) == null)) {
            return List.of();
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<ProductProcessWorkflowDTO.Node> orderedProcesses = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.remove();
            ProductProcessWorkflowDTO.Node node = nodes.get(current);
            if (node != null && "PROCESS".equals(node.getKind())) {
                orderedProcesses.add(node);
            }
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return orderedProcesses.size() == processes.size() ? List.copyOf(orderedProcesses) : List.of();
    }

    private Set<String> reachable(Set<String> starts, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>(starts);
        Queue<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private String expectedTerminalKind(ProductType product) {
        return switch (safeText(product.getProductCategory()).toUpperCase()) {
            case "SEMI_FINISHED", "SEMI_FINISHED_PRODUCT" -> "SEMI_FINISHED";
            default -> "FINISHED_GOOD";
        };
    }

    private Map<String, Object> data(ProductProcessWorkflowDTO.Node node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private String workProcessId(ProductProcessWorkflowDTO.Node node) {
        return text(data(node).get("workProcessId"));
    }

    private String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string.trim();
    }

    private RecommendationResult emptyResult(String productTypeId, String reasonCode, String message) {
        return new RecommendationResult(
                productTypeId,
                "NONE",
                SOURCE_SCOPE,
                reasonCode,
                REVIEW_NOTICE,
                message,
                null,
                null,
                null,
                null,
                List.of());
    }

    private String buildMessage(RecommendationResult result) {
        if (result.recommendations().isEmpty()) {
            return result.message();
        }
        return String.format(
                "已找到来源 %s 的完整 Workflow（%d 道工序），请核对后复制",
                result.sourceProductName(),
                result.recommendations().size());
    }

    private boolean sameText(String left, String right) {
        return safeText(left).equalsIgnoreCase(safeText(right));
    }

    /**
     * Recommendation is a copy operation, not a broad discovery search. Only
     * variants of the same product may share a workflow automatically:
     * template identity is strongest, followed by the explicit base name, then
     * a specification-stripped name (for example 350g vs 400g).
     */
    private boolean sameProductFamily(ProductType target, ProductType source) {
        if (sameNonBlankText(target.getTemplateId(), source.getTemplateId())) {
            return true;
        }
        if (sameNonBlankText(target.getBaseProductName(), source.getBaseProductName())) {
            return true;
        }
        String targetFamily = normalizedFamilyName(target);
        String sourceFamily = normalizedFamilyName(source);
        if (targetFamily.length() < 2 || sourceFamily.length() < 2) {
            return false;
        }
        if (targetFamily.equals(sourceFamily)) {
            return true;
        }
        int shorter = Math.min(targetFamily.length(), sourceFamily.length());
        return shorter >= 4
                && (targetFamily.contains(sourceFamily) || sourceFamily.contains(targetFamily));
    }

    private boolean sameNonBlankText(String left, String right) {
        return !safeText(left).isEmpty() && sameText(left, right);
    }

    private String normalizedFamilyName(ProductType product) {
        String explicitBaseName = safeText(product.getBaseProductName());
        String value = explicitBaseName.isEmpty() ? safeText(product.getName()) : explicitBaseName;
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+(?:\\.\\d+)?\\s*(?:kg|g|克|千克|公斤|斤|ml|l|毫升|升|只|件|个|盒|袋|瓶|箱|份)", "")
                .replaceAll("[\\s\\p{Punct}，。；：、·×（）【】]+", "");
    }

    private String nodeOutputUnit(ProductProcessWorkflowDTO.Node node) {
        if (node.getData() == null) {
            return null;
        }
        Object outputUnit = node.getData().get("outputUnit");
        return outputUnit == null ? null : outputUnit.toString();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    public record RecommendationResult(
            String productTypeId,
            String source,
            String sourceScope,
            String reasonCode,
            String notice,
            String message,
            String sourceProductTypeId,
            String sourceProductName,
            Long sourceWorkflowId,
            Integer sourceWorkflowVersion,
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

    private record WorkflowCandidate(
            ProductType sourceProduct,
            ProductProcessWorkflow workflow,
            List<ProductProcessWorkflowDTO.Node> processNodes) {
    }

    private record ResolvedCandidate(
            WorkflowCandidate candidate,
            Map<String, WorkProcess> workProcesses) {
    }
}
