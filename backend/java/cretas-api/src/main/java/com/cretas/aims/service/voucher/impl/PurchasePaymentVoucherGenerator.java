package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.finance.VoucherSubjectMappingService;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 采购付款凭证 generator (SP11 价税分离).
 *
 * <p>含税订单 (tax_amount > 0):
 * <pre>
 * 借: 1405 库存商品                         = 未税净额
 * 借: 2221.02 应交税费-应交增值税-进项税额  = 进项税额
 * 贷: 2202 应付账款                         = 含税总额 (未税 + 进项税)   [供应商辅助核算]
 * </pre>
 *
 * <p>零税订单 (tax_amount = 0/null) → 退化两行 (向后兼容, 字节与历史一致):
 * <pre>
 * 借: 1405 库存商品       = 未税净额
 * 贷: 2202 应付账款       = 未税净额   [供应商辅助核算]
 * </pre>
 *
 * <p>口径 (SP11 spec §3.1): {@code total_amount} 未税净额, {@code tax_amount} 进项税额单列,
 * 含税 = 二者相加。<b>应付用加法 (未税 + 税)</b> 保证借贷精确平衡, 进项税不进库存成本 (可抵扣)。
 *
 * <p>科目代码 2221.02 (进项税) 与主科目 (1405/2202) 同为 hardcode 默认值, 客户实际金蝶账套
 * 可通过 VoucherTemplate (template-first 路径) 覆盖。
 *
 * 业务: PurchaseOrder 审批通过 → 财务记 "原材料入库(未税) + 进项税 + 欠供应商款(含税)".
 */
@Component
public class PurchasePaymentVoucherGenerator extends AbstractVoucherGenerator<PurchaseOrder> {

    public static final String BUSINESS_TYPE = "PURCHASE_ORDER";

    /** 应交税费-应交增值税-进项税额 (默认科目, 客户账套可覆盖) */
    static final String INPUT_VAT_CODE = "2221.02";
    static final String INPUT_VAT_NAME = "应交税费-应交增值税-进项税额";

    /** 默认借方科目 (库存商品/原材料) — 无 settlement 映射时 fallback. */
    static final String DEFAULT_DEBIT_CODE = "1405";
    static final String DEFAULT_DEBIT_NAME = "库存商品";
    /** 默认贷方科目 (应付账款) — 无 settlement 映射时 fallback. */
    static final String DEFAULT_CREDIT_CODE = "2202";
    static final String DEFAULT_CREDIT_NAME = "应付账款";

    /**
     * SP11 settlement→subject mapping (#754): voucher_subject_mappings 业务类型 key.
     * 注意: 与 voucher 的 sourceBusinessType ("PURCHASE_ORDER") 不同 — mapping 表
     * 种子数据 (V20261011_20) 用简写 "PURCHASE".
     */
    static final String MAPPING_BUSINESS_TYPE = "PURCHASE";

    /**
     * SP11 (#754): 结算方式→科目映射服务. Optional 注入 (required=false) — 现有单元测试
     * (VoucherGeneratorsTest) 不构造此 bean 时 null → fall back 到硬编码 1405/2202, 行为
     * 字节级不变 (向后兼容). PurchaseOrder.settlementType 为 null 时同样 fall back.
     */
    @Autowired(required = false)
    private VoucherSubjectMappingService subjectMappingService;

    @Override
    public VoucherType getType() {
        return VoucherType.PURCHASE_PAYMENT;
    }

    @Override
    public boolean supports(String businessType) {
        return BUSINESS_TYPE.equals(businessType);
    }

