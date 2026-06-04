package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.entity.restaurant.RecipeVersion;
import com.cretas.aims.entity.restaurant.RecipeVersion.VersionStatus;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.repository.restaurant.RecipeVersionRepository;
import com.cretas.aims.service.restaurant.RecipeVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RecipeVersionService implementation (#60 Phase 2 配方版本化).
 *
 * <p>Borrows the {@code bom.BomVersionServiceImpl} state-machine + explicit-supersede pattern
 * (Issue #724) but stays in the {@code restaurant} package, snapshotting the flat {@link Recipe}
 * rows for a dish instead of {@code BomRecipe}.
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeVersionServiceImpl implements RecipeVersionService {

    private final RecipeVersionRepository versionRepo;
    private final RecipeRepository recipeRepo;

    @Override
    @Transactional
    public RecipeVersion createDraft(String factoryId, String productTypeId, Long createdBy) {
        List<Recipe> recipes = recipeRepo.findActiveByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (recipes == null || recipes.isEmpty()) {
            throw new EntityNotFoundException("Recipe (dish)", productTypeId);
        }

        int nextVersion = versionRepo.findMaxVersionNumber(factoryId, productTypeId) + 1;

        RecipeVersion version = RecipeVersion.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .versionNumber(nextVersion)
                .snapshotJson(buildSnapshot(productTypeId, recipes))
                .status(VersionStatus.DRAFT)
                .createdBy(createdBy)
                .build();

        RecipeVersion saved = versionRepo.save(version);
        log.info("RecipeVersion DRAFT created: factoryId={}, productTypeId={}, version={}, id={}",
                factoryId, productTypeId, nextVersion, saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public RecipeVersion submitForApproval(String factoryId, String versionId) {
        RecipeVersion version = getById(factoryId, versionId);
        requireStatus(version, VersionStatus.DRAFT, "submitForApproval");
        version.setStatus(VersionStatus.PENDING_APPROVAL);
        RecipeVersion saved = versionRepo.save(version);
        log.info("RecipeVersion submitted: id={}", versionId);
        return saved;
    }

    @Override
    @Transactional
    public RecipeVersion approve(String factoryId, String versionId, Long approverId) {
        RecipeVersion version = getById(factoryId, versionId);
        // Rule 4 防呆 idempotency: re-approving an already-APPROVED row is rejected (409).
        if (version.getStatus() == VersionStatus.APPROVED) {
            throw new IllegalStateException("RecipeVersion " + versionId
                    + " is already APPROVED — query current approved version instead of re-approving");
        }
        // Allow approving directly from DRAFT for manual fast-path (no separate submit step).
        if (version.getStatus() != VersionStatus.DRAFT
                && version.getStatus() != VersionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("RecipeVersion " + versionId
                    + " cannot approve from status=" + version.getStatus());
        }

        LocalDate today = LocalDate.now();

        // Explicit supersede BEFORE promoting (mirrors BomVersion #724 fix): the partial unique
        // index uq_rv_one_approved_per_dish is enforced at row-write time, so we must OBSOLETE
        // any prior APPROVED+effective row and flush it before writing the new APPROVED row.
        versionRepo.findCurrentInStatus(factoryId, version.getProductTypeId(), VersionStatus.APPROVED)
                .filter(prior -> !prior.getId().equals(version.getId()))
                .ifPresent(prior -> {
                    LocalDate priorEffectiveTo = today.minusDays(1);
                    prior.setStatus(VersionStatus.OBSOLETE);
                    prior.setEffectiveTo(priorEffectiveTo);
                    versionRepo.save(prior);
                    versionRepo.flush();
                    log.info("RecipeVersion superseded prior APPROVED: priorId={}, priorVersion={}, "
                                    + "newId={}, newVersion={}, effectiveTo={}",
                            prior.getId(), prior.getVersionNumber(),
                            version.getId(), version.getVersionNumber(), priorEffectiveTo);
                });

        version.setStatus(VersionStatus.APPROVED);
        version.setEffectiveFrom(today);
        version.setEffectiveTo(null);
        version.setApprovedBy(approverId);
        version.setApprovedAt(Instant.now());

        RecipeVersion saved = versionRepo.save(version);
        log.info("RecipeVersion APPROVED: id={}, productTypeId={}, version={}, approver={}",
                versionId, saved.getProductTypeId(), saved.getVersionNumber(), approverId);
        return saved;
    }

    @Override
    @Transactional
    public RecipeVersion reject(String factoryId, String versionId, Long approverId, String reason) {
        RecipeVersion version = getById(factoryId, versionId);
        requireStatus(version, VersionStatus.PENDING_APPROVAL, "reject");
        version.setStatus(VersionStatus.REJECTED);
        version.setRejectionReason(reason);
        version.setApprovedBy(approverId);
        version.setApprovedAt(Instant.now());
        RecipeVersion saved = versionRepo.save(version);
        log.info("RecipeVersion REJECTED: id={}, approver={}, reason={}", versionId, approverId, reason);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecipeVersion> getCurrentApproved(String factoryId, String productTypeId) {
        return versionRepo.findCurrentInStatus(factoryId, productTypeId, VersionStatus.APPROVED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecipeVersion> getHistory(String factoryId, String productTypeId) {
        return versionRepo.findByFactoryIdAndProductTypeIdOrderByVersionNumberDesc(factoryId, productTypeId);
    }

    @Override
    @Transactional(readOnly = true)
    public RecipeVersion getById(String factoryId, String versionId) {
        return versionRepo.findById(versionId)
                .filter(v -> factoryId.equals(v.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("RecipeVersion", versionId));
    }

    // ========== helpers ==========

    /**
     * Build snapshot Map of the dish's recipe header + items list. Cost-bearing fields
     * (unitPrice/lineCost) included when known — RBAC strip happens at response time via
     * {@link com.cretas.aims.security.PriceSensitive} on RecipeVersion.snapshotJson.
     *
     * <p>The flat {@link Recipe} entity does not carry per-item unit price (food cost lives in
     * the SmartBI {@code agg_restaurant_product_cost} gold rollup, not on Recipe), so unitPrice/
     * lineCost are snapshotted as null here and may be enriched by an upstream cost-aware caller.
     */
    private Map<String, Object> buildSnapshot(String productTypeId, List<Recipe> recipes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productTypeId", productTypeId);

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalFoodCost = BigDecimal.ZERO;
        boolean anyCost = false;
        for (Recipe r : recipes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recipeId", r.getId());
            item.put("rawMaterialTypeId", r.getRawMaterialTypeId());
            item.put("standardQuantity", r.getStandardQuantity());
            item.put("unit", r.getUnit());
            item.put("netYieldRate", r.getNetYieldRate());
            item.put("isMainIngredient", r.getIsMainIngredient());
            item.put("actualQuantity", r.getActualQuantity());
            // Cost fields not on Recipe entity — null placeholder, kept @PriceSensitive at snapshot level.
            item.put("unitPrice", null);
            item.put("lineCost", null);
            items.add(item);
        }
        snapshot.put("items", items);
        snapshot.put("itemCount", items.size());
        // totalFoodCost stays null/zero until a cost-aware caller enriches; surfaced so the
        // approve diff dialog can show "成本影响" once cost data is wired.
        snapshot.put("totalFoodCost", anyCost ? totalFoodCost : null);
        snapshot.put("snapshotTakenAt", Instant.now().toString());
        return snapshot;
    }

    private void requireStatus(RecipeVersion version, VersionStatus expected, String op) {
        if (version.getStatus() != expected) {
            throw new IllegalStateException("RecipeVersion " + version.getId()
                    + " cannot " + op + " from status=" + version.getStatus()
                    + " (expected " + expected + ")");
        }
    }
}
