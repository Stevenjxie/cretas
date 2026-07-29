package com.cretas.aims.ai.tool.impl.dahua;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.dahua.DiscoveredDahuaDevice;
import com.cretas.aims.service.dahua.DahuaDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 大华设备发现工具
 *
 * 使用 DHDiscover 协议 (UDP 37810) 自动发现局域网内的大华摄像头设备。
 * 支持广播发现和多网卡并行发现。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Slf4j
@Component
public class DahuaDiscoveryTool extends AbstractBusinessTool {

    @Autowired
    private DahuaDeviceService dahuaDeviceService;

    @Override
    public String getToolName() {
        return "dahua_device_discovery";
    }

    @Override
    public String getDescription() {
        return "自动发现局域网内的大华摄像头设备。使用 DHDiscover 协议扫描网络，" +
                "返回发现的设备列表，包含 IP、型号、MAC 地址等信息。" +
                "适用场景：设备初始化配置、网络摄像头巡检、新设备接入。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // timeout: 发现超时时间
        Map<String, Object> timeout = new HashMap<>();
        timeout.put("type", "integer");
        timeout.put("description", "发现超时时间（毫秒），默认 5000ms");
        timeout.put("default", 5000);
        timeout.put("minimum", 1000);
        timeout.put("maximum", 30000);
        properties.put("timeout", timeout);

        // scanAllInterfaces: 是否扫描所有网卡
        Map<String, Object> scanAll = new HashMap<>();
        scanAll.put("type", "boolean");
        scanAll.put("description", "是否扫描所有网络接口，默认 false（仅广播）");
        scanAll.put("default", false);
        properties.put("scanAllInterfaces", scanAll);

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
        int timeout = getInteger(params, "timeout", 5000);
        boolean scanAllInterfaces = getBoolean(params, "scanAllInterfaces", false);

        log.info("执行大华设备发现 - 工厂ID: {}, 超时: {}ms, 扫描所有接口: {}",
                factoryId, timeout, scanAllInterfaces);

        List<DiscoveredDahuaDevice> devices;
        if (scanAllInterfaces) {
            devices = dahuaDeviceService.discoverOnAllInterfaces(factoryId, timeout);
        } else {
            devices = dahuaDeviceService.discoverDevices(factoryId, timeout);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", devices.size());

        // 转换设备信息为简洁格式
        List<Map<String, Object>> deviceList = devices.stream()
                .map(this::convertDeviceToMap)
                .collect(Collectors.toList());
        result.put("devices", deviceList);

        // 生成友好消息
        if (devices.isEmpty()) {
            result.put("message", "未发现大华设备，请检查网络连接或增加超时时间");
        } else {
            result.put("message", String.format("发现 %d 台大华设备", devices.size()));

            // 统计设备类型
            Map<String, Long> typeCount = devices.stream()
                    .filter(d -> d.getDeviceType() != null)
                    .collect(Collectors.groupingBy(
                            DiscoveredDahuaDevice::getDeviceType,
                            Collectors.counting()
                    ));
            if (!typeCount.isEmpty()) {
                result.put("deviceTypeStats", typeCount);
            }
        }

        return result;
    }

    /**
     * 转换设备信息为 Map
     */
    private Map<String, Object> convertDeviceToMap(DiscoveredDahuaDevice device) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mac", device.getMac());
        map.put("ipAddress", device.getIpAddress());
        map.put("httpPort", device.getHttpPort());
        map.put("tcpPort", device.getPort());
        map.put("deviceType", device.getDeviceType());
        map.put("model", device.getModel());
        map.put("serialNumber", device.getSerialNumber());
        map.put("firmwareVersion", device.getFirmwareVersion());
        map.put("activated", device.getActivated());
        map.put("vendor", device.getVendor());
        return map;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "timeout", "请问设备发现的超时时间设置为多少毫秒？（默认 5000ms）",
                "scanAllInterfaces", "是否要扫描所有网络接口？（多网卡环境建议开启）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "timeout", "超时时间",
                "scanAllInterfaces", "扫描所有接口"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
