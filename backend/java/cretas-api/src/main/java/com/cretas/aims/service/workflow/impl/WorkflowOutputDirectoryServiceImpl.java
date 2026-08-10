package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.WorkflowOutputDirectoryDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.workflow.WorkflowOutputDirectoryService;
import com.cretas.aims.service.workflow.WorkflowTopology;
import com.cretas.aims.service.workflow.WorkflowTopologyClassifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 配置侧「按产出成品反查工艺图」。
 *
 * <p>⛔ 这里刻意**没有**调用 {@code ProductWorkflowResolutionServiceImpl#requireResolutionCandidates}。
 * 那个方法在匹配之后还会做两件本入口不能做的事: ①要求勾选集合与终端集合**相等**才算精确;
 * ②没有精确候选时只留「额外产出最少」的那一层, 把其它同样产出该成品的图**丢掉**。
 * 配置侧要回答的是「谁产出它」, 丢掉任何一张都会让用户在界面上找不到那张图。
 * 两种语义各自独立成入口(spec §4.5), 而不是同一个方法加布尔开关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOutputDirectoryServiceImpl implements WorkflowOutputDirectoryService {

    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public WorkflowOutputDirectoryDTO findWorkflowsProducing(
            String factoryId, String finishedGoodProductTypeId) {
        String target = finishedGoodProductTypeId == null ? "" : finishedGoodProductTypeId.trim();
        if (factoryId == null || factoryId.isBlank() || target.isEmpty()) {
            return WorkflowOutputDirectoryDTO.builder()
                    .finishedGoodProductTypeId(target)
                    .workflows(List.of())
                    .build();
        }

        List<Match> matches = new ArrayList<>();
        for (ProductProcessWorkflowActivation activation
                : activationRepository.findByFactoryIdAndEnabledTrue(factoryId)) {
            ProductProcessWorkflow workflow = liveWorkflow(factoryId, activation);
            if (workflow == null) continue;
            WorkflowTopology topology = parseTopology(workflow);
            if (topology.type() == WorkflowTopology.Type.INVALID) continue;
            // 包含语义 —— 只问「这张图的终端产出里有没有它」, 不问「是不是只有它」。
            if (!topology.terminalOutputSkuIds().contains(target)) continue;
            matches.add(new Match(activation, workflow, topology));
        }
        matches.sort(Comparator
                .comparingInt((Match match) -> match.topology.terminalOutputSkuIds().size())
                .thenComparing(match -> match.workflow.getId()));

        Map<String, String> names = resolveNames(factoryId, matches);
        return WorkflowOutputDirectoryDTO.builder()
                .finishedGoodProductTypeId(target)
                .workflows(matches.stream().map(match -> toEntry(match, names)).toList())
                .build();
    }

    /** activation 指向的图必须真的是 PUBLISHED 且版本对得上, 否则「已启用」这句话是假的。 */
    private ProductProcessWorkflow liveWorkflow(
            String factoryId, ProductProcessWorkflowActivation activation) {
        ProductProcessWorkflow workflow = workflowRepository
                .findByIdAndFactoryId(activation.getActiveWorkflowId(), factoryId).orElse(null);
        if (workflow == null
                || workflow.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                || !Objects.equals(
                        activation.getActiveDefinitionVersion(), workflow.getDefinitionVersion())) {
            return null;
        }
        return workflow;
    }

    private WorkflowOutputDirectoryDTO.Entry toEntry(Match match, Map<String, String> names) {
        String anchor = match.activation.getProductTypeId();
        List<String> terminals = match.topology.terminalOutputSkuIds();
        return WorkflowOutputDirectoryDTO.Entry.builder()
                .workflowId(match.workflow.getId())
                .definitionVersion(match.workflow.getDefinitionVersion())
                .ownerProductTypeId(anchor)
                .ownerProductName(names.getOrDefault(anchor, anchor))
                .workflowType(match.topology.type().name())
                .terminalOutputs(terminals.stream()
                        .map(sku -> WorkflowOutputDirectoryDTO.TerminalOutput.builder()
                                .productTypeId(sku)
                                .productName(names.getOrDefault(sku, sku))
                                .build())
                        .toList())
                .anchorIsTerminalOutput(terminals.contains(anchor))
                .build();
    }

    /** 锚点可能是成品也可能是原料(原料归属分流路线), 两张表都要查。 */
    private Map<String, String> resolveNames(String factoryId, List<Match> matches) {
        Set<String> ids = new LinkedHashSet<>();
        for (Match match : matches) {
            ids.add(match.activation.getProductTypeId());
            ids.addAll(match.topology.terminalOutputSkuIds());
        }
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        List<String> lookup = new ArrayList<>(ids);
        for (ProductType product : productTypeRepository.findByIdIn(lookup)) {
            if (factoryId.equals(product.getFactoryId()) && product.getName() != null) {
                names.put(product.getId(), product.getName());
            }
        }
        List<String> stillUnknown = ids.stream().filter(id -> !names.containsKey(id)).toList();
        if (stillUnknown.isEmpty()) return names;
        for (RawMaterialType material : rawMaterialTypeRepository.findByIdIn(stillUnknown)) {
            if (factoryId.equals(material.getFactoryId()) && material.getName() != null) {
                names.put(material.getId(), material.getName());
            }
        }
        return names;
    }

    private WorkflowTopology parseTopology(ProductProcessWorkflow workflow) {
        try {
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setNodes(objectMapper.readValue(
                    workflow.getNodesJson(),
                    new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            definition.setEdges(objectMapper.readValue(
                    workflow.getEdgesJson(),
                    new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));
            return WorkflowTopologyClassifier.classify(definition);
        } catch (Exception error) {
            log.error("workflow {} 图 JSON 解析失败, 反查时跳过该图", workflow.getId(), error);
            return new WorkflowTopology(WorkflowTopology.Type.INVALID, List.of(), List.of(), 0);
        }
    }

    private record Match(
            ProductProcessWorkflowActivation activation,
            ProductProcessWorkflow workflow,
            WorkflowTopology topology) {
    }
}
