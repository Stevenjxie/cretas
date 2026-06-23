package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-B1 回归: useBatchMaterial / consumeBatchMaterial 创建 MaterialConsumption 时
 * 必须补齐 NOT NULL 字段 (unitPrice / totalCost / recordedBy), 否则 INSERT 失败 (DB 500)。
 * recordedBy 取调用层线程进来的 operatorId (真实操作人, FK→users); operatorId 为 null
 * 时抛 401 (不写假 actor / 不撞 FK), 见 *RejectsNullOperator 用例。
 */
@ExtendWith(MockitoExtension.class)
class MaterialBatchServiceImplConsumptionFieldsTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(
                materialBatchRepository,
                materialBatchAdjustmentRepository,
                materialTypeRepository,
                materialBatchMapper,
                materialConsumptionRepository,
                productionPlanBatchUsageRepository,
                excelUtil,
                futurePlanMatchingService);
        InventoryLowStockEventPublisher lowStockEventPublisher = new InventoryLowStockEventPublisher(
                applicationEventPublisher,
                materialBatchRepository,
                materialTypeRepository,
                Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", lowStockEventPublisher);
    }

    @Test
    void useBatchMaterialPopulatesNotNullConsumptionFields() {
        MaterialBatch batch = batch("MB-1", "RM-1", "100", "0", "0", "8.50");
        when(materialBatchRepository.findByIdAndFactoryId("MB-1", "F006")).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchMapper.toDTO(any(MaterialBatch.class))).thenReturn(new MaterialBatchDTO());
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "200")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("90"));

        service.useBatchMaterial("F006", "MB-1", new BigDecimal("10"), "PP-1", 42L);

        MaterialConsumption saved = captureSavedConsumption();
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("8.50");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("85.00");
        assertThat(saved.getRecordedBy()).isEqualTo(42L);
    }

    @Test
    void useBatchMaterialDefaultsUnitPriceToZeroWhenBatchHasNoPrice() {
        MaterialBatch batch = batch("MB-2", "RM-1", "100", "0", "0", null);
        when(materialBatchRepository.findByIdAndFactoryId("MB-2", "F006")).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchMapper.toDTO(any(MaterialBatch.class))).thenReturn(new MaterialBatchDTO());
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "200")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("90"));

        service.useBatchMaterial("F006", "MB-2", new BigDecimal("10"), "PP-1", 42L);

        MaterialConsumption saved = captureSavedConsumption();
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("0");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("0");
        assertThat(saved.getRecordedBy()).isEqualTo(42L);
    }

    @Test
    void useBatchMaterialRejectsNonPositiveQuantity() {
        // edge-case 审计 2026-06-24: 领料量 <= 0 → 400 (否则负数绕过 remaining<qty 检查写坏账)
        MaterialBatch batch = batch("MB-NEG", "RM-1", "100", "0", "0", "8.50");
        when(materialBatchRepository.findByIdAndFactoryId("MB-NEG", "F006")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.useBatchMaterial("F006", "MB-NEG", new BigDecimal("-5"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于0");
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void consumeBatchMaterialRejectsNonPositiveQuantity() {
        // reviewer ISSUE-3: consumeBatchMaterial 同款负数守卫 (负数会反向写坏 used/reserved)
        MaterialBatch batch = batch("MB-NEG2", "RM-1", "100", "0", "50", "8.50");
        when(materialBatchRepository.findById("MB-NEG2")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.consumeBatchMaterial("F006", "MB-NEG2", new BigDecimal("-5"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于0");
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void consumeBatchMaterialPopulatesNotNullConsumptionFields() {
        MaterialBatch batch = batch("MB-3", "RM-1", "100", "0", "50", "8.50");
        when(materialBatchRepository.findById("MB-3")).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialTypeRepository.findById("RM-1")).thenReturn(Optional.of(materialType("RM-1", "Pork", "kg", "200")));
        when(materialBatchRepository.sumAvailableQuantityByMaterialType("F006", "RM-1"))
                .thenReturn(new BigDecimal("40"));

        service.consumeBatchMaterial("F006", "MB-3", new BigDecimal("10"), "PP-1", 42L);

        MaterialConsumption saved = captureSavedConsumption();
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("8.50");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("85.00");
        assertThat(saved.getRecordedBy()).isEqualTo(42L);
    }

    @Test
    void useBatchMaterialRejectsNullOperatorWhenRecordingConsumption() {
        MaterialBatch batch = batch("MB-4", "RM-1", "100", "0", "0", "8.50");
        when(materialBatchRepository.findByIdAndFactoryId("MB-4", "F006")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.useBatchMaterial("F006", "MB-4", new BigDecimal("10"), "PP-1", null))
                .isInstanceOf(BusinessException.class);
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void consumeBatchMaterialRejectsNullOperator() {
        MaterialBatch batch = batch("MB-5", "RM-1", "100", "0", "50", "8.50");
        when(materialBatchRepository.findById("MB-5")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.consumeBatchMaterial("F006", "MB-5", new BigDecimal("10"), "PP-1", null))
                .isInstanceOf(BusinessException.class);
        verify(materialConsumptionRepository, never()).save(any());
    }

    // ── C-B2: 食品安全防呆 — 过期/报废/不良品批次不可投产 ──

    @Test
    void useBatchMaterialRejectsExpiredBatch() {
        MaterialBatch batch = batch("MB-X1", "RM-1", "100", "0", "0", "8.50");
        batch.setStatus(MaterialBatchStatus.EXPIRED);
        when(materialBatchRepository.findByIdAndFactoryId("MB-X1", "F006")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.useBatchMaterial("F006", "MB-X1", new BigDecimal("10"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("不可用于生产"));
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void consumeBatchMaterialRejectsScrappedBatch() {
        MaterialBatch batch = batch("MB-X2", "RM-1", "100", "0", "50", "8.50");
        batch.setStatus(MaterialBatchStatus.SCRAPPED);
        when(materialBatchRepository.findById("MB-X2")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.consumeBatchMaterial("F006", "MB-X2", new BigDecimal("10"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("不可用于生产"));
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void useBatchMaterialRejectsDefectiveBatch() {
        MaterialBatch batch = batch("MB-X3", "RM-1", "100", "0", "0", "8.50");
        batch.setStatus(MaterialBatchStatus.DEFECTIVE);
        when(materialBatchRepository.findByIdAndFactoryId("MB-X3", "F006")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.useBatchMaterial("F006", "MB-X3", new BigDecimal("10"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class);
        verify(materialConsumptionRepository, never()).save(any());
    }

    @Test
    void useBatchMaterialRejectsInspectingBatch() {
        // 质检中批次 (待放行) 不得投产 — 食品安全 incoming QC gate (审计 follow-up)。
        MaterialBatch batch = batch("MB-X4", "RM-1", "100", "0", "0", "8.50");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.findByIdAndFactoryId("MB-X4", "F006")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() ->
                service.useBatchMaterial("F006", "MB-X4", new BigDecimal("10"), "PP-1", 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("不可用于生产"));
        verify(materialConsumptionRepository, never()).save(any());
    }

    private MaterialConsumption captureSavedConsumption() {
        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(captor.capture());
        return captor.getValue();
    }

    private MaterialBatch batch(String id, String materialTypeId, String receipt, String used,
                                String reserved, String unitPrice) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId("F006");
        batch.setBatchNumber("B-" + id);
        batch.setMaterialTypeId(materialTypeId);
        batch.setReceiptQuantity(new BigDecimal(receipt));
        batch.setUsedQuantity(new BigDecimal(used));
        batch.setReservedQuantity(new BigDecimal(reserved));
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-RAW");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        if (unitPrice != null) {
            batch.setUnitPrice(new BigDecimal(unitPrice));
        }
        return batch;
    }

    private RawMaterialType materialType(String id, String name, String unit, String minStock) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setName(name);
        type.setUnit(unit);
        type.setMinStock(new BigDecimal(minStock));
        return type;
    }
}
