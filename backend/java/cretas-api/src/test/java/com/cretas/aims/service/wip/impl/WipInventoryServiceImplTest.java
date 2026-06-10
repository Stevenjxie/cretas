package com.cretas.aims.service.wip.impl;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WipInventoryServiceImpl")
class WipInventoryServiceImplTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private SemiFinishedInventoryRepository wipRepo;

    @Mock
    private SemiFinishedInventoryTransactionRepository txnRepo;

    @Mock
    private ProductionReportRepository reportRepo;

    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;

    @Mock
    private WorkProcessTaskRepository taskRepo;

    @Mock
    private WorkProcessRepository workProcessRepo;

    // SP3 added ApplicationEventPublisher.publishEvent(ProductionCostUpdatedEvent) to
    // WipInventoryServiceImpl.postSemiOutputLedger — mock required so @InjectMocks can inject it.
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> wipCaptor;

    @Captor
    private ArgumentCaptor<SemiFinishedInventoryTransaction> txnCaptor;

    @Test
    @DisplayName("validateSourceWip rejects input quantity above available WIP")
    void validateSourceWip_rejectsOverInput() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("20"))
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(new BigDecimal("30"))
                .unit("kg")
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-S1"))
                .thenReturn(Optional.of(source));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip("WIP-S1", new BigDecimal("21"), "kg"));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_INSUFFICIENT", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSourceWip requires input quantity when source WIP is selected")
    void validateSourceWip_requiresInputQuantity() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("20"))
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(new BigDecimal("30"))
                .unit("kg")
                .build();
        when(wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-S1"))
                .thenReturn(Optional.of(source));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip("WIP-S1", null, "kg"));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_INPUT_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("validateSourceWip subtracts pending report reservations before allowing new input")
    void validateSourceWip_blocksWhenPendingReservationsWouldOverClaim() {
        SemiFinishedInventory source = SemiFinishedInventory.builder()
                .intermediateBatchNo("WIP-S1")
                .availableQuantity(new BigDecimal("100"))
                .producedQuantity(new BigDecimal("100"))
                .consumedQuantity(BigDecimal.ZERO)
                .unit("kg")
                .build();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "WIP-S1"))
                .thenReturn(Optional.of(source));
        when(reportRepo.sumPendingInputBySourceWipNo(FACTORY_ID, "WIP-S1", null))
                .thenReturn(new BigDecimal("70"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip(FACTORY_ID, "WIP-S1", new BigDecimal("40"), "kg", null));

        assertEquals(409, ex.getCode());
        assertEquals("WIP_RESERVED_INSUFFICIENT", ex.getErrorCode());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getActionHint().contains("30 kg"));
    }

    @Test
    @DisplayName("validateSourceWip scopes source WIP lookup by factory")
    void validateSourceWip_scopesSourceLookupByFactory() {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "WIP-SAME"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateSourceWip(FACTORY_ID, "WIP-SAME", new BigDecimal("10"), "kg", null));

        assertEquals(404, ex.getCode());
        verify(wipRepo, never()).findForUpdateByIntermediateBatchNoAndDeletedAtIsNull(anyString());
    }

    @Test
    @DisplayName("postApprovedOutput creates a produced WIP row for approved output")
    void postApprovedOutput_createsProducedWip() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .workProcessId("WP-001")
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(500L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("30"))
                .materialCost(new BigDecimal("45"))
                .build();
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-001-B9001-S2-7001"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals("PROD-001-B9001-S2-7001", saved.getIntermediateBatchNo());
        assertEquals(9001L, saved.getBatchId());
        assertEquals(7001L, saved.getSourceWorkProcessTaskId());
        assertEquals(new BigDecimal("75"), saved.getProducedQuantity());
        assertEquals(new BigDecimal("75"), saved.getAvailableQuantity());
        assertEquals(new BigDecimal("75"), saved.getAccumulatedCost());
        assertEquals(new BigDecimal("1.0000"), saved.getUnitCost());
    }

    @Test
    @DisplayName("postApprovedOutput is idempotent when report is already WIP posted")
    void postApprovedOutput_skipsAlreadyPostedReport() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(501L)
                .workProcessTaskId(7001L)
                .outputQuantity(new BigDecimal("75"))
                .customFields(Map.of("wipPosted", true))
                .build();

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        verify(wipRepo, never()).findByIntermediateBatchNoAndDeletedAtIsNull(anyString());
        verify(wipRepo, never()).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("postApprovedOutput rolls up INPUT material and SEGMENT labor cost for OUTPUT reports")
    void postApprovedOutput_outputReportRollsTaskCosts() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .workProcessId("WP-001")
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(502L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .reportKind("OUTPUT")
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .build();
        when(reportRepo.findYieldReportsByTask(FACTORY_ID, 7001L)).thenReturn(List.of(
                ProductionReport.builder().reportKind("INPUT").materialCost(new BigDecimal("100.00")).build(),
                ProductionReport.builder().reportKind("SEGMENT").laborCost(new BigDecimal("20.00")).build()
        ));
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-001-B9001-S2-7001"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, output, task, 10L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();
        assertEquals(new BigDecimal("120.00"), saved.getAccumulatedCost());
        assertEquals(new BigDecimal("1.6000"), saved.getUnitCost());
        assertEquals(Boolean.TRUE, output.getCustomFields().get("wipPosted"));
    }

    // ==================== W4 cost-chain: FINISHED-path SP3 event ====================

    @Test
    @DisplayName("W4: FINISHED/legacy path publishes ProductionCostUpdatedEvent when unitCost computed")
    void postApprovedOutput_finishedPath_publishesCostEvent() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7101L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9101L)
                .workProcessId("WP-002")
                .productTypeId("PROD-010")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        // outputKind = null → legacy FINISHED path (upsertProducedWip); report carries direct costs
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(701L)
                .batchId(9101L)
                .workProcessTaskId(7101L)
                .outputQuantity(new BigDecimal("50"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("80.00"))
                .materialCost(new BigDecimal("120.00"))
                .build();
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-010-B9101-S2-7101"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, output, task, 11L);

        // unitCost = (80+120)/50 = 4.0000 → event must fire with batch/product/unitCost
        ArgumentCaptor<ProductionCostUpdatedEvent> evtCaptor =
                ArgumentCaptor.forClass(ProductionCostUpdatedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        ProductionCostUpdatedEvent evt = evtCaptor.getValue();
        assertEquals(9101L, evt.getProductionBatchId());
        assertEquals("PROD-010", evt.getProductTypeId());
        assertEquals(new BigDecimal("4.0000"), evt.getActualUnitCost());
        assertEquals(new BigDecimal("200.00"), evt.getActualTotalCost());
    }

    @Test
    @DisplayName("W4: FINISHED path does NOT publish cost event when unitCost null (honest null propagation)")
    void postApprovedOutput_finishedPath_noEventWhenUnitCostNull() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7102L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9102L)
                .workProcessId("WP-003")
                .productTypeId("PROD-011")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        // No labor/material cost → accumulatedCost null → unitCost null → no event (nothing to backfill)
        ProductionReport output = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(702L)
                .batchId(9102L)
                .workProcessTaskId(7102L)
                .outputQuantity(new BigDecimal("40"))
                .outputUnit("kg")
                .build();
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-011-B9102-S1-7102"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, output, task, 11L);

        verify(eventPublisher, never()).publishEvent(any(ProductionCostUpdatedEvent.class));
    }

    // ==================== SP1 tests (T3) ====================

    @Test
    @DisplayName("SP1-SEMI: postApprovedOutput writes SFI row + IN ledger txn on first SEMI report")
    void postApprovedOutput_semiOutputKind_firstIn_createsNewSfiAndTxn() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9100L)
                .productTypeId("PROD-002")
                .processOrder(3)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(600L)
                .batchId(9100L)
                .workProcessTaskId(8001L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-001")
                .semiOutputQuantity(new BigDecimal("50"))
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("100"))
                .materialCost(new BigDecimal("150"))
                .build();

        // Idempotency guard: no existing IN txn
        when(txnRepo.findByFactoryIdAndSourceRefAndTxnType(FACTORY_ID, "SEMI-BATCH-001",
                SemiFinishedInventoryTransaction.TxnType.IN))
                .thenReturn(Optional.empty());
        // No existing SFI row (first IN)
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-BATCH-001"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            sfi.setId(7777L); // simulate DB-assigned ID
            return sfi;
        });
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        // SFI created
        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory sfi = wipCaptor.getValue();
        assertEquals("SEMI-BATCH-001", sfi.getIntermediateBatchNo());
        assertEquals(new BigDecimal("50"), sfi.getProducedQuantity());
        assertEquals(new BigDecimal("50"), sfi.getAvailableQuantity());
        // unitCost = (100+150)/50 = 5.0000
        assertEquals(new BigDecimal("5.0000"), sfi.getUnitCost());

        // Ledger IN txn saved
        verify(txnRepo).save(txnCaptor.capture());
        SemiFinishedInventoryTransaction txn = txnCaptor.getValue();
        assertEquals(SemiFinishedInventoryTransaction.TxnType.IN, txn.getTxnType());
        assertEquals(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT, txn.getSourceType());
        assertEquals("SEMI-BATCH-001", txn.getSourceRef());
        assertEquals(new BigDecimal("50"), txn.getQuantity());
        assertEquals(new BigDecimal("5.0000"), txn.getUnitCostAtTxn());
        assertEquals(600L, txn.getReportId());
        assertEquals(20L, txn.getOperatorId());

        // Legacy FINISHED path should NOT run (outputKind=SEMI → no upsertProducedWip)
        verify(wipRepo, never()).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("SP1-SEMI: postSemiOutputLedger uses moving-average cost on second IN")
    void postApprovedOutput_semiOutputKind_secondIn_movingAverageCost() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8002L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9101L)
                .productTypeId("PROD-003")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(601L)
                .batchId(9101L)
                .workProcessTaskId(8002L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-002")
                .semiOutputQuantity(new BigDecimal("40"))
                .semiOutputUnit("kg")
                .laborCost(new BigDecimal("80"))
                .materialCost(new BigDecimal("40"))
                // totalCost=120, unitCost=120/40=3.0000
                .build();

        when(txnRepo.findByFactoryIdAndSourceRefAndTxnType(FACTORY_ID, "SEMI-BATCH-002",
                SemiFinishedInventoryTransaction.TxnType.IN))
                .thenReturn(Optional.empty());

        // Existing SFI: 60kg @4.0000/kg
        SemiFinishedInventory existing = SemiFinishedInventory.builder()
                .id(555L)
                .factoryId(FACTORY_ID)
                .intermediateBatchNo("SEMI-BATCH-002")
                .producedQuantity(new BigDecimal("60"))
                .consumedQuantity(new BigDecimal("10"))
                .availableQuantity(new BigDecimal("50"))
                .unitCost(new BigDecimal("4.0000"))
                .accumulatedCost(new BigDecimal("240.00"))
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-BATCH-002"))
                .thenReturn(Optional.of(existing));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        verify(wipRepo).save(wipCaptor.capture());
        SemiFinishedInventory saved = wipCaptor.getValue();

        // producedQuantity = 60 + 40 = 100
        assertEquals(new BigDecimal("100"), saved.getProducedQuantity());
        // availableQuantity = 100 - 10 = 90
        assertEquals(new BigDecimal("90"), saved.getAvailableQuantity());
        // Moving average: (60*4.0000 + 40*3.0000) / 100 = (240 + 120) / 100 = 3.6000
        assertEquals(new BigDecimal("3.6000"), saved.getUnitCost());
    }

    @Test
    @DisplayName("SP1-SEMI: idempotency guard skips duplicate IN without error")
    void postApprovedOutput_semiOutputKind_idempotentSkip() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8003L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9102L)
                .productTypeId("PROD-004")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(602L)
                .outputKind("SEMI")
                .semiCode("SEMI-BATCH-003")
                .semiOutputQuantity(new BigDecimal("30"))
                .laborCost(new BigDecimal("60"))
                .build();

        // Existing IN txn → idempotent skip
        when(txnRepo.findByFactoryIdAndSourceRefAndTxnType(FACTORY_ID, "SEMI-BATCH-003",
                SemiFinishedInventoryTransaction.TxnType.IN))
                .thenReturn(Optional.of(SemiFinishedInventoryTransaction.builder()
                        .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                        .build()));

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        // Should not write any new SFI or txn
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("SP1-SEMI: null semiCode skips postSemiOutputLedger gracefully (R4 backward compat)")
    void postApprovedOutput_semiOutputKind_nullSemiCode_skipsGracefully() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8004L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9103L)
                .productTypeId("PROD-005")
                .processOrder(1)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(603L)
                .outputKind("SEMI")
                .semiCode(null)   // missing → skip
                .semiOutputQuantity(new BigDecimal("20"))
                .build();

        service.postApprovedOutput(FACTORY_ID, report, task, 20L);

        verify(txnRepo, never()).findByFactoryIdAndSourceRefAndTxnType(anyString(), anyString(), anyString());
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("SP1-BOTH: postApprovedOutput runs SEMI ledger AND finished WIP paths")
    void postApprovedOutput_bothOutputKind_runsBothPaths() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(8005L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9104L)
                .productTypeId("PROD-006")
                .processOrder(4)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(604L)
                .batchId(9104L)
                .workProcessTaskId(8005L)
                .outputKind("BOTH")
                .semiCode("SEMI-BATCH-004")
                .semiOutputQuantity(new BigDecimal("20"))
                .semiOutputUnit("kg")
                .outputQuantity(new BigDecimal("80"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("50"))
                .materialCost(new BigDecimal("50"))
                .build();

        when(txnRepo.findByFactoryIdAndSourceRefAndTxnType(FACTORY_ID, "SEMI-BATCH-004",
                SemiFinishedInventoryTransaction.TxnType.IN))
                .thenReturn(Optional.empty());
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "SEMI-BATCH-004"))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-006-B9104-S4-8005"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> {
            SemiFinishedInventory sfi = inv.getArgument(0);
            if (sfi.getId() == null) sfi.setId(8888L);
            return sfi;
        });
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 30L);

        // wipRepo.save called TWICE: once for SEMI SFI, once for FINISHED WIP
        verify(wipRepo, times(2)).save(any(SemiFinishedInventory.class));
        // txnRepo.save called ONCE: for SEMI IN ledger
        verify(txnRepo, times(1)).save(any(SemiFinishedInventoryTransaction.class));
    }

    @Test
    @DisplayName("SP1: null outputKind (legacy) skips SEMI path and runs existing WIP path")
    void postApprovedOutput_nullOutputKind_legacyPathOnly() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(7001L)
                .factoryId(FACTORY_ID)
                .productionBatchId(9001L)
                .productTypeId("PROD-001")
                .processOrder(2)
                .plannedUnit("kg")
                .build();
        ProductionReport report = ProductionReport.builder()
                .factoryId(FACTORY_ID)
                .id(500L)
                .batchId(9001L)
                .workProcessTaskId(7001L)
                .outputKind(null)   // legacy — no SP1 fields
                .outputQuantity(new BigDecimal("75"))
                .outputUnit("kg")
                .laborCost(new BigDecimal("30"))
                .materialCost(new BigDecimal("45"))
                .build();
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY_ID, "PROD-001-B9001-S2-7001"))
                .thenReturn(Optional.empty());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.postApprovedOutput(FACTORY_ID, report, task, 10L);

        // No txnRepo interaction for legacy path
        verify(txnRepo, never()).findByFactoryIdAndSourceRefAndTxnType(anyString(), anyString(), anyString());
        verify(txnRepo, never()).save(any());
        // Regular WIP path runs
        verify(wipRepo).save(any(SemiFinishedInventory.class));
    }

    // ========== SP1 T4: getOutputOptions ==========

    @Test
    @DisplayName("T4: getOutputOptions returns items only for tasks whose WorkProcess has semiFinishedOutputCode")
    void getOutputOptions_returnsOnlyTasksWithSemiCode() {
        Long batchId = 200L;

        WorkProcessTask task1 = WorkProcessTask.builder()
                .id(1L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-01").processOrder(1).build();
        WorkProcessTask task2 = WorkProcessTask.builder()
                .id(2L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-02").processOrder(2).build();

        WorkProcess wp1 = WorkProcess.builder()
                .id("WP-01").factoryId(FACTORY_ID).processName("焯水")
                .semiFinishedOutputCode("SEMI-01").build();
        WorkProcess wp2 = WorkProcess.builder()
                .id("WP-02").factoryId(FACTORY_ID).processName("腌制")
                .semiFinishedOutputCode(null).build();   // no semi code → excluded

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task1, task2));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-01")).thenReturn(Optional.of(wp1));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-02")).thenReturn(Optional.of(wp2));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(1, resp.getItems().size());
        OutputOptionsResponse.OutputOptionItem item = resp.getItems().get(0);
        assertEquals(1L, item.getTaskId());
        assertEquals("焯水", item.getProcessName());
        assertEquals("SEMI-01", item.getSemiCode());
        assertEquals(1, item.getProcessOrder());
    }

    @Test
    @DisplayName("T4: getOutputOptions returns empty list when no tasks have semiFinishedOutputCode")
    void getOutputOptions_emptyWhenNoSemiCodeConfigured() {
        Long batchId = 201L;

        WorkProcessTask task = WorkProcessTask.builder()
                .id(3L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId("WP-03").processOrder(1).build();

        WorkProcess wp = WorkProcess.builder()
                .id("WP-03").factoryId(FACTORY_ID).processName("切割")
                .semiFinishedOutputCode("   ").build();  // blank → excluded

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task));
        when(workProcessRepo.findByFactoryIdAndId(FACTORY_ID, "WP-03")).thenReturn(Optional.of(wp));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
    }

    @Test
    @DisplayName("T4: getOutputOptions returns empty list when batch has no tasks")
    void getOutputOptions_emptyWhenNoTasks() {
        Long batchId = 202L;

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of());

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
        verify(workProcessRepo, never()).findByFactoryIdAndId(anyString(), anyString());
    }

    @Test
    @DisplayName("T4: getOutputOptions skips tasks with null workProcessId")
    void getOutputOptions_skipsTasksWithNullWorkProcessId() {
        Long batchId = 203L;

        WorkProcessTask task = WorkProcessTask.builder()
                .id(4L).factoryId(FACTORY_ID).productionBatchId(batchId)
                .workProcessId(null).processOrder(1).build();  // no workProcessId

        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(FACTORY_ID, batchId))
                .thenReturn(List.of(task));

        OutputOptionsResponse resp = service.getOutputOptions(FACTORY_ID, batchId);

        assertEquals(0, resp.getItems().size());
        verify(workProcessRepo, never()).findByFactoryIdAndId(anyString(), anyString());
    }
}
