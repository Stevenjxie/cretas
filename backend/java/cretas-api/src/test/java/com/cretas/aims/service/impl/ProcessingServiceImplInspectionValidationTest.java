package com.cretas.aims.service.impl;

import com.cretas.aims.dto.batch.TeamBatchReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Issue #813 fix — verifies new validation in {@link ProcessingServiceImpl#submitInspection}:
 *
 * <ol>
 *   <li>{@code result=null} (or missing) throws {@link BusinessException}.</li>
 *   <li>{@code result="PASS"} with sub-95% passRate throws BusinessException
 *       (was: silently accepted, producing audit-nightmare "PASS at 43% defect rate").</li>
 *   <li>{@code result="FAIL"} with >=70% passRate throws BusinessException.</li>
 *   <li>{@code result="CONDITIONAL"} requires passRate in [70, 95).</li>
 *   <li>{@code result="PENDING"} accepted at any passRate (待复检, 无结论).</li>
 *   <li>Happy path (PASS with >=95%) is unaffected.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplInspectionValidationTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String F_ID = "F001";
    private static final String BATCH_ID = "1885";

    private Map<String, Object> buildInspection(int sample, int pass, int fail, Object result) {
        Map<String, Object> m = new HashMap<>();
        m.put("sampleSize", sample);
        m.put("passCount", pass);
        m.put("failCount", fail);
        if (result != null) m.put("result", result);
        return m;
    }

    @Test
    @DisplayName("Issue #813 fix A: result=null is rejected")
    void resultNull_rejected() {
        Map<String, Object> payload = buildInspection(5, 5, 0, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, payload));
        assert ex.getMessage().contains("检验结果");
    }

    @Test
    @DisplayName("Issue #813 fix A: result=\"\" is rejected")
    void resultBlank_rejected() {
        Map<String, Object> payload = buildInspection(5, 5, 0, "   ");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, payload));
        assert ex.getMessage().contains("检验结果");
    }

    @Test
    @DisplayName("Issue #813 fix B: PASS with 57% passRate is rejected (the customer-reported case)")
    void passWithLowPassRate_rejected() {
        // sample=7, pass=4, fail=3 → passRate = 57.14% (D 级 in entity)
        // 这就是 issue #813 描述的 auditor 噩梦 case.
        Map<String, Object> payload = buildInspection(7, 4, 3, "PASS");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, payload));
        assert ex.getMessage().contains("PASS");
    }

    @Test
    @DisplayName("Issue #813 fix B: FAIL with 100% passRate is rejected")
    void failWithHighPassRate_rejected() {
        // sample=5, pass=5, fail=0 → passRate = 100% — 不能判 FAIL
        Map<String, Object> payload = buildInspection(5, 5, 0, "FAIL");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, payload));
        assert ex.getMessage().contains("FAIL");
    }

    @Test
    @DisplayName("Issue #813 fix B: CONDITIONAL outside [70, 95) range is rejected")
    void conditional_outOfRange_rejected() {
        // 100% passRate is too high for CONDITIONAL
        Map<String, Object> tooHigh = buildInspection(5, 5, 0, "CONDITIONAL");
        assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, tooHigh));

        // 50% passRate is too low for CONDITIONAL
        Map<String, Object> tooLow = buildInspection(4, 2, 2, "CONDITIONAL");
        assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, tooLow));
    }

    @Test
    @DisplayName("Issue #813: PENDING accepted regardless of passRate (待复检)")
    void pending_alwaysAccepted() {
        Map<String, Object> payload = buildInspection(7, 4, 3, "PENDING");
        when(qualityInspectionService.createInspection(anyString(), any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        assertDoesNotThrow(() -> service.submitInspection(F_ID, BATCH_ID, payload));
    }

    @Test
    @DisplayName("Issue #813: happy path — PASS with 100% passRate still works")
    void passWithFullPassRate_accepted() {
        Map<String, Object> payload = buildInspection(5, 5, 0, "PASS");
        when(qualityInspectionService.createInspection(anyString(), any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        assertDoesNotThrow(() -> service.submitInspection(F_ID, BATCH_ID, payload));
    }

    @Test
    @DisplayName("Issue #813: happy path — CONDITIONAL with 85% passRate works")
    void conditional_in_range_accepted() {
        // sample=20, pass=17, fail=3 → passRate = 85% (B grade, in [70, 95))
        Map<String, Object> payload = buildInspection(20, 17, 3, "CONDITIONAL");
        when(qualityInspectionService.createInspection(anyString(), any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        assertDoesNotThrow(() -> service.submitInspection(F_ID, BATCH_ID, payload));
    }

    @Test
    @DisplayName("Issue #813: happy path — FAIL with 50% passRate works")
    void fail_below_threshold_accepted() {
        Map<String, Object> payload = buildInspection(4, 2, 2, "FAIL");
        when(qualityInspectionService.createInspection(anyString(), any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        assertDoesNotThrow(() -> service.submitInspection(F_ID, BATCH_ID, payload));
    }

    @Test
    @DisplayName("Issue #813: PASSED (legacy alias) accepted same as PASS")
    void passed_alias_accepted_at_high_passRate() {
        Map<String, Object> payload = buildInspection(5, 5, 0, "PASSED");
        when(qualityInspectionService.createInspection(anyString(), any(QualityInspection.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        assertDoesNotThrow(() -> service.submitInspection(F_ID, BATCH_ID, payload));
    }

    @Test
    @DisplayName("Issue #813: unknown result value rejected with clear error")
    void unknown_result_rejected() {
        Map<String, Object> payload = buildInspection(5, 5, 0, "UNKNOWN_VERDICT");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitInspection(F_ID, BATCH_ID, payload));
        assert ex.getMessage().contains("无效的检验结果");
    }

    // ── A-F1/F2 同族: 班组报工(submitTeamBatchReport)须批次已开工 ──

    private TeamBatchReportRequest teamReport(long batchId, int output) {
        TeamBatchReportRequest r = new TeamBatchReportRequest();
        r.setBatchId(batchId);
        r.setTotalOutput(output);
        return r;
    }

    private ProductionBatch batchWithStatus(long id, ProductionBatchStatus status) {
        return ProductionBatch.builder().id(id).factoryId(F_ID).status(status).build();
    }

    @Test
    @DisplayName("A-F1/F2: 班组报工 on PLANNED 批次被拒 (须先开始生产)")
    void teamReport_onPlannedBatch_rejected() {
        when(productionBatchRepository.findByIdAndFactoryId(7001L, F_ID))
                .thenReturn(java.util.Optional.of(batchWithStatus(7001L, ProductionBatchStatus.PLANNED)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitTeamBatchReport(F_ID, teamReport(7001L, 100)));
        assert ex.getMessage().contains("尚未开始生产");
    }

    @Test
    @DisplayName("A-F1/F2: 班组报工 on COMPLETED 批次被拒")
    void teamReport_onCompletedBatch_rejected() {
        when(productionBatchRepository.findByIdAndFactoryId(7002L, F_ID))
                .thenReturn(java.util.Optional.of(batchWithStatus(7002L, ProductionBatchStatus.COMPLETED)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitTeamBatchReport(F_ID, teamReport(7002L, 100)));
        assert ex.getMessage().contains("不可再");
    }
}
