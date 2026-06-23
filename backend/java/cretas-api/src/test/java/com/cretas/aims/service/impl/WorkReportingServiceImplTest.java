package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkReportSubmitRequest;
import com.cretas.aims.dto.WorkReportResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ReportMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkReportingServiceImpl 单元测试 — 单元B (F006 REQ-17)
 *
 * 旧报工路径去重: 从"全天去重" → "5 分钟窗口去重", 解封累加式分时段报工。
 *
 * 测试覆盖:
 * - UT-WR-B01: 5 分钟内重复提交 → 409
 * - UT-WR-B02: 超过 5 分钟再提交 → 放行
 * - UT-WR-B03: 校验调用 CreatedAtAfter 方法且 cutoff ≈ now-5min, 不再用 ReportDate 方法
 *
 * @author Cretas Team
 * @since 2026-06-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkReportingServiceImpl 旧报工路径 5 分钟窗口去重 (单元B REQ-17)")
class WorkReportingServiceImplTest {

    private static final String FACTORY_ID = "F001";
    private static final Long WORKER_ID = 20L;
    private static final Long BATCH_ID = 7001L;
    private static final String REPORT_TYPE = "PROGRESS";

    @Mock
    private ProductionReportRepository reportRepository;

    @Mock
    private BatchWorkSessionRepository batchWorkSessionRepository;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WorkReportingServiceImpl service;

    @Captor
    private ArgumentCaptor<LocalDateTime> cutoffCaptor;

    // ==================== Helper builders ====================

    private WorkReportSubmitRequest validRequest() {
        WorkReportSubmitRequest req = new WorkReportSubmitRequest();
        req.setBatchId(BATCH_ID);
        req.setReportType(REPORT_TYPE);
        req.setReportMode(ReportMode.MODE_1);
        req.setReportDate(LocalDate.now());
        req.setOutputQuantity(new BigDecimal("50"));
        req.setTotalWorkMinutes(60);
        return req;
    }

    @Nested
    @DisplayName("5 分钟窗口去重 submitReport")
    class FiveMinuteDedupTests {

        @Test
        @DisplayName("UT-WR-B01: 5 分钟内已有同批次报工 → 抛 409 新文案")
        void submitReport_recentDuplicateWithin5Min_throws409() {
            WorkReportSubmitRequest req = validRequest();

            // 跨租户守卫前置: batchId 须属本厂 (新增 guard), happy/dedup 路径须 stub 存在的同厂批次
            when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                    .thenReturn(java.util.Optional.of(
                            ProductionBatch.builder().id(BATCH_ID).factoryId(FACTORY_ID).build()));
            when(reportRepository
                    .existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(
                            eq(FACTORY_ID), eq(WORKER_ID), eq(BATCH_ID), eq(REPORT_TYPE), any(LocalDateTime.class)))
                    .thenReturn(true);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.submitReport(FACTORY_ID, WORKER_ID, req));

            assertEquals(409, ex.getCode());
            assertTrue(ex.getMessage().contains("5分钟内已对该批次提交过报工"),
                    "应使用 5 分钟窗口新文案, 实际: " + ex.getMessage());

            // 不应保存新报工
            verify(reportRepository, never()).save(any(ProductionReport.class));
            // 不再使用旧的 ReportDate 全天去重方法
            verify(reportRepository, never())
                    .existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndReportDateAndDeletedAtIsNull(
                            anyString(), anyLong(), anyLong(), anyString(), any(LocalDate.class));
        }

