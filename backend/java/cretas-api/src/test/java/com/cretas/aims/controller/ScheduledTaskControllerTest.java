package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Length-validation tests for {@link ScheduledTaskController} — AUD-5 B-A3 sister sweep
 * (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link DynamicSchedulerService#createTask}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Mirrors PR #48 (CanvasAlertController.ruleName length pre-check) and PR #49 / PR #71
 * (PricingStrategyController length pre-check) patterns.
 *
 * <p>Tests are pure {@code @ExtendWith(MockitoExtension.class)} — no Spring context —
 * to isolate the validation code path. The service layer is mocked out; we never expect
 * it to be invoked when validation fails (verified via {@code verify(...).never()}).
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskController AUD-5 B-A3 length pre-check")
class ScheduledTaskControllerTest {

    @Mock DynamicSchedulerService dynamicSchedulerService;
    @Mock ScheduledTaskRepository taskRepository;
    @Mock ScheduledTaskRunLogRepository runLogRepository;
    @Mock ApplicationContext applicationContext;
    @InjectMocks ScheduledTaskController controller;

    // ==================== AUD-5 B-A3: taskCode > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: taskCode > 100 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongTaskCodeRejected() {
        // PG column: scheduled_tasks.task_code VARCHAR(100). Pre-fix, a 101-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException →
        // generic 409 "数据处理异常". Pre-check delivers specific 400 with current vs
        // max length so user can immediately spot the issue.
        ScheduledTask task = newBaseTask();
        task.setTaskCode("X".repeat(101));   // 101 chars, max is 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(task),
                "101-char taskCode 必须 reject");

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("任务代码"),
                "message must reference the field name: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100"),
                "message must include the limit (100): " + ex.getMessage());
        assertTrue(ex.getMessage().contains("101"),
                "message must include the current length (101): " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("taskCode", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at taskCode");
        verify(dynamicSchedulerService, never()).createTask(any());
    }

    // ==================== AUD-5 B-A3: taskName > 255 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: taskName > 255 字符 → 400 with 4-in-1 hint")
    void testLongTaskNameRejected() {
        // PG column: scheduled_tasks.task_name VARCHAR(255).
        ScheduledTask task = newBaseTask();
        task.setTaskName("名".repeat(256));   // 256 chars, max is 255

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(task));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("任务名称"));
        assertTrue(ex.getMessage().contains("255"));
        assertTrue(ex.getMessage().contains("256"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("taskName", ex.getHintTarget());
        verify(dynamicSchedulerService, never()).createTask(any());
    }

    // ==================== AUD-5 B-A3: cronExpression > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: cronExpression > 100 字符 → 400 with 4-in-1 hint")
    void testLongCronExpressionRejected() {
        // PG column: scheduled_tasks.cron_expression VARCHAR(100).
        // A legitimate Spring 6-field cron is ~15-25 chars; > 100 chars indicates
        // either a malformed expression or an attack vector (e.g. exec evasion).
        ScheduledTask task = newBaseTask();
        task.setCronExpression("0 ".repeat(60));   // 120 chars, max is 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(task));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Cron 表达式"),
                "message must reference cron expression: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("120"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("cronExpression", ex.getHintTarget());
        verify(dynamicSchedulerService, never()).createTask(any());
    }

    // ==================== AUD-5 B-A3: handlerBeanName > 255 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: handlerBeanName > 255 字符 → 400 with 4-in-1 hint")
    void testLongHandlerBeanNameRejected() {
        // PG column: scheduled_tasks.handler_bean_name VARCHAR(255).
        ScheduledTask task = newBaseTask();
        task.setHandlerBeanName("h".repeat(256));   // 256 chars, max is 255

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(task));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Handler Bean 名称"),
                "message must reference handler bean: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("255"));
        assertTrue(ex.getMessage().contains("256"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("handlerBeanName", ex.getHintTarget());
        verify(dynamicSchedulerService, never()).createTask(any());
    }

    // ==================== Boundary tests (exactly at limit must be accepted) ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: taskCode at exactly 100 字符 accepted (inclusive)")
    void testMaxLengthTaskCodeAccepted() {
        ScheduledTask task = newBaseTask();
        task.setTaskCode("X".repeat(100));         // exactly at limit
        task.setTaskName("名".repeat(255));         // exactly at limit
        task.setCronExpression("0 0 9 1 * ?");    // legitimate cron
        task.setHandlerBeanName("h".repeat(255));  // exactly at limit

        when(dynamicSchedulerService.createTask(any(ScheduledTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<ScheduledTask> resp = controller.create(task);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).createTask(any(ScheduledTask.class));
    }

    // ==================== Rule 16: update path mirrors create ====================

    @Test
    @DisplayName("Rule 16: update applies same AUD-5 taskCode validation as create")
    void testUpdateAppliesTaskCodeValidation() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask patch = new ScheduledTask();
        patch.setTaskCode("X".repeat(200));   // way over 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(taskId, patch),
                "Rule 16: update path 也必须 reject");
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("任务代码"));
        assertEquals("taskCode", ex.getHintTarget());
        // Service was never called — validation fires first.
        verify(dynamicSchedulerService, never()).updateTask(eq(taskId), any());
    }

    @Test
    @DisplayName("Rule 16: update applies same AUD-5 taskName validation as create")
    void testUpdateAppliesTaskNameValidation() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask patch = new ScheduledTask();
        patch.setTaskName("名".repeat(300));   // way over 255

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(taskId, patch));
        assertEquals(400, ex.getCode());
        assertEquals("taskName", ex.getHintTarget());
        verify(dynamicSchedulerService, never()).updateTask(eq(taskId), any());
    }

    @Test
    @DisplayName("Rule 16: update PATCH semantics — null field tolerated (don't touch)")
    void testUpdateTolerantOfNullFields() {
        // Partial-update semantics: caller may PATCH only one field. Null fields
        // mean "don't change this", so they must NOT trigger length validation.
        UUID taskId = UUID.randomUUID();
        ScheduledTask patch = new ScheduledTask();
        // All length-checked fields left null — only `enabled` set, hypothetically.
        patch.setEnabled(false);

        when(dynamicSchedulerService.updateTask(eq(taskId), any(ScheduledTask.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ApiResponse<ScheduledTask> resp = controller.update(taskId, patch);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).updateTask(eq(taskId), any(ScheduledTask.class));
    }

    // ==================== Test helpers ====================

    /**
     * Build a minimal-valid task — within all length limits and with a syntactically
     * legitimate cron expression. Mirrors a real cron task entry (monthly cache flush
     * at 09:00 on the 1st day of each month).
     */
    private static ScheduledTask newBaseTask() {
        ScheduledTask t = new ScheduledTask();
        t.setFactoryId("F001");
        t.setTaskCode("MONTHLY_CACHE_EVICTION");
        t.setTaskName("月度缓存清理");
        t.setCronExpression("0 0 9 1 * ?");
        t.setHandlerBeanName("cacheEvictionTaskHandler");
        t.setEnabled(true);
        return t;
    }
}
