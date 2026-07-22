package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import com.cretas.aims.service.workflow.WorkflowPlanOutputContract;
import com.cretas.aims.service.workflow.WorkflowTopology;
import com.cretas.aims.service.workflow.WorkflowTopologyClassifier;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductWorkflowResolutionServiceImpl implements ProductWorkflowResolutionService {

    private static final String FINISHED_GOOD = "FINISHED_GOOD";

    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ObjectMapper objectMapper;
    private final UnitContractService unitContractService;
    private final ProductProcessWorkflowUnitValidator unitValidator;

    @Override
    @Transactional(readOnly = true)
    public WorkflowOutputResolutionDTO resolveForOutputs(
            String factoryId, List<String> finishedGoodProductTypeIds) {
        List<String> requested = dedupeNonBlank(finishedGoodProductTypeIds);
        if (requested.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个成品")
                    .withCode("WORKFLOW_RESOLUTION_EMPTY_SELECTION")
                    .withSeverity("warning");
        }
        // 校验所选成品存在于本工厂
        List<String> missing = new ArrayList<>();
        for (String id : requested) {
            if (productTypeRepository.findByIdAndFactoryId(id, factoryId).isEmpty()) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(404, "以下成品不存在于当前工厂: " + missing)
                    .withCode("WORKFLOW_RESOLUTION_PRODUCT_NOT_FOUND")
                    .withSeverity("warning");
        }
        List<ResolvedCandidate> resolved;
        try {
            resolved = requireResolutionCandidates(factoryId, requested);
        } catch (BusinessException error) {
            if (!"WORKFLOW_SINGLE_OUTPUT_NOT_FOUND".equals(error.getErrorCode())
                    && !"WORKFLOW_SHARED_OUTPUT_NOT_FOUND".equals(error.getErrorCode())) {
                throw error;
            }
            return WorkflowOutputResolutionDTO.builder()
                    .requestedProductTypeIds(requested)
                    .resolutionMode("NONE")
                    .message(error.getMessage())
                    .candidates(List.of())
                    .build();
        }
        Set<String> requestedSet = new HashSet<>(requested);
        return WorkflowOutputResolutionDTO.builder()
                .requestedProductTypeIds(requested)
                .resolutionMode(requested.size() == 1 ? "SINGLE_OUTPUT" : "MULTI_OUTPUT")
                .message(resolutionMessage(resolved))
                .candidates(resolved.stream()
                        .map(candidate -> buildCandidate(
                                factoryId, candidate.owner, candidate.rw, requestedSet))
                        .toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowProcessPath> resolveProcessPath(
            String factoryId, String finishedGoodProductTypeId) {
        WorkflowOutputResolutionDTO resolution = resolveForOutputs(
                factoryId, List.of(finishedGoodProductTypeId));
        List<WorkflowOutputResolutionDTO.Candidate> candidates = resolution.getCandidates() == null
                ? List.of() : resolution.getCandidates();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() != 1) {
            throw new BusinessException(409, "该成品匹配到多个已启用的原料 Workflow，无法确定 BOM 工序路径")
                    .withCode("WORKFLOW_RESOLUTION_AMBIGUOUS")
                    .withHint("请停用重复 Workflow，或为该成品启用一条独立 Workflow")
                    .withHintTarget("finishedGoodProductTypeId");
        }
        WorkflowOutputResolutionDTO.Candidate candidate = candidates.get(0);
        ProductProcessWorkflow workflow = workflowRepository
                .findByIdAndFactoryId(candidate.getWorkflowId(), factoryId)
                .orElseThrow(() -> invalidPath("已启用的 Workflow 不存在"));
        return Optional.of(parseProcessPath(factoryId, workflow, candidate, finishedGoodProductTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public void assertActiveWorkflowCoversOutputs(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds) {
        List<String> targets = selectedOutputs(ownerProductTypeId, targetFinishedGoodIds);
        if (!targets.isEmpty()) {
            requireResolutionForAnchor(factoryId, ownerProductTypeId, targets);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertPinnedWorkflowCoversOutputs(
            String factoryId, Long workflowId, Integer definitionVersion,
            List<String> targetFinishedGoodIds) {
        List<String> targets = dedupeNonBlank(targetFinishedGoodIds);
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.PUBLISHED)
                .filter(row -> java.util.Objects.equals(row.getDefinitionVersion(), definitionVersion))
                .filter(row -> hasCurrentUnitContract(factoryId, row))
                .orElseThrow(() -> new BusinessException(409, "生产计划固定的 Workflow 版本已失效")
                        .withCode("WORKFLOW_PINNED_VERSION_INVALID"));
        WorkflowTopology topology = parseTopology(workflow);
        if (!matchesSelection(topology, new HashSet<>(targets), targets.size())) {
            throw noMatchingWorkflow(targets.size());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowPlanOutputContract> resolveActivePlanOutputContract(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds) {
        List<String> targets = selectedOutputs(ownerProductTypeId, targetFinishedGoodIds);
        if (targets.isEmpty()) return Optional.empty();
        ResolvedWorkflow resolved = requireResolutionForAnchor(
                factoryId, ownerProductTypeId, targets);
        return Optional.of(buildPlanOutputContract(factoryId, resolved, targets));
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowPlanOutputContract resolvePinnedPlanOutputContract(
            String factoryId, String ownerProductTypeId, Long workflowId, Integer definitionVersion,
            List<String> targetFinishedGoodIds) {
        List<String> targets = selectedOutputs(ownerProductTypeId, targetFinishedGoodIds);
        if (workflowId == null || definitionVersion == null) {
            throw new BusinessException(400, "Workflow ID 与版本必须成对提交")
                    .withCode("WORKFLOW_SELECTION_INCOMPLETE")
                    .withHintTarget("selectedWorkflowId");
        }
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.PUBLISHED)
                .filter(row -> java.util.Objects.equals(row.getDefinitionVersion(), definitionVersion))
                .filter(row -> hasCurrentUnitContract(factoryId, row))
                .filter(row -> java.util.Objects.equals(row.getProductTypeId(), ownerProductTypeId))
                .orElseThrow(this::staleSelection);
        ProductProcessWorkflowActivation activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, ownerProductTypeId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .filter(row -> java.util.Objects.equals(row.getActiveWorkflowId(), workflowId))
                .filter(row -> java.util.Objects.equals(row.getActiveDefinitionVersion(), definitionVersion))
                .orElseThrow(this::staleSelection);
        ResolvedWorkflow resolved = loadResolved(factoryId, activation);
        if (resolved == null || !matchesSelection(
                resolved.topology, new HashSet<>(targets), targets.size())) {
            throw noMatchingWorkflow(targets.size());
        }
        return buildPlanOutputContract(factoryId, resolved, targets);
    }

    private WorkflowPlanOutputContract buildPlanOutputContract(
            String factoryId, ResolvedWorkflow resolved, List<String> targets) {
        Map<String, List<String>> unitsBySku = parseTerminalOutputUnits(factoryId, resolved.workflow);
        Map<String, String> selected = new LinkedHashMap<>();
        for (String target : targets) {
            List<String> units = unitsBySku.getOrDefault(target, List.of());
            if (units.isEmpty()) {
                throw new BusinessException(409, "Workflow 缺少成品 " + target + " 的产出端口")
                        .withCode("WORKFLOW_PLAN_OUTPUT_NOT_FOUND");
            }
            if (units.size() != 1) {
                throw new BusinessException(409, "成品 " + target + " 绑定了多个产出端口")
                        .withCode("WORKFLOW_PLAN_OUTPUT_DUPLICATE");
            }
            selected.put(target, units.get(0));
        }
        String plannedUnit;
        String internalAnchor = resolved.workflow.getProductTypeId();
        if (!resolved.topology.rootInputSkuIds().contains(internalAnchor)) {
            Set<String> distinctUnits = new LinkedHashSet<>(selected.values());
            if (distinctUnits.size() != 1) {
                throw new BusinessException(409, "所选成品的 Workflow 产出单位不一致，不能合并为一个计划数量")
                        .withCode("WORKFLOW_PLAN_OUTPUT_UNIT_AMBIGUOUS");
            }
            plannedUnit = distinctUnits.iterator().next();
        } else {
            Set<String> ownerInputUnits = parseOwnerInputUnits(
                    factoryId, resolved.workflow, internalAnchor);
            if (ownerInputUnits.size() != 1) {
                throw new BusinessException(409, "原料中心 Workflow 必须为计划原料提供唯一入口单位")
                        .withCode("WORKFLOW_PLAN_INPUT_UNIT_AMBIGUOUS");
            }
            plannedUnit = ownerInputUnits.iterator().next();
        }
        return new WorkflowPlanOutputContract(
                resolved.workflow.getId(), resolved.workflow.getDefinitionVersion(),
                Map.copyOf(selected), plannedUnit);
    }

    private Set<String> parseOwnerInputUnits(
            String factoryId, ProductProcessWorkflow workflow, String ownerProductTypeId) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            Set<String> ownerMaterialNodeIds = new HashSet<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node == null || node.getData() == null || "PROCESS".equals(node.getKind())) continue;
                if (ownerProductTypeId.equals(node.getData().get("skuId"))) {
                    ownerMaterialNodeIds.add(node.getId());
                }
            }
            Set<String> result = new LinkedHashSet<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
                Object portsValue = node.getData().get("ports");
                if (!(portsValue instanceof List<?> ports)) continue;
                for (Object value : ports) {
                    if (!(value instanceof Map<?, ?> port)
                            || !"INPUT".equals(String.valueOf(port.get("direction")))
                            || !ownerMaterialNodeIds.contains(String.valueOf(port.get("materialNodeId")))) {
                        continue;
                    }
                    Object rawUnit = port.get("unit");
                    var normalized = unitContractService.normalize(
                            factoryId, rawUnit == null ? null : String.valueOf(rawUnit));
                    if (!normalized.recognized()) {
                        throw new BusinessException(409, "Workflow 原料入口端口存在未知单位")
                                .withCode("WORKFLOW_PLAN_INPUT_UNIT_UNKNOWN");
                    }
                    result.add(normalized.code());
                }
            }
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(409, "Workflow 原料入口端口无法解析")
                    .withCode("WORKFLOW_PLAN_CONTRACT_INVALID");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseTerminalOutputUnits(
            String factoryId, ProductProcessWorkflow workflow) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            Map<String, String> skuByMaterialNode = new HashMap<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node != null && FINISHED_GOOD.equals(node.getKind()) && node.getData() != null) {
                    Object skuId = node.getData().get("skuId");
                    if (skuId instanceof String value && !value.isBlank()) {
                        skuByMaterialNode.put(node.getId(), value);
                    }
                }
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
                Object portsValue = node.getData().get("ports");
                if (!(portsValue instanceof List<?> ports)) continue;
                for (Object value : ports) {
                    if (!(value instanceof Map<?, ?> port)) continue;
                    if (!"OUTPUT".equals(String.valueOf(port.get("direction")))) continue;
                    String skuId = skuByMaterialNode.get(String.valueOf(port.get("materialNodeId")));
                    if (skuId == null) continue;
                    Object rawUnit = port.get("unit");
                    var normalized = unitContractService.normalize(
                            factoryId, rawUnit == null ? null : String.valueOf(rawUnit));
                    if (!normalized.recognized()) {
                        throw new BusinessException(409, "Workflow 成品产出端口存在未知单位")
                                .withCode("WORKFLOW_PLAN_OUTPUT_UNIT_UNKNOWN");
                    }
                    result.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(normalized.code());
                }
            }
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(409, "Workflow 产出端口无法解析")
                    .withCode("WORKFLOW_PLAN_CONTRACT_INVALID");
        }
    }

    // ---- 内部 ----

    private List<ResolvedCandidate> requireResolutionCandidates(String factoryId, List<String> requested) {
        Set<String> requestedSet = new HashSet<>(requested);
        List<ProductProcessWorkflowActivation> enabled =
                activationRepository.findByFactoryIdAndEnabledTrue(factoryId);
        Map<String, ProductType> ownerById = batchOwners(factoryId, enabled);
        List<ResolvedCandidate> matches = new ArrayList<>();
        for (ProductProcessWorkflowActivation activation : enabled) {
            ResolvedWorkflow resolved = loadResolved(factoryId, activation);
            if (resolved == null
                    || !matchesSelection(resolved.topology, requestedSet, requested.size())) {
                continue;
            }
            boolean exact = resolved.terminalSkuIds.size() == requestedSet.size();
            matches.add(new ResolvedCandidate(
                    ownerById.get(activation.getProductTypeId()), resolved,
                    exact, activation.getActivatedAt()));
        }
        matches.sort(Comparator
                .comparing((ResolvedCandidate candidate) -> candidate.exact ? 0 : 1)
                .thenComparingInt(candidate -> candidate.rw.terminalSkuIds.size())
                .thenComparing(candidate -> candidate.activatedAt == null
                                ? LocalDateTime.MIN : candidate.activatedAt,
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.rw.workflow.getId()));
        if (matches.isEmpty()) throw noMatchingWorkflow(requested.size());
        boolean hasExact = matches.stream().anyMatch(candidate -> candidate.exact);
        if (hasExact) {
            return matches.stream().filter(candidate -> candidate.exact).toList();
        }
        int smallestTerminalCount = matches.getFirst().rw.terminalSkuIds.size();
        return matches.stream()
                .filter(candidate -> candidate.rw.terminalSkuIds.size() == smallestTerminalCount)
                .toList();
    }

    /**
     * Revalidates the exact activation selected by the plan UI. Global resolution chooses a
     * candidate; plan persistence must not silently drift to another matching activation if
     * activations change between resolution and submission.
     */
    private ResolvedWorkflow requireResolutionForAnchor(
            String factoryId, String ownerProductTypeId, List<String> requested) {
        if (ownerProductTypeId == null || ownerProductTypeId.isBlank()) {
            throw noMatchingWorkflow(requested.size());
        }
        ProductProcessWorkflowActivation activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, ownerProductTypeId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .orElseThrow(() -> noMatchingWorkflow(requested.size()));
        ResolvedWorkflow resolved = loadResolved(factoryId, activation);
        if (resolved == null || !matchesSelection(
                resolved.topology, new HashSet<>(requested), requested.size())) {
            throw noMatchingWorkflow(requested.size());
        }
        return resolved;
    }

    private boolean matchesSelection(
            WorkflowTopology topology, Set<String> requestedSet, int requestedCount) {
        Set<String> terminals = new HashSet<>(topology.terminalOutputSkuIds());
        return requestedCount > 0
                && requestedSet.size() == requestedCount
                && terminals.containsAll(requestedSet);
    }

    private List<String> selectedOutputs(String productTypeId, List<String> requested) {
        List<String> targets = dedupeNonBlank(requested);
        if (!targets.isEmpty()) return targets;
        return productTypeId == null || productTypeId.isBlank()
                ? List.of() : List.of(productTypeId);
    }

    /** 从 activation 精确取 PUBLISHED workflow + 解析终端成品 skuId; 版本不符/坏图返 null (只读侧不瘫全厂)。 */
    private ResolvedWorkflow loadResolved(String factoryId, ProductProcessWorkflowActivation act) {
        ProductProcessWorkflow wf = workflowRepository
                .findByIdAndFactoryId(act.getActiveWorkflowId(), factoryId).orElse(null);
        if (wf == null
                || wf.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                || !java.util.Objects.equals(act.getActiveDefinitionVersion(), wf.getDefinitionVersion())) {
            return null;
        }
        if (!hasCurrentUnitContract(factoryId, wf)) return null;
        WorkflowTopology topology = parseTopology(wf);
        if (topology.type() == WorkflowTopology.Type.INVALID) {
            return null;
        }
        return new ResolvedWorkflow(wf, new LinkedHashSet<>(topology.terminalOutputSkuIds()), topology);
    }

    /**
     * unitReviewRequired is a broad invalidation marker set after unit master-data changes.
     * It is not proof that this exact Workflow is currently incompatible. Revalidate marked
     * definitions against the current factory/SKU contract so stale markers cannot hide an
     * otherwise valid enabled Workflow, while genuinely incompatible graphs remain fail-closed.
     */
    private boolean hasCurrentUnitContract(String factoryId, ProductProcessWorkflow workflow) {
        if (!Boolean.TRUE.equals(workflow.getUnitReviewRequired())) return true;
        try {
            return unitValidator.validate(factoryId, toDefinition(workflow)).valid();
        } catch (RuntimeException error) {
            log.warn("Workflow {} unit contract revalidation failed; excluding it from new plans",
                    workflow.getId(), error);
            return false;
        }
    }

    private ProductProcessWorkflowDTO toDefinition(ProductProcessWorkflow workflow) {
        try {
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setId(workflow.getId());
            definition.setFactoryId(workflow.getFactoryId());
            definition.setProductTypeId(workflow.getProductTypeId());
            definition.setSchemaVersion(workflow.getSchemaVersion());
            definition.setStatus(workflow.getStatus().name());
            definition.setVersion(workflow.getDefinitionVersion());
            definition.setLockVersion(workflow.getLockVersion());
            definition.setUnitReviewRequired(workflow.getUnitReviewRequired());
            definition.setNodes(objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            definition.setEdges(objectMapper.readValue(
                    workflow.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));
            return definition;
        } catch (Exception error) {
            throw new IllegalArgumentException("Workflow definition JSON is invalid", error);
        }
    }

    private WorkflowTopology parseTopology(ProductProcessWorkflow wf) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    wf.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            List<ProductProcessWorkflowDTO.Edge> edges = objectMapper.readValue(
                    wf.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { });
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setNodes(nodes);
            definition.setEdges(edges);
            return WorkflowTopologyClassifier.classify(definition);
        } catch (Exception e) {
            log.error("workflow {} nodesJson 解析失败, 该图剔除出候选", wf.getId(), e);
            return new WorkflowTopology(WorkflowTopology.Type.INVALID, List.of(), List.of(), 0);
        }
    }

    private WorkflowProcessPath parseProcessPath(
            String factoryId,
            ProductProcessWorkflow workflow,
            WorkflowOutputResolutionDTO.Candidate candidate,
            String terminalProductTypeId) {
        try {
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setId(workflow.getId());
            definition.setFactoryId(factoryId);
            definition.setProductTypeId(workflow.getProductTypeId());
            definition.setSchemaVersion(workflow.getSchemaVersion());
            definition.setVersion(workflow.getDefinitionVersion());
            definition.setRevisionId(workflow.getCurrentRevisionId());
            definition.setRevisionHash(workflow.getCurrentRevisionHash());
            definition.setNodes(objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            definition.setEdges(objectMapper.readValue(
                    workflow.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));

            PinnedWorkflowGraph graph = BomWorkflowRevisionService.resolveTargetGraph(
                    workflow.getCurrentRevisionId(), definition, terminalProductTypeId);
            if (ProductCategory.RAW_MATERIAL.equals(candidate.getOwnerProductCategory())
                    && !graph.rootMaterialTypeIds().contains(candidate.getOwnerProductTypeId())) {
                throw invalidPath("入口原料与 Workflow 所属原料不一致");
            }
            if (graph.processes().isEmpty()) {
                throw invalidPath("目标成品路径没有有效工序");
            }
            String ownerType = ProductCategory.RAW_MATERIAL.equals(candidate.getOwnerProductCategory())
                    ? "RAW_MATERIAL_TYPE" : "PRODUCT_TYPE";
            String singularRoot = graph.rootMaterialTypeIds().size() == 1
                    ? graph.rootMaterialTypeIds().getFirst() : null;
            return new WorkflowProcessPath(
                    workflow.getId(), workflow.getDefinitionVersion(),
                    candidate.getOwnerProductTypeId(), ownerType,
                    terminalProductTypeId, singularRoot, graph.rootMaterialTypeIds(),
                    graph.processes().stream().map(step -> new WorkflowProcessPath.ProcessStep(
                            step.processNodeId(), step.workProcessId(), step.order())).toList());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw invalidPath("Workflow 图无法解析");
        }
    }

    private BusinessException invalidPath(String message) {
        return new BusinessException(409, message)
                .withCode("WORKFLOW_PROCESS_PATH_INVALID")
                .withHint("请回到产品工序配置检查原料入口、成品出口和连线");
    }

    private WorkflowOutputResolutionDTO.Candidate buildCandidate(
            String factoryId, ProductType owner, ResolvedWorkflow rw, Set<String> requestedSet) {
        RawMaterialType rawOwner = owner != null && ProductCategory.RAW_MATERIAL.equals(owner.getProductCategory())
                ? rawMaterialTypeRepository.findById(owner.getId())
                    .filter(raw -> factoryId.equals(raw.getFactoryId()))
                    .orElse(null)
                : null;
        List<WorkflowOutputResolutionDTO.TerminalOutput> terminals = new ArrayList<>();
        Map<String, List<String>> workflowOutputUnits = parseTerminalOutputUnits(factoryId, rw.workflow);
        for (String skuId : rw.terminalSkuIds) {
            ProductType t = productTypeRepository.findByIdAndFactoryId(skuId, factoryId).orElse(null);
            List<String> portUnits = workflowOutputUnits.getOrDefault(skuId, List.of());
            terminals.add(WorkflowOutputResolutionDTO.TerminalOutput.builder()
                    .productTypeId(skuId)
                    .productName(t != null ? t.getName() : skuId)
                    .unit(portUnits.size() == 1 ? portUnits.get(0) : null)
                    .build());
        }
        WorkflowPreview preview = buildWorkflowPreview(rw.workflow);
        return WorkflowOutputResolutionDTO.Candidate.builder()
                .workflowId(rw.workflow.getId())
                .definitionVersion(rw.workflow.getDefinitionVersion())
                .ownerProductTypeId(rw.workflow.getProductTypeId())
                .ownerProductName(rawOwner != null ? rawOwner.getName() : owner == null ? null : owner.getName())
                .ownerProductCategory(owner == null ? null : owner.getProductCategory())
                .ownerUnit(rawOwner != null ? rawOwner.getUnit() : owner == null ? null : owner.getUnit())
                .plannedUnit(resolveCandidatePlanUnit(factoryId, rw.workflow.getProductTypeId(), rw))
                .terminalOutputs(terminals)
                .exactMatch(rw.terminalSkuIds.size() == requestedSet.size())
                .workflowType(rw.topology.type().name())
                .rootInputProductTypeIds(rw.topology.rootInputSkuIds())
                .logicalRootInputCount(rw.topology.logicalRootInputCount())
                .processSteps(preview.processSteps)
                .previewNodes(preview.nodes)
                .previewEdges(preview.edges)
                .build();
    }

    private WorkflowPreview buildWorkflowPreview(ProductProcessWorkflow workflow) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            List<ProductProcessWorkflowDTO.Edge> edges = objectMapper.readValue(
                    workflow.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { });
            Map<String, ProductProcessWorkflowDTO.Node> nodeById = nodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> node.getId() != null && !node.getId().isBlank())
                    .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId,
                            java.util.function.Function.identity(), (left, right) -> left,
                            LinkedHashMap::new));
            Map<String, Integer> incomingCount = new HashMap<>();
            Map<String, List<String>> outgoing = new HashMap<>();
            Map<String, Integer> depth = new HashMap<>();
            nodeById.keySet().forEach(id -> {
                incomingCount.put(id, 0);
                depth.put(id, 0);
            });
            List<WorkflowOutputResolutionDTO.PreviewEdge> previewEdges = new ArrayList<>();
            for (ProductProcessWorkflowDTO.Edge edge : edges) {
                if (edge == null || !nodeById.containsKey(edge.getSource())
                        || !nodeById.containsKey(edge.getTarget())) {
                    continue;
                }
                incomingCount.compute(edge.getTarget(), (ignored, count) -> count == null ? 1 : count + 1);
                outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
                previewEdges.add(WorkflowOutputResolutionDTO.PreviewEdge.builder()
                        .id(edge.getId())
                        .source(edge.getSource())
                        .target(edge.getTarget())
                        .build());
            }
            ArrayDeque<String> queue = nodeById.values().stream()
                    .filter(node -> incomingCount.getOrDefault(node.getId(), 0) == 0)
                    .sorted(previewNodeComparator())
                    .map(ProductProcessWorkflowDTO.Node::getId)
                    .collect(Collectors.toCollection(ArrayDeque::new));
            while (!queue.isEmpty()) {
                String source = queue.removeFirst();
                List<String> targets = outgoing.getOrDefault(source, List.of()).stream()
                        .sorted()
                        .toList();
                for (String target : targets) {
                    depth.put(target, Math.max(depth.getOrDefault(target, 0), depth.getOrDefault(source, 0) + 1));
                    int remaining = incomingCount.computeIfPresent(target, (ignored, count) -> count - 1);
                    if (remaining == 0) queue.addLast(target);
                }
            }
            List<ProductProcessWorkflowDTO.Node> orderedNodes = nodeById.values().stream()
                    .sorted(Comparator
                            .comparingInt((ProductProcessWorkflowDTO.Node node) -> depth.getOrDefault(node.getId(), 0))
                            .thenComparing(previewNodeComparator()))
                    .toList();
            List<WorkflowOutputResolutionDTO.PreviewNode> previewNodes = orderedNodes.stream()
                    .map(node -> WorkflowOutputResolutionDTO.PreviewNode.builder()
                            .id(node.getId())
                            .kind(node.getKind())
                            .label(previewNodeLabel(node))
                            .unit(previewNodeUnit(node))
                            .build())
                    .toList();
            List<String> processSteps = orderedNodes.stream()
                    .filter(node -> "PROCESS".equals(node.getKind()))
                    .map(this::previewNodeLabel)
                    .toList();
            return new WorkflowPreview(processSteps, previewNodes, previewEdges);
        } catch (Exception error) {
            log.warn("Failed to build workflow selection preview: workflowId={}", workflow.getId(), error);
            return new WorkflowPreview(List.of(), List.of(), List.of());
        }
    }

    private Comparator<ProductProcessWorkflowDTO.Node> previewNodeComparator() {
        return Comparator
                .comparingDouble((ProductProcessWorkflowDTO.Node node) ->
                        node.getPosition() == null || node.getPosition().getX() == null
                                ? Double.MAX_VALUE : node.getPosition().getX())
                .thenComparingDouble(node ->
                        node.getPosition() == null || node.getPosition().getY() == null
                                ? Double.MAX_VALUE : node.getPosition().getY())
                .thenComparing(ProductProcessWorkflowDTO.Node::getId);
    }

    private String previewNodeLabel(ProductProcessWorkflowDTO.Node node) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        Object primary = "PROCESS".equals(node.getKind()) ? data.get("processName") : data.get("name");
        String label = stringValue(primary);
        if (label != null) return label;
        String skuId = stringValue(data.get("skuId"));
        if (skuId != null) return skuId;
        return switch (String.valueOf(node.getKind())) {
            case "RAW_MATERIAL" -> "未命名原料";
            case "SEMI_FINISHED" -> "未命名半成品";
            case "FINISHED_GOOD" -> "未命名成品";
            default -> "未命名工序";
        };
    }

    private String previewNodeUnit(ProductProcessWorkflowDTO.Node node) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        if (!"PROCESS".equals(node.getKind())) {
            return stringValue(data.get("baseUnit"));
        }
        String inputUnit = stringValue(data.get("inputUnit"));
        String outputUnit = stringValue(data.get("outputUnit"));
        if (inputUnit == null) return outputUnit;
        if (outputUnit == null || inputUnit.equals(outputUnit)) return inputUnit;
        return inputUnit + " → " + outputUnit;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String resolutionMessage(List<ResolvedCandidate> candidates) {
        if (candidates.size() > 1) {
            return "匹配到多条同优先级 Workflow，请根据工序链选择本计划使用的版本";
        }
        return candidates.getFirst().exact
                ? "已匹配启用的工序 Workflow"
                : "匹配到包含额外联产成品的 Workflow，请确认完整产出集合";
    }

    private BusinessException staleSelection() {
        return new BusinessException(409, "所选 Workflow 已被切换或失效，请重新选择")
                .withCode("WORKFLOW_SELECTED_VERSION_CHANGED")
                .withHint("刷新生产成品选择后，重新确认工序链")
                .withHintTarget("selectedWorkflowId")
                .withSeverity("warning");
    }

    private String resolveCandidatePlanUnit(
            String factoryId, String ownerProductTypeId, ResolvedWorkflow workflow) {
        if (!workflow.topology.rootInputSkuIds().contains(ownerProductTypeId)) {
            List<String> units = parseTerminalOutputUnits(factoryId, workflow.workflow)
                    .getOrDefault(ownerProductTypeId, List.of());
            return units.size() == 1 ? units.get(0) : null;
        }
        Set<String> units = parseOwnerInputUnits(factoryId, workflow.workflow, ownerProductTypeId);
        return units.size() == 1 ? units.iterator().next() : null;
    }

    private Map<String, ProductType> batchOwners(
            String factoryId, List<ProductProcessWorkflowActivation> activations) {
        Set<String> ownerIds = activations.stream()
                .map(ProductProcessWorkflowActivation::getProductTypeId)
                .collect(Collectors.toSet());
        Map<String, ProductType> map = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return map;
        }
        for (ProductType pt : productTypeRepository.findByIdIn(new ArrayList<>(ownerIds))) {
            if (factoryId.equals(pt.getFactoryId())) {
                map.put(pt.getId(), pt);
            }
        }
        return map;
    }

    private List<String> dedupeNonBlank(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private BusinessException noMatchingWorkflow(int requestedCount) {
        if (requestedCount == 1) {
            return new BusinessException(409, "未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置")
                    .withCode("WORKFLOW_SINGLE_OUTPUT_NOT_FOUND")
                    .withHintTarget("productTypeId")
                    .withSeverity("warning");
        }
        return new BusinessException(409, "未找到共享的工序 Workflow，请分开创建生产计划")
                .withCode("WORKFLOW_SHARED_OUTPUT_NOT_FOUND")
                .withHintTarget("targetFinishedGoodIds")
                .withSeverity("warning");
    }

    private static final class ResolvedWorkflow {
        final ProductProcessWorkflow workflow;
        final Set<String> terminalSkuIds;
        final WorkflowTopology topology;
        ResolvedWorkflow(ProductProcessWorkflow workflow, Set<String> terminalSkuIds,
                         WorkflowTopology topology) {
            this.workflow = workflow;
            this.terminalSkuIds = terminalSkuIds;
            this.topology = topology;
        }
    }

    private static final class ResolvedCandidate {
        final ProductType owner;
        final ResolvedWorkflow rw;
        final boolean exact;
        final LocalDateTime activatedAt;
        ResolvedCandidate(ProductType owner, ResolvedWorkflow rw, boolean exact, LocalDateTime activatedAt) {
            this.owner = owner;
            this.rw = rw;
            this.exact = exact;
            this.activatedAt = activatedAt;
        }
    }

    private record WorkflowPreview(
            List<String> processSteps,
            List<WorkflowOutputResolutionDTO.PreviewNode> nodes,
            List<WorkflowOutputResolutionDTO.PreviewEdge> edges) {
    }
}
