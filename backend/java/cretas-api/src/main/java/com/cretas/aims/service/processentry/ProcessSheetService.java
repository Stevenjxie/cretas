package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowHistoryView;
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
     * @param userId     操作人 userId (controller @RequestAttribute 注入, 供 SP-G P3 操作记录)
     * @throws BusinessException 404 — 行不存在; 409 — 已被下游消耗
     */
    void deleteRow(String factoryId, String planId, String clientRowId, Long userId);

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
     * <p>SP-F role-mode fix: processOrder 非空时用 (processCode, processOrder) 双键过滤,
     * 隔离同 archetype 多工序的库存; null 时回退 code-only 查询 (向后兼容)。
     *
     * @param factoryId    工厂 ID
     * @param planId       生产计划 ID
     * @param processCode  工序代码 (archetype, e.g. "chaoshui")
     * @param processOrder 链内唯一工序序号; null → code-only 回退
     * @return 该工序下所有已物化行的库存视图列表 (DRAFT 行跳过)
     */
    List<ProcessSheetInventoryItem> getInventory(String factoryId, String planId,
                                                 String processCode, Integer processOrder);

    /**
     * SP-F Task 2.2: 读回指定工序下已保存的行列表 (供前端电子表格重载)。
     *
     * <p>同时返回 SAVED (已物化) 和 DRAFT (未物化) 行，包含原始录入 payload 的反序列化结果，
     * 可直接回填前端表单。查询 factory-scoped 🔒 (rowRepo 三键过滤)。
     *
     * <p>SP-F role-mode fix: processOrder 非空时用 (processCode, processOrder) 双键过滤,
     * 隔离同 archetype 多工序的行; null 时回退 code-only 查询 (向后兼容)。
     *
     * @param factoryId    工厂 ID
     * @param planId       生产计划 ID
     * @param processCode  工序代码 (archetype, e.g. "chaoshui")
     * @param processOrder 链内唯一工序序号; null → code-only 回退
     * @return 该工序下所有行的视图列表 (含 DRAFT)
     */
    List<ProcessSheetRowView> getRows(String factoryId, String planId,
                                      String processCode, Integer processOrder);

    /**
     * F006 双出成率: 计划级半成品库存卡 (跨工序汇总视图)。
     *
     * <p>返回该计划所有生产批次下的 SemiFinishedInventory 行, 按 processOrder 升序排列,
     * 并填充 stepYieldRate (对上工序) 与 cumulativeYieldRate (对原料) 两个出成率字段。
     *
     * <p><b>stepYieldRate 算法</b>: 首道 = Σ该批次YIELD报工 inputQuantity (原料投入);
     * 后续道 = 上一道 WIP 行的 producedQuantity. 产出 / 投入 × 100, scale 4 HALF_UP.
     * 投入 = 0 / 无数据 → null (诚实, 不造假).
     *
     * <p><b>cumulativeYieldRate 算法</b>: 本道产出折算首道单位后 / 首道原料投入 × 100.
     * 跨单位时用 ProductType.gramsPerUnit (g/份) 折算 kg; 无折算系数 → null.
     *
     * @param factoryId 工厂 ID (factory-scoped 🔒)
     * @param planId    生产计划 ID
     * @return 按 processOrder 升序排列的库存卡行列表 (含出成率)
     */
    List<ProcessSheetInventoryItem> getInventoryYieldCard(String factoryId, String planId);

    /**
     * SP-G P3: 读取某一行的操作记录时间线 (字段级 diff 审计)。
     *
     * <p>按 (factory, plan, processCode, clientRowId) 定位某一行的全部变更，按创建时间倒序
     * (最新在前)。查询天然 factory-scoped (🔒)。
     *
     * @param factoryId   工厂 ID
     * @param planId      生产计划 ID
     * @param processCode 工序代码 (e.g. "xiuyou")
     * @param clientRowId 前端行键
     * @return 该行的全部变更记录 (CREATE / UPDATE / DELETE)，时间倒序
     */
    List<ProcessSheetRowHistoryView> getRowHistory(String factoryId, String planId,
                                                   String processCode, String clientRowId);
}
