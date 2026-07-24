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
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
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
    private final ProductProcessWorkflowRevisionRepository workflowRevisionRepository;
    private final BomRecipeService bomRecipeService;

    @Override
    @Transactional(readOnly = true)
    public BomSeasoningWorkspaceResponse getWorkspace(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        PinnedWorkflowGraph pinnedGraph = recipe.getWorkflowRevisionHash() == null
                ? null
                : bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
        List<ResolvedProcess> workflow = resolveProcesses(factoryId, recipe, pinnedGraph);
        String bindingRecipeId = recipe.getSharedRecipeId() == null
                ? recipeId : recipe.getSharedRecipeId();
        List<BomSeasoningItem> sharedOwnerBindings =
                seasoningItemRepository.findByRecipeIdOrderBySeqAsc(bindingRecipeId);
        List<BomSeasoningItem> bindings = new ArrayList<>(sharedOwnerBindings.stream()
                .filter(binding -> !"OUTPUT_EXCLUSIVE".equals(binding.getCostScope()))
                .toList());
        if (bindingRecipeId.equals(recipeId)) {
            bindings.addAll(sharedOwnerBindings.stream()
                    .filter(binding -> "OUTPUT_EXCLUSIVE".equals(binding.getCostScope()))
                    .toList());
        } else {
            bindings.addAll(seasoningItemRepository.findByRecipeIdOrderBySeqAsc(recipeId).stream()
                    .filter(binding -> "OUTPUT_EXCLUSIVE".equals(binding.getCostScope()))
                    .toList());
        }
        Map<String, String> processCostScopes = pinnedGraph == null
                ? Map.of()
                : bomWorkflowRevisionService.resolveProcessCostScopes(factoryId, recipe);

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
        populatePinnedRevisionSummary(response, factoryId, recipe, bindingRecipeId, pinnedGraph);

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
                    configured.standardBasisMaterialKind(),
                    configured.standardUsageSupported(),
                    processCostScopes.getOrDefault(configured.processNodeId(), "SHARED"),
                    recipe.getStatus() == BomRecipe.Status.DRAFT
                            && (bindingRecipeId.equals(recipeId)
                            || "OUTPUT_EXCLUSIVE".equals(processCostScopes.get(configured.processNodeId()))),
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
                    effectivePrice(first),
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
        BindingTarget target = resolveBindingTarget(factoryId, recipe, process.processNodeId());
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                target.recipe().getId(), process.processNodeId(), material.getId()).ifPresent(existing -> {
            throw duplicate(existing);
        });
        claimRevision(target.recipe(), factoryId, request.getExpectedRevision());

        BomSeasoningItem binding = new BomSeasoningItem();
        binding.setRecipeId(target.recipe().getId());
        binding.setFactoryId(factoryId);
        binding.setWorkProcessId(workProcessId);
        binding.setWorkflowProcessNodeId(process.processNodeId());
        binding.setCostScope(target.costScope());
        binding.setMaterialTypeId(material.getId());
        binding.setSection("COOKING");
        binding.setSeq(seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(
                target.recipe().getId(), process.processNodeId()).size());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        substituteService.replaceForSeasoningItem(
                factoryId, target.recipe().getId(), saved.getId(),
                request.getSubstitutes() == null ? List.of() : request.getSubstitutes());
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse updateBinding(String factoryId, String recipeId, Long bindingId,
                                                           SeasoningBindingUpdateRequest request) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBindingForWorkspace(recipe, bindingId);
        ResolvedProcess process = validateWorkflow(
                recipe, factoryId, binding.getWorkProcessId(), binding.getWorkflowProcessNodeId());
        BindingTarget target = resolveBindingTarget(factoryId, recipe, process.processNodeId());
        assertBindingTarget(binding, target);
        RawMaterialType material = validateMaterial(factoryId, request.getMaterialTypeId());
        validateValues(request.getDosagePerKgG(), request.getSubsequentPotRatio());
        seasoningItemRepository.findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
                        target.recipe().getId(), binding.getWorkflowProcessNodeId(), material.getId())
                .filter(existing -> !existing.getId().equals(bindingId))
                .ifPresent(existing -> { throw duplicate(existing); });
        claimRevision(target.recipe(), factoryId, request.getExpectedRevision());
        binding.setMaterialTypeId(material.getId());
        binding.setCostScope(target.costScope());
        apply(binding, material, request.getDosagePerKgG(), request.getSubsequentPotRatio(),
                request.getCountInSeasoning(), request.getRemark());
        BomSeasoningItem saved = seasoningItemRepository.save(binding);
        if (request.getSubstitutes() != null) {
            substituteService.replaceForSeasoningItem(
                    factoryId, target.recipe().getId(), saved.getId(), request.getSubstitutes());
        }
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(request.getExpectedRevision() + 1, saved);
    }

    @Override
    @Transactional
    public SeasoningBindingMutationResponse deleteBinding(String factoryId, String recipeId, Long bindingId,
                                                           Long expectedRevision) {
        BomRecipe recipe = editableRecipe(factoryId, recipeId);
        BomSeasoningItem binding = loadBindingForWorkspace(recipe, bindingId);
        BindingTarget target = resolveBindingTarget(
                factoryId, recipe, binding.getWorkflowProcessNodeId());
        assertBindingTarget(binding, target);
        claimRevision(target.recipe(), factoryId, expectedRevision);
        substituteService.replaceForSeasoningItem(
                factoryId, target.recipe().getId(), bindingId, List.of());
        binding.softDelete();
        seasoningItemRepository.save(binding);
        bomRecipeService.calculateCost(factoryId, target.recipe().getId());
        return new SeasoningBindingMutationResponse(expectedRevision + 1, null);
    }

    private void apply(BomSeasoningItem binding, RawMaterialType material, BigDecimal dosage,
                       BigDecimal ratio, Boolean countInSeasoning, String remark) {
        PriceSnapshot price = resolvePrice(material);
        binding.setName(material.getName());
        binding.setDosagePerKgG(dosage);
        binding.setSubsequentPotRatio(ratio);
        binding.setPriceSource1(price.movingAveragePrice());
        binding.setPriceSource2(price.purchaseReferencePrice());
        binding.setCountInSeasoning(countInSeasoning != null ? countInSeasoning : Boolean.TRUE);
        binding.setRemark(remark);
    }

    private BigDecimal effectivePrice(BomSeasoningItem binding) {
        return positive(binding.getPriceSource1())
                ? binding.getPriceSource1()
                : positive(binding.getPriceSource2()) ? binding.getPriceSource2() : null;
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
            throw new BusinessException(409, "BOM 草稿尚未自动关联完整工艺")
                    .withCode("BOM_WORKFLOW_REVISION_REQUIRED")
                    .withHint("请先保存唯一且结构完整的 Workflow 草稿，再重新创建 BOM 草稿");
        }
        return recipe;
    }

    private BomSeasoningItem loadBinding(String recipeId, Long bindingId) {
        return seasoningItemRepository.findByIdAndRecipeId(bindingId, recipeId)
                .orElseThrow(() -> new EntityNotFoundException("调料绑定不存在: id=" + bindingId));
    }

    private BomSeasoningItem loadBindingForWorkspace(BomRecipe recipe, Long bindingId) {
        String sharedRecipeId = recipe.getSharedRecipeId() == null
                ? recipe.getId() : recipe.getSharedRecipeId();
        return seasoningItemRepository.findByIdAndRecipeId(bindingId, recipe.getId())
                .or(() -> recipe.getId().equals(sharedRecipeId)
                        ? Optional.empty()
                        : seasoningItemRepository.findByIdAndRecipeId(bindingId, sharedRecipeId))
                .orElseThrow(() ->
                        new EntityNotFoundException("调料绑定不属于当前 BOM 工作区: id=" + bindingId));
    }

    private BindingTarget resolveBindingTarget(
            String factoryId, BomRecipe recipe, String workflowProcessNodeId) {
        String scope = bomWorkflowRevisionService.resolveProcessCostScopes(factoryId, recipe)
                .get(workflowProcessNodeId);
        if (scope == null) {
            throw new BusinessException(409, "工序不属于当前 BOM 固定的目标产出路径")
                    .withCode("SEASONING_WORKFLOW_NODE_OUTSIDE_TARGET");
        }
        if (!"SHARED".equals(scope)) {
            return new BindingTarget(recipe, "OUTPUT_EXCLUSIVE");
        }
        String sharedRecipeId = recipe.getSharedRecipeId() == null
                ? recipe.getId() : recipe.getSharedRecipeId();
        if (!recipe.getId().equals(sharedRecipeId)) {
            throw new BusinessException(409, "该工序由多个产出共享，请在主产出 BOM 中修改")
                    .withCode("SEASONING_SHARED_PROCESS_READ_ONLY")
                    .withHint("切换到 BOM Family 的主产出，修改后会同步用于所有联产品");
        }
        return new BindingTarget(recipe, "SHARED");
    }

    private void assertBindingTarget(BomSeasoningItem binding, BindingTarget target) {
        boolean legacySharedBinding = binding.getCostScope() == null
                && "SHARED".equals(target.costScope());
        if (!target.recipe().getId().equals(binding.getRecipeId())
                || (!legacySharedBinding && !target.costScope().equals(binding.getCostScope()))) {
            throw new BusinessException(409, "调料绑定与当前工序的共享范围不一致")
                    .withCode("SEASONING_COST_SCOPE_CONFLICT")
                    .withHint("请刷新 BOM 工作区后重试；历史绑定不会被静默移动");
        }
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
        if (!matched.standardUsageSupported()) {
            throw new BusinessException(400, "当前 Workflow 工序的产出单位缺失、冲突或不支持，无法保存工序辅料")
                    .withCode("SEASONING_STANDARD_BASIS_UNSUPPORTED")
                    .withHint("请回到 Workflow 检查该工序的产出端口及半成品单位，保存修订后再配置辅料");
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
                            null, null, null, false))
                    .toList();
        }
        return productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, recipe.getProductTypeId())
                .stream()
                .map(process -> new ResolvedProcess(
                        "legacy:" + process.getId(), process.getWorkProcessId(), process.getProcessOrder(),
                        null, null, null, false))
            .toList();
    }

    private ResolvedProcess resolvedProcess(
            PinnedWorkflowGraph.ProcessStep step, PinnedWorkflowGraph graph) {
        StandardBasis basis = standardBasis(graph, step.processNodeId());
        return new ResolvedProcess(
                step.processNodeId(), step.workProcessId(), step.order(),
                basis.quantity(), basis.unit(), basis.materialKind(), basis.supported());
    }

    private StandardBasis standardBasis(PinnedWorkflowGraph graph, String processNodeId) {
        ProductProcessWorkflowDTO.Node node = graph.nodes().stream()
                .filter(candidate -> Objects.equals(processNodeId, candidate.getId()))
                .findFirst().orElse(null);
        if (node == null || node.getData() == null) return StandardBasis.unsupported();

        Set<String> outputUnits = new java.util.LinkedHashSet<>();
        Set<String> outputKinds = new java.util.LinkedHashSet<>();
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = graph.nodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left));
        Object rawPorts = node.getData().get("ports");
        if (rawPorts instanceof List<?> ports) {
            for (Object rawPort : ports) {
                if (!(rawPort instanceof Map<?, ?> port)
                        || !"OUTPUT".equalsIgnoreCase(Objects.toString(port.get("direction"), ""))) {
                    continue;
                }
                addCanonicalUnit(outputUnits, port.get("unit"));
                addCanonicalKind(outputKinds, port.get("materialKind"));
                collectMaterialNodeContract(
                        nodesById.get(Objects.toString(port.get("materialNodeId"), null)),
                        outputUnits, outputKinds);
            }
        }
        addCanonicalUnit(outputUnits, node.getData().get("outputUnit"));
        addCanonicalKind(outputKinds, node.getData().get("outputMaterialKind"));
        for (ProductProcessWorkflowDTO.Edge edge : graph.edges()) {
            if (Objects.equals(processNodeId, edge.getSource())) {
                collectMaterialNodeContract(nodesById.get(edge.getTarget()), outputUnits, outputKinds);
            }
        }
        if (outputUnits.size() != 1) return StandardBasis.unsupported();
        String unit = outputUnits.iterator().next();
        String materialKind = outputKinds.size() == 1 ? outputKinds.iterator().next() : null;
        if (outputKinds.size() > 1) return StandardBasis.unsupported();
        if ("kg".equals(unit)) return new StandardBasis(BigDecimal.ONE, "kg", materialKind, true);
        if ("g".equals(unit)) return new StandardBasis(new BigDecimal("1000"), "g", materialKind, true);
        return new StandardBasis(BigDecimal.ONE, unit, materialKind, false);
    }

    private void collectMaterialNodeContract(ProductProcessWorkflowDTO.Node materialNode,
                                             Set<String> outputUnits,
                                             Set<String> outputKinds) {
        if (materialNode == null || materialNode.getData() == null) return;
        addCanonicalUnit(outputUnits, materialNode.getData().get("baseUnit"));
        addCanonicalKind(outputKinds, materialNode.getData().get("materialKind"));
        addCanonicalKind(outputKinds, materialNode.getKind());
    }

    private void addCanonicalUnit(Set<String> units, Object rawUnit) {
        String unit = canonicalWorkflowUnit(Objects.toString(rawUnit, null));
        if (unit != null) units.add(unit);
    }

    private void addCanonicalKind(Set<String> kinds, Object rawKind) {
        String kind = canonicalMaterialKind(Objects.toString(rawKind, null));
        if (kind != null) kinds.add(kind);
    }

    private String canonicalMaterialKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) return null;
        return switch (rawKind.trim().toUpperCase(Locale.ROOT)) {
            case "SEMI_FINISHED", "SEMI_FINISHED_PRODUCT" -> "SEMI_FINISHED";
            case "FINISHED", "FINISHED_GOOD", "FINISHED_PRODUCT", "PRODUCT" -> "FINISHED_GOOD";
            case "MATERIAL", "CELL", "RAW_MATERIAL", "PROCESS" -> null;
            default -> rawKind.trim().toUpperCase(Locale.ROOT);
        };
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
            String bindingRecipeId,
            PinnedWorkflowGraph graph) {
        if (graph == null) return;
        response.setWorkflowRevisionId(graph.workflowRevisionId());
        response.setWorkflowId(graph.workflowId());
        workflowRevisionRepository.findByIdAndFactoryId(graph.workflowRevisionId(), factoryId)
                .ifPresent(revision ->
                        response.setWorkflowOwnerProductTypeId(revision.getProductTypeId()));
        response.setWorkflowDefinitionVersion(graph.definitionVersion());
        response.setWorkflowRevisionHash(graph.revisionHash());
        response.setWorkflowRootCount(graph.rootMaterialTypeIds().size());
        response.setWorkflowProcessCount(graph.processes().size());
        response.setWorkflowTargetCount(
                bomWorkflowRevisionService.resolvePinnedTerminalOutputs(factoryId, recipe).size());
        response.setWorkflowTargetProductTypeId(graph.targetProductTypeId());
        response.setBomFamilyId(recipe.getBomFamilyId());
        response.setSharedRecipeId(bindingRecipeId);
        response.setSharedRulesOwner(bindingRecipeId.equals(recipe.getId()));
        response.setOutputRole(recipe.getOutputRole() == null ? null : recipe.getOutputRole().name());
        response.setCostAllocationRatio(recipe.getCostAllocationRatio());
        bomWorkflowRevisionService.findNewerCompatibleDraft(factoryId, recipe).ifPresent(revision -> {
            response.setWorkflowUpgradeAvailable(true);
            response.setWorkflowUpgradeRevisionId(revision.getId());
            response.setWorkflowUpgradeDefinitionVersion(revision.getDefinitionVersion());
        });

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
            String standardBasisMaterialKind,
            boolean standardUsageSupported) { }

    private record StandardBasis(BigDecimal quantity, String unit, String materialKind, boolean supported) {
        private static StandardBasis unsupported() {
            return new StandardBasis(null, null, null, false);
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
        resolvePrice(material);
        return material;
    }

    private PriceSnapshot resolvePrice(RawMaterialType material) {
        if (positive(material.getMovingAvgPrice())) {
            return new PriceSnapshot(material.getMovingAvgPrice(), null);
        }
        if (positive(material.getTaxIncludedUnitPrice())) {
            return new PriceSnapshot(null, material.getTaxIncludedUnitPrice());
        }
        throw new BusinessException(400, "调料物料缺少有效成本价格: " + material.getName())
                .withCode("SEASONING_PRICE_REQUIRED")
                .withHint("请维护正数移动平均价或含税采购参考价");
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record PriceSnapshot(BigDecimal movingAveragePrice, BigDecimal purchaseReferencePrice) { }

    private record BindingTarget(BomRecipe recipe, String costScope) { }

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
        } else if (!positive(material.getMovingAvgPrice())
                && !positive(material.getTaxIncludedUnitPrice())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_PRICE", "调料物料缺少有效移动平均价或采购参考价", binding.getId()));
        }
    }
}
