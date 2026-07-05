package com.cretas.aims.repository;

import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SP-F Task 1.1: ProcessSheetRow repository.
 *
 * <p>主键方法 findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId 对应 uk_sheet_row 唯一约束,
 * 供后续 upsert 端点使用。
 */
public interface ProcessSheetRowRepository extends JpaRepository<ProcessSheetRow, Long> {

    Optional<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
            String factoryId, String planId, String processCode, String clientRowId);

    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCode(
            String factoryId, String planId, String processCode);

    /**
     * SP-F role-mode fix: 双键查询 (factory, plan, processCode, processOrder)。
     *
     * <p>role-mode 下多道普通工序共享同一 archetype processCode (如 'chaoshui'),
     * 仅 processCode 过滤会把多道的行/库存混在一起。加 processOrder (链内唯一) 后
     * 每道分别隔离。仅当 processOrder 非空时由 service 调用; 旧客户端不传 processOrder
     * 时回退到 code-only finder (向后兼容)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
            String factoryId, String planId, String processCode, Integer processOrder);

    List<ProcessSheetRow> findByFactoryIdAndPlanId(
            String factoryId, String planId);

    /**
     * R4 (2026-07-04): 按物化后的 {@code batchId} (= ProductionBatch.id) 查行 —— 成本拆分
     * ({@code OrderCostBreakdownService}) 逐批读该批 process-row 的 SFI/FG 投料边, 补计投料成本。
     * factory-scoped 🔒。纯 SFI 中间道 (SAVED_SFI, batchId==null) 不物化, 不在此集合内 (其成本经
     * 下游道 semiFinished 投料的 getSemiUnitCost 移动均价传导, 无需在此单独读)。
     */
    List<ProcessSheetRow> findByFactoryIdAndBatchId(String factoryId, Long batchId);

    /**
     * SP-F Task 1.8: 按 (factory, plan, clientRowId) 查行 —— delete 端点路径不含 processCode。
     * 正常情况下 clientRowId 在同一 plan 内跨工序不重复，返回 1 条；边缘情形返多条则全删。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndClientRowId(
            String factoryId, String planId, String clientRowId);

    /**
     * 撤销小结: 查某次小结 (postedAt 时间戳) 打戳的全部产出行 (interim_settled_at == postedAt)。
     * 逆转时逐行清 interim_settled_at → 行恢复未结 (可再编辑/删除/重新小结)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndInterimSettledAt(
            String factoryId, String planId, LocalDateTime interimSettledAt);

    /**
     * 🔴🔒🔒 延迟扣减盲区 (sibling of #1216/#1219, 2026-07-05): 查整厂全部<b>待小结</b>
     * (interim_settled_at IS NULL) 且属<b>存货生产 (SAFETY_STOCK)</b> 计划的逐工序行。
     *
     * <p>用途: 小结前, SFI/FG 投料 (row_payload.upstreamSources 的 semiFinished/finishedGoods 引用)
     * 已物理消耗常驻半成品/成品库存, 但 {@code availableQuantity/producedQuantity} 要到「小结」
     * ({@code InterimSettleServiceImpl} §②) 才扣减 —— 与原料 {@code MaterialConsumption} 的延迟扣减同构。
     * 半成品盘点 / 成品销售发货 / 调拨 须减去这些「待扣」投料量, 否则拿 stale-high 账面 → 假盘亏(SFI)
     * 双重扣减 或 超发/超调 → 小结阶段 {@code SFI_INSUFFICIENT / FG_INSUFFICIENT} 409 卡死。
     *
     * <p><b>族门控 SAFETY_STOCK</b> (同 {@code sumUnsettledConsumptionGroupedByBatch} #1219 口径):
     * 只有存货生产计划走「小结」延迟扣减; 结单族 (CUSTOMER_ORDER/MANUAL) 走「结单」即时扣, 其行
     * {@code interim_settled_at} 恒 null 却早已落库 → 若一并计入会二次减同一投料 (假盘盈/超发放行)。
     * Factory-scoped 防跨租户。
     */
    @Query("SELECT r FROM ProcessSheetRow r, ProductionPlan p "
            + "WHERE r.factoryId = :factoryId AND r.interimSettledAt IS NULL "
            + "AND r.planId = p.id AND p.factoryId = :factoryId "
            + "AND p.sourceType = com.cretas.aims.entity.enums.PlanSourceType.SAFETY_STOCK")
    List<ProcessSheetRow> findUnsettledSafetyStockRows(@org.springframework.data.repository.query.Param("factoryId") String factoryId);
}
