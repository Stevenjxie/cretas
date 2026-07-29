package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.common.UnifiedDeviceType;
import com.cretas.aims.repository.EquipmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 电子秤设备删除工具
 *
 * 删除 IoT 电子秤设备。会检查设备是否有使用记录，有记录的设备建议停用而非删除。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ScaleDeleteDeviceTool extends AbstractBusinessTool {

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Override
    public String getToolName() {
        return "scale_delete_device";
    }

    @Override
    public String getDescription() {
        return "删除 IoT 电子秤设备。会检查设备是否有使用记录，有记录的设备无法删除。" +
                "适用场景：移除废弃设备、删除测试设备、清理无用配置。" +
                "注意：此操作不可恢复，请谨慎使用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // deviceId: 设备ID（必需）
        Map<String, Object> deviceId = new HashMap<>();
        deviceId.put("type", "integer");
        deviceId.put("description", "要删除的设备ID");
        properties.put("deviceId", deviceId);

        // force: 强制删除（可选，默认 false）
        Map<String, Object> force = new HashMap<>();
        force.put("type", "boolean");
        force.put("description", "是否强制删除（即使有使用记录）");
        force.put("default", false);
        properties.put("force", force);

        // reason: 删除原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "删除原因，用于审计记录");
        properties.put("reason", reason);

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
            return "请提供要删除的设备ID";
        }
        return null;
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "deviceId", "设备ID",
            "force", "强制删除",
            "reason", "删除原因"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行删除电子秤设备 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long deviceId = getLong(params, "deviceId");
        Boolean force = getBoolean(params, "force", false);
        String reason = getString(params, "reason");

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

        // 保存设备信息用于返回
        String deletedName = device.getEquipmentName();
        String deletedCode = device.getEquipmentCode();
        String iotDeviceCode = device.getIotDeviceCode();

        // 检查是否有使用记录
        boolean hasUsageRecords = equipmentRepository.hasUsageRecords(deviceId);
        if (hasUsageRecords && !force) {
            log.warn("设备存在使用记录，拒绝删除: deviceId={}, code={}", deviceId, deletedCode);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", deviceId);
            result.put("equipmentCode", deletedCode);
            result.put("equipmentName", deletedName);
            result.put("hasUsageRecords", true);
            result.put("deleted", false);
            result.put("suggestion", "设备存在使用记录，建议将设备状态改为「disabled」(停用)而不是删除");

            return buildSimpleResult(
                    "设备存在使用记录，无法删除。\n" +
                    "设备: " + deletedName + " [" + deletedCode + "]\n\n" +
                    "建议：将设备状态改为「停用」而不是删除，以保留历史数据。\n" +
                    "使用 scale_update_device 工具将 status 设置为 disabled",
                    result
            );
        }

        // 执行删除
        equipmentRepository.delete(device);

        log.info("删除电子秤设备成功: id={}, code={}, name={}, reason={}",
                deviceId, deletedCode, deletedName, reason);

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("equipmentCode", deletedCode);
        result.put("equipmentName", deletedName);
        result.put("iotDeviceCode", iotDeviceCode);
        result.put("deleted", true);
        result.put("deletedAt", System.currentTimeMillis());
        if (reason != null) {
            result.put("reason", reason);
        }
        if (hasUsageRecords && force) {
            result.put("forceDeleted", true);
            result.put("warning", "设备存在使用记录，已强制删除");
        }

        StringBuilder message = new StringBuilder();
        message.append("设备已删除!\n");
        message.append("设备名称: ").append(deletedName).append("\n");
        message.append("设备编码: ").append(deletedCode);
        if (reason != null) {
            message.append("\n删除原因: ").append(reason);
        }
        if (hasUsageRecords && force) {
            message.append("\n警告: 设备存在使用记录，已强制删除");
        }

        return buildSimpleResult(message.toString(), result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
