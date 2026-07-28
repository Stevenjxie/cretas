package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.BomCopyToDraftRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BomCopyServiceImpl implements BomCopyService {

    private static final int MAX_VERSIONS_PER_PRODUCT = 10;
    private final BomRecipeRepository recipeRepo;
    private final BomRecipeItemRepository itemRepo;
    private final BomSeasoningItemRepository seasoningRepo;
    private final BomProcessInjectionConfigRepository processInjectionConfigRepo;
    private final ProductTypeRepository productTypeRepo;
    private final WorkProcessRepository workProcessRepo;
    private final ProductWorkflowResolutionService workflowResolutionService;
    private final BomRecipeService bomRecipeService;

    @Override
    @Transactional(readOnly = true)
    public List<BomCopyCandidateDTO> listCandidates(String factoryId, String targetProductTypeId) {
        ProductType target = loadProduct(factoryId, targetProductTypeId);
        WorkflowProcessPath targetPath = workflowResolutionService.resolveProcessPath(factoryId, targetProductTypeId)
                .orElse(null);
        if (targetPath == null) {
            return List.of();
        }

        List<BomRecipe> currentActiveRecipes = recipeRepo
                .findByFactoryIdAndStatus(factoryId, BomRecipe.Status.ACTIVE, Pageable.unpaged())
                .getContent().stream()
                .filter(recipe -> Boolean.TRUE.equals(recipe.getIsCurrent()))
                .filter(recipe -> !targetProductTypeId.equals(recipe.getProductTypeId()))
                .toList();
        Map<String, ProductType> products = productTypeRepo.findByIdIn(currentActiveRecipes.stream()
                        .map(BomRecipe::getProductTypeId).collect(Collectors.toSet())).stream()
                .filter(product -> factoryId.equals(product.getFactoryId()))
                .collect(Collectors.toMap(ProductType::getId, Function.identity(), (left, right) -> left));

        List<CandidateContext> contexts = new ArrayList<>();
        for (BomRecipe sourceRecipe : currentActiveRecipes) {
            ProductType sourceProduct = products.get(sourceRecipe.getProductTypeId());
            if (sourceProduct == null || !Boolean.TRUE.equals(sourceProduct.getIsActive())) {
                continue;
            }
            WorkflowProcessPath sourcePath = workflowResolutionService
                    .resolveProcessPath(factoryId, sourceRecipe.getProductTypeId()).orElse(null);
            if (sourcePath == null || !sameSource(targetPath, sourcePath)) {
                continue;
            }
            Set<String> shared = sharedProcessIds(targetPath, sourcePath);
            if (!shared.isEmpty()) {
                contexts.add(new CandidateContext(sourceProduct, sourceRecipe, sourcePath, shared));
            }
        }

        Set<String> allProcessIds = contexts.stream().flatMap(context -> context.sharedProcessIds().stream())
                .collect(Collectors.toSet());
        Map<String, String> processNames = workProcessRepo
                .findByFactoryIdAndIdIn(factoryId, new ArrayList<>(allProcessIds)).stream()
                .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (left, right) -> left));

        return contexts.stream()
                .sorted(Comparator.<CandidateContext>comparingInt(context -> context.sharedProcessIds().size())
                        .reversed().thenComparing(context -> context.sourceProduct().getName()))
                .map(context -> toCandidate(targetPath, context, processNames))
                .toList();
    }

    @Override
    @Transactional
    public BomRecipe copySelectedRulesToDraft(String factoryId, BomCopyToDraftRequest request) {
        ProductType target = productTypeRepo.findByIdAndFactoryIdForUpdate(
                        request.getTargetProductTypeId(), factoryId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "目标产品不存在: " + request.getTargetProductTypeId()));
        BomRecipe source = loadSourceRecipe(factoryId, request.getSourceRecipeId());
        if (target.getId().equals(source.getProductTypeId())) {
            throw businessError(400, "不能把产品自己的 BOM 作为同源复制来源", "BOM_COPY_SOURCE_IS_TARGET");
        }
        if (target.getGramsPerUnit() == null || target.getGramsPerUnit().compareTo(BigDecimal.ZERO) <= 0) {
            throw businessError(409, "目标 SKU 未配置有效标准克重，无法创建 BOM 草稿", "BOM_TARGET_GRAMS_PER_UNIT_REQUIRED");
        }
        if (target.getUnit() == null || target.getUnit().isBlank()) {
            throw businessError(409, "目标 SKU 未配置产出单位，无法创建 BOM 草稿", "BOM_TARGET_UNIT_REQUIRED");
        }
        List<BomRecipe> targetVersions = recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc(
                factoryId, target.getId());
        if (targetVersions.stream().anyMatch(recipe -> recipe.getStatus() == BomRecipe.Status.DRAFT)) {
            throw businessError(409, "目标产品已有 BOM 草稿，请先编辑或删除现有草稿", "BOM_TARGET_DRAFT_EXISTS");
        }
        if (targetVersions.size() >= MAX_VERSIONS_PER_PRODUCT) {
            throw businessError(409, "每个 SKU 最多保留 10 个 BOM 版本", "BOM_VERSION_LIMIT_REACHED");
        }

        WorkflowProcessPath targetPath = requirePath(factoryId, target.getId());
        WorkflowProcessPath sourcePath = requirePath(factoryId, source.getProductTypeId());
        Set<String> sharedProcessIds = sharedProcessIds(targetPath, sourcePath);
        if (!sameSource(targetPath, sourcePath) || sharedProcessIds.isEmpty()) {
            throw businessError(409, "来源产品与目标产品已不再属于同源共享工序，不能复制", "BOM_COPY_WORKFLOW_MISMATCH");
        }

        List<Long> itemIds = validateIdList(request.getRecipeItemIds(), "recipeItemIds");
        List<Long> seasoningIds = validateIdList(request.getSeasoningItemIds(), "seasoningItemIds");
        List<Long> configIds = validateIdList(request.getProcessInjectionConfigIds(), "processInjectionConfigIds");
        if (itemIds.isEmpty() && seasoningIds.isEmpty() && configIds.isEmpty()) {
            throw businessError(400, "请至少选择一条要复制的 BOM 规则", "BOM_COPY_EMPTY_SELECTION");
        }

        List<BomRecipeItem> sourceItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(source.getId());
        List<BomSeasoningItem> sourceSeasoning = seasoningRepo.findByRecipeIdOrderBySeqAsc(source.getId());
        List<BomProcessInjectionConfig> sourceConfigs = processInjectionConfigRepo
                .findByRecipeIdAndDeletedAtIsNull(source.getId());
        List<BomRecipeItem> selectedItems = selectOwned(sourceItems, itemIds, BomRecipeItem::getId, "recipeItemIds");
        List<BomSeasoningItem> selectedSeasoning = selectOwned(
                sourceSeasoning, seasoningIds, BomSeasoningItem::getId, "seasoningItemIds");
        List<BomProcessInjectionConfig> selectedConfigs = selectOwned(
                sourceConfigs, configIds, BomProcessInjectionConfig::getId, "processInjectionConfigIds");
        List<SeasoningCopySelection> seasoningSelections = new ArrayList<>();
        for (BomSeasoningItem item : selectedSeasoning) {
            ProcessNodeResolution resolution = resolveTargetSeasoningNode(sourcePath, targetPath, item);
            if (resolution.status() == ProcessNodeMappingStatus.AMBIGUOUS) {
                throw businessError(409, "所选调味规则无法唯一映射到目标 Workflow 工序节点: " + item.getId(),
                        "BOM_COPY_AMBIGUOUS_PROCESS_NODE");
            }
            if (resolution.status() != ProcessNodeMappingStatus.MAPPED) {
                throw businessError(400, "所选调味规则绑定了非共享工序: " + item.getId(), "BOM_COPY_INCOMPATIBLE_RULE");
            }
            seasoningSelections.add(new SeasoningCopySelection(item, resolution.targetStep()));
        }
        for (BomProcessInjectionConfig config : selectedConfigs) {
            if (!sharedProcessIds.contains(config.getWorkProcessId())) {
                throw businessError(400, "所选注射配置不属于共享工序: " + config.getId(), "BOM_COPY_INCOMPATIBLE_RULE");
            }
        }

        // Always start from the target SKU's server-owned Workflow skeleton. Copying into a
        // hand-built, unpinned recipe used to create RAW rows with no stable node/port/edge tuple.
        BomRecipe draft = bomRecipeService.ensureDraft(factoryId, target.getId());
        draft.setNotes("参考复制自 " + source.getProductName() + " / " + source.getRecipeCode()
                + " (v" + source.getVersion() + ")，请核对数量后再激活");
        draft = recipeRepo.save(draft);
        String draftId = draft.getId();

        for (BomRecipeItem selectedItem : selectedItems) {
            // Deliberately omit source Workflow tuple and cost scope. addItem resolves the target
            // slot by unique material+unit and merges into its skeleton; ambiguity fails closed.
            bomRecipeService.addItem(factoryId, draftId, copyItemRequest(selectedItem));
        }
        seasoningRepo.saveAll(seasoningSelections.stream()
                .map(selection -> copySeasoning(
                        factoryId, draftId, selection.source(), selection.targetStep())).toList());
        processInjectionConfigRepo.saveAll(selectedConfigs.stream()
                .map(config -> copyInjectionConfig(factoryId, draftId, config)).toList());
        return bomRecipeService.getRecipe(factoryId, draftId);
    }

    private BomCopyCandidateDTO toCandidate(WorkflowProcessPath targetPath, CandidateContext context,
                                             Map<String, String> processNames) {
        List<BomCopyCandidateDTO.SharedProcessDTO> sharedProcesses = targetPath.processes().stream()
                .filter(step -> context.sharedProcessIds().contains(step.workProcessId()))
                .map(step -> BomCopyCandidateDTO.SharedProcessDTO.builder()
                        .workflowProcessNodeId(step.processNodeId())
                        .workProcessId(step.workProcessId()).processName(processNames.get(step.workProcessId()))
                        .targetOrder(step.order()).build()).toList();
        List<BomCopyCandidateDTO.BomItemRuleDTO> items = itemRepo
                .findByRecipeIdOrderBySortOrderAsc(context.sourceRecipe().getId()).stream()
                .map(this::toItemRule).toList();
        List<BomCopyCandidateDTO.SeasoningRuleDTO> seasonings = new ArrayList<>();
        for (BomSeasoningItem item : seasoningRepo
                .findByRecipeIdOrderBySeqAsc(context.sourceRecipe().getId())) {
            ProcessNodeResolution resolution = resolveTargetSeasoningNode(
                    context.sourcePath(), targetPath, item);
            if (resolution.status() == ProcessNodeMappingStatus.MAPPED) {
                seasonings.add(toSeasoningRule(item, resolution.targetStep(), processNames));
            }
        }
        List<BomCopyCandidateDTO.ProcessInjectionConfigRuleDTO> configs = processInjectionConfigRepo
                .findByRecipeIdAndDeletedAtIsNull(context.sourceRecipe().getId()).stream()
                .filter(config -> context.sharedProcessIds().contains(config.getWorkProcessId()))
                .map(config -> toInjectionConfigRule(config, processNames)).toList();
        return BomCopyCandidateDTO.builder()
                .sourceProductTypeId(context.sourceProduct().getId())
                .sourceProductName(context.sourceProduct().getName())
                .sourceRecipeId(context.sourceRecipe().getId())
                .sourceRecipeCode(context.sourceRecipe().getRecipeCode())
                .sourceRecipeVersion(context.sourceRecipe().getVersion())
                .rawRootMaterialTypeId(context.sourcePath().rawRootMaterialTypeId())
                .rawRootMaterialTypeIds(normalizedRawRootMaterialTypeIds(context.sourcePath()).stream()
                        .sorted().toList())
                .sharedProcesses(sharedProcesses).bomItems(items).seasoningItems(seasonings)
                .processInjectionConfigs(configs).build();
    }

    private BomCopyCandidateDTO.BomItemRuleDTO toItemRule(BomRecipeItem item) {
        return BomCopyCandidateDTO.BomItemRuleDTO.builder().id(item.getId())
                .materialTypeId(item.getMaterialTypeId()).materialName(item.getMaterialName())
                .standardQuantity(item.getStandardQuantity()).unit(item.getUnit())
                .materialCategory(item.getMaterialCategory()).sortOrder(item.getSortOrder())
                .optional(item.getIsOptional()).substituteGroup(item.getSubstituteGroup())
                .remark(item.getRemark()).perPortion(item.getPerPortion())
                .semiFinishedRefCode(item.getSemiFinishedRefCode())
                .subProductTypeId(item.getSubProductTypeId()).primaryCode(item.getPrimaryCode()).build();
    }

    private BomCopyCandidateDTO.SeasoningRuleDTO toSeasoningRule(
            BomSeasoningItem item,
            WorkflowProcessPath.ProcessStep targetStep,
            Map<String, String> processNames) {
        return BomCopyCandidateDTO.SeasoningRuleDTO.builder().id(item.getId())
                .materialTypeId(item.getMaterialTypeId()).name(item.getName()).section(item.getSection())
                .dosagePerKgG(item.getDosagePerKgG()).seq(item.getSeq())
                .workProcessId(targetStep.workProcessId())
                .workflowProcessNodeId(targetStep.processNodeId())
                .sourceWorkflowProcessNodeId(item.getWorkflowProcessNodeId())
                .workProcessName(processNames.get(targetStep.workProcessId()))
                .countInSeasoning(item.getCountInSeasoning()).remark(item.getRemark()).build();
    }

    private BomCopyCandidateDTO.ProcessInjectionConfigRuleDTO toInjectionConfigRule(
            BomProcessInjectionConfig config, Map<String, String> processNames) {
        return BomCopyCandidateDTO.ProcessInjectionConfigRuleDTO.builder().id(config.getId())
                .workProcessId(config.getWorkProcessId())
                .workProcessName(processNames.get(config.getWorkProcessId()))
                .injectionAmountKg(config.getInjectionAmountKg()).notes(config.getNotes()).build();
    }

    private BomRecipeItemDTO copyItemRequest(BomRecipeItem source) {
        BomRecipeItemDTO copy = new BomRecipeItemDTO();
        copy.setMaterialTypeId(source.getMaterialTypeId());
        copy.setStandardQuantity(source.getStandardQuantity());
        copy.setUnit(source.getUnit());
        copy.setMaterialCategory(source.getMaterialCategory());
        copy.setSortOrder(source.getSortOrder());
        copy.setIsOptional(source.getIsOptional());
        copy.setSubstituteGroup(source.getSubstituteGroup());
        copy.setPackagingSpecId(source.getPackagingSpecId());
        copy.setPackagingRole(source.getPackagingRole());
        copy.setNaturalQuantity(source.getNaturalQuantity());
        copy.setRemark(source.getRemark());
        copy.setPerPortion(source.getPerPortion());
        copy.setSemiFinishedRefCode(source.getSemiFinishedRefCode());
        copy.setSubProductTypeId(source.getSubProductTypeId());
        copy.setPrimaryCode(source.getPrimaryCode());
        copy.setPrimaryCodeRef(source.getPrimaryCodeRef());
        return copy;
    }

    private BomSeasoningItem copySeasoning(
            String factoryId,
            String recipeId,
            BomSeasoningItem source,
            WorkflowProcessPath.ProcessStep targetStep) {
        BomSeasoningItem copy = new BomSeasoningItem();
        copy.setRecipeId(recipeId);
        copy.setFactoryId(factoryId);
        copy.setMaterialTypeId(source.getMaterialTypeId());
        copy.setSection(source.getSection());
        copy.setSeq(source.getSeq());
        copy.setName(source.getName());
        copy.setDosagePerKgG(source.getDosagePerKgG());
        copy.setPriceSource1(source.getPriceSource1());
        copy.setPriceSource2(source.getPriceSource2());
        copy.setCountInSeasoning(source.getCountInSeasoning());
        copy.setRemark(source.getRemark());
        copy.setWorkProcessId(targetStep.workProcessId());
        copy.setWorkflowProcessNodeId(targetStep.processNodeId());
        copy.setSubsequentPotRatio(source.getSubsequentPotRatio());
        return copy;
    }

    private BomProcessInjectionConfig copyInjectionConfig(
            String factoryId, String recipeId, BomProcessInjectionConfig source) {
        BomProcessInjectionConfig copy = new BomProcessInjectionConfig();
        copy.setFactoryId(factoryId);
        copy.setRecipeId(recipeId);
        copy.setWorkProcessId(source.getWorkProcessId());
        copy.setInjectionAmountKg(source.getInjectionAmountKg());
        copy.setNotes(source.getNotes());
        return copy;
    }

    private ProductType loadProduct(String factoryId, String productTypeId) {
        return productTypeRepo.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("目标产品不存在: " + productTypeId));
    }

    private BomRecipe loadSourceRecipe(String factoryId, String sourceRecipeId) {
        BomRecipe source = recipeRepo.findById(sourceRecipeId)
                .orElseThrow(() -> new EntityNotFoundException("来源 BOM 不存在: " + sourceRecipeId));
        if (!factoryId.equals(source.getFactoryId())) {
            throw businessError(403, "来源 BOM 不属于当前工厂", "BOM_COPY_CROSS_FACTORY");
        }
        if (source.getStatus() != BomRecipe.Status.ACTIVE || !Boolean.TRUE.equals(source.getIsCurrent())) {
            throw businessError(409, "来源 BOM 必须是当前生效版本", "BOM_COPY_SOURCE_NOT_CURRENT");
        }
        return source;
    }

    private WorkflowProcessPath requirePath(String factoryId, String productTypeId) {
        return workflowResolutionService.resolveProcessPath(factoryId, productTypeId)
                .orElseThrow(() -> businessError(409, "产品没有唯一有效的 Workflow 工序路径: " + productTypeId,
                        "BOM_COPY_WORKFLOW_REQUIRED"));
    }

    private boolean sameSource(WorkflowProcessPath left, WorkflowProcessPath right) {
        Set<String> leftRoots = normalizedRawRootMaterialTypeIds(left);
        Set<String> rightRoots = normalizedRawRootMaterialTypeIds(right);
        return !leftRoots.isEmpty() && leftRoots.equals(rightRoots);
    }

    private Set<String> normalizedRawRootMaterialTypeIds(WorkflowProcessPath path) {
        Set<String> roots = path.rawRootMaterialTypeIds() == null
                ? new LinkedHashSet<>()
                : path.rawRootMaterialTypeIds().stream()
                        .map(this::normalizeIdentifier)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!roots.isEmpty()) {
            return roots;
        }
        String legacyRoot = normalizeIdentifier(path.rawRootMaterialTypeId());
        return legacyRoot == null ? Set.of() : Set.of(legacyRoot);
    }

    private ProcessNodeResolution resolveTargetSeasoningNode(
            WorkflowProcessPath sourcePath,
            WorkflowProcessPath targetPath,
            BomSeasoningItem item) {
        String workProcessId = normalizeIdentifier(item.getWorkProcessId());
        if (workProcessId == null) {
            return ProcessNodeResolution.incompatible();
        }
        List<WorkflowProcessPath.ProcessStep> sourceMasterMatches = sourcePath.processes().stream()
                .filter(step -> workProcessId.equals(normalizeIdentifier(step.workProcessId())))
                .toList();
        String sourceNodeId = normalizeIdentifier(item.getWorkflowProcessNodeId());
        WorkflowProcessPath.ProcessStep sourceStep;
        if (sourceNodeId == null) {
            if (sourceMasterMatches.size() > 1) {
                return ProcessNodeResolution.ambiguous();
            }
            if (sourceMasterMatches.isEmpty()) {
                return ProcessNodeResolution.incompatible();
            }
            sourceStep = sourceMasterMatches.getFirst();
        } else {
            List<WorkflowProcessPath.ProcessStep> sourceIdentityMatches = sourceMasterMatches.stream()
                    .filter(step -> sourceNodeId.equals(normalizeIdentifier(step.processNodeId())))
                    .toList();
            if (sourceIdentityMatches.size() > 1) {
                return ProcessNodeResolution.ambiguous();
            }
            if (sourceIdentityMatches.isEmpty()) {
                return ProcessNodeResolution.incompatible();
            }
            sourceStep = sourceIdentityMatches.getFirst();
        }

        List<WorkflowProcessPath.ProcessStep> targetMatches;
        if (sameWorkflowRevision(sourcePath, targetPath)) {
            String exactNodeId = normalizeIdentifier(sourceStep.processNodeId());
            if (exactNodeId == null) {
                return ProcessNodeResolution.incompatible();
            }
            targetMatches = targetPath.processes().stream()
                    .filter(step -> workProcessId.equals(normalizeIdentifier(step.workProcessId())))
                    .filter(step -> exactNodeId.equals(normalizeIdentifier(step.processNodeId())))
                    .toList();
        } else {
            if (sourceMasterMatches.size() > 1) {
                return ProcessNodeResolution.ambiguous();
            }
            targetMatches = targetPath.processes().stream()
                    .filter(step -> workProcessId.equals(normalizeIdentifier(step.workProcessId())))
                    .toList();
        }
        if (targetMatches.size() > 1) {
            return ProcessNodeResolution.ambiguous();
        }
        if (targetMatches.isEmpty()
                || normalizeIdentifier(targetMatches.getFirst().processNodeId()) == null) {
            return ProcessNodeResolution.incompatible();
        }
        return ProcessNodeResolution.mapped(targetMatches.getFirst());
    }

    private boolean sameWorkflowRevision(WorkflowProcessPath left, WorkflowProcessPath right) {
        return left.workflowId() == right.workflowId()
                && left.definitionVersion() == right.definitionVersion();
    }

    private String normalizeIdentifier(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Set<String> sharedProcessIds(WorkflowProcessPath left, WorkflowProcessPath right) {
        Set<String> rightIds = right.processes().stream().map(WorkflowProcessPath.ProcessStep::workProcessId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return left.processes().stream().map(WorkflowProcessPath.ProcessStep::workProcessId)
                .filter(Objects::nonNull).filter(rightIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Long> validateIdList(List<Long> ids, String field) {
        if (ids == null) return List.of();
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) {
            throw businessError(400, field + " 含空值或重复 ID", "BOM_COPY_INVALID_SELECTION");
        }
        return List.copyOf(ids);
    }

    private <T> List<T> selectOwned(List<T> sourceRows, List<Long> selectedIds,
                                    Function<T, Long> idGetter, String field) {
        Map<Long, T> byId = sourceRows.stream().collect(Collectors.toMap(idGetter, Function.identity()));
        List<T> selected = new ArrayList<>();
        for (Long id : selectedIds) {
            T row = byId.get(id);
            if (row == null) {
                throw businessError(400, field + " 包含不属于来源 BOM 的 ID: " + id,
                        "BOM_COPY_FOREIGN_RULE_ID");
            }
            selected.add(row);
        }
        return selected;
    }

    private BusinessException businessError(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code).withSeverity("BLOCKING");
    }

    private record CandidateContext(ProductType sourceProduct, BomRecipe sourceRecipe,
                                    WorkflowProcessPath sourcePath, Set<String> sharedProcessIds) { }

    private record SeasoningCopySelection(
            BomSeasoningItem source, WorkflowProcessPath.ProcessStep targetStep) { }

    private enum ProcessNodeMappingStatus { MAPPED, INCOMPATIBLE, AMBIGUOUS }

    private record ProcessNodeResolution(
            ProcessNodeMappingStatus status, WorkflowProcessPath.ProcessStep targetStep) {

        private static ProcessNodeResolution mapped(WorkflowProcessPath.ProcessStep targetStep) {
            return new ProcessNodeResolution(ProcessNodeMappingStatus.MAPPED, targetStep);
        }

        private static ProcessNodeResolution incompatible() {
            return new ProcessNodeResolution(ProcessNodeMappingStatus.INCOMPATIBLE, null);
        }

        private static ProcessNodeResolution ambiguous() {
            return new ProcessNodeResolution(ProcessNodeMappingStatus.AMBIGUOUS, null);
        }
    }
}
