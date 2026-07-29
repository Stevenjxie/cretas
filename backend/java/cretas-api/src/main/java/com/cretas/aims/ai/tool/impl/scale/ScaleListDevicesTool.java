package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.cretas.aims.repository.EquipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 电子秤设备列表查询工具
 *
 * 提供 IoT 电子秤设备的分页查询功能，支持按状态筛选。
 * 作为查询类 Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleListDevicesTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Override
    public String getToolName() {
        return "scale_list_devices";
    }

    @Override
    public String getDescription() {
        return "查询 IoT 电子秤设备列表。支持按状态筛选，返回设备基本信息和状态分布统计。" +
                "适用场景：查看所有电子秤、查找特定状态的设备、获取设备统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // status: 状态筛选（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "设备状态筛选");
        status.put("enum", Arrays.asList(
                "idle",      // 空闲
                "active",    // 运行中
                "offline",   // 离线
                "error",     // 故障
                "disabled"   // 停用
        ));
        properties.put("status", status);

        // keyword: 关键词搜索（可选）
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "关键词搜索，匹配设备名称或编码");
        properties.put("keyword", keyword);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认20）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 20);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);

        // 查询类 Tool 无必需参数
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 查询类 Tool 无必需参数
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行电子秤设备列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 20);

        // 解析筛选参数
        String status = getString(params, "status");
        String keyword = getString(params, "keyword");

        // 查询设备列表
        List<FactoryEquipment> allDevices = equipmentRepository.findByFactoryId(factoryId);

        // 过滤 IoT 秤设备
        List<FactoryEquipment> scaleDevices = allDevices.stream()
                .filter(e -> e.getUnifiedDeviceType() == UnifiedDeviceType.SCALE)
                .filter(e -> status == null || status.isEmpty() ||
                        (e.getStatus() != null && e.getStatus().equalsIgnoreCase(status)))
                .filter(e -> keyword == null || keyword.isEmpty() ||
                        (e.getEquipmentName() != null && e.getEquipmentName().toLowerCase().contains(keyword.toLowerCase())) ||
                        (e.getEquipmentCode() != null && e.getEquipmentCode().toLowerCase().contains(keyword.toLowerCase())))
                .collect(Collectors.toList());

        // 计算分页
        int totalElements = scaleDevices.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<FactoryEquipment> pagedDevices = fromIndex < totalElements
                ? scaleDevices.subList(fromIndex, toIndex)
                : Collections.emptyList();

        // 构建设备摘要列表
        List<Map<String, Object>> deviceSummaries = pagedDevices.stream()
                .map(device -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", device.getId());
                    summary.put("code", device.getEquipmentCode());
                    summary.put("name", device.getEquipmentName());
                    summary.put("status", device.getStatus());
                    summary.put("location", device.getLocation());
                    summary.put("manufacturer", device.getManufacturer());
                    summary.put("model", device.getModel());
                    summary.put("iotDeviceCode", device.getIotDeviceCode());
                    summary.put("lastReading", device.getLastWeightReading());
                    summary.put("lastReadingTime", device.getLastWeightTime());
                    return summary;
                })
                .collect(Collectors.toList());

        // 统计状态分布
        Map<String, Long> statusDistribution = scaleDevices.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getStatus() != null ? e.getStatus() : "unknown",
                        Collectors.counting()));

        // 构建返回结果
        Map<String, Object> result = buildPageResult(deviceSummaries, totalElements, totalPages, page);
        result.put("statusDistribution", statusDistribution);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (status != null) queryConditions.put("status", status);
        if (keyword != null) queryConditions.put("keyword", keyword);
        result.put("queryConditions", queryConditions);

        log.info("电子秤设备列表查询完成 - 总记录数: {}, 当前页: {}", totalElements, page);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