    @Override
    protected String extractSourceBusinessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String extractSourceBusinessId(PurchaseOrder order) {
        return order.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(PurchaseOrder order) {
        return order.getOrderDate();
    }

    @Override
    protected String extractDescription(PurchaseOrder order) {
        return "采购订单 " + order.getOrderNumber();
    }

    /**
     * 无 factoryId 路径 (向后兼容 / 单测) — 用硬编码默认科目 1405/2202.
     */
    @Override
    public List<VoucherEntry> buildEntries(PurchaseOrder order) {
        return buildEntries(DEFAULT_DEBIT_CODE, DEFAULT_DEBIT_NAME,
                DEFAULT_CREDIT_CODE, DEFAULT_CREDIT_NAME, order);
    }

    /**
     * SP11 settlement→subject mapping (#754): factoryId-aware 路径.
     *
     * <p>按 {@code order.getSettlementType()} 查 {@code voucher_subject_mappings}:
     * <ul>
     *   <li>预付 (PREPAID) → 贷 1002 银行存款 (款已付, 不挂应付)</li>
     *   <li>现结 (IMMEDIATE) → 贷 1001 库存现金</li>
     *   <li>未到票 (NO_INVOICE) → 贷 2241 暂估应付款</li>
     *   <li>赊销/月结/账期 (CREDIT_FIRST/MONTHLY/CREDIT_PERIOD) → 贷 2202 应付账款</li>
     * </ul>
     *
     * <p>无映射服务 / settlementType 为 null / 查不到映射 → fall back 硬编码 1405/2202
     * (与历史行为字节一致). 借贷金额完全不受科目映射影响 → 借贷平衡不破坏.
     */
    @Override
    protected List<VoucherEntry> buildEntries(String factoryId, PurchaseOrder order) {
        String debitCode = DEFAULT_DEBIT_CODE;
        String debitName = DEFAULT_DEBIT_NAME;
        String creditCode = DEFAULT_CREDIT_CODE;
        String creditName = DEFAULT_CREDIT_NAME;

        SettlementType settlement = order.getSettlementType();
        if (subjectMappingService != null && factoryId != null && settlement != null) {
            Optional<VoucherSubjectMappingDTO> mappingOpt =
                    subjectMappingService.findMapping(factoryId, settlement, MAPPING_BUSINESS_TYPE);
            if (mappingOpt.isPresent()) {
                VoucherSubjectMappingDTO m = mappingOpt.get();
                // 仅在映射提供了科目代码时覆盖 (防御性: 空字段不覆盖默认值)
                if (m.getDebitSubjectCode() != null && !m.getDebitSubjectCode().isBlank()) {
                    debitCode = m.getDebitSubjectCode();
                    debitName = m.getDebitSubjectName() != null ? m.getDebitSubjectName() : debitName;
                }
                if (m.getCreditSubjectCode() != null && !m.getCreditSubjectCode().isBlank()) {
                    creditCode = m.getCreditSubjectCode();
                    creditName = m.getCreditSubjectName() != null ? m.getCreditSubjectName() : creditName;
                }
            }
        }
        return buildEntries(debitCode, debitName, creditCode, creditName, order);
    }

    /**
     * 共享分录构建 — 接收已解析的借/贷科目, 金额逻辑不变 (借贷恒平衡).
     */
    private List<VoucherEntry> buildEntries(String debitCode, String debitName,
            String creditCode, String creditName, PurchaseOrder order) {
        // SP11: total_amount = 未税净额, tax_amount = 进项税额 (单列)
        BigDecimal netAmount = nullToZero(order.getTotalAmount());       // 未税净额
        BigDecimal taxAmount = nullToZero(order.getTaxAmount());         // 进项税额
        // 含税应付 = 未税 + 税 (加法, 借贷精确平衡 — SP11 spec §3.1/§3.4)
        BigDecimal payableAmount = netAmount.add(taxAmount);
        String supplierId = order.getSupplierId();  // Sprint 5 F-2: 供应商辅助核算

        List<VoucherEntry> entries = new ArrayList<>(3);
        // 借方科目 (默认 1405 库存商品; settlement 映射可覆盖): 未税净额 (进项税不进成本)
        entries.add(debitEntry(1, debitCode, debitName, netAmount, "采购入库 " + order.getOrderNumber()));
        // 贷方科目 (默认 2202 应付账款; settlement 映射按预付/现结/赊销覆盖) 按供应商分账; 含税
        entries.add(creditEntryWithAuxiliary(2, creditCode, creditName, payableAmount, "供应商应付",
                supplierId != null ? AuxiliaryType.SUPPLIER : null, supplierId));
        // 进项税额单列 借方 (仅含税订单, 零税退化两行向后兼容)
        if (taxAmount.signum() > 0) {
            entries.add(debitEntry(3, INPUT_VAT_CODE, INPUT_VAT_NAME, taxAmount,
                    "进项税额 " + order.getOrderNumber()));
        }
        return entries;
    }

    /**
     * 🔴🔒 #1207: template 路径把 SUPPLIER 辅助核算附到应付款贷方行 (与 buildEntries 一致)。
     * 贷方 = 往来 (供应商应付/预付银行/现结现金), 前缀 2202 (默认应付); settlement 映射改了
     * 贷方科目码时, 回退到"贷方唯一行"仍命中往来行 —— 镜像硬编码路径把 aux 挂在 creditCode 上。
     * 库存/进项税在借方不挂供应商维度。
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, PurchaseOrder order) {
        String supplierId = order.getSupplierId();
        attachAuxiliary(entries, EntrySide.CREDIT, "2202",
                supplierId != null ? AuxiliaryType.SUPPLIER : null, supplierId);
    }
}
