package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * SP3: 生产成本已更新事件.
 *
 * <p>由 {@link com.cretas.aims.service.wip.impl.WipInventoryServiceImpl#postSemiOutputLedger}
 * 在 OUT 过账后发布, 触发:
 * <ul>
 *   <li>OrderCostBackfillListener  — 回填 SalesOrderItem.costUnitPrice</li>
 *   <li>OrderCostAlarmListener     — 超支百分比预警</li>
 * </ul>
 */
@Getter
public class ProductionCostUpdatedEvent extends ApplicationEvent {

    private final String factoryId;

    /** 关联的生产批次ID (SemiFinishedInventory.intermediateBatchNo 归属批次). */
    private final Long productionBatchId;

    /** 产品类型ID, 用于查标准BOM成本. */
    private final String productTypeId;

    /** SP1 移动均价计算后的实际单位成本. */
    private final BigDecimal actualUnitCost;

    /** 实际总成本 (可选, 用于汇总级超支判断). */
    private final BigDecimal actualTotalCost;

    public ProductionCostUpdatedEvent(Object source, String factoryId, Long productionBatchId,
                                       String productTypeId, BigDecimal actualUnitCost,
                                       BigDecimal actualTotalCost) {
        super(source);
        this.factoryId = factoryId;
        this.productionBatchId = productionBatchId;
        this.productTypeId = productTypeId;
        this.actualUnitCost = actualUnitCost;
        this.actualTotalCost = actualTotalCost;
    }

    @Override
    public String toString() {
        return String.format("ProductionCostUpdatedEvent[factoryId=%s, batchId=%d, productTypeId=%s, actualUnitCost=%s]",
                factoryId, productionBatchId, productTypeId, actualUnitCost);
    }
}
