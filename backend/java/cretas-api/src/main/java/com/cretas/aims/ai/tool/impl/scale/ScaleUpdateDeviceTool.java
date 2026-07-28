package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ScaleProtocolConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电子秤设备更新工具
 *
 * 更新 IoT 电子秤设备的配置信息，包括名称、位置、状态、协议等。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleUpdateDeviceTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ScaleProtocolConfigRepository protocolRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "scale_update_device";
    }

    @Override
    public String getDescription() {
        return "更新 IoT 电子秤设备信息。支持修改设备名称、位置、状态、协议等配置。" +
                "适用场景：修改设备配置、更新设备状态、变更设备位置、绑定新协议。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // deviceId: 设备ID（必需）
        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "integer");
        deviceId.put("description", "要更新的设备ID");
        properties.put("deviceId", deviceId);

        // equipmentName: 设备名称（可选）
        Map<String, Object> equipmentName = new HashMap<>();
        equipmentName.put("type", "string");
        equipmentName.put("description", "新的设备名称");
        properties.put("equipmentName", equipmentName);

        // location: 位置（可选）
        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "新的设备位置");
        properties.put("location", location);

        // status: 状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "新的设备状态");
        status.put("enum", Arrays.asList("idle", "active", "offline", "error", "disabled"));
        properties.put("status", status);

        // protocolId: 协议ID（可选）
        Map<String, Object> protocolId = new HashMap<>();
        protocolId.put("type", "string");
        protocolId.put("description", "要绑定的协议ID");
        properties.put("protocolId", protocolId);

        // mqttTopic: MQTT 主题（可选）
        Map<String, Object> mqttTopic = new HashMap<>();
        mqttTopic.put("type", "string");
        mqttTopic.put("description", "MQTT 订阅主题");
        properties.put("mqttTopic", mqttTopic);

        // ip: IP 地址（可选）
        Map<String, Object> ip = new HashMap<>();
        ip.put("type", "string");
        ip.put("description", "设备 IP 地址");
        properties.put("ip", ip);

        // port: 端口号（可选）
        Map<String, Object> port = new HashMap<>();
        port.put("type", "integer");
        port.put("description", "通信端口号");
        properties.put("port", port);

        // serialPort: 串口号（可选）
        Map<String, Object> serialPort = new HashMap<>();
        serialPort.put("type", "string");
        serialPort.put("description", "串口号");
        properties.put("serialPort", serialPort);

        // baudRate: 波特率（可选）
        Map<String, Object> baudRate = new HashMap<>();
        baudRate.put("type", "integer");
        baudRate.put("description", "波特率");
        properties.put("baudRate", baudRate);

        // notes: 备注（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "设备备注信息");
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("deviceId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("deviceId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("deviceId".equals(paramName)) {
            return "请提供要更新的设备ID";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.ofEntries(
            Map.entry("deviceId", "设备ID"),
            Map.entry("equipmentName", "设备名称"),
            Map.entry("location", "位置"),
            Map.entry("status", "状态"),
            Map.entry("protocolId", "协议ID"),
            Map.entry("mqttTopic", "MQTT主题"),
            Map.entry("ip", "IP地址"),
            Map.entry("port", "端口号"),
            Map.entry("serialPort", "串口号"),
            Map.entry("baudRate", "波特率"),
            Map.entry("notes", "备注")
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行更新电子秤设备 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long deviceId = getLong(params, "deviceId");

        // 查找设备
        Optional<FactoryEquipment> deviceOpt = equipmentRepository.findByIdAndFactoryId(deviceId, factoryId);
        if (deviceOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到指定的设备");
        }

        FactoryEquipment device = deviceOpt.get();

        // 验证是否为电子秤设备
        if (device.getUnifiedDeviceType() != UnifiedDeviceType.SCALE) {
            throw new IllegalArgumentException("该设备不是 IoT 电子秤设备");
        }

        // 记录更新的字段
        List<String> updatedFields = new ArrayList<>();
        Map<String, Object> changes = new LinkedHashMap<>();

        // 更新设备名称
        String equipmentName = getString(params, "equipmentName");
        if (equipmentName != null && !equipmentName.isEmpty()) {
            changes.put("equipmentName", Map.of("from", device.getEquipmentName(), "to", equipmentName));
            device.setEquipmentName(equipmentName);
            updatedFields.add("设备名称");
        }

        // 更新位置
        String location = getString(params, "location");
        if (location != null) {
            changes.put("location", Map.of("from", device.getLocation(), "to", location));
            device.setLocation(location);
            updatedFields.add("位置");
        }

        // 更新状态
        String status = getString(params, "status");
        if (status != null && !status.isEmpty()) {
            changes.put("status", Map.of("from", device.getStatus(), "to", status));
            device.setStatus(status);
            updatedFields.add("状态");
        }

        // 更新协议
        String protocolId = getString(params, "protocolId");
        if (protocolId != null && !protocolId.isEmpty()) {
            // 验证协议存在
            if (!protocolRepository.existsById(protocolId)) {
                throw new IllegalArgumentException("指定的协议不存在: " + protocolId);
            }
            changes.put("protocolId", Map.of("from", device.getScaleProtocolId(), "to", protocolId));
            device.setScaleProtocolId(protocolId);
            updatedFields.add("协议");
        }

        // 更新 MQTT 主题
        String mqttTopic = getString(params, "mqttTopic");
        if (mqttTopic != null) {
            changes.put("mqttTopic", Map.of("from", device.getMqttTopic(), "to", mqttTopic));
            device.setMqttTopic(mqttTopic);
            updatedFields.add("MQTT主题");
        }

        // 更新备注
        String notes = getString(params, "notes");
        if (notes != null) {
            changes.put("notes", Map.of("from", device.getNotes(), "to", notes));
            device.setNotes(notes);
            updatedFields.add("备注");
        }

        // 更新连接参数
        String ip = getString(params, "ip");
        Integer port = getInteger(params, "port");
        String serialPort = getString(params, "serialPort");
        Integer baudRate = getInteger(params, "baudRate");

        if (ip != null || port != null || serialPort != null || baudRate != null) {
            // 解析现有连接参数
            Map<String, Object> connectionParams = new LinkedHashMap<>();
            if (device.getScaleConnectionParams() != null && !device.getScaleConnectionParams().isEmpty()) {
                try {
                    connectionParams = objectMapper.readValue(device.getScaleConnectionParams(), Map.class);
                } catch (Exception e) {
                    log.warn("解析现有连接参数失败，将重建: {}", e.getMessage());
                }
            }

            if (ip != null) {
                connectionParams.put("ip", ip);
                updatedFields.add("IP地址");
            }
            if (port != null) {
                connectionParams.put("port", port);
                updatedFields.add("端口号");
            }
            if (serialPort != null) {
                connectionParams.put("serialPort", serialPort);
                updatedFields.add("串口号");
            }
            if (baudRate != null) {
                connectionParams.put("baudRate", baudRate);
                updatedFields.add("波特率");
            }

            device.setScaleConnectionParams(objectMapper.writeValueAsString(connectionParams));
            changes.put("connectionParams", connectionParams);
        }

        // 检查是否有实际更新
        if (updatedFields.isEmpty()) {
            throw new IllegalArgumentException("没有提供要更新的字段");
        }

        // 保存设备
        equipmentRepository.save(device);

        log.info("更新电子秤设备成功: id={}, updatedFields={}", deviceId, updatedFields);

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("equipmentCode", device.getEquipmentCode());
        result.put("equipmentName", device.getEquipmentName());
        result.put("updatedFields", updatedFields);
        result.put("changes", changes);

        return buildSimpleResult(
                "设备更新成功!\n更新字段: " + String.join(", ", updatedFields),
                result
        );
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
