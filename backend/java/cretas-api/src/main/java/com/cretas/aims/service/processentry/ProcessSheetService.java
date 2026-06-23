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
}
