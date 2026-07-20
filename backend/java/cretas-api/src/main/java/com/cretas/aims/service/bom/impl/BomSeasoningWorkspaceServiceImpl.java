package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.bom.SeasoningBindingMutationResponse;
import com.cretas.aims.dto.bom.SeasoningBindingUpdateRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BomSeasoningWorkspaceServiceImpl implements BomSeasoningWorkspaceService {

    private static final Set<String> AUXILIARY_CATEGORIES = Set.of(
            "AUXILIARY", "SEASONING", "辅料", "调料", "调味料");

    private final BomRecipeRepository recipeRepository;
    private final BomSeasoningItemRepository seasoningItemRepository;
    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final WorkProcessRepository workProcessRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final ProductWorkflowResolutionService workflowResolutionService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final BomItemSubstituteService substituteService;

    @Override
    @Transactional(readOnly = true)
    public BomSeasoningWorkspaceResponse getWorkspace(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        PinnedWorkflowGraph pinnedGraph = recipe.getWorkflowRevisionHash() == null
                ? null
                : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
        List<ResolvedProcess> workflow = resolveProcesses(factoryId, recipe, pinnedGraph);
        List<BomSeasoningItem> bindings = seasoningItemRepository.findByRecipeIdOrderBySeqAsc(recipeId);

        List<String> processIds = workflow.stream().map(ResolvedProcess::workProcessId).distinct().toList();
        Map<String, WorkProcess> workProcesses = workProcessRepository
                .findByFactoryIdAndIdIn(factoryId, processIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, Function.identity()));
        Map<String, List<BomSeasoningItem>> byNode = bindings.stream()
                .filter(b -> b.getWorkflowProcessNodeId() != null)
                .collect(Collectors.groupingBy(BomSeasoningItem::getWorkflowProcessNodeId));
        Map<String, Long> masterOccurrences = workflow.stream().collect(Collectors.groupingBy(
                ResolvedProcess::workProcessId, Collectors.counting()));
        Map<String, List<BomSeasoningItem>> legacyByProcess = bindings.stream()
                .filter(b -> b.getWorkflowProcessNodeId() == null && b.getWorkProcessId() != null)
                .collect(Collectors.groupingBy(BomSeasoningItem::getWorkProcessId));

        BomSeasoningWorkspaceResponse response = new BomSeasoningWorkspaceResponse();
        response.setRecipeId(recipe.getId());
        response.setProductTypeId(recipe.getProductTypeId());
        response.setProductName(recipe.getProductName());
        response.setStatus(recipe.getStatus());
        response.setEditable(recipe.getStatus() == BomRecipe.Status.DRAFT);
        response.setSeasoningRevision(recipe.getSeasoningRevision());
        populatePinnedRevisionSummary(response, factoryId, recipe, pinnedGraph);

        for (ResolvedProcess configured : workflow) {
            WorkProcess master = workProcesses.get(configured.workProcessId());
            List<BomSeasoningItem> processBindings = new ArrayList<>(
                    byNode.getOrDefault(configured.processNodeId(), List.of()));
            if (masterOccurrences.getOrDefault(configured.workProcessId(), 0L) == 1L) {
                processBindings.addAll(legacyByProcess.getOrDefault(configured.workProcessId(), List.of()));
            }
            response.getProcesses().add(new BomSeasoningWorkspaceResponse.ProcessView(
                    configured.processNodeId(),
                    configured.workProcessId(),
                    master != null ? master.getProcessName() : configured.workProcessId(),
                    master != null ? master.getProcessCategory() : null,
                    configured.processOrder(),
                    configured.standardBasisQuantity(),
                    configured.standardBasisUnit(),
                    configured.standardUsageSupported(),
                    processBindings));
        }

        Map<String, RawMaterialType> materials = materialTypeRepository.findAllById(bindings.stream()
                        .map(BomSeasoningItem::getMaterialTypeId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(RawMaterialType::getId, Function.identity()));
        Map<String, String> processNames = response.getProcesses().stream().collect(Collectors.toMap(
                BomSeasoningWorkspaceResponse.ProcessView::getWorkflowProcessNodeId,
                BomSeasoningWorkspaceResponse.ProcessView::getProcessName));
        Set<String> validWorkProcessIds = workflow.stream().map(ResolvedProcess::workProcessId).collect(Collectors.toSet());

        LinkedHashMap<String, List<BomSeasoningItem>> byMaterial = new LinkedHashMap<>();
        for (BomSeasoningItem binding : bindings) {
            collectAnomalies(response, binding, processNames, validWorkProcessIds, masterOccurrences, materials);
            if (binding.getMaterialTypeId() != null) {
                byMaterial.computeIfAbsent(binding.getMaterialTypeId(), ignored -> new ArrayList<>()).add(binding);
            }
        }
        byMaterial.forEach((materialId, usages) -> {
            RawMaterialType material = materials.get(materialId);
            BomSeasoningItem first = usages.get(0);
            List<BomSeasoningWorkspaceResponse.ProcessUsage> processUsages = usages.stream()
                    .map(binding -> new BomSeasoningWorkspaceResponse.ProcessUsage(
                            binding.getWorkflowProcessNodeId(),
                            binding.getWorkProcessId(),
                            processNames.getOrDefault(binding.getWorkflowProcessNodeId(), binding.getWorkProcessId()),
                            binding.getDosagePerKgG(),
                            binding.getSubsequentPotRatio()))
                    .toList();
            response.getMaterialSummaries().add(new BomSeasoningWorkspaceResponse.MaterialSummary(
                    materialId,
                    material != null ? material.getCode() : null,
                    material != null ? material.getName() : first.getName(),
                    material != null ? material.getCategory() : null,
                    material != null ? material.getUnit() : null,
                    first.getPriceSource1(),
                    processUsages));
        });
        return response;
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse createBinding(String factoryId, String recipeId,
                                                           String workProcessId,
                                                           SeasoningBindingCreateRequest request) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        ResolvedProcess process = validateWorkflow(recipe, factoryId, workProcessId,
                request.getWorkflowProcessNodeId());
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                recipeId, process.processNodeId(), material.getId()).ifPresent(existing -> {
            throw duplicate(existing);
        });
        claimRevision(recipe, factoryId, request.getExpectedRevision());

        BomSeasoningItem binding = new BomSeasoningItem();
        binding.setRecipeId(recipeId);
        binding.setFactoryId(factoryId);
        binding.setWorkProcessId(workProcessId);
        binding.setWorkflowProcessNodeId(process.processNodeId());
        binding.setMaterialTypeId(material.getId());
        binding.setSection("COOKING");
        binding.setSeq(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(
                recipeId, process.processNodeId()).size());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        substituteService.replaceForSeasoningItem(
                factoryId, recipeId, saved.getId(),
                request.getSubstitutes() == null ? List.of() : request.getSubstitutes());
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse updateBinding(String factoryId, String recipeId, Long bindingId,
                                                           SeasoningBindingUpdateRequest request) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBinding(recipeId, bindingId);
        validateWorkflow(recipe, factoryId, binding.getWorkProcessId(), binding.getWorkflowProcessNodeId());
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                        recipeId, binding.getWorkflowProcessNodeId(), material.getId())
                .filter(existing -> !existing.getId().equals(bindingId))
                .ifPresent(existing -> { throw duplicate(existing); });
        claimRevision(recipe, factoryId, request.getExpectedRevision());
        binding.setMaterialTypeId(material.getId());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        if (request.getSubstitutes() != null) {
            substituteService.replaceForSeasoningItem(
                    factoryId, recipeId, saved.getId(), request.getSubstitutes());
        }
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse deleteBinding(String factoryId, String recipeId, Long bindingId,
                                                           Long expectedRevision) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBinding(recipeId, bindingId);
        claimRevision(recipe, factoryId, expectedRevision);
        substituteService.replaceForSeasoningItem(factoryId, recipeId, bindingId, List.of());
        binding.softDelete();
        seasoningItemRepository.save(binding);
        return new SeasoningBindingMutationResponse(expectedRevision + 1, null);
    }

    private void apply(BomSeasoningItem binding, RawMaterialType material, BigDecimal dosage,
                       BigDecimal ratio, Boolean countInSeasoning, String remark) {
        binding.setName(material.getName());
        binding.setDosagePerKgG(dosage);
        binding.setSubsequentPotRatio(ratio);
        binding.setPriceSource1(material.getMovingAvgPrice());
        binding.setPriceSource2(null);
        binding.setCountInSeasoning(countInSeasoning != null ? countInSeasoning : Boolean.TRUE);
        binding.setRemark(remark);
    }

    private BomRecipe loadRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new EntityNotFoundException("BomRecipe 不存在: id=" + recipeId));
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw new IllegalArgumentException("配方不属于该工厂");
        }
        return recipe;
    }

    private BomRecipe editableRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new BusinessException(409, "只有 DRAFT BOM 可修改调料")
                    .withCode("SEASONING_READ_ONLY")
                    .withHint("请先克隆为新的 DRAFT 版本");
        }
        if (recipe.getWorkflowRevisionHash() == null) {
            throw new BusinessException(409, "请先为 BOM 草稿选择已保存的 Workflow 修订")
                    .withCode("BOM_WORKFLOW_REVISION_REQUIRED")
                    .withHint("保存结构完整的 Workflow 草稿后，在 BOM 中选择对应修订");
        }
        return recipe;
    }

    private BomSeasoningItem loadBinding(String recipeId, Long bindingId) {
        return seasoningItemRepository.findByIdAndRecipeId(bindingId, recipeId)
                .orElseThrow(() -> new EntityNotFoundException("调料绑定不存在: id=" + bindingId));
    }

    private ResolvedProcess validateWorkflow(BomRecipe recipe, String factoryId, String workProcessId,
                                             String workflowProcessNodeId) {
        if (workflowProcessNodeId == null || workflowProcessNodeId.isBlank()) {
            throw new BusinessException(400, "新增工序辅料必须指定 Workflow 工序节点")
                    .withCode("SEASONING_WORKFLOW_NODE_REQUIRED");
        }
        ResolvedProcess matched = resolveProcesses(factoryId, recipe).stream()
                .filter(process -> workflowProcessNodeId.equals(process.processNodeId()))
                .filter(process -> workProcessId != null && workProcessId.equals(process.workProcessId()))
                .findFirst().orElse(null);
        if (matched == null) {
            throw new BusinessException(400, "所选工序不是该 SKU 的有效工序")
                    .withHint("请从当前 SKU 的 workflow 中选择工序");
        }
        return matched;
    }

    private List<ResolvedProcess> resolveProcesses(String factoryId, BomRecipe recipe) {
        PinnedWorkflowGraph pinnedGraph = recipe.getWorkflowRevisionHash() == null
                ? null
                : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
        return resolveProcesses(factoryId, recipe, pinnedGraph);
    }

    private List<ResolvedProcess> resolveProcesses(
            String factoryId, BomRecipe recipe, PinnedWorkflowGraph pinnedGraph) {
        if (pinnedGraph != null || recipe.getStatus() == BomRecipe.Status.DRAFT) {
            PinnedWorkflowGraph graph = pinnedGraph != null
                    ? pinnedGraph
                    : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
            return graph.processes().stream()
                    .map(step -> resolvedProcess(step, graph))
                    .toList();
        }
        Optional<WorkflowProcessPath> activePath = workflowResolutionService
                .resolveProcessPath(factoryId, recipe.getProductTypeId());
        if (activePath.isPresent()) {
            return activePath.get().processes().stream()
                    .map(step -> new ResolvedProcess(
                            step.processNodeId(), step.workProcessId(), step.order(),
                            null, null, false))
                    .toList();
        }
        return productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, recipe.getProductTypeId())
                .stream()
                .map(process -> new ResolvedProcess(
                        "legacy:" + process.getId(), process.getWorkProcessId(), process.getProcessOrder(),
                        null, null, false))
            .toList();
    }

    private ResolvedProcess resolvedProcess(
            PinnedWorkflowGraph.ProcessStep step, PinnedWorkflowGraph graph) {
        StandardBasis basis = standardBasis(graph, step.processNodeId());
        return new ResolvedProcess(
                step.processNodeId(), step.workProcessId(), step.order(),
                basis.quantity(), basis.unit(), basis.supported());
    }

    private StandardBasis standardBasis(PinnedWorkflowGraph graph, String processNodeId) {
        ProductProcessWorkflowDTO.Node node = graph.nodes().stream()
                .filter(candidate -> Objects.equals(processNodeId, candidate.getId()))
                .findFirst().orElse(null);
        if (node == null || node.getData() == null) return StandardBasis.unsupported();

        Set<String> outputUnits = new java.util.LinkedHashSet<>();
        Object rawPorts = node.getData().get("ports");
        if (rawPorts instanceof List<?> ports) {
            for (Object rawPort : ports) {
                if (!(rawPort instanceof Map<?, ?> port)
                        || !"OUTPUT".equalsIgnoreCase(Objects.toString(port.get("direction"), ""))) {
                    continue;
                }
                String unit = canonicalWorkflowUnit(Objects.toString(port.get("unit"), null));
                if (unit != null) outputUnits.add(unit);
            }
        }
        if (outputUnits.isEmpty()) {
            String unit = canonicalWorkflowUnit(Objects.toString(node.getData().get("outputUnit"), null));
            if (unit != null) outputUnits.add(unit);
        }
        if (outputUnits.size() != 1) return StandardBasis.unsupported();
        String unit = outputUnits.iterator().next();
        if ("kg".equals(unit)) return new StandardBasis(BigDecimal.ONE, "kg", true);
        if ("g".equals(unit)) return new StandardBasis(new BigDecimal("1000"), "g", true);
        return new StandardBasis(BigDecimal.ONE, unit, false);
    }

    private String canonicalWorkflowUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) return null;
        return switch (rawUnit.trim().toLowerCase(Locale.ROOT)) {
            case "公斤", "千克", "kg" -> "kg";
            case "克", "g" -> "g";
            case "盒", "box" -> "box";
            case "箱", "case" -> "case";
            case "片", "slice" -> "slice";
            case "毫升", "ml" -> "ml";
            case "升", "l" -> "l";
            default -> rawUnit.trim();
        };
    }

    private void populatePinnedRevisionSummary(
            BomSeasoningWorkspaceResponse response,
            String factoryId,
            BomRecipe recipe,
            PinnedWorkflowGraph graph) {
        if (graph == null) return;
        response.setWorkflowRevisionId(graph.workflowRevisionId());
        response.setWorkflowId(graph.workflowId());
        response.setWorkflowDefinitionVersion(graph.definitionVersion());
        response.setWorkflowRevisionHash(graph.revisionHash());
        response.setWorkflowRootCount(graph.rootMaterialTypeIds().size());
        response.setWorkflowProcessCount(graph.processes().size());
        response.setWorkflowTargetCount(1);
        response.setWorkflowTargetProductTypeId(graph.targetProductTypeId());

        bomWorkflowRevisionService.listCompatible(factoryId, recipe.getId()).stream()
                .filter(candidate -> Objects.equals(candidate.getRevisionId(), graph.workflowRevisionId())
                        || (Objects.equals(candidate.getWorkflowId(), graph.workflowId())
                        && Objects.equals(candidate.getRevisionHash(), graph.revisionHash())))
                .findFirst()
                .ifPresent(candidate -> {
                    response.setWorkflowRevisionStatus(displayRevisionStatus(candidate));
                    response.setWorkflowRevisionSavedAt(candidate.getSavedAt());
                });
    }

    private String displayRevisionStatus(WorkflowRevisionCandidateDTO candidate) {
        if (candidate.isEnabled()) return "ENABLED";
        return candidate.getStatus();
    }

    private record ResolvedProcess(
            String processNodeId,
            String workProcessId,
            Integer processOrder,
            BigDecimal standardBasisQuantity,
            String standardBasisUnit,
            boolean standardUsageSupported) { }

    private record StandardBasis(BigDecimal quantity, String unit, boolean supported) {
        private static StandardBasis unsupported() {
            return new StandardBasis(null, null, false);
        }
    }

    private RawMaterialType validateMaterial(String factoryId, String materialTypeId) {
        RawMaterialType material = materialTypeRepository.findById(materialTypeId)
                .orElseThrow(() -> new BusinessException(400, "调料物料不存在: " + materialTypeId));
        if (!factoryId.equals(material.getFactoryId())) {
            throw new BusinessException(400, "调料物料不属于当前工厂");
        }
        if (!Boolean.TRUE.equals(material.getIsActive())) {
            throw new BusinessException(400, "调料物料已停用: " + material.getName());
        }
        String category = material.getCategory() == null ? "" : material.getCategory().trim();
        boolean auxiliary = AUXILIARY_CATEGORIES.contains(category)
                || AUXILIARY_CATEGORIES.contains(category.toUpperCase(Locale.ROOT))
                || "003".equals(material.getPrimaryCode());
        if (!auxiliary) {
            throw new BusinessException(400, "只能选择辅料或调料物料: " + material.getName());
        }
        if (material.getMovingAvgPrice() == null) {
            throw new BusinessException(400, "调料物料缺少移动平均价: " + material.getName())
                    .withHint("请先维护采购价格");
        }
        return material;
    }

    private void validateValues(BigDecimal dosage, BigDecimal ratio) {
        if (dosage == null || dosage.signum() <= 0) {
            throw new BusinessException(400, "每 kg 调料用量必须大于 0");
        }
        if (ratio != null && (ratio.compareTo(BigDecimal.ZERO) < 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(400, "后续锅比例必须在 0 到 1 之间");
        }
    }

    private void claimRevision(BomRecipe recipe, String factoryId, Long expectedRevision) {
        if (expectedRevision == null || recipeRepository.claimSeasoningRevision(
                recipe.getId(), factoryId, expectedRevision) != 1) {
            throw new BusinessException(409, "调料配置已被其他人修改")
                    .withCode("SEASONING_REVISION_CONFLICT")
                    .withHint("请重新加载最新配置后再保存");
        }
    }

    private BusinessException duplicate(BomSeasoningItem existing) {
        return new BusinessException(409, "该调料已在本工序配置，bindingId=" + existing.getId())
                .withCode("SEASONING_BINDING_DUPLICATE")
                .withHint("请定位并编辑现有调料行");
    }

    private void collectAnomalies(BomSeasoningWorkspaceResponse response, BomSeasoningItem binding,
                                  Map<String, String> processNames, Set<String> validWorkProcessIds,
                                  Map<String, Long> masterOccurrences,
                                  Map<String, RawMaterialType> materials) {
        if (binding.getWorkflowProcessNodeId() != null
                && !processNames.containsKey(binding.getWorkflowProcessNodeId())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_PROCESS", "调料尚未绑定当前 workflow 的有效工序", binding.getId()));
        } else if (binding.getWorkflowProcessNodeId() == null
                && (binding.getWorkProcessId() == null || !validWorkProcessIds.contains(binding.getWorkProcessId()))) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_PROCESS", "历史调料绑定的工序不在当前 workflow 中", binding.getId()));
        } else if (binding.getWorkflowProcessNodeId() == null
                && masterOccurrences.getOrDefault(binding.getWorkProcessId(), 0L) > 1L) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "AMBIGUOUS_PROCESS_NODE", "历史调料仅保存工序主数据，无法区分重复工序节点", binding.getId()));
        }
        if (binding.getMaterialTypeId() == null) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_MATERIAL_LINK", "历史调料需要重新选择物料", binding.getId()));
            return;
        }
        RawMaterialType material = materials.get(binding.getMaterialTypeId());
        if (material == null || !Boolean.TRUE.equals(material.getIsActive())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "INVALID_MATERIAL", "调料物料不存在或已停用", binding.getId()));
        } else if (material.getMovingAvgPrice() == null) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_PRICE", "调料物料缺少移动平均价", binding.getId()));
        }
    }
}
