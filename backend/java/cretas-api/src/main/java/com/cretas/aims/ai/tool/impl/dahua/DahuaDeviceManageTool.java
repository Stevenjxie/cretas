package com.cretas.aims.ai.tool.impl.dahua;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.isapi.SmartAnalysisDTO;
import com.cretas.aims.entity.dahua.DahuaDevice;
import com.cretas.aims.service.dahua.DahuaDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 大华设备管理工具
 *
 * 管理大华设备的完整生命周期：
 * - 设备列表查询
 * - 设备添加
 * - 连接测试
 * - 获取流地址
 * - 抓拍图片
 * - 查询智能分析能力
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Slf4j
@Component
public class DahuaDeviceManageTool extends AbstractBusinessTool {

    @Autowired
    private DahuaDeviceService dahuaDeviceService;

    @Override
    public String getToolName() {
        return "dahua_device_manage";
    }

    @Override
    public String getDescription() {
        return "管理大华设备：查询设备列表、添加设备、测试连接、获取流地址、抓拍图片、查询智能分析能力。" +
                "适用场景：设备管理、监控预览、视频流获取、设备状态检查。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // action: 操作类型（必需）
        Map<String, Object> action = new HashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型：list=设备列表，add=添加设备，test=测试连接，" +
                "streams=获取流地址，capture=抓拍，capabilities=查询智能能力，info=设备信息");
        action.put("enum", Arrays.asList("list", "add", "test", "streams", "capture", "capabilities", "info"));
        properties.put("action", action);

