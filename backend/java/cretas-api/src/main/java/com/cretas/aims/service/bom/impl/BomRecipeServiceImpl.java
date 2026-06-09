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
            recipe.setItems(newItems);
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
        clone.setItems(clonedItems);
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
        // Touch items to trigger LAZY load.
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
        recipe.setItems(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId()));
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
        recipe.setItems(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId()));
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
        recipe.setItems(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId()));
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
        recipe.setItems(itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId()));
        recomputeMaterialCost(recipe);
        recipeRepo.save(recipe);
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

        applyDtoToItem(dto, item);
        return item;
    }

    /** Apply DTO fields to entity (used by add + update). */
    private void applyDtoToItem(BomRecipeItemDTO dto, BomRecipeItem item) {
        item.setStandardQuantity(dto.getStandardQuantity());
        item.setYieldRate(dto.getYieldRate() != null ? dto.getYieldRate() : new BigDecimal("100.00"));
        item.setUnit(dto.getUnit());
        item.setUnitPrice(dto.getUnitPrice());
        item.setTaxRate(dto.getTaxRate() != null ? dto.getTaxRate() : BigDecimal.ZERO);
        item.setMaterialCategory(dto.getMaterialCategory() != null ? dto.getMaterialCategory() : "RAW");
        item.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        item.setIsOptional(dto.getIsOptional() != null ? dto.getIsOptional() : false);
        item.setSubstituteGroup(dto.getSubstituteGroup());
        item.setRemark(dto.getRemark());
        // SP4-T3: 按份计量 + 半成品引用
        item.setPerPortion(dto.getPerPortion() != null ? dto.getPerPortion() : false);
        item.setSemiFinishedRefCode(dto.getSemiFinishedRefCode());
        // Cache actual_quantity + item_cost (also computable @Transient, but cached for query speed).
        item.setActualQuantity(item.calculateActualQuantity());
        item.setItemCost(item.computeItemCost());
    }

    /** Recompute material cost from items (sum of itemCost). */
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
            BigDecimal cost = item.computeItemCost();
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
