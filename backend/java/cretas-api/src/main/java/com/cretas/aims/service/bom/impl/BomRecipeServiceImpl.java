package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.BomFamilyOutputCostingResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest.SeasoningItemDTO;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.dto.bom.UpdateBomFamilyOutputCostingRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.dto.bom.ProcessInjectionConfigDTO;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * BomRecipeService implementation (Track D1 / M-BOM-1).
 *
 * <p>状态机:
 * <pre>
 *   DRAFT ─→ ACTIVE (activate; 同产品其他 is_current=true 设为 false)
 *     │       │
 *     │       ↓
 *     └─→ ARCHIVED (archive; is_current=false)
 *
 *   DRAFT 可 delete (softDelete);
 *   ACTIVE/ARCHIVED 不可 delete (用 archive 替代).
 * </pre>
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomRecipeServiceImpl implements BomRecipeService {

    private static final DateTimeFormatter CODE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_VERSIONS_PER_PRODUCT = 10;

    private final BomRecipeRepository recipeRepo;
    private final BomRecipeItemRepository itemRepo;
    private final ProductTypeRepository productTypeRepo;
    private final RawMaterialTypeRepository materialTypeRepo;
    private final MaterialUomConverter materialUomConverter;
    private final UnitContractService unitContractService;
    private final ProductPackagingSpecRepository packagingSpecRepository;
    private final ProductConfigurationReadinessService readinessService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final BomItemSubstituteService substituteService;
    /** SP1: 嵌套 BOM 成本聚合 (组合装/先做后用). */
    private final NestedBomCostService nestedBomCostService;
    /** U5: BOM 调料明细 repo (BOM 统管配方+锅序, 2026-06-24). */
    private final BomSeasoningItemRepository seasoningItemRepo;
    private final BomProcessInjectionConfigRepository processInjectionConfigRepo;

    @Override
    @Transactional
    public BomRecipe ensureDraft(String factoryId, String productTypeId) {
        ProductType product = loadProductForUpdate(factoryId, productTypeId);
        validateProductOutputMetadata(product);

        List<BomRecipe> versions = recipeRepo
                .findByFactoryIdAndProductTypeIdOrderByVersionDesc(factoryId, productTypeId);
        List<BomRecipe> drafts = versions.stream()
                .filter(recipe -> recipe.getStatus() == BomRecipe.Status.DRAFT)
                .toList();
        if (drafts.size() > 1) {
            throw bomError(409,
                    "该产品存在多个 BOM 草稿，无法判断应继续编辑哪一个",
                    "BOM_MULTIPLE_DRAFTS",
                    "请先保留一个草稿并归档或删除其余草稿后重试",
                    "bomVersions");
        }
        if (drafts.size() == 1) {
            BomRecipe draft = drafts.get(0);
            if (draft.getWorkflowRevisionHash() == null) {
                BomWorkflowRevisionService.WorkflowBinding binding =
                        bomWorkflowRevisionService.autoBindUniqueDraft(factoryId, draft);
                initializeFamilyAndInputSkeletons(factoryId, draft, binding);
            } else {
                assertPinnedDraft(factoryId, draft);
            }
            List<BomRecipeItem> freshItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(draft.getId());
            draft.getItems().clear();
            draft.getItems().addAll(freshItems);
            return draft;
        }

        assertVersionCapacity(factoryId, productTypeId);
        if (versions.isEmpty()) {
            BomRecipe draft = new BomRecipe();
            draft.setFactoryId(factoryId);
            draft.setRecipeCode(generateRecipeCode(factoryId));
            draft.setProductTypeId(productTypeId);
            draft.setProductName(product.getName());
            draft.setVersion(1);
            draft.setIsCurrent(false);
            draft.setOverallYieldRate(null);
            applyProductOutputSnapshot(draft, product);
            draft.setStatus(BomRecipe.Status.DRAFT);
            draft.setSourceType(BomRecipe.SourceType.MANUAL);
            draft.setTotalMaterialCost(BigDecimal.ZERO);
            draft.setTotalCost(BigDecimal.ZERO);
            draft = recipeRepo.saveAndFlush(draft);
            BomWorkflowRevisionService.WorkflowBinding binding =
                    bomWorkflowRevisionService.autoBindUniqueDraft(factoryId, draft);
            return initializeFamilyAndInputSkeletons(factoryId, draft, binding);
        }

        List<BomRecipe> currentActive = versions.stream()
                .filter(recipe -> recipe.getStatus() == BomRecipe.Status.ACTIVE)
                .filter(recipe -> Boolean.TRUE.equals(recipe.getIsCurrent()))
                .toList();
        if (currentActive.size() != 1) {
            throw bomError(409,
                    "该产品没有唯一的当前生效 BOM，无法安全创建新版本",
                    "BOM_CURRENT_ACTIVE_REQUIRED",
                    "请先修复版本状态，确保只有一个 ACTIVE/current 版本",
                    "bomVersions");
        }
        return cloneRecipe(factoryId, currentActive.get(0).getId());
    }

    @Override
    @Transactional
    public BomRecipe createRecipe(String factoryId, CreateBomRecipeRequest req) {
        log.info("Creating BOM recipe: factory={}, product={}, items={}",
                factoryId, req.getProductTypeId(), req.getItems().size());

        assertVersionCapacity(factoryId, req.getProductTypeId());
        ProductType product = loadProductForUpdate(factoryId, req.getProductTypeId());
        validateProductOutputMetadata(product);
        BomRecipe recipe = new BomRecipe();
        recipe.setFactoryId(factoryId);
        recipe.setRecipeCode(generateRecipeCode(factoryId));
        recipe.setProductTypeId(req.getProductTypeId());
        recipe.setProductName(product.getName());
        recipe.setVersion(recipeRepo.findMaxVersion(factoryId, req.getProductTypeId()) + 1);
        // 草稿不占用“当前生效”槽位；只有 activateRecipe 能设置 isCurrent=true。
        recipe.setIsCurrent(false);
        // 整体出成率由正式批次报工历史自动学习，配方头不接收人工初始值。
        recipe.setOverallYieldRate(null);
        applyProductOutputSnapshot(recipe, product);
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setSourceType(req.getSourceType() != null ? req.getSourceType() : BomRecipe.SourceType.MANUAL);
        recipe.setSourceSampleId(req.getSourceSampleId());
        recipe.setNotes(req.getNotes());

        // First persist parent (UUID assigned by @PrePersist) to get recipe.id for items.
        recipe = recipeRepo.saveAndFlush(recipe);
        BomWorkflowRevisionService.WorkflowBinding binding =
                bomWorkflowRevisionService.autoBindUniqueDraft(factoryId, recipe);
        recipe = initializeFamilyAndInputSkeletons(factoryId, recipe, binding);

        // Build items with rehydrated material_name + denormalized unit if not provided.
        List<BomRecipeItem> items = new ArrayList<>();
        if (!req.getItems().isEmpty()) {
            List<BomRecipeItem> generated =
                    itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
            generated.forEach(BomRecipeItem::softDelete);
            itemRepo.saveAll(generated);
        }
        for (BomRecipeItemDTO dto : req.getItems()) {
            items.add(buildItem(factoryId, recipe, dto));
        }
        itemRepo.saveAll(items);
        recipe.getItems().clear();
        recipe.getItems().addAll(items);

        // Initial cost calculation (material cost only; labor/overhead deferred to Day 5).
        recomputeFamilyCosts(recipe);
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public BomRecipe updateRecipe(String factoryId, String recipeId, UpdateBomRecipeRequest req) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态的 BOM 配方可以修改; 当前 status=" + recipe.getStatus()
                    + ", 请克隆为新版本后再改");
        }
        assertPinnedDraft(factoryId, recipe);

        if (req.getProductName() != null) recipe.setProductName(req.getProductName());
        // overallYieldRate 是系统学习字段，草稿编辑不可人工覆盖。
        ProductType product = loadProductForUpdate(factoryId, recipe.getProductTypeId());
        validateProductOutputMetadata(product);
        applyProductOutputSnapshot(recipe, product);
        if (req.getNotes() != null) recipe.setNotes(req.getNotes());

        // PUT is full-replace for items: soft-delete existing, persist new list.
        if (req.getItems() != null) {
            if (familyForStatus(recipe).size() > 1) {
                throw bomError(409,
                        "多产出 BOM 不能从单个产出执行整表覆盖",
                        "BOM_FAMILY_FULL_REPLACE_FORBIDDEN",
                        "请逐条编辑物料；批量导入需使用支持完整 BOM Family 的专用入口",
                        "bomItems");
            }
            List<BomRecipeItem> oldItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
            for (BomRecipeItem old : oldItems) {
                old.softDelete();
            }
            itemRepo.saveAll(oldItems);

            List<BomRecipeItem> newItems = new ArrayList<>();
            for (BomRecipeItemDTO dto : req.getItems()) {
                newItems.add(buildItem(factoryId, recipe, dto));
            }
            itemRepo.saveAll(newItems);
            // IMPORTANT: keep same Hibernate PersistentBag reference (clear+addAll, not setItems).
            recipe.getItems().clear();
            recipe.getItems().addAll(newItems);
        }

        recomputeFamilyCosts(recipe);
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public BomRecipe activateRecipe(String factoryId, String recipeId, Long operatorId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() == BomRecipe.Status.ACTIVE && Boolean.TRUE.equals(recipe.getIsCurrent())) {
            throw new IllegalStateException("当前 BOM 已经生效，无需重复激活");
        }

        List<BomRecipe> family = activationFamily(factoryId, recipe);
        validateFamilyContracts(factoryId, family);
        validateByProductCreditRules(family);
        recomputeFamilyCosts(recipe);
        for (BomRecipe member : family) {
            ProductType product = loadProductForUpdate(factoryId, member.getProductTypeId());
            validateProductOutputMetadata(product);
            validateRecipeOutputContract(factoryId, member, product);
            validateActivatableItems(member);
            readinessService.requireBomCompleteForActivation(factoryId, member);
        }

        // Archive competing versions for every terminal output before making the new family current.
        for (BomRecipe member : family) {
            for (BomRecipe other : recipeRepo.findCompetingVersionsForActivation(
                    factoryId, member.getProductTypeId(), member.getId(), BomRecipe.Status.ACTIVE)) {
                other.setStatus(BomRecipe.Status.ARCHIVED);
                other.setIsCurrent(false);
                recipeRepo.save(other);
            }
        }
        recipeRepo.flush();

        LocalDateTime activatedAt = LocalDateTime.now();
        for (BomRecipe member : family) {
            member.setStatus(BomRecipe.Status.ACTIVE);
            member.setIsCurrent(true);
            member.setActivatedAt(activatedAt);
            member.setActivatedBy(operatorId);
            recipeRepo.save(member);
        }
        return family.stream().filter(member -> member.getId().equals(recipeId)).findFirst().orElse(recipe);
    }

    private void validateByProductCreditRules(List<BomRecipe> family) {
        for (BomRecipe member : family) {
            if (member.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT) continue;
            if (member.getCostAllocationRatio() == null
                    || member.getCostAllocationRatio().compareTo(BigDecimal.ZERO) != 0) {
                throw bomError(409,
                        "副产品不参与共享成本比例分摊，分摊比例必须为 0%",
                        "BOM_BY_PRODUCT_ALLOCATION_MUST_BE_ZERO",
                        "请在 Workflow 中把副产品分摊比例设为 0",
                        "costAllocationRatio");
            }
            if (member.getByproductNrvUnitPrice() == null
                    || member.getByproductNrvUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw bomError(409,
                        "副产品缺少单位可变现净值，无法计算成本抵扣",
                        "BOM_BY_PRODUCT_NRV_REQUIRED",
                        "请填写副产品预计售价扣除后续加工、销售费用后的单位净值",
                        "byproductNrvUnitPrice");
            }
            if (member.getOutputQuantityPerUnit() == null
                    || member.getOutputQuantityPerUnit().compareTo(BigDecimal.ZERO) <= 0) {
                throw bomError(409,
                        "副产品缺少本批次基准产出数量，无法计算成本抵扣",
                        "BOM_BY_PRODUCT_OUTPUT_QUANTITY_REQUIRED",
                        "请先补齐副产品的产出数量与单位",
                        "outputQuantityPerUnit");
            }
        }
    }

    @Override
    @Transactional
    public BomRecipe cloneRecipe(String factoryId, String recipeId) {
        BomRecipe source = loadRecipe(factoryId, recipeId);
        if (source.getWorkflowRevisionHash() == null) {
            throw bomError(409, "历史 BOM 未固定可证明的 Workflow 修订，不能静默克隆",
                    "BOM_WORKFLOW_LEGACY_MIGRATION_REQUIRED",
                    "请先执行确定性迁移；存在歧义时返回 Workflow 人工处理", "workflow");
        }
        List<BomRecipe> family = source.getBomFamilyId() == null
                ? List.of(source)
                : recipeRepo.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                        factoryId, source.getBomFamilyId()).stream()
                        .filter(member -> Objects.equals(source.getWorkflowRevisionId(), member.getWorkflowRevisionId()))
                        .filter(member -> member.getStatus() == source.getStatus())
                        .toList();
        for (BomRecipe member : family) {
            assertVersionCapacity(factoryId, member.getProductTypeId());
        }
        String newFamilyId = UUID.randomUUID().toString();
        List<BomRecipe> ordered = family.stream()
                .sorted(Comparator.comparing((BomRecipe member) ->
                        member.getOutputRole() != BomRecipe.OutputRole.MAIN))
                .toList();
        BomRecipe mainClone = null;
        Map<String, BomRecipe> clonesBySourceId = new HashMap<>();
        for (BomRecipe member : ordered) {
            String sharedCloneId = mainClone == null ? null : mainClone.getId();
            BomRecipe clone = cloneRecipeInternal(factoryId, member, newFamilyId, sharedCloneId);
            if (member.getOutputRole() == BomRecipe.OutputRole.MAIN || ordered.size() == 1) {
                mainClone = clone;
                clone.setSharedRecipeId(clone.getId());
                recipeRepo.save(clone);
            }
            clonesBySourceId.put(member.getId(), clone);
        }
        BomRecipe selected = clonesBySourceId.get(source.getId());
        recomputeFamilyCosts(selected);
        return selected;
    }

    @Override
    @Transactional
    public BomRecipe upgradeWorkflowRevision(String factoryId, String recipeId) {
        BomRecipe source = loadRecipe(factoryId, recipeId);
        BomRecipe editable = source.getStatus() == BomRecipe.Status.DRAFT
                ? source : cloneRecipe(factoryId, recipeId);
        List<BomRecipe> family = editable.getBomFamilyId() == null
                ? List.of(editable)
                : recipeRepo.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                        factoryId, editable.getBomFamilyId(), BomRecipe.Status.DRAFT);
        for (BomRecipe member : family) {
            bomWorkflowRevisionService.upgradeToLatestCompatibleDraft(factoryId, member);
        }
        reconcileUpgradedInputSkeletons(factoryId, family);
        validateFamilyContracts(factoryId, family);
        recomputeFamilyCosts(editable);
        return recipeRepo.findById(editable.getId()).orElse(editable);
    }

    @Override
    @Transactional(readOnly = true)
    public BomFamilyOutputCostingResponse getFamilyOutputCosting(
            String factoryId, String recipeId) {
        BomRecipe reference = loadRecipe(factoryId, recipeId);
        return buildFamilyOutputCostingResponse(familyForStatus(reference));
    }

    @Override
    @Transactional
    public BomFamilyOutputCostingResponse updateFamilyOutputCosting(
            String factoryId,
            String recipeId,
            UpdateBomFamilyOutputCostingRequest request) {
        BomRecipe reference = loadRecipe(factoryId, recipeId);
        List<BomRecipe> family = familyForStatus(reference);
        if (family.stream().anyMatch(member -> member.getStatus() != BomRecipe.Status.DRAFT)) {
            throw bomError(409,
                    "已生效或历史 BOM 的产出成本配置不可修改",
                    "BOM_FAMILY_OUTPUT_COSTING_READ_ONLY",
                    "请先克隆为新版本再修改",
                    "bomVersions");
        }
        Map<String, UpdateBomFamilyOutputCostingRequest.OutputCostingInput> requested =
                request.getOutputs().stream().collect(java.util.stream.Collectors.toMap(
                        UpdateBomFamilyOutputCostingRequest.OutputCostingInput::getRecipeId,
                        item -> item,
                        (left, right) -> {
                            throw bomError(400,
                                    "同一产出不能重复提交",
                                    "BOM_FAMILY_OUTPUT_DUPLICATE",
                                    "请刷新产出成本配置后重试",
                                    "outputs");
                        }));
        Set<String> familyIds = family.stream().map(BomRecipe::getId).collect(
                java.util.stream.Collectors.toSet());
        if (!familyIds.equals(requested.keySet())) {
            throw bomError(409,
                    "产出成本配置必须一次覆盖当前 BOM Family 的全部产出",
                    "BOM_FAMILY_OUTPUT_SET_MISMATCH",
                    "请刷新页面后重新填写，不能新增、遗漏或替换产出",
                    "outputs");
        }
        for (BomRecipe member : family) {
            BigDecimal nrv = requested.get(member.getId()).getByproductNrvUnitPrice();
            if (member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT) {
                if (nrv == null || nrv.compareTo(BigDecimal.ZERO) <= 0) {
                    throw bomError(400,
                            "副产品单位可变现净值必须大于 0",
                            "BOM_BY_PRODUCT_NRV_REQUIRED",
                            "请填写预计售价扣除后续加工、销售费用后的单位净值",
                            "byproductNrvUnitPrice");
                }
                member.setByproductNrvUnitPrice(nrv);
            } else {
                if (nrv != null) {
                    throw bomError(400,
                            "主产出和联产品不使用副产品净值抵扣",
                            "BOM_NON_BY_PRODUCT_NRV_FORBIDDEN",
                            "仅在副产品行填写单位可变现净值",
                            "byproductNrvUnitPrice");
                }
                member.setByproductNrvUnitPrice(null);
            }
            recipeRepo.save(member);
        }
        recomputeFamilyCosts(reference);
        return buildFamilyOutputCostingResponse(familyForStatus(reference));
    }

    private BomFamilyOutputCostingResponse buildFamilyOutputCostingResponse(
            List<BomRecipe> family) {
        BomFamilyOutputCostingResponse response = new BomFamilyOutputCostingResponse();
        response.setBomFamilyId(family.isEmpty() ? null : family.get(0).getBomFamilyId());
        response.setEditable(!family.isEmpty() && family.stream()
                .allMatch(member -> member.getStatus() == BomRecipe.Status.DRAFT));
        response.setOutputs(family.stream()
                .sorted(Comparator.comparing((BomRecipe member) ->
                                member.getOutputRole() != BomRecipe.OutputRole.MAIN)
                        .thenComparing(
                                BomRecipe::getProductName,
                                Comparator.nullsLast(String::compareTo)))
                .map(member -> new BomFamilyOutputCostingResponse.OutputCosting(
                        member.getId(),
                        member.getProductTypeId(),
                        member.getProductName(),
                        member.getOutputRole(),
                        member.getCostAllocationRatio(),
                        member.getOutputQuantityPerUnit(),
                        member.getOutputUnit(),
                        member.getByproductNrvUnitPrice()))
                .toList());
        return response;
    }

    private BomRecipe cloneRecipeInternal(String factoryId, BomRecipe source) {
        String familyId = UUID.randomUUID().toString();
        BomRecipe clone = cloneRecipeInternal(factoryId, source, familyId, null);
        clone.setSharedRecipeId(clone.getId());
        clone = recipeRepo.save(clone);
        recomputeFamilyCosts(clone);
        return clone;
    }

    private BomRecipe cloneRecipeInternal(
            String factoryId, BomRecipe source, String familyId, String sharedRecipeId) {
        Integer maxVersion = recipeRepo.findMaxVersion(factoryId, source.getProductTypeId());

        BomRecipe clone = new BomRecipe();
        clone.setId(UUID.randomUUID().toString());
        clone.setFactoryId(factoryId);
        clone.setRecipeCode(generateRecipeCode(factoryId));
        clone.setProductTypeId(source.getProductTypeId());
        clone.setProductName(source.getProductName());
        clone.setVersion(maxVersion + 1);
        clone.setIsCurrent(false);  // clone 不自动 current, 用户激活后才是
        clone.setOverallYieldRate(source.getOverallYieldRate());
        clone.setOutputQuantityPerUnit(source.getOutputQuantityPerUnit());
        clone.setOutputUnit(source.getOutputUnit());
        clone.setNetContentQuantity(source.getNetContentQuantity());
        clone.setNetContentUnit(source.getNetContentUnit());
        clone.setStatus(BomRecipe.Status.DRAFT);
        clone.setSourceType(BomRecipe.SourceType.MANUAL);
        clone.setNotes("克隆自 " + source.getRecipeCode() + " (v" + source.getVersion() + ")");
        clone.setWorkflowRevisionId(source.getWorkflowRevisionId());
        clone.setWorkflowId(source.getWorkflowId());
        clone.setWorkflowDefinitionVersion(source.getWorkflowDefinitionVersion());
        clone.setWorkflowRevisionHash(source.getWorkflowRevisionHash());
        clone.setWorkflowSchemaVersion(source.getWorkflowSchemaVersion());
        clone.setWorkflowNodesSnapshotJson(source.getWorkflowNodesSnapshotJson());
        clone.setWorkflowEdgesSnapshotJson(source.getWorkflowEdgesSnapshotJson());
        clone.setBomFamilyId(familyId);
        clone.setSharedRecipeId(sharedRecipeId);
        clone.setTargetTerminalNodeId(source.getTargetTerminalNodeId());
        clone.setOutputRole(source.getOutputRole());
        clone.setCostAllocationRatio(source.getCostAllocationRatio());
        clone.setByproductNrvUnitPrice(source.getByproductNrvUnitPrice());
        clone = recipeRepo.save(clone);

        // Copy items.
        List<BomRecipeItem> sourceItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(source.getId());
        List<BomRecipeItem> clonedItems = new ArrayList<>();
        for (BomRecipeItem src : sourceItems) {
            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(clone.getId());
            item.setFactoryId(factoryId);
            item.setMaterialTypeId(src.getMaterialTypeId());
            item.setMaterialName(src.getMaterialName());
            item.setStandardQuantity(src.getStandardQuantity());
            item.setYieldRate(src.getYieldRate());
            item.setActualQuantity(src.getActualQuantity());
            item.setUnit(src.getUnit());
            item.setUnitPrice(src.getUnitPrice());
            item.setPriceUnit(src.getPriceUnit() != null ? src.getPriceUnit() : src.getUnit());
            item.setQuantityToPriceFactor(src.getQuantityToPriceFactor() != null
                    ? src.getQuantityToPriceFactor() : BigDecimal.ONE);
            item.setTaxRate(src.getTaxRate());
            item.setItemCost(src.getItemCost());
            item.setMaterialCategory(src.getMaterialCategory());
            item.setSortOrder(src.getSortOrder());
            item.setIsOptional(src.getIsOptional());
            item.setSubstituteGroup(src.getSubstituteGroup());
            item.setRemark(src.getRemark());
            // SP4-T3: propagate per_portion + semi_finished_ref_code on clone
            item.setPerPortion(src.getPerPortion() != null ? src.getPerPortion() : false);
            item.setSemiFinishedRefCode(src.getSemiFinishedRefCode());
            item.setSubProductTypeId(src.getSubProductTypeId());
            item.setPrimaryCode(src.getPrimaryCode());
            item.setPrimaryCodeRef(src.getPrimaryCodeRef());
            item.setPackagingSpecId(src.getPackagingSpecId());
            item.setPackagingSpecNameSnapshot(src.getPackagingSpecNameSnapshot());
            item.setPackagingRole(src.getPackagingRole());
            item.setNaturalQuantity(src.getNaturalQuantity());
            item.setNaturalUnit(src.getNaturalUnit());
            item.setPackagingPackageUnitSnapshot(src.getPackagingPackageUnitSnapshot());
            item.setPackagingBaseUnitSnapshot(src.getPackagingBaseUnitSnapshot());
            item.setPackagingConversionFactorSnapshot(src.getPackagingConversionFactorSnapshot());
            item.setWorkflowMaterialNodeId(src.getWorkflowMaterialNodeId());
            item.setWorkflowInputPortId(src.getWorkflowInputPortId());
            item.setWorkflowEdgeId(src.getWorkflowEdgeId());
            item.setCostScope(src.getCostScope());
            item.setCostScopeKey(src.getCostScopeKey());
            clonedItems.add(item);
        }
        itemRepo.saveAll(clonedItems);
        Map<Long, Long> clonedRecipeItemIds = new HashMap<>();
        for (int i = 0; i < sourceItems.size(); i++) {
            clonedRecipeItemIds.put(sourceItems.get(i).getId(), clonedItems.get(i).getId());
        }
        // IMPORTANT: keep same Hibernate PersistentBag reference (clear+addAll, not setItems).
        clone.getItems().clear();
        clone.getItems().addAll(clonedItems);

        // Clone canonical binding-level seasoning rules and injection configs.
        List<BomSeasoningItem> sourceSeasoning = seasoningItemRepo.findByRecipeIdOrderBySeqAsc(source.getId());
        List<BomSeasoningItem> clonedSeasoning = new ArrayList<>();
        for (BomSeasoningItem s : sourceSeasoning) {
            BomSeasoningItem cs = new BomSeasoningItem();
            cs.setRecipeId(clone.getId());
            cs.setFactoryId(factoryId);
            cs.setMaterialTypeId(s.getMaterialTypeId());
            cs.setSection(s.getSection());
            cs.setSeq(s.getSeq());
            cs.setName(s.getName());
            cs.setDosagePerKgG(s.getDosagePerKgG());
            cs.setPriceSource1(s.getPriceSource1());
            cs.setPriceSource2(s.getPriceSource2());
            cs.setCountInSeasoning(s.getCountInSeasoning());
            cs.setRemark(s.getRemark());
            cs.setWorkProcessId(s.getWorkProcessId()); // 调料配方按工序 (2026-07-13): 保工序分配
            cs.setWorkflowProcessNodeId(s.getWorkflowProcessNodeId());
            cs.setCostScope(s.getCostScope());
            cs.setCostScopeKey(s.getCostScopeKey());
            cs.setSubsequentPotRatio(s.getSubsequentPotRatio());
            clonedSeasoning.add(cs);
        }
        seasoningItemRepo.saveAll(clonedSeasoning);
        Map<Long, Long> clonedSeasoningItemIds = new HashMap<>();
        for (int i = 0; i < sourceSeasoning.size(); i++) {
            clonedSeasoningItemIds.put(sourceSeasoning.get(i).getId(), clonedSeasoning.get(i).getId());
        }
        substituteService.cloneRelations(
                factoryId,
                source.getId(),
                clone.getId(),
                clonedRecipeItemIds,
                clonedSeasoningItemIds);

        List<BomProcessInjectionConfig> clonedConfigs = new ArrayList<>();
        for (BomProcessInjectionConfig p : processInjectionConfigRepo
                .findByRecipeIdAndDeletedAtIsNull(source.getId())) {
            BomProcessInjectionConfig cp = new BomProcessInjectionConfig();
            cp.setRecipeId(clone.getId());
            cp.setFactoryId(factoryId);
            cp.setWorkProcessId(p.getWorkProcessId());
            cp.setInjectionAmountKg(p.getInjectionAmountKg());
            cp.setNotes(p.getNotes());
            clonedConfigs.add(cp);
        }
        processInjectionConfigRepo.saveAll(clonedConfigs);

        return recipeRepo.save(clone);
    }

    private BomRecipe initializeFamilyAndInputSkeletons(
            String factoryId,
            BomRecipe requested,
            BomWorkflowRevisionService.WorkflowBinding requestedBinding) {
        List<BomWorkflowRevisionService.TerminalOutput> outputs = requestedBinding.terminalOutputs();
        String familyId = UUID.randomUUID().toString();
        Map<String, BomRecipe> membersByProduct = new HashMap<>();
        Map<String, BomWorkflowRevisionService.WorkflowBinding> bindingsByProduct = new HashMap<>();
        membersByProduct.put(requested.getProductTypeId(), requested);
        bindingsByProduct.put(requested.getProductTypeId(), requestedBinding);

        for (BomWorkflowRevisionService.TerminalOutput output : outputs) {
            if (membersByProduct.containsKey(output.productTypeId())) continue;
            List<BomRecipe> siblingDrafts = recipeRepo
                    .findByFactoryIdAndProductTypeIdOrderByVersionDesc(factoryId, output.productTypeId()).stream()
                    .filter(recipe -> recipe.getStatus() == BomRecipe.Status.DRAFT)
                    .toList();
            if (!siblingDrafts.isEmpty()) {
                throw bomError(409,
                        "联产 SKU " + output.productTypeId() + " 已有独立 BOM 草稿，无法安全合并为同一 Family",
                        "BOM_FAMILY_EXISTING_DRAFT_CONFLICT",
                        "请先保留或归档冲突草稿，再重新创建联产 BOM", "bomVersions");
            }
            ProductType product = loadProductForUpdate(factoryId, output.productTypeId());
            validateProductOutputMetadata(product);
            assertVersionCapacity(factoryId, output.productTypeId());
            BomRecipe member = createBareDraft(factoryId, product, "由联产 Workflow 自动创建");
            BomWorkflowRevisionService.WorkflowBinding memberBinding =
                    bomWorkflowRevisionService.bindExactRevision(
                            factoryId, member, requestedBinding.revision().getId());
            membersByProduct.put(output.productTypeId(), member);
            bindingsByProduct.put(output.productTypeId(), memberBinding);
        }

        BomRecipe main = outputs.stream()
                .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN)
                .map(output -> membersByProduct.get(output.productTypeId()))
                .findFirst()
                .orElseThrow(() -> bomError(409, "联产 Workflow 缺少主产出",
                        "BOM_FAMILY_MAIN_REQUIRED", "请回到 Workflow 配置唯一主产出", "workflow"));
        for (BomRecipe member : membersByProduct.values()) {
            member.setBomFamilyId(familyId);
            member.setSharedRecipeId(main.getId());
            recipeRepo.save(member);
        }
        recipeRepo.flush();

        Map<String, List<BomWorkflowRevisionService.InputSlot>> slotsByRecipe = new LinkedHashMap<>();
        bindingsByProduct.forEach((productTypeId, binding) -> {
            List<BomWorkflowRevisionService.InputSlot> slots =
                    BomWorkflowRevisionService.resolveInputSlots(binding.graph());
            slotsByRecipe.put(membersByProduct.get(productTypeId).getId(), slots);
        });

        Map<String, List<BomRecipeItem>> skeletonsByOwner = new LinkedHashMap<>();
        for (InputCostProfile profile : resolveInputCostProfiles(
                new ArrayList<>(membersByProduct.values()), slotsByRecipe, main)) {
            List<BomRecipeItem> ownerItems = skeletonsByOwner.computeIfAbsent(
                    profile.owner().getId(), ignored -> new ArrayList<>());
            ownerItems.add(createInputSkeleton(
                    factoryId,
                    profile.owner(),
                    profile.slot(),
                    profile.costScope(),
                    profile.costScopeKey(),
                    ownerItems.size()));
        }
        for (BomRecipe member : membersByProduct.values()) {
            List<BomRecipeItem> skeletons =
                    skeletonsByOwner.getOrDefault(member.getId(), List.of());
            if (!skeletons.isEmpty()) itemRepo.saveAll(skeletons);
            member.getItems().clear();
            member.getItems().addAll(skeletons);
            recipeRepo.save(member);
        }
        recomputeFamilyCosts(requested);
        return recipeRepo.findById(requested.getId()).orElse(requested);
    }

    private String slotKey(BomWorkflowRevisionService.InputSlot slot) {
        return slot.materialNodeId() + "\u0000"
                + slot.inputPortId() + "\u0000" + slot.edgeId();
    }

    /**
     * Preserve every mapped business row during an explicit Workflow upgrade and
     * append skeletons for genuinely new stable input slots. Moving an existing
     * row between family-shared and output-exclusive ownership is not guessed:
     * it requires an explicit topology correction before retrying the upgrade.
     */
    private void reconcileUpgradedInputSkeletons(String factoryId, List<BomRecipe> family) {
        BomRecipe main = family.stream()
                .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.MAIN)
                .findFirst()
                .orElseThrow(() -> bomError(409,
                        "升级后的 BOM Family 缺少主产出",
                        "BOM_FAMILY_MAIN_REQUIRED",
                        "请回到 Workflow 配置唯一主产出",
                        "workflow"));
        Map<String, List<BomWorkflowRevisionService.InputSlot>> slotsByRecipe = new LinkedHashMap<>();
        for (BomRecipe member : family) {
            PinnedWorkflowGraph graph = bomWorkflowRevisionService.resolvePinnedGraph(factoryId, member);
            List<BomWorkflowRevisionService.InputSlot> slots =
                    BomWorkflowRevisionService.resolveInputSlots(graph);
            slotsByRecipe.put(member.getId(), slots);
        }

        Map<String, BomRecipeItem> existingBySlot = new HashMap<>();
        for (BomRecipe member : family) {
            for (BomRecipeItem item : itemRepo.findByRecipeIdOrderBySortOrderAsc(member.getId())) {
                if (!hasText(item.getWorkflowMaterialNodeId())
                        || !hasText(item.getWorkflowInputPortId())
                        || !hasText(item.getWorkflowEdgeId())) {
                    continue;
                }
                String key = stableItemKey(item);
                if (existingBySlot.putIfAbsent(key, item) != null) {
                    throw bomError(409,
                            "升级时发现同一 Workflow 投入槽存在多条 BOM 明细",
                            "BOM_WORKFLOW_UPGRADE_SLOT_AMBIGUOUS",
                            "请先保留每个稳定投入槽唯一一条主料规则",
                            "bomItems");
                }
            }
        }

        Map<String, List<BomRecipeItem>> additionsByOwner = new LinkedHashMap<>();
        for (InputCostProfile profile : resolveInputCostProfiles(family, slotsByRecipe, main)) {
            BomRecipeItem existing = existingBySlot.get(slotKey(profile.slot()));
            if (existing != null) {
                existing.setCostScope(profile.costScope());
                existing.setCostScopeKey(profile.costScopeKey());
                itemRepo.save(existing);
                continue;
            }
            List<BomRecipeItem> additions = additionsByOwner.computeIfAbsent(
                    profile.owner().getId(), ignored -> new ArrayList<>());
            BomRecipeItem skeleton = createInputSkeleton(
                    factoryId,
                    profile.owner(),
                    profile.slot(),
                    profile.costScope(),
                    profile.costScopeKey(),
                    itemRepo.findByRecipeIdOrderBySortOrderAsc(profile.owner().getId()).size()
                            + additions.size());
            additions.add(skeleton);
            existingBySlot.put(slotKey(profile.slot()), skeleton);
        }
        for (BomRecipe member : family) {
            List<BomRecipeItem> additions =
                    additionsByOwner.getOrDefault(member.getId(), List.of());
            if (!additions.isEmpty()) itemRepo.saveAll(additions);
            refreshItemsInPlace(member);
        }
    }

    private List<InputCostProfile> resolveInputCostProfiles(
            List<BomRecipe> family,
            Map<String, List<BomWorkflowRevisionService.InputSlot>> slotsByRecipe,
            BomRecipe main) {
        Map<String, BomWorkflowRevisionService.InputSlot> representativeSlots = new LinkedHashMap<>();
        Map<String, LinkedHashSet<BomRecipe>> targetsBySlot = new LinkedHashMap<>();
        for (BomRecipe member : family) {
            for (BomWorkflowRevisionService.InputSlot slot :
                    slotsByRecipe.getOrDefault(member.getId(), List.of())) {
                String key = slotKey(slot);
                representativeSlots.putIfAbsent(key, slot);
                targetsBySlot.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(member);
            }
        }
        List<InputCostProfile> profiles = new ArrayList<>();
        for (Map.Entry<String, BomWorkflowRevisionService.InputSlot> entry :
                representativeSlots.entrySet()) {
            List<BomRecipe> targets = targetsBySlot.get(entry.getKey()).stream()
                    .sorted(Comparator.comparing(BomRecipe::getTargetTerminalNodeId))
                    .toList();
            String costScope = targets.size() == family.size()
                    ? "SHARED"
                    : targets.size() == 1 ? "OUTPUT_EXCLUSIVE" : "OUTPUT_GROUP";
            BomRecipe owner = "SHARED".equals(costScope) ? main : targets.get(0);
            String costScopeKey = BomWorkflowRevisionService.canonicalCostScopeKey(
                    targets.stream().map(BomRecipe::getTargetTerminalNodeId).toList());
            profiles.add(new InputCostProfile(
                    entry.getValue(), targets, owner, costScope, costScopeKey));
        }
        profiles.sort(Comparator.comparingInt(profile -> profile.slot().order()));
        return profiles;
    }

    private String stableItemKey(BomRecipeItem item) {
        return item.getWorkflowMaterialNodeId() + "\u0000"
                + item.getWorkflowInputPortId() + "\u0000"
                + item.getWorkflowEdgeId();
    }

    private BomRecipe createBareDraft(String factoryId, ProductType product, String notes) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(UUID.randomUUID().toString());
        recipe.setFactoryId(factoryId);
        recipe.setRecipeCode(generateRecipeCode(factoryId));
        recipe.setProductTypeId(product.getId());
        recipe.setProductName(product.getName());
        recipe.setVersion(recipeRepo.findMaxVersion(factoryId, product.getId()) + 1);
        recipe.setIsCurrent(false);
        recipe.setOverallYieldRate(null);
        applyProductOutputSnapshot(recipe, product);
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setSourceType(BomRecipe.SourceType.MANUAL);
        recipe.setNotes(notes);
        return recipeRepo.saveAndFlush(recipe);
    }

    private BomRecipeItem createInputSkeleton(
            String factoryId,
            BomRecipe recipe,
            BomWorkflowRevisionService.InputSlot slot,
            String costScope,
            String costScopeKey,
            int sortOrder) {
        RawMaterialType material = materialTypeRepo.findById(slot.materialTypeId())
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> bomError(409,
                        "Workflow 原料入口未绑定当前工厂的有效原料档案: " + slot.materialTypeId(),
                        "BOM_WORKFLOW_INPUT_MATERIAL_INVALID",
                        "请回到 Workflow 修复该原料 Cell 后重新保存草稿", slot.materialNodeId()));
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipe.getId());
        item.setFactoryId(factoryId);
        item.setMaterialTypeId(material.getId());
        item.setMaterialName(material.getName());
        item.setStandardQuantity(null);
        item.setActualQuantity(null);
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnit(slot.unit() == null ? material.getUnit() : canonicalUnitOrThrow(
                factoryId, slot.unit(), "workflowInputUnit"));
        applyMaterialMasterPricing(item, material);
        item.setMaterialCategory(materialCategoryForSkeleton(material));
        item.setSortOrder(sortOrder);
        item.setIsOptional(false);
        item.setPerPortion(false);
        item.setWorkflowMaterialNodeId(slot.materialNodeId());
        item.setWorkflowInputPortId(slot.inputPortId());
        item.setWorkflowEdgeId(slot.edgeId());
        item.setCostScope(costScope);
        item.setCostScopeKey(costScopeKey);
        applyPrimaryCode(new BomRecipeItemDTO(), item, material);
        return item;
    }

    private String materialCategoryForSkeleton(RawMaterialType material) {
        String category = material.getCategory() == null ? "" : material.getCategory().trim();
        if ("PACKAGING".equalsIgnoreCase(category) || "包材".equals(category)) {
            return "PACKAGING";
        }
        if (Set.of("AUXILIARY", "SEASONING", "辅料", "调料", "调味料").stream()
                .anyMatch(value -> value.equalsIgnoreCase(category))) {
            return "AUXILIARY";
        }
        return "RAW";
    }

    private void assertPinnedDraft(String factoryId, BomRecipe recipe) {
        if (recipe.getWorkflowRevisionHash() == null) {
            throw bomError(409, "BOM 草稿尚未自动关联已保存的 Workflow 修订",
                    "BOM_WORKFLOW_REVISION_REQUIRED",
                    "请先保存结构完整的 Workflow 草稿，再重新进入 BOM", "workflow");
        }
        bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
    }

    private List<BomRecipe> activationFamily(String factoryId, BomRecipe recipe) {
        if (recipe.getBomFamilyId() == null) return List.of(recipe);
        List<BomRecipe> drafts = recipeRepo.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                factoryId, recipe.getBomFamilyId(), BomRecipe.Status.DRAFT);
        if (drafts.stream().noneMatch(member -> member.getId().equals(recipe.getId()))) {
            throw bomError(409, "当前 BOM 不属于可激活的草稿 Family",
                    "BOM_FAMILY_DRAFT_REQUIRED", "请克隆完整 Family 后再激活", "bomFamily");
        }
        return drafts;
    }

    private void validateFamilyContracts(String factoryId, List<BomRecipe> family) {
        if (family.isEmpty()) {
            throw bomError(409, "BOM Family 为空", "BOM_FAMILY_EMPTY",
                    "请重新创建 BOM 草稿", "bomFamily");
        }
        BomRecipe reference = family.getFirst();
        if (reference.getWorkflowRevisionId() == null) {
            throw bomError(409, "BOM Family 尚未固定 Workflow 修订",
                    "BOM_WORKFLOW_REVISION_REQUIRED", "请重新创建 BOM 草稿", "workflow");
        }
        List<BomWorkflowRevisionService.TerminalOutput> outputs =
                bomWorkflowRevisionService.resolvePinnedTerminalOutputs(factoryId, reference);
        Map<String, BomRecipe> byProduct = family.stream().collect(java.util.stream.Collectors.toMap(
                BomRecipe::getProductTypeId,
                member -> member,
                (left, right) -> {
                    throw bomError(409, "同一 BOM Family 存在重复终端 SKU",
                            "BOM_FAMILY_DUPLICATE_OUTPUT", "请归档冲突草稿", "bomFamily");
                }));
        if (byProduct.size() != outputs.size()) {
            throw bomError(409, "BOM Family 的终端 Output Recipe 不完整",
                    "BOM_FAMILY_OUTPUTS_INCOMPLETE",
                    "请补齐 Workflow 要求的所有终端 SKU 配方", "bomFamily");
        }
        BigDecimal ratioTotal = BigDecimal.ZERO;
        int mainCount = 0;
        BomRecipe main = null;
        for (BomWorkflowRevisionService.TerminalOutput output : outputs) {
            BomRecipe member = byProduct.get(output.productTypeId());
            if (member == null
                    || !Objects.equals(reference.getWorkflowRevisionId(), member.getWorkflowRevisionId())
                    || !Objects.equals(reference.getWorkflowRevisionHash(), member.getWorkflowRevisionHash())
                    || !Objects.equals(output.terminalNodeId(), member.getTargetTerminalNodeId())
                    || output.outputRole() != member.getOutputRole()
                    || member.getCostAllocationRatio() == null
                    || output.costAllocationRatio().compareTo(member.getCostAllocationRatio()) != 0) {
                throw bomError(409, "终端 SKU " + output.productTypeId() + " 的工艺身份或分摊规则不一致",
                        "BOM_FAMILY_OUTPUT_CONTRACT_INVALID",
                        "请使用同一 Workflow 修订重新创建完整 Family", output.productTypeId());
            }
            ratioTotal = ratioTotal.add(member.getCostAllocationRatio());
            if (member.getOutputRole() == BomRecipe.OutputRole.MAIN) {
                mainCount++;
                main = member;
            }
        }
        if (mainCount != 1 || ratioTotal.compareTo(new BigDecimal("100")) != 0) {
            throw bomError(409, "BOM Family 必须有且仅有一个主产出，成本分摊合计必须为100%",
                    "BOM_FAMILY_ALLOCATION_INVALID",
                    "请回到 Workflow 修正多产出角色和分摊比例", "costAllocationRatio");
        }
        for (BomRecipe member : family) {
            if (!Objects.equals(main.getId(), member.getSharedRecipeId())) {
                throw bomError(409, "BOM Family 的共享配方来源不一致",
                        "BOM_FAMILY_SHARED_RECIPE_INVALID",
                        "请重新克隆完整 BOM Family", "sharedRecipe");
            }
            validateInputSlotCoverage(factoryId, member);
        }
    }

    private void validateInputSlotCoverage(String factoryId, BomRecipe member) {
        List<BomWorkflowRevisionService.InputSlot> slots = BomWorkflowRevisionService.resolveInputSlots(
                bomWorkflowRevisionService.resolvePinnedGraph(factoryId, member));
        List<BomRecipe> family = familyForStatus(member);
        Map<String, BomRecipe> owners = family.stream().collect(java.util.stream.Collectors.toMap(
                BomRecipe::getId, candidate -> candidate));
        List<BomRecipeItem> available = family.stream()
                .flatMap(candidate -> itemRepo.findByRecipeIdOrderBySortOrderAsc(candidate.getId()).stream())
                .filter(item -> costRowAppliesTo(
                        item.getCostScope(),
                        item.getCostScopeKey(),
                        owners.get(item.getRecipeId()),
                        member,
                        family,
                        "PACKAGING".equalsIgnoreCase(item.getMaterialCategory())))
                .toList();
        for (BomWorkflowRevisionService.InputSlot slot : slots) {
            long matches = available.stream()
                    .filter(item -> Objects.equals(slot.materialNodeId(), item.getWorkflowMaterialNodeId()))
                    .filter(item -> Objects.equals(slot.inputPortId(), item.getWorkflowInputPortId()))
                    .filter(item -> Objects.equals(slot.edgeId(), item.getWorkflowEdgeId()))
                    .count();
            if (matches != 1) {
                throw bomError(409,
                        "Workflow 投入槽 " + slot.processNodeId() + "/" + slot.inputPortId()
                                + " 必须且只能配置一条主料关系",
                        "BOM_WORKFLOW_INPUT_SLOT_INCOMPLETE",
                        "请按系统生成的投入槽配置主料及替代料", slot.inputPortId());
            }
        }
    }

    @Override
    @Transactional
    public BomRecipe archiveRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() == BomRecipe.Status.ARCHIVED) {
            return recipe;
        }
        recipe.setStatus(BomRecipe.Status.ARCHIVED);
        recipe.setIsCurrent(false);
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public void deleteRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() == BomRecipe.Status.ACTIVE) {
            throw new IllegalStateException("当前生效的 BOM 不能删除，请先激活其他版本");
        }
        recipe.softDelete();
        recipeRepo.save(recipe);
    }

    private ProductType loadProductForUpdate(String factoryId, String productTypeId) {
        return productTypeRepo.findByIdAndFactoryIdForUpdate(productTypeId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("产品不存在: " + productTypeId));
    }

    private void validateProductOutputMetadata(ProductType product) {
        if (product.getUnit() == null || product.getUnit().isBlank()) {
            throw bomError(409,
                    "SKU 未配置产出单位，不能创建或激活 BOM",
                    "BOM_SKU_UNIT_REQUIRED",
                    "请先在产品档案中填写基本单位",
                    "productUnit");
        }
        BigDecimal netContent = product.getNetContentQuantity() != null
                ? product.getNetContentQuantity() : product.getGramsPerUnit();
        String netContentUnit = product.getNetContentUnit() != null
                ? product.getNetContentUnit() : (product.getGramsPerUnit() == null ? null : "g");
        if (netContent == null || netContent.compareTo(BigDecimal.ZERO) <= 0 || netContentUnit == null) {
            throw bomError(409,
                    "SKU 未配置有效净含量，不能创建或激活 BOM",
                    "BOM_SKU_NET_CONTENT_REQUIRED",
                    "请先在产品档案中填写大于 0 的净含量及单位",
                    "netContentQuantity");
        }
    }

    private void applyProductOutputSnapshot(BomRecipe recipe, ProductType product) {
        UnitNormalizationResult outputUnit = unitContractService.normalize(product.getFactoryId(), product.getUnit());
        if (!outputUnit.recognized()) {
            throw bomError(409, "SKU 基本单位无法识别，不能创建或激活 BOM",
                    "BOM_SKU_UNIT_UNKNOWN", "请修正 SKU 基本单位", "productUnit");
        }
        recipe.setOutputQuantityPerUnit(BigDecimal.ONE);
        recipe.setOutputUnit(outputUnit.code());
        if (product.getNetContentQuantity() != null && product.getNetContentUnit() != null) {
            recipe.setNetContentQuantity(product.getNetContentQuantity());
            recipe.setNetContentUnit(product.getNetContentUnit());
        } else {
            recipe.setNetContentQuantity(product.getGramsPerUnit());
            recipe.setNetContentUnit(product.getGramsPerUnit() == null ? null : "g");
        }
    }

    private void validateRecipeOutputContract(String factoryId, BomRecipe recipe, ProductType product) {
        if (recipe.getOutputQuantityPerUnit() == null
                || recipe.getOutputQuantityPerUnit().compareTo(BigDecimal.ONE) != 0) {
            throw bomError(409,
                    "BOM 每单位产出必须是 1 个 SKU 基本单位，不能使用净含量作为产出数量",
                    "BOM_OUTPUT_QUANTITY_MISMATCH",
                    "请重新保存当前草稿，使每单位产出恢复为 1 " + product.getUnit(),
                    "outputQuantityPerUnit");
        }
        if (!unitContractService.areEquivalent(factoryId, recipe.getOutputUnit(), product.getUnit())) {
            throw bomError(409, "BOM 产出单位与 SKU 基本单位不一致",
                    "BOM_OUTPUT_UNIT_MISMATCH", "请按 SKU 基本单位保存 BOM", "outputUnit");
        }
        if (recipe.getNetContentQuantity() == null
                || recipe.getNetContentQuantity().compareTo(BigDecimal.ZERO) <= 0
                || recipe.getNetContentUnit() == null || recipe.getNetContentUnit().isBlank()) {
            throw bomError(409, "BOM 缺少可追溯的 SKU 净含量快照",
                    "BOM_NET_CONTENT_SNAPSHOT_REQUIRED", "请重新保存当前草稿后再激活", "netContentQuantity");
        }
    }

    private void validateActivatableItems(BomRecipe recipe) {
        List<BomRecipeItem> items = itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        if (items.isEmpty()) {
            throw bomError(409,
                    "BOM 还没有任何原辅料或包材，不能激活",
                    "BOM_ACTIVATION_ITEMS_REQUIRED",
                    "请至少添加一条原料、辅料或包材明细",
                    "bomItems");
        }
        for (BomRecipeItem item : items) {
            if (item.getMaterialTypeId() == null || item.getMaterialTypeId().isBlank()) {
                throw bomError(409,
                        "BOM 明细存在未关联物料的行，不能激活",
                        "BOM_ACTIVATION_MATERIAL_REQUIRED",
                        "请为每一行选择有效物料",
                        "bomItems");
            }
            boolean packagingMaterial = "PACKAGING".equalsIgnoreCase(item.getMaterialCategory());
            boolean invalidQuantity = item.getStandardQuantity() != null
                    && item.getStandardQuantity().compareTo(BigDecimal.ZERO) <= 0;
            // 原料与工序辅料的 BOM 行表达资格/关系，固定用量可留空；
            // 包材是确定性消耗，激活前必须有正数用量。
            boolean missingRequiredQuantity = packagingMaterial && item.getStandardQuantity() == null;
            if (invalidQuantity || missingRequiredQuantity) {
                String category = packagingMaterial ? "包材" : "原料/辅料";
                throw bomError(409,
                        category + "明细「" + displayItemName(item) + "」缺少有效数量，不能激活",
                        "BOM_ACTIVATION_QUANTITY_REQUIRED",
                        packagingMaterial
                                ? "请填写该包装规格下大于 0 的固定包材用量"
                                : "参考用量如填写必须大于 0；实际投料以生产计划和报工为准",
                        "standardQuantity");
            }
            if (item.getUnit() == null || item.getUnit().isBlank()) {
                throw bomError(409,
                        "BOM 明细「" + displayItemName(item) + "」缺少单位，不能激活",
                        "BOM_ACTIVATION_ITEM_UNIT_REQUIRED",
                        "请为该明细选择计量单位",
                        "unit");
            }
        }
        // Do not replace the Hibernate-managed orphanRemoval collection here.
        // The activation transaction only validates rows; replacing PersistentBag
        // causes a flush-time 500 even though every business condition is valid.
    }

    private String displayItemName(BomRecipeItem item) {
        return item.getMaterialName() == null || item.getMaterialName().isBlank()
                ? item.getMaterialTypeId() : item.getMaterialName();
    }

    private BusinessException bomError(int status, String message, String code, String hint, String target) {
        return new BusinessException(status, message)
                .withCode(code)
                .withHint(hint)
                .withHintTarget(target)
                .withSeverity("BLOCKING");
    }

    private void assertVersionCapacity(String factoryId, String productTypeId) {
        long versionCount = recipeRepo.countByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (versionCount >= MAX_VERSIONS_PER_PRODUCT) {
            throw new BusinessException(409, "每个 SKU 最多保留 10 个 BOM 版本，请删除不再需要的草稿或历史版本后重试")
                    .withCode("BOM_VERSION_LIMIT_REACHED")
                    .withHint("当前未删除版本数为 " + versionCount + "，上限为 " + MAX_VERSIONS_PER_PRODUCT)
                    .withHintTarget("bomVersions");
        }
    }

    @Override
    public BomRecipe getRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        // 非 @Transactional 读路径: recipe 已 detached, items 是未初始化懒代理.
        // 必须用 setItems 替换字段引用 (而非 clear()+addAll) —— 后者会强制初始化
        // detached 懒代理 → LazyInitializationException (no Session).
        // orphanRemoval 异常只在 @Transactional 写方法 flush 时触发, 此处无 flush 无风险.
        List<BomRecipe> family = familyForStatus(recipe);
        Map<String, BomRecipe> owners = family.stream().collect(java.util.stream.Collectors.toMap(
                BomRecipe::getId, candidate -> candidate));
        List<BomRecipeItem> applicableItems = family.stream()
                .flatMap(member -> itemRepo.findByRecipeIdOrderBySortOrderAsc(member.getId()).stream())
                .filter(item -> costRowAppliesTo(
                        item.getCostScope(),
                        item.getCostScopeKey(),
                        owners.get(item.getRecipeId()),
                        recipe,
                        family,
                        "PACKAGING".equalsIgnoreCase(item.getMaterialCategory())))
                .sorted(Comparator.comparing(BomRecipeItem::getSortOrder)
                        .thenComparing(BomRecipeItem::getId))
                .toList();
        recipe.setItems(new ArrayList<>(applicableItems));
        return recipe;
    }

    @Override
    public Optional<BomRecipe> getCurrentRecipe(String factoryId, String productTypeId) {
        return recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                factoryId, productTypeId, BomRecipe.Status.ACTIVE);
    }

    @Override
    public List<BomRecipe> getRecipeVersions(String factoryId, String productTypeId) {
        return recipeRepo.findByFactoryIdAndProductTypeIdOrderByVersionDesc(factoryId, productTypeId);
    }

    @Override
    public Page<BomRecipe> listRecipes(String factoryId, BomRecipe.Status status, Pageable pageable) {
        if (status == null) {
            return recipeRepo.findByFactoryId(factoryId, pageable);
        }
        return recipeRepo.findByFactoryIdAndStatus(factoryId, status, pageable);
    }

    @Override
    @Transactional
    public BomRecipe calculateCost(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() == BomRecipe.Status.DRAFT) {
            assertPinnedDraft(factoryId, recipe);
        }
        // IMPORTANT: use refreshItemsInPlace to keep Hibernate PersistentBag reference intact.
        refreshItemsInPlace(recipe);
        recomputeFamilyCosts(recipe);
        // labor/overhead 留 Day 5 BomCostCalculationService 接入.
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public BomRecipeItem addItem(String factoryId, String recipeId, BomRecipeItemDTO dto) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可加 item; 当前 status=" + recipe.getStatus());
        }
        assertPinnedDraft(factoryId, recipe);
        BomRecipeItem item = buildItem(factoryId, recipe, dto);
        item = itemRepo.save(item);
        substituteService.replaceForRecipeItem(
                factoryId,
                recipeId,
                item.getId(),
                dto.getSubstitutes() == null ? List.of() : dto.getSubstitutes());
        // Touch recipe to mark updated + recompute cost.
        // IMPORTANT: must NOT replace the Hibernate-managed collection reference (orphanRemoval=true).
        // Using clear+addAll keeps the same List instance so Hibernate's orphan tracking stays intact.
        refreshItemsInPlace(recipe);
        recomputeFamilyCosts(recipe);
        recipeRepo.save(recipe);
        return item;
    }

    @Override
    @Transactional
    public BomRecipeItem updateItem(String factoryId, Long itemId, BomRecipeItemDTO dto) {
        BomRecipeItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("BomRecipeItem 不存在: id=" + itemId));
        if (!factoryId.equals(item.getFactoryId())) {
            throw new IllegalArgumentException("配方项不属于该工厂");
        }
        BomRecipe recipe = loadRecipe(factoryId, item.getRecipeId());
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可改 item; 当前 status=" + recipe.getStatus());
        }
        assertPinnedDraft(factoryId, recipe);
        // The material chosen in the editor is the new main material for this stable
        // logical input slot. Validate that requested material, not the previous row.
        RawMaterialType mt = materialTypeRepo.findById(dto.getMaterialTypeId())
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException(
                        "原料类型不存在或已删除, 请从字典选择: materialTypeId=" + dto.getMaterialTypeId()));
        if (!factoryId.equals(mt.getFactoryId())) {
            throw new IllegalArgumentException(
                    "原料类型不属于该工厂: materialTypeId=" + dto.getMaterialTypeId());
        }
        prepareItemUnit(factoryId, dto, mt);
        checkBomUnitCompatible(mt, dto.getUnit());
        item.setMaterialTypeId(mt.getId());
        item.setMaterialName(mt.getName());
        applyPrimaryCode(dto, item, mt);
        applyPackagingLevel(factoryId, recipe.getProductTypeId(), dto, mt, item);
        validateItemQuantity(dto);
        validateStableItemBinding(factoryId, recipe, dto, item);
        applyDtoToItem(dto, item);
        applyMaterialMasterPricing(item, mt);
        item = itemRepo.save(item);
        if (dto.getSubstitutes() != null) {
            substituteService.replaceForRecipeItem(
                    factoryId, recipe.getId(), item.getId(), dto.getSubstitutes());
        }
        refreshItemsInPlace(recipe);
        recomputeFamilyCosts(recipe);
        recipeRepo.save(recipe);
        return item;
    }

    @Override
    @Transactional
    public void deleteItem(String factoryId, Long itemId) {
        BomRecipeItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("BomRecipeItem 不存在: id=" + itemId));
        if (!factoryId.equals(item.getFactoryId())) {
            throw new IllegalArgumentException("配方项不属于该工厂");
        }
        BomRecipe recipe = loadRecipe(factoryId, item.getRecipeId());
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可删 item; 当前 status=" + recipe.getStatus());
        }
        assertPinnedDraft(factoryId, recipe);
        item.softDelete();
        itemRepo.save(item);
        refreshItemsInPlace(recipe);
        recomputeFamilyCosts(recipe);
        recipeRepo.save(recipe);
    }

    // ========== U5: 调料配方 CRUD ==========

    @Override
    public BomSeasoningResponse getSeasoning(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        return buildSeasoningResponse(recipe, seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipeId));
    }

    @Override
    public Optional<BomSeasoningResponse> getSeasoningByProduct(String factoryId, String productTypeId) {
        Optional<BomRecipe> opt = recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                factoryId, productTypeId, BomRecipe.Status.ACTIVE);
        return opt.map(recipe ->
                buildSeasoningResponse(recipe, seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipe.getId())));
    }

    @Override
    @Transactional
    public BomSeasoningResponse saveSeasoning(String factoryId, String recipeId, BomSeasoningSaveRequest req) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        // DRAFT-only guard — mirror the same check used in addItem/updateItem/deleteItem.
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可修改调料配方; 当前 status=" + recipe.getStatus()
                    + ", 请克隆为新版本后再改");
        }
        assertPinnedDraft(factoryId, recipe);

        // Validate sections and resolve authoritative material snapshots before any write.
        List<SeasoningItemDTO> items = req.getSeasoningItems();
        List<RawMaterialType> resolvedMaterials = new ArrayList<>();
        List<String> resolvedProcessNodeIds = new ArrayList<>();
        Set<String> processMaterialKeys = new HashSet<>();
        if (items != null) {
            for (SeasoningItemDTO dto : items) {
                if (!"INJECTION".equals(dto.getSection()) && !"COOKING".equals(dto.getSection())) {
                    throw new BusinessException(400,
                            "调料 section 只允许 INJECTION 或 COOKING, 收到: " + dto.getSection())
                            .withHint("请将 section 改为 INJECTION(注射段) 或 COOKING(熟制段)")
                            .withHintTarget("section");
                }
                if (!"COOKING".equals(dto.getSection()) && dto.getSubsequentPotRatio() != null) {
                    throw new BusinessException(400, "只有熟制调料绑定可以设置续锅比例")
                            .withHintTarget("subsequentPotRatio");
                }
                resolvedProcessNodeIds.add(resolveUniqueProcessNodeId(factoryId, recipe, dto.getWorkProcessId()));
                if (dto.getMaterialTypeId() == null || dto.getMaterialTypeId().isBlank()) {
                    throw new BusinessException(400, "调料必须关联物料档案")
                            .withHint("请从原辅料档案中选择调料")
                            .withHintTarget("materialTypeId");
                }
                RawMaterialType material = materialTypeRepo.findById(dto.getMaterialTypeId())
                        .orElseThrow(() -> new BusinessException(400,
                                "调料关联的物料不存在: " + dto.getMaterialTypeId())
                                .withHint("请重新选择有效的原辅料")
                                .withHintTarget("materialTypeId"));
                if (!factoryId.equals(material.getFactoryId())) {
                    throw new BusinessException(400, "调料物料不属于当前工厂: " + dto.getMaterialTypeId())
                            .withHint("只能选择当前工厂的原辅料")
                            .withHintTarget("materialTypeId");
                }
                if (!Boolean.TRUE.equals(material.getIsActive())) {
                    throw new BusinessException(400, "调料物料已停用: " + material.getName())
                            .withHint("请启用该物料或选择其他原辅料")
                            .withHintTarget("materialTypeId");
                }
                if (material.getMovingAvgPrice() == null) {
                    throw new BusinessException(400, "调料物料缺少移动平均价: " + material.getName())
                            .withHint("请先完成该物料的采购入库或维护移动平均价")
                            .withHintTarget("materialTypeId");
                }
                String processKey = (dto.getWorkProcessId() == null ? "" : dto.getWorkProcessId())
                        + "\u0000" + dto.getMaterialTypeId();
                if (!processMaterialKeys.add(processKey)) {
                    throw new BusinessException(400, "该工序已添加该调料: " + material.getName())
                            .withHint("同一工序内同一种调料只能添加一次")
                            .withHintTarget("materialTypeId");
                }
                resolvedMaterials.add(material);
            }
        }

        List<ProcessInjectionConfigDTO> injectionConfigs = req.getInjectionConfigs();
        if (injectionConfigs != null) {
            Set<String> injectionProcessIds = items == null ? Set.of() : items.stream()
                    .filter(item -> "INJECTION".equals(item.getSection()))
                    .map(SeasoningItemDTO::getWorkProcessId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> seenWp = new HashSet<>();
            for (ProcessInjectionConfigDTO config : injectionConfigs) {
                validateProductWorkProcess(factoryId, recipe, config.getWorkProcessId());
                if (!seenWp.add(config.getWorkProcessId())) {
                    throw new BusinessException(400, "同一工序的注射配置重复: " + config.getWorkProcessId())
                            .withHint("每道注射工序只能有一条绝对注射量配置");
                }
                if (config.getInjectionAmountKg() == null
                        || config.getInjectionAmountKg().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException(400, "注射量必须大于 0")
                            .withHintTarget("injectionAmountKg");
                }
                if (!injectionProcessIds.contains(config.getWorkProcessId())) {
                    throw new BusinessException(400, "注射配置没有对应的注射调料绑定: "
                            + config.getWorkProcessId())
                            .withHint("请先为该工序配置 INJECTION 调料明细")
                            .withHintTarget("workProcessId");
                }
            }
        }

        // Full-replace: soft-delete existing seasoning items, then insert new ones.
        // Mirror the pattern from updateRecipe (oldItems.softDelete + saveAll).
        // ⚠️ 走 repo 直写, 不碰 recipe.getSeasoningItems() (LAZY 受管集合, orphanRemoval=true).
        //    切勿在本 tx 内 saveAll 之后再读/改 recipe.getSeasoningItems() — 受管集合是 flush 前的
        //    旧快照, flush 时 orphanRemoval 会把刚插入的行当孤儿删掉 (audit R4 latent trap).
        List<BomSeasoningItem> oldItems = seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipeId);
        for (BomSeasoningItem old : oldItems) {
            old.softDelete();
        }
        seasoningItemRepo.saveAll(oldItems);

        List<BomSeasoningItem> newItems = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                SeasoningItemDTO dto = items.get(i);
                RawMaterialType material = resolvedMaterials.get(i);
                BomSeasoningItem si = new BomSeasoningItem();
                si.setRecipeId(recipeId);
                si.setFactoryId(factoryId);
                si.setMaterialTypeId(material.getId());
                si.setSection(dto.getSection());
                si.setSeq(dto.getSeq() != null ? dto.getSeq() : 0);
                si.setName(material.getName());
                si.setDosagePerKgG(dto.getDosagePerKgG());
                si.setPriceSource1(material.getMovingAvgPrice());
                si.setPriceSource2(null);
                si.setCountInSeasoning(dto.getCountInSeasoning() != null ? dto.getCountInSeasoning() : Boolean.TRUE);
                si.setRemark(dto.getRemark());
                si.setWorkProcessId(dto.getWorkProcessId()); // 调料配方按工序 (2026-07-13)
                si.setWorkflowProcessNodeId(resolvedProcessNodeIds.get(i));
                si.setCostScope("SHARED");
                si.setSubsequentPotRatio(dto.getSubsequentPotRatio());
                newItems.add(si);
            }
        }
        seasoningItemRepo.saveAll(newItems);

        List<BomProcessInjectionConfig> oldConfigs = processInjectionConfigRepo
                .findByRecipeIdAndDeletedAtIsNull(recipeId);
        for (BomProcessInjectionConfig old : oldConfigs) {
            old.softDelete();
        }
        processInjectionConfigRepo.saveAll(oldConfigs);
        List<BomProcessInjectionConfig> newConfigs = new ArrayList<>();
        if (injectionConfigs != null) {
            for (ProcessInjectionConfigDTO dto : injectionConfigs) {
                BomProcessInjectionConfig config = new BomProcessInjectionConfig();
                config.setRecipeId(recipeId);
                config.setFactoryId(factoryId);
                config.setWorkProcessId(dto.getWorkProcessId());
                config.setInjectionAmountKg(dto.getInjectionAmountKg());
                config.setNotes(dto.getNotes());
                newConfigs.add(config);
            }
        }
        processInjectionConfigRepo.saveAll(newConfigs);

        recomputeFamilyCosts(recipe);
        recipeRepo.save(recipe);

        return buildSeasoningResponse(recipe, newItems);
    }

    private void validateProductWorkProcess(String factoryId, BomRecipe recipe, String workProcessId) {
        resolveUniqueProcessNodeId(factoryId, recipe, workProcessId);
    }

    private String resolveUniqueProcessNodeId(String factoryId, BomRecipe recipe, String workProcessId) {
        if (workProcessId == null || workProcessId.isBlank()) {
            throw new BusinessException(400, "调料必须选择工序")
                    .withHint("请选择该 SKU 配置的有效工序")
                    .withHintTarget("workProcessId");
        }
        List<com.cretas.aims.service.workflow.PinnedWorkflowGraph.ProcessStep> matches =
                bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe).processes().stream()
                .filter(process -> workProcessId.equals(process.workProcessId()))
                .toList();
        if (matches.size() != 1) {
            throw new BusinessException(400, matches.isEmpty()
                    ? "所选工序不是该 SKU 固定工艺中的有效工序: " + workProcessId
                    : "固定工艺中存在重复工序节点，旧接口无法无歧义绑定: " + workProcessId)
                    .withHint("请使用按 Workflow 工序节点保存的辅料工作区")
                    .withHintTarget("workProcessId");
        }
        return matches.getFirst().processNodeId();
    }

    private BomSeasoningResponse buildSeasoningResponse(BomRecipe recipe, List<BomSeasoningItem> seasoningItems) {
        BomSeasoningResponse resp = new BomSeasoningResponse();
        resp.setBomRecipeId(recipe.getId());
        resp.setProductTypeId(recipe.getProductTypeId());
        resp.setProductName(recipe.getProductName());
        resp.setStatus(recipe.getStatus());
        resp.setSeasoningItems(seasoningItems);
        List<ProcessInjectionConfigDTO> configs = new ArrayList<>();
        for (BomProcessInjectionConfig config : processInjectionConfigRepo
                .findByRecipeIdAndDeletedAtIsNull(recipe.getId())) {
            ProcessInjectionConfigDTO dto = new ProcessInjectionConfigDTO();
            dto.setWorkProcessId(config.getWorkProcessId());
            dto.setInjectionAmountKg(config.getInjectionAmountKg());
            dto.setNotes(config.getNotes());
            configs.add(dto);
        }
        resp.setInjectionConfigs(configs);
        return resp;
    }

    /**
     * Refresh the recipe's Hibernate-managed items collection in-place.
     *
     * <p>Calling {@code recipe.setItems(newList)} after an addItem/updateItem/deleteItem
     * replaces the Hibernate PersistentBag reference, which triggers:
     * {@code HibernateException: A collection with cascade="all-delete-orphan" was no longer
     * referenced by the owning entity instance}.
     *
     * <p>Using {@code clear()} + {@code addAll()} keeps the same collection instance so
     * Hibernate's orphan-removal tracking remains intact.
     */
    private void refreshItemsInPlace(BomRecipe recipe) {
        List<BomRecipeItem> fresh = itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        recipe.getItems().clear();
        recipe.getItems().addAll(fresh);
    }

    // ========== Helpers ==========

    private BomRecipe loadRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = recipeRepo.findById(recipeId)
                .orElseThrow(() -> new EntityNotFoundException("BomRecipe 不存在: id=" + recipeId));
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw new IllegalArgumentException("配方不属于该工厂");
        }
        return recipe;
    }

    /** Build new item from DTO with material_name + default unit rehydrated from raw_material_types. */
    private BomRecipeItem buildItem(String factoryId, BomRecipe recipe, BomRecipeItemDTO dto) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipe.getId());
        item.setFactoryId(factoryId);
        item.setMaterialTypeId(dto.getMaterialTypeId());

        // Pre-check material_type_id exists in dictionary (better UX than FK violation).
        Optional<RawMaterialType> mt = materialTypeRepo.findById(dto.getMaterialTypeId());
        if (mt.isEmpty() || mt.get().getDeletedAt() != null) {
            throw new IllegalArgumentException(
                    "原料类型不存在或已删除, 请从字典选择: materialTypeId=" + dto.getMaterialTypeId());
        }
        // Cross-factory guard.
        if (!factoryId.equals(mt.get().getFactoryId())) {
            throw new IllegalArgumentException(
                    "原料类型不属于该工厂: materialTypeId=" + dto.getMaterialTypeId());
        }
        item.setMaterialName(mt.get().getName());

        applyPrimaryCode(dto, item, mt.get());

        // The UI displays localized labels (盒/箱) while material masters may hold
        // English historical codes (box/case). Resolve both through the shared unit
        // contract and persist one canonical code before compatibility/pricing checks.
        prepareItemUnit(factoryId, dto, mt.get());

        // T159-B R3: UoM dimension guard at BOM write time.
        checkBomUnitCompatible(mt.get(), dto.getUnit());

        applyPackagingLevel(factoryId, recipe.getProductTypeId(), dto, mt.get(), item);

        validateItemQuantity(dto);
        validateStableItemBinding(factoryId, recipe, dto, null);
        applyDtoToItem(dto, item);
        applyMaterialMasterPricing(item, mt.get());
        return item;
    }

    /**
     * Canonicalize units before persistence. Packaging units are inherited
     * from the selected material master and cannot be independently selected
     * in a BOM row.
     */
    private void prepareItemUnit(String factoryId, BomRecipeItemDTO dto, RawMaterialType material) {
        if ("PACKAGING".equalsIgnoreCase(dto.getMaterialCategory())) {
            String materialUnit = canonicalUnitOrThrow(factoryId, material.getUnit(), "unit");
            if (dto.getUnit() != null && !dto.getUnit().isBlank()
                    && !unitContractService.areEquivalent(factoryId, dto.getUnit(), materialUnit)) {
                throw bomError(400, "包材单位必须继承物料档案，不能在 BOM 中另选",
                        "BOM_PACKAGING_UNIT_MISMATCH", "请先修正包材档案的库存单位", "unit");
            }
            dto.setUnit(materialUnit);
            return;
        }
        normalizeItemUnit(factoryId, dto);
    }

    private void validateItemQuantity(BomRecipeItemDTO dto) {
        String category = dto.getMaterialCategory() == null ? "RAW" : dto.getMaterialCategory();
        BigDecimal quantity = dto.getStandardQuantity();
        if ((quantity == null && "PACKAGING".equalsIgnoreCase(category))
                || (quantity != null && quantity.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("包材用量必须大于 0；原料和工序辅料可由计划/报工确定实际用量");
        }
    }

    private void applyPackagingLevel(
            String factoryId,
            String productTypeId,
            BomRecipeItemDTO dto,
            RawMaterialType material,
            BomRecipeItem item) {
        if (!"PACKAGING".equalsIgnoreCase(dto.getMaterialCategory())) {
            item.setPackagingSpecId(null);
            item.setPackagingSpecNameSnapshot(null);
            item.setPackagingRole(null);
            item.setNaturalQuantity(null);
            item.setNaturalUnit(null);
            item.setPackagingPackageUnitSnapshot(null);
            item.setPackagingBaseUnitSnapshot(null);
            item.setPackagingConversionFactorSnapshot(null);
            return;
        }
        if (dto.getPackagingRole() == null || dto.getPackagingRole().isBlank()) {
            throw bomError(400, "包材必须选择所在包装层级的业务角色",
                    "BOM_PACKAGING_ROLE_REQUIRED", "请选择成品容器、封装或外包装等角色", "packagingRole");
        }
        String materialUnit = canonicalUnitOrThrow(factoryId, material.getUnit(), "unit");
        if (dto.getUnit() != null && !dto.getUnit().isBlank()
                && !unitContractService.areEquivalent(factoryId, dto.getUnit(), materialUnit)) {
            throw bomError(400, "包材单位必须继承物料档案，不能在 BOM 中另选",
                    "BOM_PACKAGING_UNIT_MISMATCH", "请先修正包材档案的库存单位", "unit");
        }
        dto.setUnit(materialUnit);
        BigDecimal naturalQuantity = dto.getNaturalQuantity() != null
                ? dto.getNaturalQuantity() : dto.getStandardQuantity();
        if (naturalQuantity == null || naturalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw bomError(400, "包材自然用量必须大于 0",
                    "BOM_PACKAGING_QUANTITY_REQUIRED", "请填写每个当前包装规格所需的包材数量", "naturalQuantity");
        }

        item.setPackagingRole(dto.getPackagingRole().trim());
        item.setNaturalQuantity(naturalQuantity);
        item.setNaturalUnit(materialUnit);
        if (dto.getPackagingSpecId() == null || dto.getPackagingSpecId().isBlank()) {
            dto.setStandardQuantity(naturalQuantity);
            item.setPackagingSpecId(null);
            item.setPackagingSpecNameSnapshot("基本规格");
            item.setPackagingPackageUnitSnapshot(null);
            item.setPackagingBaseUnitSnapshot(null);
            item.setPackagingConversionFactorSnapshot(BigDecimal.ONE);
            return;
        }

        ProductPackagingSpec spec = packagingSpecRepository
                .findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(
                        dto.getPackagingSpecId(), factoryId, productTypeId)
                .orElseThrow(() -> bomError(400, "所选包装规格不存在、已停用或不属于当前 SKU",
                        "BOM_PACKAGING_SPEC_INVALID", "请刷新 SKU 包装规格后重新选择", "packagingSpecId"));
        if (spec.getConversionFactor() == null || spec.getConversionFactor().compareTo(BigDecimal.ZERO) <= 0) {
            throw bomError(409, "SKU 包装规格换算系数无效",
                    "BOM_PACKAGING_SPEC_CONVERSION_INVALID", "请先修正 SKU 包装规格", "packagingSpecId");
        }
        dto.setStandardQuantity(naturalQuantity.divide(spec.getConversionFactor(), 8, RoundingMode.HALF_UP));
        item.setPackagingSpecId(spec.getId());
        item.setPackagingSpecNameSnapshot(spec.getName());
        item.setPackagingPackageUnitSnapshot(spec.getPackageUnit());
        item.setPackagingBaseUnitSnapshot(spec.getBaseUnit());
        item.setPackagingConversionFactorSnapshot(spec.getConversionFactor());
    }

    /** BOM 价格只读继承物料主数据/库存移动均价，禁止形成第二套人工价格真值。 */
    private void applyMaterialMasterPricing(BomRecipeItem item, RawMaterialType material) {
        BigDecimal price = material.getMovingAvgPrice() != null
                ? material.getMovingAvgPrice()
                : material.getUnitPrice();
        item.setUnitPrice(price);
        applyPriceUnitContract(item, material.getUnit());
        item.setTaxRate(material.getTaxRate() == null
                ? null
                : material.getTaxRate().getRate().multiply(BigDecimal.valueOf(100)));
    }

    private void applyPriceUnitContract(BomRecipeItem item, String rawPriceUnit) {
        String requestedPriceUnit = rawPriceUnit != null && !rawPriceUnit.isBlank()
                ? rawPriceUnit : item.getUnit();
        String priceUnit = canonicalUnitOrThrow(item.getFactoryId(), requestedPriceUnit, "priceUnit");
        item.setPriceUnit(priceUnit);
        if (item.getUnit() == null || priceUnit == null || item.getUnit().equals(priceUnit)) {
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            return;
        }
        MaterialUomConverter.ConversionResult conversion = materialUomConverter.toComparableQuantity(
                item.getMaterialTypeId(), BigDecimal.ONE, item.getUnit(), priceUnit);
        if (!conversion.isConverted() || conversion.getQuantity() == null) {
            throw new BusinessException(409,
                    "BOM 数量单位无法换算到计价单位: " + item.getUnit() + " → " + priceUnit);
        }
        item.setQuantityToPriceFactor(conversion.getQuantity());
    }

    /** Apply DTO fields to entity (used by add + update). */
    private void applyDtoToItem(BomRecipeItemDTO dto, BomRecipeItem item) {
        item.setStandardQuantity(dto.getStandardQuantity());
        // 出成率是同工厂、同 SKU 正式批次的系统统计，不再让 BOM 单行人工反推。
        // 旧列保留 100% 中性值，以兼容存量成本公式和 NOT NULL 约束。
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnit(dto.getUnit());
        item.setUnitPrice(dto.getUnitPrice());
        // F6 诚实 null: taxRate 未传 → 保持 null (不默认 0%). 默认 0% 会把"未配置税率"静默当
        //   "未税/含税无差", 成本侧失真. null → 上层成本计算诚实标缺, 让用户补填.
        item.setTaxRate(dto.getTaxRate());
        item.setMaterialCategory(dto.getMaterialCategory() != null ? dto.getMaterialCategory() : "RAW");
        if (dto.getWorkflowMaterialNodeId() != null) {
            item.setWorkflowMaterialNodeId(dto.getWorkflowMaterialNodeId());
        }
        if (dto.getWorkflowInputPortId() != null) {
            item.setWorkflowInputPortId(dto.getWorkflowInputPortId());
        }
        if (dto.getWorkflowEdgeId() != null) {
            item.setWorkflowEdgeId(dto.getWorkflowEdgeId());
        }
        if (dto.getCostScope() != null) {
            item.setCostScope(dto.getCostScope());
        } else if (item.getCostScope() == null) {
            item.setCostScope("PACKAGING".equalsIgnoreCase(item.getMaterialCategory())
                    ? "OUTPUT_EXCLUSIVE" : "SHARED");
        }
        if (dto.getCostScopeKey() != null) {
            item.setCostScopeKey(dto.getCostScopeKey());
        }
        item.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        item.setIsOptional(dto.getIsOptional() != null ? dto.getIsOptional() : false);
        item.setSubstituteGroup(dto.getSubstituteGroup());
        item.setRemark(dto.getRemark());
        // SP4-T3: 按份计量 + 半成品引用
        item.setPerPortion(dto.getPerPortion() != null ? dto.getPerPortion() : false);
        item.setSemiFinishedRefCode(dto.getSemiFinishedRefCode());
        // SP1: 嵌套 BOM 子产品引用 (组合装/先做后用)
        item.setSubProductTypeId(dto.getSubProductTypeId());
        // SP8: primaryCode null-guard update. primaryCodeRef is a legacy alias.
        if (dto.getPrimaryCode() != null) {
            item.setPrimaryCode(dto.getPrimaryCode());
            item.setPrimaryCodeRef(dto.getPrimaryCode());
        }
        if (dto.getPrimaryCodeRef() != null) {
            item.setPrimaryCodeRef(dto.getPrimaryCodeRef());
            if (dto.getPrimaryCode() == null) {
                item.setPrimaryCode(dto.getPrimaryCodeRef());
            }
        }
        // Cache actual_quantity (also computable @Transient, but cached for query speed).
        // Note: item_cost for nested-component rows is resolved via NestedBomCostService at recipe-level;
        // the cached itemCost here reflects only the local unitPrice (plain material rows).
        // recomputeMaterialCost will use NestedBomCostService.resolveItemCost for subProductTypeId rows.
        item.setActualQuantity(item.calculateActualQuantity());
        item.setItemCost(item.computeItemCost());
    }

    /**
     * Stable Workflow slot identities are server-owned business context. A client may
     * round-trip them during a full-replace import, but it cannot re-point an existing
     * item or invent a node/port/edge tuple outside the pinned target DAG slice.
     */
    private void validateStableItemBinding(
            String factoryId,
            BomRecipe recipe,
            BomRecipeItemDTO dto,
            BomRecipeItem existing) {
        if (existing != null && existing.getWorkflowMaterialNodeId() != null) {
            assertStableFieldUnchanged(
                    "materialNodeId", existing.getWorkflowMaterialNodeId(), dto.getWorkflowMaterialNodeId());
            assertStableFieldUnchanged(
                    "inputPortId", existing.getWorkflowInputPortId(), dto.getWorkflowInputPortId());
            assertStableFieldUnchanged(
                    "edgeId", existing.getWorkflowEdgeId(), dto.getWorkflowEdgeId());
            assertStableFieldUnchanged("costScope", existing.getCostScope(), dto.getCostScope());
            assertStableFieldUnchanged(
                    "costScopeKey", existing.getCostScopeKey(), dto.getCostScopeKey());
            return;
        }

        boolean hasMaterialNode = hasText(dto.getWorkflowMaterialNodeId());
        boolean hasInputPort = hasText(dto.getWorkflowInputPortId());
        boolean hasEdge = hasText(dto.getWorkflowEdgeId());
        int stableFieldCount = (hasMaterialNode ? 1 : 0) + (hasInputPort ? 1 : 0) + (hasEdge ? 1 : 0);
        if (stableFieldCount > 0 && stableFieldCount < 3) {
            throw bomError(409,
                    "Workflow 投入槽标识不完整，不能保存 BOM 明细",
                    "BOM_WORKFLOW_INPUT_SLOT_IDENTITY_INCOMPLETE",
                    "请刷新 BOM 工作区后重试；不要手工修改工艺标识",
                    "bomItems");
        }

        if (stableFieldCount == 3) {
            BomWorkflowRevisionService.InputSlot slot =
                    BomWorkflowRevisionService.resolveInputSlots(
                                    bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe))
                            .stream()
                            .filter(candidate -> dto.getWorkflowMaterialNodeId().equals(candidate.materialNodeId()))
                            .filter(candidate -> dto.getWorkflowInputPortId().equals(candidate.inputPortId()))
                            .filter(candidate -> dto.getWorkflowEdgeId().equals(candidate.edgeId()))
                            .findFirst()
                            .orElseThrow(() -> bomError(409,
                                    "Workflow 投入槽已变化，不能保存到当前 BOM 工艺来源",
                                    "BOM_WORKFLOW_INPUT_SLOT_MISMATCH",
                                    "请刷新 BOM；如工艺已有新修订，请使用“升级到最新工艺”",
                                    "workflow"));
            InputCostProfile expectedProfile =
                    resolveExpectedInputProfile(factoryId, recipe, slot);
            if (dto.getCostScope() != null
                    && !expectedProfile.costScope().equals(dto.getCostScope())) {
                throw bomError(409,
                        "BOM 明细成本范围与固定工艺路径不一致",
                        "BOM_WORKFLOW_COST_SCOPE_MISMATCH",
                        "请刷新 BOM 工作区后重试",
                        "bomItems");
            }
            if (dto.getCostScopeKey() != null
                    && !expectedProfile.costScopeKey().equals(dto.getCostScopeKey())) {
                throw bomError(409,
                        "BOM 明细成本目标与固定工艺路径不一致",
                        "BOM_WORKFLOW_COST_SCOPE_TARGET_MISMATCH",
                        "请刷新 BOM 工作区后重试",
                        "bomItems");
            }
            dto.setCostScope(expectedProfile.costScope());
            dto.setCostScopeKey(expectedProfile.costScopeKey());
            return;
        }

        String expectedManualScope = "PACKAGING".equalsIgnoreCase(dto.getMaterialCategory())
                ? "OUTPUT_EXCLUSIVE" : "SHARED";
        if (dto.getCostScope() != null && !expectedManualScope.equals(dto.getCostScope())) {
            throw bomError(409,
                    "手工明细成本范围与物料用途不一致",
                    "BOM_MANUAL_ITEM_COST_SCOPE_MISMATCH",
                    "包材只能归属当前产出；共享投入必须由工艺投入槽生成",
                    "bomItems");
        }
        dto.setCostScope(expectedManualScope);
        String expectedManualScopeKey = "OUTPUT_EXCLUSIVE".equals(expectedManualScope)
                ? recipe.getTargetTerminalNodeId()
                : BomWorkflowRevisionService.canonicalCostScopeKey(
                        familyForStatus(recipe).stream()
                                .map(BomRecipe::getTargetTerminalNodeId)
                                .toList());
        if (dto.getCostScopeKey() != null
                && !expectedManualScopeKey.equals(dto.getCostScopeKey())) {
            throw bomError(409,
                    "手工明细成本目标与当前固定产出集合不一致",
                    "BOM_MANUAL_ITEM_COST_SCOPE_TARGET_MISMATCH",
                    "请刷新 BOM 工作区后重试；成本归属由系统自动确定",
                    "bomItems");
        }
        dto.setCostScopeKey(expectedManualScopeKey);
    }

    private InputCostProfile resolveExpectedInputProfile(
            String factoryId,
            BomRecipe recipe,
            BomWorkflowRevisionService.InputSlot expectedSlot) {
        List<BomRecipe> family = familyForStatus(recipe);
        BomRecipe main = family.stream()
                .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.MAIN)
                .findFirst()
                .orElse(recipe);
        Map<String, List<BomWorkflowRevisionService.InputSlot>> slotsByRecipe =
                new LinkedHashMap<>();
        for (BomRecipe member : family) {
            slotsByRecipe.put(member.getId(), BomWorkflowRevisionService.resolveInputSlots(
                    bomWorkflowRevisionService.resolvePinnedGraph(factoryId, member)));
        }
        return resolveInputCostProfiles(family, slotsByRecipe, main).stream()
                .filter(profile -> slotKey(profile.slot()).equals(slotKey(expectedSlot)))
                .findFirst()
                .orElseThrow(() -> bomError(409,
                        "当前 Workflow 投入槽不属于 BOM Family 的固定工艺路径",
                        "BOM_WORKFLOW_INPUT_SLOT_SCOPE_MISSING",
                        "请刷新 BOM；如工艺已有新修订，请使用“升级到最新工艺”",
                        "workflow"));
    }

    private void assertStableFieldUnchanged(String field, String existing, String requested) {
        if (requested != null && !Objects.equals(existing, requested)) {
            throw bomError(409,
                    "BOM 明细的固定工艺标识不能在普通编辑中修改: " + field,
                    "BOM_WORKFLOW_INPUT_SLOT_IMMUTABLE",
                    "如需采用新工艺，请使用“升级到最新工艺”",
                    "workflow");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void normalizeItemUnit(String factoryId, BomRecipeItemDTO dto) {
        if (dto.getUnit() == null || dto.getUnit().isBlank()) {
            throw bomError(400,
                    "BOM 明细单位不能为空",
                    "BOM_ITEM_UNIT_REQUIRED",
                    "请从物料主数据或工厂单位目录选择计量单位",
                    "unit");
        }
        dto.setUnit(canonicalUnitOrThrow(factoryId, dto.getUnit(), "unit"));
    }

    private String canonicalUnitOrThrow(String factoryId, String rawUnit, String hintTarget) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return rawUnit;
        }
        UnitNormalizationResult normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized() || normalized.code() == null) {
            throw bomError(400,
                    "BOM 明细单位无法识别: " + rawUnit,
                    "BOM_ITEM_UNIT_UNKNOWN",
                    "请从物料主数据或工厂单位目录选择有效计量单位",
                    hintTarget);
        }
        return normalized.code();
    }

    /**
     * Recompute material cost from items (sum of itemCost).
     *
     * <p>SP1: 嵌套 BOM 聚合 — 若某行有 {@code subProductTypeId} (组合装子产品或先做后用半成品),
     * 委托 {@link NestedBomCostService#resolveItemCost} 处理递归成本 + WIP 移动均价优先;
     * 普通原材料行仍走 {@code item.computeItemCost()} (actualQuantity × unitPrice)，
     * <b>不破坏单层 BOM 现有行为</b>。
     *
     * <p>诚实 null 传播: 任一行成本 null (未定价) → 整体 totalMaterialCost = null。
     */
    private void recomputeFamilyCosts(BomRecipe changed) {
        List<BomRecipe> targets = familyForStatus(changed);
        Map<String, List<BomRecipe>> poolTargets = new LinkedHashMap<>();
        Map<String, BigDecimal> poolCosts = new LinkedHashMap<>();

        for (BomRecipe owner : targets) {
            for (BomRecipeItem item :
                    itemRepo.findByRecipeIdOrderBySortOrderAsc(owner.getId())) {
                BigDecimal cost = nestedBomCostService.isNestedComponent(item)
                        ? nestedBomCostService.resolveItemCost(owner.getFactoryId(), item)
                        : item.computeItemCost();
                if (cost == null) {
                    markFamilyCostIncomplete(targets);
                    return;
                }
                List<BomRecipe> costTargets = resolveCostTargets(
                        item.getCostScope(),
                        item.getCostScopeKey(),
                        owner,
                        targets,
                        "PACKAGING".equalsIgnoreCase(item.getMaterialCategory()));
                addToCostPool(poolTargets, poolCosts, costTargets, cost);
            }
            BigDecimal outputKg = outputKilograms(owner);
            for (BomSeasoningItem item :
                    seasoningItemRepo.findByRecipeIdOrderBySeqAsc(owner.getId())) {
                CostValue cost = seasoningCost(List.of(item), outputKg);
                if (!cost.complete()) {
                    markFamilyCostIncomplete(targets);
                    return;
                }
                List<BomRecipe> costTargets = resolveCostTargets(
                        item.getCostScope(), item.getCostScopeKey(), owner, targets, false);
                addToCostPool(poolTargets, poolCosts, costTargets, cost.value());
            }
        }

        Map<String, BigDecimal> byproductDirectCosts = new HashMap<>();
        for (Map.Entry<String, BigDecimal> pool : poolCosts.entrySet()) {
            List<BomRecipe> members = poolTargets.get(pool.getKey());
            List<BomRecipe> costingOutputs = members.stream()
                    .filter(member -> member.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT)
                    .toList();
            if (!costingOutputs.isEmpty()) continue;
            List<BomRecipe> byproducts = members.stream()
                    .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT)
                    .toList();
            if (byproducts.isEmpty()) continue;
            Map<String, BigDecimal> weights = new LinkedHashMap<>();
            for (BomRecipe byproduct : byproducts) {
                BigDecimal grossNrv = byproductGrossNrv(byproduct);
                if (grossNrv == null) {
                    markFamilyCostIncomplete(targets);
                    return;
                }
                weights.put(byproduct.getId(), grossNrv);
            }
            BigDecimal totalWeight = weights.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalWeight.signum() <= 0) {
                markFamilyCostIncomplete(targets);
                return;
            }
            for (BomRecipe byproduct : byproducts) {
                BigDecimal allocated = pool.getValue()
                        .multiply(weights.get(byproduct.getId()))
                        .divide(totalWeight, 8, RoundingMode.HALF_UP);
                byproductDirectCosts.merge(byproduct.getId(), allocated, BigDecimal::add);
            }
        }

        Map<String, BigDecimal> adjustedPoolCosts = new LinkedHashMap<>(poolCosts);
        for (BomRecipe byproduct : targets.stream()
                .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT)
                .toList()) {
            BigDecimal grossNrv = byproductGrossNrv(byproduct);
            if (grossNrv == null) {
                markFamilyCostIncomplete(targets);
                return;
            }
            BigDecimal netCredit = grossNrv.subtract(
                    byproductDirectCosts.getOrDefault(byproduct.getId(), BigDecimal.ZERO));
            List<String> eligiblePools = poolTargets.entrySet().stream()
                    .filter(entry -> entry.getValue().stream()
                            .anyMatch(member -> member.getId().equals(byproduct.getId())))
                    .filter(entry -> entry.getValue().stream()
                            .anyMatch(member -> member.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT))
                    .map(Map.Entry::getKey)
                    .toList();
            if (netCredit.signum() != 0 && eligiblePools.isEmpty()) {
                throw bomError(409,
                        "副产品没有可抵扣的共享成本路径",
                        "BOM_BY_PRODUCT_CREDIT_PATH_MISSING",
                        "请检查副产品是否与主产出共享上游投入或工序",
                        "byproductNrvUnitPrice");
            }
            BigDecimal eligibleTotal = eligiblePools.stream()
                    .map(poolCosts::get)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (netCredit.signum() != 0 && eligibleTotal.signum() <= 0) {
                throw bomError(409,
                        "副产品对应的共享成本池为 0，无法应用净值抵扣",
                        "BOM_BY_PRODUCT_CREDIT_POOL_EMPTY",
                        "请检查共享投入的数量、价格和工序辅料",
                        "byproductNrvUnitPrice");
            }
            for (String poolKey : eligiblePools) {
                BigDecimal credit = netCredit.multiply(poolCosts.get(poolKey))
                        .divide(eligibleTotal, 8, RoundingMode.HALF_UP);
                BigDecimal adjusted = adjustedPoolCosts.get(poolKey).subtract(credit);
                if (adjusted.signum() < 0) {
                    throw bomError(409,
                            "副产品净值抵扣超过其关联共享成本，不能形成负成本",
                            "BOM_BY_PRODUCT_CREDIT_EXCEEDS_SHARED_COST",
                            "请核对副产品单位净值、产出数量或共享投入成本",
                            "byproductNrvUnitPrice");
                }
                adjustedPoolCosts.put(poolKey, adjusted);
            }
        }

        Map<String, BigDecimal> materialCosts = targets.stream().collect(
                java.util.stream.Collectors.toMap(
                        BomRecipe::getId,
                        ignored -> BigDecimal.ZERO,
                        BigDecimal::add,
                        LinkedHashMap::new));
        for (Map.Entry<String, BigDecimal> pool : adjustedPoolCosts.entrySet()) {
            List<BomRecipe> costingOutputs = poolTargets.get(pool.getKey()).stream()
                    .filter(member -> member.getOutputRole() != BomRecipe.OutputRole.BY_PRODUCT)
                    .toList();
            if (costingOutputs.isEmpty()) continue;
            BigDecimal ratioTotal = costingOutputs.stream()
                    .map(this::costingRatio)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (ratioTotal.signum() <= 0) {
                throw bomError(409,
                        "成本池覆盖的主产出/联产品没有有效分摊比例",
                        "BOM_FAMILY_COST_POOL_RATIO_INVALID",
                        "请在 Workflow 中补齐该路径对应产出的成本分摊比例",
                        "costAllocationRatio");
            }
            for (BomRecipe output : costingOutputs) {
                BigDecimal allocated = pool.getValue()
                        .multiply(costingRatio(output))
                        .divide(ratioTotal, 8, RoundingMode.HALF_UP);
                materialCosts.merge(output.getId(), allocated, BigDecimal::add);
            }
        }
        for (BomRecipe byproduct : targets.stream()
                .filter(member -> member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT)
                .toList()) {
            materialCosts.put(byproduct.getId(), byproductGrossNrv(byproduct));
        }

        for (BomRecipe target : targets) {
            BigDecimal materialCost = materialCosts.get(target.getId())
                    .setScale(4, RoundingMode.HALF_UP);
            target.setTotalMaterialCost(materialCost);
            BigDecimal allocation = target.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT
                    ? BigDecimal.ZERO
                    : costingRatio(target).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
            BigDecimal allocatedLabor = valueOrZero(target.getTotalLaborCost()).multiply(allocation);
            BigDecimal allocatedOverhead = valueOrZero(target.getTotalOverheadCost()).multiply(allocation);
            target.setTotalCost(materialCost.add(allocatedLabor).add(allocatedOverhead)
                    .setScale(4, RoundingMode.HALF_UP));
            recipeRepo.save(target);
        }
    }

    private List<BomRecipe> familyForStatus(BomRecipe reference) {
        if (reference.getBomFamilyId() == null) return List.of(reference);
        return recipeRepo.findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
                        reference.getFactoryId(), reference.getBomFamilyId()).stream()
                .filter(recipe -> Objects.equals(
                        reference.getWorkflowRevisionId(), recipe.getWorkflowRevisionId()))
                .filter(recipe -> recipe.getStatus() == reference.getStatus())
                .toList();
    }

    private void addToCostPool(
            Map<String, List<BomRecipe>> poolTargets,
            Map<String, BigDecimal> poolCosts,
            List<BomRecipe> targets,
            BigDecimal cost) {
        String key = BomWorkflowRevisionService.canonicalCostScopeKey(
                targets.stream().map(BomRecipe::getTargetTerminalNodeId).toList());
        poolTargets.putIfAbsent(key, targets);
        poolCosts.merge(key, cost, BigDecimal::add);
    }

    private List<BomRecipe> resolveCostTargets(
            String scope,
            String scopeKey,
            BomRecipe owner,
            List<BomRecipe> family,
            boolean packaging) {
        if (hasText(scopeKey)) {
            Set<String> terminalIds = new LinkedHashSet<>(
                    List.of(scopeKey.split(",")));
            List<BomRecipe> matched = family.stream()
                    .filter(member -> terminalIds.contains(member.getTargetTerminalNodeId()))
                    .sorted(Comparator.comparing(BomRecipe::getTargetTerminalNodeId))
                    .toList();
            if (matched.size() != terminalIds.size()) {
                throw bomError(409,
                        "BOM 成本目标包含不属于当前固定工艺的产出",
                        "BOM_COST_SCOPE_TARGET_INVALID",
                        "请刷新 BOM；如工艺已变化，请使用“升级到最新工艺”",
                        "workflow");
            }
            return matched;
        }
        if (packaging || "OUTPUT_EXCLUSIVE".equals(scope)) return List.of(owner);
        if ("OUTPUT_GROUP".equals(scope)) {
            throw bomError(409,
                    "部分产出共享成本缺少精确目标集合",
                    "BOM_COST_SCOPE_KEY_REQUIRED",
                    "请重新执行工艺升级，让系统按稳定 DAG 路径补齐成本目标",
                    "workflow");
        }
        return family.stream()
                .sorted(Comparator.comparing(BomRecipe::getTargetTerminalNodeId))
                .toList();
    }

    private boolean costRowAppliesTo(
            String scope,
            String scopeKey,
            BomRecipe owner,
            BomRecipe target,
            List<BomRecipe> family,
            boolean packaging) {
        if (owner == null) return false;
        return resolveCostTargets(scope, scopeKey, owner, family, packaging).stream()
                .anyMatch(member -> member.getId().equals(target.getId()));
    }

    private BigDecimal byproductGrossNrv(BomRecipe recipe) {
        if (recipe.getByproductNrvUnitPrice() == null
                || recipe.getOutputQuantityPerUnit() == null) return null;
        return recipe.getByproductNrvUnitPrice().multiply(recipe.getOutputQuantityPerUnit());
    }

    private BigDecimal costingRatio(BomRecipe recipe) {
        if (recipe.getCostAllocationRatio() != null) return recipe.getCostAllocationRatio();
        return BigDecimal.valueOf(100);
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void markFamilyCostIncomplete(List<BomRecipe> family) {
        for (BomRecipe member : family) {
            member.setTotalMaterialCost(null);
            member.setTotalCost(null);
            recipeRepo.save(member);
        }
    }

    private CostValue sumItemCosts(String factoryId, List<BomRecipeItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (BomRecipeItem item : items) {
            BigDecimal cost = nestedBomCostService.isNestedComponent(item)
                    ? nestedBomCostService.resolveItemCost(factoryId, item)
                    : item.computeItemCost();
            if (cost == null) return CostValue.incomplete();
            total = total.add(cost);
        }
        return CostValue.complete(total);
    }

    private CostValue seasoningCost(List<BomSeasoningItem> items, BigDecimal outputKg) {
        if (items.isEmpty()) return CostValue.complete(BigDecimal.ZERO);
        if (outputKg == null || outputKg.signum() <= 0) return CostValue.incomplete();
        BigDecimal perKg = BigDecimal.ZERO;
        for (BomSeasoningItem item : items) {
            if (!Boolean.TRUE.equals(item.getCountInSeasoning())) continue;
            BigDecimal price = item.getPriceSource1() != null && item.getPriceSource1().signum() > 0
                    ? item.getPriceSource1()
                    : item.getPriceSource2() != null && item.getPriceSource2().signum() > 0
                            ? item.getPriceSource2() : null;
            if (item.getDosagePerKgG() == null || price == null) return CostValue.incomplete();
            perKg = perKg.add(item.getDosagePerKgG()
                    .divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP)
                    .multiply(price));
        }
        return CostValue.complete(perKg.multiply(outputKg));
    }

    private BigDecimal outputKilograms(BomRecipe recipe) {
        BigDecimal quantity = recipe.getNetContentQuantity() != null
                ? recipe.getNetContentQuantity() : recipe.getOutputQuantityPerUnit();
        String unit = recipe.getNetContentQuantity() != null
                ? recipe.getNetContentUnit() : recipe.getOutputUnit();
        if (quantity == null || unit == null) return null;
        return switch (unit.trim().toLowerCase()) {
            case "kg", "千克", "公斤" -> quantity;
            case "g", "克" -> quantity.divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP);
            default -> null;
        };
    }

    private record CostValue(BigDecimal value, boolean complete) {
        private static CostValue complete(BigDecimal value) {
            return new CostValue(value, true);
        }

        private static CostValue incomplete() {
            return new CostValue(null, false);
        }
    }

    private record InputCostProfile(
            BomWorkflowRevisionService.InputSlot slot,
            List<BomRecipe> targets,
            BomRecipe owner,
            String costScope,
            String costScopeKey) { }

    /**
     * T159-B R3: 防呆 — 校验 BOM 单位与原料主数据规范单位的计量维度.
     *
     * <p>传入已加载的 {@link RawMaterialType} (caller 已确认非 null) 和 dtoUnit.
     * 调用 {@link MaterialUomConverter#isWriteUnitCompatible} 判断是否跨维度.
     * 如不兼容则抛 {@link BusinessException} 并附上 4-位一体防呆消息.
     *
     * @param material 原料主数据 (非 null)
     * @param dtoUnit  BOM DTO 中的单位字段 (可 null/blank, 会被 isWriteUnitCompatible 放行)
     */
    private void checkBomUnitCompatible(RawMaterialType material, String dtoUnit) {
        if (dtoUnit == null || dtoUnit.isBlank()) {
            return;   // fail-OPEN: 空单位留后验证
        }
        if (!materialUomConverter.isWriteUnitCompatible(material.getId(), dtoUnit)) {
            String materialName = material.getName() != null ? material.getName() : material.getId();
            String canonicalUnit = material.getUnit() != null ? material.getUnit() : "?";
            throw new BusinessException(409,
                    String.format("「%s」BOM单位(%s)与原料主数据单位(%s)计量维度不符，" +
                                    "请改为同维度单位（如该原料按%s计量）",
                            materialName, dtoUnit, canonicalUnit, canonicalUnit))
                    .withHint(String.format("请将BOM单位改为与「%s」主数据单位(%s)同维度的单位",
                            materialName, canonicalUnit))
                    .withSeverity("BLOCKING");
        }
    }

    private void applyPrimaryCode(BomRecipeItemDTO dto, BomRecipeItem item, RawMaterialType material) {
        String materialPrimary = normalizePrimaryCode(material.getPrimaryCode());
        String requestedPrimary = normalizePrimaryCode(dto.getPrimaryCode());
        String requestedPrimaryRef = normalizePrimaryCode(dto.getPrimaryCodeRef());
        if (requestedPrimary != null && requestedPrimaryRef != null && !requestedPrimary.equals(requestedPrimaryRef)) {
            throw new BusinessException(400, String.format(
                    "BOM物料主编码不一致: 传入primaryCode=%s, primaryCodeRef=%s。请统一主编码后重试。",
                    requestedPrimary, requestedPrimaryRef))
                    .withHint("请只传primaryCode，或确保primaryCodeRef与primaryCode一致")
                    .withHintTarget("primaryCode")
                    .withSeverity("BLOCKING");
        }
        if (requestedPrimary == null) {
            requestedPrimary = requestedPrimaryRef;
        }

        if (materialPrimary != null && requestedPrimary != null && !materialPrimary.equals(requestedPrimary)) {
            String materialName = material.getName() != null ? material.getName() : material.getId();
            throw new BusinessException(400, String.format(
                    "BOM物料主编码不一致: 物料%s(materialTypeId=%s)主编码为%s, 传入primaryCode=%s。请在BOM选料器中按主编码分组重新选择物料。",
                    materialName, material.getId(), materialPrimary, requestedPrimary))
                    .withHint("请重新选择同一主编码分组下的物料，或清空primaryCode让系统从物料主数据回填")
                    .withHintTarget("primaryCode")
                    .withSeverity("BLOCKING");
        }

        String toStore = requestedPrimary != null ? requestedPrimary : materialPrimary;
        if (toStore != null) {
            item.setPrimaryCode(toStore);
            item.setPrimaryCodeRef(toStore);
        }
    }

    private String normalizePrimaryCode(String primaryCode) {
        if (primaryCode == null || primaryCode.isBlank()) {
            return null;
        }
        return primaryCode.trim();
    }

    /** Generate {@code BOM-YYYYMMDD-NNN} where NNN = today's recipe count + 1 (factory-scoped). */
    private String generateRecipeCode(String factoryId) {
        String today = LocalDate.now().format(CODE_DATE_FMT);
        String prefix = "BOM-" + today + "-";
        long countToday = recipeRepo.countByRecipeCodePrefix(factoryId, prefix + "%");
        return String.format("%s%03d", prefix, countToday + 1);
    }
}
