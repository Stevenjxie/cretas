package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 餐饮到访业绩归属事件。
 *
 * <p>#59 Phase 1 — 当一次到访 <b>计业绩</b>（visit_number &gt;= 2，即第二次复购起）时发布。
 * 携带快照营销员 ID（{@code repId}）与本次到访营收（{@code visitRevenue}），供 Phase 2
 * 阶梯提成监听器结算（Phase 1 <b>只发布事件、不建监听器</b>——邓总精确阶梯费率尚未确定）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Getter
public class RestaurantVisitAttributedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String guestId;
    private final String visitId;
    /** 业绩归属营销员（到访时快照），可空（散客无营销员维护时为 null）。 */
    private final Long repId;
    /** 本次到访营收。 */
    private final BigDecimal visitRevenue;
    /**
     * 本次到访时间（Phase 2 用于月度计提周期 period_key 'YYYY-MM' 归月，避免用结算时刻跨月误算）。
     * 可空（旧 publish 站点未携带时为 null → 结算降级用结算时刻）。
     */
    private final LocalDateTime visitAt;

    public RestaurantVisitAttributedEvent(Object source, String factoryId, String guestId,
                                          String visitId, Long repId, BigDecimal visitRevenue,
                                          LocalDateTime visitAt) {
        super(source);
        this.factoryId = factoryId;
        this.guestId = guestId;
        this.visitId = visitId;
        this.repId = repId;
        this.visitRevenue = visitRevenue;
        this.visitAt = visitAt;
    }

    @Override
    public String toString() {
        return String.format("RestaurantVisitAttributedEvent[factoryId=%s, guestId=%s, visitId=%s, repId=%s, revenue=%s, visitAt=%s]",
                factoryId, guestId, visitId, repId, visitRevenue, visitAt);
    }
}
