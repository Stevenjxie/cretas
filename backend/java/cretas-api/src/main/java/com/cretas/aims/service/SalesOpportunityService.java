package com.cretas.aims.service;

import com.cretas.aims.entity.OpportunityStageHistory;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.event.OpportunityClosedWonEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.InvalidStageTransitionException;
import com.cretas.aims.repository.OpportunityStageHistoryRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售商机 Service — Sprint 7 wave 2 T4 (2026-05-20).
 *
 * <p>State machine 规则:
 * <ul>
 *   <li>顺向 (forward by 1 step): LEAD→QUALIFIED→DEMO→PROPOSAL→NEGOTIATE→VERBAL→CLOSED_WON/LOST. 自由允许.</li>
 *   <li>反向 (backward): PROPOSAL→QUALIFIED 等. 允许, 但 reason 必填.</li>
 *   <li>Skip-forward (跳级): LEAD→PROPOSAL. 需 confirmSkip=true 标志.</li>
 *   <li>终态重激活: CLOSED_WON/LOST→任意 non-terminal. 允许, reason 必填 (审计强约束).</li>
 *   <li>非法 transition: same stage 自循 / 完全不存在的 stage 值 → 400.</li>
 * </ul>
 *
 * <p>每次 transitionStage 都会:
 * <ol>
 *   <li>校验 transition 合法性</li>
 *   <li>更新 probability (per stage default, 若调用方未显式提供)</li>
 *   <li>CLOSED_WON / CLOSED_LOST 时设 closedAt = now()</li>
 *   <li>非终态时清空 closedAt (重新激活场景)</li>
 *   <li>写一条 {@link OpportunityStageHistory} 审计</li>
 * </ol>
 */
@Slf4j
@Service
public class SalesOpportunityService {

    private final SalesOpportunityRepository repository;
    private final OpportunityStageHistoryRepository historyRepository;

    /**
     * Sprint 7 wave 2 T5 — CLOSED_WON event publishing for commission auto-calc.
     * Nullable to keep legacy {@link SalesOpportunityServiceTest} (T4) green
     * (its 2-arg constructor doesn't supply an event publisher).
     */
    private final org.springframework.beans.factory.ObjectProvider<ApplicationEventPublisher>
            eventPublisherProvider;

    // ==================== Legacy ctor for T4 tests (no event publisher) ====================

