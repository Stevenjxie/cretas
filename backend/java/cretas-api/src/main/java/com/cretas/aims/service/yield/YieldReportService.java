package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.CostReconcileResult;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.OrderYieldSummaryDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface YieldReportService {

    /** 工人逐道报工 (投入+产出双量). 返回 {reportId, yieldRate, alert?} */
    Map<String, Object> submitReport(String factoryId, Long batchId, Long workerId, YieldReportRequest req);

    /** 领料环节记 出库量+投料量 (首道). 返回 {reportId} */
    Map<String, Object> recordMaterialInput(String factoryId, Long batchId, Long workerId, MaterialInputRequest req);

    /** 整批出成率 (派生). */
    BatchYieldDTO getYield(String factoryId, Long batchId);

    /**
     * SP-C: 按批次号查出成率 (存货生产无订单号场景).
     * 通过 findByFactoryIdAndBatchNumber 解析批次 id, 再复用 getYield.
     * 批次不存在 → {@code BusinessException(404)}.
     */
    BatchYieldDTO getBatchYieldByNumber(String factoryId, String batchNumber);

    /**
     * 段2(B): 按批次号做辅料标准单价双锚点投料-产出对账 (抓多投/误差)。
     *
     * <p>标准侧 = (产品×工序) 配置的 {@code standardYieldRate}, 实际侧 = 逐道报工 (复用 {@link #getYield})。
     * 标准应投 ÷ 实际投料 → 多投信号; 辅料标准/实际/多投; 阈值工厂可配 (默认 5%)。只读, 不动库存。
     * 批次不存在 → {@code BusinessException(404)}; 无报工 → 诚实空态 (issue NO_ACTUAL_STEPS)。</p>
     */
    CostReconcileResult getBatchReconcile(String factoryId, String batchNumber);

    /**
     * 单元 F (F006 REQ-21 "以订单的模式呈现…分订单分产品分工序"):
     * 一张销售订单下全部生产批次的出成率聚合 (复用 {@link #getYield} 逐批求, 再聚合)。
     *
     * <p>数据链: orderId → ProductionPlan(source_order_id) → ProductionBatch(production_plan_id IN)
     * → 每批 getYield。总投入/总产出/整体出成率仅单位一致时计算; 成本 null-safe 累加。
     * 订单无计划/批次 → 空 batches + batchCount 0 (诚实空态, 非异常)。</p>
     */
    OrderYieldSummaryDTO getOrderYieldSummary(String factoryId, String orderId);

    /**
     * G6/G7 (Wave 4): 该批次半成品库存 (WIP) 只读列表 — 每道工序中间品存量。
     * 供 RN 报工领用展示 + web-admin 批次详情 WIP 区。按 processOrder 升序。
     * 无 WIP 行 → 空 list (诚实空态, 不臆造)。
     */
    List<WipRowDTO> listWip(String factoryId, Long batchId);

    /**
     * 预检端点 — 防呆 Rule 1: 报工 dialog 打开时前端调此端点, 取得超收边界预填 input max + 显示提示文字.
     *
     * @param inputQuantity 操作员填写的本道投入量; null/0 时 targetQuantity/maxAllowed/remaining 均为 null.
     */
    YieldLimitsDTO getLimits(String factoryId, Long batchId, Long workProcessTaskId, BigDecimal inputQuantity);

    /**
     * 人工标记每日结清. triggerComplete=true 时(末道结清/整批完成)聚合末道产出回写批次并入成品库.
     * 返回 {settledCount, batchYield, completed}
     */
    Map<String, Object> settleDay(String factoryId, Long batchId, Long workerId, LocalDate date, boolean triggerComplete);

    /**
     * A2b: 主动触发自动结清 — 供仓管员在 MaterialBatch 标记 USED_UP 之后手动调用,
     * 覆盖"先 USED_UP 后 recordMaterialInput 未触发"的边界场景.
     * 内部调用 checkAndAutoSettle(factoryId, batchId, materialBatchId).
     * 返回 {settledCount: N}
     */
    Map<String, Object> autoSettleByMaterialBatch(String factoryId, Long batchId, String materialBatchId);
}
