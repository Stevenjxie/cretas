package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;

/**
 * SP-F 逐工序电子表格服务 (spec §4)。
 *
 * <p>每行存一次 (增量 upsert)，一行 = 一个批次的一道工序。上下游靠真实持久化的
 * batchNumber (跨请求) 串联，复用 {@link ClerkProcessEntryService#materializeBatch}
 * 写核心。
 */
public interface ProcessSheetService {

    /**
     * 增量保存/更新一行 (upsert by factory+plan+processCode+clientRowId)。
     *
     * <p>新建路径 (Task 1.5): 解析 plan 归属 + factory-scoped 上游边 + ctx →
     * materializeBatch → 写 process_sheet_rows。outputQty<=0 → DRAFT 不物化。
     *
     * <p>已存行 → 委托 resaveRow (Task 1.6, 当前为 409 stub)。
     *
     * @param factoryId 工厂 ID
     * @param planId    生产计划 ID (String — 与 ClerkProcessEntryController 一致)
     * @param req       单行录入请求
     * @param userId    文员 token userId (controller @RequestAttribute 注入)
     * @return 单行物化结果
     */
    ProcessSheetRowResult saveRow(String factoryId, String planId,
                                  ProcessSheetRowRequest req, Long userId);

    /**
     * 删除一行 (SP-F Task 1.8)。
     *
     * <p>若行已物化 (batchId != null): 先检查下游消耗守卫 (有消耗 → 409)，再逆向物化
     * (软删消耗边 + 报工 + WIP MaterialBatch + ProductionBatch)，最后软删行本身。
     *
     * <p>若行为 DRAFT (batchId == null): 直接软删行，不涉及批次操作。
     *
     * @param factoryId  工厂 ID
     * @param planId     生产计划 ID
     * @param clientRowId 前端行键 (在同一 plan 内唯一)
     * @throws BusinessException 404 — 行不存在; 409 — 已被下游消耗
     */
    void deleteRow(String factoryId, String planId, String clientRowId);
}
