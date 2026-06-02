package com.cretas.aims.controller;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元E (F006 防呆 REQ-13): "今日工序" 下拉只显示 plannedDate=today 的计划,
 * 多日计划不再污染列表; plannedDate=null 的计划保留 (向后兼容)。
 *
 * 直接测试抽出的纯函数谓词 {@link ProcessCheckinController#isAvailableProcessPlan},
 * 无需 Spring 上下文。
 */
class ProcessCheckinControllerAvailableProcessTest {

    private static ProductionPlan plan(ProductionPlanStatus status, LocalDate plannedDate, String processName) {
        ProductionPlan p = new ProductionPlan();
        p.setStatus(status);
        p.setPlannedDate(plannedDate);
        p.setProcessName(processName);
        return p;
    }

    @Test
    void includesPlanDatedToday() {
        ProductionPlan p = plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now(), "切割");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isTrue();
    }

    @Test
    void excludesPlanDatedYesterday() {
        ProductionPlan p = plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now().minusDays(1), "切割");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isFalse();
    }

    @Test
    void excludesPlanDatedTomorrow() {
        ProductionPlan p = plan(ProductionPlanStatus.PLANNED, LocalDate.now().plusDays(1), "切割");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isFalse();
    }

    @Test
    void includesPlanWithNullDateForBackwardCompat() {
        ProductionPlan p = plan(ProductionPlanStatus.PENDING, null, "切割");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isTrue();
    }

    @Test
    void excludesCompletedPlanEvenIfDatedToday() {
        ProductionPlan p = plan(ProductionPlanStatus.COMPLETED, LocalDate.now(), "切割");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isFalse();
    }

    @Test
    void excludesPlanWithBlankProcessName() {
        ProductionPlan p = plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now(), "");
        assertThat(ProcessCheckinController.isAvailableProcessPlan(p, LocalDate.now())).isFalse();
        ProductionPlan pNull = plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now(), null);
        assertThat(ProcessCheckinController.isAvailableProcessPlan(pNull, LocalDate.now())).isFalse();
    }

    @Test
    void acceptsPlannedAndPendingAndInProgressDatedToday() {
        assertThat(ProcessCheckinController.isAvailableProcessPlan(
                plan(ProductionPlanStatus.PLANNED, LocalDate.now(), "切割"), LocalDate.now())).isTrue();
        assertThat(ProcessCheckinController.isAvailableProcessPlan(
                plan(ProductionPlanStatus.PENDING, LocalDate.now(), "切割"), LocalDate.now())).isTrue();
        assertThat(ProcessCheckinController.isAvailableProcessPlan(
                plan(ProductionPlanStatus.IN_PROGRESS, LocalDate.now(), "切割"), LocalDate.now())).isTrue();
    }
}
