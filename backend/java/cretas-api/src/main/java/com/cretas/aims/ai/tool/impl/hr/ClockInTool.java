package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 签到打卡工具
 *
 * 执行签到打卡操作，记录当前用户的上班签到时间。
 * 作为操作类Tool，用户上下文从JWT获取，无需额外必需参数。
 *
 * Intent Code: CLOCK_IN
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ClockInTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Override
    public String getToolName() {
        return "clock_in";
    }

    @Override
    public String getDescription() {
        return "执行签到打卡。记录当前用户的上班签到时间，支持添加签到备注和位置信息。" +
                "适用场景：上班打卡、补签到。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // remark: 签到备注（可选）
        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "签到备注，如外勤说明");
        properties.put("remark", remark);

        // location: 签到位置（可选）
        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "签到位置描述");
        properties.put("location", location);

        // latitude: 纬度（可选）
        Map<String, Object> latitude = new HashMap<>();
        latitude.put("type", "number");
        latitude.put("description", "签到位置纬度");
        properties.put("latitude", latitude);

        // longitude: 经度（可选）
        Map<String, Object> longitude = new HashMap<>();
        longitude.put("type", "number");
        longitude.put("description", "签到位置经度");
        properties.put("longitude", longitude);

        schema.put("properties", properties);
        // 签到操作无必需参数，用户信息从JWT获取
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 签到操作无必需参数，用户信息从context获取
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long userId = getLong(context, "userId");
        String location = getString(params, "location");
        String device = getString(params, "device", "AI_TOOL");

        Object latObj = params.get("latitude");
        Object lngObj = params.get("longitude");
        Double latitude = latObj != null ? ((Number) latObj).doubleValue() : null;
        Double longitude = lngObj != null ? ((Number) lngObj).doubleValue() : null;

        try {
            TimeClockRecord record;
            if (latitude != null && longitude != null) {
                record = timeClockService.clockIn(factoryId, userId, location, device, latitude, longitude);
            } else {
                record = timeClockService.clockIn(factoryId, userId, location, device);
            }
            log.info("签到打卡成功: factoryId={}, userId={}", factoryId, userId);
            return buildSimpleResult("签到打卡成功", record);
        } catch (Exception e) {
            log.error("签到打卡失败: factoryId={}, userId={}", factoryId, userId, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "remark", "请问需要添加签到备注吗？",
            "location", "请问需要记录签到位置吗？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "remark", "签到备注",
            "location", "签到位置",
            "latitude", "纬度",
            "longitude", "经度"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
