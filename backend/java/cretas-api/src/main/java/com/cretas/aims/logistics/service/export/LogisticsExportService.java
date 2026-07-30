package com.cretas.aims.logistics.service.export;

/**
 * Phase 5 — 排线计划导出 (handoff §11.3 {@code /plans/{planId}/export.csv|.xlsx}).
 *
 * <p>导出内容必须与计划详情逐字段一致 (handoff §16.1 最后一条: "导出顺序、里程、方数、重量与计划详情
 * 一致") — 本 service 直接从 {@link com.cretas.aims.logistics.entity.LogisticsPlan} /
 * {@link com.cretas.aims.logistics.entity.LogisticsTrip} / {@link com.cretas.aims.logistics.entity.LogisticsStop}
 * 已落库的值读出，不重新计算/不四舍五入成别的口径。
 */
public interface LogisticsExportService {

    /**
     * 导出计划为 CSV (UTF-8 BOM，Excel 打开中文不乱码)。
     *
     * @param factoryId 认证上下文取得的工厂 id（租户隔离）
     * @param planId    计划 id
     * @return CSV 字节内容
     */
    byte[] exportCsv(String factoryId, String planId);

    /**
     * 导出计划为 XLSX (EasyExcel)。
     *
     * @param factoryId 认证上下文取得的工厂 id（租户隔离）
     * @param planId    计划 id
     * @return XLSX 字节内容
     */
    byte[] exportXlsx(String factoryId, String planId);
}
