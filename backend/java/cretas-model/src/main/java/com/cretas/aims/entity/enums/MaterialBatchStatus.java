package com.cretas.aims.entity.enums;

/**
 * 原材料批次状态枚举
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public enum MaterialBatchStatus {
    /** 库存中（兼容旧数据） */
    IN_STOCK("库存中", "批次在库存中"),
    /** 可用 */
    AVAILABLE("可用", "批次可以正常使用"),
    /** 鲜品 - 2025-11-20新增 */
    FRESH("鲜品", "新鲜原材料批次"),
    /** 冻品 - 2025-11-20新增 */
    FROZEN("冻品", "已冻结原材料批次"),
    /** 已耗尽（预留+剩余=0） */
    DEPLETED("已耗尽", "批次已全部预留或消耗，无剩余可用"),
    /** 已用完 */
    USED_UP("已用完", "批次已全部消耗"),
    /** 已过期 */
    EXPIRED("已过期", "批次已超过保质期"),
    /** 质检中 */
    INSPECTING("质检中", "批次正在质量检验"),
    /** 已报废 */
    SCRAPPED("已报废", "批次已报废处理"),
    /** 已预留 */
    RESERVED("已预留", "批次已被预留，等待使用"),
    /** 生产预留 (在制品 WIP) - 关联到进行中的生产批次 */
    PRODUCING_RESERVED("生产预留", "批次已被进行中的生产批次占用 (在制品 WIP)"),
    /**
     * 不良品 (issue #795 PURCHASE_RETURN with-goods 不良品入库).
     * <p>PURCHASE_RETURN with-goods 完成时, 物料退回到 WH-LOG, 状态置为 DEFECTIVE.
     * 与 SALES_RETURN with-goods 在 FinishedGoodsBatch 上用 status='DEFECTIVE'
     * 形成对称语义. 现有 FEFO / sumAvailable / findAvailable 查询都过滤
     * status='AVAILABLE', 自动排除 DEFECTIVE, 无需重构 repository.
     */
    DEFECTIVE("不良品", "批次为不良品 (退货回库, 需单独处理, 不可用于生产/销售)");

    private final String displayName;
    private final String description;

    MaterialBatchStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
