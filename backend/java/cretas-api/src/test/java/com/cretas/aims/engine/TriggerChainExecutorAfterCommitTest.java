package com.cretas.aims.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #929 族 backlog #2 (2026-06-16) — doomed-tx 防御: TriggerChainExecutor 监听器
 * 必须是 AFTER_COMMIT + REQUIRES_NEW (+ fallbackExecution=true)。
 *
 * <p>背景: 旧 {@code @EventListener} 在发布者 (e.g. createReceiveRecord) 的同一物理事务内
 * 同步执行。chain step 里的工具若做内层 DB 写并把事务标 rollback-only, 即便本类吞掉异常,
 * 也无法清除该标记 → 发布者 commit 抛 {@code UnexpectedRollbackException} → doom。
 *
 * <p>改 AFTER_COMMIT 让 chain 在发布者事务提交后才跑 (不可能再 doom 它); REQUIRES_NEW 让
 * chain 跑在独立事务; fallbackExecution=true 保证无事务上下文发布的事件 (e.g.
 * SalesOrderCreatedEvent) 仍能触发 (默认会被静默丢弃)。
 *
 * <p>这是 build-time 守卫: 任何把监听器改回 {@code @EventListener} (同步入发布者事务) 的
 * refactor 会立刻让此测试失败, 防止 doom 风险静默回归。
 */
@DisplayName("#929 TriggerChainExecutor 监听器 AFTER_COMMIT + REQUIRES_NEW 守卫")
class TriggerChainExecutorAfterCommitTest {

    @Test
    @DisplayName("onApplicationEvent 必须标 @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)")
    void onApplicationEvent_isAfterCommitTransactionalListener() throws Exception {
        Method m = TriggerChainExecutor.class.getMethod("onApplicationEvent",
                org.springframework.context.ApplicationEvent.class);

        TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
        assertNotNull(tel,
                "onApplicationEvent 必须用 @TransactionalEventListener (不能用同步 @EventListener — "
                        + "会在发布者事务内执行, 内层 rollback-only 会 doom 发布者)");
        assertEquals(TransactionPhase.AFTER_COMMIT, tel.phase(),
                "phase 必须 AFTER_COMMIT — chain 在发布者提交后才跑, 看到已落库数据且不 doom 发布者");
        assertTrue(tel.fallbackExecution(),
                "fallbackExecution 必须 true — 否则无事务上下文发布的事件 (SalesOrderCreatedEvent 等) 被静默丢弃");
    }

    @Test
    @DisplayName("onApplicationEvent 必须标 @Transactional(REQUIRES_NEW) — chain 独立事务")
    void onApplicationEvent_isRequiresNew() throws Exception {
        Method m = TriggerChainExecutor.class.getMethod("onApplicationEvent",
                org.springframework.context.ApplicationEvent.class);

        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "onApplicationEvent 必须有 @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "propagation 必须 REQUIRES_NEW — chain 失败只回滚自身, 不影响其他");
    }
}
