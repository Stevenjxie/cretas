package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.repository.MaterialBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * WastageReportVoucherGenerator 单元测试.
 *
 * <p>覆盖: 借 6602.01 管理费用-损耗 / 贷 1403 原材料 借贷平衡 = 报损价值 (数量×单价);
 * INVENTORY 辅助核算挂 materialBatchId; computeWastageValue honest-null (批次缺失/单价空 抛).
 */
@DisplayName("WastageReportVoucherGenerator 单元测试")
@ExtendWith(MockitoExtension.class)
class WastageReportVoucherGeneratorTest {

    @Mock private MaterialBatchRepository materialBatchRepo;

    private WastageReportVoucherGenerator generator;

    private static final String BATCH_ID = "BATCH-9";

    @BeforeEach
    void setUp() {
        generator = new WastageReportVoucherGenerator(materialBatchRepo);
    }

    private WastageReport report(BigDecimal qty) {
        WastageReport r = new WastageReport();
        r.setId("WR-1");
        r.setFactoryId("F006");
        r.setReportNo("WR-20260701-001");
        r.setTrackType(WastageReport.TrackType.WAREHOUSE);
        r.setMaterialBatchId(BATCH_ID);
        r.setWastageQty(qty);
        r.setWastageReason(WastageReport.WastageReason.EXPIRED);
        return r;
    }

    private MaterialBatch batch(BigDecimal unitPrice) {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setUnitPrice(unitPrice);
        return b;
    }

    @Test
    @DisplayName("getType/supports: EXPENSE + WASTAGE_REPORT")
    void metadata() {
        assertThat(generator.getType()).isEqualTo(VoucherType.EXPENSE);
        assertThat(generator.supports("WASTAGE_REPORT")).isTrue();
        assertThat(generator.supports("WASTAGE_RECORD")).isFalse();
    }

    @Test
    @DisplayName("buildEntries: 10 × 2.50 = 25.00 → 借 6602.01 25 / 贷 1403 25, 借贷平衡, 1403 挂 INVENTORY 辅助核算")
    void buildEntries_balanced() {
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch(new BigDecimal("2.50"))));

        List<VoucherEntry> entries = generator.buildEntries(report(new BigDecimal("10.0000")));

        assertThat(entries).hasSize(2);
        VoucherEntry debit = entries.get(0);
        VoucherEntry credit = entries.get(1);
        assertThat(debit.getSubjectCode()).isEqualTo("6602.01");
        assertThat(debit.getDebit()).isEqualByComparingTo("25.00");
        assertThat(debit.getCredit()).isEqualByComparingTo("0");
        assertThat(credit.getSubjectCode()).isEqualTo("1403");
        assertThat(credit.getCredit()).isEqualByComparingTo("25.00");
        assertThat(credit.getDebit()).isEqualByComparingTo("0");
        // INVENTORY 辅助核算挂 materialBatchId
        assertThat(credit.getAuxiliaryType()).isEqualTo(AuxiliaryType.INVENTORY);
        assertThat(credit.getAuxiliaryEntityId()).isEqualTo(BATCH_ID);
        // 借贷平衡
        assertThat(debit.getDebit()).isEqualByComparingTo(credit.getCredit());
    }

    @Test
    @DisplayName("computeWastageValue: HALF_UP scale-2 (3 × 3.335 = 10.005 → 10.01)")
    void computeWastageValue_halfUp() {
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch(new BigDecimal("3.335"))));
        BigDecimal value = generator.computeWastageValue(report(new BigDecimal("3")));
        assertThat(value).isEqualByComparingTo("10.01");
    }

    @Test
    @DisplayName("computeWastageValue: 批次不存在 → IllegalStateException (honest, 不静默 0)")
    void computeWastageValue_batchMissing_throws() {
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> generator.computeWastageValue(report(new BigDecimal("10"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("批次不存在");
    }

    @Test
    @DisplayName("computeWastageValue: 批次单价为空 → IllegalStateException (honest-null 由审批流先行拦截)")
    void computeWastageValue_nullUnitPrice_throws() {
        when(materialBatchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch(null)));
        assertThatThrownBy(() -> generator.computeWastageValue(report(new BigDecimal("10"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("单价为空");
    }
}
