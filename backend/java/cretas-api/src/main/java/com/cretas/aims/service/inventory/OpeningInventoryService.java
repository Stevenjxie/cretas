package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.finance.OpeningApCorrectionRequest;
import com.cretas.aims.dto.finance.OpeningApCorrectionResult;
import com.cretas.aims.dto.material.OpeningInventoryRequest;
import com.cretas.aims.dto.material.OpeningInventoryResult;

/**
 * 期初建账 (opening inventory onboarding) 服务。
 *
 * <p>提供一条<b>干净的</b>建账通道: 凭空建立起始库存 (MaterialBatch) + 过一张期初凭证
 * (借 1403 原材料 / 贷 4001 实收资本), <b>不产生任何供应商应付</b>。修复此前客户被迫走
 * 采购入库建账 (每个物料挂供应商应付 → ¥436k 幽灵应付) 的问题。
 *
 * <p>期初存货的会计对方科目是<b>实收资本</b> (业主投入的存货), 不是营业外收入 (6301, 会虚增建账当期损益),
 * 也不是供应商应付 (2202)。此约定与 {@code FactoryStocktakeServiceImpl} 的 OPENING 盘点分支一致。
 */
public interface OpeningInventoryService {

    /**
     * 期初建账: 批量建立起始库存批次 + 过一张期初凭证 (借 1403 / 贷 4001)。
     *
     * <ul>
     *   <li>每行建一个 {@code MaterialBatch} (sourceDocType=OPENING), 数量入库, <b>不挂应付</b>;</li>
     *   <li>全部行汇总过<b>一张</b>期初凭证 (借 1403 原材料 = Σ数量×单价 / 贷 4001 实收资本 = 同额);</li>
     *   <li><b>诚实-null</b>: 未录单价的行仍建批次 (数量), 但不计入凭证金额 + 记日志, 绝不臆造价值;</li>
     *   <li><b>幂等</b>: 相同 batchKey (或相同内容自动派生 key) 重复提交返回既有结果, 不双建/不双过账;</li>
     *   <li>支持 200+ 行一次导入 (六膳门 215 物料)。</li>
     * </ul>
     */
    OpeningInventoryResult createOpeningInventory(String factoryId, OpeningInventoryRequest request, Long userId);

    /**
     * 修正"误走采购入库建账"产生的幽灵应付: 红冲指定应付挂账 + 补正确的期初凭证 (借 1403 / 贷 4001),
     * <b>库存数量不动</b>。管理员专用、幂等、留审计痕迹。要修正哪几笔由请求参数传入 (不硬编码租户/记录)。
     */
    OpeningApCorrectionResult correctMisroutedOpeningAp(
            String factoryId, OpeningApCorrectionRequest request, Long userId);
}
