package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 气调货入库 标称数 vs 实收数 (六扇门转录 13:01-13:48 "一托36放37, 对方划单").
 *
 * <p>覆盖:
 * <ul>
 *   <li>派生差异字段 getInboundDiscrepancy() (实收 − 标称)</li>
 *   <li>标称≠实收 + 未划单确认 → 422 拒绝 (fool-proof Rule 1)</li>
 *   <li>标称≠实收 + 已划单确认 → 放行, 库存按实收数</li>
 *   <li>标称==实收 → 放行 (无差异不要求确认)</li>
 *   <li>无标称数 (普通入库回归) → 放行, 派生差异为 null</li>
 * </ul>
 */
@DisplayName("气调货入库 标称vs实收")
class FinishedGoodsGasFlushInboundTest {

    /**
     * 复刻 SalesController.createOpeningFinishedGoods 的气调防呆校验块.
     * (controller 层直接做, 无需 Spring context — 与 FinishedGoodsOpeningValidationTest 同 pattern)
     */
    private void gasFlushGuard(BigDecimal nominal, BigDecimal actual, Boolean confirmed) {
        boolean hasDiscrepancy = nominal != null && nominal.compareTo(actual) != 0;
        if (hasDiscrepancy && !Boolean.TRUE.equals(confirmed)) {
            BigDecimal diff = actual.subtract(nominal);
            throw new BusinessException(422,
                    "气调货标称数 " + nominal.stripTrailingZeros().toPlainString()
                            + " 与实收数 " + actual.stripTrailingZeros().toPlainString()
                            + " 不一致 (差 " + diff.stripTrailingZeros().toPlainString() + "), 需对方划单确认后入库")
                    .withHint("请勾选「对方划单已确认」(counterpartyConfirmed=true) 再提交; 库存按实收数入库");
        }
    }

    @Test
    @DisplayName("派生差异: 标称36 实收37 → 差异 1")
    void derivedDiscrepancy_nominal36_actual37() {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setNominalQuantity(new BigDecimal("36"));
        b.setProducedQuantity(new BigDecimal("37"));
        assertThat(b.getInboundDiscrepancy()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("派生差异: 无标称数 → null (普通入库无差异概念)")
    void derivedDiscrepancy_noNominal_null() {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setProducedQuantity(new BigDecimal("37"));
        assertThat(b.getInboundDiscrepancy()).isNull();
    }

    @Test
    @DisplayName("标称≠实收 + 未划单确认 → 422 拒绝")
    void discrepancyWithoutConfirm_422() {
        assertThatThrownBy(() -> gasFlushGuard(new BigDecimal("36"), new BigDecimal("37"), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(422);
                    assertThat(be.getMessage()).contains("36").contains("37").contains("差 1");
                    assertThat(be.getActionHint()).contains("对方划单");
                });
    }

    @Test
    @DisplayName("标称≠实收 + counterpartyConfirmed=false → 仍 422")
    void discrepancyConfirmedFalse_422() {
        assertThatThrownBy(() -> gasFlushGuard(new BigDecimal("36"), new BigDecimal("37"), Boolean.FALSE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("标称≠实收 + 已划单确认 → 放行")
    void discrepancyConfirmed_passes() {
        assertThatCode(() -> gasFlushGuard(new BigDecimal("36"), new BigDecimal("37"), Boolean.TRUE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("标称==实收 → 放行 (无差异不要求确认)")
    void noDiscrepancy_passes() {
        assertThatCode(() -> gasFlushGuard(new BigDecimal("36"), new BigDecimal("36"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("普通入库回归: 无标称数 → 放行")
    void plainInbound_noNominal_passes() {
        assertThatCode(() -> gasFlushGuard(null, new BigDecimal("37"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("库存按实收数计量: producedQuantity=实收37 不被标称覆盖")
    void stockUsesActualNotNominal() {
        // 复刻 controller 映射: producedQuantity 取实收, nominalQuantity 仅留痕
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        BigDecimal actual = new BigDecimal("37");
        BigDecimal nominal = new BigDecimal("36");
        b.setProducedQuantity(actual);       // 库存量 = 实收
        b.setNominalQuantity(nominal);       // 标称仅留痕
        b.setCounterpartyConfirmed(Boolean.TRUE);
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("37");
        assertThat(b.getNominalQuantity()).isEqualByComparingTo("36");
        assertThat(b.getInboundDiscrepancy()).isEqualByComparingTo("1");
    }
}
