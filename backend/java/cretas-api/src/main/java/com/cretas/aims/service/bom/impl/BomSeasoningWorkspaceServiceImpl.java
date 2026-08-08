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
import java.util.Comparator;
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
        List<BomRecipe> family = familyForStatus(recipe);
        String bindingRecipeId = recipe.getSharedRecipeId() == null
                ? recipeId : recipe.getSharedRecipeId();
        Map<String, BomRecipe> owners = family.stream().collect(Collectors.toMap(
                BomRecipe::getId, Function.identity()));
        List<BomSeasoningItem> bindings = family.stream()
                .flatMap(member -> seasoningItemRepository
                        .findByRecipeIdOrderBySeqAsc(member.getId()).stream())
                .filter(binding -> bindingAppliesTo(
                        binding,
                        owners.get(binding.getRecipeId()),
                        recipe,
                        family))
                .toList();
        Map<String, BomWorkflowRevisionService.CostScopeProfile> processCostProfiles =
                pinnedGraph == null
                ? Map.of()
                : bomWorkflowRevisionService.resolveProcessCostProfiles(factoryId, recipe);

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
            BomWorkflowRevisionService.CostScopeProfile costProfile =
                    processCostProfiles.get(configured.processNodeId());
            String costScope = costProfile == null ? "SHARED" : costProfile.costScope();
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
                    costScope,
                    costProfile == null ? List.of(recipe.getProductTypeId())
                            : costProfile.productTypeIds(),
                    recipe.getStatus() == BomRecipe.Status.DRAFT,
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
        binding.setCostScopeKey(target.costScopeKey());
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
        binding.setCostScopeKey(target.costScopeKey());
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
        List<BomRecipe> family = familyForStatus(recipe);
        if (family.size() == 1) {
            return loadBinding(recipe.getId(), bindingId);
        }
        Map<String, BomRecipe> owners = family.stream().collect(Collectors.toMap(
                BomRecipe::getId, Function.identity()));
        return seasoningItemRepository.findById(bindingId)
                .filter(binding -> owners.containsKey(binding.getRecipeId()))
                .filter(binding -> bindingAppliesTo(
                        binding,
                        owners.get(binding.getRecipeId()),
                        recipe,
                        family))
                .orElseThrow(() ->
                        new EntityNotFoundException("调料绑定不属于当前 BOM 工作区: id=" + bindingId));
    }

    private BindingTarget resolveBindingTarget(
            String factoryId, BomRecipe recipe, String workflowProcessNodeId) {
        BomWorkflowRevisionService.CostScopeProfile profile =
                bomWorkflowRevisionService.resolveProcessCostProfiles(factoryId, recipe)
                .get(workflowProcessNodeId);
        if (profile == null) {
            throw new BusinessException(409, "工序不属于当前 BOM 固定的目标产出路径")
                    .withCode("SEASONING_WORKFLOW_NODE_OUTSIDE_TARGET");
        }
        List<BomRecipe> family = familyForStatus(recipe);
        List<BomRecipe> targetMembers = family.stream()
                .filter(member -> profile.productTypeIds().contains(member.getProductTypeId()))
                .sorted(Comparator.comparing(BomRecipe::getTargetTerminalNodeId))
                .toList();
        if (targetMembers.size() != profile.productTypeIds().size()) {
            throw new BusinessException(409, "工序成本目标与当前 BOM Family 不完整")
                    .withCode("SEASONING_COST_SCOPE_TARGET_INCOMPLETE")
                    .withHint("请刷新 BOM；如工艺已有新修订，请使用“升级到最新工艺”");
        }
        BomRecipe owner;
        if (!"SHARED".equals(profile.costScope())) {
            owner = targetMembers.get(0);
        } else if (family.size() == 1) {
            // Legacy single-output BOMs predate outputRole. Their only recipe is
            // deterministically the shared-cost owner and must remain editable.
            owner = family.get(0);
        } else {
            owner = family.stream()
                    .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.MAIN)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(409, "BOM Family 缺少主产出")
                            .withCode("BOM_FAMILY_MAIN_REQUIRED"));
        }
        return new BindingTarget(owner, profile.costScope(), profile.costScopeKey());
    }

    private void assertBindingTarget(BomSeasoningItem binding, BindingTarget target) {
        boolean legacySharedBinding = binding.getCostScope() == null
                && "SHARED".equals(target.costScope());
        boolean legacyScopeKey = binding.getCostScopeKey() == null
                && !"OUTPUT_GROUP".equals(target.costScope());
        if (!target.recipe().getId().equals(binding.getRecipeId())
                || (!legacySharedBinding && !target.costScope().equals(binding.getCostScope()))
                || (!legacyScopeKey && !Objects.equals(
                        target.costScopeKey(), binding.getCostScopeKey()))) {
            throw new BusinessException(409, "调料绑定与当前工序的共享范围不一致")
                    .withCode("SEASONING_COST_SCOPE_CONFLICT")
                    .withHint("请刷新 BOM 工作区后重试；历史绑定不会被静默移动");
        }
    }

    private List<BomRecipe> familyForStatus(BomRecipe reference) {
        if (reference.getBomFamilyId() == null) return List.of(reference);
        return recipeRepository
                .findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                        reference.getFactoryId(),
                        reference.getBomFamilyId(),
                        reference.getStatus()).stream()
                .filter(member -> Objects.equals(
                        reference.getWorkflowRevisionId(), member.getWorkflowRevisionId()))
                .toList();
    }

    private boolean bindingAppliesTo(
            BomSeasoningItem binding,
            BomRecipe owner,
            BomRecipe target,
            List<BomRecipe> family) {
        if (owner == null) return false;
        if (binding.getCostScopeKey() != null && !binding.getCostScopeKey().isBlank()) {
            Set<String> terminalIds = java.util.Arrays.stream(
                            binding.getCostScopeKey().split(","))
                    .collect(Collectors.toSet());
            return terminalIds.contains(target.getTargetTerminalNodeId());
        }
        if ("OUTPUT_GROUP".equals(binding.getCostScope())) return false;
        if ("OUTPUT_EXCLUSIVE".equals(binding.getCostScope())) {
            return owner.getId().equals(target.getId());
        }
        return family.stream().anyMatch(member -> member.getId().equals(target.getId()));
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

        Set<String> portUnits = new java.util.LinkedHashSet<>();
        Set<String> portKinds = new java.util.LinkedHashSet<>();
        Set<String> processUnits = new java.util.LinkedHashSet<>();
        Set<String> processKinds = new java.util.LinkedHashSet<>();
        Set<String> fallbackUnits = new java.util.LinkedHashSet<>();
        Set<String> fallbackKinds = new java.util.LinkedHashSet<>();
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
                // 🔴 2026-08-09: 副产不参与「标准用量基准」的单位判定。
                //
                // 基准回答的是「每产出多少主/联产, 配多少辅料」。副产是这道工序捎带出来的东西
                // (肥油/边角料), 它的单位与主产出天然可以不同 —— 真机: 成品是 盒、副产肥油是 kg。
                // 把它算进来会让 outputUnits = {盒, kg} → size != 1 → 整道工序判为「缺少可用的产出基准」,
                // 后果是**这道工序的辅料从此再也改不了**(弹窗里"当前不能保存", 而按钮不禁用, 点了没反应)。
                // 即: 加一个副产 = 永久丧失辅料可配置性, 而两件事在业务上毫无关系。
                if (isExcludedFromBasis(port, nodesById)) {
                    continue;
                }
                addCanonicalUnit(portUnits, port.get("unit"));
                addCanonicalKind(portKinds, port.get("materialKind"));
                collectMaterialNodeContract(
                        nodesById.get(Objects.toString(port.get("materialNodeId"), null)),
                        fallbackUnits, fallbackKinds);
            }
        }
        addCanonicalUnit(processUnits, node.getData().get("outputUnit"));
        addCanonicalKind(processKinds, node.getData().get("outputMaterialKind"));
        for (ProductProcessWorkflowDTO.Edge edge : graph.edges()) {
            if (Objects.equals(processNodeId, edge.getSource())) {
                ProductProcessWorkflowDTO.Node target = nodesById.get(edge.getTarget());
                // 兜底路径同样要排除副产, 否则 port 未声明单位时副产又从这里混进来。
                if (isByproductNode(target)) {
                    continue;
                }
                collectMaterialNodeContract(target, fallbackUnits, fallbackKinds);
            }
        }
        // Workflow 修订中工序 OUTPUT port 是报工单位权威；相邻物料节点只在 port 未声明时兜底。
        // 例如包装工序 port=box、成品 SKU 基本单位=kg 时不能合并成“多单位未解析”。
        Set<String> outputUnits = !portUnits.isEmpty()
                ? portUnits : (!processUnits.isEmpty() ? processUnits : fallbackUnits);
        Set<String> outputKinds = !portKinds.isEmpty()
                ? portKinds : (!processKinds.isEmpty() ? processKinds : fallbackKinds);
        if (outputUnits.size() != 1) return StandardBasis.unsupported();
        String unit = outputUnits.iterator().next();
        String materialKind = outputKinds.size() == 1 ? outputKinds.iterator().next() : null;
        if (outputKinds.size() > 1) return StandardBasis.unsupported();
        if ("kg".equals(unit)) return new StandardBasis(BigDecimal.ONE, "kg", materialKind, true);
        if ("g".equals(unit)) return new StandardBasis(new BigDecimal("1000"), "g", materialKind, true);
        // The dosage basis is the authoritative SKU/Workflow output unit itself. A
        // custom unit such as 只、袋 or a tenant-defined code is an opaque "per one
        // output unit" basis and needs no dimensional conversion. We only fail closed
        // above when the pinned graph has no unit or conflicting output contracts.
        return new StandardBasis(BigDecimal.ONE, unit, materialKind, true);
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

    /**
     * 走系统权威别名表。原来是一张只有 7 组的私有 switch (无 袋/bag、无 件/个/只)，
     * 结果喂给 {@code addCanonicalUnit} 去重时，同一个单位的两种写法会被当成两种单位
     * —— 「这道工序有几种单位」就会数多。与 2026-07-31 报工/结单那两处同一个根因。
     */
    private String canonicalWorkflowUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) return null;
        return com.cretas.aims.service.unit.impl.UnitContractServiceImpl.crossLanguageCode(rawUnit);
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

    /**
     * 该 OUTPUT 端口要不要排除出「标准用量基准」的单位判定。
     *
     * <p>两种都排除:
     * <ol>
     *   <li>指向的节点**明确标了副产**;</li>
     *   <li>指向的节点<b>根本不在本切片里</b> —— 🔴 这条才是 prod 的真实形态。
     *       {@link PinnedWorkflowGraph} 是按目标产品切出来的子图(ancestors/terminals),
     *       兄弟终端(副产、联产)通常**不在** graph.nodes() 里, 于是「按 materialNodeId 查
     *       isByproduct」永远查不到 → 判不出副产 → 它的 kg 照样混进 outputUnits → size!=1 →
     *       整道工序「标准用量不可用」, 辅料从此改不了。
     *       我的第一版修复只判了第 1 条, 单测(节点在图里)绿、真机(节点不在图里)照挂 ——
     *       判据不能依赖「节点恰好存在」。</li>
     * </ol>
     *
     * <p>语义上也站得住: 基准回答的是「本配方的目标产出每一单位配多少辅料」, 不在本目标路径上的
     * 产出(副产/兄弟终端)本就不该参与这个分母。
     */
    private boolean isExcludedFromBasis(
            Map<?, ?> port, Map<String, ProductProcessWorkflowDTO.Node> nodesById) {
        String materialNodeId = Objects.toString(port.get("materialNodeId"), null);
        if (materialNodeId == null) {
            return false;   // 没声明目标节点 → 按老路径参与判定, 不改变既有行为
        }
        ProductProcessWorkflowDTO.Node target = nodesById.get(materialNodeId);
        return target == null || isByproductNode(target);
    }

    /**
     * 画布把副产建成「普通产出节点 + isByproduct 标记」(刻意不设 kind:'BYPRODUCT',
     * 见 MaterialNodeData.isByproduct: 「副产只看物料上的 isByproduct 标记, 与材质正交」),
     * 所以这里只认这个标记, 不按 kind 判。
     */
    private boolean isByproductNode(ProductProcessWorkflowDTO.Node node) {
        if (node == null || node.getData() == null) return false;
        Object flag = node.getData().get("isByproduct");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(Objects.toString(flag, ""));
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
        if (positive(material.getUnitPrice())) {
            return new PriceSnapshot(null, material.getUnitPrice());
        }
        throw new BusinessException(400, "调料物料缺少有效成本价格: " + material.getName())
                .withCode("SEASONING_PRICE_REQUIRED")
                .withHint("请维护正数移动平均库存成本或未税采购参考价");
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record PriceSnapshot(BigDecimal movingAveragePrice, BigDecimal purchaseReferencePrice) { }

    private record BindingTarget(BomRecipe recipe, String costScope, String costScopeKey) { }

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
                && !positive(material.getUnitPrice())) {
            response.getAnomalies().add(new BomSeasoningWorkspaceResponse.Anomaly(
                    "MISSING_PRICE", "调料物料缺少有效移动平均库存成本或未税采购参考价", binding.getId()));
        }
    }
}
