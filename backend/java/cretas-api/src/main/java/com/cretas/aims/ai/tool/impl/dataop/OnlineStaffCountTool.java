package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import com.cretas.aims.repository.TimeClockRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 在线人员统计工具
 *
 * 查询当前在线人员数量。
 * Intent Code: QUERY_ONLINE_STAFF_COUNT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class OnlineStaffCountTool extends AbstractBusinessTool {

    @Autowired
    private EmployeeWorkSessionRepository workSessionRepository;

    @Autowired
    private TimeClockRecordRepository timeClockRecordRepository;

    @Override
    public String getToolName() {
        return "online_staff_count";
    }

    @Override
    public String getDescription() {
        return "查询当前在线人员数量和统计信息。" +
                "适用场景：查看多少人在线、在线人数统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        try {
            // Count active work sessions (employees currently working)
            long activeWorkSessions = workSessionRepository.countByFactoryIdAndStatus(factoryId, "active");

            // Count employees who clocked in today but haven't clocked out
            LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
            LocalDateTime endOfDay = startOfDay.plusDays(1);
            long clockedInToday = timeClockRecordRepository.countDistinctUsersByFactoryIdAndClockDateBetween(
                    factoryId, startOfDay, endOfDay);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activeWorkSessions", activeWorkSessions);
            result.put("clockedInToday", clockedInToday);
            result.put("queryTime", LocalDateTime.now().toString());

            return buildSimpleResult("在线人员统计成功", result);
        } catch (Exception e) {
            log.error("在线人员统计失败: factoryId={}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
