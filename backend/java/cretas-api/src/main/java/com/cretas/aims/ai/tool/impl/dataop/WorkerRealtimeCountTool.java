package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.repository.WorkerAssignmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 车间实时工人数查询工具
 *
 * 查询车间当前实时工人数量和人员分布。
 * Intent Code: WORKER_IN_SHOP_REALTIME_COUNT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class WorkerRealtimeCountTool extends AbstractBusinessTool {

    @Autowired
    private EmployeeWorkSessionRepository workSessionRepository;

    @Autowired
    private WorkerAssignmentRepository workerAssignmentRepository;

    @Override
    public String getToolName() {
        return "worker_realtime_count";
    }

    @Override
    public String getDescription() {
        return "查询车间当前实时工人数量和人员分布情况。" +
                "适用场景：查看车间有多少人在、实时人员统计。";
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
            // Count active work sessions as proxy for workers currently in the shop
            long activeWorkers = workSessionRepository.countByFactoryIdAndStatus(factoryId, "active");

            // Count workers with checked_in status in assignments
            List<WorkerAssignment> checkedInAssignments = workerAssignmentRepository
                    .findByFactoryIdAndStatus(factoryId, WorkerAssignment.AssignmentStatus.checked_in);
            long checkedInCount = checkedInAssignments != null ? checkedInAssignments.size() : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activeWorkSessions", activeWorkers);
            result.put("checkedInWorkers", checkedInCount);
            result.put("queryTime", LocalDateTime.now().toString());
            result.put("note", "activeWorkSessions: 当前有活跃工作会话的员工数; checkedInWorkers: 已签到排程的工人数");

            return buildSimpleResult("车间实时工人数查询成功", result);
        } catch (Exception e) {
            log.error("车间实时工人数查询失败: factoryId={}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
