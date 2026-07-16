package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.BomCopyToDraftRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.bom.BomProcessSeasoning;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.bom.BomProcessSeasoningRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter CODE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BomRecipeRepository recipeRepo;
    private final BomRecipeItemRepository itemRepo;
    private final BomSeasoningItemRepository seasoningRepo;
    private final BomProcessSeasoningRepository processSeasoningRepo;
    private final ProductTypeRepository productTypeRepo;
    private final WorkProcessRepository workProcessRepo;
    private final ProductWorkflowResolutionService workflowResolutionService;

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
        ProductType target = loadProduct(factoryId, request.getTargetProductTypeId());
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

        List<Long> itemIds = validateIdList(request.getBomItemIds(), "bomItemIds");
        List<Long> seasoningIds = validateIdList(request.getSeasoningItemIds(), "seasoningItemIds");
        List<Long> paramIds = validateIdList(request.getProcessSeasoningParamIds(), "processSeasoningParamIds");
        if (itemIds.isEmpty() && seasoningIds.isEmpty() && paramIds.isEmpty()) {
            throw businessError(400, "请至少选择一条要复制的 BOM 规则", "BOM_COPY_EMPTY_SELECTION");
        }

        List<BomRecipeItem> sourceItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(source.getId());
        List<BomSeasoningItem> sourceSeasoning = seasoningRepo.findByRecipeIdOrderBySeqAsc(source.getId());
        List<BomProcessSeasoning> sourceParams = processSeasoningRepo.findByRecipeIdAndDeletedAtIsNull(source.getId());
        List<BomRecipeItem> selectedItems = selectOwned(sourceItems, itemIds, BomRecipeItem::getId, "bomItemIds");
        List<BomSeasoningItem> selectedSeasoning = selectOwned(
                sourceSeasoning, seasoningIds, BomSeasoningItem::getId, "seasoningItemIds");
        List<BomProcessSeasoning> selectedParams = selectOwned(
                sourceParams, paramIds, BomProcessSeasoning::getId, "processSeasoningParamIds");
        for (BomSeasoningItem item : selectedSeasoning) {
            if (item.getWorkProcessId() == null || !sharedProcessIds.contains(item.getWorkProcessId())) {
                throw businessError(400, "所选调味规则绑定了非共享工序: " + item.getId(), "BOM_COPY_INCOMPATIBLE_RULE");
            }
        }
        for (BomProcessSeasoning param : selectedParams) {
            if (!sharedProcessIds.contains(param.getWorkProcessId())) {
                throw businessError(400, "所选工序调味参数不属于共享工序: " + param.getId(), "BOM_COPY_INCOMPATIBLE_RULE");
            }
        }

        BomRecipe draft = new BomRecipe();
        draft.setFactoryId(factoryId);
        draft.setRecipeCode(generateRecipeCode(factoryId));
        draft.setProductTypeId(target.getId());
        draft.setProductName(target.getName());
        Integer maxVersion = recipeRepo.findMaxVersion(factoryId, target.getId());
        draft.setVersion(maxVersion == null ? 1 : maxVersion + 1);
        draft.setIsCurrent(false);
        draft.setOverallYieldRate(null);
        draft.setOutputQuantityPerUnit(target.getGramsPerUnit());
        draft.setOutputUnit(target.getUnit());
        draft.setStatus(BomRecipe.Status.DRAFT);
        draft.setSourceType(BomRecipe.SourceType.MANUAL);
        draft.setNotes("参考复制自 " + source.getProductName() + " / " + source.getRecipeCode()
                + " (v" + source.getVersion() + ")，请核对数量后再激活");
        draft = recipeRepo.save(draft);
        String draftId = draft.getId();

        List<BomRecipeItem> copiedItems = selectedItems.stream()
                .map(item -> copyItem(factoryId, draftId, item)).toList();
        itemRepo.saveAll(copiedItems);
        draft.getItems().clear();
        draft.getItems().addAll(copiedItems);
        seasoningRepo.saveAll(selectedSeasoning.stream()
                .map(item -> copySeasoning(factoryId, draftId, item)).toList());
        processSeasoningRepo.saveAll(selectedParams.stream()
                .map(param -> copyProcessParam(factoryId, draftId, param)).toList());
        return recipeRepo.save(draft);
    }

    private BomCopyCandidateDTO toCandidate(WorkflowProcessPath targetPath, CandidateContext context,
                                             Map<String, String> processNames) {
        List<BomCopyCandidateDTO.SharedProcessDTO> sharedProcesses = targetPath.processes().stream()
                .filter(step -> context.sharedProcessIds().contains(step.workProcessId()))
                .map(step -> BomCopyCandidateDTO.SharedProcessDTO.builder()
                        .workProcessId(step.workProcessId()).processName(processNames.get(step.workProcessId()))
                        .targetOrder(step.order()).build()).toList();
        List<BomCopyCandidateDTO.BomItemRuleDTO> items = itemRepo
                .findByRecipeIdOrderBySortOrderAsc(context.sourceRecipe().getId()).stream()
                .map(this::toItemRule).toList();
        List<BomCopyCandidateDTO.SeasoningRuleDTO> seasonings = seasoningRepo
                .findByRecipeIdOrderBySeqAsc(context.sourceRecipe().getId()).stream()
                .filter(item -> item.getWorkProcessId() != null
                        && context.sharedProcessIds().contains(item.getWorkProcessId()))
                .map(item -> toSeasoningRule(item, processNames)).toList();
        List<BomCopyCandidateDTO.ProcessSeasoningRuleDTO> params = processSeasoningRepo
                .findByRecipeIdAndDeletedAtIsNull(context.sourceRecipe().getId()).stream()
                .filter(param -> context.sharedProcessIds().contains(param.getWorkProcessId()))
                .map(param -> toParamRule(param, processNames)).toList();
        return BomCopyCandidateDTO.builder()
                .sourceProductTypeId(context.sourceProduct().getId())
                .sourceProductName(context.sourceProduct().getName())
                .sourceRecipeId(context.sourceRecipe().getId())
                .sourceRecipeCode(context.sourceRecipe().getRecipeCode())
                .sourceRecipeVersion(context.sourceRecipe().getVersion())
                .rawRootMaterialTypeId(context.sourcePath().rawRootMaterialTypeId())
                .sharedProcesses(sharedProcesses).bomItems(items).seasoningItems(seasonings)
                .processSeasoningParams(params).build();
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

    private BomCopyCandidateDTO.SeasoningRuleDTO toSeasoningRule(BomSeasoningItem item,
                                                                  Map<String, String> processNames) {
        return BomCopyCandidateDTO.SeasoningRuleDTO.builder().id(item.getId())
                .materialTypeId(item.getMaterialTypeId()).name(item.getName()).section(item.getSection())
                .dosagePerKgG(item.getDosagePerKgG()).seq(item.getSeq())
                .workProcessId(item.getWorkProcessId()).workProcessName(processNames.get(item.getWorkProcessId()))
                .countInSeasoning(item.getCountInSeasoning()).remark(item.getRemark()).build();
    }

    private BomCopyCandidateDTO.ProcessSeasoningRuleDTO toParamRule(BomProcessSeasoning param,
                                                                     Map<String, String> processNames) {
        return BomCopyCandidateDTO.ProcessSeasoningRuleDTO.builder().id(param.getId())
                .workProcessId(param.getWorkProcessId()).workProcessName(processNames.get(param.getWorkProcessId()))
                .subsequentPotRatio(param.getSubsequentPotRatio())
                .injectionAmountKg(param.getInjectionAmountKg()).notes(param.getNotes()).build();
    }

    private BomRecipeItem copyItem(String factoryId, String recipeId, BomRecipeItem source) {
        BomRecipeItem copy = new BomRecipeItem();
        copy.setRecipeId(recipeId);
        copy.setFactoryId(factoryId);
        copy.setMaterialTypeId(source.getMaterialTypeId());
        copy.setMaterialName(source.getMaterialName());
        copy.setStandardQuantity(source.getStandardQuantity());
        copy.setYieldRate(source.getYieldRate());
        copy.setActualQuantity(source.getActualQuantity());
        copy.setUnit(source.getUnit());
        copy.setUnitPrice(source.getUnitPrice());
        copy.setTaxRate(source.getTaxRate());
        copy.setItemCost(source.getItemCost());
        copy.setMaterialCategory(source.getMaterialCategory());
        copy.setSortOrder(source.getSortOrder());
        copy.setIsOptional(source.getIsOptional());
        copy.setSubstituteGroup(source.getSubstituteGroup());
        copy.setRemark(source.getRemark());
        copy.setPerPortion(source.getPerPortion());
        copy.setSemiFinishedRefCode(source.getSemiFinishedRefCode());
        copy.setSubProductTypeId(source.getSubProductTypeId());
        copy.setPrimaryCode(source.getPrimaryCode());
        copy.setPrimaryCodeRef(source.getPrimaryCodeRef());
        return copy;
    }

    private BomSeasoningItem copySeasoning(String factoryId, String recipeId, BomSeasoningItem source) {
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
        copy.setWorkProcessId(source.getWorkProcessId());
        copy.setSubsequentPotRatio(source.getSubsequentPotRatio());
        return copy;
    }

    private BomProcessSeasoning copyProcessParam(String factoryId, String recipeId, BomProcessSeasoning source) {
        BomProcessSeasoning copy = new BomProcessSeasoning();
        copy.setFactoryId(factoryId);
        copy.setRecipeId(recipeId);
        copy.setWorkProcessId(source.getWorkProcessId());
        copy.setSubsequentPotRatio(source.getSubsequentPotRatio());
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
        return left.rawRootMaterialTypeId() != null
                && !left.rawRootMaterialTypeId().isBlank()
                && right.rawRootMaterialTypeId() != null
                && !right.rawRootMaterialTypeId().isBlank()
                && left.rawRootMaterialTypeId().equals(right.rawRootMaterialTypeId());
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

    private String generateRecipeCode(String factoryId) {
        String prefix = "BOM-" + LocalDate.now().format(CODE_DATE_FMT) + "-";
        return String.format("%s%03d", prefix, recipeRepo.countByRecipeCodePrefix(factoryId, prefix + "%") + 1);
    }

    private BusinessException businessError(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code).withSeverity("BLOCKING");
    }

    private record CandidateContext(ProductType sourceProduct, BomRecipe sourceRecipe,
                                    WorkflowProcessPath sourcePath, Set<String> sharedProcessIds) { }
}
