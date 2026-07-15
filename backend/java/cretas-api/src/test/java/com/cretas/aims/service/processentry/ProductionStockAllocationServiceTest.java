package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionStockAllocationServiceTest {

    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private ProductionInputAllocationRepository allocationRepository;
    @Mock
    private WarehouseResolver warehouseResolver;

    private ProductionStockAllocationService service;

    @BeforeEach
    void setUp() {
        service = new ProductionStockAllocationServiceImpl(
                materialBatchRepository, allocationRepository, warehouseResolver);
    }

    @Test
    void allocatesWorkshopStockInFefoOrderAndSubtractsPendingFormalAllocations() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "6");
        MaterialBatch first = batch("B1", "RAW-1", "WKS-1", "3", LocalDate.of(2026, 7, 20));
        MaterialBatch second = batch("B2", "RAW-1", "WKS-1", "5", LocalDate.of(2026, 7, 25));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(first, second));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("1"));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B2"))
                .thenReturn(BigDecimal.ZERO);

        List<ProductionStockAllocationService.PlannedAllocation> result =
                service.plan("F006", List.of(input));

        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::materialBatchId)
                .containsExactly("B1", "B2");
        assertThat(result).extracting(ProductionStockAllocationService.PlannedAllocation::quantity)
                .containsExactly(new BigDecimal("2"), new BigDecimal("4"));
    }

    @Test
    void rejectsFormalSubmissionWithStructuredShortageAndExactOperatorMessage() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "10");
        MaterialBatch only = batch("B1", "RAW-1", "WKS-1", "7", LocalDate.of(2026, 7, 20));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate(
                "F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(only));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.plan("F006", List.of(input)))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getMessage())
                            .isEqualTo("当前只能保存草稿，生产库中投料量不足。需要 10kg，可用 7kg，缺少 3kg，请联系仓管补料");
                    assertThat(error.getShortage().getRequired()).isEqualByComparingTo("10");
                    assertThat(error.getShortage().getAvailable()).isEqualByComparingTo("7");
                    assertThat(error.getShortage().getShortage()).isEqualByComparingTo("3");
                    assertThat(error.getShortage().getItems()).singleElement().satisfies(item -> {
                        assertThat(item.getMaterialTypeId()).isEqualTo("RAW-1");
                        assertThat(item.getRequired()).isEqualByComparingTo("10");
                        assertThat(item.getAvailable()).isEqualByComparingTo("7");
                        assertThat(item.getShortage()).isEqualByComparingTo("3");
                    });
                });
    }

    @Test
    void refusesToGuessWhenWorkshopWarehouseCannotBeResolved() {
        ProcessSheetRowRequest.MaterialInputTotal input = total("RAW-1", "1");
        when(warehouseResolver.resolveWorkshopId("F006"))
                .thenThrow(new BusinessException(500, "missing workshop")
                        .withCode("WORKSHOP_WAREHOUSE_NOT_CONFIGURED"));

        assertThatThrownBy(() -> service.plan("F006", List.of(input)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("WORKSHOP_WAREHOUSE_NOT_CONFIGURED"));
    }

    @Test
    void legacyExplicitBatchIsLockedAndPendingAllocationPreventsDoubleUse() {
        MaterialBatch batch = batch("B1", "RAW-1", "WKS-1", "7", LocalDate.of(2026, 7, 20));
        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId("B1");
        input.setSkuId("RAW-1");
        input.setQuantity(new BigDecimal("5"));

        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B1", "F006"))
                .thenReturn(java.util.Optional.of(batch));
        when(allocationRepository.sumPendingQuantityByMaterialBatchId("F006", "B1"))
                .thenReturn(new BigDecimal("3"));

        assertThatThrownBy(() -> service.planExplicit("F006", List.of(input)))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error -> {
                    assertThat(error.getShortage().getRequired()).isEqualByComparingTo("5");
                    assertThat(error.getShortage().getAvailable()).isEqualByComparingTo("4");
                    assertThat(error.getShortage().getShortage()).isEqualByComparingTo("1");
                });
    }

    private static ProcessSheetRowRequest.MaterialInputTotal total(String materialTypeId, String quantity) {
        ProcessSheetRowRequest.MaterialInputTotal input = new ProcessSheetRowRequest.MaterialInputTotal();
        input.setMaterialTypeId(materialTypeId);
        input.setQuantity(new BigDecimal(quantity));
        input.setUnit("kg");
        return input;
    }

    private static MaterialBatch batch(
            String id,
            String materialTypeId,
            String warehouseId,
            String quantity,
            LocalDate expireDate) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId("F006");
        batch.setMaterialTypeId(materialTypeId);
        batch.setWarehouseId(warehouseId);
        batch.setReceiptQuantity(new BigDecimal(quantity));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setExpireDate(expireDate);
        return batch;
    }
}
