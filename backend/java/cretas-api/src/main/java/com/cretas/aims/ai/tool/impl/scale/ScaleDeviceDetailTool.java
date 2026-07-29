package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.scale.ScaleBrandModelDTO;
import com.cretas.aims.dto.scale.ScaleProtocolDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ScaleBrandModelRepository;
import com.cretas.aims.repository.ScaleProtocolConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电子秤设备详情查询工具
 *
 * 提供 IoT 电子秤设备的详细信息查询，包括设备配置、关联的协议和品牌型号信息。
 *
 * factoryId 隔离豁免说明: 主查询 equipmentRepository.findByIdAndFactoryId 已带
 * factoryId 校验 (真正 factory-scoped). 后续 protocolRepository.findById 和
 * brandModelRepository.findById 查询的是**全局共享主数据** (秤协议配置/品牌型号库,
 * 不同工厂共用), 不涉及跨工厂业务数据泄露.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleDeviceDetailTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ScaleProtocolConfigRepository protocolRepository;

    @Autowired
    private ScaleBrandModelRepository brandModelRepository;

    @Override
    public String getToolName() {
        return "scale_device_detail";
    }

    @Override
    public String getDescription() {
        return "查询 IoT 电子秤设备详情。返回设备的完整配置信息，包括连接参数、协议配置、品牌型号等。" +
                "适用场景：查看设备详细配置、排查连接问题、检查协议绑定状态。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // deviceId: 设备ID（必需）
        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "integer");
        deviceId.put("description", "设备ID，必须是 IoT 电子秤设备的 ID");
        properties.put("deviceId", deviceId);

        // deviceCode: 设备编码（可选，用于通过编码查询）
        Map<String, Object> deviceCode = new HashMap<>();
        deviceCode.put("type", "string");
        deviceCode.put("description", "设备编码，例如 SCALE-0001，可替代 deviceId 使用");
        properties.put("deviceCode", deviceCode);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("deviceId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // deviceId 或 deviceCode 至少需要一个，这里返回空列表，在 doExecute 中手动验证
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行电子秤设备详情查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long deviceId = getLong(params, "deviceId");
        String deviceCode = getString(params, "deviceCode");

        // 验证参数
        if (deviceId == null && (deviceCode == null || deviceCode.trim().isEmpty())) {
            throw new IllegalArgumentException("请提供 deviceId 或 deviceCode");
        }

        // 查找设备
        Optional<FactoryEquipment> deviceOpt = Optional.empty();

        if (deviceId != null) {
            deviceOpt = equipmentRepository.findByIdAndFactoryId(deviceId, factoryId);
        } else if (deviceCode != null && !deviceCode.trim().isEmpty()) {
            List<FactoryEquipment> devices = equipmentRepository.findByFactoryId(factoryId);
            String finalDeviceCode = deviceCode.trim();
            deviceOpt = devices.stream()
                    .filter(e -> e.getUnifiedDeviceType() == UnifiedDeviceType.SCALE)
                    .filter(e -> e.getEquipmentCode() != null &&
                            e.getEquipmentCode().equalsIgnoreCase(finalDeviceCode))
                    .findFirst();
        }

        if (deviceOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到指定的设备");
        }

        FactoryEquipment device = deviceOpt.get();

        // 验证是否为电子秤设备
        if (device.getUnifiedDeviceType() != UnifiedDeviceType.SCALE) {
            throw new IllegalArgumentException("该设备不是 IoT 电子秤设备");
        }

        // 构建详情
        Map<String, Object> deviceDetail = new LinkedHashMap<>();
        deviceDetail.put("id", device.getId());
        deviceDetail.put("factoryId", device.getFactoryId());
        deviceDetail.put("equipmentCode", device.getEquipmentCode());
        deviceDetail.put("equipmentName", device.getEquipmentName());
        deviceDetail.put("type", device.getType());
        deviceDetail.put("status", device.getStatus());
        deviceDetail.put("location", device.getLocation());
        deviceDetail.put("manufacturer", device.getManufacturer());
        deviceDetail.put("model", device.getModel());
        deviceDetail.put("serialNumber", device.getSerialNumber());

        // IoT 相关字段
        deviceDetail.put("iotDeviceCode", device.getIotDeviceCode());
        deviceDetail.put("mqttTopic", device.getMqttTopic());
        deviceDetail.put("scaleProtocolId", device.getScaleProtocolId());
        deviceDetail.put("scaleBrandModelId", device.getScaleBrandModelId());
        deviceDetail.put("connectionParams", device.getScaleConnectionParams());

        // 实时数据
        deviceDetail.put("lastWeightReading", device.getLastWeightReading());
        deviceDetail.put("lastWeightTime", device.getLastWeightTime());
        deviceDetail.put("lastDataReceived", device.getLastDataReceived());

        // 加载关联的协议信息
        if (device.getScaleProtocolId() != null) {
            protocolRepository.findById(device.getScaleProtocolId())
                    .ifPresent(protocol -> {
                        deviceDetail.put("protocol", ScaleProtocolDTO.fromEntity(protocol));
                    });
        }

        // 加载关联的品牌型号信息
        if (device.getScaleBrandModelId() != null) {
            brandModelRepository.findById(device.getScaleBrandModelId())
                    .ifPresent(brandModel -> {
                        deviceDetail.put("brandModel", ScaleBrandModelDTO.fromEntity(brandModel));
                    });
        }

        // 备注信息
        deviceDetail.put("notes", device.getNotes());
        deviceDetail.put("createdAt", device.getCreatedAt());
        deviceDetail.put("updatedAt", device.getUpdatedAt());

        log.info("电子秤设备详情查询完成 - 设备ID: {}, 设备编码: {}", device.getId(), device.getEquipmentCode());

        return buildSimpleResult("设备详情查询成功", deviceDetail);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
