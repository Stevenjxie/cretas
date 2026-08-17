package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驳回一条报工，必须把它的库存影响一起退回来。
 *
 * <p><b>2026-08-17 生产实测（web-admin「报工审批」页）</b>：
 * 提交第②道产出 2.5kg → 半成品建行 {@code 2.50 AVAILABLE}；文员点<b>驳回</b> →
 * 报工变 {@code REJECTED}，而半成品行<b>纹丝不动</b>，任务仍是 {@code COMPLETED 2.5000}。
 * <b>下一道照样能领走文员刚判为无效的那 2.5 kg。</b>
 *
 * <p>库存在<b>提交</b>那一刻就进账（不等审批），这个设计能成立的前提就是<b>核对能纠正它</b>。
 * 纠正不了，实时入库就变成不可追溯。
 *
 * <p>⚠️ 这里钉的是<b>接线</b>与<b>事务安全</b>，不是库存算术 ——
 * 「退多少」由 {@code WipInventoryServiceImpl.reverseReportPosting} 负责。
 * 分开钉是有意的：本仓反复栽在「测了 helper 不是测接线」上。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RejectReversesInventoryTest {

    private static final String FACTORY = "F006";
    private static final Long REPORT_ID = 23814L;
    private static final Long TASK_ID = 1786L;

    @Mock private ProductionReportRepository reportRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private UserRepository userRepository;

    private ProcessWorkReportingServiceImpl service;
    private ProductionReport report;
    private WorkProcessTask task;

    @BeforeEach
    void setUp() {
        service = new ProcessWorkReportingServiceImpl(
                reportRepository, workProcessRepository, productTypeRepository,
                attachmentRepository, workProcessTaskRepository, wipInventoryService,
                userRepository);

        report = new ProductionReport();
        report.setId(REPORT_ID);
        report.setFactoryId(FACTORY);
        report.setWorkProcessTaskId(TASK_ID);
        report.setApprovalStatus("PENDING");
        report.setReportKind("OUTPUT");
        report.setOutputQuantity(new BigDecimal("2.5"));

        task = new WorkProcessTask();
        task.setId(TASK_ID);
        task.setFactoryId(FACTORY);
        task.setProductionBatchId(10759L);
        task.setProcessOrder(2);

        // loadPendingReport 走 findById + 校验 factoryId/PENDING
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));
        when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(task));
        when(reportRepository.save(any(ProductionReport.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("🔴 驳回时必须冲销这条报工的库存 —— ⛔ 不能只翻状态位")
    void rejectReversesInventory() {
        service.rejectReport(FACTORY, REPORT_ID, "数量填错了", 1310L);

        verify(wipInventoryService).reverseReportPosting(FACTORY, report, task, 1310L);
        assertThat(report.getApprovalStatus()).as("驳回本身也要生效").isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("🔴 冲销失败(下游已领用)时, ⛔ 报工不许留下「已驳回但库存还在」的状态")
    void rejectDoesNotPersistWhenReversalRefused() {
        doThrow(new BusinessException(409, "这笔产出已被下一道领用 2.5kg, 不能直接驳回"))
                .when(wipInventoryService).reverseReportPosting(anyString(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.rejectReport(FACTORY, REPORT_ID, "数量填错了", 1310L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被下一道领用");

        assertThat(report.getApprovalStatus())
                .as("冲销被拒时状态必须还是 PENDING —— 否则库存没退、报工却显示已驳回")
                .isEqualTo("PENDING");
        verify(reportRepository, never()).save(any(ProductionReport.class));
    }

    @Test
    @DisplayName("⛔ 阴性对照: 找不到对应工序任务时不调冲销, 也不因此把驳回挡死")
    void noTaskMeansNoReversalButRejectStillWorks() {
        report.setWorkProcessTaskId(null);

        service.rejectReport(FACTORY, REPORT_ID, "工序对不上", 1310L);

        verify(wipInventoryService, never()).reverseReportPosting(any(), any(), any(), any());
        assertThat(report.getApprovalStatus()).isEqualTo("REJECTED");
    }
}
