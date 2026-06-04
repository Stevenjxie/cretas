package com.cretas.aims.event;

import com.cretas.aims.service.restaurant.RestaurantCommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 餐饮营销员月度阶梯提成 listener（#59 Phase 2）。
 *
 * <p>触发: {@link RestaurantVisitAttributedEvent}（{@code RestaurantCrmServiceImpl.recordVisitAt}
 * 在计业绩到访 visit_number &gt;= 2 时发布）。
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 确保到访事务提交后才结算提成，避免到访回滚
 * 导致脏提成。{@code fallbackExecution=true} 让无事务上下文时（如手工 publish / 测试）也执行。
 *
 * <p>Best-effort fail-soft: listener 异常仅 log，不影响 caller（到访记录已提交）。
 * {@code RestaurantCommissionService.settleForVisit} 在 {@code REQUIRES_NEW} 独立事务结算且
 * idempotent（visitId 唯一），即使重复触发也安全 —— 契合 doomed-tx 规则（独立事务不污染父事务）。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantCommissionEventListener {

    private final RestaurantCommissionService restaurantCommissionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVisitAttributed(RestaurantVisitAttributedEvent event) {
        log.info("收到 RestaurantVisitAttributedEvent {}，结算营销员阶梯提成", event);
        try {
            restaurantCommissionService.settleForVisit(
                    event.getFactoryId(),
                    event.getVisitId(),
                    event.getRepId(),
                    event.getVisitRevenue(),
                    event.getVisitAt()  // 计提周期 period_key 按到访月份归月 (settleForVisit 内 null 降级用结算时刻)
            );
        } catch (Exception e) {
            log.error("餐饮提成结算失败 (不影响到访记录) visit={} rep={}: {}",
                    event.getVisitId(), event.getRepId(), e.getMessage(), e);
        }
    }
}
