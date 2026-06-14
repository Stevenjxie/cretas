package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
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
import java.util.List;
import java.util.Optional;

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

    private final BomRecipeRepository recipeRepo;
    private final BomRecipeItemRepository itemRepo;
    private final RawMaterialTypeRepository materialTypeRepo;
    private final MaterialUomConverter materialUomConverter;
    /** SP1: 嵌套 BOM 成本聚合 (组合装/先做后用). */
    private final NestedBomCostService nestedBomCostService;

    @Override
    @Transactional
    public BomRecipe createRecipe(String factoryId, CreateBomRecipeRequest req) {
        log.info("Creating BOM recipe: factory={}, product={}, items={}",
                factoryId, req.getProductTypeId(), req.getItems().size());

        BomRecipe recipe = new BomRecipe();
        recipe.setFactoryId(factoryId);
        recipe.setRecipeCode(generateRecipeCode(factoryId));
        recipe.setProductTypeId(req.getProductTypeId());
        recipe.setProductName(req.getProductName() != null ? req.getProductName() : req.getProductTypeId());
        recipe.setVersion(1);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(req.getOverallYieldRate() != null
                ? req.getOverallYieldRate() : new BigDecimal("100.00"));
        recipe.setOutputQuantityPerUnit(req.getOutputQuantityPerUnit());
        recipe.setOutputUnit(req.getOutputUnit());
        recipe.setStatus(BomRecipe.Status.DRAFT);
        recipe.setSourceType(req.getSourceType() != null ? req.getSourceType() : BomRecipe.SourceType.MANUAL);
        recipe.setSourceSampleId(req.getSourceSampleId());
        recipe.setNotes(req.getNotes());

        // First persist parent (UUID assigned by @PrePersist) to get recipe.id for items.
        recipe = recipeRepo.save(recipe);

        // Build items with rehydrated material_name + denormalized unit if not provided.
        List<BomRecipeItem> items = new ArrayList<>();
        for (BomRecipeItemDTO dto : req.getItems()) {
            items.add(buildItem(factoryId, recipe.getId(), dto));
        }
        itemRepo.saveAll(items);
        recipe.setItems(items);

        // Initial cost calculation (material cost only; labor/overhead deferred to Day 5).
        recomputeMaterialCost(recipe);
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

        if (req.getProductName() != null) recipe.setProductName(req.getProductName());
        if (req.getOverallYieldRate() != null) recipe.setOverallYieldRate(req.getOverallYieldRate());
        if (req.getOutputQuantityPerUnit() != null) recipe.setOutputQuantityPerUnit(req.getOutputQuantityPerUnit());
        if (req.getOutputUnit() != null) recipe.setOutputUnit(req.getOutputUnit());
        if (req.getNotes() != null) recipe.setNotes(req.getNotes());

        // PUT is full-replace for items: soft-delete existing, persist new list.
        if (req.getItems() != null) {
            List<BomRecipeItem> oldItems = itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
            for (BomRecipeItem old : oldItems) {
                old.softDelete();
            }
            itemRepo.saveAll(oldItems);

            List<BomRecipeItem> newItems = new ArrayList<>();
            for (BomRecipeItemDTO dto : req.getItems()) {
                newItems.add(buildItem(factoryId, recipe.getId(), dto));
            }
            itemRepo.saveAll(newItems);
            // IMPORTANT: keep same Hibernate PersistentBag reference (clear+addAll, not setItems).
            recipe.getItems().clear();
            recipe.getItems().addAll(newItems);
        }

        recomputeMaterialCost(recipe);
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public BomRecipe activateRecipe(String factoryId, String recipeId, Long operatorId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可激活; 当前 status=" + recipe.getStatus());
        }

        // Clear is_current=true on other versions of same product.
        List<BomRecipe> others = recipeRepo.findCurrentVersionsExcluding(
                factoryId, recipe.getProductTypeId(), recipe.getId());
        for (BomRecipe other : others) {
            other.setIsCurrent(false);
            recipeRepo.save(other);
        }

        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setActivatedAt(LocalDateTime.now());
        recipe.setActivatedBy(operatorId);
        return recipeRepo.save(recipe);
    }

    @Override
    @Transactional
    public BomRecipe cloneRecipe(String factoryId, String recipeId) {
        BomRecipe source = loadRecipe(factoryId, recipeId);
        Integer maxVersion = recipeRepo.findMaxVersion(factoryId, source.getProductTypeId());

        BomRecipe clone = new BomRecipe();
        clone.setFactoryId(factoryId);
        clone.setRecipeCode(generateRecipeCode(factoryId));
        clone.setProductTypeId(source.getProductTypeId());
        clone.setProductName(source.getProductName());
        clone.setVersion(maxVersion + 1);
        clone.setIsCurrent(false);  // clone 不自动 current, 用户激活后才是
        clone.setOverallYieldRate(source.getOverallYieldRate());
        clone.setOutputQuantityPerUnit(source.getOutputQuantityPerUnit());
        clone.setOutputUnit(source.getOutputUnit());
        clone.setStatus(BomRecipe.Status.DRAFT);
        clone.setSourceType(BomRecipe.SourceType.MANUAL);
        clone.setNotes("克隆自 " + source.getRecipeCode() + " (v" + source.getVersion() + ")");
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
            item.setUnit(src.getUnit());
            item.setUnitPrice(src.getUnitPrice());
            item.setTaxRate(src.getTaxRate());
            item.setMaterialCategory(src.getMaterialCategory());
            item.setSortOrder(src.getSortOrder());
            item.setIsOptional(src.getIsOptional());
            item.setSubstituteGroup(src.getSubstituteGroup());
            item.setRemark(src.getRemark());
            // SP4-T3: propagate per_portion + semi_finished_ref_code on clone
            item.setPerPortion(src.getPerPortion() != null ? src.getPerPortion() : false);
            item.setSemiFinishedRefCode(src.getSemiFinishedRefCode());
            clonedItems.add(item);
        }
        itemRepo.saveAll(clonedItems);
        // IMPORTANT: keep same Hibernate PersistentBag reference (clear+addAll, not setItems).
        clone.getItems().clear();
        clone.getItems().addAll(clonedItems);
        recomputeMaterialCost(clone);
        return recipeRepo.save(clone);
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
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw new IllegalStateException(
                    "只有 DRAFT 状态可删除; 当前 status=" + recipe.getStatus()
                    + ", 用 archive 替代");
        }
        recipe.softDelete();
        recipeRepo.save(recipe);
    }

    @Override
    public BomRecipe getRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        // 非 @Transactional 读路径: recipe 已 detached, items 是未初始化懒代理.
        // 必须用 setItems 替换字段引用 (而非 clear()+addAll) —— 后者会强制初始化
        // detached 懒代理 → LazyInitializationException (no Session).
        // orphanRemoval 异常只在 @Transactional 写方法 flush 时触发, 此处无 flush 无风险.
        recipe.setItems(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId()));
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
        // IMPORTANT: use refreshItemsInPlace to keep Hibernate PersistentBag reference intact.
        refreshItemsInPlace(recipe);
        recomputeMaterialCost(recipe);
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
        BomRecipeItem item = buildItem(factoryId, recipe.getId(), dto);
        item = itemRepo.save(item);
        // Touch recipe to mark updated + recompute cost.
        // IMPORTANT: must NOT replace the Hibernate-managed collection reference (orphanRemoval=true).
        // Using clear+addAll keeps the same List instance so Hibernate's orphan tracking stays intact.
        refreshItemsInPlace(recipe);
        recomputeMaterialCost(recipe);
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
        // T159-B R3: UoM dimension guard on update path.
        // Look up the live material name (for 防呆 message) + canonical unit via item's materialTypeId.
        if (dto.getUnit() != null && !dto.getUnit().isBlank()) {
            RawMaterialType mt = materialTypeRepo.findById(item.getMaterialTypeId()).orElse(null);
            if (mt != null) {
                checkBomUnitCompatible(mt, dto.getUnit());
            }
        }
        applyDtoToItem(dto, item);
        item = itemRepo.save(item);
        refreshItemsInPlace(recipe);
        recomputeMaterialCost(recipe);
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
        item.softDelete();
        itemRepo.save(item);
        refreshItemsInPlace(recipe);
        recomputeMaterialCost(recipe);
        recipeRepo.save(recipe);
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
    private BomRecipeItem buildItem(String factoryId, String recipeId, BomRecipeItemDTO dto) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(recipeId);
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

        // T159-B R3: UoM dimension guard at BOM write time.
        checkBomUnitCompatible(mt.get(), dto.getUnit());

        // SP8: primaryCodeRef — auto-backfill from RawMaterialType.primaryCode when DTO doesn't supply it
        if (dto.getPrimaryCodeRef() != null) {
            item.setPrimaryCodeRef(dto.getPrimaryCodeRef());
        } else if (mt.get().getPrimaryCode() != null) {
            item.setPrimaryCodeRef(mt.get().getPrimaryCode());
        }

        // 包材规格自动推算: PACKAGING 行若 DTO 未手填 standardQuantity 且物料有 packQtyPerProduct,
        // 则自动写入 standardQuantity = packQtyPerProduct (每成品单位用量).
        // 手填优先 (dto.standardQuantity != null) — 向后兼容, 不破坏已有手填包材 BOM.
        applyPackagingSpecAutoInfer(dto, mt.get());

        applyDtoToItem(dto, item);
        return item;
    }

    /**
     * 包材规格自动推算 (Packaging Spec Auto-Inference).
     *
     * <p>触发条件 (AND):
     * <ol>
     *   <li>{@code dto.materialCategory} 为 {@code PACKAGING}</li>
     *   <li>{@code dto.standardQuantity} 为 {@code null} (未手填)</li>
     *   <li>{@code mt.packQtyPerProduct} 不为 {@code null} (物料已配置规格)</li>
     * </ol>
     *
     * <p>当条件满足时, 将 {@code dto.standardQuantity} 设为 {@code mt.packQtyPerProduct},
     * 后续 {@link #applyDtoToItem} 正常按手填路径处理.
     *
     * <p>手填优先 — 若 {@code dto.standardQuantity != null} 直接跳过, 不覆盖用户明确输入.
     * unit 为 null 时同步从物料主数据回填 (包材单位通常是"个/袋/盒").
     *
     * @param dto DTO (mutated in-place when auto-infer applies)
     * @param mt  已查出的原料主数据
     */
    private void applyPackagingSpecAutoInfer(BomRecipeItemDTO dto, RawMaterialType mt) {
        boolean isPackaging = "PACKAGING".equals(dto.getMaterialCategory());
        if (!isPackaging) {
            return;
        }
        if (dto.getStandardQuantity() != null) {
            // 手填优先 — 用户已明确给了数量, 尊重用户
            return;
        }
        BigDecimal packQty = mt.getPackQtyPerProduct();
        if (packQty == null) {
            // 物料未配置规格 — 维持诚实 null, 让上层 @NotNull 校验拒绝或用户手填
            return;
        }
        // 自动推算: standardQuantity = packQtyPerProduct
        dto.setStandardQuantity(packQty);
        log.info("[PackagingSpecInfer] materialTypeId={} name={} packQtyPerProduct={} → BOM standardQuantity auto-set",
                mt.getId(), mt.getName(), packQty);

        // unit 回填: 若 DTO 未给单位, 从物料主数据取 (包材通常是"个"/"袋"/"盒")
        if (dto.getUnit() == null || dto.getUnit().isBlank()) {
            String matUnit = mt.getUnit();
            if (matUnit != null && !matUnit.isBlank()) {
                dto.setUnit(matUnit);
                log.debug("[PackagingSpecInfer] unit auto-set from material: {}", matUnit);
            }
        }
    }

    /** Apply DTO fields to entity (used by add + update). */
    private void applyDtoToItem(BomRecipeItemDTO dto, BomRecipeItem item) {
        item.setStandardQuantity(dto.getStandardQuantity());
        item.setYieldRate(dto.getYieldRate() != null ? dto.getYieldRate() : new BigDecimal("100.00"));
        item.setUnit(dto.getUnit());
        item.setUnitPrice(dto.getUnitPrice());
        // F6 诚实 null: taxRate 未传 → 保持 null (不默认 0%). 默认 0% 会把"未配置税率"静默当
        //   "未税/含税无差", 成本侧失真. null → 上层成本计算诚实标缺, 让用户补填.
        item.setTaxRate(dto.getTaxRate());
        item.setMaterialCategory(dto.getMaterialCategory() != null ? dto.getMaterialCategory() : "RAW");
        item.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        item.setIsOptional(dto.getIsOptional() != null ? dto.getIsOptional() : false);
        item.setSubstituteGroup(dto.getSubstituteGroup());
        item.setRemark(dto.getRemark());
        // SP4-T3: 按份计量 + 半成品引用
        item.setPerPortion(dto.getPerPortion() != null ? dto.getPerPortion() : false);
        item.setSemiFinishedRefCode(dto.getSemiFinishedRefCode());
        // SP1: 嵌套 BOM 子产品引用 (组合装/先做后用)
        item.setSubProductTypeId(dto.getSubProductTypeId());
        // SP8: primaryCodeRef null-guard update (only update if DTO supplies it; auto-backfill done in buildItem)
        if (dto.getPrimaryCodeRef() != null) {
            item.setPrimaryCodeRef(dto.getPrimaryCodeRef());
        }
        // Cache actual_quantity (also computable @Transient, but cached for query speed).
        // Note: item_cost for nested-component rows is resolved via NestedBomCostService at recipe-level;
        // the cached itemCost here reflects only the local unitPrice (plain material rows).
        // recomputeMaterialCost will use NestedBomCostService.resolveItemCost for subProductTypeId rows.
        item.setActualQuantity(item.calculateActualQuantity());
        item.setItemCost(item.computeItemCost());
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
    private void recomputeMaterialCost(BomRecipe recipe) {
        List<BomRecipeItem> items = recipe.getItems();
        if (items == null || items.isEmpty()) {
            recipe.setTotalMaterialCost(BigDecimal.ZERO);
            recipe.setTotalCost(recipe.getTotalLaborCost() != null
                    ? recipe.getTotalLaborCost().add(recipe.getTotalOverheadCost() != null
                            ? recipe.getTotalOverheadCost() : BigDecimal.ZERO)
                    : BigDecimal.ZERO);
            return;
        }
        BigDecimal materialCost = BigDecimal.ZERO;
        boolean hasNullPrice = false;
        for (BomRecipeItem item : items) {
            // SP1: 嵌套子产品行 → 委托 NestedBomCostService; 普通行 → item.computeItemCost()
            BigDecimal cost = nestedBomCostService.isNestedComponent(item)
                    ? nestedBomCostService.resolveItemCost(recipe.getFactoryId(), item)
                    : item.computeItemCost();
            if (cost != null) {
                materialCost = materialCost.add(cost);
            } else {
                hasNullPrice = true;
            }
        }
        recipe.setTotalMaterialCost(hasNullPrice ? null : materialCost.setScale(4, RoundingMode.HALF_UP));

        BigDecimal labor = recipe.getTotalLaborCost() != null ? recipe.getTotalLaborCost() : BigDecimal.ZERO;
        BigDecimal overhead = recipe.getTotalOverheadCost() != null ? recipe.getTotalOverheadCost() : BigDecimal.ZERO;
        if (recipe.getTotalMaterialCost() != null) {
            recipe.setTotalCost(recipe.getTotalMaterialCost().add(labor).add(overhead));
        } else {
            recipe.setTotalCost(null);
        }
    }

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

    /** Generate {@code BOM-YYYYMMDD-NNN} where NNN = today's recipe count + 1 (factory-scoped). */
    private String generateRecipeCode(String factoryId) {
        String today = LocalDate.now().format(CODE_DATE_FMT);
        String prefix = "BOM-" + today + "-";
        long countToday = recipeRepo.countByRecipeCodePrefix(factoryId, prefix + "%");
        return String.format("%s%03d", prefix, countToday + 1);
    }
}