        @Test
        @DisplayName("UT-WR-B02: 超过 5 分钟前的报工 (CreatedAtAfter 返 false) → 放行累加, 正常创建")
        void submitReport_noRecentDuplicate_createsReport() {
            WorkReportSubmitRequest req = validRequest();
            ProductionReport saved = ProductionReport.builder()
                    .id(900L)
                    .factoryId(FACTORY_ID)
                    .workerId(WORKER_ID)
                    .batchId(BATCH_ID)
                    .reportType(REPORT_TYPE)
                    .outputQuantity(new BigDecimal("50"))
                    .build();

            when(reportRepository
                    .existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(
                            eq(FACTORY_ID), eq(WORKER_ID), eq(BATCH_ID), eq(REPORT_TYPE), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);
            // batchId != null → 跨租户 guard + updateBatchActualQuantity 查批次. 须 stub 存在的同厂批次
            // (plannedQuantity null → checkAndCompleteBatch 提前返回, 不触发完成事件)。
            when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                    .thenReturn(java.util.Optional.of(
                            ProductionBatch.builder().id(BATCH_ID).factoryId(FACTORY_ID).build()));

            WorkReportResponse resp = service.submitReport(FACTORY_ID, WORKER_ID, req);

            assertNotNull(resp);
            assertEquals(900L, resp.getId());

            // 第二次 >5min 的报工应被保存 (累加放行)
            verify(reportRepository).save(any(ProductionReport.class));
        }

        @Test
        @DisplayName("UT-WR-B03: cutoff ≈ now-5min, 调用 CreatedAtAfter 而非 ReportDate 方法")
        void submitReport_usesCreatedAtAfterWithCutoffNearNowMinus5Min() {
            WorkReportSubmitRequest req = validRequest();
            ProductionReport saved = ProductionReport.builder().id(901L).factoryId(FACTORY_ID).build();

            LocalDateTime before = LocalDateTime.now().minusMinutes(5);

            when(reportRepository
                    .existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(
                            eq(FACTORY_ID), eq(WORKER_ID), eq(BATCH_ID), eq(REPORT_TYPE), cutoffCaptor.capture()))
                    .thenReturn(false);
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);
            when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                    .thenReturn(java.util.Optional.of(
                            ProductionBatch.builder().id(BATCH_ID).factoryId(FACTORY_ID).build()));

            service.submitReport(FACTORY_ID, WORKER_ID, req);

            LocalDateTime after = LocalDateTime.now().minusMinutes(5);
            LocalDateTime cutoff = cutoffCaptor.getValue();
            assertNotNull(cutoff, "cutoff 不应为 null");
            // cutoff 应落在 [before, after] 区间内 (≈ now-5min, 允许执行耗时)
            assertFalse(cutoff.isBefore(before.minusSeconds(5)),
                    "cutoff 不应早于 now-5min-5s, 实际: " + cutoff);
            assertFalse(cutoff.isAfter(after.plusSeconds(5)),
                    "cutoff 不应晚于 now-5min+5s, 实际: " + cutoff);

            // 显式确认旧的全天 ReportDate 方法未被调用
            verify(reportRepository, never())
                    .existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndReportDateAndDeletedAtIsNull(
                            anyString(), anyLong(), anyLong(), anyString(), any(LocalDate.class));
        }

        @Test
        @DisplayName("UT-WR-B05: 跨租户 batchId (别厂批次) → 404, 不持久化报工")
        void submitReport_crossTenantBatch_throws404() {
            WorkReportSubmitRequest req = validRequest();
            // batchId 不属于当前工厂 → findByIdAndFactoryId 返 empty → guard 抛 EntityNotFound(404)
            when(productionBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                    .thenReturn(java.util.Optional.empty());

            com.cretas.aims.exception.EntityNotFoundException ex = assertThrows(
                    com.cretas.aims.exception.EntityNotFoundException.class,
                    () -> service.submitReport(FACTORY_ID, WORKER_ID, req));
            assertTrue(ex.getMessage().contains("不属于当前工厂") || ex.getMessage().contains("不存在"),
                    "应提示批次不存在/不属于当前工厂, 实际: " + ex.getMessage());
            // 跨租户报工不得持久化任何行
            verify(reportRepository, never()).save(any(ProductionReport.class));
        }

        @Test
        @DisplayName("UT-WR-B04: batchId 为空 → 走 BatchIdIsNull 的 CreatedAtAfter 分支")
        void submitReport_nullBatch_usesNullBranchCreatedAtAfter() {
            WorkReportSubmitRequest req = validRequest();
            req.setBatchId(null);
            ProductionReport saved = ProductionReport.builder().id(902L).factoryId(FACTORY_ID).build();

            when(reportRepository
                    .existsByFactoryIdAndWorkerIdAndReportTypeAndCreatedAtAfterAndBatchIdIsNullAndDeletedAtIsNull(
                            eq(FACTORY_ID), eq(WORKER_ID), eq(REPORT_TYPE), any(LocalDateTime.class)))
                    .thenReturn(false);
            when(reportRepository.save(any(ProductionReport.class))).thenReturn(saved);

            WorkReportResponse resp = service.submitReport(FACTORY_ID, WORKER_ID, req);

            assertNotNull(resp);
            verify(reportRepository).save(any(ProductionReport.class));
            verify(reportRepository, never())
                    .existsByFactoryIdAndWorkerIdAndReportTypeAndReportDateAndBatchIdIsNullAndDeletedAtIsNull(
                            anyString(), anyLong(), anyString(), any(LocalDate.class));
        }
    }
}
