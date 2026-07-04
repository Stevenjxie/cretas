package com.cretas.aims.entity.enums;

/**
 * 内部调拨单状态枚举（完整状态机）
 *
 * DRAFT → REQUESTED → APPROVED → SHIPPED → RECEIVED → CONFIRMED → REVERSED (小结撤销连带冲销, 仅同厂调拨)
 *                  ↘ REJECTED
 * 任意非终态 → CANCELLED
 */
public enum TransferStatus {
    DRAFT("草稿", "调拨单草稿"),
    REQUESTED("已申请", "分店/分厂已提交调拨申请"),
    APPROVED("已审批", "总部/调出方已审批通过"),
    REJECTED("已驳回", "调拨申请被驳回"),
    SHIPPED("已发货", "调出方已出库发货"),
    RECEIVED("已签收", "调入方已签收"),
    CONFIRMED("已确认", "双方确认完成，库存已更新"),
    CANCELLED("已取消", "调拨单已取消"),
    /**
     * 已冲销 — 仅同厂调拨 (source==target) 由 {@code FinishedGoodsFeedServiceImpl.reverseInterimCreate}
     * 在其 TRF-child 成品批次被撤销小结<b>整批退回归零</b> (状态转 {@code FinishedGoodsBatch.Status.REVERSED})
     * 时置入, 表示"该 CONFIRMED 调拨记录的库存效力已被上游生产小结撤销作废, 不应再被当作有效在库调拨/收货处理"。
     * 物理货物是否已实际搬回由人工核实 (账面冲销 ≠ 物理必然归位) — 见 rejectReason 落的操作提示。
     * 无 DB CHECK 约束限制 status 列的合法取值 (纯 VARCHAR(32), 见 database-entity-sync.md), 新增安全。
     */
    REVERSED("已冲销", "关联的生产小结已撤销, 该调拨对应的成品库存已作废(需人工核实物理货物是否已实际归位)");

    private final String displayName;
    private final String description;

    TransferStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == REJECTED || this == REVERSED;
    }
}
