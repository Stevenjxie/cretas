package com.cretas.aims.service.cron;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试 for Canvas-Cron Phase 5 — Spring 实际拉起 + DynamicScheduler 调度 +
 * EchoTaskHandler 执行 + ScheduledTaskRunLog 写库.
 *
 * <p>用每 2 秒触发一次的 cron, 等待最多 12 秒, 验证至少有 1 个 SUCCESS run log 记录.
 *
 * <p>Test profile 的 H2 不跑 Flyway, shedlock 表不存在, 所以 override LockProvider 用
 * 进程内 in-memory 版本 (test 不需要跨进程协调).
 *
 * <p>慢测试 — IT 阶段才跑. 可通过 SKIP_INTEGRATION_TESTS=true 跳过.
 *
 * @since Phase 5 (2026-05-18)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(DynamicSchedulerIntegrationTest.TestLockConfig.class)
@ActiveProfiles("test")
@DisabledIfEnvironmentVariable(named = "SKIP_INTEGRATION_TESTS", matches = "true")
@DisplayName("DynamicScheduler 集成测试 — 全链路从 DB → 调度 → handler → run log")
class DynamicSchedulerIntegrationTest {

    /**
     * Test-only LockProvider — pure in-process, no shedlock DB table required.
     * Replaces the production JdbcTemplateLockProvider from {@code ShedLockConfig}.
     */
    @TestConfiguration
    static class TestLockConfig {
        private final ConcurrentMap<String, Long> locks = new ConcurrentHashMap<>();

        @Bean
        @Primary
        public LockProvider inMemoryLockProvider() {
            return (LockConfiguration cfg) -> {
                long now = System.currentTimeMillis();
                Long currentExpiry = locks.get(cfg.getName());
                if (currentExpiry != null && currentExpiry > now) {
                    return Optional.empty();
                }
                long expiry = now + cfg.getLockAtMostFor().toMillis();
                locks.put(cfg.getName(), expiry);
                return Optional.of(new SimpleLock() {
                    @Override
                    public void unlock() {
                        // For test: keep lockAtLeastFor honored, so re-fires within
                        // the same second don't double-trigger.
                        long releaseAt = System.currentTimeMillis()
                                + Math.max(0, cfg.getLockAtLeastFor().toMillis());
                        locks.put(cfg.getName(), releaseAt);
                    }
                    @Override
                    public Optional<SimpleLock> extend(java.time.Duration lockAtMostFor,
                                                       java.time.Duration lockAtLeastFor) {
                        return Optional.empty();
                    }
                });
            };
        }
    }

    @Autowired
    private DynamicSchedulerService dynamicSchedulerService;

    @Autowired
    private DynamicScheduler dynamicScheduler;

    @Autowired
    private ScheduledTaskRepository taskRepository;

    @Autowired
    private ScheduledTaskRunLogRepository runLogRepository;

    @Test
    @DisplayName("✅ 创建 cron='0/2 * * * * ?' task → 12s 内 run_log 至少 1 个 SUCCESS")
    void integrationTest_cronFiresAndProducesRunLog() throws Exception {
        // 1. Create task with every-2-second cron, echo handler
        ScheduledTask task = ScheduledTask.builder()
                .factoryId("F999")
                .taskCode("integration-echo-" + System.nanoTime())
                .taskName("Integration echo test")
                .cronExpression("0/2 * * * * ?")  // every 2 seconds, all minutes
                .handlerBeanName("echoTaskHandler")
                .enabled(true)
                .build();
        ScheduledTask saved = dynamicSchedulerService.createTask(task);
        assertThat(saved.getId()).isNotNull();
        UUID taskId = saved.getId();

        // 2. Poll up to 12s (every 500ms) for at least one SUCCESS run log
        boolean hasSuccess = false;
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            List<ScheduledTaskRunLog> logs = runLogRepository.findAll();
            hasSuccess = logs.stream()
                    .anyMatch(l -> taskId.equals(l.getTaskId())
                            && l.getStatus() == TaskRunStatus.SUCCESS);
            if (hasSuccess) break;
            Thread.sleep(500L);
        }

        try {
            assertThat(hasSuccess)
                    .as("Expected at least one SUCCESS run_log for task " + taskId
                            + " within 12s (cron every 2s)")
                    .isTrue();
        } finally {
            // 3. Cleanup
            try {
                dynamicSchedulerService.deleteTask(taskId);
            } catch (Exception e) {
                // best-effort
            }
        }
    }
}
