package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.AccountingPeriodService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 期间结账状态查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>给财务主管 / AI 查 "X 年 X 月期间是否已关账", 默认本月. 用于"本月经营怎么样?" 入口
 * 提示 ("数据未结账, 可能不准") + Workdesk 中显期间状态.
 *
 * <p>读路径. 无 row 时按 backwards compat 视为 OPEN.
 *
 * <p>防呆 R2 — 返 period status + 责任人 (openedBy/closedBy) + audit 时间戳.
 *
 * <p>Intent Code: {@code PERIOD_STATUS_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class PeriodStatusQueryTool extends AbstractBusinessTool {

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Override
    public String getToolName() {
        return "period_status_query";
    }

    @Override
    public String getDescription() {
        return "查询 factory 指定 (year, month) 期间结账状态 (OPEN/PENDING_CLOSE/CLOSED). "
                + "默认本月. LLM 触发场景: 用户问 '本月关账了吗?' / '5 月期间状态' / "
                + "'这个月还能改凭证吗?' / '财务期间' / '关账状态'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> year = new HashMap<>();
        year.put("type", "integer");
        year.put("description", "可选 — 年份, 默认当前年");
        properties.put("year", year);

        Map<String, Object> month = new HashMap<>();
        month.put("type", "integer");
        month.put("description", "可选 — 月份 1-12, 默认当前月");
        month.put("minimum", 1);
        month.put("maximum", 12);
        properties.put("month", month);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        LocalDate today = LocalDate.now();
        Integer year = getInteger(params, "year", today.getYear());
        Integer month = getInteger(params, "month", today.getMonthValue());

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12, 当前: " + month);
        }

        log.info("period_status_query — factory={} year={} month={}", factoryId, year, month);

        AccountingPeriod.Status status = accountingPeriodService.getStatus(factoryId, year, month);
        Optional<AccountingPeriod> opt = accountingPeriodService.findPeriod(factoryId, year, month);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("year", year);
        data.put("month", month);
        data.put("status", status.name());
        data.put("statusDisplay", displayName(status));
        data.put("hasRow", opt.isPresent());

        opt.ifPresent(p -> {
            data.put("id", p.getId());
            data.put("openedAt", p.getOpenedAt() != null ? p.getOpenedAt().toString() : null);
            data.put("openedBy", p.getOpenedBy());
            data.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : null);
            data.put("closedBy", p.getClosedBy());
            data.put("reopenedAt", p.getReopenedAt() != null ? p.getReopenedAt().toString() : null);
            data.put("reopenedBy", p.getReopenedBy());
            data.put("reopenReason", p.getReopenReason());
            data.put("approvalWorkflowInstanceId", p.getApprovalWorkflowInstanceId());
        });

        String message = String.format("%d-%02d 期间状态: %s%s",
                year, month, displayName(status),
                opt.isEmpty() ? " (无记录, backwards compat 默认 OPEN)" : "");

        // Rule 5 dead-end → 跳到 accounting-period 页
        if (status == AccountingPeriod.Status.OPEN || status == AccountingPeriod.Status.PENDING_CLOSE) {
            data.put("actionHint", "/finance/accounting-period?year=" + year + "&month=" + month);
        }

        return buildSimpleResult(message, data);
    }

    private String displayName(AccountingPeriod.Status s) {
        return switch (s) {
            case OPEN -> "OPEN (期间正常, 可写凭证)";
            case PENDING_CLOSE -> "PENDING_CLOSE (财务发起结账, 待审批)";
            case CLOSED -> "CLOSED (期间已关账, 凭证写入被锁)";
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
