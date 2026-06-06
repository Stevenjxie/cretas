package com.cretas.aims.service.wip.impl;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WipInventoryServiceImpl")
class WipInventoryServiceImplTest {

    private static final String FACTORY_ID = "F001";

    @Mock
    private SemiFinishedInventoryRepository wipRepo;

    @Mock
    private ProductionReportRepository reportRepo;

    @Mock
    private BatchLineageEdgeRepository lineageEdgeRepo;

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Captor
    private ArgumentCaptor<SemiFinishedInventory> wipCaptor;

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
}
