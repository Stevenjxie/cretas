package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 操作日志查询 Tool — 用户在 AI Chat 里问"查最近操作日志"等触发.
 *
 * <p>Sprint 4 Chat K C-LOG-AUDIT-1. 复用 OperationLogRepository.findByFilters
 * (CAST(:p AS string) IS NULL PG-safe pattern).
 */
@Slf4j
@Component
public class OperationLogQueryTool extends AbstractBusinessTool {

    @Autowired
    private OperationLogRepository repo;

    @Override
    public String getToolName() {
        return "operation_log_query";
    }

    @Override
    public String getDescription() {
        return "查询系统操作日志. 支持按模块 / 操作类型 / 用户 / Entity 类型 / 时间范围筛选, "
                + "返回带字段级 diff 的操作记录. "
                + "适用场景: 用户问'最近谁改过 XX'、'今天有几次删除操作'、'查 customer 的变更历史'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> module = new HashMap<>();
        module.put("type", "string");
        module.put("description", "业务模块, 大写下划线, 如 CUSTOMER / MATERIAL / PURCHASE_ORDER. 不填查全部.");
        properties.put("module", module);

        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型. 不填查全部.");
        action.put("enum", Arrays.asList("CREATE", "UPDATE", "DELETE", "EXPORT", "IMPORT", "OTHER"));
        properties.put("action", action);

        Map<String, Object> entityType = new HashMap<>();
        entityType.put("type", "string");
        entityType.put("description", "Entity 简单类名, 如 Customer / MaterialBatch.");
        properties.put("entityType", entityType);

        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "integer");
        userId.put("description", "操作人用户 ID.");
        properties.put("userId", userId);

        Map<String, Object> hours = new HashMap<>();
        hours.put("type", "integer");
        hours.put("description", "查最近 N 小时的记录, 默认 24. 与 days 互斥.");
        hours.put("default", 24);
        properties.put("hours", hours);

        Map<String, Object> days = new HashMap<>();
        days.put("type", "integer");
        days.put("description", "查最近 N 天的记录. 覆盖 hours.");
        properties.put("days", days);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "返回条数上限, 默认 20, 最多 100.");
        size.put("default", 20);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String module = getString(params, "module");
        String action = getString(params, "action");
        String entityType = getString(params, "entityType");
        Long userId = getLong(params, "userId");
        Integer days = getInteger(params, "days", null);
        Integer hours = getInteger(params, "hours", 24);
        Integer size = getInteger(params, "size", 20);
        if (size == null || size <= 0) size = 20;
        if (size > 100) size = 100;

        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = days != null
                ? end.minusDays(days)
                : end.minusHours(hours == null ? 24 : hours);

        Page<OperationLog> page = repo.findByFilters(
                factoryId, module, action, userId, entityType, null,
                start, end, PageRequest.of(0, size));

        List<Map<String, Object>> rows = new ArrayList<>(page.getContent().size());
        for (OperationLog logRow : page.getContent()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", logRow.getId());
            r.put("createdAt", logRow.getCreatedAt());
            r.put("user", logRow.getUsername() != null ? logRow.getUsername()
                    : (logRow.getUserId() != null ? "#" + logRow.getUserId() : null));
            r.put("module", logRow.getModule());
            r.put("action", logRow.getAction());
            r.put("entityType", logRow.getEntityType());
            r.put("entityId", logRow.getEntityId());
            r.put("success", logRow.getSuccess());
            r.put("summary", logRow.getSummary());
            // diff 仅给 first 5 fields 防止 LLM context 爆
            if (logRow.getDiff() != null) {
                List<Map<String, Object>> diff = logRow.getDiff();
                r.put("diff", diff.size() > 5 ? diff.subList(0, 5) : diff);
                r.put("diffTotal", diff.size());
            }
            rows.add(r);
        }

        Map<String, Object> queryConditions = new LinkedHashMap<>();
        if (module != null) queryConditions.put("module", module);
        if (action != null) queryConditions.put("action", action);
        if (entityType != null) queryConditions.put("entityType", entityType);
        if (userId != null) queryConditions.put("userId", userId);
        queryConditions.put("rangeStart", start);
        queryConditions.put("rangeEnd", end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotalElements());
        result.put("returned", rows.size());
        result.put("queryConditions", queryConditions);
        result.put("logs", rows);

        log.info("[OperationLogQueryTool] factory={} module={} action={} entityType={} hours={}/{} → total={}",
                factoryId, module, action, entityType, hours, days, page.getTotalElements());
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
