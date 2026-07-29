package com.cretas.aims.ai.tool.impl.equipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.camera.CameraDeviceInfo;
import com.cretas.aims.dto.camera.CaptureImageResponse;
import com.cretas.aims.service.CameraService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摄像头启动工具
 *
 * 枚举摄像头设备、连接并拍照。
 *
 * Intent Code: EQUIPMENT_CAMERA_START / OPEN_CAMERA
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class EquipmentCameraStartTool extends AbstractBusinessTool {

    @Autowired
    private CameraService cameraService;

    @Override
    public String getToolName() {
        return "equipment_camera_start";
    }

    @Override
    public String getDescription() {
        return "启动摄像头设备，枚举可用摄像头、连接并拍照。" +
                "适用场景：打开摄像头、拍照、查看摄像头设备列表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行摄像头启动 - 工厂ID: {}", factoryId);

        try {
            List<CameraDeviceInfo> devices = cameraService.enumerateDevices();
            if (devices == null || devices.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("connected", false);
                result.put("message", "摄像头设备查询完成：当前工厂未配置摄像头设备。\n如需使用摄像头功能，请先在设备管理中添加摄像头设备。");
                return result;
            }

            if (!cameraService.isConnected()) {
                cameraService.connectCamera(0);
            }
            CaptureImageResponse capture = cameraService.captureImage();

            Map<String, Object> result = new HashMap<>();
            result.put("devices", devices);
            result.put("capture", capture);
            result.put("connected", true);
            result.put("message", "摄像头已启动，检测到 " + devices.size() + " 个设备，已完成拍照");

            log.info("摄像头启动完成 - 设备数: {}", devices.size());

            return result;
        } catch (Exception e) {
            log.warn("摄像头操作失败: {}", e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("connected", false);
            result.put("message", "摄像头设备查询完成：当前未检测到可用的摄像头设备。\n如需使用摄像头功能，请先在设备管理中添加摄像头设备。");
            return result;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
