package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("M12 stocktake cutoff, evidence and two-step apply contract")
class FactoryStocktakeM12ContractTest {

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private FactoryStocktakeItemRepository stocktakeItemRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialBatchAdjustmentRepository adjustmentRepo;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private InventoryLowStockEventPublisher lowStockPublisher;
    @Mock private VoucherService voucherService;
    @InjectMocks private FactoryStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", lowStockPublisher);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
    }

    @Test
    @DisplayName("server authors cutoff/end/period and ignores a forged client period")
    void initiateAuthorsImmutableCutoffAndPeriod() {
        CreateStocktakeRequest request = new CreateStocktakeRequest();
        request.setWarehouseId("WH-LOG");
        request.setPeriodMonth("1999-01");
        request.setReconciliationPreset("LAST_7_DAYS");
        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(anyString(), anyString(), anyString())).thenReturn(0L);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId("F006", "WH-LOG")).thenReturn(List.of());
        when(stocktakeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        service.initiate("F006", request, 1309L);
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        ArgumentCaptor<FactoryStocktake> captor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        FactoryStocktake saved = captor.getValue();
        assertThat(saved.getInventoryCutoffAt()).isBetween(before, after);
        assertThat(saved.getReconciliationEndAt()).isEqualTo(saved.getInventoryCutoffAt());
        assertThat(saved.getReconciliationStartAt()).isEqualTo(saved.getInventoryCutoffAt().minusDays(7));
        assertThat(saved.getPeriodMonth()).isEqualTo(YearMonth.from(saved.getInventoryCutoffAt()).toString());
        assertThat(saved.getPeriodMonth()).isNotEqualTo("1999-01");
    }

    @Test
    @DisplayName("zero difference self confirmation stays APPROVED until a separate APPLIED action")
    void zeroDifferenceKeepsApprovalAndApplySeparateWithoutInventoryWrite() {
        FactoryStocktake stocktake = stocktake(FactoryStocktake.Status.PENDING_APPROVAL, BigDecimal.TEN, BigDecimal.TEN);
        stocktake.setInitiatedBy(1309L);
        stocktake.setCountedBy(1309L);
        stocktake.setVersion(4L);
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktake.getId(), "F006")).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(stocktake.getId(), "F006", 1309L, "factory_super_admin", 4L);

        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.APPROVED);
        assertThat(stocktake.isSelfConfirmedZeroDifference()).isTrue();
        assertThat(stocktake.getAppliedAt()).isNull();

        MaterialBatch batch = identityBatch(stocktake.getItems().get(0));
        when(materialBatchRepo.findByIdAndFactoryId(batch.getId(), "F006")).thenReturn(Optional.of(batch));
        service.apply(stocktake.getId(), "F006", 1309L, 4L);

        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.APPLIED);
        assertThat(stocktake.getAppliedBy()).isEqualTo(1309L);
        verify(adjustmentRepo, never()).save(any());
        verify(materialBatchRepo, never()).save(any());
        verify(voucherService, never()).createManual(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("non-zero maker approval is rejected under lock and writes no approval state")
    void nonZeroDifferenceRejectsSelfApproval() {
        FactoryStocktake stocktake = stocktake(FactoryStocktake.Status.PENDING_APPROVAL, BigDecimal.TEN, new BigDecimal("9"));
        stocktake.setInitiatedBy(1200L);
        stocktake.setCountedBy(1201L);
        stocktake.setSubmittedBy(1309L);
        stocktake.setVersion(2L);
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktake.getId(), "F006")).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.approve(stocktake.getId(), "F006", 1309L, "factory_super_admin", 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("STOCKTAKE_SELF_APPROVAL_FORBIDDEN"));
        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.PENDING_APPROVAL);
        verify(stocktakeRepo, never()).save(any());
    }

    @Test
    @DisplayName("stale approval version fails before state mutation")
    void staleVersionFailsClosed() {
        FactoryStocktake stocktake = stocktake(FactoryStocktake.Status.PENDING_APPROVAL, BigDecimal.TEN, BigDecimal.TEN);
        stocktake.setVersion(7L);
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktake.getId(), "F006")).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.approve(stocktake.getId(), "F006", 2000L, "finance_manager", 6L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("STALE_STOCKTAKE_VERSION"));
        verify(stocktakeRepo, never()).save(any());
    }

    @Test
    @DisplayName("pending approval evidence is immutable and cannot bypass re-approval")
    void pendingApprovalCountEvidenceIsLocked() {
        FactoryStocktake stocktake = stocktake(
                FactoryStocktake.Status.PENDING_APPROVAL, BigDecimal.TEN, BigDecimal.TEN);
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktake.getId(), "F006"))
                .thenReturn(Optional.of(stocktake));
        StocktakeItemUpdateDTO update = new StocktakeItemUpdateDTO();
        update.setItemId(stocktake.getItems().get(0).getId());
        update.setActualQty(new BigDecimal("9"));

        assertThatThrownBy(() -> service.updateItems(
                stocktake.getId(), "F006", List.of(update), 1309L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("STOCKTAKE_COUNT_EVIDENCE_LOCKED"));
        assertThat(stocktake.getItems().get(0).getActualQty()).isEqualByComparingTo("10");
        verify(stocktakeRepo, never()).save(any());
    }

    @Test
    @DisplayName("editing a rejected task explicitly begins a new counting and approval cycle")
    void rejectedTaskReturnsToCountingBeforeEvidenceChanges() {
        FactoryStocktake stocktake = stocktake(
                FactoryStocktake.Status.REJECTED, BigDecimal.TEN, BigDecimal.TEN);
        stocktake.setSubmittedBy(1309L);
        stocktake.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        stocktake.setApprovedBy(2000L);
        stocktake.setApprovedAt(LocalDateTime.now().minusMinutes(1));
        stocktake.setRejectReason("recount");
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(stocktake.getId(), "F006"))
                .thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StocktakeItemUpdateDTO update = new StocktakeItemUpdateDTO();
        update.setItemId(stocktake.getItems().get(0).getId());
        update.setActualQty(new BigDecimal("9"));

        service.updateItems(stocktake.getId(), "F006", List.of(update), 1309L);

        assertThat(stocktake.getStatus()).isEqualTo(FactoryStocktake.Status.COUNTING);
        assertThat(stocktake.getItems().get(0).getDifferenceQty()).isEqualByComparingTo("-1");
        assertThat(stocktake.getSubmittedAt()).isNull();
        assertThat(stocktake.getApprovedAt()).isNull();
        assertThat(stocktake.getRejectReason()).isNull();
    }

    private FactoryStocktake stocktake(FactoryStocktake.Status status, BigDecimal system, BigDecimal actual) {
        FactoryStocktake stocktake = new FactoryStocktake();
        stocktake.setId(UUID.randomUUID().toString());
        stocktake.setFactoryId("F006");
        stocktake.setWarehouseId("WH-LOG");
        stocktake.setStatus(status);
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setStocktake(stocktake);
        item.setMaterialBatchId("BATCH-001");
        item.setRawMaterialTypeId("MAT-001");
        item.setSystemQty(system);
        item.setActualQty(actual);
        item.setDifferenceQty(actual.subtract(system));
        stocktake.setItems(List.of(item));
        return stocktake;
    }

    private MaterialBatch identityBatch(FactoryStocktakeItem item) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(item.getMaterialBatchId());
        batch.setFactoryId("F006");
        batch.setWarehouseId("WH-LOG");
        batch.setMaterialTypeId(item.getRawMaterialTypeId());
        return batch;
    }
}
