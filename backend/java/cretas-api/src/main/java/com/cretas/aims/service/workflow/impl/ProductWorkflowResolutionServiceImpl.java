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
import com.cretas.aims.service.unit.UnitContractService;
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
        Set<String> requestedSet = new HashSet<>(requested);

        // 单选优先该成品自有图 (owner 非原料)
        if (requested.size() == 1) {
            String only = requested.get(0);
            ProductProcessWorkflowActivation self = activationRepository
                    .findByFactoryIdAndProductTypeId(factoryId, only).orElse(null);
            if (self != null && Boolean.TRUE.equals(self.getEnabled())) {
                ProductType ownerPt = productTypeRepository.findByIdAndFactoryId(only, factoryId).orElse(null);
                if (ownerPt != null && !ProductCategory.RAW_MATERIAL.equals(ownerPt.getProductCategory())) {
                    ResolvedWorkflow rw = loadResolved(factoryId, self);
                    if (rw != null && rw.terminalSkuIds.containsAll(requestedSet)) {
                        return WorkflowOutputResolutionDTO.builder()
                                .requestedProductTypeIds(requested)
                                .resolutionMode("SELF_WORKFLOW")
                                .candidates(List.of(buildCandidate(factoryId, ownerPt, rw, requestedSet)))
                                .build();
                    }
                }
            }
        }

        // 原料图候选: 扫已启用 activation → 过滤 owner=原料 → 复核版本/PUBLISHED → 终端 ⊇ 所选
        List<ProductProcessWorkflowActivation> enabled =
                activationRepository.findByFactoryIdAndEnabledTrue(factoryId);
        Map<String, ProductType> ownerById = batchOwners(factoryId, enabled);
        List<ResolvedCandidate> resolved = new ArrayList<>();
        for (ProductProcessWorkflowActivation act : enabled) {
            ProductType owner = ownerById.get(act.getProductTypeId());
            if (owner == null || !ProductCategory.RAW_MATERIAL.equals(owner.getProductCategory())) {
                continue;
            }
            ResolvedWorkflow rw = loadResolved(factoryId, act);
            if (rw == null) {
                continue;
            }
            if (rw.terminalSkuIds.containsAll(requestedSet)) {
                boolean exact = rw.terminalSkuIds.size() == requestedSet.size();
                resolved.add(new ResolvedCandidate(owner, rw, exact, act.getActivatedAt()));
            }
        }
        // 排序: 精确匹配 > 终端数少 > 启用时间新
        resolved.sort(Comparator
                .comparing((ResolvedCandidate c) -> c.exact ? 0 : 1)
                .thenComparingInt(c -> c.rw.terminalSkuIds.size())
                .thenComparing(c -> c.activatedAt == null ? LocalDateTime.MIN : c.activatedAt,
                        Comparator.reverseOrder()));

        List<WorkflowOutputResolutionDTO.Candidate> candidates = resolved.stream()
                .map(c -> buildCandidate(factoryId, c.owner, c.rw, requestedSet))
                .collect(Collectors.toList());
        return WorkflowOutputResolutionDTO.builder()
                .requestedProductTypeIds(requested)
                .resolutionMode(candidates.isEmpty() ? "NONE" : "RAW_OWNED")
                .candidates(candidates)
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
        return Optional.of(parseProcessPath(workflow, candidate, finishedGoodProductTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public void assertActiveWorkflowCoversOutputs(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds) {
        List<String> targets = dedupeNonBlank(targetFinishedGoodIds);
        if (targets.isEmpty()) {
            return;
        }
        ProductProcessWorkflowActivation act = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, ownerProductTypeId)
                .filter(a -> Boolean.TRUE.equals(a.getEnabled()))
                .orElseThrow(this::notCovered);
        ResolvedWorkflow rw = loadResolved(factoryId, act);
        if (rw == null || !rw.terminalSkuIds.containsAll(new HashSet<>(targets))) {
            throw notCovered();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowPlanOutputContract> resolveActivePlanOutputContract(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds) {
        ProductProcessWorkflowActivation activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, ownerProductTypeId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .orElse(null);
        if (activation == null) return Optional.empty();

        ResolvedWorkflow resolved = loadResolved(factoryId, activation);
        if (resolved == null) {
            throw new BusinessException(409, "已启用的工序 Workflow 版本无效或等待单位复核")
                    .withCode("WORKFLOW_PLAN_CONTRACT_INVALID");
        }
        List<String> targets = dedupeNonBlank(targetFinishedGoodIds);
        if (targets.isEmpty()) {
            if (resolved.terminalSkuIds.contains(ownerProductTypeId)) {
                targets = List.of(ownerProductTypeId);
            } else if (resolved.terminalSkuIds.size() == 1) {
                targets = List.copyOf(resolved.terminalSkuIds);
            } else {
                throw new BusinessException(409, "该 Workflow 有多个成品出口，请明确选择计划成品")
                        .withCode("WORKFLOW_PLAN_OUTPUT_AMBIGUOUS");
            }
        }
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
        if (resolved.terminalSkuIds.contains(ownerProductTypeId)) {
            Set<String> distinctUnits = new LinkedHashSet<>(selected.values());
            if (distinctUnits.size() != 1) {
                throw new BusinessException(409, "所选成品的 Workflow 产出单位不一致，不能合并为一个计划数量")
                        .withCode("WORKFLOW_PLAN_OUTPUT_UNIT_AMBIGUOUS");
            }
            plannedUnit = distinctUnits.iterator().next();
        } else {
            Set<String> ownerInputUnits = parseOwnerInputUnits(
                    factoryId, resolved.workflow, ownerProductTypeId);
            if (ownerInputUnits.size() != 1) {
                throw new BusinessException(409, "原料中心 Workflow 必须为计划原料提供唯一入口单位")
                        .withCode("WORKFLOW_PLAN_INPUT_UNIT_AMBIGUOUS");
            }
            plannedUnit = ownerInputUnits.iterator().next();
        }
        return Optional.of(new WorkflowPlanOutputContract(
                resolved.workflow.getId(), resolved.workflow.getDefinitionVersion(),
                Map.copyOf(selected), plannedUnit));
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

    /** 从 activation 精确取 PUBLISHED workflow + 解析终端成品 skuId; 版本不符/坏图返 null (只读侧不瘫全厂)。 */
    private ResolvedWorkflow loadResolved(String factoryId, ProductProcessWorkflowActivation act) {
        ProductProcessWorkflow wf = workflowRepository
                .findByIdAndFactoryId(act.getActiveWorkflowId(), factoryId).orElse(null);
        if (wf == null
                || wf.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                || Boolean.TRUE.equals(wf.getUnitReviewRequired())
                || !java.util.Objects.equals(act.getActiveDefinitionVersion(), wf.getDefinitionVersion())) {
            return null;
        }
        Set<String> terminals = parseTerminalSkuIds(wf);
        if (terminals == null) {
            return null;
        }
        return new ResolvedWorkflow(wf, terminals);
    }

    private Set<String> parseTerminalSkuIds(ProductProcessWorkflow wf) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    wf.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            List<ProductProcessWorkflowDTO.Edge> edges = objectMapper.readValue(
                    wf.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { });
            Set<String> nodesWithOutgoingEdges = edges.stream()
                    .map(ProductProcessWorkflowDTO.Edge::getSource)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> skuIds = new LinkedHashSet<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node != null && FINISHED_GOOD.equals(node.getKind()) && node.getData() != null
                        && !nodesWithOutgoingEdges.contains(node.getId())) {
                    Object skuId = node.getData().get("skuId");
                    if (skuId instanceof String s && !s.isBlank()) {
                        skuIds.add(s);
                    }
                }
            }
            return skuIds;
        } catch (Exception e) {
            log.error("workflow {} nodesJson 解析失败, 该图剔除出候选", wf.getId(), e);
            return null;
        }
    }

    private WorkflowProcessPath parseProcessPath(
            ProductProcessWorkflow workflow,
            WorkflowOutputResolutionDTO.Candidate candidate,
            String terminalProductTypeId) {
        try {
            List<ProductProcessWorkflowDTO.Node> nodes = objectMapper.readValue(
                    workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { });
            List<ProductProcessWorkflowDTO.Edge> edges = objectMapper.readValue(
                    workflow.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { });
            Map<String, ProductProcessWorkflowDTO.Node> nodeById = nodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> node.getId() != null)
                    .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId,
                            java.util.function.Function.identity(), (left, right) -> left));
            Map<String, List<ProductProcessWorkflowDTO.Edge>> incoming = edges.stream()
                    .collect(Collectors.groupingBy(ProductProcessWorkflowDTO.Edge::getTarget));
            Set<String> hasOutgoing = edges.stream()
                    .map(ProductProcessWorkflowDTO.Edge::getSource)
                    .collect(Collectors.toSet());

            List<ProductProcessWorkflowDTO.Node> terminalNodes = nodes.stream()
                    .filter(node -> node != null && FINISHED_GOOD.equals(node.getKind()))
                    .filter(node -> node.getData() != null
                            && terminalProductTypeId.equals(node.getData().get("skuId")))
                    .filter(node -> !hasOutgoing.contains(node.getId()))
                    .toList();
            if (terminalNodes.size() != 1) {
                throw invalidPath("目标成品必须恰好对应一个实际终端 Cell");
            }

            Set<String> ancestors = new LinkedHashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.add(terminalNodes.get(0).getId());
            while (!pending.isEmpty()) {
                String nodeId = pending.removeFirst();
                if (!ancestors.add(nodeId)) continue;
                for (ProductProcessWorkflowDTO.Edge edge : incoming.getOrDefault(nodeId, List.of())) {
                    if (nodeById.containsKey(edge.getSource())) pending.addLast(edge.getSource());
                }
            }

            List<ProductProcessWorkflowDTO.Node> rawRoots = ancestors.stream()
                    .map(nodeById::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                    .filter(node -> incoming.getOrDefault(node.getId(), List.of()).isEmpty())
                    .toList();
            if (rawRoots.size() != 1) {
                throw invalidPath("目标成品路径必须恰好回溯到一个入口原料 Cell");
            }
            String rawRootId = rawRoots.get(0).getData() == null
                    ? null : String.valueOf(rawRoots.get(0).getData().get("skuId"));
            if (ProductCategory.RAW_MATERIAL.equals(candidate.getOwnerProductCategory())
                    && !candidate.getOwnerProductTypeId().equals(rawRootId)) {
                throw invalidPath("入口原料与 Workflow 所属原料不一致");
            }

            Map<String, Integer> indegree = new LinkedHashMap<>();
            Map<String, List<String>> outgoing = new LinkedHashMap<>();
            ancestors.forEach(id -> indegree.put(id, 0));
            for (ProductProcessWorkflowDTO.Edge edge : edges) {
                if (!ancestors.contains(edge.getSource()) || !ancestors.contains(edge.getTarget())) continue;
                outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
                indegree.computeIfPresent(edge.getTarget(), (ignored, value) -> value + 1);
            }
            ArrayDeque<String> ready = new ArrayDeque<>();
            indegree.forEach((id, degree) -> { if (degree == 0) ready.addLast(id); });
            List<WorkflowProcessPath.ProcessStep> processSteps = new ArrayList<>();
            int order = 1;
            int visited = 0;
            while (!ready.isEmpty()) {
                String nodeId = ready.removeFirst();
                visited++;
                ProductProcessWorkflowDTO.Node node = nodeById.get(nodeId);
                if (node != null && "PROCESS".equals(node.getKind())) {
                    String workProcessId = node.getData() == null
                            ? null : String.valueOf(node.getData().get("workProcessId"));
                    if (workProcessId == null || workProcessId.isBlank() || "null".equals(workProcessId)) {
                        throw invalidPath("工序 Cell 缺少工序主数据绑定");
                    }
                    processSteps.add(new WorkflowProcessPath.ProcessStep(nodeId, workProcessId, order++));
                }
                for (String target : outgoing.getOrDefault(nodeId, List.of())) {
                    int next = indegree.computeIfPresent(target, (ignored, value) -> value - 1);
                    if (next == 0) ready.addLast(target);
                }
            }
            if (visited != ancestors.size() || processSteps.isEmpty()) {
                throw invalidPath("目标成品路径存在环路或没有有效工序");
            }
            String ownerType = ProductCategory.RAW_MATERIAL.equals(candidate.getOwnerProductCategory())
                    ? "RAW_MATERIAL_TYPE" : "PRODUCT_TYPE";
            return new WorkflowProcessPath(
                    workflow.getId(), workflow.getDefinitionVersion(),
                    candidate.getOwnerProductTypeId(), ownerType,
                    terminalProductTypeId, rawRootId, List.copyOf(processSteps));
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
        RawMaterialType rawOwner = ProductCategory.RAW_MATERIAL.equals(owner.getProductCategory())
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
        return WorkflowOutputResolutionDTO.Candidate.builder()
                .workflowId(rw.workflow.getId())
                .definitionVersion(rw.workflow.getDefinitionVersion())
                .ownerProductTypeId(owner.getId())
                .ownerProductName(rawOwner != null ? rawOwner.getName() : owner.getName())
                .ownerProductCategory(owner.getProductCategory())
                .ownerUnit(rawOwner != null ? rawOwner.getUnit() : owner.getUnit())
                .plannedUnit(resolveCandidatePlanUnit(factoryId, owner.getId(), rw))
                .terminalOutputs(terminals)
                .exactMatch(rw.terminalSkuIds.size() == requestedSet.size())
                .build();
    }

    private String resolveCandidatePlanUnit(
            String factoryId, String ownerProductTypeId, ResolvedWorkflow workflow) {
        if (workflow.terminalSkuIds.contains(ownerProductTypeId)) {
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

    private BusinessException notCovered() {
        return new BusinessException(409, "所选成品没有对应的通用(原料)工序配置, 请先配置")
                .withCode("WORKFLOW_RESOLUTION_NOT_COVERED")
                .withHint("到「产品工序配置」以原料为锚建一张覆盖这些成品的工序图并启用; 或分别为每个成品单独建计划")
                .withHintTarget("targetFinishedGoodIds")
                .withSeverity("warning");
    }

    private static final class ResolvedWorkflow {
        final ProductProcessWorkflow workflow;
        final Set<String> terminalSkuIds;
        ResolvedWorkflow(ProductProcessWorkflow workflow, Set<String> terminalSkuIds) {
            this.workflow = workflow;
            this.terminalSkuIds = terminalSkuIds;
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
}
