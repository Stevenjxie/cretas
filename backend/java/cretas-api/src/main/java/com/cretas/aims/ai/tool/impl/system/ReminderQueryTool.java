package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Reminder;
import com.cretas.aims.entity.enums.ReminderStatus;
import com.cretas.aims.service.ReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Sprint 5 Tool 3 — 我的提醒列表查询 Tool.
 *
 * <p>Wraps {@link ReminderService#listMine} (shipped in PR #766 Chat N — Sprint 4 W2 S-REMIND-1).
 * Used when user asks "我有什么提醒?" / "今天的待办" / "催款提醒" via AIChat.
 *
 * <p>防呆设计:
 * <ul>
 *   <li>R2 (context): 每条提醒返回 sourceType + sourceId + sourceNumber + type + dueDate + message
 *       so LLM 可以告诉用户 "哪个订单 / 哪个客户 / 多少天逾期".</li>
 *   <li>R5 (next-action hint): 顶层 actionHint 指向 /sales/reminders 让用户知道去哪处理.</li>
 * </ul>
 *
 * <p>Read-only Tool, 按 Tool 命名约定 (`_query`) auto-derived RiskLevel=LOW / ActionType=READ.
 */
@Slf4j
@Component
public class ReminderQueryTool extends AbstractBusinessTool {

    @Autowired
    private ReminderService reminderService;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    @Override
    public String getToolName() {
        return "reminder_query_mine";
    }

    @Override
    public String getDescription() {
        return "查询我的待办提醒 (收款提醒 PAYMENT_DUE 等). 默认返回 PENDING + SNOOZED 状态的提醒, "
                + "按到期日期 (dueDate) 升序排列. 用户问 '我有什么提醒?' / '今天的待办' / "
                + "'催款提醒' / '有什么待办没处理' / '我的提醒' 时调用. 只读 Tool.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("status", Map.of(
                "type", "string",
                "description", "过滤状态: PENDING / SNOOZED / DISMISSED (可逗号分隔多个), "
                        + "默认 'PENDING,SNOOZED' 即仍需处理的提醒"));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "返回条数, 默认 " + DEFAULT_LIMIT + ", 最大 " + MAX_LIMIT));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
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
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long userId = getUserId(context);
        if (userId == null) {
            throw new IllegalArgumentException("Missing userId in context");
        }

        // Parse status filter (default PENDING + SNOOZED).
        String statusFilter = getString(params, "status", "PENDING,SNOOZED");
        List<ReminderStatus> statuses = parseStatuses(statusFilter);
        if (statuses.isEmpty()) {
            // Fall back to default if user typed invalid values.
            statuses = Arrays.asList(ReminderStatus.PENDING, ReminderStatus.SNOOZED);
            statusFilter = "PENDING,SNOOZED";
        }

        // Clamp limit to [1, MAX_LIMIT].
        Integer limit = getInteger(params, "limit", DEFAULT_LIMIT);
        if (limit == null || limit < 1) {
            limit = DEFAULT_LIMIT;
        } else if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        Page<Reminder> page = reminderService.listMine(
                factoryId, userId, statuses,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "dueDate")));

        List<Map<String, Object>> items = page.getContent().stream()
                .map(this::toContextRow)
                .collect(Collectors.toList());

        // 防呆 R5: 顶层 actionHint 指向 web-admin 提醒列表页面.
        Map<String, Object> result = new HashMap<>();
        result.put("count", items.size());
        result.put("totalElements", page.getTotalElements());
        result.put("statusFilter", statusFilter);
        result.put("assigneeUserId", userId);
        result.put("reminders", items);
        result.put("actionHint", "查看完整提醒列表或处理提醒: /sales/reminders (我的提醒页面)");

        // Pending count is convenient for AI summary ("还有 N 条未处理").
        long pendingCount = reminderService.countPending(factoryId, userId);
        result.put("pendingCount", pendingCount);

        String message;
        if (page.getTotalElements() == 0) {
            message = String.format("您没有待处理的提醒 (筛选状态: %s).", statusFilter);
        } else {
            message = String.format("您有 %d 条提醒 (状态: %s, 显示前 %d 条). 待办处理总数: %d 条 PENDING.",
                    page.getTotalElements(), statusFilter, items.size(), pendingCount);
        }
        return buildSimpleResult(message, result);
    }

    /**
     * 防呆 R2: 每条 reminder 返回足够上下文让 LLM 给用户清晰回答.
     * <p>包含: id / type / sourceType / sourceId (sourceNumber 占位) / status / dueDate /
     * snoozedUntil / message (用户友好的提示文本) / typeLabel (中文标签).
     */
    private Map<String, Object> toContextRow(Reminder r) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", r.getId());
        row.put("type", r.getType() != null ? r.getType().name() : null);
        row.put("typeLabel", r.getType() != null ? typeLabel(r.getType().name()) : null);
        row.put("sourceType", r.getSourceType());
        row.put("sourceId", r.getSourceId());
        // sourceNumber alias: sourceId 即业务单号 (e.g. SalesOrder.id). 显式 alias 方便 LLM 引用.
        row.put("sourceNumber", r.getSourceId());
        row.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        row.put("dueDate", r.getDueDate());
        row.put("snoozedUntil", r.getSnoozedUntil());
        row.put("message", r.getMessage());
        return row;
    }

    /** 中文 typeLabel — LLM friendly 描述 (其他 type 加进来时扩展). */
    private String typeLabel(String type) {
        if ("PAYMENT_DUE".equals(type)) {
            return "收款提醒";
        }
        return type;
    }

    /** 解析逗号分隔的状态字符串为 ReminderStatus 列表, 无效值跳过. */
    private List<ReminderStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<ReminderStatus> result = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(ReminderStatus.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.debug("Ignored invalid reminder status filter token: {}", trimmed);
            }
        }
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
