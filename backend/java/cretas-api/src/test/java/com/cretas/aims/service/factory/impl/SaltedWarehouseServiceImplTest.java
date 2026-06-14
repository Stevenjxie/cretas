package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSaltedDeductionRequest;
import com.cretas.aims.dto.factory.SaltedReportDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.SaltedWarehouseDeduction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.factory.SaltedWarehouseDeductionRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SaltedWarehouseServiceImpl 单元测试 (SP7 F11).
 *
 * <p>覆盖 8 个场景:
 * <ol>
 *   <li>T1: 正常扣量 → 保存 SaltedDeduction + batch.usedQuantity 增加</li>
 *   <li>T2: 仓库非 SALTED → 422 WAREHOUSE_NOT_SALTED</li>
 *   <li>T3: 批次不在盐化仓 → 422 BATCH_NOT_IN_SALTED_WAREHOUSE</li>
 *   <li>T4: 批次库存不足 → 422 INSUFFICIENT_STOCK</li>
 *   <li>T5: 重复 orderNumber → 409 SALTED_DEDUCTION_DUPLICATE</li>
 *   <li>T6: 扣量后 remaining=0 → batch.status=USED_UP</li>
 *   <li>T7: 报表查询 — 单仓 SALTED → 返回 SaltedReportDTO</li>
 *   <li>T8: 报表非 SALTED 仓 → 422</li>
 * </ol>
 */
@DisplayName("SaltedWarehouseServiceImpl 单元测试 (SP7 F11)")
@ExtendWith(MockitoExtension.class)
class SaltedWarehouseServiceImplTest {

    @Mock private SaltedWarehouseDeductionRepository deductionRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private MaterialBatchRepository batchRepo;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    @InjectMocks private SaltedWarehouseServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String WH_SALTED_ID = "WH-SALT-001";
    private static final String BATCH_ID = "BATCH-001";
    private static final String ORDER_NO = "SD-20261019-001";
    private static final Long OPERATOR_ID = 99L;

