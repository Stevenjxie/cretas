package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 「逐道报工做完了没有」有<b>两条腿</b>，结单闸不能只认其中一条。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 判据：「同一笔业务，在 App 上录进去，web 端立刻看得见、自己算得出、还改得动」。
 *
 * 实测 F006 PLAN-1786954657305（黄油鸡 80 盒，workflow 计划）：
 * <pre>
 * 工人在 App 把两道工都报完   → 报工 23832(60kg) / 23833(75盒)，任务均 COMPLETED，审批均 APPROVED
 * 文员在 web 点「核对结单」   → 409 WORKFLOW_REPORTING_REQUIRED
 *                              「workflow 计划必须先完成并正式提交逐道报工」
 * </pre>
 *
 * 因为这道闸读的是 {@code process_sheet_rows}（<b>web 逐道录入</b>那张表），而 App 报工写的是
 * {@code production_reports} + {@code work_process_tasks} + 半成品台账 —— <b>两端结构性不相交</b>。
 * ⇒ 纯用 App 报完的 workflow 计划<b>永远结不了单</b>，这正是判据里那种「为什么点不动」。
 *
 * <h2>口径：意图不变，量的对象改对</h2>
 * 闸的意图是「逐道报工做完了没有」，不是「web 那张表有没有行」——量错了对象（形态 A）。
 * 补上另一条腿之后<b>不放松</b>：App 腿要求每一道工序任务都 COMPLETED
 * （⛔ 不是 anyMatch，否则只报了第一道也能结单）。
 */
class WorkflowSettlementReportingLegsTest {

    private static final String F = "F006";
    private static final String PLAN_ID = "plan-butter-chicken";
    private static final Long BATCH = 10761L;

    private ProductionPlanServiceImpl service;
    private ProcessSheetRowRepository rowRepo;
    private WorkProcessTaskRepository taskRepo;
    private ProductionBatchRepository batchRepo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        rowRepo = mock(ProcessSheetRowRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        batchRepo = mock(ProductionBatchRepository.class);

        when(rowRepo.findByFactoryIdAndPlanId(anyString(), anyString())).thenReturn(List.of());
        ProductionBatch b = new ProductionBatch();
        b.setId(BATCH);
        when(batchRepo.findByFactoryIdAndProductionPlanId(eq(F), eq(PLAN_ID))).thenReturn(List.of(b));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(eq(F), eq(BATCH)))
                .thenReturn(List.of());

        ReflectionTestUtils.setField(service, "processSheetRowRepository", rowRepo);
        ReflectionTestUtils.setField(service, "workProcessTaskRepository", taskRepo);
        ReflectionTestUtils.setField(service, "productionBatchRepository", batchRepo);
    }

    private static ProductionPlan workflowPlan() {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(F);
        p.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        return p;
    }

    private static WorkProcessTask task(int order, WorkProcessTask.Status status) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId((long) (1780 + order));
        t.setProcessOrder(order);
        t.setStatus(status);
        return t;
    }

    private static ProcessSheetRow submittedRow() {
        ProcessSheetRow r = new ProcessSheetRow();
        r.setRowPayload("{\"x\":1}");
        r.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        return r;
    }

    private void gate() {
        ReflectionTestUtils.invokeMethod(
                service, "ensureWorkflowSettlementUsesSubmittedReports", F, workflowPlan());
    }

    private void tasks(WorkProcessTask... ts) {
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(eq(F), eq(BATCH)))
                .thenReturn(List.of(ts));
    }

    @Test
    @DisplayName("阳性对照: 一道工序都没报 → 必须拦 (否则下面的放行断言可能只是因为闸从不拦)")
    void nothingReportedIsBlocked() {
        BusinessException e = assertThrows(BusinessException.class, this::gate);
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("逐道报工"), e.getMessage());
        // 下一步要把【两条腿】都说出来, 否则 App 报工的人不知道该去哪
        assertTrue(String.valueOf(e.getActionHint()).contains("App"),
                "提示只说了 web 那条路, App 上报完工的人会一直点不动: " + e.getActionHint());
    }

    @Test
    @DisplayName("web 腿: 有一行已提交的逐道录入 → 放行 (老行为不许被这次改动带走)")
    void webProcessSheetRowStillPasses() {
        when(rowRepo.findByFactoryIdAndPlanId(eq(F), eq(PLAN_ID))).thenReturn(List.of(submittedRow()));
        assertDoesNotThrow(this::gate);
    }

    @Test
    @DisplayName("🔴 App 腿: 工序任务全部 COMPLETED → 放行 (这就是 prod 上那个死结)")
    void appSideCompletedTasksPass() {
        tasks(task(1, WorkProcessTask.Status.COMPLETED), task(2, WorkProcessTask.Status.COMPLETED));
        assertDoesNotThrow(this::gate,
                "App 把每道工都报完了还结不了单 —— 文员点「核对结单」永远 409");
    }

    @Test
    @DisplayName("🔴 不许放松: 只报完第一道 → 仍然拦 (App 腿要求【全部】完成)")
    void partiallyReportedIsStillBlocked() {
        tasks(task(1, WorkProcessTask.Status.COMPLETED), task(2, WorkProcessTask.Status.PENDING));
        BusinessException e = assertThrows(BusinessException.class, this::gate,
                "只报了第一道就放行 —— 闸被放松成 anyMatch 了");
        assertEquals(409, e.getCode());
    }

    @Test
    @DisplayName("🔴 一条任务都没有 ≠ 报完了 —— 量不到就不放行 (硬约束 4)")
    void noTasksAtAllIsNotTreatedAsDone() {
        tasks();   // 空
        assertThrows(BusinessException.class, this::gate,
                "把「一条任务都没有」当成了「报完了」");
    }
}
