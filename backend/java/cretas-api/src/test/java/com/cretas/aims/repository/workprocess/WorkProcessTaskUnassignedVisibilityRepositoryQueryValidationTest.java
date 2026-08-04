package com.cretas.aims.repository.workprocess;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反锁死兜底 — 操作员任务入口查询必须带出「未指派」工序.
 *
 * <p><b>背景 (2026-08-04)</b>: prod 实测 {@code work_process_tasks} 18 条 {@code assigned_to} 全为 null,
 * {@code product_work_process_assignees} 0 行, {@code production_plans} 10 条无一填 supervisor ——
 * 指派配置<b>从未被填写过</b>。RN 操作员报工屏的第一跳走
 * {@code GET /work-process-tasks?assignedTo={me}} → {@link WorkProcessTaskRepository#findByFilters},
 * 该查询原为 {@code t.assignedTo = :assignedTo} 严格相等 → NULL 全部被排除 → 操作员永远看到空列表,
 * <b>手机端报工无从开始</b>。
 *
 * <p><b>兜底本来就存在, 只是装错了门</b>: 姊妹查询
 * {@code WorkProcessTaskServiceImpl#listByBatch} 早有 M1 兜底 (注释原文「防止未配默认责任人的老批次
 * 把任何人锁死」), 过滤式为 {@code assignedTo == null || equals(assignedTo)}; 但它在<b>进入某个批次之后</b>
 * 才执行, 而操作员卡在更早的入口列表上, 永远走不到那里。本测试钉住两处口径一致。
 *
 * <p><b>鉴权侧无需同步放开</b>: {@code ReportAuthGuard#assertCanReport} 对空允许集合已经 fail-open
 * (注释原文「未指派, 任何操作员均可报工」), 且 {@code WorkProcessTaskServiceImpl#start} 在
 * {@code assignedTo == null} 时自动把任务认给当前操作员 —— 「未指派任务可被任何人捡起」本就是既有设计,
 * 本改动只是让入口列表与该设计一致。
 *
 * <p>⛔ <b>刻意不放开的那一条</b>: 指派给<b>他人</b>的任务仍然不得出现在我的列表里, 否则是越权。
 *
 * <p>H2 PG-compat (application-test.properties), 模式同 {@code MaterialBatchFefoWipExclusionTest}。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("WorkProcessTaskRepository#findByFilters — 未指派工序对操作员可见 (反锁死)")
class WorkProcessTaskUnassignedVisibilityRepositoryQueryValidationTest {

    private static final String F1 = "F-WPT-1";
    private static final String F2 = "F-WPT-2";
    private static final Long ME = 7L;
    private static final Long SOMEONE_ELSE = 9L;

    @Autowired
    private WorkProcessTaskRepository repo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long mineId;
    private Long unassignedId;
    private Long othersId;
    private Long otherFactoryUnassignedId;

    @BeforeEach
    void seed() {
        // work_process_tasks 的 created_by → users(id) 等 FK: 只 seed 任务不建全依赖树
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        repo.deleteAll();

        mineId = save(F1, 1, ME).getId();
        unassignedId = save(F1, 2, null).getId();
        othersId = save(F1, 3, SOMEONE_ELSE).getId();
        otherFactoryUnassignedId = save(F2, 4, null).getId();
    }

    private WorkProcessTask save(String factoryId, int order, Long assignedTo) {
        return repo.save(WorkProcessTask.builder()
                .factoryId(factoryId)
                .productionBatchId(2025L)
                .workProcessId("WP-" + order)
                .productTypeId("PT-1")
                .processOrder(order)
                .status(WorkProcessTask.Status.PENDING)
                .assignedTo(assignedTo)
                .build());
    }

    private List<Long> idsFor(Long assignedTo) {
        Pageable page = PageRequest.of(0, 50);
        return repo.findByFilters(F1, null, null, assignedTo, page)
                .getContent().stream().map(WorkProcessTask::getId).toList();
    }

    @Test
    @DisplayName("按 assignedTo 过滤时, 未指派工序必须一并返回 (否则操作员空列表锁死)")
    void unassignedTasksRemainVisibleWhenFilteringByAssignee() {
        List<Long> ids = idsFor(ME);

        // 这一条是本次修复的 oracle: 改回 `t.assignedTo = :assignedTo` 严格相等即红
        assertThat(ids).contains(unassignedId);
        assertThat(ids).contains(mineId);
    }

    @Test
    @DisplayName("⛔ 指派给他人的工序仍然不可见 (放开这条就是越权)")
    void tasksAssignedToOthersStayHidden() {
        assertThat(idsFor(ME)).doesNotContain(othersId);
    }

    @Test
    @DisplayName("不传 assignedTo → 主管视图返回全部 (含他人的)")
    void supervisorViewReturnsEverything() {
        assertThat(idsFor(null))
                .containsExactlyInAnyOrder(mineId, unassignedId, othersId);
    }

    @Test
    @DisplayName("跨工厂隔离不受兜底影响 —— 别厂的未指派工序不得漏出")
    void unassignedFallbackDoesNotLeakAcrossFactories() {
        assertThat(idsFor(ME)).doesNotContain(otherFactoryUnassignedId);
        assertThat(idsFor(null)).doesNotContain(otherFactoryUnassignedId);
    }
}
