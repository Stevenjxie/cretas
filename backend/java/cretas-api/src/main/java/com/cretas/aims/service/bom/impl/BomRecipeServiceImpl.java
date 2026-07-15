package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest.SeasoningItemDTO;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomProcessSeasoningRepository;
import com.cretas.aims.entity.bom.BomProcessSeasoning;
import com.cretas.aims.dto.bom.ProcessSeasoningParamDTO;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final ProductWorkProcessRepository productWorkProcessRepo;
    private final MaterialUomConverter materialUomConverter;
    /** SP1: 嵌套 BOM 成本聚合 (组合装/先做后用). */
    private final NestedBomCostService nestedBomCostService;
    /** U5: BOM 调料明细 repo (BOM 统管配方+锅序, 2026-06-24). */
    private final BomSeasoningItemRepository seasoningItemRepo;
    private final BomProcessSeasoningRepository bomProcessSeasoningRepo;

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
        recipe.setVersion(recipeRepo.findMaxVersion(factoryId, req.getProductTypeId()) + 1);
        // 草稿不占用“当前生效”槽位；只有 activateRecipe 能设置 isCurrent=true。
        recipe.setIsCurrent(false);
        // 整体出成率由正式批次报工历史自动学习，配方头不接收人工初始值。
        recipe.setOverallYieldRate(null);
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
        // overallYieldRate 是系统学习字段，草稿编辑不可人工覆盖。
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

        // 同一 SKU 只能有一个 ACTIVE/current 版本。历史脏数据可能 ACTIVE 但 current=false，
        // 也必须在同一事务中归档，确保状态文案与真实生效语义一致。
        List<BomRecipe> others = recipeRepo.findCompetingVersionsForActivation(
                factoryId, recipe.getProductTypeId(), recipe.getId(), BomRecipe.Status.ACTIVE);
        for (BomRecipe other : others) {
            other.setStatus(BomRecipe.Status.ARCHIVED);
            other.setIsCurrent(false);
            recipeRepo.save(other);
        }
        // 原子性修复: 显式 flush 把旧版本 is_current=false 先落库, 再设新版本 current。
        // 否则同一 @Transactional 内 Hibernate 提交时 flush 顺序不确定, 可能先把新版本
        // is_current=true 落库再清旧版本, 瞬时存在两个 is_current=true → 撞 partial-unique
        // uk_br_product_current → DataIntegrityViolation (flush-order flaky: 多数情况偶然成功,
        // 偶发激活 409 "数据已存在"; 2026-06-29 补录调料配方时在猪舌产品复现)。
        recipeRepo.flush();

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
            item.setPrimaryCode(src.getPrimaryCode());
            item.setPrimaryCodeRef(src.getPrimaryCodeRef());
            clonedItems.add(item);
        }
        itemRepo.saveAll(clonedItems);
        // IMPORTANT: keep same Hibernate PersistentBag reference (clear+addAll, not setItems).
        clone.getItems().clear();
        clone.getItems().addAll(clonedItems);

        // BOM 统管配方+锅序 (2026-06-24): clone 必须带上折叠后的配方 (锅序参数 + 调料明细),
        // 否则克隆出的 DRAFT 调料为空 → 激活后调料成本静默归 0 (audit Issue 1).
        clone.setCookingPotBaseKg(source.getCookingPotBaseKg());
        clone.setSubsequentPotRatio(source.getSubsequentPotRatio());
        clone.setInjectionRate(source.getInjectionRate());
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
            cs.setSubsequentPotRatio(s.getSubsequentPotRatio());
            clonedSeasoning.add(cs);
        }
        seasoningItemRepo.saveAll(clonedSeasoning);

        // 调料配方按工序 (2026-07-13): 克隆 per-工序 参数, 否则新 DRAFT 丢锅序/注射量
        List<BomProcessSeasoning> clonedParams = new ArrayList<>();
        for (BomProcessSeasoning p : bomProcessSeasoningRepo.findByRecipeIdAndDeletedAtIsNull(source.getId())) {
            BomProcessSeasoning cp = new BomProcessSeasoning();
            cp.setRecipeId(clone.getId());
            cp.setFactoryId(factoryId);
            cp.setWorkProcessId(p.getWorkProcessId());
            cp.setSubsequentPotRatio(p.getSubsequentPotRatio());
            cp.setInjectionAmountKg(p.getInjectionAmountKg());
            cp.setNotes(p.getNotes());
            clonedParams.add(cp);
        }
        bomProcessSeasoningRepo.saveAll(clonedParams);

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
        RawMaterialType mt = materialTypeRepo.findById(item.getMaterialTypeId()).orElse(null);
        if (dto.getUnit() != null && !dto.getUnit().isBlank()) {
            if (mt != null) {
                checkBomUnitCompatible(mt, dto.getUnit());
            }
        }
        if (mt != null) {
            applyPrimaryCode(dto, item, mt);
        }
        validateItemQuantity(dto);
        applyDtoToItem(dto, item);
        if (mt != null) {
            applyMaterialMasterPricing(item, mt);
        }
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

    // ========== U5: 调料配方 CRUD ==========

    @Override
    public BomSeasoningResponse getSeasoning(String factoryId, String recipeId) {
        BomRecipe recipe = loadRecipe(factoryId, recipeId);
        return buildSeasoningResponse(recipe, seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipeId));
    }

    @Override
    public Optional<BomSeasoningResponse> getSeasoningByProduct(String factoryId, String productTypeId) {
        Optional<BomRecipe> opt = recipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
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

        // Validate sections and resolve authoritative material snapshots before any write.
        List<SeasoningItemDTO> items = req.getSeasoningItems();
        List<RawMaterialType> resolvedMaterials = new ArrayList<>();
        Set<String> processMaterialKeys = new HashSet<>();
        if (items != null) {
            for (SeasoningItemDTO dto : items) {
                if (!"INJECTION".equals(dto.getSection()) && !"COOKING".equals(dto.getSection())) {
                    throw new BusinessException(400,
                            "调料 section 只允许 INJECTION 或 COOKING, 收到: " + dto.getSection())
                            .withHint("请将 section 改为 INJECTION(注射段) 或 COOKING(熟制段)")
                            .withHintTarget("section");
                }
                validateProductWorkProcess(factoryId, recipe, dto.getWorkProcessId());
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

        List<ProcessSeasoningParamDTO> params = req.getProcessParams();
        if (params != null) {
            Set<String> seenWp = new HashSet<>();
            for (ProcessSeasoningParamDTO p : params) {
                validateProductWorkProcess(factoryId, recipe, p.getWorkProcessId());
                if (!seenWp.add(p.getWorkProcessId())) {
                    throw new BusinessException(400, "同一工序的调料参数重复: " + p.getWorkProcessId())
                            .withHint("每道工序只能有一行锅序/注射量参数");
                }
            }
        }

        // Set pot params on the recipe (any null → keep unchanged).
        if (req.getCookingPotBaseKg() != null)    recipe.setCookingPotBaseKg(req.getCookingPotBaseKg());
        if (req.getSubsequentPotRatio() != null)  recipe.setSubsequentPotRatio(req.getSubsequentPotRatio());
        if (req.getInjectionRate() != null)       recipe.setInjectionRate(req.getInjectionRate());

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
                newItems.add(si);
            }
        }
        seasoningItemRepo.saveAll(newItems);

        // 调料配方按工序 (2026-07-13): 全量替换该 recipe 的 per-工序 参数 (bom_process_seasoning)。
        List<BomProcessSeasoning> oldParams = bomProcessSeasoningRepo.findByRecipeIdAndDeletedAtIsNull(recipeId);
        for (BomProcessSeasoning old : oldParams) {
            old.softDelete();
        }
        bomProcessSeasoningRepo.saveAll(oldParams);
        List<BomProcessSeasoning> newParams = new ArrayList<>();
        if (params != null) {
            for (ProcessSeasoningParamDTO p : params) {
                BomProcessSeasoning bps = new BomProcessSeasoning();
                bps.setRecipeId(recipeId);
                bps.setFactoryId(factoryId);
                bps.setWorkProcessId(p.getWorkProcessId());
                bps.setSubsequentPotRatio(p.getSubsequentPotRatio());
                bps.setInjectionAmountKg(p.getInjectionAmountKg());
                bps.setNotes(p.getNotes());
                newParams.add(bps);
            }
        }
        bomProcessSeasoningRepo.saveAll(newParams);

        recipeRepo.save(recipe);

        return buildSeasoningResponse(recipe, newItems);
    }

    private void validateProductWorkProcess(String factoryId, BomRecipe recipe, String workProcessId) {
        if (workProcessId == null || workProcessId.isBlank()) {
            throw new BusinessException(400, "调料必须选择工序")
                    .withHint("请选择该 SKU 配置的有效工序")
                    .withHintTarget("workProcessId");
        }
        if (!productWorkProcessRepo.existsByFactoryIdAndProductTypeIdAndWorkProcessId(
                factoryId, recipe.getProductTypeId(), workProcessId)) {
            throw new BusinessException(400, "所选工序不是该 SKU 的有效工序: " + workProcessId)
                    .withHint("请重新选择该 SKU 工序路线中的工序")
                    .withHintTarget("workProcessId");
        }
    }

    private BomSeasoningResponse buildSeasoningResponse(BomRecipe recipe, List<BomSeasoningItem> seasoningItems) {
        BomSeasoningResponse resp = new BomSeasoningResponse();
        resp.setBomRecipeId(recipe.getId());
        resp.setProductTypeId(recipe.getProductTypeId());
        resp.setProductName(recipe.getProductName());
        resp.setStatus(recipe.getStatus());
        resp.setCookingPotBaseKg(recipe.getCookingPotBaseKg());
        resp.setSubsequentPotRatio(recipe.getSubsequentPotRatio());
        resp.setInjectionRate(recipe.getInjectionRate());
        resp.setSeasoningItems(seasoningItems);
        // 调料配方按工序 (2026-07-13): 带上 per-工序 锅序/注射量参数
        List<ProcessSeasoningParamDTO> params = new ArrayList<>();
        for (BomProcessSeasoning bps : bomProcessSeasoningRepo.findByRecipeIdAndDeletedAtIsNull(recipe.getId())) {
            ProcessSeasoningParamDTO dto = new ProcessSeasoningParamDTO();
            dto.setWorkProcessId(bps.getWorkProcessId());
            dto.setSubsequentPotRatio(bps.getSubsequentPotRatio());
            dto.setInjectionAmountKg(bps.getInjectionAmountKg());
            dto.setNotes(bps.getNotes());
            params.add(dto);
        }
        resp.setProcessParams(params);
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

        applyPrimaryCode(dto, item, mt.get());

        // 包材规格自动推算: PACKAGING 行若 DTO 未手填 standardQuantity 且物料有 packQtyPerProduct,
        // 则自动写入 standardQuantity = packQtyPerProduct (每成品单位用量).
        // 手填优先 (dto.standardQuantity != null) — 向后兼容, 不破坏已有手填包材 BOM.
        applyPackagingSpecAutoInfer(dto, mt.get());

        validateItemQuantity(dto);
        applyDtoToItem(dto, item);
        applyMaterialMasterPricing(item, mt.get());
        return item;
    }

    private void validateItemQuantity(BomRecipeItemDTO dto) {
        String category = dto.getMaterialCategory() == null ? "RAW" : dto.getMaterialCategory();
        BigDecimal quantity = dto.getStandardQuantity();
        if ((quantity == null && !"RAW".equalsIgnoreCase(category))
                || (quantity != null && quantity.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("非原料 BOM 的每成品用量必须大于 0");
        }
    }

    /** BOM 价格只读继承物料主数据/库存移动均价，禁止形成第二套人工价格真值。 */
    private void applyMaterialMasterPricing(BomRecipeItem item, RawMaterialType material) {
        BigDecimal price = material.getMovingAvgPrice() != null
                ? material.getMovingAvgPrice()
                : material.getUnitPrice();
        item.setUnitPrice(price);
        item.setTaxRate(material.getTaxRate() == null
                ? null
                : material.getTaxRate().getRate().multiply(BigDecimal.valueOf(100)));
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
        // 出成率是同工厂、同 SKU 正式批次的系统统计，不再让 BOM 单行人工反推。
        // 旧列保留 100% 中性值，以兼容存量成本公式和 NOT NULL 约束。
        item.setYieldRate(new BigDecimal("100.00"));
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
