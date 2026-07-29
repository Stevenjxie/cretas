package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.hr.ExpenseRequest;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.service.hr.ExpenseRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 报销申请查询工具.
 *
 * Intent Code: HR_EXPENSE_REQUEST_QUERY
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-EXP-1)
 * @since 2026-05-17
 */
@Slf4j
@Component
public class ExpenseRequestQueryTool extends AbstractBusinessTool {

    @Autowired
    private ExpenseRequestService expenseRequestService;

    @Override
    public String getToolName() {
        return "expense_request_query";
    }

    @Override
    public String getDescription() {
        return "查询报销申请列表. 支持状态/类目过滤, 月度汇总按类目分组 (TRAVEL/MEAL/OFFICE/ENTERTAIN/TRAINING/OTHER). "
                + "适用场景: '我的报销', '待审报销', '本月差旅汇总', '谁报销最多'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of("type", "string",
                "description", "用户 ID, 默认当前用户"));
        properties.put("status", Map.of("type", "string",
                "description", "状态过滤",
                "enum", List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "PAID")));
        properties.put("scope", Map.of("type", "string",
                "description", "范围: mine / pending / all",
                "enum", List.of("mine", "pending", "all"),
                "default", "mine"));
        properties.put("yearMonth", Map.of("type", "string",
                "description", "月度汇总 YYYY-MM (传则返回类目汇总)"));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String yearMonth = getString(params, "yearMonth");
        if (yearMonth != null && !yearMonth.isBlank()) {
            Map<ExpenseCategory, BigDecimal> summary =
                    expenseRequestService.summarizeMonth(factoryId, yearMonth);
            BigDecimal total = summary.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String msg = summary.isEmpty()
                    ? String.format("%s 暂无已批准报销", yearMonth)
                    : String.format("%s 报销汇总: 总额 ¥%s, %s", yearMonth, total,
                            summary.entrySet().stream()
                                    .map(e -> e.getKey().name() + "=¥" + e.getValue())
                                    .reduce((a, b) -> a + ", " + b).orElse(""));
            log.info("ExpenseRequestQuery summary: factory={} yearMonth={} total={}",
                    factoryId, yearMonth, total);
            return buildSimpleResult(msg, Map.of("summary", summary, "total", total));
        }

        String scope = getString(params, "scope", "mine");
        String statusStr = getString(params, "status");
        HrRequestStatus status = (statusStr == null || statusStr.isBlank())
                ? null : HrRequestStatus.valueOf(statusStr);
        Long ctxUserId = getUserId(context);
        Long paramUserId = getLong(params, "userId");

        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        var page = switch (scope) {
            case "pending" -> expenseRequestService.listPending(factoryId, pageable);
            case "all" -> expenseRequestService.listAll(factoryId, pageable);
            default -> expenseRequestService.listMine(factoryId,
                    paramUserId != null ? paramUserId : ctxUserId, status, pageable);
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExpenseRequest r : page.getContent()) {
            rows.add(Map.of(
                    "id", r.getId(),
                    "userId", r.getUserId(),
                    "category", r.getCategory().name(),
                    "amount", r.getAmount(),
                    "expenseDate", r.getExpenseDate().toString(),
                    "status", r.getStatus().name(),
                    "paymentRecordId", r.getPaymentRecordId() != null ? r.getPaymentRecordId() : "",
                    "reason", r.getReason() != null ? r.getReason() : ""));
        }

        String msg = String.format("找到 %d 条报销记录 (scope=%s, 总数 %d). 首条: %s",
                rows.size(), scope, page.getTotalElements(),
                rows.isEmpty() ? "(空)" : formatFirstRow(page.getContent().get(0)));
        log.info("ExpenseRequestQuery list: factory={} scope={} count={}", factoryId, scope, rows.size());
        return buildSimpleResult(msg, Map.of(
                "rows", rows,
                "total", page.getTotalElements(),
                "scope", scope));
    }

    private String formatFirstRow(ExpenseRequest r) {
        return String.format("[%s] 申请人=%d, %s ¥%s (%s), 状态=%s",
                r.getId().substring(0, 8), r.getUserId(),
                r.getCategory().name(), r.getAmount(),
                r.getExpenseDate(), r.getStatus().name());
    }


    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
