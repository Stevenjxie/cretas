package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商机赢单事件 — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>SalesOpportunity.stage 转为 CLOSED_WON 时由 {@code SalesOpportunityService.transitionStage}
 * 发布. 触发 {@code CommissionEventListener} 自动计算提成.
 *
 * <p>事件 listener 链:
 * <ul>
 *   <li>{@code CommissionEventListener} → 创建 Commission(PENDING)</li>
 *   <li>(future) 业绩更新通知 / 销售排名刷新</li>
 * </ul>
 */
@Getter
public class OpportunityClosedWonEvent extends ApplicationEvent {

    private final String factoryId;
    private final String opportunityId;
    private final String customerId;
    private final Long ownerId;
    private final BigDecimal valueAmount;
    private final LocalDateTime closedAt;

    public OpportunityClosedWonEvent(Object source, String factoryId, String opportunityId,
                                       String customerId, Long ownerId, BigDecimal valueAmount,
                                       LocalDateTime closedAt) {
        super(source);
        this.factoryId = factoryId;
        this.opportunityId = opportunityId;
        this.customerId = customerId;
        this.ownerId = ownerId;
        this.valueAmount = valueAmount;
        this.closedAt = closedAt;
    }

    @Override
    public String toString() {
        return String.format(
                "OpportunityClosedWonEvent[factoryId=%s, opportunityId=%s, ownerId=%s, valueAmount=%s]",
                factoryId, opportunityId, ownerId, valueAmount);
    }
}