        // deviceId: 设备ID（部分操作需要）
        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "string");
        deviceId.put("description", "设备ID（test/streams/capture/capabilities/info 操作需要）");
        properties.put("deviceId", deviceId);

        // channelId: 通道ID
        Map<String, Object> channelId = new HashMap<>();
        channelId.put("type", "integer");
        channelId.put("description", "通道ID（从0开始），默认0");
        channelId.put("default", 0);
        channelId.put("minimum", 0);
        properties.put("channelId", channelId);

        // subStream: 是否子码流
        Map<String, Object> subStream = new HashMap<>();
        subStream.put("type", "boolean");
        subStream.put("description", "获取流地址时，是否使用子码流（低分辨率），默认false");
        subStream.put("default", false);
        properties.put("subStream", subStream);

        // === 添加设备所需参数 ===
        Map<String, Object> deviceName = new HashMap<>();
        deviceName.put("type", "string");
        deviceName.put("description", "设备名称（add 操作需要）");
        properties.put("deviceName", deviceName);

        Map<String, Object> ipAddress = new HashMap<>();
        ipAddress.put("type", "string");
        ipAddress.put("description", "设备IP地址（add 操作需要）");
        properties.put("ipAddress", ipAddress);

        Map<String, Object> port = new HashMap<>();
        port.put("type", "integer");
        port.put("description", "HTTP端口，默认80");
        port.put("default", 80);
        properties.put("port", port);

        Map<String, Object> username = new HashMap<>();
        username.put("type", "string");
        username.put("description", "登录用户名（add 操作需要）");
        properties.put("username", username);

        Map<String, Object> password = new HashMap<>();
        password.put("type", "string");
        password.put("description", "登录密码（add 操作需要）");
        properties.put("password", password);

        Map<String, Object> deviceType = new HashMap<>();
        deviceType.put("type", "string");
        deviceType.put("description", "设备类型：IPC/NVR/DVR/XVR");
        deviceType.put("enum", Arrays.asList("IPC", "NVR", "DVR", "XVR"));
        deviceType.put("default", "IPC");
        properties.put("deviceType", deviceType);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("action"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("action");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String action = getString(params, "action", null);

        log.info("执行大华设备管理 - 工厂ID: {}, 操作: {}", factoryId, action);

        switch (action.toLowerCase()) {
            case "list":
                return handleList(factoryId);
            case "add":
                return handleAdd(factoryId, params);
            case "test":
                return handleTest(params);
            case "streams":
                return handleStreams(params);
            case "capture":
                return handleCapture(params);
            case "capabilities":
                return handleCapabilities(params);
            case "info":
                return handleInfo(params);
            default:
                throw new IllegalArgumentException("不支持的操作类型: " + action);
        }
    }

    /**
     * 处理设备列表查询
     */
    private Map<String, Object> handleList(String factoryId) {
        List<DahuaDevice> devices = dahuaDeviceService.listDevices(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", devices.size());

        List<Map<String, Object>> deviceList = devices.stream()
                .map(this::convertDeviceToMap)
                .collect(Collectors.toList());
        result.put("devices", deviceList);

        // 统计设备状态
        Map<String, Long> statusCount = devices.stream()
                .collect(Collectors.groupingBy(
                        d -> d.getStatus().name(),
                        Collectors.counting()
                ));
        result.put("statusStats", statusCount);

        result.put("message", String.format("共 %d 台大华设备", devices.size()));
        return result;
    }

    /**
     * 处理添加设备
     */
    private Map<String, Object> handleAdd(String factoryId, Map<String, Object> params) {
        // 验证必需参数
        String deviceName = getString(params, "deviceName", null);
        String ipAddress = getString(params, "ipAddress", null);
        String username = getString(params, "username", null);
        String password = getString(params, "password", null);

        if (deviceName == null || ipAddress == null || username == null || password == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", "NEED_MORE_INFO");

            List<String> missing = new ArrayList<>();
            if (deviceName == null) missing.add("deviceName");
            if (ipAddress == null) missing.add("ipAddress");
            if (username == null) missing.add("username");
            if (password == null) missing.add("password");

            result.put("missingParameters", missing);
            result.put("message", "添加设备需要提供: " + String.join(", ", missing));
            return result;
        }

        Integer port = getInteger(params, "port", 80);
        String deviceTypeStr = getString(params, "deviceType", "IPC");

        DahuaDevice device = DahuaDevice.builder()
                .factoryId(factoryId)
                .deviceName(deviceName)
                .ipAddress(ipAddress)
                .port(port)
                .username(username)
                .passwordEncrypted(password) // Service 层会自动加密
                .deviceType(DahuaDevice.DeviceType.valueOf(deviceTypeStr))
                .status(DahuaDevice.DeviceStatus.UNKNOWN)
                .build();

        DahuaDevice saved = dahuaDeviceService.addDevice(device);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("deviceId", saved.getId());
        result.put("device", convertDeviceToMap(saved));
        result.put("message", String.format("设备 %s (%s) 添加成功", deviceName, ipAddress));
        return result;
    }

    /**
     * 处理连接测试
     */
    private Map<String, Object> handleTest(Map<String, Object> params) {
        String deviceId = getString(params, "deviceId", null);
        if (deviceId == null) {
            return buildNeedDeviceIdResult("test");
        }

        Map<String, Object> testResult = dahuaDeviceService.testConnection(deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", testResult.get("success"));
        result.put("deviceId", deviceId);
        result.put("status", testResult.get("status"));
        result.put("message", testResult.get("message"));
        return result;
    }

    /**
     * 处理获取流地址
     */
    private Map<String, Object> handleStreams(Map<String, Object> params) {
        String deviceId = getString(params, "deviceId", null);
        if (deviceId == null) {
            return buildNeedDeviceIdResult("streams");
        }

        Integer channelId = getInteger(params, "channelId", 0);
        Boolean subStream = getBoolean(params, "subStream", false);

        String mainStreamUrl = dahuaDeviceService.getStreamUrl(deviceId, channelId, false);
        String subStreamUrl = dahuaDeviceService.getStreamUrl(deviceId, channelId, true);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("deviceId", deviceId);
        result.put("channelId", channelId);
        result.put("mainStream", mainStreamUrl);
        result.put("subStream", subStreamUrl);
        result.put("recommendedStream", subStream ? subStreamUrl : mainStreamUrl);
        result.put("message", String.format("通道 %d 的流地址获取成功", channelId));
        return result;
    }

    /**
     * 处理抓拍
     */
    private Map<String, Object> handleCapture(Map<String, Object> params) throws Exception {
        String deviceId = getString(params, "deviceId", null);
        if (deviceId == null) {
            return buildNeedDeviceIdResult("capture");
        }

        Integer channelId = getInteger(params, "channelId", 0);

        byte[] imageData = dahuaDeviceService.capturePicture(deviceId, channelId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("deviceId", deviceId);
        result.put("channelId", channelId);
        result.put("imageSize", imageData.length);
        result.put("imageSizeKB", String.format("%.2f KB", imageData.length / 1024.0));
        result.put("imageBase64", Base64.getEncoder().encodeToString(imageData));
        result.put("message", String.format("通道 %d 抓拍成功，图片大小: %.2f KB",
                channelId, imageData.length / 1024.0));
        return result;
    }

    /**
     * 处理查询智能能力
     */
    private Map<String, Object> handleCapabilities(Map<String, Object> params) throws Exception {
        String deviceId = getString(params, "deviceId", null);
        if (deviceId == null) {
            return buildNeedDeviceIdResult("capabilities");
        }

        SmartAnalysisDTO.SmartCapabilities caps = dahuaDeviceService.getSmartCapabilities(deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("deviceId", deviceId);
        result.put("smartSupported", caps.getSmartSupported());

        // 支持的功能列表
        List<String> supportedFeatures = new ArrayList<>();
        if (Boolean.TRUE.equals(caps.getLineDetectionSupported())) {
            supportedFeatures.add("越界检测");
            result.put("lineDetectionSupported", true);
        }
        if (Boolean.TRUE.equals(caps.getFieldDetectionSupported())) {
            supportedFeatures.add("区域入侵检测");
            result.put("fieldDetectionSupported", true);
        }
        if (Boolean.TRUE.equals(caps.getFaceDetectionSupported())) {
            supportedFeatures.add("人脸检测");
            result.put("faceDetectionSupported", true);
        }
        if (Boolean.TRUE.equals(caps.getMotionDetectionSupported())) {
            supportedFeatures.add("移动侦测");
            result.put("motionDetectionSupported", true);
        }
        if (Boolean.TRUE.equals(caps.getAudioDetectionSupported())) {
            supportedFeatures.add("音频检测");
            result.put("audioDetectionSupported", true);
        }

        result.put("supportedFeatures", supportedFeatures);
        result.put("message", supportedFeatures.isEmpty()
                ? "该设备不支持智能分析功能"
                : String.format("该设备支持 %d 种智能分析功能: %s",
                supportedFeatures.size(), String.join("、", supportedFeatures)));
        return result;
    }

    /**
     * 处理获取设备信息
     */
    private Map<String, Object> handleInfo(Map<String, Object> params) throws Exception {
        String deviceId = getString(params, "deviceId", null);
        if (deviceId == null) {
            return buildNeedDeviceIdResult("info");
        }

        Map<String, Object> deviceInfo = dahuaDeviceService.getDeviceInfo(deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("deviceId", deviceId);
        result.put("deviceInfo", deviceInfo);
        result.put("message", "设备信息获取成功");
        return result;
    }

    /**
     * 构建需要设备ID的响应
     */
    private Map<String, Object> buildNeedDeviceIdResult(String action) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("status", "NEED_MORE_INFO");
        result.put("missingParameters", Arrays.asList("deviceId"));
        result.put("message", String.format("执行 %s 操作需要提供设备ID", action));
        result.put("clarificationQuestions", Arrays.asList("请问要操作哪个大华设备？请提供设备ID"));
        return result;
    }

    /**
     * 转换设备为 Map
     */
    private Map<String, Object> convertDeviceToMap(DahuaDevice device) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", device.getId());
        map.put("deviceName", device.getDeviceName());
        map.put("deviceType", device.getDeviceType().name());
        map.put("ipAddress", device.getIpAddress());
        map.put("port", device.getPort());
        map.put("status", device.getStatus().name());
        map.put("model", device.getDeviceModel());
        map.put("serialNumber", device.getSerialNumber());
        map.put("channelCount", device.getChannelCount());
        map.put("supportsPtz", device.getSupportsPtz());
        map.put("supportsAudio", device.getSupportsAudio());
        map.put("supportsSmart", device.getSupportsSmart());
        map.put("locationDescription", device.getLocationDescription());
        map.put("lastHeartbeatAt", device.getLastHeartbeatAt());
        return map;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("action", "请问要执行什么操作？（list=设备列表，add=添加设备，test=测试连接，streams=获取流地址，capture=抓拍，capabilities=查询智能能力，info=设备信息）");
        questions.put("deviceId", "请问要操作哪个大华设备？请提供设备ID");
        questions.put("channelId", "请问要操作哪个通道？（从0开始，默认0）");
        questions.put("deviceName", "请问设备名称是什么？");
        questions.put("ipAddress", "请问设备的IP地址是什么？");
        questions.put("username", "请问登录用户名是什么？");
        questions.put("password", "请问登录密码是什么？");
        questions.put("deviceType", "请问设备类型是什么？（IPC/NVR/DVR/XVR）");
        questions.put("subStream", "是否使用子码流（低分辨率）？");
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("action", "操作类型");
        displayNames.put("deviceId", "设备ID");
        displayNames.put("channelId", "通道ID");
        displayNames.put("deviceName", "设备名称");
        displayNames.put("ipAddress", "IP地址");
        displayNames.put("username", "用户名");
        displayNames.put("password", "密码");
        displayNames.put("deviceType", "设备类型");
        displayNames.put("subStream", "子码流");
        displayNames.put("port", "HTTP端口");
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
