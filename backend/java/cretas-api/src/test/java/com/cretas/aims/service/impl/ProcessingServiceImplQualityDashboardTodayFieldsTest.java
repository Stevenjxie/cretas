package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
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
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * B3-fix (2026-07-06) — web-admin 首页质量统计卡片 (DashboardAdmin.vue) 读取
 * {@code qualityStats.todayInspections} / {@code qualityStats.failedBatches},
 * 但 {@link ProcessingServiceImpl#getQualityDashboard} 此前从未输出这两个顶层字段
 * (只有月度的 totalInspections/failedInspections) → 前端恒显 "-"。
 *
 * <p>验证修复后 dashboard 顶层含:
 * <ul>
 *   <li>{@code todayInspections} = 只按"今天"这一天查询的检验数(与月度范围区分的子集)</li>
 *   <li>{@code failedBatches} = 与既有 failedInspections/passRate 同口径(本月)的不合格批次数</li>
 *   <li>既有字段 passRate / totalInspections / failedInspections 不受影响</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplQualityDashboardTodayFieldsTest {

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
    @Mock private com.cretas.aims.util.BackdateWindowValidator backdateWindowValidator;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String F_ID = "F006";

    private QualityInspection inspection(LocalDate date, String result) {
        return QualityInspection.builder()
                .id(java.util.UUID.randomUUID().toString())
                .factoryId(F_ID)
                .inspectionDate(date)
                .sampleSize(BigDecimal.TEN)
                .passRate("PASS".equals(result) ? BigDecimal.valueOf(100) : BigDecimal.valueOf(0))
                .result(result)
                .build();
    }

    @Test
    @DisplayName("B3: getQualityDashboard exposes todayInspections + failedBatches at top level")
    void dashboardExposesTodayInspectionsAndFailedBatches() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        // 本月范围: 2 条今天(1 pass/1 fail) + 1 条更早的 fail (若月初非今天)
        List<QualityInspection> monthly = List.of(
                inspection(today, "PASS"),
                inspection(today, "FAIL")
        );
        // 只查"今天"这一天: 与上面月度查询共享同 2 条(因为都发生在今天)
        List<QualityInspection> todayOnly = List.of(
                inspection(today, "PASS"),
                inspection(today, "FAIL")
        );

        when(qualityInspectionRepository.findByFactoryIdAndDateRange(eq(F_ID), eq(monthStart), eq(today)))
                .thenReturn(monthly);
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(eq(F_ID), eq(today), eq(today)))
                .thenReturn(todayOnly);
        // getQualityTrends(30天) 和 getInspections(分页) 也会被 getQualityDashboard 调用
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(eq(F_ID), eq(today.minusDays(30)), eq(today)))
                .thenReturn(monthly);
        // getQualityDashboard 也拉"最近检验记录"分页(与本测试目标无关, 只需不 NPE)
        when(qualityInspectionRepository.findByFactoryId(anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> dashboard = service.getQualityDashboard(F_ID);

        // 新字段: 修复目标
        assertEquals(2, dashboard.get("todayInspections"), "todayInspections 应为今天查询到的检验数");
        assertEquals(1L, dashboard.get("failedBatches"), "failedBatches 应与月度 failedInspections 同口径");

        // 既有字段不受影响
        assertEquals(2, dashboard.get("totalInspections"));
        assertEquals(1L, dashboard.get("failedInspections"));
        assertEquals(BigDecimal.valueOf(50.00).setScale(2), dashboard.get("passRate"));
    }
}