    public SalesOpportunityService(SalesOpportunityRepository repository,
                                     OpportunityStageHistoryRepository historyRepository) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.eventPublisherProvider = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SalesOpportunityService(SalesOpportunityRepository repository,
                                     OpportunityStageHistoryRepository historyRepository,
                                     org.springframework.beans.factory.ObjectProvider<ApplicationEventPublisher>
                                             eventPublisherProvider) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.eventPublisherProvider = eventPublisherProvider;
    }

    // ==================== Read ====================

    public Page<SalesOpportunity> list(String factoryId, OpportunityStage stage, Long ownerId,
                                        int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        PageRequest pageReq = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        if (stage != null && ownerId != null) {
            return repository.findByFactoryIdAndStageAndOwnerIdAndDeletedAtIsNull(
                    factoryId, stage, ownerId, pageReq);
        } else if (stage != null) {
            return repository.findByFactoryIdAndStageAndDeletedAtIsNull(factoryId, stage, pageReq);
        } else if (ownerId != null) {
            return repository.findByFactoryIdAndOwnerIdAndDeletedAtIsNull(factoryId, ownerId, pageReq);
        }
        return repository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageReq);
    }

    public SalesOpportunity get(String factoryId, String id) {
        return repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "商机不存在: " + id));
    }

    public List<OpportunityStageHistory> getHistory(String factoryId, String opportunityId) {
        // 验证商机存在
        get(factoryId, opportunityId);
        return historyRepository
                .findByFactoryIdAndOpportunityIdAndDeletedAtIsNullOrderByChangedAtDesc(
                        factoryId, opportunityId);
    }

    public List<SalesOpportunityRepository.FunnelStat> getFunnelStats(String factoryId, Long ownerId) {
        return repository.getFunnelStats(factoryId, ownerId);
    }

    // ==================== Write ====================

    /**
     * 创建商机 — initial stage = LEAD by default (除非调用方明确传 other).
     * Probability 跟随 stage default. 写一条 history 记录 (fromStage = null).
     */
    @Transactional
    public SalesOpportunity create(String factoryId, String customerId, String title,
                                     OpportunityStage initialStage, BigDecimal valueAmount,
                                     Integer probabilityOverride, LocalDate expectedCloseDate,
                                     Long ownerId, String description, Long createdBy) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(400, "customerId 必填");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "title 必填");
        }
        if (ownerId == null) {
            throw new BusinessException(400, "ownerId 必填 (业务员)");
        }
        if (createdBy == null) {
            throw new BusinessException(400, "createdBy 必填");
        }

        OpportunityStage stage = initialStage != null ? initialStage : OpportunityStage.LEAD;
        int probability = probabilityOverride != null
                ? validateProbability(probabilityOverride)
                : stage.getDefaultProbability();

        SalesOpportunity opp = SalesOpportunity.builder()
                .factoryId(factoryId)
                .customerId(customerId)
                .title(title.trim())
                .stage(stage)
                .valueAmount(valueAmount)
                .probability(probability)
                .expectedCloseDate(expectedCloseDate)
                .ownerId(ownerId)
                .closedAt(stage.isTerminal() ? LocalDateTime.now() : null)
                .description(description)
                .createdBy(createdBy)
                .version(0L)
                .build();

        SalesOpportunity saved = repository.save(opp);

        // 写 initial creation history
        OpportunityStageHistory history = OpportunityStageHistory.builder()
                .factoryId(factoryId)
                .opportunityId(saved.getId())
                .fromStage(null)
                .toStage(stage)
                .reason("初始创建")
                .changedBy(createdBy)
                .changedAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);

        log.info("Created opportunity {} customer={} stage={} owner={}",
                saved.getId(), customerId, stage, ownerId);

        // Sprint 7 wave 2 T5 — if created directly in CLOSED_WON state, fire event.
        if (stage == OpportunityStage.CLOSED_WON) {
            publishClosedWonEvent(saved);
        }

        return saved;
    }

    /**
     * 阶段转换 — 主要 state machine 入口.
     *
     * @param confirmSkip 是否允许跳级 (e.g. LEAD→PROPOSAL). 默认 false.
     * @param probabilityOverride 若非 null, 手动覆盖默认 probability.
     * @throws InvalidStageTransitionException 非法 transition.
     */
    @Transactional
    public SalesOpportunity transitionStage(String factoryId, String opportunityId,
                                              OpportunityStage newStage, String reason,
                                              boolean confirmSkip, Integer probabilityOverride,
                                              Long changedBy) {
        if (newStage == null) {
            throw new InvalidStageTransitionException("newStage 必填");
        }
        if (changedBy == null) {
            throw new BusinessException(400, "changedBy 必填");
        }

        SalesOpportunity opp = get(factoryId, opportunityId);
        OpportunityStage fromStage = opp.getStage();

        validateTransition(fromStage, newStage, reason, confirmSkip);

        // ==================== 应用状态转换 ====================

        opp.setStage(newStage);

        // Probability 处理
        if (probabilityOverride != null) {
            opp.setProbability(validateProbability(probabilityOverride));
        } else {
            // 自动跟随 stage default
            opp.setProbability(newStage.getDefaultProbability());
        }

        // closedAt 处理
        if (newStage.isTerminal()) {
            opp.setClosedAt(LocalDateTime.now());
        } else {
            // 重新激活: 清空 closedAt
            opp.setClosedAt(null);
        }

        SalesOpportunity saved = repository.save(opp);

        // 写 history
        OpportunityStageHistory history = OpportunityStageHistory.builder()
                .factoryId(factoryId)
                .opportunityId(opportunityId)
                .fromStage(fromStage)
                .toStage(newStage)
                .reason(reason)
                .changedBy(changedBy)
                .changedAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);

        log.info("Transitioned opportunity {} {} -> {} by user={} reason={}",
                opportunityId, fromStage, newStage, changedBy, reason);

        // Sprint 7 wave 2 T5 — Publish CLOSED_WON event for commission auto-calc.
        if (newStage == OpportunityStage.CLOSED_WON && fromStage != OpportunityStage.CLOSED_WON) {
            publishClosedWonEvent(saved);
        }

        return saved;
    }

    /**
     * Publish OpportunityClosedWonEvent for commission listener. Best-effort: failures
     * are logged but don't roll back the transaction (per existing Sales event pattern).
     */
    private void publishClosedWonEvent(SalesOpportunity opp) {
        if (eventPublisherProvider == null) {
            log.debug("eventPublisherProvider not available (legacy ctor) — skip CLOSED_WON event publish");
            return;
        }
        ApplicationEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher == null) {
            log.debug("ApplicationEventPublisher not available — skip CLOSED_WON event publish");
            return;
        }
        try {
            publisher.publishEvent(new OpportunityClosedWonEvent(
                    this,
                    opp.getFactoryId(),
                    opp.getId(),
                    opp.getCustomerId(),
                    opp.getOwnerId(),
                    opp.getValueAmount(),
                    opp.getClosedAt() != null ? opp.getClosedAt() : LocalDateTime.now()
            ));
            log.info("Published OpportunityClosedWonEvent: opp={} owner={}",
                    opp.getId(), opp.getOwnerId());
        } catch (Exception e) {
            log.error("Failed to publish OpportunityClosedWonEvent: opp={}", opp.getId(), e);
        }
    }

    /**
     * 部分更新 — 不动 stage / closedAt (只能通过 transitionStage 改).
     */
    @Transactional
    public SalesOpportunity update(String factoryId, String id, String title, BigDecimal valueAmount,
                                     Integer probabilityOverride, LocalDate expectedCloseDate,
                                     Long ownerId, String description) {
        SalesOpportunity opp = get(factoryId, id);
        if (title != null && !title.isBlank()) opp.setTitle(title.trim());
        if (valueAmount != null) opp.setValueAmount(valueAmount);
        if (probabilityOverride != null) {
            opp.setProbability(validateProbability(probabilityOverride));
        }
        if (expectedCloseDate != null) opp.setExpectedCloseDate(expectedCloseDate);
        if (ownerId != null) opp.setOwnerId(ownerId);
        if (description != null) opp.setDescription(description);
        return repository.save(opp);
    }

    /** 软删除 — 不写 history (操作不可逆但保留 audit 表). */
    @Transactional
    public void delete(String factoryId, String id) {
        SalesOpportunity opp = get(factoryId, id);
        repository.delete(opp);
        log.info("Soft-deleted opportunity {} factory={}", id, factoryId);
    }

    // ==================== Internal validation ====================

    /**
     * State machine 校验. Throws {@link InvalidStageTransitionException} on illegal moves.
     *
     * 规则:
     * - same-stage self-loop → throw
     * - forward by 1 → allow
     * - forward skip > 1 → require confirmSkip=true, else throw
     * - backward (任意 non-terminal → 更早 non-terminal) → require non-empty reason
     * - terminal → non-terminal (重新激活) → require non-empty reason
     * - terminal → terminal (CLOSED_WON ↔ CLOSED_LOST) → require non-empty reason (审计)
     * - 任意 → terminal (forward win/lose) → allow without reason
     */
    private void validateTransition(OpportunityStage from, OpportunityStage to,
                                      String reason, boolean confirmSkip) {
        if (from == to) {
            throw new InvalidStageTransitionException(
                    "无效转换: 起止阶段相同 (" + from.getDisplayName() + ")");
        }

        int fromOrd = from.ordinalForFlow();
        int toOrd = to.ordinalForFlow();

        boolean fromTerminal = from.isTerminal();
        boolean toTerminal = to.isTerminal();

        // ===== Case 1: from non-terminal → to terminal (forward close) =====
        if (!fromTerminal && toTerminal) {
            // Always allowed (CLOSED_WON / CLOSED_LOST 从任何 active stage 可达).
            return;
        }

        // ===== Case 2: from terminal → to non-terminal (重新激活) =====
        if (fromTerminal && !toTerminal) {
            requireReason(reason, "终态商机重新激活需说明原因 ("
                    + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            return;
        }

        // ===== Case 3: terminal → terminal (CLOSED_WON ↔ CLOSED_LOST) =====
        if (fromTerminal && toTerminal) {
            requireReason(reason, "终态间转换需说明原因 ("
                    + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            return;
        }

        // ===== Case 4: non-terminal → non-terminal =====
        int delta = toOrd - fromOrd;
        if (delta == 1) {
            // Forward by 1 → allow without reason
            return;
        } else if (delta > 1) {
            // Skip forward → require confirmSkip
            if (!confirmSkip) {
                throw new InvalidStageTransitionException(String.format(
                        "跳级转换 (%s → %s, 跨越 %d 阶段). 请明确确认 (confirmSkip=true) 并填写原因.",
                        from.getDisplayName(), to.getDisplayName(), delta));
            }
            requireReason(reason, "跳级转换需说明原因 ("
                    + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            return;
        } else {
            // Backward (delta < 0) → require reason
            requireReason(reason, "阶段回退需说明原因 ("
                    + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            return;
        }
    }

    private void requireReason(String reason, String errorMsg) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new InvalidStageTransitionException(errorMsg);
        }
    }

    private int validateProbability(int probability) {
        if (probability < 0 || probability > 100) {
            throw new BusinessException(400, "probability 必须在 0-100 区间, 实际: " + probability);
        }
        return probability;
    }
}
