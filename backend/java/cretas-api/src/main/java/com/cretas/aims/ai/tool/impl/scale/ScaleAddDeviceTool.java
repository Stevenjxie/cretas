package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.enums.DeviceCategory;
import com.cretas.aims.entity.scale.ScaleBrandModel;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ScaleBrandModelRepository;
import com.cretas.aims.repository.ScaleProtocolConfigRepository;
import com.cretas.aims.util.ScaleBrandMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电子秤设备添加工具
 *
 * 通过自然语言描述添加 IoT 电子秤设备。支持品牌/型号自动识别、协议自动匹配。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleAddDeviceTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ScaleBrandModelRepository brandModelRepository;

    @Autowired
    private ScaleProtocolConfigRepository protocolRepository;

    @Autowired
    private ScaleBrandMatcher brandMatcher;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "scale_add_device";
    }

    @Override
    public String getDescription() {
        return "添加新的 IoT 电子秤设备。支持指定设备名称、IP地址、端口、协议等配置。" +
                "可自动识别品牌型号并匹配对应的通信协议。" +
                "适用场景：添加新电子秤、配置设备连接参数。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // name: 设备名称（必需）
        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "设备名称，例如「包装车间柯力电子秤」");
        properties.put("name", name);

        // ip: IP 地址（必需）
        Map<String, Object> ip = new HashMap<>();
        ip.put("type", "string");
        ip.put("description", "设备 IP 地址，例如 192.168.1.100");
        properties.put("ip", ip);

        // port: 端口号（必需）
        Map<String, Object> port = new HashMap<>();
        port.put("type", "integer");
        port.put("description", "通信端口号，例如 502 (Modbus), 9600 (串口服务器)");
        properties.put("port", port);

        // protocol: 协议类型（必需）
        Map<String, Object> protocol = new HashMap<>();
        protocol.put("type", "string");
        protocol.put("description", "通信协议类型或协议ID");
        protocol.put("enum", Arrays.asList(
                "MODBUS_RTU",
                "MODBUS_TCP",
                "SERIAL_ASCII",
                "TCP_SOCKET",
                "MQTT"
        ));
        properties.put("protocol", protocol);

        // workstationId: 工位ID（可选）
        Map<String, Object> workstationId = new HashMap<>();
        workstationId.put("type", "string");
        workstationId.put("description", "关联的工位ID，用于绑定到特定工作站");
        properties.put("workstationId", workstationId);

        // location: 位置（可选）
        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "设备安装位置，例如「包装车间」");
        properties.put("location", location);

        // brandCode: 品牌代码（可选）
        Map<String, Object> brandCode = new HashMap<>();
        brandCode.put("type", "string");
        brandCode.put("description", "品牌代码，例如 KELI (柯力), YAOHUA (耀华)");
        properties.put("brandCode", brandCode);

        // modelCode: 型号代码（可选）
        Map<String, Object> modelCode = new HashMap<>();
        modelCode.put("type", "string");
        modelCode.put("description", "型号代码，例如 D2008, XK3190-A9");
        properties.put("modelCode", modelCode);

        // serialPort: 串口号（可选，用于串口通信）
        Map<String, Object> serialPort = new HashMap<>();
        serialPort.put("type", "string");
        serialPort.put("description", "串口号，例如 COM3, /dev/ttyUSB0");
        properties.put("serialPort", serialPort);

        // baudRate: 波特率（可选）
        Map<String, Object> baudRate = new HashMap<>();
        baudRate.put("type", "integer");
        baudRate.put("description", "波特率，例如 9600, 19200, 115200");
        baudRate.put("default", 9600);
        properties.put("baudRate", baudRate);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("name", "ip", "port", "protocol"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("name", "ip", "port", "protocol");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "name", "请提供设备名称，例如「包装车间柯力电子秤」",
            "ip", "请提供设备的 IP 地址，例如 192.168.1.100",
            "port", "请提供通信端口号，例如 502 (Modbus) 或 9600",
            "protocol", "请选择通信协议：MODBUS_RTU, MODBUS_TCP, SERIAL_ASCII, TCP_SOCKET, MQTT"
        );
        return questions.getOrDefault(paramName, null);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "name", "设备名称",
            "ip", "IP地址",
            "port", "端口号",
            "protocol", "通信协议",
            "workstationId", "工位ID",
            "location", "安装位置",
            "brandCode", "品牌代码",
            "modelCode", "型号代码"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行添加电子秤设备 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析参数
        String name = getString(params, "name");
        String ip = getString(params, "ip");
        Integer port = getInteger(params, "port");
        String protocol = getString(params, "protocol");
        String workstationId = getString(params, "workstationId");
        String location = getString(params, "location");
        String brandCode = getString(params, "brandCode");
        String modelCode = getString(params, "modelCode");
        String serialPort = getString(params, "serialPort");
        Integer baudRate = getInteger(params, "baudRate", 9600);

        // 生成设备编码
        long count = equipmentRepository.countByFactoryId(factoryId);
        String equipmentCode = "SCALE-" + String.format("%04d", count + 1);

        // 检查设备编码是否已存在
        while (equipmentRepository.existsByFactoryIdAndEquipmentCode(factoryId, equipmentCode)) {
            count++;
            equipmentCode = "SCALE-" + String.format("%04d", count + 1);
        }

        // 查找匹配的品牌型号
        String brandModelId = null;
        String protocolId = null;
        String brandName = null;

        if (brandCode != null && !brandCode.isEmpty()) {
            Optional<ScaleBrandModel> brandModelOpt = brandMatcher.findBrandModel(brandCode, modelCode);
            if (brandModelOpt.isPresent()) {
                ScaleBrandModel brandModel = brandModelOpt.get();
                brandModelId = brandModel.getId();
                brandName = brandModel.getBrandName();
                if (brandModel.getDefaultProtocolId() != null) {
                    protocolId = brandModel.getDefaultProtocolId();
                }
            }
        }

        // 如果没有通过品牌型号找到协议，尝试通过协议类型查找
        if (protocolId == null && protocol != null) {
            // 假设 protocol 可能是协议ID或协议类型代码
            if (protocolRepository.existsById(protocol)) {
                protocolId = protocol;
            }
        }

        // 创建设备实体
        FactoryEquipment equipment = new FactoryEquipment();
        equipment.setFactoryId(factoryId);
        equipment.setEquipmentCode(equipmentCode);
        equipment.setCode(equipmentCode);
        equipment.setEquipmentName(name);
        equipment.setType("scale");
        equipment.setDeviceCategory(DeviceCategory.IOT_SCALE);
        equipment.setLocation(location);
        equipment.setStatus("idle");
        equipment.setManufacturer(brandName);
        equipment.setModel(modelCode);
        equipment.setScaleBrandModelId(brandModelId);
        equipment.setScaleProtocolId(protocolId);
        equipment.setIotDeviceCode("SCALE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // 设置连接参数
        Map<String, Object> connectionParams = new LinkedHashMap<>();
        connectionParams.put("ip", ip);
        connectionParams.put("port", port);
        connectionParams.put("protocol", protocol);
        if (serialPort != null) {
            connectionParams.put("serialPort", serialPort);
        }
        connectionParams.put("baudRate", baudRate);
        connectionParams.put("dataBits", 8);
        connectionParams.put("stopBits", 1);
        connectionParams.put("parity", "NONE");

        equipment.setScaleConnectionParams(objectMapper.writeValueAsString(connectionParams));

        // 设置关联的工位
        if (workstationId != null && !workstationId.isEmpty()) {
            equipment.setNotes("关联工位: " + workstationId);
        }

        // 保存设备
        equipment = equipmentRepository.save(equipment);

        log.info("添加电子秤设备成功: id={}, code={}, name={}", equipment.getId(), equipmentCode, name);

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", equipment.getId());
        result.put("equipmentCode", equipmentCode);
        result.put("equipmentName", name);
        result.put("iotDeviceCode", equipment.getIotDeviceCode());
        result.put("ip", ip);
        result.put("port", port);
        result.put("protocol", protocol);
        result.put("protocolId", protocolId);
        result.put("brandModelId", brandModelId);
        result.put("location", location);
        result.put("status", "idle");

        if (workstationId != null) {
            result.put("workstationId", workstationId);
        }

        return buildSimpleResult(
                String.format("成功添加电子秤设备: %s [%s]", name, equipmentCode),
                result
        );
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
