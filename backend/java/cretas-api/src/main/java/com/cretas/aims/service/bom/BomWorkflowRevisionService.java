package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.BomWorkflowRevisionPinRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stable Workflow revision selection and exact target-SKU graph slicing for BOM drafts. */
@Service
@RequiredArgsConstructor
public class BomWorkflowRevisionService {

    private static final Set<String> OUTPUT_ROLES = Set.of("MAIN", "CO_PRODUCT", "BY_PRODUCT");

    private final BomRecipeRepository recipeRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final WorkflowRevisionSnapshotService revisionSnapshotService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<WorkflowRevisionCandidateDTO> listCompatible(String factoryId, String recipeId) {
        BomRecipe recipe = requireRecipe(factoryId, recipeId);
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, recipe.getProductTypeId())
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()));

        List<WorkflowRevisionCandidateDTO> result = new ArrayList<>();
        Set<String> represented = new HashSet<>();
        for (ProductProcessWorkflowRevision revision : revisionRepository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, recipe.getProductTypeId())) {
            represented.add(revision.getWorkflowId() + ":" + revision.getRevisionHash());
            result.add(toCandidate(revision, recipe.getProductTypeId(), activation));
        }

        // Existing deployments have published/draft rows created before the revision table. Expose them
        // read-only without a data backfill; an explicit user pin captures the immutable revision atomically.
        for (ProductProcessWorkflow workflow : workflowRepository
                .findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(factoryId, recipe.getProductTypeId())) {
            String hash = revisionSnapshotService.hash(workflow);
            if (represented.add(workflow.getId() + ":" + hash)) {
                result.add(toLegacyCandidate(workflow, hash, recipe.getProductTypeId(), activation));
            }
        }

        result.sort(Comparator
                .comparing(WorkflowRevisionCandidateDTO::isCompatible).reversed()
                .thenComparing(Comparator.comparing(
                        (WorkflowRevisionCandidateDTO row) -> "DRAFT".equals(row.getStatus())).reversed())
                .thenComparing(WorkflowRevisionCandidateDTO::getSavedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkflowRevisionCandidateDTO::getDefinitionVersion,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        result.stream().filter(WorkflowRevisionCandidateDTO::isCompatible).findFirst()
                .ifPresent(candidate -> candidate.setRecommended(true));
        return result;
    }

    @Transactional
    public BomRecipe pin(String factoryId, String recipeId, BomWorkflowRevisionPinRequest request) {
        BomRecipe recipe = recipeRepository.lockByIdAndFactoryId(recipeId, factoryId)
                .orElseThrow(() -> invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND"));
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw invalid(409, "只有 BOM 草稿可以选择 Workflow 修订", "BOM_WORKFLOW_PIN_READ_ONLY");
        }
        ProductProcessWorkflowRevision revision = resolveRequestedRevision(factoryId, recipe, request);
        ProductProcessWorkflowDTO definition = requireCompatible(revision, recipe.getProductTypeId());
        PinnedWorkflowGraph graph = resolveTargetGraph(revision.getId(), definition, recipe.getProductTypeId());
        if (graph.processes().isEmpty()) {
            throw invalid(409, "所选 Workflow 修订没有可配置的工序", "BOM_WORKFLOW_REVISION_HAS_NO_PROCESS");
        }

        recipe.setWorkflowRevisionId(revision.getId());
        recipe.setWorkflowId(revision.getWorkflowId());
        recipe.setWorkflowDefinitionVersion(revision.getDefinitionVersion());
        recipe.setWorkflowRevisionHash(revision.getRevisionHash());
        recipe.setWorkflowSchemaVersion(revision.getSchemaVersion());
        recipe.setWorkflowNodesSnapshotJson(revision.getNodesJson());
        recipe.setWorkflowEdgesSnapshotJson(revision.getEdgesJson());
        return recipeRepository.saveAndFlush(recipe);
    }

    @Transactional(readOnly = true)
    public PinnedWorkflowGraph resolvePinnedGraph(String factoryId, BomRecipe recipe) {
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND");
        }
        if (recipe.getWorkflowRevisionHash() == null
                || recipe.getWorkflowNodesSnapshotJson() == null
                || recipe.getWorkflowEdgesSnapshotJson() == null) {
            throw invalid(409, "BOM 尚未选择已保存的 Workflow 修订", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        ProductProcessWorkflowDTO definition = definitionFromRecipe(recipe);
        validator.validateStructureComplete(definition);
        catalogValidator.validateForBomConfiguration(factoryId, recipe.getProductTypeId(), definition);
        return resolveTargetGraph(recipe.getWorkflowRevisionId(), definition, recipe.getProductTypeId());
    }

    @Transactional(readOnly = true)
    public ProductProcessWorkflowRevision requireExactPublishedRevisionForActiveBom(
            String factoryId, Long workflowId, String productTypeId) {
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                .filter(row -> productTypeId.equals(row.getProductTypeId()))
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.PUBLISHED)
                .orElseThrow(() -> invalid(409, "只能启用当前工厂已发布的 Workflow", "WORKFLOW_NOT_PUBLISHED"));
        Long revisionId = workflow.getCurrentRevisionId();
        if (revisionId == null || workflow.getCurrentRevisionHash() == null) {
            throw invalid(409, "已发布 Workflow 缺少保存修订，请另存草稿并重新发布",
                    "WORKFLOW_PUBLISHED_REVISION_MISSING");
        }
        ProductProcessWorkflowRevision revision = revisionRepository.findByIdAndFactoryId(revisionId, factoryId)
                .filter(row -> workflowId.equals(row.getWorkflowId()))
                .filter(row -> productTypeId.equals(row.getProductTypeId()))
                .filter(row -> workflow.getCurrentRevisionHash().equals(row.getRevisionHash()))
                .orElseThrow(() -> invalid(409, "Workflow 当前修订身份不一致", "WORKFLOW_REVISION_IDENTITY_MISMATCH"));
        recipeRepository.findFirstByFactoryIdAndProductTypeIdAndWorkflowRevisionIdAndStatusOrderByVersionDesc(
                        factoryId, productTypeId, revisionId, BomRecipe.Status.ACTIVE)
                .filter(recipe -> revision.getRevisionHash().equals(recipe.getWorkflowRevisionHash()))
                .orElseThrow(() -> invalid(409, "没有 pin 当前 Workflow 修订的 ACTIVE BOM",
                        "WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH"));
        return revision;
    }

    /**
     * Publication gate: the active BOM must already pin the exact immutable revision
     * that is about to be published. The revision is still DRAFT at this point, so
     * this check deliberately does not require a published workflow status.
     */
    @Transactional(readOnly = true)
    public BomRecipe requireActiveBomPinsRevision(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowRevision revision) {
        if (revision == null
                || !factoryId.equals(revision.getFactoryId())
                || !productTypeId.equals(revision.getProductTypeId())) {
            throw invalid(409, "Workflow 修订不属于当前工厂或 SKU",
                    "WORKFLOW_REVISION_SCOPE_INVALID");
        }
        BomRecipe active = recipeRepository
                .findFirstByFactoryIdAndProductTypeIdAndWorkflowRevisionIdAndStatusOrderByVersionDesc(
                        factoryId, productTypeId, revision.getId(), BomRecipe.Status.ACTIVE)
                .filter(recipe -> Objects.equals(revision.getWorkflowId(), recipe.getWorkflowId()))
                .filter(recipe -> Objects.equals(revision.getDefinitionVersion(), recipe.getWorkflowDefinitionVersion()))
                .filter(recipe -> Objects.equals(revision.getRevisionHash(), recipe.getWorkflowRevisionHash()))
                .orElseThrow(() -> invalid(409, "当前没有 pin 该 Workflow 保存修订的 ACTIVE BOM",
                        "WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH"));
        // Re-validate the immutable snapshot rather than trusting mutable workflow data.
        resolvePinnedGraph(factoryId, active);
        return active;
    }

    private ProductProcessWorkflowRevision resolveRequestedRevision(
            String factoryId, BomRecipe recipe, BomWorkflowRevisionPinRequest request) {
        if (request == null) {
            throw invalid(400, "请选择 Workflow 修订", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        if (request.getRevisionId() != null) {
            ProductProcessWorkflowRevision revision = revisionRepository
                    .findByIdAndFactoryId(request.getRevisionId(), factoryId)
                    .filter(row -> recipe.getProductTypeId().equals(row.getProductTypeId()))
                    .orElseThrow(() -> invalid(400, "Workflow 修订不属于当前工厂或 SKU",
                            "BOM_WORKFLOW_REVISION_SCOPE_INVALID"));
            if (request.getRevisionHash() != null
                    && !request.getRevisionHash().equals(revision.getRevisionHash())) {
                throw invalid(409, "Workflow 修订已变化，请刷新后重选", "BOM_WORKFLOW_REVISION_STALE");
            }
            return revision;
        }
        if (request.getWorkflowId() == null || request.getRevisionHash() == null) {
            throw invalid(400, "请选择带稳定修订标识的 Workflow", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(request.getWorkflowId(), factoryId)
                .filter(row -> recipe.getProductTypeId().equals(row.getProductTypeId()))
                .orElseThrow(() -> invalid(400, "Workflow 不属于当前工厂或 SKU",
                        "BOM_WORKFLOW_REVISION_SCOPE_INVALID"));
        if (!request.getRevisionHash().equals(revisionSnapshotService.hash(workflow))) {
            throw invalid(409, "Workflow 草稿已变化，请刷新后选择最新保存修订", "BOM_WORKFLOW_REVISION_STALE");
        }
        ProductProcessWorkflowRevision revision = revisionSnapshotService.capture(workflow);
        workflow.setCurrentRevisionId(revision.getId());
        workflow.setCurrentRevisionHash(revision.getRevisionHash());
        workflowRepository.save(workflow);
        return revision;
    }

    private WorkflowRevisionCandidateDTO toCandidate(
            ProductProcessWorkflowRevision revision,
            String targetProductTypeId,
            Optional<ProductProcessWorkflowActivation> activation) {
        String reason = incompatibility(revision, targetProductTypeId);
        return WorkflowRevisionCandidateDTO.builder()
                .revisionId(revision.getId())
                .workflowId(revision.getWorkflowId())
                .definitionVersion(revision.getDefinitionVersion())
                .revisionNumber(revision.getRevisionNumber())
                .revisionHash(revision.getRevisionHash())
                .status(revision.getStatus().name())
                .savedAt(revision.getCreatedAt())
                .processCount(revision.getProcessCount())
                .enabled(isEnabled(activation, revision.getWorkflowId()))
                .compatible(reason == null)
                .incompatibilityReason(reason)
                .build();
    }

    private WorkflowRevisionCandidateDTO toLegacyCandidate(
            ProductProcessWorkflow workflow,
            String hash,
            String targetProductTypeId,
            Optional<ProductProcessWorkflowActivation> activation) {
        ProductProcessWorkflowRevision transientRevision = new ProductProcessWorkflowRevision();
        transientRevision.setFactoryId(workflow.getFactoryId());
        transientRevision.setProductTypeId(workflow.getProductTypeId());
        transientRevision.setWorkflowId(workflow.getId());
        transientRevision.setDefinitionVersion(workflow.getDefinitionVersion());
        transientRevision.setRevisionHash(hash);
        transientRevision.setSchemaVersion(workflow.getSchemaVersion());
        transientRevision.setNodesJson(workflow.getNodesJson());
        transientRevision.setEdgesJson(workflow.getEdgesJson());
        transientRevision.setViewportJson(workflow.getViewportJson());
        transientRevision.setStatus(workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                ? ProductProcessWorkflowRevision.Status.PUBLISHED
                : ProductProcessWorkflowRevision.Status.DRAFT);
        ProductProcessWorkflowDTO definition = revisionSnapshotService.definition(transientRevision);
        String reason = incompatibility(transientRevision, targetProductTypeId);
        int processCount = (int) definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).count();
        return WorkflowRevisionCandidateDTO.builder()
                .workflowId(workflow.getId())
                .definitionVersion(workflow.getDefinitionVersion())
                .revisionHash(hash)
                .status(workflow.getStatus().name())
                .savedAt(workflow.getUpdatedAt())
                .processCount(processCount)
                .enabled(isEnabled(activation, workflow.getId()))
                .compatible(reason == null)
                .incompatibilityReason(reason)
                .build();
    }

    private String incompatibility(ProductProcessWorkflowRevision revision, String targetProductTypeId) {
        try {
            requireCompatible(revision, targetProductTypeId);
            return null;
        } catch (BusinessException error) {
            return error.getMessage();
        }
    }

    private ProductProcessWorkflowDTO requireCompatible(
            ProductProcessWorkflowRevision revision, String targetProductTypeId) {
        if (!targetProductTypeId.equals(revision.getProductTypeId())) {
            throw invalid(400, "Workflow 修订与 BOM SKU 不一致", "BOM_WORKFLOW_REVISION_PRODUCT_MISMATCH");
        }
        if (!Objects.equals(revision.getRevisionHash(), revisionSnapshotService.hash(revision))) {
            throw invalid(409, "Workflow 修订内容哈希不一致", "BOM_WORKFLOW_REVISION_HASH_INVALID");
        }
        ProductProcessWorkflowDTO definition = revisionSnapshotService.definition(revision);
        validator.validateStructureComplete(definition);
        catalogValidator.validateForBomConfiguration(revision.getFactoryId(), targetProductTypeId, definition);
        resolveTargetGraph(revision.getId(), definition, targetProductTypeId);
        return definition;
    }

    private ProductProcessWorkflowDTO definitionFromRecipe(BomRecipe recipe) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(recipe.getWorkflowId());
        dto.setFactoryId(recipe.getFactoryId());
        dto.setProductTypeId(recipe.getProductTypeId());
        dto.setSchemaVersion(recipe.getWorkflowSchemaVersion());
        dto.setStatus("PINNED");
        dto.setVersion(recipe.getWorkflowDefinitionVersion());
        dto.setRevisionId(recipe.getWorkflowRevisionId());
        dto.setRevisionHash(recipe.getWorkflowRevisionHash());
        dto.setNodes(read(recipe.getWorkflowNodesSnapshotJson(), new TypeReference<>() {}, "nodes"));
        dto.setEdges(read(recipe.getWorkflowEdgesSnapshotJson(), new TypeReference<>() {}, "edges"));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    /** Shared exact target-SKU reverse-DAG resolver used by BOM draft and ACTIVE runtime paths. */
    public static PinnedWorkflowGraph resolveTargetGraph(
            Long revisionId, ProductProcessWorkflowDTO definition, String targetProductTypeId) {
        Map<String, ProductProcessWorkflowDTO.Node> nodeById = definition.getNodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ProductProcessWorkflowDTO.Edge>> incoming = new HashMap<>();
        Map<String, List<ProductProcessWorkflowDTO.Edge>> outgoing = new HashMap<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            incoming.computeIfAbsent(edge.getTarget(), ignored -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge);
        }
        List<ProductProcessWorkflowDTO.Node> terminals = definition.getNodes().stream()
                .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                .filter(node -> targetProductTypeId.equals(string(node.getData(), "skuId")))
                .filter(node -> outgoing.getOrDefault(node.getId(), List.of()).isEmpty())
                .toList();
        if (terminals.size() != 1) {
            throw invalid(409, terminals.isEmpty()
                            ? "Workflow 修订没有目标 SKU 的终端成品 Cell"
                            : "Workflow 修订重复绑定目标 SKU 终端 Cell",
                    "BOM_WORKFLOW_TARGET_TERMINAL_INVALID");
        }

        Set<String> ancestors = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(terminals.getFirst().getId());
        while (!pending.isEmpty()) {
            String nodeId = pending.removeFirst();
            if (!ancestors.add(nodeId)) continue;
            for (ProductProcessWorkflowDTO.Edge edge : incoming.getOrDefault(nodeId, List.of())) {
                pending.addLast(edge.getSource());
            }
        }
        List<ProductProcessWorkflowDTO.Edge> sliceEdges = definition.getEdges().stream()
                .filter(edge -> ancestors.contains(edge.getSource()) && ancestors.contains(edge.getTarget()))
                .toList();
        List<ProductProcessWorkflowDTO.Node> rawRoots = ancestors.stream()
                .map(nodeById::get)
                .filter(Objects::nonNull)
                .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                .filter(node -> incoming.getOrDefault(node.getId(), List.of()).stream()
                        .noneMatch(edge -> ancestors.contains(edge.getSource())))
                .toList();
        List<String> rootMaterialTypeIds = rawRoots.stream()
                .map(node -> string(node.getData(), "skuId"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rawRoots.isEmpty() || rootMaterialTypeIds.size() != rawRoots.size()) {
            throw invalid(409, "目标 SKU 路径必须回溯到可识别的原料入口",
                    "BOM_WORKFLOW_ROOT_INPUT_INVALID");
        }

        validateMultiOutputContracts(ancestors, nodeById);

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> sliceOutgoing = new HashMap<>();
        ancestors.forEach(id -> indegree.put(id, 0));
        for (ProductProcessWorkflowDTO.Edge edge : sliceEdges) {
            sliceOutgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
            indegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        Comparator<String> nodeOrder = Comparator
                .comparingDouble((String id) -> coordinate(nodeById.get(id), true))
                .thenComparingDouble(id -> coordinate(nodeById.get(id), false))
                .thenComparing(Function.identity());
        ArrayDeque<String> ready = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted(nodeOrder)
                .collect(Collectors.toCollection(ArrayDeque::new));
        List<ProductProcessWorkflowDTO.Node> orderedNodes = new ArrayList<>();
        List<PinnedWorkflowGraph.ProcessStep> processes = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            ProductProcessWorkflowDTO.Node node = nodeById.get(current);
            orderedNodes.add(node);
            if ("PROCESS".equals(node.getKind())) {
                String workProcessId = string(node.getData(), "workProcessId");
                if (workProcessId == null) {
                    throw invalid(409, "Workflow 工序 Cell 缺少工序主数据绑定",
                            "BOM_WORKFLOW_PROCESS_IDENTITY_MISSING");
                }
                processes.add(new PinnedWorkflowGraph.ProcessStep(
                        node.getId(), workProcessId, processes.size() + 1));
            }
            sliceOutgoing.getOrDefault(current, List.of()).stream().sorted(nodeOrder).forEach(target -> {
                int remaining = indegree.merge(target, -1, Integer::sum);
                if (remaining == 0) ready.addLast(target);
            });
        }
        if (orderedNodes.size() != ancestors.size()) {
            throw invalid(409, "Workflow 目标路径包含回路", "BOM_WORKFLOW_TARGET_SLICE_CYCLE");
        }
        return new PinnedWorkflowGraph(revisionId, definition.getId(), definition.getVersion(),
                definition.getRevisionHash(), targetProductTypeId, terminals.getFirst().getId(),
                rootMaterialTypeIds, List.copyOf(processes), List.copyOf(orderedNodes), List.copyOf(sliceEdges));
    }

    private static void validateMultiOutputContracts(
            Set<String> ancestors, Map<String, ProductProcessWorkflowDTO.Node> nodeById) {
        for (String nodeId : ancestors) {
            ProductProcessWorkflowDTO.Node node = nodeById.get(nodeId);
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
            Object rawPorts = node.getData().get("ports");
            if (!(rawPorts instanceof List<?> ports)) continue;
            List<Map<?, ?>> outputs = ports.stream()
                    .filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value)
                    .filter(port -> "OUTPUT".equals(String.valueOf(port.get("direction"))))
                    .toList();
            if (outputs.size() <= 1) continue;
            BigDecimal total = BigDecimal.ZERO;
            int mainCount = 0;
            for (Map<?, ?> output : outputs) {
                String role = value(output.get("outputRole"));
                BigDecimal ratio = decimal(output.get("costAllocationRatio"));
                if (!OUTPUT_ROLES.contains(role) || ratio == null || ratio.signum() <= 0) {
                    throw invalid(409, "多产出工序必须为每个产出配置角色和成本分摊比例",
                            "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED");
                }
                if ("MAIN".equals(role)) mainCount++;
                total = total.add(ratio);
            }
            if (mainCount != 1 || total.compareTo(new BigDecimal("100")) != 0) {
                throw invalid(409, "多产出工序必须有且仅有一个主产出，成本分摊合计必须为100%",
                        "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_INVALID");
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text) {
            try { return new BigDecimal(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static double coordinate(ProductProcessWorkflowDTO.Node node, boolean x) {
        if (node == null || node.getPosition() == null) return Double.MAX_VALUE;
        Double value = x ? node.getPosition().getX() : node.getPosition().getY();
        return value == null ? Double.MAX_VALUE : value;
    }

    private static String string(Map<String, Object> data, String key) {
        return data == null ? null : value(data.get(key));
    }

    private static String value(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private boolean isEnabled(Optional<ProductProcessWorkflowActivation> activation, Long workflowId) {
        return activation.map(row -> workflowId.equals(row.getActiveWorkflowId())).orElse(false);
    }

    private BomRecipe requireRecipe(String factoryId, String recipeId) {
        return recipeRepository.findById(recipeId)
                .filter(recipe -> factoryId.equals(recipe.getFactoryId()))
                .orElseThrow(() -> invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND"));
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "BOM 固定的 Workflow " + field + " 快照损坏", error)
                    .withCode("BOM_WORKFLOW_SNAPSHOT_INVALID");
        }
    }

    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code).withSeverity("warning");
    }
}
