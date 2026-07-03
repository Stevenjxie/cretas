package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 报损单 (SP7 双轨报损 {@link WastageReport}) 财务凭证 generator.
 *
 * <pre>
 * 借: 6602.01 管理费用-损耗                  报损价值 = 报损数量 × 批次单价
 * 贷: 1403   原材料 — 辅助核算 INVENTORY=materialBatchId
 * </pre>
 *
 * <p>业务: WastageReport 审批通过 (APPLIED) → 财务记 "损耗费用 + 原材料库存减少"。
 * 与 {@link ExpenseVoucherGenerator}(餐饮 WastageRecord) 和半成品盘点盘亏
 * (SemiFinishedStocktakeServiceImpl 借 6602.01 / 贷 1405) 同一"损耗=费用"会计模型
 * (张权客户约定)。区别: 报损单扣减的是<b>原料批次</b> (MaterialBatch), 故贷方用
 * <b>1403 原材料</b> (而非 1405 库存商品=产成品) — 科目更贴合 raw-material 库存性质。
 *
 * <p><b>计价来源</b>: 报损价值需批次单价 (batch.unitPrice), WastageReport 实体本身不带金额,
 * 故 generator 注入 {@link MaterialBatchRepository} 按 materialBatchId 反查 unitPrice。
 *
 * <p><b>honest-null 边界</b>: buildEntries 遇批次缺失 / unitPrice=null 会抛
 * {@link IllegalStateException}(不静默产 0 金额空凭证 — 违反"禁止降级处理"红线)。
 * 审批流 (WastageReportServiceImpl.approve) 在调用本 generator 前<b>先行</b>判定
 * unitPrice 是否可计价: 不可计价 → 只扣库存、不过账凭证 + 记 honest-null 日志
 * (库存已实扣, 但缺成本无法产平衡凭证)。因此本方法被审批流调用时 unitPrice 恒非空。
 *
 * <p><b>与其他 7 个 generator 一致</b>: 通过 @Component 自动注册进
 * {@link com.cretas.aims.service.voucher.VoucherGeneratorRegistry},
 * supports("WASTAGE_REPORT")。审批流刻意用 {@code voucherService.createManual}
 * (POSTED + 与库存扣减同一 @Transactional + 幂等) 持久化本 generator 产出的分录,
 * 镜像半成品盘点差异过账 (盘点/报损都是"审批即生效即入账"的库存校准类事件)。
 */
@Component
public class WastageReportVoucherGenerator extends AbstractVoucherGenerator<WastageReport> {

    public static final String BUSINESS_TYPE = "WASTAGE_REPORT";

    /** 借方: 管理费用-损耗 (镜像盘亏 / 餐饮报损损耗科目)。 */
    public static final String SUBJECT_LOSS_CODE = "6602.01";
    public static final String SUBJECT_LOSS_NAME = "管理费用-损耗";
    /** 贷方: 原材料 (报损扣减的是原料批次, 用 1403 而非 1405 库存商品)。 */
    public static final String SUBJECT_MATERIAL_CODE = "1403";
    public static final String SUBJECT_MATERIAL_NAME = "原材料";

    private final MaterialBatchRepository materialBatchRepo;

    @Autowired
    public WastageReportVoucherGenerator(MaterialBatchRepository materialBatchRepo) {
        this.materialBatchRepo = materialBatchRepo;
    }

    @Override
    public VoucherType getType() {
        return VoucherType.EXPENSE;
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
    protected String extractSourceBusinessId(WastageReport w) {
        return w.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(WastageReport w) {
        // 报损单本身无独立业务日期; 用生效时间 → 审批时间 → 当天 (审批即入账)。
        if (w.getAppliedAt() != null) return w.getAppliedAt().toLocalDate();
        if (w.getApprovedAt() != null) return w.getApprovedAt().toLocalDate();
        return LocalDate.now();
    }

    @Override
    protected String extractDescription(WastageReport w) {
        return "报损单 " + w.getReportNo() + " (" + w.getWastageReason() + ")";
    }

    /**
     * 计算报损价值 = 报损数量 × 批次单价 (scale-2, HALF_UP)。
     *
     * @return 正的报损价值; 无法计价 (批次缺失 / unitPrice=null) 抛 {@link IllegalStateException}
     */
    public BigDecimal computeWastageValue(WastageReport w) {
        MaterialBatch batch = materialBatchRepo.findById(w.getMaterialBatchId())
                .orElseThrow(() -> new IllegalStateException(
                        "报损单计价失败: 批次不存在 " + w.getMaterialBatchId()));
        BigDecimal unitPrice = batch.getUnitPrice();
        if (unitPrice == null) {
            throw new IllegalStateException(
                    "报损单计价失败: 批次单价为空 (honest-null 应由审批流先行拦截) batchId="
                            + w.getMaterialBatchId());
        }
        BigDecimal qty = nullToZero(w.getWastageQty());
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public List<VoucherEntry> buildEntries(WastageReport w) {
        BigDecimal value = computeWastageValue(w);
        String batchRef = w.getMaterialBatchId();
        return List.of(
                debitEntry(1, SUBJECT_LOSS_CODE, SUBJECT_LOSS_NAME, value,
                        "报损损耗 " + w.getReportNo()),
                // 原材料库存按批次分账 (INVENTORY 辅助核算 = materialBatchId)
                creditEntryWithAuxiliary(2, SUBJECT_MATERIAL_CODE, SUBJECT_MATERIAL_NAME, value,
                        "原材料减少 (" + w.getWastageReason() + ")",
                        batchRef != null && !batchRef.isBlank() ? AuxiliaryType.INVENTORY : null,
                        batchRef != null && !batchRef.isBlank() ? batchRef : null)
        );
    }
}
