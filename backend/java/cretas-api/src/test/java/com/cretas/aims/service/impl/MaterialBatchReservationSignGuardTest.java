package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预留/释放数量必须为正。
 *
 * 背景: 这两条路径原来只检查「够不够」({@code remaining >= quantity} /
 * {@code reserved >= quantity}) —— 负数天然满足, 于是:
 *   • reserve(-5)  → reserved += -5, 把 reserved 推成负值;
 *   • release(-5)  → reserved -= -5, 变成用释放接口做预留。
 * 而 {@code currentQuantity = receipt − used − reserved}, **reserved 为负时可用量
 * 会大于实物量**。DB 的 ck_material_batch_no_overconsume (used+reserved ≤ receipt)
 * 对负 reserved 只会更容易满足, 兜不住。DTO 上本来写了 {@code @DecimalMin("0.01")},
 * 但 controller 的 {@code @RequestBody} 没有 {@code @Valid}, 那条约束从未执行过。
 */
@DisplayName("预留/释放的数量符号闸")
class MaterialBatchReservationSignGuardTest {

    private MaterialBatchRepository batchRepository;
    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(MaterialBatchRepository.class);
        service = new MaterialBatchServiceImpl(
                batchRepository,
                mock(MaterialBatchAdjustmentRepository.class),
                mock(RawMaterialTypeRepository.class),
                mock(MaterialBatchMapper.class),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));
        // 字段注入的依赖: 正向对照会一路走到 publishStockChangedEventIfApplicable,
        // 不打桩就是 NPE —— 而 NPE 恰好说明这条对照真的驱动到了实现深处, 不是空跑。
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher",
                mock(InventoryLowStockEventPublisher.class));
    }

    /** 收 100 / 已用 0 / 已预留 10 —— 一个完全正常的批次。 */
    private MaterialBatch batch() {
        MaterialBatch b = new MaterialBatch();
        b.setId("B-1");
        b.setFactoryId("F006");
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(new BigDecimal("10"));
        when(batchRepository.findById(anyString())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    @DisplayName("reserve 负数 → 400, reserved 一动不动")
    void negativeReserveIsRejected() {
        MaterialBatch b = batch();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.reserveBatchMaterial("F006", "B-1", new BigDecimal("-5"), "P-1"));

        assertEquals(400, e.getCode());
        assertEquals(new BigDecimal("10"), b.getReservedQuantity(), "预留量不许被负数改动");
        verify(batchRepository, never()).save(b);
    }

    @Test
    @DisplayName("reserve 0 → 400 (0 不是有效预留)")
    void zeroReserveIsRejected() {
        batch();
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.reserveBatchMaterial("F006", "B-1", BigDecimal.ZERO, "P-1")).getCode());
    }

    @Test
    @DisplayName("release 负数 → 400, 不能反向变成预留")
    void negativeReleaseIsRejected() {
        MaterialBatch b = batch();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.releaseBatchReservation("F006", "B-1", new BigDecimal("-5"), "P-1"));

        assertEquals(400, e.getCode());
        assertEquals(new BigDecimal("10"), b.getReservedQuantity(), "释放接口不许把预留量加上去");
        verify(batchRepository, never()).save(b);
    }

    @Test
    @DisplayName("对照: 正数仍然照常走 —— 闸没有把正常路径一起挡掉")
    void positiveQuantitiesStillWork() {
        MaterialBatch b = batch();

        service.reserveBatchMaterial("F006", "B-1", new BigDecimal("5"), "P-1");
        assertEquals(0, new BigDecimal("15").compareTo(b.getReservedQuantity()), "10 + 5 = 15");

        service.releaseBatchReservation("F006", "B-1", new BigDecimal("5"), "P-1");
        assertEquals(0, new BigDecimal("10").compareTo(b.getReservedQuantity()), "15 − 5 = 10");
    }

    @Test
    @DisplayName("这就是被挡住的那个后果: 负 reserved 会让可用量超过实物量")
    void negativeReservedWouldInflateAvailableAbovePhysical() {
        MaterialBatch b = new MaterialBatch();
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(new BigDecimal("-5"));   // reserve(-5) 从 0 出发会落到这里

        assertTrue(b.getCurrentQuantity().compareTo(b.getPhysicalQuantity()) > 0,
                "可用量 105 > 实物量 100 —— 正是上面几道闸要防的形态");
    }
}
