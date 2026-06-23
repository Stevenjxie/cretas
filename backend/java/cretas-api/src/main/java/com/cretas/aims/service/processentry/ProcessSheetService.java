package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProcessSheetRowView;

import java.util.List;

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

    /**
     * SP-F Task 2.1: 读取指定工序的 WIP 在制品库存视图。
     *
     * <p>通过 process_sheet_rows (plan + processCode 范围, 🔒) 找到所属行,
     * 再经 WIP MaterialBatch 和下游 MaterialConsumption 汇总每批次的
     * produced / used / remaining / status / unitPrice。
     *
     * <p>plan 归属校验 (跨租户守卫) 由 controller 层前置或由 rowRepo 查询的
     * (factory, plan) 双键隐式覆盖 (factory-scoped 自然隔离 🔒)。
     *
     * @param factoryId   工厂 ID
     * @param planId      生产计划 ID
     * @param processCode 工序代码 (e.g. "xiuyou")
     * @return 该工序下所有已物化行的库存视图列表 (DRAFT 行跳过)
     */
    List<ProcessSheetInventoryItem> getInventory(String factoryId, String planId, String processCode);

    /**
     * SP-F Task 2.2: 读回指定工序下已保存的行列表 (供前端电子表格重载)。
     *
     * <p>同时返回 SAVED (已物化) 和 DRAFT (未物化) 行，包含原始录入 payload 的反序列化结果，
     * 可直接回填前端表单。查询 factory-scoped 🔒 (rowRepo 三键过滤)。
     *
     * @param factoryId   工厂 ID
     * @param planId      生产计划 ID
     * @param processCode 工序代码 (e.g. "xiuyou")
     * @return 该工序下所有行的视图列表 (含 DRAFT)
     */
    List<ProcessSheetRowView> getRows(String factoryId, String planId, String processCode);
}
