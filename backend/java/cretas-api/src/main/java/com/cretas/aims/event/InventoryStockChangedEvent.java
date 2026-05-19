package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存数量变更事件 — Phase 2 Canvas-Alerts 引入.
 *
 * <p>触发 {@code AlertInventoryLowListener}, 评估 INVENTORY_LOW 规则.
 * 在入库 / 出库 / 调整后由业务 Service publish, 让告警引擎实时检查阈值.
 *
 * <p><b>Phase 2 follow-up</b>: WMS Service (MaterialBatchServiceImpl /
 * InventoryService 等) 应在写操作后 publish 此事件. 当前 Listener 在
 * @PostConstruct log.warn 提示未挂接, 但 listener 逻辑完整可用.
 *
 * <p>定时扫描兜底 ({@code AlertInventoryLowScheduler}) 每 15 分钟检查 — 即使
 * publish 事件漏发, 也会被 scheduler 抓到.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Getter
public class InventoryStockChangedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String materialTypeId;
    private final String materialName;
    /** 变更后的当前库存量. */
    private final BigDecimal currentStock;
    /** 物料最低库存阈值 (来自 material_type 配置, null 表示不计预警). */
    private final BigDecimal minStockLevel;
    /** 计量单位. */
    private final String unit;
    /** 触发本次变更的操作: e.g. "IN" / "OUT" / "ADJUST". */
    private final String changeType;
    private final LocalDateTime occurredAt;

    public InventoryStockChangedEvent(Object source, String factoryId, String materialTypeId,
                                       String materialName, BigDecimal currentStock,
                                       BigDecimal minStockLevel, String unit, String changeType) {
        super(source);
        this.factoryId = factoryId;
        this.materialTypeId = materialTypeId;
        this.materialName = materialName;
        this.currentStock = currentStock;
        this.minStockLevel = minStockLevel;
        this.unit = unit;
        this.changeType = changeType;
        this.occurredAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format(
                "InventoryStockChangedEvent[factoryId=%s, materialName=%s, currentStock=%s, minStockLevel=%s]",
                factoryId, materialName, currentStock, minStockLevel);
    }
}
