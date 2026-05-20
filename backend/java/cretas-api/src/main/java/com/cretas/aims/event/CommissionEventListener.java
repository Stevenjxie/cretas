package com.cretas.aims.event;

import com.cretas.aims.service.CommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sprint 7 wave 2 Track T5 — 提成自动计算 listener.
 *
 * <p>触发: {@link OpportunityClosedWonEvent} (在 SalesOpportunity stage 转为
 * CLOSED_WON 时由 {@code SalesOpportunityService} 发布).
 *
 * <p>使用 {@code @TransactionalEventListener(AFTER_COMMIT)} 确保 opportunity 事务提交后
 * 才计算提成, 避免回滚导致脏数据.
 *
 * <p>Best-effort: listener 失败仅 log, 不影响 caller. CommissionService.calculate
 * 本身 idempotent, 即使重复触发也安全.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionEventListener {

    private final CommissionService commissionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClosedWon(OpportunityClosedWonEvent event) {
        log.info("Received OpportunityClosedWonEvent for opp={}, calculating commission",
                event.getOpportunityId());
        try {
            commissionService.calculate(event.getOpportunityId());
        } catch (Exception e) {
            log.error("Failed to calculate commission for opportunity {}",
                    event.getOpportunityId(), e);
        }
    }
}
