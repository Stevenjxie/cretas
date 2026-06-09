package com.cretas.aims.service.inventory;

/**
 * SP11: 进销存台账期末快照服务.
 *
 * <p>在月结 (AccountingPeriod confirmClose) 时调用 {@link #createForPeriod},
 * 快照当期各物料期末结存, 作为下一期期初.
 */
public interface InventoryLedgerSnapshotService {

    /**
     * 为指定会计期创建期末快照 (幂等: 同 factoryId + accountingPeriodId + materialTypeId 唯一键 upsert).
     *
     * @param factoryId         工厂 ID
     * @param accountingPeriodId 会计期 ID
     */
    void createForPeriod(String factoryId, String accountingPeriodId);
}
