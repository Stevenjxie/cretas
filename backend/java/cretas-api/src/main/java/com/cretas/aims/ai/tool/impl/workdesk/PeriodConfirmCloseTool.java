package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.AccountingPeriodService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确认期间关账 Tool (Sprint 8 P2 — 财务主管 Workdesk, WRITE + Preview).
 *
 * <p>finance director 确认结账: PENDING_CLOSE → CLOSED. voucher 写 gate 立即生效, 期间内
 * 凭证将抛 PeriodClosedException 阻止任何 CRUD.
 *
 * <p>BLOCKING action — preview 必显 "CLOSED 后立即生效, 所有 voucher 写操作被锁" 警告.
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1 (max display): preview 显当前状态 + BLOCKING 警告</li>
 *   <li>R2 (context): year-month + 责任人 + 操作时间</li>
 *   <li>R4: state machine — 仅 PENDING_CLOSE 可 confirmClose</li>
 *   <li>error: 后端 message 原样透传</li>
 * </ul>
 *
 * <p>Intent Code: {@code PERIOD_CONFIRM_CLOSE}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class PeriodConfirmCloseTool extends AbstractBusinessTool {

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Override
    public String getToolName() {
        return "period_confirm_close";
    }

    @Override
    public String getDescription() {
        return "确认期间关账 (PENDING_CLOSE → CLOSED). BLOCKING — 关账后 voucher 写入立即被锁. "
                + "LLM 触发场景: 用户说 '确认 5 月关账' / '关账 5 月' / '财务关账' / "
                + "'finalize 期间'. 需 finance_manager 角色. WRITE + Preview (BLOCKING 警告).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> year = new HashMap<>();
        year.put("type", "integer");
        year.put("description", "年份 (必填)");
        properties.put("year", year);

        Map<String, Object> month = new HashMap<>();
        month.put("type", "integer");
        month.put("description", "月份 1-12 (必填)");
        month.put("minimum", 1);
        month.put("maximum", 12);
        properties.put("month", month);

        schema.put("properties", properties);
        schema.put("required", List.of("year", "month"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("year", "month");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Integer year = getInteger(params, "year");
        Integer month = getInteger(params, "month");
        validate(year, month);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("factoryId", factoryId);

        AccountingPeriod.Status currentStatus = accountingPeriodService.getStatus(factoryId, year, month);
        result.put("currentStatus", currentStatus.name());
        result.put("targetStatus", AccountingPeriod.Status.CLOSED.name());

        if (currentStatus != AccountingPeriod.Status.PENDING_CLOSE) {
            result.put("status", "INVALID_STATE");
            result.put("canDo", false);
            String hint = currentStatus == AccountingPeriod.Status.OPEN
                    ? "请先调用 period_request_close 发起结账请求"
                    : currentStatus == AccountingPeriod.Status.CLOSED
                            ? "已 CLOSED, 如需重新打开请用 period_reopen"
                            : "状态不明";
            result.put("message", String.format(
                    "⚠️ %d-%02d 当前状态=%s, 仅 PENDING_CLOSE 可 confirmClose. %s",
                    year, month, currentStatus, hint));
            return result;
        }

        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("blocking", true);
        result.put("message", String.format(
                "🔒 BLOCKING 警告: 确认关账 %d-%02d 后, voucher 写入立即锁定 (任何 CRUD 抛 "
                + "PeriodClosedException). 仅 finance director 可后续 reopen. 确认提交?",
                year, month));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Integer year = getInteger(params, "year");
        Integer month = getInteger(params, "month");
        validate(year, month);
        Long userId = getUserId(context);

        log.info("period_confirm_close — factory={} year={} month={} userId={}",
                factoryId, year, month, userId);

        AccountingPeriod p = accountingPeriodService.confirmClose(factoryId, year, month, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", p.getId());
        data.put("year", p.getYear());
        data.put("month", p.getMonth());
        data.put("status", p.getStatus().name());
        data.put("closedAt", p.getClosedAt() != null ? p.getClosedAt().toString() : null);
        data.put("closedBy", p.getClosedBy());

        String message = String.format(
                "🔒 %d-%02d 期间已关账 (CLOSED). voucher 写入已锁. closedBy user=%d",
                p.getYear(), p.getMonth(), userId);
        return buildSimpleResult(message, data);
    }

    private void validate(Integer year, Integer month) {
        if (year == null || month == null) {
            throw new IllegalArgumentException("year + month 必填");
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12, 当前: " + month);
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
