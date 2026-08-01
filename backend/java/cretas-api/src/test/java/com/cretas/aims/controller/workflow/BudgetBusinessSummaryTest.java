package com.cretas.aims.controller.workflow;

import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUDGET 待办的「业务单据」列。
 *
 * <p>客户截图里这一列显示的是裸 UUID {@code BUDGET b67922a2-e4b9-4143-bd6e-33d42ed98ae0}
 * —— 因为 {@code buildBusinessSummary} 的 hydrate 只覆盖了 PURCHASE_ORDER 与 SALES_ORDER,
 * 其余 module 一律 fallback 成 {@code moduleCode + businessEntityId}。
 *
 * <p>会计期间的 context 里本来就带 year / month(prod 实例 153f4e39 实测), 所以
 * <b>不需要回查 accounting_periods</b> —— 与 INVENTORY_TRANSFER 分支同样直接读 context。
 */
class BudgetBusinessSummaryTest {

    // buildBusinessSummary 只读 instance 与传入的 map, 不碰任何注入的协作者
    private final WorkflowInstanceController controller =
            new WorkflowInstanceController(null, null, null, null, null, null, null, null);

    private String summaryOf(Map<String, Object> context, String bizId) {
        ApprovalWorkflowInstance inst = new ApprovalWorkflowInstance();
        inst.setModuleCode("BUDGET");
        inst.setBusinessEntityId(bizId);
        inst.setContextJson(context);
        return (String) ReflectionTestUtils.invokeMethod(
                controller, "buildBusinessSummary", inst, Map.of(), Map.of(), Map.of());
    }

    @Test
    @DisplayName("会计期间显示成可读的年月, 而不是裸 UUID")
    void accountingPeriodShowsReadableYearMonth() {
        Map<String, Object> context = new HashMap<>();
        context.put("year", 2026);
        context.put("month", 7);
        context.put("periodId", "b67922a2-e4b9-4143-bd6e-33d42ed98ae0");
        context.put("entityType", "ACCOUNTING_PERIOD");

        assertThat(summaryOf(context, "b67922a2-e4b9-4143-bd6e-33d42ed98ae0"))
                .as("客户看到的应是「2026 年 7 月 会计期间」而不是一串 UUID")
                .contains("2026")
                .contains("7")
                .doesNotContain("b67922a2");
    }

    @Test
    @DisplayName("context 缺 year/month 时退回可辨识的兜底, 不炸")
    void missingYearMonthDegradesGracefully() {
        Map<String, Object> context = new HashMap<>();
        context.put("entityType", "ACCOUNTING_PERIOD");

        String summary = summaryOf(context, "b67922a2-e4b9-4143-bd6e-33d42ed98ae0");
        assertThat(summary).isNotBlank();
    }

    @Test
    @DisplayName("context 为 null 时不抛异常")
    void nullContextDoesNotThrow() {
        assertThat(summaryOf(null, "some-id")).isNotBlank();
    }
}
