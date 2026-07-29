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
 * 反结账 (期间重新打开) Tool (Sprint 8 P2 — 财务主管 Workdesk, WRITE + Preview).
 *
 * <p>finance director 反结账: CLOSED → OPEN. 必填 reason (audit log). voucher 写入解锁.
 *
 * <p>BLOCKING action — preview 必显 "反结账影响月报 / 三表 / 利润审计, 请谨慎" 警告.
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1 (max display): preview 显当前状态 + audit 警告</li>
 *   <li>R2 (context): year-month + reason 必填提示</li>
 *   <li>R3 (constrained reason): 自由文本 reason, audit log 永久留存</li>
 *   <li>R4: state machine — 仅 CLOSED 可 reopen</li>
 *   <li>error: 后端 message 原样透传</li>
 * </ul>
 *
 * <p>Intent Code: {@code PERIOD_REOPEN}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class PeriodReopenTool extends AbstractBusinessTool {

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Override
    public String getToolName() {
        return "period_reopen";
    }

    @Override
    public String getDescription() {
        return "反结账 (CLOSED → OPEN), 必填 reason audit log. voucher 写入解锁. "
                + "LLM 触发场景: 用户说 '反结账 5 月' / '5 月需要补凭证' / '重开 5 月期间' / "
                + "'reopen 5 月'. 需 finance_manager 角色. WRITE + Preview (audit 警告).";
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

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "反结账原因 (必填, audit log 留存. 例: '5/15 补漏一笔工资凭证')");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", List.of("year", "month", "reason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("year", "month", "reason");
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
        String reason = getString(params, "reason");
        validate(year, month, reason);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("month", month);
        result.put("reason", reason);
        result.put("factoryId", factoryId);

        AccountingPeriod.Status currentStatus = accountingPeriodService.getStatus(factoryId, year, month);
        result.put("currentStatus", currentStatus.name());
        result.put("targetStatus", AccountingPeriod.Status.OPEN.name());

        if (currentStatus != AccountingPeriod.Status.CLOSED) {
            result.put("status", "INVALID_STATE");
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ %d-%02d 当前状态=%s, 仅 CLOSED 期间可 reopen",
                    year, month, currentStatus));
            return result;
        }

        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("auditWarning", true);
        result.put("message", String.format(
                "⚠️ AUDIT 警告: 反结账 %d-%02d 后, 该期间月报 / 三表 / 利润计算可能改变. "
                + "已生效的报表对外披露需重新生成. reason='%s' 将永久留 audit log. 确认提交?",
                year, month, reason));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Integer year = getInteger(params, "year");
        Integer month = getInteger(params, "month");
        String reason = getString(params, "reason");
        validate(year, month, reason);
        Long userId = getUserId(context);

        log.info("period_reopen — factory={} year={} month={} userId={} reason={}",
                factoryId, year, month, userId, reason);

        AccountingPeriod p = accountingPeriodService.reopenPeriod(factoryId, year, month, reason, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", p.getId());
        data.put("year", p.getYear());
        data.put("month", p.getMonth());
        data.put("status", p.getStatus().name());
        data.put("reopenedAt", p.getReopenedAt() != null ? p.getReopenedAt().toString() : null);
        data.put("reopenedBy", p.getReopenedBy());
        data.put("reopenReason", p.getReopenReason());

        String message = String.format(
                "🔓 %d-%02d 期间已反结账 (OPEN). voucher 写入解锁. reopenBy user=%d. audit log 永久留存.",
                p.getYear(), p.getMonth(), userId);
        return buildSimpleResult(message, data);
    }

    private void validate(Integer year, Integer month, String reason) {
        if (year == null || month == null) {
            throw new IllegalArgumentException("year + month 必填");
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12, 当前: " + month);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 必填 — audit log 永久留存");
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
