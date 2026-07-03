package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.finance.OpeningApCorrectionRequest;
import com.cretas.aims.dto.finance.OpeningApCorrectionResult;
import com.cretas.aims.dto.material.OpeningInventoryItem;
import com.cretas.aims.dto.material.OpeningInventoryRequest;
import com.cretas.aims.dto.material.OpeningInventoryResult;
import com.cretas.aims.entity.MaterialBatch;

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
     * 为「盘点 OPENING 期初建账」的 create-from-zero 建一个<b>空壳</b>期初批次
     * ({@code receiptQuantity=0}, sourceDocType=OPENING, <b>不过凭证 / 不挂应付</b>)。
     *
     * <p>期初建账已统一并入盘点 (Steve 架构决策 2026-07): 期初/盘盈/盘亏都是「库存调整 + 过凭证」，
     * 统一在盘点模块一个入口。盘点 OPENING 导入时，未匹配现有库存的新物料行经此建壳（数量为 0），
     * 盘点快照 systemQty=0 → 回填实盘=期初数量 → 生效 apply 走盘盈机制把数量+价值补入，
     * 并计入盘点<b>同一张</b>期初凭证 (借1403原材料/贷4001实收资本)，避免与建壳双过账。
     *
     * <p>诚实-null：{@code item.unitPrice} 为空则批次单价为空，生效时排除出凭证金额（数量仍调整）。
     * 由调用方 (盘点 confirm) 的事务包裹（REQUIRED 传播）：盘点创建失败则建壳一并回滚。
     *
     * @param batchKey 建壳来源单据键 (存 source_doc_id，供追溯；≤64)
     * @return 已保存的空壳 MaterialBatch（供盘点快照 + 回填实盘）
     */
    MaterialBatch createOpeningBatchShell(String factoryId, OpeningInventoryItem item, String batchKey, Long userId);

    /**
     * 修正"误走采购入库建账"产生的幽灵应付: 红冲指定应付挂账 + 补正确的期初凭证 (借 1403 / 贷 4001),
     * <b>库存数量不动</b>。管理员专用、幂等、留审计痕迹。要修正哪几笔由请求参数传入 (不硬编码租户/记录)。
     */
    OpeningApCorrectionResult correctMisroutedOpeningAp(
            String factoryId, OpeningApCorrectionRequest request, Long userId);
}
