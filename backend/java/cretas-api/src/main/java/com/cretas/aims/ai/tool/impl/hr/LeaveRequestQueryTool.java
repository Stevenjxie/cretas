package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.service.hr.LeaveRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 请假申请查询工具 (AIChat).
 *
 * <p>R2 防呆: 返回 message 含申请人 / 类型 / 时长 / 起止日期 上下文.
 *
 * Intent Code: HR_LEAVE_REQUEST_QUERY
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-17
 */
@Slf4j
@Component
public class LeaveRequestQueryTool extends AbstractBusinessTool {

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Override
    public String getToolName() {
        return "leave_request_query";
    }

    @Override
    public String getDescription() {
        return "查询请假申请列表 (员工自助 / 主管审批 / HR 报表). "
                + "支持按状态过滤 (DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED), "
                + "按用户过滤, 按月份汇总. 适用场景: '我的请假', '待审请假', '本月年假统计', "
                + "'谁请假最多'. 不修改数据, read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of(
                "type", "string",
                "description", "用户 ID, 不传则查询当前登录用户的申请"));
        properties.put("status", Map.of(
                "type", "string",
                "description", "状态过滤: DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED, 不传查全部",
                "enum", List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED")));
        properties.put("scope", Map.of(
                "type", "string",
                "description", "查询范围: mine (我的) / pending (主管待审) / all (HR 全部)",
                "enum", List.of("mine", "pending", "all"),
                "default", "mine"));
        properties.put("yearMonth", Map.of(
                "type", "string",
                "description", "月份 YYYY-MM, 不传则不限月份. 传则返回月度汇总而非列表."));

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
            // 月度汇总分支
            Map<LeaveType, BigDecimal> summary = leaveRequestService.summarizeMonth(factoryId, yearMonth);
            String msg = summary.isEmpty()
                    ? String.format("%s 工厂 %s 暂无已批准请假记录", factoryId, yearMonth)
                    : String.format("%s 月已批准请假汇总: %s", yearMonth,
                            summary.entrySet().stream()
                                    .map(e -> e.getKey().name() + "=" + e.getValue() + "h")
                                    .reduce((a, b) -> a + ", " + b).orElse(""));
            log.info("LeaveRequestQuery summary: factory={} yearMonth={}", factoryId, yearMonth);
            return buildSimpleResult(msg, summary);
        }

        // 列表分支
        String scope = getString(params, "scope", "mine");
        String statusStr = getString(params, "status");
        HrRequestStatus status = (statusStr == null || statusStr.isBlank())
                ? null : HrRequestStatus.valueOf(statusStr);
        Long ctxUserId = getUserId(context);
        Long paramUserId = getLong(params, "userId");

        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        var page = switch (scope) {
            case "pending" -> leaveRequestService.listPending(factoryId, pageable);
            case "all" -> leaveRequestService.listAll(factoryId, pageable);
            default -> leaveRequestService.listMine(factoryId,
                    paramUserId != null ? paramUserId : ctxUserId, status, pageable);
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        for (LeaveRequest r : page.getContent()) {
            rows.add(Map.of(
                    "id", r.getId(),
                    "userId", r.getUserId(),
                    "leaveType", r.getLeaveType().name(),
                    "startDate", r.getStartDate().toString(),
                    "endDate", r.getEndDate().toString(),
                    "durationHours", r.getDurationHours(),
                    "status", r.getStatus().name(),
                    "reason", r.getReason() != null ? r.getReason() : ""));
        }

        // R2 防呆: message 含 申请人 / 类型 / 时长 context summary
        String msg = String.format("找到 %d 条请假记录 (scope=%s, 总数 %d). 首条: %s",
                rows.size(), scope, page.getTotalElements(),
                rows.isEmpty() ? "(空)" : formatFirstRow(page.getContent().get(0)));
        log.info("LeaveRequestQuery list: factory={} scope={} status={} count={}",
                factoryId, scope, status, rows.size());
        return buildSimpleResult(msg, Map.of(
                "rows", rows,
                "total", page.getTotalElements(),
                "scope", scope));
    }

    private String formatFirstRow(LeaveRequest r) {
        return String.format("[%s] 申请人=%d, %s %sh (%s~%s), 状态=%s",
                r.getId().substring(0, 8), r.getUserId(),
                r.getLeaveType().name(), r.getDurationHours(),
                r.getStartDate(), r.getEndDate(), r.getStatus().name());
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return Map.of(
                "userId", "用户 ID",
                "status", "状态",
                "scope", "查询范围",
                "yearMonth", "月份"
        ).getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
