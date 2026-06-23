package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.MaterializeContext;
import com.cretas.aims.dto.processentry.MaterializedBatch;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.dto.processentry.ResolvedEdge;

import java.math.BigDecimal;
import java.util.List;

/**
 * 文员逐道录入服务接口. SP-B1 Task 3.
 *
 * <p>接收一条生产链(多个半成品批 + 1 成品批)的文员录入负载,
 * 物化成本图: 每道工序的原料/调料/混锅消耗 → MaterialConsumption 行,
 * 半成品产出 → MaterialBatch(sourceDocType=PRODUCTION_BATCH), 供 OrderCostBreakdownService.traceCost 回溯。
 */
public interface ClerkProcessEntryService {
    /**
     * 录入一条生产链并物化成本图。幂等(idempotencyKey per factoryId+planId)。
     *
     * @param factoryId  工厂 ID
     * @param planId     生产计划 ID (可空, 无计划时传 null)
     * @param req        逐道录入请求
     * @param operatorId 文员 token userId (⛔ 禁用 SecurityUtils 取, 从 controller @RequestAttribute 注入)
     * @return 录入结果 (含批次号 + 统计 + 幂等标记)
     */
    ProcessChainEntryResult recordChain(String factoryId, String planId,
                                        ProcessChainEntryRequest req, Long operatorId);

    /**
     * 物化单个批次的 WRITE 逻辑 (SP-F Task 1.3 KEYSTONE seam)。
     *
     * <p>共享给 {@code recordChain} 和未来的逐行 caller (ProcessSheetService)。
     * <b>不做任何上游/仓库/工时单价 resolution</b> —— edges 与 ctx 全部已由 caller 预解析,
     * 这样不同 caller 可用不同上游解析策略 (in-memory map vs 持久化批次号)。
     *
     * <p>职责: 建 ProductionBatch + 写 MaterialConsumption(每条 edge) +
     * SEASONING ProductionReport + 人工成本累加 + WIP MaterialBatch 产出。
     *
     * @param ctx      单批上下文 (factoryId/planId/finished/laborRate/warehouseId/rawMaterialTypeId 等已预解析)
     * @param steps    本批工序列表 (供调料/人工/产出量计算; 上游消耗已由 edges 替代, 不在此重新解析)
     * @param edges    已解析的上游消耗边 (RAW + SEMI_FINISHED), 是本方法唯一的上游消耗输入
     * @param warnings 警告收集器 (调料配方缺失/未识别调味工序 等), 由 caller 提供并汇总
     * @return 物化结果 (批次 id/号 + WIP MaterialBatch id + 总成本 + 消耗行数)
     */
    MaterializedBatch materializeBatch(MaterializeContext ctx,
                                       List<StepEntry> steps,
                                       List<ResolvedEdge> edges,
                                       List<String> warnings);

    /**
     * SP-F Task 1.6: 原地重物化一个<b>已存在</b>的批次 (保留 ProductionBatch + WIP MaterialBatch 的 id)。
     *
     * <p>与 {@link #materializeBatch} 的关键区别: <b>不新建</b> ProductionBatch / WIP MaterialBatch ——
     * 而是复用 caller 传入的 {@code existingBatchId} + {@code existingWipMbId}, 仅重写消耗边/报工 +
     * 更新 WIP 批的 receiptQuantity/unitPrice + ProductionBatch.quantity。
     *
     * <p><b>为什么原地更新而非删除重建</b>: 下游消费者按 id 引用这两个实体
     * (MaterialConsumption.batchId = WIP UUID; MaterialBatch.sourceDocId = ProductionBatch id)。
     * 删除重建会 churn id 并悬挂这些引用。
     *
     * <p><b>caller 的前置职责</b> (本方法<b>不</b>做): 已校验无下游消耗 + 已软删旧消耗边/报工。
     * 本方法只: 重写消耗边 + SEASONING/人工报工 + 重算总成本 + 更新现有 WIP 批 + ProductionBatch.quantity。
     *
     * @param ctx            单批上下文 (batchNumber 必传现有批次号以保留, planId/finished/laborRate 等已预解析)
     * @param existingBatchId 现有 ProductionBatch.id (保留, 不新建)
     * @param existingWipMbId 现有 WIP MaterialBatch.id (保留, 不新建; 可空 = 此批之前无 WIP 产出)
     * @param steps          本批工序列表 (供调料/人工/产出量计算)
     * @param edges          已解析的上游消耗边 (RAW + SEMI_FINISHED), 唯一上游消耗输入
     * @param warnings       警告收集器
     * @return 物化结果 (productionBatchId = existingBatchId, wipMaterialBatchId = existingWipMbId, 新总成本 + 消耗行数)
     */
    MaterializedBatch rematerializeInPlace(MaterializeContext ctx, Long existingBatchId,
                                           String existingWipMbId, List<StepEntry> steps,
                                           List<ResolvedEdge> edges, List<String> warnings);

    /**
     * 解析工厂工时单价 (SP-F: 暴露给 ProcessSheetService 复用, 单一真相)。
     *
     * <p>从 factory_cost_settings 读 laborHourlyRate; 未配置则回退默认值并记 warning。
     *
     * @param factoryId 工厂 ID
     * @param warnings  warning 收集器 (fallback 时追加)
     * @return 工时单价 (¥/工时)
     */
    BigDecimal resolveLaborRate(String factoryId, List<String> warnings);

    /**
     * 解析工厂仓库 ID (SP-F: 暴露给 ProcessSheetService 复用, 单一真相)。
     *
     * <p>按 code 查 (通常 WH_WKS 车间仓); 未找到则回退第一个可用仓库并记 warning;
     * 工厂无任何仓库时回退 placeholder。factory-scoped, 无跨租户扫描。
     *
     * @param factoryId 工厂 ID
     * @param code      仓库 code (如 WarehouseCodes.WH_WKS)
     * @param warnings  warning 收集器 (fallback 时追加)
     * @return 仓库 ID
     */
    String resolveWarehouseId(String factoryId, String code, List<String> warnings);
}
