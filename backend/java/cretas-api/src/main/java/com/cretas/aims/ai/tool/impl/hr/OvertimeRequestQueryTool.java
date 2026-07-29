package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.service.hr.OvertimeRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 加班/调休申请查询工具.
 *
 * Intent Code: HR_OVERTIME_REQUEST_QUERY
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-OVT-1)
 * @since 2026-05-17
 */
@Slf4j
@Component
public class OvertimeRequestQueryTool extends AbstractBusinessTool {

    @Autowired
    private OvertimeRequestService overtimeRequestService;

    @Override
    public String getToolName() {
        return "overtime_request_query";
    }

    @Override
    public String getDescription() {
        return "查询加班/调休申请列表. 支持状态过滤, 月度汇总按补偿方式(CASH/COMPTIME)分组. "
                + "适用场景: '我的加班', '本月加班统计', '待审加班', '本月调休累计'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of("type", "string",
                "description", "用户 ID, 默认当前用户"));
        properties.put("status", Map.of("type", "string",
                "description", "状态过滤",
                "enum", List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED")));
        properties.put("scope", Map.of("type", "string",
                "description", "范围: mine / pending / all",
                "enum", List.of("mine", "pending", "all"),
                "default", "mine"));
        properties.put("yearMonth", Map.of("type", "string",
                "description", "月度汇总 YYYY-MM (传则返回 CASH/COMPTIME 汇总而非列表)"));

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
            Map<CompensationType, BigDecimal> summary =
                    overtimeRequestService.summarizeMonth(factoryId, yearMonth);
            String msg = summary.isEmpty()
                    ? String.format("%s 暂无已批准加班记录", yearMonth)
                    : String.format("%s 已批准加班汇总: %s", yearMonth,
                            summary.entrySet().stream()
                                    .map(e -> e.getKey().name() + "=" + e.getValue() + "h")
                                    .reduce((a, b) -> a + ", " + b).orElse(""));
            log.info("OvertimeRequestQuery summary: factory={} yearMonth={}", factoryId, yearMonth);
            return buildSimpleResult(msg, summary);
        }

        String scope = getString(params, "scope", "mine");
        String statusStr = getString(params, "status");
        HrRequestStatus status = (statusStr == null || statusStr.isBlank())
                ? null : HrRequestStatus.valueOf(statusStr);
        Long ctxUserId = getUserId(context);
        Long paramUserId = getLong(params, "userId");

        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        var page = switch (scope) {
            case "pending" -> overtimeRequestService.listPending(factoryId, pageable);
            case "all" -> overtimeRequestService.listAll(factoryId, pageable);
            default -> overtimeRequestService.listMine(factoryId,
                    paramUserId != null ? paramUserId : ctxUserId, status, pageable);
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        for (OvertimeRequest r : page.getContent()) {
            rows.add(Map.of(
                    "id", r.getId(),
                    "userId", r.getUserId(),
                    "overtimeType", r.getOvertimeType().name(),
                    "startTime", r.getStartTime().toString(),
                    "endTime", r.getEndTime().toString(),
                    "hours", r.getHours(),
                    "compensationType", r.getCompensationType().name(),
                    "status", r.getStatus().name(),
                    "reason", r.getReason() != null ? r.getReason() : ""));
        }

        String msg = String.format("找到 %d 条加班记录 (scope=%s, 总数 %d). 首条: %s",
                rows.size(), scope, page.getTotalElements(),
                rows.isEmpty() ? "(空)" : formatFirstRow(page.getContent().get(0)));
        log.info("OvertimeRequestQuery list: factory={} scope={} count={}", factoryId, scope, rows.size());
        return buildSimpleResult(msg, Map.of(
                "rows", rows,
                "total", page.getTotalElements(),
                "scope", scope));
    }

    private String formatFirstRow(OvertimeRequest r) {
        return String.format("[%s] 申请人=%d, %s %sh, 转%s, 状态=%s",
                r.getId().substring(0, 8), r.getUserId(),
                r.getOvertimeType().name(), r.getHours(),
                r.getCompensationType().name(), r.getStatus().name());
    }


    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
