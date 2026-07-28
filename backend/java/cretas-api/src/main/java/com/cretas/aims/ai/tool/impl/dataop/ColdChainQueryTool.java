package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.repository.IotDeviceDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 冷链温度查询工具
 *
 * 查询冷库温度记录。
 * Intent Code: COLD_CHAIN_TEMPERATURE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ColdChainQueryTool extends AbstractBusinessTool {

    @Autowired
    private IotDeviceDataRepository iotDeviceDataRepository;

    @Override
    public String getToolName() {
        return "cold_chain_query";
    }

    @Override
    public String getDescription() {
        return "查询冷库温度记录。支持按冷库编号和时间范围查询。" +
                "适用场景：查看冷库温度、温度历史记录、冷链监控。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> warehouseId = new HashMap<>();
        warehouseId.put("type", "string");
        warehouseId.put("description", "冷库编号或名称");
        properties.put("warehouseId", warehouseId);

        Map<String, Object> timeRange = new HashMap<>();
        timeRange.put("type", "string");
        timeRange.put("description", "时间范围，如'今天'、'最近7天'");
        properties.put("timeRange", timeRange);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String warehouseId = getString(params, "warehouseId");
        String timeRange = getString(params, "timeRange");

        try {
            List<IotDeviceData> records;

            if (warehouseId != null && !warehouseId.isBlank()) {
                // Query by device code (warehouse ID maps to IoT device)
                LocalDateTime endTime = LocalDateTime.now();
                LocalDateTime startTime = resolveStartTime(timeRange, endTime);

                records = iotDeviceDataRepository.findByFactoryIdAndDeviceIdAndTimeRange(factoryId, warehouseId, startTime, endTime);

                // Filter to only TEMPERATURE data type
                records = records.stream()
                        .filter(d -> "TEMPERATURE".equals(d.getDataType()))
                        .limit(50)
                        .collect(java.util.stream.Collectors.toList());
            } else {
                // Query by factory — recent temperature data
                LocalDateTime endTime = LocalDateTime.now();
                LocalDateTime startTime = resolveStartTime(timeRange, endTime);

                records = iotDeviceDataRepository.findByTimeRange(factoryId, startTime, endTime).stream()
                        .filter(d -> "TEMPERATURE".equals(d.getDataType()))
                        .limit(50)
                        .collect(java.util.stream.Collectors.toList());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", records);
            result.put("count", records.size());
            result.put("warehouseId", warehouseId);
            result.put("timeRange", timeRange);

            if (records.isEmpty()) {
                result.put("note", "未找到温度数据。冷链监控需要已配置的IoT温度传感器设备。");
            }

            return buildSimpleResult("冷链温度查询成功", result);
        } catch (Exception e) {
            log.error("冷链温度查询失败: factoryId={}, warehouseId={}", factoryId, warehouseId, e);
            throw e;
        }
    }

    private LocalDateTime resolveStartTime(String timeRange, LocalDateTime endTime) {
        if (timeRange == null) return endTime.minusDays(1);
        if (timeRange.contains("7天") || timeRange.contains("一周") || timeRange.contains("本周")) {
            return endTime.minusDays(7);
        }
        if (timeRange.contains("30天") || timeRange.contains("一月") || timeRange.contains("本月")) {
            return endTime.minusDays(30);
        }
        // Default: today
        return endTime.toLocalDate().atStartOfDay();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
