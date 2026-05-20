package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.EcnCreateRequest;
import com.cretas.aims.dto.bom.EcnImpactReport;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnStatus;

import java.util.Map;

/**
 * ECNService (Sprint 3 Track-H / M-BOM-VER-1).
 *
 * <p>Engineering Change Notice workflow. 5 reason categories + approval chain (hardcoded
 * default until Track-I C-APPROVAL-EDITOR ships) + impact scope + notification fan-out.
 *
 * <p>State machine: {@code DRAFT → SUBMITTED → APPROVED → EFFECTIVE} (or REJECTED terminal).
 * ECN approve cascades into {@link BomVersionService#approve} for any linked BomVersion in
 * PENDING_APPROVAL.
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
public interface ECNService {

    /** Create a DRAFT ECN. Auto-generates {@code ecn_number} (ECN-YYYY-NNNN per factory). */
    EngineeringChangeNotice create(EcnCreateRequest req);

    /** DRAFT → SUBMITTED. Idempotent if already SUBMITTED. */
    EngineeringChangeNotice submitForApproval(String factoryId, String ecnId);

    /**
     * SUBMITTED → APPROVED. Sets approvedBy/approvedAt. Cascades to all linked
     * {@link com.cretas.aims.entity.bom.BomVersion} rows in PENDING_APPROVAL — flips them to
     * APPROVED via {@link BomVersionService#approve}.
     */
    EngineeringChangeNotice approve(String factoryId, String ecnId, Long approverId);

    /** SUBMITTED → REJECTED. Sets rejectionReason. Terminal. */
    EngineeringChangeNotice reject(String factoryId, String ecnId, Long approverId, String reason);

    /**
     * Send InApp notifications to roles in {@code ecn.notifyRoles}. Idempotent — may be
     * triggered on submit, approve, or effective-date crossing.
     */
    void notifyImpactedRoles(String factoryId, String ecnId);

    /**
     * Compute impact scope (affected SKUs / customers / open orders) for a proposed batch of
     * changes. Day 4-5 returns skeleton; Day 8-9 batch operations exercise full population.
     *
     * @param changeContext extensible map: {@code {"addedMaterialIds": [...], "removedMaterialIds": [...],
     *                       "modifiedMaterialIds": [...], "replacements": [{"from":..,"to":..}]}}
     */
    EcnImpactReport calculateImpact(String factoryId, String bomRecipeId,
                                     Map<String, Object> changeContext);

    /** Lookup by id. */
    EngineeringChangeNotice getById(String factoryId, String ecnId);

    /**
     * Daily housekeeping: APPROVED ECN whose effectiveDate ≤ today → flip to EFFECTIVE.
     * Returns count of transitions. Intended for nightly cron / startup hook.
     */
    int activateDueApprovedEcns(String factoryId);

    /**
     * Sprint 6 W4-C — Paginated list of ECNs with optional status / reason / bomRecipeId filters.
     *
     * @param factoryId   factory scope
     * @param pageRequest 分页参数 (page, size, sortBy, sortDirection)
     * @param status      可选 status 过滤 (null = all)
     * @param reason      可选 reason 过滤 (null = all)
     * @param bomRecipeId 可选 BOM recipe id 过滤 (null = all)
     */
    PageResponse<EngineeringChangeNotice> listEcns(String factoryId, PageRequest pageRequest,
                                                    EcnStatus status, EcnReason reason,
                                                    String bomRecipeId);
}