    private FactoryWarehouse saltedWarehouse;
    private MaterialBatch availableBatch;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);

        saltedWarehouse = new FactoryWarehouse();
        saltedWarehouse.setId(WH_SALTED_ID);
        saltedWarehouse.setFactoryId(FACTORY_ID);
        saltedWarehouse.setType(FactoryWarehouse.WarehouseType.SALTED);
        saltedWarehouse.setName("盐化仓");
        saltedWarehouse.setCode("WH-SALT");

        availableBatch = new MaterialBatch();
        availableBatch.setId(BATCH_ID);
        availableBatch.setFactoryId(FACTORY_ID);
        availableBatch.setWarehouseId(WH_SALTED_ID);
        availableBatch.setBatchNumber("MB-20261019-001");
        availableBatch.setQuantityUnit("kg");
        availableBatch.setStatus(MaterialBatchStatus.AVAILABLE);
        availableBatch.setReceiptQuantity(new BigDecimal("100.000"));
        availableBatch.setUsedQuantity(BigDecimal.ZERO);
        availableBatch.setReservedQuantity(BigDecimal.ZERO);
    }

    // ── T1: 正常扣量 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1: 正常盐化扣量 → 保存记录 + batch.usedQuantity 增加")
    void createDeduction_happyPath_savesDeductionAndUpdatesBatch() {
        // Arrange
        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(saltedWarehouse));
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(availableBatch));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deductionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateSaltedDeductionRequest req = buildRequest("30.000", null);

        // Act
        SaltedWarehouseDeduction result = service.createDeduction(FACTORY_ID, req, OPERATOR_ID);

        // Assert — deduction saved with correct fields
        assertThat(result.getFactoryId()).isEqualTo(FACTORY_ID);
        assertThat(result.getWarehouseId()).isEqualTo(WH_SALTED_ID);
        assertThat(result.getQuantity()).isEqualByComparingTo("30");
        assertThat(result.getOrderNumber()).isEqualTo(ORDER_NO);

        // Assert — batch usedQuantity updated
        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batchRepo).save(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getUsedQuantity()).isEqualByComparingTo("30");
        verify(inventoryLowStockEventPublisher).publishIfLowStock(FACTORY_ID, availableBatch, "OUT");
    }

    // ── T2: 非 SALTED 仓 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("T2: 仓库类型非 SALTED → 422 WAREHOUSE_NOT_SALTED")
    void createDeduction_nonSaltedWarehouse_throws422() {
        FactoryWarehouse rawWarehouse = new FactoryWarehouse();
        rawWarehouse.setId(WH_SALTED_ID);
        rawWarehouse.setFactoryId(FACTORY_ID);
        rawWarehouse.setType(FactoryWarehouse.WarehouseType.RAW);
        rawWarehouse.setName("原料仓");

        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(rawWarehouse));

        CreateSaltedDeductionRequest req = buildRequest("10", null);

        assertThatThrownBy(() -> service.createDeduction(FACTORY_ID, req, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);

        verify(batchRepo, never()).save(any());
        verify(deductionRepo, never()).save(any());
    }

    // ── T3: 批次不在盐化仓 ────────────────────────────────────────────────────

    @Test
    @DisplayName("T3: 批次所在仓库与请求盐化仓不匹配 → 422 BATCH_NOT_IN_SALTED_WAREHOUSE")
    void createDeduction_batchNotInSaltedWarehouse_throws422() {
        availableBatch.setWarehouseId("OTHER-WAREHOUSE");

        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(saltedWarehouse));
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(availableBatch));

        CreateSaltedDeductionRequest req = buildRequest("10", null);

        assertThatThrownBy(() -> service.createDeduction(FACTORY_ID, req, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    // ── T4: 库存不足 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("T4: 扣减量 > 批次剩余库存 → 422 INSUFFICIENT_STOCK")
    void createDeduction_insufficientStock_throws422() {
        availableBatch.setReceiptQuantity(new BigDecimal("10.000"));
        availableBatch.setUsedQuantity(new BigDecimal("8.000")); // remaining = 2kg

        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(saltedWarehouse));
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(availableBatch));

        CreateSaltedDeductionRequest req = buildRequest("5", null); // 5kg > 2kg remaining

        assertThatThrownBy(() -> service.createDeduction(FACTORY_ID, req, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);

        verify(batchRepo, never()).save(any());
    }

    // ── T5: 重复 orderNumber ──────────────────────────────────────────────────

    @Test
    @DisplayName("T5: 同工厂+orderNumber 已存在 → 409 SALTED_DEDUCTION_DUPLICATE")
    void createDeduction_duplicateOrderNumber_throws409() {
        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(true);

        CreateSaltedDeductionRequest req = buildRequest("10", null);

        assertThatThrownBy(() -> service.createDeduction(FACTORY_ID, req, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(409);

        verify(warehouseRepo, never()).findByIdAndFactoryIdAndDeletedAtIsNull(any(), any());
    }

    // ── T6: 扣完后 USED_UP ───────────────────────────────────────────────────

    @Test
    @DisplayName("T6: 扣量后 remaining=0 → batch.status 自动变 USED_UP")
    void createDeduction_depletesStock_marksBatchUsedUp() {
        availableBatch.setReceiptQuantity(new BigDecimal("30.000"));
        availableBatch.setUsedQuantity(BigDecimal.ZERO);

        when(deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(FACTORY_ID, ORDER_NO))
                .thenReturn(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(saltedWarehouse));
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(availableBatch));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deductionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateSaltedDeductionRequest req = buildRequest("30", null); // exact amount

        service.createDeduction(FACTORY_ID, req, OPERATOR_ID);

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(batchRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MaterialBatchStatus.USED_UP);
    }

    // ── T7: 报表查询 (正常) ──────────────────────────────────────────────────

    @Test
    @DisplayName("T7: 盐化报表查询 — 单仓 SALTED → 返回 SaltedReportDTO")
    void getReport_saltedWarehouse_returnsReportDTO() {
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(saltedWarehouse));

        SaltedWarehouseDeduction d = SaltedWarehouseDeduction.builder()
                .id("D-001")
                .factoryId(FACTORY_ID)
                .warehouseId(WH_SALTED_ID)
                .materialBatchId(BATCH_ID)
                .orderNumber(ORDER_NO)
                .deductionDate(LocalDate.of(2026, 10, 19))
                .quantity(new BigDecimal("30.000"))
                .quantityUnit("kg")
                .unitPrice(new BigDecimal("15.0000"))
                .build();
        d.computeTotal();

        when(deductionRepo.findByWarehouseAndDateRange(
                eq(FACTORY_ID), eq(WH_SALTED_ID),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(d));

        when(batchRepo.findById(BATCH_ID)).thenReturn(Optional.of(availableBatch));

        SaltedReportDTO report = service.getReport(
                FACTORY_ID, WH_SALTED_ID,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(report.getLineCount()).isEqualTo(1);
        assertThat(report.getTotalQuantity()).isEqualByComparingTo("30");
        assertThat(report.getTotalAmount()).isEqualByComparingTo("450.00");
        assertThat(report.getLines().get(0).getOrderNumber()).isEqualTo(ORDER_NO);
    }

    // ── T8: 报表非 SALTED 仓 ─────────────────────────────────────────────────

    @Test
    @DisplayName("T8: 报表查询传非 SALTED 仓 → 422")
    void getReport_nonSaltedWarehouse_throws422() {
        FactoryWarehouse rawWh = new FactoryWarehouse();
        rawWh.setId(WH_SALTED_ID);
        rawWh.setFactoryId(FACTORY_ID);
        rawWh.setType(FactoryWarehouse.WarehouseType.RAW);
        rawWh.setName("原料仓");

        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_SALTED_ID, FACTORY_ID))
                .thenReturn(Optional.of(rawWh));

        assertThatThrownBy(() -> service.getReport(
                FACTORY_ID, WH_SALTED_ID,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CreateSaltedDeductionRequest buildRequest(String qty, BigDecimal unitPrice) {
        CreateSaltedDeductionRequest req = new CreateSaltedDeductionRequest();
        req.setWarehouseId(WH_SALTED_ID);
        req.setMaterialBatchId(BATCH_ID);
        req.setOrderNumber(ORDER_NO);
        req.setDeductionDate(LocalDate.of(2026, 10, 19));
        req.setQuantity(new BigDecimal(qty));
        req.setUnitPrice(unitPrice);
        req.setCustomerName("测试外商");
        return req;
    }
}
