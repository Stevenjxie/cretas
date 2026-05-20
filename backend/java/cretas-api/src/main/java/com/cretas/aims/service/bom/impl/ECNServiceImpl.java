package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.EcnCreateRequest;
import com.cretas.aims.dto.bom.EcnImpactReport;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Notification;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnStatus;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.bom.EngineeringChangeNoticeRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.bom.BomVersionService;
import com.cretas.aims.service.bom.ECNService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ECNService implementation (Sprint 3 Track-H / M-BOM-VER-1).
 *
 * <p>Hardcoded default approval chain: roles {@code factory_super_admin} + {@code production_admin}.
 * Track-I C-APPROVAL-EDITOR ships UI for user-defined chains → ECNService will then resolve via
 * {@code approval_chain_configs} table when {@link EcnCreateRequest#getApprovalChainId()} non-null.
 *
 * @author Cretas Team / Sprint 3 Track-H
 * @since 2026-05-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ECNServiceImpl implements ECNService {

    /** Default notify roles when {@link EcnCreateRequest#getNotifyRoles()} not provided. */
    private static final List<String> DEFAULT_NOTIFY_ROLES =
            Arrays.asList("production", "procurement", "quality", "sales");

    private final EngineeringChangeNoticeRepository ecnRepo;
    private final BomVersionRepository versionRepo;
    private final BomVersionService versionService;
    /** Optional — environments without InApp notifications still operate; logs at warn level. */
    @Autowired(required = false)
    private NotificationService notificationService;

    @Override
    @Transactional
    public EngineeringChangeNotice create(EcnCreateRequest req) {
        if (req.getNotifyRoles() == null || req.getNotifyRoles().isEmpty()) {
            req.setNotifyRoles(new ArrayList<>(DEFAULT_NOTIFY_ROLES));
        }

        EngineeringChangeNotice ecn = EngineeringChangeNotice.builder()
                .factoryId(req.getFactoryId())
                .ecnNumber(generateEcnNumber(req.getFactoryId()))
                .bomRecipeId(req.getBomRecipeId())
                .fromVersion(req.getFromVersion())
                .toVersion(req.getToVersion())
                .reason(req.getReason())
                .reasonDetail(req.getReasonDetail())
                .effectiveDate(req.getEffectiveDate())
                .notifyRoles(req.getNotifyRoles())
                .approvalChainId(req.getApprovalChainId())
                .status(EcnStatus.DRAFT)
                .createdBy(req.getCreatedBy())
                .build();

        EngineeringChangeNotice saved = ecnRepo.save(ecn);
        log.info("ECN created: id={}, number={}, factory={}, recipe={}, reason={}",
                saved.getId(), saved.getEcnNumber(), saved.getFactoryId(),
                saved.getBomRecipeId(), saved.getReason());
        return saved;
    }

    @Override
    @Transactional
    public EngineeringChangeNotice submitForApproval(String factoryId, String ecnId) {
        EngineeringChangeNotice ecn = getById(factoryId, ecnId);
        if (ecn.getStatus() == EcnStatus.SUBMITTED) {
            return ecn; // idempotent
        }
        requireStatus(ecn, EcnStatus.DRAFT, "submitForApproval");
        ecn.setStatus(EcnStatus.SUBMITTED);
        EngineeringChangeNotice saved = ecnRepo.save(ecn);
        notifyImpactedRoles(factoryId, ecnId);
        log.info("ECN submitted: id={}, number={}", ecnId, saved.getEcnNumber());
        return saved;
    }

    @Override
    @Transactional
    public EngineeringChangeNotice approve(String factoryId, String ecnId, Long approverId) {
        EngineeringChangeNotice ecn = getById(factoryId, ecnId);
        requireStatus(ecn, EcnStatus.SUBMITTED, "approve");

        ecn.setStatus(EcnStatus.APPROVED);
        ecn.setApprovedBy(approverId);
        ecn.setApprovedAt(Instant.now());
        EngineeringChangeNotice saved = ecnRepo.save(ecn);

        // Cascade: any linked BomVersion in PENDING_APPROVAL flips to APPROVED.
        List<BomVersion> linked = versionRepo.findByEcnId(ecnId);
        for (BomVersion v : linked) {
            if (v.getStatus() == BomVersion.VersionStatus.PENDING_APPROVAL
                    || v.getStatus() == BomVersion.VersionStatus.DRAFT) {
                versionService.approve(factoryId, v.getId(), approverId);
            }
        }

        notifyImpactedRoles(factoryId, ecnId);
        log.info("ECN APPROVED: id={}, number={}, approver={}, cascadedVersions={}",
                ecnId, saved.getEcnNumber(), approverId, linked.size());
        return saved;
    }

    @Override
    @Transactional
    public EngineeringChangeNotice reject(String factoryId, String ecnId, Long approverId, String reason) {
        EngineeringChangeNotice ecn = getById(factoryId, ecnId);
        requireStatus(ecn, EcnStatus.SUBMITTED, "reject");
        ecn.setStatus(EcnStatus.REJECTED);
        ecn.setRejectionReason(reason);
        ecn.setApprovedBy(approverId);
        ecn.setApprovedAt(Instant.now());
        EngineeringChangeNotice saved = ecnRepo.save(ecn);

        // Cascade reject to any PENDING_APPROVAL BomVersion linked to this ECN.
        List<BomVersion> linked = versionRepo.findByEcnId(ecnId);
        for (BomVersion v : linked) {
            if (v.getStatus() == BomVersion.VersionStatus.PENDING_APPROVAL) {
                versionService.reject(factoryId, v.getId(), approverId, "ECN rejected: " + reason);
            }
        }

        log.info("ECN REJECTED: id={}, number={}, approver={}, reason={}",
                ecnId, saved.getEcnNumber(), approverId, reason);
        return saved;
    }

    @Override
    public void notifyImpactedRoles(String factoryId, String ecnId) {
        if (notificationService == null) {
            log.debug("NotificationService not wired; skipping ECN role notification (ecnId={})", ecnId);
            return;
        }
        EngineeringChangeNotice ecn = getById(factoryId, ecnId);
        List<String> roles = ecn.getNotifyRoles();
        if (roles == null || roles.isEmpty()) {
            return;
        }
        String title = "ECN " + ecn.getEcnNumber() + " — " + ecn.getStatus();
        String content = String.format("BOM recipe=%s, v%s→v%s, reason=%s",
                ecn.getBomRecipeId(), ecn.getFromVersion(), ecn.getToVersion(), ecn.getReason());

        for (String roleCode : roles) {
            FactoryUserRole roleEnum = mapRole(roleCode);
            if (roleEnum == null) {
                log.warn("ECN notifyImpactedRoles: unknown role code '{}' on ecn={} — skipping",
                        roleCode, ecnId);
                continue;
            }
            try {
                List<Notification> sent = notificationService.sendToRole(
                        factoryId, roleEnum, title, content,
                        NotificationType.SYSTEM, "ECN", ecnId);
                log.info("ECN notified role={}, recipients={}, ecn={}",
                        roleCode, sent == null ? 0 : sent.size(), ecnId);
            } catch (Exception e) {
                log.warn("ECN notifyImpactedRoles failed for role {} ecn {}: {}",
                        roleCode, ecnId, e.getMessage());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EcnImpactReport calculateImpact(String factoryId, String bomRecipeId,
                                            Map<String, Object> changeContext) {
        // Day 4-5 scope: skeleton report. Day 8-9 batch operations fill in open-order scan
        // (queries SalesOrder for bomRecipeId-tied items still in OPEN/PENDING status) and
        // affectedCustomers (groupBy customerId on open orders).
        return EcnImpactReport.builder()
                .affectedSkus(new ArrayList<>())
                .affectedCustomers(new ArrayList<>())
                .openOrderCount(0)
                .openOrderSummary(new ArrayList<>())
                .warnings(new ArrayList<>(
                        Arrays.asList("calculateImpact skeleton — full impl arrives Day 8-9")))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public EngineeringChangeNotice getById(String factoryId, String ecnId) {
        return ecnRepo.findById(ecnId)
                .filter(e -> factoryId.equals(e.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("EngineeringChangeNotice", ecnId));
    }

    @Override
    @Transactional
    public int activateDueApprovedEcns(String factoryId) {
        LocalDate today = LocalDate.now();
        List<EngineeringChangeNotice> due = ecnRepo.findDueForActivation(factoryId, EcnStatus.APPROVED, today);
        int count = 0;
        for (EngineeringChangeNotice ecn : due) {
            ecn.setStatus(EcnStatus.EFFECTIVE);
            ecnRepo.save(ecn);
            count++;
        }
        if (count > 0) {
            log.info("ECN activateDueApprovedEcns: factory={}, transitioned={}", factoryId, count);
        }
        return count;
    }

    // ========== helpers ==========

    /** Generate {@code ECN-YYYY-NNNN}. NNNN is per-factory cumulative count + 1. */
    private String generateEcnNumber(String factoryId) {
        int year = Year.now().getValue();
        String prefix = "ECN-" + year + "-";
        long count = ecnRepo.countByEcnNumberPrefix(factoryId, prefix + "%");
        return String.format("%s%04d", prefix, count + 1);
    }

    private void requireStatus(EngineeringChangeNotice ecn, EcnStatus expected, String op) {
        if (ecn.getStatus() != expected) {
            throw new IllegalStateException("ECN " + ecn.getId()
                    + " cannot " + op + " from status=" + ecn.getStatus()
                    + " (expected " + expected + ")");
        }
    }

    /**
     * Map JSON role code (string) to {@link FactoryUserRole} enum. Returns null for unknown.
     * Default short codes: production / procurement / quality / sales — mapped to admin variants.
     */
    private FactoryUserRole mapRole(String code) {
        if (code == null) return null;
        String upper = code.trim().toUpperCase();
        // Try literal enum name first.
        try {
            return FactoryUserRole.valueOf(upper);
        } catch (IllegalArgumentException ignore) {
            // Fall through to short-code heuristics.
        }
        switch (upper) {
            case "PRODUCTION":  return tryEnum("PRODUCTION_ADMIN", "PRODUCTION_MANAGER");
            case "PROCUREMENT": return tryEnum("PROCUREMENT_ADMIN", "PROCUREMENT_MANAGER");
            case "QUALITY":     return tryEnum("QUALITY_ADMIN", "QUALITY_MANAGER", "QUALITY_INSPECTOR");
            case "SALES":       return tryEnum("SALES_ADMIN", "SALES_MANAGER");
            default:            return null;
        }
    }

    private FactoryUserRole tryEnum(String... candidates) {
        for (String name : candidates) {
            try {
                return FactoryUserRole.valueOf(name);
            } catch (IllegalArgumentException ignore) {
                // try next
            }
        }
        return null;
    }

    // ================== Sprint 6 W4-C ==================

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EngineeringChangeNotice> listEcns(String factoryId, PageRequest pageRequest,
                                                            EcnStatus status, EcnReason reason,
                                                            String bomRecipeId) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(pageRequest.getSortDirection())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortBy = pageRequest.getSortBy() != null ? pageRequest.getSortBy() : "createdAt";
        // page from 1, Spring Data Pageable from 0
        org.springframework.data.domain.PageRequest pageable =
                org.springframework.data.domain.PageRequest.of(
                        pageRequest.getPage() - 1,
                        pageRequest.getSize(),
                        Sort.by(direction, sortBy));

        Page<EngineeringChangeNotice> page =
                ecnRepo.findByFactoryWithFilters(factoryId, status, reason, bomRecipeId, pageable);

        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements());
    }
}
