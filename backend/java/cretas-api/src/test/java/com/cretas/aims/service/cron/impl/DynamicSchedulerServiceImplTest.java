package com.cretas.aims.service.cron.impl;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicScheduler;
import com.cretas.aims.service.cron.TaskHandler;
import com.cretas.aims.service.cron.handler.EchoTaskHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DynamicSchedulerServiceImpl} (Canvas-Cron Phase 5).
 *
 * <p>Covers:
 * <ul>
 *   <li>createTask: validates cron + handler bean + uniqueness, saves, triggers reload</li>
 *   <li>updateTask: applies partial patch, reload only when scheduler-affecting field changes</li>
 *   <li>toggleTask: flip enabled flag, persist, reload</li>
 *   <li>deleteTask: soft delete via Repository.delete (@SQLDelete), reload</li>
 *   <li>runNow: delegate to DynamicScheduler.runNow + 404 if task missing</li>
 *   <li>Invalid cron / missing handler bean → BusinessException(400) with action hint</li>
 * </ul>
 *
 * @since Phase 5 (2026-05-18)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DynamicSchedulerServiceImpl 单元测试")
class DynamicSchedulerServiceImplTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private ScheduledTaskRunLogRepository runLogRepository;

    @Mock
    private DynamicScheduler dynamicScheduler;

    @Mock
    private ApplicationContext applicationContext;

    @InjectMocks
    private DynamicSchedulerServiceImpl service;

    private TaskHandler echoHandler;

    @BeforeEach
    void setUp() {
        echoHandler = new EchoTaskHandler();
        when(applicationContext.containsBean("echoTaskHandler")).thenReturn(true);
        when(applicationContext.getBean("echoTaskHandler")).thenReturn(echoHandler);
        when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> {
            ScheduledTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
    }

    private ScheduledTask buildTask(String code) {
        return ScheduledTask.builder()
                .factoryId("F001")
                .taskCode(code)
                .taskName("task " + code)
                .cronExpression("0 0 9 1 * ?")
                .handlerBeanName("echoTaskHandler")
                .enabled(true)
                .build();
    }

    // ==================== createTask ====================

    @Test
    @DisplayName("createTask - 合法 task → save + reload")
    void createTask_valid_savesAndReloads() {
        when(taskRepository.findByTaskCode("daily-report")).thenReturn(Optional.empty());

        ScheduledTask result = service.createTask(buildTask("daily-report"));

        assertNotNull(result.getId());
        assertEquals("daily-report", result.getTaskCode());
        verify(taskRepository).save(any(ScheduledTask.class));
        verify(dynamicScheduler).reload();
    }

    @Test
    @DisplayName("createTask - 无效 cron 表达式 → BusinessException(400)")
    void createTask_invalidCron_throws() {
        ScheduledTask t = buildTask("bad-cron");
        t.setCronExpression("invalid cron string");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(t));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("无效的 cron"),
                "Should mention invalid cron in message");
        verify(taskRepository, never()).save(any());
        verify(dynamicScheduler, never()).reload();
    }

    @Test
    @DisplayName("createTask - handler bean 不存在 → BusinessException(400)")
    void createTask_missingHandlerBean_throws() {
        when(applicationContext.containsBean("missingHandler")).thenReturn(false);
        ScheduledTask t = buildTask("missing-handler");
        t.setHandlerBeanName("missingHandler");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(t));
        assertEquals(400, ex.getCode());
        verify(taskRepository, never()).save(any());
        verify(dynamicScheduler, never()).reload();
    }

    @Test
    @DisplayName("createTask - bean 存在但未实现 TaskHandler → BusinessException(400)")
    void createTask_beanIsNotTaskHandler_throws() {
        when(applicationContext.containsBean("plainObject")).thenReturn(true);
        when(applicationContext.getBean("plainObject")).thenReturn("not a TaskHandler");
        ScheduledTask t = buildTask("plain-bean");
        t.setHandlerBeanName("plainObject");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(t));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("createTask - taskCode 重复 (同 scope) → BusinessException(409)")
    void createTask_duplicateTaskCode_throws() {
        ScheduledTask existing = buildTask("dup-code");
        existing.setId(UUID.randomUUID());
        when(taskRepository.findByTaskCode("dup-code")).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(buildTask("dup-code")));
        assertEquals(409, ex.getCode());
        verify(dynamicScheduler, never()).reload();
    }

    // ==================== updateTask ====================

    @Test
    @DisplayName("updateTask - cron 变化 → reload")
    void updateTask_cronChanged_triggersReload() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("upd-1");
        existing.setId(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        ScheduledTask patch = new ScheduledTask();
        patch.setCronExpression("0 0 10 * * ?");

        service.updateTask(id, patch);

        verify(dynamicScheduler).reload();
    }

    @Test
    @DisplayName("updateTask - 只改 taskName (非调度相关) → 不 reload")
    void updateTask_onlyTaskName_skipsReload() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("upd-2");
        existing.setId(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        ScheduledTask patch = new ScheduledTask();
        patch.setTaskName("新名称");

        service.updateTask(id, patch);

        verify(taskRepository).save(any(ScheduledTask.class));
        verify(dynamicScheduler, never()).reload();
    }

    @Test
    @DisplayName("updateTask - task not found → BusinessException(404)")
    void updateTask_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTask(id, new ScheduledTask()));
        assertEquals(404, ex.getCode());
        verify(dynamicScheduler, never()).reload();
    }

    // ==================== toggleTask ====================

    @Test
    @DisplayName("toggleTask - 启用→禁用 → save + reload")
    void toggleTask_disable_triggersReload() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("tog-1");
        existing.setId(id);
        existing.setEnabled(true);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        ScheduledTask result = service.toggleTask(id, false);

        assertFalse(result.getEnabled());
        verify(taskRepository).save(any(ScheduledTask.class));
        verify(dynamicScheduler).reload();
    }

    @Test
    @DisplayName("toggleTask - 已 disabled 再次 disable → no-op, 不 reload")
    void toggleTask_noOp_skipsReload() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("tog-2");
        existing.setId(id);
        existing.setEnabled(false);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        service.toggleTask(id, false);

        verify(taskRepository, never()).save(any(ScheduledTask.class));
        verify(dynamicScheduler, never()).reload();
    }

    // ==================== deleteTask ====================

    @Test
    @DisplayName("deleteTask - soft delete via Repository + reload")
    void deleteTask_softDeletesAndReloads() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("del-1");
        existing.setId(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deleteTask(id);

        verify(taskRepository).delete(existing);  // @SQLDelete on BaseEntity converts to UPDATE
        verify(dynamicScheduler).reload();
    }

    @Test
    @DisplayName("deleteTask - not found → BusinessException(404)")
    void deleteTask_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTask(id));
        assertEquals(404, ex.getCode());
        verify(dynamicScheduler, never()).reload();
    }

    // ==================== runNow ====================

    @Test
    @DisplayName("runNow - 委托到 DynamicScheduler")
    void runNow_delegatesToScheduler() {
        UUID id = UUID.randomUUID();
        ScheduledTask existing = buildTask("run-1");
        existing.setId(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(existing));

        ScheduledTaskRunLog log = ScheduledTaskRunLog.builder()
                .taskId(id)
                .status(TaskRunStatus.SUCCESS)
                .build();
        when(dynamicScheduler.runNow(id)).thenReturn(log);

        ScheduledTaskRunLog result = service.runNow(id);

        assertEquals(TaskRunStatus.SUCCESS, result.getStatus());
        verify(dynamicScheduler).runNow(id);
    }

    @Test
    @DisplayName("runNow - task not found → BusinessException(404)")
    void runNow_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.runNow(id));
        assertEquals(404, ex.getCode());
        verify(dynamicScheduler, never()).runNow(any());
    }

    // ==================== reload ====================

    @Test
    @DisplayName("reload - 委托到 DynamicScheduler")
    void reload_delegatesToScheduler() {
        service.reload();
        verify(dynamicScheduler).reload();
    }
}
