package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.enums.DeviceCategory;
import com.cretas.aims.entity.scale.ScaleBrandModel;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.util.ScaleBrandMatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电子秤设备图片识别添加工具
 *
 * 通过图片（铭牌、规格书等）识别设备信息并自动添加。
 * 使用 AI 视觉模型提取品牌、型号、规格等信息。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleAddDeviceVisionTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ScaleBrandMatcher brandMatcher;

    @Autowired
    private DashScopeVisionClient visionClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "scale_add_device_vision";
    }

    @Override
    public String getDescription() {
        return "通过图片识别添加 IoT 电子秤设备。上传设备铭牌或规格书照片，AI 自动提取设备信息。" +
                "适用场景：快速录入新设备、识别设备铭牌、批量设备登记。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // name: 设备名称（必需，用于补充或覆盖识别结果）
        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "设备名称，如果图片识别失败会使用此名称");
        properties.put("name", name);

        // cameraConfig: 摄像头配置/图片数据（必需）
        Map<String, Object> cameraConfig = new HashMap<>();
        cameraConfig.put("type", "object");
        cameraConfig.put("description", "图片配置，包含 imageBase64 或 imageUrl");
        Map<String, Object> cameraConfigProps = new HashMap<>();

        Map<String, Object> imageBase64 = new HashMap<>();
        imageBase64.put("type", "string");
        imageBase64.put("description", "Base64 编码的图片数据");
        cameraConfigProps.put("imageBase64", imageBase64);

        Map<String, Object> imageUrl = new HashMap<>();
        imageUrl.put("type", "string");
        imageUrl.put("description", "图片 URL 地址");
        cameraConfigProps.put("imageUrl", imageUrl);

        Map<String, Object> imageType = new HashMap<>();
        imageType.put("type", "string");
        imageType.put("description", "图片类型：铭牌、规格书、设备照片");
        imageType.put("enum", Arrays.asList("铭牌", "规格书", "设备照片"));
        imageType.put("default", "铭牌");
        cameraConfigProps.put("imageType", imageType);

        cameraConfig.put("properties", cameraConfigProps);
        properties.put("cameraConfig", cameraConfig);

        // location: 位置（可选）
        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "设备安装位置");
        properties.put("location", location);

        // workstationId: 工位ID（可选）
        Map<String, Object> workstationId = new HashMap<>();
        workstationId.put("type", "string");
        workstationId.put("description", "关联的工位ID");
        properties.put("workstationId", workstationId);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("name", "cameraConfig"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("name", "cameraConfig");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "name", "请提供设备名称，作为识别失败时的备用名称",
            "cameraConfig", "请提供图片数据，包含 imageBase64 (Base64编码图片) 或 imageUrl (图片URL)"
        );
        return questions.getOrDefault(paramName, null);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "name", "设备名称",
            "cameraConfig", "图片配置",
            "location", "安装位置",
            "workstationId", "工位ID"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行图片识别添加电子秤设备 - 工厂ID: {}", factoryId);

        // 解析参数
        String name = getString(params, "name");
        String location = getString(params, "location");
        String workstationId = getString(params, "workstationId");

        // 解析图片配置
        Map<String, Object> cameraConfig = (Map<String, Object>) params.get("cameraConfig");
        if (cameraConfig == null) {
            throw new IllegalArgumentException("请提供 cameraConfig 参数");
        }

        String imageBase64 = cameraConfig.get("imageBase64") != null ? cameraConfig.get("imageBase64").toString() : null;
        String imageUrl = cameraConfig.get("imageUrl") != null ? cameraConfig.get("imageUrl").toString() : null;
        String imageType = cameraConfig.get("imageType") != null ? cameraConfig.get("imageType").toString() : "铭牌";

        if (imageBase64 == null && imageUrl == null) {
            throw new IllegalArgumentException("请提供 imageBase64 或 imageUrl");
        }

        // 检查视觉服务是否可用
        if (!visionClient.isAvailable()) {
            throw new IllegalStateException("图片识别服务未配置，请联系管理员检查 DashScope API 配置");
        }

        // 目前仅支持 Base64
        if (imageBase64 == null) {
            throw new IllegalArgumentException("目前仅支持 Base64 编码的图片，请使用 imageBase64 传递图片数据");
        }

        log.debug("调用 DashScopeVisionClient 进行设备铭牌识别, imageType={}", imageType);

        // 调用视觉服务识别图片
        DashScopeVisionClient.ScaleRecognitionResult visionResult = visionClient.parseScaleImage(imageBase64, imageType);

        if (!visionResult.isSuccess()) {
            log.error("图片识别失败: {}", visionResult.getMessage());
            throw new RuntimeException("图片识别失败: " +
                    (visionResult.getMessage() != null ? visionResult.getMessage() : "无法解析图片内容"));
        }

        // 提取识别结果
        String brand = visionResult.getBrand();
        String model = visionResult.getModel();
        String maxCapacity = visionResult.getMaxCapacity();
        String precision = visionResult.getPrecision();
        String connectionType = visionResult.getConnectionType();
        String serialNumber = visionResult.getSerialNumber();
        String rawText = visionResult.getRawText();
        Double confidence = visionResult.getConfidence();

        log.info("图片识别结果: brand={}, model={}, maxCapacity={}, confidence={}", brand, model, maxCapacity, confidence);

        // 标准化品牌名称
        String brandCode = null;
        String brandName = null;
        if (brand != null) {
            ScaleBrandMatcher.BrandMatchResult normalizedBrand = brandMatcher.normalizeBrand(brand);
            brandCode = normalizedBrand.getBrandCode();
            brandName = normalizedBrand.getBrandName();
        }

        // 生成设备编码
        long count = equipmentRepository.countByFactoryId(factoryId);
        String equipmentCode = "SCALE-" + String.format("%04d", count + 1);

        // 检查设备编码是否已存在
        while (equipmentRepository.existsByFactoryIdAndEquipmentCode(factoryId, equipmentCode)) {
            count++;
            equipmentCode = "SCALE-" + String.format("%04d", count + 1);
        }

        // 生成设备名称（优先使用识别结果）
        String equipmentName = name;
        if (brandName != null || model != null) {
            equipmentName = (brandName != null ? brandName : "") +
                    (model != null ? " " + model : "") + " 电子秤";
            equipmentName = equipmentName.trim();
        }

        // 查找匹配的品牌型号
        String brandModelId = null;
        String protocolId = null;
        if (brandCode != null) {
            Optional<ScaleBrandModel> brandModelOpt = brandMatcher.findBrandModel(brandCode, model);
            if (brandModelOpt.isPresent()) {
                ScaleBrandModel brandModel = brandModelOpt.get();
                brandModelId = brandModel.getId();
                protocolId = brandModel.getDefaultProtocolId();
            }

            // 智能协议匹配
            if (protocolId == null) {
                ScaleBrandMatcher.ProtocolMatchInfo protocolMatch =
                        brandMatcher.findBestMatchingProtocol(factoryId, brandCode, model, null);
                if (protocolMatch != null) {
                    protocolId = protocolMatch.getProtocolId();
                }
            }
        }

        // 创建设备实体
        FactoryEquipment equipment = new FactoryEquipment();
        equipment.setFactoryId(factoryId);
        equipment.setEquipmentCode(equipmentCode);
        equipment.setCode(equipmentCode);
        equipment.setEquipmentName(equipmentName);
        equipment.setType("scale");
        equipment.setDeviceCategory(DeviceCategory.IOT_SCALE);
        equipment.setLocation(location);
        equipment.setStatus("idle");
        equipment.setManufacturer(brandName);
        equipment.setModel(model);
        equipment.setSerialNumber(serialNumber);
        equipment.setScaleBrandModelId(brandModelId);
        equipment.setScaleProtocolId(protocolId);
        equipment.setIotDeviceCode("SCALE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // 设置连接参数
        if (connectionType != null) {
            Map<String, Object> connectionParams = new LinkedHashMap<>();
            connectionParams.put("connectionType", connectionType);
            if (connectionType.contains("232") || connectionType.contains("485")) {
                connectionParams.put("baudRate", 9600);
                connectionParams.put("dataBits", 8);
                connectionParams.put("stopBits", 1);
                connectionParams.put("parity", "NONE");
            }
            equipment.setScaleConnectionParams(objectMapper.writeValueAsString(connectionParams));
        }

        // 设置规格参数
        if (maxCapacity != null || precision != null) {
            Map<String, Object> specs = new LinkedHashMap<>();
            if (maxCapacity != null) specs.put("maxCapacity", maxCapacity);
            if (precision != null) specs.put("precision", precision);
            String specsJson = objectMapper.writeValueAsString(specs);
            String notes = "规格: " + specsJson;
            if (workstationId != null) {
                notes += "\n关联工位: " + workstationId;
            }
            equipment.setNotes(notes);
        } else if (workstationId != null) {
            equipment.setNotes("关联工位: " + workstationId);
        }

        // 保存设备
        equipment = equipmentRepository.save(equipment);

        log.info("通过图片识别添加电子秤设备成功: id={}, code={}, name={}",
                equipment.getId(), equipmentCode, equipmentName);

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", equipment.getId());
        result.put("equipmentCode", equipmentCode);
        result.put("equipmentName", equipmentName);
        result.put("iotDeviceCode", equipment.getIotDeviceCode());
        result.put("location", location);
        result.put("status", "idle");

        // 识别信息
        Map<String, Object> recognizedInfo = new LinkedHashMap<>();
        recognizedInfo.put("brand", brand != null ? brand : "-");
        recognizedInfo.put("model", model != null ? model : "-");
        recognizedInfo.put("maxCapacity", maxCapacity != null ? maxCapacity : "-");
        recognizedInfo.put("precision", precision != null ? precision : "-");
        recognizedInfo.put("connectionType", connectionType != null ? connectionType : "-");
        recognizedInfo.put("serialNumber", serialNumber != null ? serialNumber : "-");
        result.put("recognizedInfo", recognizedInfo);

        result.put("recognitionConfidence", confidence);
        result.put("rawText", rawText);
        result.put("brandModelId", brandModelId);
        result.put("protocolId", protocolId);

        if (workstationId != null) {
            result.put("workstationId", workstationId);
        }

        StringBuilder message = new StringBuilder();
        message.append("成功通过图片识别添加设备!\n");
        message.append("设备名称: ").append(equipmentName).append("\n");
        message.append("设备编码: ").append(equipmentCode);
        if (brand != null) message.append("\n品牌: ").append(brand);
        if (model != null) message.append("\n型号: ").append(model);
        if (maxCapacity != null) message.append("\n量程: ").append(maxCapacity);
        if (confidence != null) {
            message.append("\n识别置信度: ").append(String.format("%.0f%%", confidence * 100));
        }

        return buildSimpleResult(message.toString(), result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
