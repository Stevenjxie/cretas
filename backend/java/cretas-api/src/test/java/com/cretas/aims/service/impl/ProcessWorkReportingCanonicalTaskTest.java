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
