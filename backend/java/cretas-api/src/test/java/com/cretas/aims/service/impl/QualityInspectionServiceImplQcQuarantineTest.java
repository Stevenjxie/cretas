package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔒🔒 QC 生产门 (食品安全): 生产批次质检判 FAILED → 该生产计划已入库的可售成品批次隔离为 DEFECTIVE。
 *
 * <p>追溯链: QualityInspection.productionBatchId → ProductionBatch.productionPlanId →
 * FinishedGoodsBatch.productionPlanId (1:N, 跨多次小结)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QualityInspectionServiceImpl QC-FAIL quarantines finished-goods")
class QualityInspectionServiceImplQcQuarantineTest {

    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @InjectMocks
    private QualityInspectionServiceImpl service;

    private static final String FACTORY = "F001";
    private static final Long BATCH_ID = 42L;
    private static final String PLAN_ID = "PLAN-1";

    @BeforeEach
    void setUp() {
        // field-level @Autowired(required=false) optional deps — Mockito 不注入, 手动 wire。
        ReflectionTestUtils.setField(service, "productionBatchRepository", productionBatchRepository);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
    }

    @Test
    @DisplayName("result=FAIL → ProductionBatch qualityStatus=FAILED + only AVAILABLE FG → DEFECTIVE")
    void fail_quarantinesAvailableFinishedGoods() {
        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductionBatch batch = productionBatch();
        when(productionBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        FinishedGoodsBatch sellable = fg("FG-AVAILABLE", "AVAILABLE");
        FinishedGoodsBatch depleted = fg("FG-DEPLETED", "DEPLETED");
        when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                .thenReturn(List.of(sellable, depleted));

        service.createInspection(FACTORY, inspection("FAIL"));

        assertEquals(QualityStatus.FAILED, batch.getQualityStatus());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(captor.capture());
        assertEquals("FG-AVAILABLE", captor.getValue().getBatchNumber());
        assertEquals("DEFECTIVE", captor.getValue().getStatus());
        // DEPLETED 批次不被改动 (仍 DEPLETED, 未 save)
        assertEquals("DEPLETED", depleted.getStatus());
    }

    @Test
    @DisplayName("result=PASS → no FG quarantine, ProductionBatch qualityStatus=PASSED")
    void pass_doesNotQuarantine() {
        when(qualityInspectionRepository.save(any(QualityInspection.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductionBatch batch = productionBatch();
        when(productionBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        service.createInspection(FACTORY, inspection("PASS"));

        assertEquals(QualityStatus.PASSED, batch.getQualityStatus());
        verify(finishedGoodsBatchRepository, never())
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(any(), any());
        verify(finishedGoodsBatchRepository, never()).save(any());
    }

    private QualityInspection inspection(String result) {
        QualityInspection qi = new QualityInspection();
        qi.setProductionBatchId(BATCH_ID);
        qi.setResult(result);
        qi.setPassRate(new BigDecimal("50.0"));
        return qi;
    }

    private ProductionBatch productionBatch() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductionPlanId(PLAN_ID);
        return batch;
    }

    private FinishedGoodsBatch fg(String batchNumber, String status) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setBatchNumber(batchNumber);
        fg.setFactoryId(FACTORY);
        fg.setProductionPlanId(PLAN_ID);
        fg.setStatus(status);
        return fg;
    }
}
