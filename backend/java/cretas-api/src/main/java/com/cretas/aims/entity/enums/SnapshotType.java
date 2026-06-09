package com.cretas.aims.entity.enums;

/**
 * SP11: 库存台账快照类型.
 */
public enum SnapshotType {
    /** 月结时触发 (期末快照 = 下期期初) */
    PERIOD_CLOSE,
    /** 手工盘点触发的临时快照 */
    MANUAL_STOCKTAKE
}
