package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * 🔴 关单退料漏判 DEPLETED → 库存永久冻结 回归测试.
 *
 * <p>{@code restoreBatchUsedQuantity} 恢复 usedQuantity 后, 若批次曾被打满耗尽终态
 * (USED_UP 或 DEPLETED) 且退料后现存 (currentQuantity = receipt-used-reserved) > 0,
 * 必须翻回 AVAILABLE, 否则 FEFO/FIFO 查询硬编码 status='AVAILABLE' 永远捞不到,
 * 批次库存被永久冻结不可用 (2026-07 F006 事故: 10 批次冻结 67.40kg)。
 *
 * <p>修复前: 只判 USED_UP, 漏了 DEPLETED (DEPLETED 语义="预留+剩余=0"的耗尽终态,
 * 与 USED_UP 同为耗尽终态, 见 ReportReversalServiceImpl.restoreMaterialBatchConsumption
 * 和 MaterialBatchServiceImpl.releaseBatchReservation 的同 pattern)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryMaterialRequisitionServiceImpl.restoreBatchUsedQuantity — DEPLETED/USED_UP 退料翻转 AVAILABLE")
class FactoryMaterialRequisitionRestoreBatchStatusTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private FactoryMaterialRequisitionRepository repository;
    @Mock
    private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private BomRecipeItemRepository bomItemRepository;
    @Mock
    private TransferService transferService;
    @Mock
    private FactoryWarehouseRepository warehouseRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private ProductionMaterialReturnRepository productionMaterialReturnRepository;
    @Mock
    private WarehouseResolver warehouseResolver;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    private MaterialBatch newBatch(MaterialBatchStatus status, String receipt, String used, String reserved) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("batch-001");
        batch.setFactoryId(FACTORY_ID);
        batch.setStatus(status);
        batch.setReceiptQuantity(new BigDecimal(receipt));
        batch.setUsedQuantity(new BigDecimal(used));
        batch.setReservedQuantity(new BigDecimal(reserved));
        return batch;
    }

    private FactoryMaterialRequisitionItem newItem() {
        FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
        item.setId("item-001");
        item.setMaterialName("测试原料");
        return item;
    }

    private void invokeRestore(MaterialBatch batch, FactoryMaterialRequisitionItem item, String returnQty) {
        ReflectionTestUtils.invokeMethod(service, "restoreBatchUsedQuantity",
                FACTORY_ID, batch, item, new BigDecimal(returnQty));
    }

    @Test
    @DisplayName("DEPLETED 批次退料后现存 > 0 → 翻回 AVAILABLE (回归修复点)")
    void depletedBatchWithResidualAfterReturn_flipsToAvailable() {
        // receipt=100, used=100, reserved=0 → currentQuantity=0 → DEPLETED (耗尽)
        MaterialBatch batch = newBatch(MaterialBatchStatus.DEPLETED, "100", "100", "0");

        // 退料 30 → used=70 → currentQuantity=100-70-0=30 > 0 → 必须翻回 AVAILABLE
        invokeRestore(batch, newItem(), "30");

        assertEquals(new BigDecimal("70"), batch.getUsedQuantity());
        assertEquals(MaterialBatchStatus.AVAILABLE, batch.getStatus(),
                "DEPLETED 批次退料后现存 > 0 必须翻回 AVAILABLE, 否则 FEFO/FIFO 查询捞不到 (库存冻结)");
        verify(materialBatchRepository).save(batch);
    }

    @Test
    @DisplayName("USED_UP 批次退料后现存 > 0 → 翻回 AVAILABLE (既有 pattern 不回归)")
    void usedUpBatchWithResidualAfterReturn_flipsToAvailable() {
        MaterialBatch batch = newBatch(MaterialBatchStatus.USED_UP, "100", "100", "0");

        invokeRestore(batch, newItem(), "40");

        assertEquals(new BigDecimal("60"), batch.getUsedQuantity());
        assertEquals(MaterialBatchStatus.AVAILABLE, batch.getStatus());
        verify(materialBatchRepository).save(batch);
    }

    @Test
    @DisplayName("DEPLETED 批次 (由 reserved 占满导致耗尽) 退料后现存 > 0 → currentQuantity 正确计入 reserved 后翻转")
    void depletedBatchDueToReservedThenReturned_currentQuantityAccountsForReserved() {
        // receipt=100, used=70, reserved=30 → currentQuantity=100-70-30=0 → DEPLETED
        // (验证 currentQuantity 计算正确纳入 reserved, 不是只看 used)
        MaterialBatch batch = newBatch(MaterialBatchStatus.DEPLETED, "100", "70", "30");

        // 退料 20 → used=50 → currentQuantity=100-50-30=20 > 0 → 必须翻回 AVAILABLE
        invokeRestore(batch, newItem(), "20");

        assertEquals(new BigDecimal("50"), batch.getUsedQuantity());
        assertEquals(MaterialBatchStatus.AVAILABLE, batch.getStatus());
    }

    @Test
    @DisplayName("非耗尽终态 (如 FRESH) 批次退料 → 状态不受影响")
    void nonDepletedStatusBatch_statusUnaffected() {
        MaterialBatch batch = newBatch(MaterialBatchStatus.FRESH, "100", "40", "0");

        invokeRestore(batch, newItem(), "10");

        assertEquals(new BigDecimal("30"), batch.getUsedQuantity());
        assertEquals(MaterialBatchStatus.FRESH, batch.getStatus(),
                "非耗尽终态不应被本方法改动状态");
    }
}
