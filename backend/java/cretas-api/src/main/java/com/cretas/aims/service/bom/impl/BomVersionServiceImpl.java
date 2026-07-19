package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.bom.BomVersion.VersionStatus;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.service.bom.BomVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BomVersionService implementation (Sprint 3 Track-H / M-BOM-VER-1).
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomVersionServiceImpl implements BomVersionService {

    private final BomVersionRepository versionRepo;
    private final BomRecipeRepository recipeRepo;
    private final BomSeasoningItemRepository seasoningItemRepo;
    private final BomProcessInjectionConfigRepository processInjectionConfigRepo;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public BomVersion createDraft(String factoryId, String bomRecipeId, Long createdBy) {
        BomRecipe recipe = recipeRepo.findById(bomRecipeId)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("BomRecipe", bomRecipeId));

        int nextVersion = versionRepo.findMaxVersionNumber(factoryId, bomRecipeId) + 1;

        BomVersion version = BomVersion.builder()
                .factoryId(factoryId)
                .bomRecipeId(bomRecipeId)
                .versionNumber(nextVersion)
                .snapshotJson(buildSnapshot(recipe))
                .status(VersionStatus.DRAFT)
                .createdBy(createdBy)
                .build();

        BomVersion saved = versionRepo.save(version);
        log.info("BomVersion DRAFT created: factoryId={}, bomRecipeId={}, version={}, id={}",
                factoryId, bomRecipeId, nextVersion, saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public BomVersion createDraftWithSnapshot(String factoryId, String bomRecipeId,
                                               Map<String, Object> snapshot, Long createdBy) {
        // Validate parent recipe exists for the given factory (catches stale FK at write time).
        recipeRepo.findById(bomRecipeId)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("BomRecipe", bomRecipeId));

        int nextVersion = versionRepo.findMaxVersionNumber(factoryId, bomRecipeId) + 1;
        BomVersion version = BomVersion.builder()
                .factoryId(factoryId)
                .bomRecipeId(bomRecipeId)
                .versionNumber(nextVersion)
                .snapshotJson(snapshot)
                .status(VersionStatus.DRAFT)
                .createdBy(createdBy)
                .build();
        BomVersion saved = versionRepo.save(version);
        log.info("BomVersion DRAFT created with explicit snapshot: factoryId={}, recipeId={}, version={}, id={}",
                factoryId, bomRecipeId, nextVersion, saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public BomVersion submitForApproval(String factoryId, String versionId, String ecnId) {
        BomVersion version = getById(factoryId, versionId);
        requireStatus(version, VersionStatus.DRAFT, "submitForApproval");

        version.setStatus(VersionStatus.PENDING_APPROVAL);
        version.setEcnId(ecnId);
        BomVersion saved = versionRepo.save(version);
        log.info("BomVersion submitted: id={}, ecnId={}", versionId, ecnId);
        return saved;
    }

    @Override
    @Transactional
    public BomVersion approve(String factoryId, String versionId, Long approverId) {
        BomVersion version = getById(factoryId, versionId);
        // Allow approving directly from DRAFT for manual / no-ECN fast path.
        if (version.getStatus() != VersionStatus.DRAFT
                && version.getStatus() != VersionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("BomVersion " + versionId
                    + " cannot approve from status=" + version.getStatus());
        }

        LocalDate today = LocalDate.now();

        // Issue #724 fix: explicitly OBSOLETE any prior APPROVED+effective version BEFORE
        // promoting this row to APPROVED. The partial unique index
        // {@code uq_bv_one_current_per_recipe} is enforced at row-write time; the AFTER
        // trigger {@code trg_bom_version_supersede} fires too late to prevent the conflict.
        // Service layer must perform the supersede itself, then flush, then write the new
        // APPROVED row. Trigger remains as defense-in-depth for direct SQL / batch paths.
        versionRepo.findCurrentInStatus(factoryId, version.getBomRecipeId(), VersionStatus.APPROVED)
                .filter(prior -> !prior.getId().equals(version.getId()))
                .ifPresent(prior -> {
                    LocalDate priorEffectiveTo = today.minusDays(1);
                    prior.setStatus(VersionStatus.OBSOLETE);
                    prior.setEffectiveTo(priorEffectiveTo);
                    versionRepo.save(prior);
                    versionRepo.flush();
                    log.info("BomVersion superseded prior APPROVED: priorId={}, priorVersion={}, "
                                    + "newId={}, newVersion={}, effectiveTo={}",
                            prior.getId(), prior.getVersionNumber(),
                            version.getId(), version.getVersionNumber(), priorEffectiveTo);
                });

        version.setStatus(VersionStatus.APPROVED);
        version.setEffectiveFrom(today);
        version.setEffectiveTo(null);
        version.setApprovedBy(approverId);
        version.setApprovedAt(Instant.now());

        BomVersion saved = versionRepo.save(version);
        // Service layer also keeps BomRecipe.isCurrent in sync for parity with 6-month
        // transitional dual-track.
        syncBomRecipeIsCurrent(saved);

        log.info("BomVersion APPROVED: id={}, bomRecipeId={}, version={}, approver={}",
                versionId, saved.getBomRecipeId(), saved.getVersionNumber(), approverId);
        return saved;
    }

    @Override
    @Transactional
    public BomVersion reject(String factoryId, String versionId, Long approverId, String reason) {
        BomVersion version = getById(factoryId, versionId);
        requireStatus(version, VersionStatus.PENDING_APPROVAL, "reject");

        version.setStatus(VersionStatus.REJECTED);
        version.setRejectionReason(reason);
        version.setApprovedBy(approverId);
        version.setApprovedAt(Instant.now());
        BomVersion saved = versionRepo.save(version);
        log.info("BomVersion REJECTED: id={}, approver={}, reason={}", versionId, approverId, reason);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BomVersion> getCurrent(String factoryId, String bomRecipeId) {
        return versionRepo.findCurrentInStatus(factoryId, bomRecipeId, VersionStatus.APPROVED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomVersion> getHistory(String factoryId, String bomRecipeId) {
        return versionRepo.findByFactoryIdAndBomRecipeIdOrderByVersionNumberDesc(factoryId, bomRecipeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BomVersion> getEffectiveAt(String factoryId, String bomRecipeId, LocalDate date) {
        return versionRepo.findEffectiveAtInStatuses(factoryId, bomRecipeId, date,
                List.of(VersionStatus.APPROVED, VersionStatus.OBSOLETE));
    }

    @Override
    @Transactional(readOnly = true)
    public BomVersion getById(String factoryId, String versionId) {
        return versionRepo.findById(versionId)
                .filter(v -> factoryId.equals(v.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("BomVersion", versionId));
    }

    // ========== helpers ==========

    /**
     * Build snapshot Map of recipe header + items list. Cost fields included — RBAC strip
     * happens at response time via @PriceSensitive on BomVersion.snapshotJson.
     */
    private Map<String, Object> buildSnapshot(BomRecipe recipe) {
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

        snapshot.put("seasoningRevision", recipe.getSeasoningRevision());

        List<Map<String, Object>> seasoning = new ArrayList<>();
        // 经 repo 取 (非 LAZY 集合) — tx 内外都安全, 避免 LazyInitializationException / 隐藏 N+1 (audit Issue 2).
        List<BomSeasoningItem> seasoningItems = seasoningItemRepo.findByRecipeIdOrderBySeqAsc(recipe.getId());
        for (BomSeasoningItem si : seasoningItems) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("id", si.getId());
            snap.put("materialTypeId", si.getMaterialTypeId());
            snap.put("workProcessId", si.getWorkProcessId());
            snap.put("section", si.getSection());
            snap.put("seq", si.getSeq());
            snap.put("name", si.getName());
            snap.put("dosagePerKgG", si.getDosagePerKgG());
            snap.put("priceSource1", si.getPriceSource1());
            snap.put("priceSource2", si.getPriceSource2());
            snap.put("subsequentPotRatio", si.getSubsequentPotRatio());
            snap.put("countInSeasoning", si.getCountInSeasoning());
            snap.put("remark", si.getRemark());
            seasoning.add(snap);
        }
        snapshot.put("seasoningItems", seasoning);

        List<Map<String, Object>> injectionConfigs = new ArrayList<>();
        if (processInjectionConfigRepo != null) {
            for (BomProcessInjectionConfig config : processInjectionConfigRepo
                    .findByRecipeIdAndDeletedAtIsNull(recipe.getId())) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("workProcessId", config.getWorkProcessId());
                snap.put("injectionAmountKg", config.getInjectionAmountKg());
                snap.put("notes", config.getNotes());
                injectionConfigs.add(snap);
            }
        }
        snapshot.put("processInjectionConfigs", injectionConfigs);

        List<Map<String, Object>> items = new ArrayList<>();
        List<BomRecipeItem> recipeItems = recipe.getItems();
        if (recipeItems != null) {
            for (BomRecipeItem item : recipeItems) {
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
                items.add(snap);
            }
        }
        snapshot.put("items", items);

        snapshot.put("snapshotTakenAt", Instant.now().toString());
        return snapshot;
    }

    private void requireStatus(BomVersion version, VersionStatus expected, String op) {
        if (version.getStatus() != expected) {
            throw new IllegalStateException("BomVersion " + version.getId()
                    + " cannot " + op + " from status=" + version.getStatus()
                    + " (expected " + expected + ")");
        }
    }

    /**
     * Keep {@link BomRecipe#getIsCurrent()} aligned with BomVersion APPROVED+effective_to-IS-NULL.
     * Used during the 6-month transition where consumer code may still read BomRecipe.isCurrent.
     */
    private void syncBomRecipeIsCurrent(BomVersion approvedVersion) {
        recipeRepo.findById(approvedVersion.getBomRecipeId()).ifPresent(recipe -> {
            // approve() has already superseded any prior APPROVED bomVersion (via explicit
            // OBSOLETE write). The new approved version IS the current. Mark
            // BomRecipe.isCurrent=true and version=approvedVersion.versionNumber.
            recipe.setIsCurrent(true);
            recipe.setVersion(approvedVersion.getVersionNumber());
            recipeRepo.save(recipe);
        });
    }
}
