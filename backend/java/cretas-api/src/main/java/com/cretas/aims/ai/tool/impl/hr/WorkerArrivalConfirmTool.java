package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工人到岗确认工具
 *
 * 确认工人到岗，执行签到打卡操作。
 *
 * Intent Code: WORKER_ARRIVAL_CONFIRM
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class WorkerArrivalConfirmTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "worker_arrival_confirm";
    }

    @Override
    public String getDescription() {
        return "确认工人到岗。执行签到打卡操作，记录当前用户的上班到岗时间。" +
                "适用场景：工人到岗确认、快速签到。";
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
        log.info("执行工人到岗确认 - 工厂ID: {}", factoryId);

        Long userId = getUserId(context);
        if (userId == null) {
            throw new IllegalArgumentException("无法获取用户信息，请重新登录");
        }

        TimeClockRecord record = timeClockService.clockIn(factoryId, userId, null, null);

        Map<String, Object> result = new HashMap<>();
        result.put("record", record);
        result.put("clockInTime", record.getClockInTime());

        return buildSimpleResult(
                "工人到岗确认成功！打卡时间: " + record.getClockInTime(),
                result
        );
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
