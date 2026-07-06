package com.cretas.aims.entity;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔒🔒 MaterialBatch.assertConsumptionInvariant() 单测 —— 库存超扣兜底守卫。
 *
 * <p>不变式: {@code usedQuantity + reservedQuantity ≤ receiptQuantity}。这是纯内存后置校验
 * (mutation 之后调用), 误伤-proof: 任何合法态必满足, 唯有真超扣才 loud-fail 409 BATCH_OVER_CONSUMED。
 *
 * <p>复现根因: 逐工序延迟扣减在 #1204 守卫补上前, 单条消耗即超收货量 (F006 三批: 13.01→20 / 0.54→1 /
 * 1.25→5.39) 落库成负库存。本守卫在每个增加 used/reserved 的写入点 save 前拦截, 阻止再发生。
 */
class MaterialBatchConsumptionInvariantTest {

    private MaterialBatch batch(String receipt, String used, String reserved) {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber("MB-TEST-0001");
        mb.setReceiptQuantity(new BigDecimal(receipt));
        mb.setUsedQuantity(new BigDecimal(used));
        mb.setReservedQuantity(new BigDecimal(reserved));
        return mb;
    }

    // ── 真超扣 → loud-fail (复现 3 个 F006 违规场景) ──

    @Test
    void singleConsumptionExceedsReceipt_throws() {
        // 13.01kg 批次被单次消耗 20kg → used(20)+reserved(0) > receipt(13.01)。
        MaterialBatch mb = batch("13.01", "20.00", "0");
        assertThatThrownBy(mb::assertConsumptionInvariant)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("BATCH_OVER_CONSUMED");
                })
                .hasMessageContaining("MB-TEST-0001")
                .hasMessageContaining("超");
    }

    @Test
    void usedPlusReservedExceedsReceipt_throws() {
        // used 单独不超, 但 used+reserved 超 → 必须拦 (guard 用 committed=used+reserved, 非只看 used)。
        MaterialBatch mb = batch("10", "7", "5"); // 12 > 10
        assertThatThrownBy(mb::assertConsumptionInvariant)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reservationExceedsReceipt_throws() {
        MaterialBatch mb = batch("5", "0", "6");
        assertThatThrownBy(mb::assertConsumptionInvariant)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "BATCH_OVER_CONSUMED");
    }

    // ── 合法态 → 放行 (误伤-proof: 边界 + 反转都不误伤) ──

    @Test
    void usedEqualsReceipt_fullyConsumed_ok() {
        // 用光批次是合法态 (used==receipt), CHECK 是 ≤ 不是 < → 不得误伤。
        MaterialBatch mb = batch("13.01", "13.01", "0");
        assertThatCode(mb::assertConsumptionInvariant).doesNotThrowAnyException();
    }

    @Test
    void usedPlusReservedEqualsReceipt_ok() {
        MaterialBatch mb = batch("10", "6", "4"); // 恰好 10
        assertThatCode(mb::assertConsumptionInvariant).doesNotThrowAnyException();
    }

    @Test
    void partialConsumption_ok() {
        MaterialBatch mb = batch("100", "30", "20");
        assertThatCode(mb::assertConsumptionInvariant).doesNotThrowAnyException();
    }

    @Test
    void reversalReducesUsed_ok() {
        // 撤销/退料使 used 下降 → 不变式天然满足, 守卫不得干扰反转流。
        MaterialBatch mb = batch("50", "10", "0");
        assertThatCode(mb::assertConsumptionInvariant).doesNotThrowAnyException();
    }

    @Test
    void nullUsedAndReserved_treatedAsZero_ok() {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber("MB-TEST-NULL");
        mb.setReceiptQuantity(new BigDecimal("10"));
        // used / reserved 为 null → 视作 0, receipt≥0 → 放行 (无 NPE)。
        assertThatCode(mb::assertConsumptionInvariant).doesNotThrowAnyException();
    }
}
