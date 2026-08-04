package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessWorkReportingCanonicalTaskTest {

    @Mock private ProductionReportRepository reportRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private WipInventoryService wipInventoryService;

    private ProcessWorkReportingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessWorkReportingServiceImpl(
                reportRepository,
                workProcessRepository,
                productTypeRepository,
                attachmentRepository,
                workProcessTaskRepository,
                wipInventoryService);
    }

    @Test
    void byTaskReadsOnlyCanonicalWorkProcessTaskId() {
        ProductionReport report = report(9L, 42L, "APPROVED", "12.5");
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 42L))
                .thenReturn(List.of(report));

        List<Map<String, Object>> result = service.getReportsByTask("F006", "WPT-42");

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.get("processTaskId")).isEqualTo("42");
            assertThat(item.get("workProcessTaskId")).isEqualTo(42L);
        });
        verify(reportRepository)
                .findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 42L);
    }

    @Test
    void workerSummaryAggregatesCanonicalTaskReports() {
        ProductionReport approved = report(9L, 42L, "APPROVED", "12.5");
        ProductionReport pending = report(9L, 42L, "PENDING", "2.5");
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 42L))
                .thenReturn(List.of(approved, pending));

        List<WorkProcessTaskDTO.WorkerSummary> result =
                service.getWorkerSummaryByTask("F006", "42");

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.getWorkerId()).isEqualTo(9L);
            assertThat(summary.getTotalQuantity()).isEqualByComparingTo("15.0");
            assertThat(summary.getApprovedQuantity()).isEqualByComparingTo("12.5");
            assertThat(summary.getPendingQuantity()).isEqualByComparingTo("2.5");
            assertThat(summary.getReportCount()).isEqualTo(2);
        });
    }

    /**
     * 两个<b>不同</b>工人报同一道工序 → 必须聚合成两行, 各自的量不得互相串。
     *
     * <p>此前本类只用单个 workerId=9 测「同一人报两次」, 于是「把所有报工归到同一行」
     * 或「量算到别人头上」这类缺陷暴露不出来 —— 而 {@code WorkerSummary} 的职责恰恰是<b>按工人分组</b>。
     *
     * <p>补这条的直接原因 (2026-08-04, PR#2274): 入口查询放开了「未指派工序对同厂操作员可见」
     * 的兜底 —— prod 实测指派配置从未被填过 (assigned_to 全 null), 严格相等过滤会让操作员恒见空列表。
     * 放开后<b>多个操作员会同时看到同一道未指派工序并各自报工</b>, 这条路径从「几乎走不到」变成常态。
     * 鉴权侧本就允许 ({@code ReportAuthGuard} 对空允许集合 fail-open, 见 T121-06),
     * 多次报工累加也是设计 ({@code isFirstReportForTask}) —— 缺的只是<b>按工人分摊</b>的断言。
     */
    @Test
    void workerSummarySeparatesDistinctWorkersOnSameUnassignedTask() {
        ProductionReport alice = report(9L, 42L, "APPROVED", "12.5");
        ProductionReport bob = report(11L, 42L, "APPROVED", "4.0");
        ProductionReport bobPending = report(12L, 42L, "PENDING", "1.5");
        bobPending.setWorkerId(11L);   // 同一人的第二条 (id 仍唯一)
        when(reportRepository.findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull("F006", 42L))
                .thenReturn(List.of(alice, bob, bobPending));

        List<WorkProcessTaskDTO.WorkerSummary> result =
                service.getWorkerSummaryByTask("F006", "42");

        assertThat(result).hasSize(2);

        WorkProcessTaskDTO.WorkerSummary s9 = result.stream()
                .filter(s -> s.getWorkerId().equals(9L)).findFirst().orElseThrow();
        WorkProcessTaskDTO.WorkerSummary s11 = result.stream()
                .filter(s -> s.getWorkerId().equals(11L)).findFirst().orElseThrow();

        // 9 号只有自己那 12.5, 不得把 11 号的 4.0/1.5 算进来
        assertThat(s9.getTotalQuantity()).isEqualByComparingTo("12.5");
        assertThat(s9.getApprovedQuantity()).isEqualByComparingTo("12.5");
        assertThat(s9.getPendingQuantity()).isEqualByComparingTo("0");
        assertThat(s9.getReportCount()).isEqualTo(1);

        // 11 号两条各自归位
        assertThat(s11.getTotalQuantity()).isEqualByComparingTo("5.5");
        assertThat(s11.getApprovedQuantity()).isEqualByComparingTo("4.0");
        assertThat(s11.getPendingQuantity()).isEqualByComparingTo("1.5");
        assertThat(s11.getReportCount()).isEqualTo(2);
    }

    private ProductionReport report(Long workerId, Long taskId, String status, String quantity) {
        ProductionReport report = new ProductionReport();
        report.setId(workerId + taskId);
        report.setFactoryId("F006");
        report.setWorkerId(workerId);
        report.setReporterName("测试工人");
        report.setWorkProcessTaskId(taskId);
        report.setProcessCategory("分割");
        report.setProductName("测试产品");
        report.setApprovalStatus(status);
        report.setOutputQuantity(new BigDecimal(quantity));
        return report;
    }
}
