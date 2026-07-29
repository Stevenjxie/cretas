package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.hr.LeaveBalance;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.service.hr.LeaveBalanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.*;

/**
 * 假期余额查询工具.
 *
 * <p>R2 防呆: 返回 message 含 用户 / 类型 / 已获/已用/剩余 完整上下文.
 *
 * Intent Code: HR_LEAVE_BALANCE_QUERY
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-17
 */
@Slf4j
@Component
public class LeaveBalanceQueryTool extends AbstractBusinessTool {

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Override
    public String getToolName() {
        return "leave_balance_query";
    }

    @Override
    public String getDescription() {
        return "查询假期余额 (年假/调休/病假等月度累计). 显示已获/已用/剩余 小时数. "
                + "适用场景: '我还有多少年假', '调休余额', '本月年假剩余'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of("type", "string",
                "description", "用户 ID, 默认当前用户"));
        properties.put("leaveType", Map.of("type", "string",
                "description", "假期类型: ANNUAL/SICK/COMPTIME/OTHER, 不传查全部跟踪类型",
                "enum", List.of("ANNUAL", "SICK", "COMPTIME", "OTHER")));
        properties.put("yearMonth", Map.of("type", "string",
                "description", "月份 YYYY-MM, 不传则取当月"));

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
        String yearMonth = getString(params, "yearMonth", YearMonth.now().toString());
        String leaveTypeStr = getString(params, "leaveType");
        Long ctxUserId = getUserId(context);
        Long paramUserId = getLong(params, "userId");
        Long userId = paramUserId != null ? paramUserId : ctxUserId;

        if (userId == null) {
            return buildSimpleResult("无法识别用户, 请指定 userId 参数",
                    Collections.emptyMap());
        }

        List<LeaveBalance> balances;
        if (leaveTypeStr != null && !leaveTypeStr.isBlank()) {
            LeaveType lt = LeaveType.valueOf(leaveTypeStr);
            balances = leaveBalanceService.getBalance(factoryId, userId, lt, yearMonth)
                    .map(List::of)
                    .orElse(List.of());
        } else {
            balances = leaveBalanceService.listForUserMonth(factoryId, userId, yearMonth);
        }

        if (balances.isEmpty()) {
            String msg = String.format("用户 %d 在 %s 月暂无 %s 余额记录", userId, yearMonth,
                    leaveTypeStr != null ? leaveTypeStr : "任何类型");
            return buildSimpleResult(msg, Collections.emptyList());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder msg = new StringBuilder(String.format("用户 %d %s 月余额: ", userId, yearMonth));
        for (int i = 0; i < balances.size(); i++) {
            LeaveBalance b = balances.get(i);
            rows.add(Map.of(
                    "leaveType", b.getLeaveType().name(),
                    "yearMonth", b.getYearMonth(),
                    "earnedHours", b.getEarnedHours(),
                    "usedHours", b.getUsedHours(),
                    "balanceHours", b.getBalanceHours() != null ? b.getBalanceHours()
                            : b.getEarnedHours().subtract(b.getUsedHours())));
            if (i > 0) msg.append(", ");
            msg.append(String.format("%s 剩余 %sh (已获 %sh, 已用 %sh)",
                    b.getLeaveType().name(),
                    b.getBalanceHours() != null ? b.getBalanceHours()
                            : b.getEarnedHours().subtract(b.getUsedHours()),
                    b.getEarnedHours(), b.getUsedHours()));
        }
        log.info("LeaveBalanceQuery: factory={} user={} month={} type={} count={}",
                factoryId, userId, yearMonth, leaveTypeStr, rows.size());
        return buildSimpleResult(msg.toString(), rows);
    }


    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
