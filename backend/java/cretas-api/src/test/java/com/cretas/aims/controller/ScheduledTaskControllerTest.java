package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
                () -> controller.create(req(), task),
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
                () -> controller.create(req(), task));

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
                () -> controller.create(req(), task));

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
                () -> controller.create(req(), task));

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

        ApiResponse<ScheduledTask> resp = controller.create(req(), task);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).createTask(any(ScheduledTask.class));
    }

    // ==================== Rule 16: update path mirrors create ====================

    @Test
    @DisplayName("Rule 16: update applies same AUD-5 taskCode validation as create")
    void testUpdateAppliesTaskCodeValidation() {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskCode", "X".repeat(200));   // way over 100

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(req(), taskId, body),
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
        Map<String, Object> body = new HashMap<>();
        body.put("taskName", "名".repeat(300));   // way over 255

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(req(), taskId, body));
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
        Map<String, Object> body = new HashMap<>();
        // All length-checked fields left null — only `enabled` set, hypothetically.
        body.put("enabled", false);

        when(dynamicSchedulerService.updateTask(eq(taskId), any(ScheduledTask.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ApiResponse<ScheduledTask> resp = controller.update(req(), taskId, body);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).updateTask(eq(taskId), any(ScheduledTask.class));
    }

    // ==================== AUD-4 (PR #94 follow-up): explicit version check ====================

    @Test
    @DisplayName("AUD-4: PUT with stale version (req v=0, db v=1) → 409 with refresh hint")
    void testStaleVersionRejectedWith409() {
        // Edge audit 2026-05-20 finding repro: two admins open the same task in 2 tabs,
        // both see v=1. Admin A saves first (DB now v=2). Admin B clicks save with stale
        // v=1 snapshot — without this check the DynamicSchedulerService would silently
        // overwrite Admin A's work because updateTask() does its own findById which reads
        // the current v=2. The explicit check fires before dispatching to the service so
        // 409 surfaces instead.
        UUID taskId = UUID.randomUUID();
        ScheduledTask existing = newBaseTask();
        existing.setId(taskId);
        existing.setVersion(1L);   // DB has been updated by Admin A
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0L);   // stale client snapshot
        body.put("taskName", "覆盖尝试");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(req(), taskId, body),
                "stale version 必须 reject 不允许 silent overwrite");

        assertEquals(409, ex.getCode(), "must be 409 CONFLICT, not silent 200");
        assertTrue(ex.getMessage().contains("数据已被其他用户修改"),
                "message must surface the lost-update semantics: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("v=1") || ex.getMessage().contains("服务端 v=1"),
                "message must surface server version (1): " + ex.getMessage());
        assertTrue(ex.getMessage().contains("v=0") || ex.getMessage().contains("客户端 v=0"),
                "message must surface client version (0): " + ex.getMessage());
        assertEquals("请刷新页面查看最新数据后再编辑", ex.getActionHint(),
                "4-in-1 UX (a): actionHint must direct user to refresh");
        assertEquals("warning", ex.getSeverity(),
                "4-in-1 UX (c): severity warning for sticky toast");
        // CRITICAL: dispatching service NEVER called — stale write blocked before overwriting
        verify(dynamicSchedulerService, never()).updateTask(eq(taskId), any(ScheduledTask.class));
    }

    @Test
    @DisplayName("AUD-4: PUT with current version (req v=1, db v=1) → 200 happy path (regression)")
    void testCurrentVersionAccepted() {
        // Companion happy path — when client is up-to-date, the dispatch to
        // DynamicSchedulerService.updateTask proceeds normally. Guards against the
        // version check accidentally blocking legitimate updates.
        UUID taskId = UUID.randomUUID();
        ScheduledTask existing = newBaseTask();
        existing.setId(taskId);
        existing.setVersion(1L);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 1L);   // matches DB
        body.put("taskName", "新名字");

        when(dynamicSchedulerService.updateTask(eq(taskId), any(ScheduledTask.class)))
                .thenAnswer(inv -> {
                    ScheduledTask updated = newBaseTask();
                    updated.setId(taskId);
                    updated.setVersion(2L);   // version bumped on save
                    updated.setTaskName("新名字");
                    return updated;
                });

        ApiResponse<ScheduledTask> resp = controller.update(req(), taskId, body);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).updateTask(eq(taskId), any(ScheduledTask.class));
    }

    @Test
    @DisplayName("AUD-4: PUT without version (null) → 200 lenient (legacy clients keep working)")
    void testNullVersionLenientPassthrough() {
        // AUD-4 is a Lost Update fix — when clients DON'T send version they get the
        // pre-AUD-4 behavior (last-writer-wins). This lenient path lets AI Tool callers
        // (e.g. ScheduledTaskTool that doesn't carry version) keep working without
        // forcing a version round-trip. The repo lookup is also skipped (lazy) since
        // we have nothing to compare against.
        UUID taskId = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        // intentionally no version
        body.put("taskName", "leniency-test");

        when(dynamicSchedulerService.updateTask(eq(taskId), any(ScheduledTask.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        ApiResponse<ScheduledTask> resp = controller.update(req(), taskId, body);
        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        verify(dynamicSchedulerService).updateTask(eq(taskId), any(ScheduledTask.class));
        // Critical: when version is null we don't even need to load existing (lazy path)
        verify(taskRepository, never()).findById(taskId);
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

    /** 平台角色请求 — 绕过多租户守卫, 让现有长度/正常路径测试聚焦原逻辑。 */
    private static MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("role", "platform_admin");
        r.setAttribute("factoryId", "F001");
        return r;
    }

    // ==================== 🔒 多租户隔离守卫 (2026-06-17) ====================

    @Test
    @DisplayName("🔒 工厂级管理员不能为别家工厂创建定时任务 → 403")
    void testCrossFactoryCreateRejected() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("role", "factory_super_admin"); // 工厂级角色
        r.setAttribute("factoryId", "F001");
        ScheduledTask task = newBaseTask();
        task.setFactoryId("F999"); // 指向别家工厂

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(r, task), "跨工厂创建必须 403");

        assertEquals(403, ex.getCode(), "must be 403 cross-tenant");
        verify(dynamicSchedulerService, never()).createTask(any());
    }

    @Test
    @DisplayName("🔒 工厂级管理员改别家工厂的任务 → 403 (loadTaskGuarded)")
    void testCrossFactoryUpdateRejected() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask otherFactoryTask = newBaseTask();
        otherFactoryTask.setFactoryId("F999"); // task 属于别家
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(otherFactoryTask));

        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("role", "factory_super_admin");
        r.setAttribute("factoryId", "F001"); // caller 是 F001

        Map<String, Object> body = new HashMap<>();
        body.put("taskName", "试图篡改");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(r, taskId, body), "跨工厂改必须 403");

        assertEquals(403, ex.getCode());
        verify(dynamicSchedulerService, never()).updateTask(any(), any());
    }
}
