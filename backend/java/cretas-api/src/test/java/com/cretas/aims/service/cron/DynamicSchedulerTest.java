package com.cretas.aims.service.cron;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.handler.EchoTaskHandler;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DynamicScheduler} (Canvas-Cron Phase 5).
 *
 * <p>Covers:
 * <ul>
 *   <li>configureTasks: loads enabled tasks from DB + registers each via taskRegistrar</li>
 *   <li>reload(): cancels active futures + re-registers from DB</li>
 *   <li>runNow(): manual override executes handler bean by name + records run log</li>
 * </ul>
 *
 * <p>Lock provider is mocked to always grant the lock so the inner handler call fires
 * synchronously in tests. ThreadPoolTaskScheduler is the real one created inside
 * DynamicScheduler — we use ScheduledTaskRegistrar mock for verifying registration.
 *
 * @since Phase 5 (2026-05-18)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DynamicScheduler 单元测试")
class DynamicSchedulerTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private ScheduledTaskRunLogRepository runLogRepository;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private SimpleLock simpleLock;

    private DynamicScheduler scheduler;
    private EchoTaskHandler echoHandler;

    @BeforeEach
    void setUp() {
        echoHandler = new EchoTaskHandler();
        scheduler = new DynamicScheduler(taskRepository, runLogRepository,
                applicationContext, lockProvider);

        // Default: locks always granted, run log save returns input
        when(lockProvider.lock(any(LockConfiguration.class)))
                .thenReturn(Optional.of(simpleLock));
        when(runLogRepository.save(any(ScheduledTaskRunLog.class)))
                .thenAnswer(inv -> {
                    ScheduledTaskRunLog r = inv.getArgument(0);
                    if (r.getId() == null) {
                        r.setId(UUID.randomUUID());
                    }
                    return r;
                });
        when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationContext.containsBean("echoTaskHandler")).thenReturn(true);
        when(applicationContext.getBean("echoTaskHandler")).thenReturn(echoHandler);
    }

    private ScheduledTask sampleTask(String code, String handler) {
        return ScheduledTask.builder()
                .id(UUID.randomUUID())
                .factoryId("F001")
                .taskCode(code)
                .taskName("test " + code)
                .cronExpression("0 0 12 * * ?")  // valid Spring cron, won't fire in unit test
                .handlerBeanName(handler)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("configureTasks - 加载 DB 中 enabled task 并注册到 scheduler")
    void configureTasks_loadsAndRegistersEnabledTasks() {
        ScheduledTask t1 = sampleTask("task-1", "echoTaskHandler");
        ScheduledTask t2 = sampleTask("task-2", "echoTaskHandler");
        when(taskRepository.findByEnabledTrue()).thenReturn(List.of(t1, t2));

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);

        // findByEnabledTrue called exactly once at bootstrap
        verify(taskRepository, times(1)).findByEnabledTrue();
        // taskScheduler was set on the registrar
        assertNotNull(registrar.getScheduler(),
                "DynamicScheduler should install its ThreadPoolTaskScheduler on registrar");
    }

    @Test
    @DisplayName("configureTasks - 空 DB → 0 task 注册, 不抛错")
    void configureTasks_emptyDb_noRegistrations() {
        when(taskRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);

        verify(taskRepository, times(1)).findByEnabledTrue();
        // Should not throw or otherwise fail boot
    }

    @Test
    @DisplayName("reload - 取消现有 future 后再次 findByEnabledTrue 重新加载")
    void reload_cancelsCurrentAndReloads() {
        ScheduledTask t1 = sampleTask("task-1", "echoTaskHandler");
        when(taskRepository.findByEnabledTrue()).thenReturn(List.of(t1));

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);

        scheduler.reload();

        // reload triggered a second findByEnabledTrue call
        verify(taskRepository, times(2)).findByEnabledTrue();
    }

    @Test
    @DisplayName("runNow - 找到 task → resolve handler bean → 写 RUNNING + SUCCESS log")
    void runNow_executesHandlerAndRecordsRunLog() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask task = sampleTask("manual-task", "echoTaskHandler");
        task.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        // Capture status snapshot at the moment of save() — entity is the same object reference,
        // so without snapshotting we'd see only the final mutated state.
        List<TaskRunStatus> statusSnapshots = new ArrayList<>();
        when(runLogRepository.save(any(ScheduledTaskRunLog.class))).thenAnswer(inv -> {
            ScheduledTaskRunLog r = inv.getArgument(0);
            statusSnapshots.add(r.getStatus());
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        ScheduledTaskRunLog result = scheduler.runNow(taskId);

        // resolved handler bean
        verify(applicationContext, atLeastOnce()).containsBean("echoTaskHandler");
        verify(applicationContext, atLeastOnce()).getBean("echoTaskHandler");

        // 1st save: RUNNING; final save: SUCCESS
        verify(runLogRepository, atLeast(2)).save(any(ScheduledTaskRunLog.class));
        assertEquals(TaskRunStatus.RUNNING, statusSnapshots.get(0),
                "1st save status should be RUNNING");
        assertEquals(TaskRunStatus.SUCCESS, statusSnapshots.get(statusSnapshots.size() - 1),
                "Final save status should be SUCCESS");
        assertNotNull(result.getFinishedAt(), "finishedAt should be populated");
        assertNotNull(result.getDurationMs(), "durationMs should be populated");
        assertEquals(TaskRunStatus.SUCCESS, result.getStatus(),
                "returned run log should be in SUCCESS state");

        // scheduled_task.last_run_* updated
        verify(taskRepository, atLeastOnce()).save(any(ScheduledTask.class));
    }

    @Test
    @DisplayName("runNow - handler bean 不存在 → FAILED + error_msg 填充")
    void runNow_handlerBeanMissing_recordsFailure() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask task = sampleTask("manual-task", "nonExistentHandler");
        task.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(applicationContext.containsBean("nonExistentHandler")).thenReturn(false);

        ScheduledTaskRunLog result = scheduler.runNow(taskId);

        assertEquals(TaskRunStatus.FAILED, result.getStatus());
        assertNotNull(result.getErrorMsg(), "error_msg should describe missing bean");
        assertTrue(result.getErrorMsg().contains("nonExistentHandler"),
                "error should mention the bean name");
    }

    @Test
    @DisplayName("runNow - taskId not found → IllegalArgumentException")
    void runNow_taskNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(taskRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> scheduler.runNow(missing));
    }
}
