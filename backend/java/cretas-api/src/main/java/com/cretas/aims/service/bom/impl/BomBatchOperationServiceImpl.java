package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BatchAddRequest;
import com.cretas.aims.dto.bom.BatchDeleteRequest;
import com.cretas.aims.dto.bom.BatchModifyRequest;
import com.cretas.aims.dto.bom.BatchReplaceRequest;
import com.cretas.aims.dto.bom.BatchResult;
import com.cretas.aims.dto.bom.EcnCreateRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomBatchOperationService;
import com.cretas.aims.service.bom.BomVersionService;
import com.cretas.aims.service.bom.ECNService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BomBatchOperationService implementation (Sprint 3 Track-H / M-BOM-VER-1).
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomBatchOperationServiceImpl implements BomBatchOperationService {

    private final BomRecipeRepository recipeRepo;
    private final BomRecipeItemRepository itemRepo;
    private final RawMaterialTypeRepository materialRepo;
    private final BomVersionService versionService;
    private final ECNService ecnService;

    // ========== batchModify ==========

    @Override
    @Transactional
    public BatchResult batchModify(BatchModifyRequest req) {
        List<BomRecipe> recipes = resolveScope(req.getFactoryId(), req.getMaterialId(),
                req.isAllInFactory(), req.getRecipeIds());

        BatchResult.BatchResultBuilder result = BatchResult.builder().operation("MODIFY");
        Map<String, String> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> affected = new ArrayList<>();

        for (BomRecipe recipe : recipes) {
            List<BomRecipeItem> items = loadItems(recipe);
            List<BomRecipeItem> matches = items.stream()
                    .filter(i -> req.getMaterialId().equals(i.getMaterialTypeId()))
                    .toList();
            if (matches.isEmpty()) {
                skipped.put(recipe.getId(), "recipe contains no item for material " + req.getMaterialId());
                continue;
            }
            // Build snapshot reflecting post-modification state.
            Map<String, Object> snapshot = buildSnapshotMap(recipe, items, item -> {
                if (req.getMaterialId().equals(item.getMaterialTypeId())) {
                    Map<String, Object> patched = new LinkedHashMap<>(itemToMap(item));
                    boolean raw = "RAW".equalsIgnoreCase(item.getMaterialCategory());
                    if (!raw && (req.getNewStandardQuantity() == null
                            || req.getNewStandardQuantity().compareTo(BigDecimal.ZERO) <= 0)) {
                        throw new IllegalArgumentException("非原料 BOM 的每成品用量必须大于 0");
                    }
                    patched.put("standardQuantity", raw ? null : req.getNewStandardQuantity());
                    if (raw) patched.put("yieldRate", new BigDecimal("100.00"));
                    if (req.getNewUnit() != null) patched.put("unit", req.getNewUnit());
                    // Recompute actualQuantity if yieldRate present.
                    BigDecimal yieldRate = item.getYieldRate();
                    BigDecimal qty = raw ? null : req.getNewStandardQuantity();
                    if (qty != null && yieldRate != null
                            && yieldRate.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ratio = yieldRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                        patched.put("actualQuantity", qty.divide(ratio, 6, RoundingMode.HALF_UP));
                    }
                    return patched;
                }
                return itemToMap(item);
            });
            BomVersion draft = versionService.createDraftWithSnapshot(
                    req.getFactoryId(), recipe.getId(), snapshot, req.getCreatedBy());
            drafts.put(recipe.getId(), draft.getId());
            affected.add(recipe.getId());
        }

        EngineeringChangeNotice ecn = createDraftEcn(req.getFactoryId(),
                affected.isEmpty() ? null : affected.get(0),
                req.getEcnReason(), req.getEcnReasonDetail(), req.getEffectiveDate(),
                req.getCreatedBy(),
                buildBatchImpactScope("MODIFY", req.getMaterialId(), affected,
                        Map.of("newStandardQuantity", req.getNewStandardQuantity(),
                                "newUnit", req.getNewUnit() == null ? "" : req.getNewUnit())));

        result.ecnId(ecn.getId()).ecnNumber(ecn.getEcnNumber())
                .affectedRecipeIds(affected)
                .draftVersionIds(drafts)
                .skippedRecipes(skipped)
                .warnings(warnings);
        log.info("batchModify done: factory={}, material={}, affected={}, skipped={}, ecn={}",
                req.getFactoryId(), req.getMaterialId(), affected.size(), skipped.size(),
                ecn.getEcnNumber());
        return result.build();
    }

    // ========== batchReplace ==========

    @Override
    @Transactional
    public BatchResult batchReplace(BatchReplaceRequest req) {
        List<BomRecipe> recipes = resolveScope(req.getFactoryId(), req.getOldMaterialId(),
                req.isAllInFactory(), req.getRecipeIds());

        RawMaterialType newMat = materialRepo.findById(req.getNewMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("newMaterial not found: " + req.getNewMaterialId()));
        List<String> warnings = new ArrayList<>();

        BatchResult.BatchResultBuilder result = BatchResult.builder().operation("REPLACE");
        Map<String, String> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> affected = new ArrayList<>();

        for (BomRecipe recipe : recipes) {
            List<BomRecipeItem> items = loadItems(recipe);
            boolean anyMatch = items.stream().anyMatch(i -> req.getOldMaterialId().equals(i.getMaterialTypeId()));
            if (!anyMatch) {
                skipped.put(recipe.getId(), "recipe contains no item for material " + req.getOldMaterialId());
                continue;
            }
            Map<String, Object> snapshot = buildSnapshotMap(recipe, items, item -> {
                if (req.getOldMaterialId().equals(item.getMaterialTypeId())) {
                    Map<String, Object> patched = new LinkedHashMap<>(itemToMap(item));
                    patched.put("materialTypeId", req.getNewMaterialId());
                    patched.put("materialName", newMat.getName());
                    patched.put("unitPrice", masterPrice(newMat));
                    patched.put("taxRate", masterTaxPercent(newMat));
                    return patched;
                }
                return itemToMap(item);
            });
            BomVersion draft = versionService.createDraftWithSnapshot(
                    req.getFactoryId(), recipe.getId(), snapshot, req.getCreatedBy());
            drafts.put(recipe.getId(), draft.getId());
            affected.add(recipe.getId());
        }

        EngineeringChangeNotice ecn = createDraftEcn(req.getFactoryId(),
                affected.isEmpty() ? null : affected.get(0),
                req.getEcnReason(), req.getEcnReasonDetail(), req.getEffectiveDate(),
                req.getCreatedBy(),
                buildBatchImpactScope("REPLACE", req.getOldMaterialId(), affected,
                        Map.of("newMaterialId", req.getNewMaterialId(),
                                "preserveQuantity", req.isPreserveQuantity())));

        result.ecnId(ecn.getId()).ecnNumber(ecn.getEcnNumber())
                .affectedRecipeIds(affected)
                .draftVersionIds(drafts)
                .skippedRecipes(skipped)
                .warnings(warnings);
        log.info("batchReplace done: factory={}, {} → {}, affected={}, ecn={}",
                req.getFactoryId(), req.getOldMaterialId(), req.getNewMaterialId(),
                affected.size(), ecn.getEcnNumber());
        return result.build();
    }

    // ========== batchDelete ==========

    @Override
    @Transactional
    public BatchResult batchDelete(BatchDeleteRequest req) {
        List<BomRecipe> recipes = resolveScope(req.getFactoryId(), req.getMaterialId(),
                req.isAllInFactory(), req.getRecipeIds());

        BatchResult.BatchResultBuilder result = BatchResult.builder().operation("DELETE");
        Map<String, String> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> affected = new ArrayList<>();

        for (BomRecipe recipe : recipes) {
            List<BomRecipeItem> items = loadItems(recipe);
            boolean deletesWorkflowSlot = items.stream()
                    .filter(item -> req.getMaterialId().equals(item.getMaterialTypeId()))
                    .anyMatch(this::hasWorkflowIdentity);
            if (deletesWorkflowSlot) {
                throw new BusinessException(
                        409,
                        "批量删除不能移除 Workflow 必需投入槽")
                        .withCode("BOM_WORKFLOW_REQUIRED_INPUT_DELETE_FORBIDDEN")
                        .withHint("请先在 Workflow 中删除对应投入并保存新修订")
                        .withHintTarget("workflow");
            }
            List<BomRecipeItem> remaining = items.stream()
                    .filter(i -> !req.getMaterialId().equals(i.getMaterialTypeId()))
                    .toList();
            if (remaining.size() == items.size()) {
                skipped.put(recipe.getId(), "recipe contains no item for material " + req.getMaterialId());
                continue;
            }
            Map<String, Object> snapshot = buildSnapshotMapFromList(recipe, remaining);
            BomVersion draft = versionService.createDraftWithSnapshot(
                    req.getFactoryId(), recipe.getId(), snapshot, req.getCreatedBy());
            drafts.put(recipe.getId(), draft.getId());
            affected.add(recipe.getId());
        }

        EngineeringChangeNotice ecn = createDraftEcn(req.getFactoryId(),
                affected.isEmpty() ? null : affected.get(0),
                req.getEcnReason(), req.getEcnReasonDetail(), req.getEffectiveDate(),
                req.getCreatedBy(),
                buildBatchImpactScope("DELETE", req.getMaterialId(), affected,
                        Map.of("deletedMaterialId", req.getMaterialId())));

        result.ecnId(ecn.getId()).ecnNumber(ecn.getEcnNumber())
                .affectedRecipeIds(affected)
                .draftVersionIds(drafts)
                .skippedRecipes(skipped)
                .warnings(new ArrayList<>());
        log.info("batchDelete done: factory={}, material={}, affected={}, ecn={}",
                req.getFactoryId(), req.getMaterialId(), affected.size(), ecn.getEcnNumber());
        return result.build();
    }

    // ========== batchAdd ==========

    @Override
    @Transactional
    public BatchResult batchAdd(BatchAddRequest req) {
        // Scope for ADD: explicit recipeIds (no material-anchored lookup) OR all factory BOMs.
        List<BomRecipe> recipes;
        if (req.isAllInFactory()) {
            recipes = recipeRepo.findAll().stream()
                    .filter(r -> req.getFactoryId().equals(r.getFactoryId()))
                    .toList();
        } else if (req.getRecipeIds() != null && !req.getRecipeIds().isEmpty()) {
            recipes = recipeRepo.findAllById(req.getRecipeIds()).stream()
                    .filter(r -> req.getFactoryId().equals(r.getFactoryId()))
                    .toList();
        } else {
            throw new IllegalArgumentException(
                    "batchAdd requires allInFactory=true OR explicit recipeIds list");
        }

        RawMaterialType material = materialRepo.findById(req.getMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("material not found: " + req.getMaterialId()));
        List<String> warnings = new ArrayList<>();
        String category = req.getMaterialCategory() != null ? req.getMaterialCategory() : "RAW";
        boolean raw = "RAW".equalsIgnoreCase(category);
        if (raw) {
            throw new BusinessException(
                    409,
                    "批量新增不能创建无 Workflow 投入槽身份的原料行")
                    .withCode("BOM_WORKFLOW_RAW_INPUT_SLOT_REQUIRED")
                    .withHint("请从 BOM 工作区的系统投入槽配置主料或替代料")
                    .withHintTarget("workflow");
        }
        BigDecimal standardQuantity = raw ? null : req.getStandardQuantity();
        if (!raw && (standardQuantity == null || standardQuantity.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("非原料 BOM 的每成品用量必须大于 0");
        }
        BigDecimal yieldRate = new BigDecimal("100.00");

        BatchResult.BatchResultBuilder result = BatchResult.builder().operation("ADD");
        Map<String, String> drafts = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> affected = new ArrayList<>();

        for (BomRecipe recipe : recipes) {
            List<BomRecipeItem> items = loadItems(recipe);
            boolean alreadyPresent = items.stream()
                    .anyMatch(i -> req.getMaterialId().equals(i.getMaterialTypeId()));
            if (alreadyPresent && req.isSkipIfAlreadyPresent()) {
                skipped.put(recipe.getId(), "material " + req.getMaterialId() + " already present");
                continue;
            }
            // Build snapshot with the new item appended.
            List<Map<String, Object>> snapshotItems = new ArrayList<>();
            int maxSort = 0;
            for (BomRecipeItem existing : items) {
                snapshotItems.add(itemToMap(existing));
                if (existing.getSortOrder() != null) {
                    maxSort = Math.max(maxSort, existing.getSortOrder());
                }
            }
            Map<String, Object> newItem = new LinkedHashMap<>();
            newItem.put("id", null);
            newItem.put("materialTypeId", req.getMaterialId());
            newItem.put("materialName", material.getName());
            newItem.put("standardQuantity", standardQuantity);
            newItem.put("yieldRate", yieldRate);
            BigDecimal ratio = yieldRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            newItem.put("actualQuantity",
                    ratio.compareTo(BigDecimal.ZERO) > 0
                            ? (standardQuantity == null ? null : standardQuantity.divide(ratio, 6, RoundingMode.HALF_UP))
                            : standardQuantity);
            newItem.put("unit", req.getUnit());
            newItem.put("unitPrice", masterPrice(material));
            newItem.put("taxRate", masterTaxPercent(material));
            newItem.put("itemCost", null);
            newItem.put("materialCategory", category);
            newItem.put("sortOrder", maxSort + 10);
            newItem.put("isOptional", false);
            newItem.put("substituteGroup", null);
            newItem.put("remark", "batchAdd: created via BomBatchOperationService");
            snapshotItems.add(newItem);

            Map<String, Object> snapshot = recipeHeader(recipe);
            snapshot.put("items", snapshotItems);
            snapshot.put("snapshotTakenAt", Instant.now().toString());

            BomVersion draft = versionService.createDraftWithSnapshot(
                    req.getFactoryId(), recipe.getId(), snapshot, req.getCreatedBy());
            drafts.put(recipe.getId(), draft.getId());
            affected.add(recipe.getId());
        }

        EngineeringChangeNotice ecn = createDraftEcn(req.getFactoryId(),
                affected.isEmpty() ? null : affected.get(0),
                req.getEcnReason(), req.getEcnReasonDetail(), req.getEffectiveDate(),
                req.getCreatedBy(),
                buildBatchImpactScope("ADD", req.getMaterialId(), affected,
                        batchAddImpact(standardQuantity, req.getUnit(), category)));

        result.ecnId(ecn.getId()).ecnNumber(ecn.getEcnNumber())
                .affectedRecipeIds(affected)
                .draftVersionIds(drafts)
                .skippedRecipes(skipped)
                .warnings(warnings);
        log.info("batchAdd done: factory={}, material={}, affected={}, skipped={}, ecn={}",
                req.getFactoryId(), req.getMaterialId(), affected.size(), skipped.size(),
                ecn.getEcnNumber());
        return result.build();
    }

    // ========== helpers ==========

    private BigDecimal masterPrice(RawMaterialType material) {
        return material.getMovingAvgPrice() != null ? material.getMovingAvgPrice() : material.getUnitPrice();
    }

    private BigDecimal masterTaxPercent(RawMaterialType material) {
        return material.getTaxRate() == null ? null
                : material.getTaxRate().getRate().multiply(BigDecimal.valueOf(100));
    }

    private Map<String, Object> batchAddImpact(BigDecimal quantity, String unit, String category) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("standardQuantity", quantity);
        impact.put("unit", unit);
        impact.put("materialCategory", category);
        return impact;
    }

    /**
     * Resolve recipe scope: either explicit ids or all-in-factory filtered to recipes containing
     * the anchor material (skips obvious no-ops at scope level — finer per-recipe check inside).
     */
    private List<BomRecipe> resolveScope(String factoryId, String anchorMaterialId,
                                          boolean allInFactory, List<String> recipeIds) {
        if (recipeIds != null && !recipeIds.isEmpty()) {
            return recipeRepo.findAllById(recipeIds).stream()
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .toList();
        }
        if (!allInFactory) {
            throw new IllegalArgumentException(
                    "batch op requires allInFactory=true OR explicit recipeIds list");
        }
        // Use reverse-query path: only recipes referencing anchorMaterialId.
        List<BomRecipeItem> items = itemRepo.findByFactoryIdAndMaterialTypeId(factoryId, anchorMaterialId);
        if (items.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (BomRecipeItem i : items) ids.add(i.getRecipeId());
        return recipeRepo.findAllById(ids);
    }

    private List<BomRecipeItem> loadItems(BomRecipe recipe) {
        List<BomRecipeItem> items = recipe.getItems();
        if (items != null && !items.isEmpty()) return items;
        return itemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
    }

    /** Build snapshot map by transforming each item via the given mapper. */
    private Map<String, Object> buildSnapshotMap(BomRecipe recipe,
                                                  List<BomRecipeItem> items,
                                                  java.util.function.Function<BomRecipeItem, Map<String, Object>> itemMapper) {
        Map<String, Object> snapshot = recipeHeader(recipe);
        List<Map<String, Object>> snapItems = new ArrayList<>();
        for (BomRecipeItem item : items) {
            snapItems.add(itemMapper.apply(item));
        }
        snapshot.put("items", snapItems);
        snapshot.put("snapshotTakenAt", Instant.now().toString());
        return snapshot;
    }

    /** Build snapshot map from an explicit (possibly filtered) item list. */
    private Map<String, Object> buildSnapshotMapFromList(BomRecipe recipe, List<BomRecipeItem> items) {
        return buildSnapshotMap(recipe, items, this::itemToMap);
    }

    private Map<String, Object> recipeHeader(BomRecipe recipe) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recipeId", recipe.getId());
        snapshot.put("recipeCode", recipe.getRecipeCode());
        snapshot.put("productTypeId", recipe.getProductTypeId());
        snapshot.put("productName", recipe.getProductName());
        snapshot.put("version", recipe.getVersion());
        snapshot.put("overallYieldRate", recipe.getOverallYieldRate());
        snapshot.put("outputQuantityPerUnit", recipe.getOutputQuantityPerUnit());
        snapshot.put("outputUnit", recipe.getOutputUnit());
        snapshot.put("totalMaterialCost", recipe.getTotalMaterialCost());
        snapshot.put("totalLaborCost", recipe.getTotalLaborCost());
        snapshot.put("totalOverheadCost", recipe.getTotalOverheadCost());
        snapshot.put("totalCost", recipe.getTotalCost());
        snapshot.put("standardSalePrice", recipe.getStandardSalePrice());
        snapshot.put("status", recipe.getStatus() == null ? null : recipe.getStatus().name());
        snapshot.put("sourceType", recipe.getSourceType() == null ? null : recipe.getSourceType().name());
        snapshot.put("notes", recipe.getNotes());
        return snapshot;
    }

    private Map<String, Object> itemToMap(BomRecipeItem item) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", item.getId());
        snap.put("materialTypeId", item.getMaterialTypeId());
        snap.put("materialName", item.getMaterialName());
        snap.put("standardQuantity", item.getStandardQuantity());
        snap.put("yieldRate", item.getYieldRate());
        snap.put("actualQuantity", item.getActualQuantity());
        snap.put("unit", item.getUnit());
        snap.put("unitPrice", item.getUnitPrice());
        snap.put("taxRate", item.getTaxRate());
        snap.put("itemCost", item.getItemCost());
        snap.put("materialCategory", item.getMaterialCategory());
        snap.put("sortOrder", item.getSortOrder());
        snap.put("isOptional", item.getIsOptional());
        snap.put("substituteGroup", item.getSubstituteGroup());
        snap.put("remark", item.getRemark());
        snap.put("workflowMaterialNodeId", item.getWorkflowMaterialNodeId());
        snap.put("workflowInputPortId", item.getWorkflowInputPortId());
        snap.put("workflowEdgeId", item.getWorkflowEdgeId());
        snap.put("costScope", item.getCostScope());
        snap.put("costScopeKey", item.getCostScopeKey());
        return snap;
    }

    private boolean hasWorkflowIdentity(BomRecipeItem item) {
        return item.getWorkflowMaterialNodeId() != null
                || item.getWorkflowInputPortId() != null
                || item.getWorkflowEdgeId() != null;
    }

    private Map<String, Object> buildBatchImpactScope(String operation, String materialId,
                                                      List<String> affectedRecipeIds,
                                                      Map<String, Object> extra) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("operation", operation);
        scope.put("materialId", materialId);
        scope.put("affectedRecipeCount", affectedRecipeIds.size());
        scope.put("affectedRecipeIds", new ArrayList<>(affectedRecipeIds));
        if (extra != null) {
            scope.putAll(extra);
        }
        return scope;
    }

    /**
     * Create a DRAFT ECN with the operation summary. The ECN's bomRecipeId is the first
     * affected recipe (representative); full list is in impactScope.affectedRecipeIds.
     * fromVersion / toVersion left null at batch DRAFT time — populated by ECN approve workflow.
     */
    private EngineeringChangeNotice createDraftEcn(
            String factoryId, String firstRecipeId,
            com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason reason,
            String reasonDetail, java.time.LocalDate effectiveDate, Long createdBy,
            Map<String, Object> impactScope) {

        // ECN.bomRecipeId is @NotNull — for empty-scope batches we still need a non-null value.
        // Use placeholder "BATCH-EMPTY" so downstream tooling can filter.
        String anchor = firstRecipeId != null ? firstRecipeId : "BATCH-EMPTY";

        EcnCreateRequest req = EcnCreateRequest.builder()
                .factoryId(factoryId)
                .bomRecipeId(anchor)
                .toVersion(0) // Will be set per-recipe by ECN approve workflow.
                .reason(reason)
                .reasonDetail(reasonDetail)
                .effectiveDate(effectiveDate)
                .notifyRoles(new ArrayList<>(Arrays.asList(
                        "production", "procurement", "quality", "sales")))
                .createdBy(createdBy)
                .build();
        EngineeringChangeNotice ecn = ecnService.create(req);
        // Patch impactScope JSONB onto the just-created ECN.
        ecn.setImpactScope(impactScope);
        // Persist via service.create's transaction — entity is managed, the setter will flush on commit.
        return ecn;
    }
}
