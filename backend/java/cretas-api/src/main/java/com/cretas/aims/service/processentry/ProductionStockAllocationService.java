package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;

import java.math.BigDecimal;
import java.util.List;

public interface ProductionStockAllocationService {

    List<PlannedAllocation> plan(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.MaterialInputTotal> materialInputTotals);

    /** Lock and reserve legacy caller-selected production-stock batches. */
    List<PlannedAllocation> planExplicit(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.RawInput> rawMaterialInputs);

    /** FEFO-lock and reserve BOM-derived packaging/seasoning requirements in their native stock units. */
    List<PlannedAllocation> planNative(
            String factoryId,
            String planId,
            List<AutomaticRequirement> requirements);

    void persist(
            String factoryId,
            String planId,
            Long processSheetRowId,
            Long userId,
            List<PlannedAllocation> allocations);

    List<ProcessSheetRowRequest.RawInput> toRawInputs(List<PlannedAllocation> allocations);

    List<ProcessSheetRowResult.InputAllocation> toResult(List<PlannedAllocation> allocations);

    record PlannedAllocation(
            String materialTypeId,
            String materialBatchId,
            String batchNumber,
            String warehouseId,
            BigDecimal quantity,
            String unit,
            Integer allocationOrder,
            String workflowPortId,
            String materialNodeId,
            String materialName,
            String sourceType,
            BigDecimal unitPrice,
            BigDecimal totalCost,
            boolean automatic) {

        public PlannedAllocation(
                String materialTypeId,
                String materialBatchId,
                String batchNumber,
                String warehouseId,
                BigDecimal quantity,
                String unit,
                Integer allocationOrder,
                String workflowPortId,
                String materialNodeId) {
            this(materialTypeId, materialBatchId, batchNumber, warehouseId, quantity, unit,
                    allocationOrder, workflowPortId, materialNodeId,
                    materialTypeId, "RAW_MATERIAL", null, null, false);
        }
    }

    /**
     * 只读: 每个投入端口在**生产仓**的当前可投量, 外加同物料在别的仓的存量。
     *
     * <p>🔴 与 {@link #plan} 走同一段核心 —— 同一个生产仓、同一套单位归一、同样扣掉待占用、
     * 同样过客供料归属守卫。唯一区别是用**非锁定** query (它是给界面渲染用的, 每次打开都会调)。</p>
     *
     * <p>为什么不能让前端自己算 (2026-07-31 客户实测): 界面显示「可用 10kg」而提交时后端说
     * 「可用 0kg」。上面三样口径前端一个都拿不到, 自己算必然偏大 —— 一个偏大且看着权威的数字
     * 比不显示更糟, 仓管员会照着它排活。</p>
     *
     * <p>{@code elsewhere} 是为了把**「真没货」和「有货但没调过来」**区分开: 刘山门实测
     * 生产仓里一条原料都没有 (全是 WIP 半成品), 原料都在主仓 —— 只报「可用 0」用户会以为系统坏了。</p>
     */
    List<PortAvailability> availability(
            String factoryId,
            String planId,
            List<ProcessSheetRowRequest.MaterialInputTotal> ports);

    /** 一个投入端口的可投量。unit 与端口声明的单位一致, 前端直接显示不再换算。 */
    record PortAvailability(
            String workflowPortId,
            String materialTypeId,
            BigDecimal available,
            String unit,
            List<ElsewhereStock> elsewhere,
            /**
             * 生产仓里<b>过期但仍有余量</b>的数量 —— 只用于展示与提醒, <b>不计入 available</b>。
             *
             * <p>过期批次原本被 {@code status='AVAILABLE'} 条件静默滤掉, 界面只剩一句
             * 「生产仓可用 0」, 仓管分不清是<b>真没货</b>还是<b>货过期了</b>(实测 F006 羊排
             * 原料仓有 100kg 但全部 EXPIRED, 界面既不提示也不解释)。把它单独报出来,
             * 前端可显示「过期 100kg · 不可投, 请处理」并引导去报损/处置。
             */
            BigDecimal expired,
            /**
             * <b>生产仓以外</b>各仓的过期存量 —— 同样只用于展示与提醒。
             *
             * <p>🔴 2026-08-03: {@code expired} 只统计生产仓, {@code elsewhere} 只统计
             * {@code status='AVAILABLE'} —— 于是「别的仓有货但过期了」这一种形态<b>两边都不覆盖</b>,
             * 整批从界面上消失。prod 实证: F006 羊排原料仓有 300kg 全过期(其中 100kg 还是按「箱」
             * 存量的), 而界面只显示生产仓的 expired=300, 原料仓那 300kg 一个字都没有。
             *
             * <p>讽刺的是 {@code findExpiredBatchesByWarehouse} 的 Javadoc 写的动机就是
             * 「实测 F006 羊排<b>在原料仓</b>有 100kg 但全部 EXPIRED」, 可它的实现按生产仓过滤 ——
             * 实现与自己写的动机相反。
             */
            List<ElsewhereStock> expiredElsewhere) {
    }

    /** 同物料在**非生产仓**的存量 —— 用于「主仓另有 200 只, 待调拨入生产仓」。 */
    record ElsewhereStock(
            String warehouseName,
            BigDecimal quantity,
            String unit) {
    }

    record AutomaticRequirement(
            String materialTypeId,
            String materialName,
            BigDecimal quantity,
            String unit,
            String sourceType) {
    }
}
