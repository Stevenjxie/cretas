package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成品创建事件
 * 当生产完成并创建成品批次入库时触发，用于关联销售订单并驱动出库流程
 */
@Getter
public class FinishedGoodsCreatedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String sourceOrderId;
    private final String productTypeId;
    private final BigDecimal quantity;
    private final String batchId;
    private final LocalDateTime createdAt;

    /**
     * T126 Phase 1 (F8) — 来源类型，供监听者过滤批量期初入库条目。
     * <ul>
     *   <li>"OPENING" — 期初/手工入库（POST /finished-goods/opening）</li>
     *   <li>"PRODUCTION" — 生产计划完工入库（SupplyChainOrchestrator 路径）</li>
     *   <li>null — 未指定（老代码路径，向后兼容）</li>
     * </ul>
     */
    private final String sourceType;

    /** Backward-compatible constructor (sourceType = null). */
    public FinishedGoodsCreatedEvent(Object source, String factoryId, String sourceOrderId,
                                      String productTypeId, BigDecimal quantity, String batchId) {
        this(source, factoryId, sourceOrderId, productTypeId, quantity, batchId, null);
    }

    /** Full constructor with sourceType. */
    public FinishedGoodsCreatedEvent(Object source, String factoryId, String sourceOrderId,
                                      String productTypeId, BigDecimal quantity, String batchId,
                                      String sourceType) {
        super(source);
        this.factoryId = factoryId;
        this.sourceOrderId = sourceOrderId;
        this.productTypeId = productTypeId;
        this.quantity = quantity;
        this.batchId = batchId;
        this.createdAt = LocalDateTime.now();
        this.sourceType = sourceType;
    }

    @Override
    public String toString() {
        return String.format("FinishedGoodsCreatedEvent[factoryId=%s, sourceOrderId=%s, productTypeId=%s, quantity=%s, batchId=%s, createdAt=%s]",
                factoryId, sourceOrderId, productTypeId, quantity, batchId, createdAt);
    }
}
