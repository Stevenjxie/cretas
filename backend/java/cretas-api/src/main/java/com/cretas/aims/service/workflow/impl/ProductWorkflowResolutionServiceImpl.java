package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductWorkflowResolutionServiceImpl implements ProductWorkflowResolutionService {

    private static final String FINISHED_GOOD = "FINISHED_GOOD";

    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ObjectMapper objectMapper;

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

    // ---- 内部 ----

    /** 从 activation 精确取 PUBLISHED workflow + 解析终端成品 skuId; 版本不符/坏图返 null (只读侧不瘫全厂)。 */
    private ResolvedWorkflow loadResolved(String factoryId, ProductProcessWorkflowActivation act) {
        ProductProcessWorkflow wf = workflowRepository
                .findByIdAndFactoryId(act.getActiveWorkflowId(), factoryId).orElse(null);
        if (wf == null
                || wf.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
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
            Set<String> skuIds = new LinkedHashSet<>();
            for (ProductProcessWorkflowDTO.Node node : nodes) {
                if (node != null && FINISHED_GOOD.equals(node.getKind()) && node.getData() != null) {
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

    private WorkflowOutputResolutionDTO.Candidate buildCandidate(
            String factoryId, ProductType owner, ResolvedWorkflow rw, Set<String> requestedSet) {
        List<WorkflowOutputResolutionDTO.TerminalOutput> terminals = new ArrayList<>();
        for (String skuId : rw.terminalSkuIds) {
            ProductType t = productTypeRepository.findByIdAndFactoryId(skuId, factoryId).orElse(null);
            terminals.add(WorkflowOutputResolutionDTO.TerminalOutput.builder()
                    .productTypeId(skuId)
                    .productName(t != null ? t.getName() : skuId)
                    .unit(t != null ? t.getUnit() : null)
                    .build());
        }
        return WorkflowOutputResolutionDTO.Candidate.builder()
                .workflowId(rw.workflow.getId())
                .definitionVersion(rw.workflow.getDefinitionVersion())
                .ownerProductTypeId(owner.getId())
                .ownerProductName(owner.getName())
                .ownerProductCategory(owner.getProductCategory())
                .ownerUnit(owner.getUnit())
                .terminalOutputs(terminals)
                .exactMatch(rw.terminalSkuIds.size() == requestedSet.size())
                .build();
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
