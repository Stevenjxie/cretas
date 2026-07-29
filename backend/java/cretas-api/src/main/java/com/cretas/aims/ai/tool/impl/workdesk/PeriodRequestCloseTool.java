package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.service.finance.AccountingPeriodService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发起期间结账请求 Tool (Sprint 8 P2 — 财务主管 Workdesk, WRITE + Preview).
 *
 * <p>财务员发起 "X 月期间结账请求", OPEN → PENDING_CLOSE, 触发 BUDGET_APPROVAL workflow
 * (workflow 未配置时 fail-open 留 PENDING_CLOSE).
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1 (max display): doPreview() 显期间内 voucher 数量 + DRAFT/POSTED 分布</li>
 *   <li>R2 (context): 返 message 含 year-month + 责任人</li>
 *   <li>R4 (idempotent): 已 PENDING_CLOSE / CLOSED 时拒绝 (state machine)</li>
 *   <li>error: 后端 message 原样透传, sticky toast</li>
 * </ul>
 *
 * <p>Intent Code: {@code PERIOD_REQUEST_CLOSE}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class PeriodRequestCloseTool extends AbstractBusinessTool {

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private VoucherRepository voucherRepository;

    @Override
    public String getToolName() {
        return "period_request_close";
    }

    @Override
    public String getDescription() {
        return "发起期间结账请求 (OPEN → PENDING_CLOSE), 触发 BUDGET_APPROVAL workflow. "
                + "LLM 触发场景: 用户说 '发起 5 月结账' / '5 月可以关账了' / '申请关账' / "
                + "'提交结账'. WRITE + Preview (显期间内 voucher 数量 + DRAFT 状态分布).";
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

    /**
     * Preview: 显当前状态 + 期间内 voucher 数量 + DRAFT 状态分布 (R1 max display).
     */
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
        result.put("targetStatus", AccountingPeriod.Status.PENDING_CLOSE.name());

        // R1: 期间内 voucher 数量 + DRAFT 数量 (尚未 POST 的可能阻塞结账)
        LocalDate start = YearMonth.of(year, month).atDay(1);
        LocalDate end = YearMonth.of(year, month).atEndOfMonth();
        List<Voucher> vouchers = voucherRepository.findByFactoryIdAndDateRange(factoryId, start, end);
        long draftCount = vouchers.stream()
                .filter(v -> v.getStatus() == VoucherStatus.DRAFT).count();
        long postedCount = vouchers.stream()
                .filter(v -> v.getStatus() == VoucherStatus.POSTED).count();
        long voidCount = vouchers.stream()
                .filter(v -> v.getStatus() == VoucherStatus.VOID).count();
        result.put("voucherCount", vouchers.size());
        result.put("draftCount", draftCount);
        result.put("postedCount", postedCount);
        result.put("voidCount", voidCount);

        if (currentStatus != AccountingPeriod.Status.OPEN) {
            result.put("status", "INVALID_STATE");
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ %d-%02d 当前状态=%s, 仅 OPEN 期间可发起结账",
                    year, month, currentStatus));
            return result;
        }

        result.put("status", "PREVIEW");
        result.put("canDo", true);
        String draftWarning = draftCount > 0
                ? String.format(" (含 %d 张 DRAFT 草稿凭证, 建议先 POSTED)", draftCount)
                : "";
        result.put("message", String.format(
                "✓ 准备发起 %d-%02d 期间结账 (OPEN → PENDING_CLOSE). 期间内共 %d 张凭证%s. "
                + "提交后将触发 BUDGET_APPROVAL workflow 审批.",
                year, month, vouchers.size(), draftWarning));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Integer year = getInteger(params, "year");
        Integer month = getInteger(params, "month");
        validate(year, month);
        Long userId = getUserId(context);

        log.info("period_request_close — factory={} year={} month={} userId={}",
                factoryId, year, month, userId);

        AccountingPeriod p = accountingPeriodService.requestClose(factoryId, year, month, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", p.getId());
        data.put("year", p.getYear());
        data.put("month", p.getMonth());
        data.put("status", p.getStatus().name());
        data.put("approvalWorkflowInstanceId", p.getApprovalWorkflowInstanceId());

        String message = String.format(
                "✓ %d-%02d 期间结账请求已发起 (%s). %s",
                p.getYear(), p.getMonth(), p.getStatus().name(),
                p.getApprovalWorkflowInstanceId() != null
                        ? "BUDGET_APPROVAL workflow 已触发, 等待审批."
                        : "BUDGET workflow 未配置 → 待 finance director 手工 confirmClose.");
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
