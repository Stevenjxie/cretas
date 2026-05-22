package com.cretas.aims.service.impl;

import com.cretas.aims.dto.equipment.CreateEquipmentAlertRequest;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.enums.DeviceAlertLevel;
import com.cretas.aims.entity.iot.DeviceStatus;
import com.cretas.aims.entity.iot.IotDevice;
import com.cretas.aims.entity.iot.IotDeviceData;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.IotDeviceDataRepository;
import com.cretas.aims.repository.IotDeviceRepository;
import com.cretas.aims.service.EquipmentAlertsService;
import com.cretas.aims.service.IotDataService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * IoT数据服务实现类
 * 负责IoT设备数据的采集、存储和处理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDataServiceImpl implements IotDataService {

    private final IotDeviceRepository iotDeviceRepository;
    private final IotDeviceDataRepository iotDeviceDataRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentAlertsService equipmentAlertsService;
    private final ObjectMapper objectMapper;
    /**
     * Canvas-Thresholds resolver (Phase A) — overlays hard-coded fallback defaults
     * with per-factory configured values from {@code factory_thresholds} table.
     */
    private final ThresholdResolverService thresholdResolver;

    // ==================== 温湿度阈值 fallback 默认值 ====================
    // 以下 static final 为 hard-coded baseline; 实际值通过 xxxThreshold(factoryId) 读取.

    /** 冷链温度上限 fallback 默认值 (°C) */
    private static final double FALLBACK_COLD_CHAIN_TEMP_MAX = -18.0;

    /** 常温温度下限 fallback 默认值 (°C) */
    private static final double FALLBACK_NORMAL_TEMP_MIN = 0.0;

    /** 常温温度上限 fallback 默认值 (°C) */
    private static final double FALLBACK_NORMAL_TEMP_MAX = 25.0;

    /** 湿度下限 fallback 默认值 (%) */
    private static final double FALLBACK_HUMIDITY_MIN = 40.0;

    /** 湿度上限 fallback 默认值 (%) */
    private static final double FALLBACK_HUMIDITY_MAX = 70.0;

    // ==================== Threshold resolver shortcuts ====================

    private double coldChainTempMax(String factoryId) {
        return thresholdResolver.getDouble(factoryId, ThresholdKeys.IOT_COLD_CHAIN_TEMP_MAX, FALLBACK_COLD_CHAIN_TEMP_MAX);
    }
    private double normalTempMin(String factoryId) {
        return thresholdResolver.getDouble(factoryId, ThresholdKeys.IOT_NORMAL_TEMP_MIN, FALLBACK_NORMAL_TEMP_MIN);
    }
    private double normalTempMax(String factoryId) {
        return thresholdResolver.getDouble(factoryId, ThresholdKeys.IOT_NORMAL_TEMP_MAX, FALLBACK_NORMAL_TEMP_MAX);
    }
    private double humidityMin(String factoryId) {
        return thresholdResolver.getDouble(factoryId, ThresholdKeys.IOT_HUMIDITY_MIN, FALLBACK_HUMIDITY_MIN);
    }
    private double humidityMax(String factoryId) {
        return thresholdResolver.getDouble(factoryId, ThresholdKeys.IOT_HUMIDITY_MAX, FALLBACK_HUMIDITY_MAX);
    }

    // ==================== 设备数据管理 ====================

    @Override
    @Transactional
    public void saveDeviceData(String factoryId, String deviceId, String dataType, JsonNode data) {
        log.info("保存设备数据: factoryId={}, deviceId={}, dataType={}", factoryId, deviceId, dataType);

        try {
            // 获取设备信息
            Optional<IotDevice> deviceOpt = iotDeviceRepository.findById(deviceId);
            String deviceCode = deviceOpt.map(IotDevice::getDeviceCode).orElse(deviceId);

            // 创建数据记录
            IotDeviceData deviceData = new IotDeviceData();
            deviceData.setDeviceId(deviceId);
            deviceData.setDeviceCode(deviceCode);
            deviceData.setFactoryId(factoryId);
            deviceData.setDataType(dataType);
            deviceData.setDataValue(objectMapper.writeValueAsString(data));
            deviceData.setCollectedAt(LocalDateTime.now());
            deviceData.setReceivedAt(LocalDateTime.now());
            deviceData.setProcessed(false);

            // 保存数据
            iotDeviceDataRepository.save(deviceData);

            // 更新设备最后数据时间
            iotDeviceRepository.updateLastDataTime(deviceId, LocalDateTime.now());

            log.debug("设备数据保存成功: dataId={}", deviceData.getId());

        } catch (JsonProcessingException e) {
            log.error("设备数据JSON序列化失败: deviceId={}, error={}", deviceId, e.getMessage());
            throw new RuntimeException("数据序列化失败", e);
        }
    }

    @Override
    public List<IotDeviceData> getRecentData(String factoryId, String deviceId, int limit) {
        log.debug("获取设备最近数据: factoryId={}, deviceId={}, limit={}", factoryId, deviceId, limit);

        return iotDeviceDataRepository.findByDeviceIdOrderByReceivedAtDesc(
                deviceId,
                PageRequest.of(0, limit)
        );
    }

    // ==================== 设备状态管理 ====================

    @Override
    @Transactional
    public void updateDeviceStatus(String factoryId, String deviceId, String status) {
        log.info("更新设备状态: factoryId={}, deviceId={}, status={}", factoryId, deviceId, status);

        try {
            DeviceStatus deviceStatus = DeviceStatus.valueOf(status.toUpperCase());
            int updated = iotDeviceRepository.updateStatus(deviceId, deviceStatus);

            if (updated == 0) {
                log.warn("设备状态更新失败，设备不存在: deviceId={}", deviceId);
            } else {
                log.debug("设备状态更新成功: deviceId={}, status={}", deviceId, status);
            }

        } catch (IllegalArgumentException e) {
            log.error("无效的设备状态: status={}", status);
            throw new BusinessException(400, "无效的设备状态: " + status)
                    .withHint("请使用合法的设备状态: ONLINE / OFFLINE / FAULT / MAINTENANCE")
                    .withHintTarget("status");
        }
    }

    @Override
    @Transactional
    public void updateDeviceHeartbeat(String factoryId, String deviceId) {
        log.debug("更新设备心跳: factoryId={}, deviceId={}", factoryId, deviceId);

        LocalDateTime now = LocalDateTime.now();
        int updated = iotDeviceRepository.updateLastHeartbeat(deviceId, now);

        if (updated == 0) {
            log.warn("设备心跳更新失败，设备不存在: deviceId={}", deviceId);
        }
    }

    @Override
    public Optional<IotDevice> getDeviceByCode(String deviceCode) {
        log.debug("根据设备编码获取设备: deviceCode={}", deviceCode);
        return iotDeviceRepository.findByDeviceCode(deviceCode);
    }

    // ==================== 设备关联更新 ====================

    @Override
    @Transactional
    public void updateEquipmentLastWeight(String deviceCode, BigDecimal weight, LocalDateTime time) {
        log.info("更新关联设备称重信息: deviceCode={}, weight={}, time={}", deviceCode, weight, time);

        // 根据设备编码查找IoT设备
        Optional<IotDevice> iotDeviceOpt = iotDeviceRepository.findByDeviceCode(deviceCode);

        if (iotDeviceOpt.isEmpty()) {
            log.warn("IoT设备不存在: deviceCode={}", deviceCode);
            return;
        }

        IotDevice iotDevice = iotDeviceOpt.get();
        Long equipmentId = iotDevice.getEquipmentId();

        if (equipmentId == null) {
            log.debug("IoT设备未关联工厂设备: deviceCode={}", deviceCode);
            return;
        }

        // 更新关联的工厂设备称重信息
        Optional<FactoryEquipment> equipmentOpt = equipmentRepository.findById(equipmentId);

        if (equipmentOpt.isPresent()) {
            FactoryEquipment equipment = equipmentOpt.get();
            equipment.setLastWeightReading(weight);
            equipment.setLastWeightTime(time);
            equipment.setLastDataReceived(LocalDateTime.now());
            equipmentRepository.save(equipment);

            log.info("工厂设备称重信息已更新: equipmentId={}, weight={}", equipmentId, weight);
        } else {
            log.warn("关联的工厂设备不存在: equipmentId={}", equipmentId);
        }
    }

    // ==================== 阈值告警检查 ====================

    @Override
    public void checkTemperatureThreshold(String factoryId, String deviceId, double temperature) {
        log.debug("检查温度阈值: factoryId={}, deviceId={}, temperature={}", factoryId, deviceId, temperature);

        // 获取设备信息，判断是否为冷链设备
        Optional<IotDevice> deviceOpt = iotDeviceRepository.findById(deviceId);
        boolean isColdChain = deviceOpt
                .map(device -> "COLD_CHAIN".equalsIgnoreCase(device.getProtocolId()))
                .orElse(false);

        String alertMessage = null;
        DeviceAlertLevel alertLevel = DeviceAlertLevel.WARNING;

        if (isColdChain) {
            // 冷链设备: 温度高于阈值 (默认 -18°C) 触发告警
            double coldMax = coldChainTempMax(factoryId);
            if (temperature > coldMax) {
                alertMessage = String.format("冷链温度异常: 当前温度 %.1f°C，超过阈值 %.1f°C",
                        temperature, coldMax);
                if (temperature > coldMax + 5) {
                    alertLevel = DeviceAlertLevel.CRITICAL;
                }
            }
        } else {
            // 常温设备: 温度低于下限或高于上限 (默认 0-25°C) 触发告警
            double tmin = normalTempMin(factoryId);
            double tmax = normalTempMax(factoryId);
            if (temperature < tmin) {
                alertMessage = String.format("温度过低: 当前温度 %.1f°C，低于阈值 %.1f°C",
                        temperature, tmin);
            } else if (temperature > tmax) {
                alertMessage = String.format("温度过高: 当前温度 %.1f°C，超过阈值 %.1f°C",
                        temperature, tmax);
                if (temperature > tmax + 10) {
                    alertLevel = DeviceAlertLevel.CRITICAL;
                }
            }
        }

        if (alertMessage != null) {
            log.warn("温度阈值告警: {}", alertMessage);
            createDeviceAlertInternal(factoryId, deviceId, "TEMPERATURE_ALERT", alertMessage, alertLevel);
        }
    }

    @Override
    public void checkHumidityThreshold(String factoryId, String deviceId, double humidity) {
        log.debug("检查湿度阈值: factoryId={}, deviceId={}, humidity={}", factoryId, deviceId, humidity);

        String alertMessage = null;
        DeviceAlertLevel alertLevel = DeviceAlertLevel.WARNING;

        double hmin = humidityMin(factoryId);
        double hmax = humidityMax(factoryId);
        if (humidity < hmin) {
            alertMessage = String.format("湿度过低: 当前湿度 %.1f%%，低于阈值 %.1f%%",
                    humidity, hmin);
        } else if (humidity > hmax) {
            alertMessage = String.format("湿度过高: 当前湿度 %.1f%%，超过阈值 %.1f%%",
                    humidity, hmax);
            if (humidity > hmax + 10) {
                alertLevel = DeviceAlertLevel.CRITICAL;
            }
        }

        if (alertMessage != null) {
            log.warn("湿度阈值告警: {}", alertMessage);
            createDeviceAlertInternal(factoryId, deviceId, "HUMIDITY_ALERT", alertMessage, alertLevel);
        }
    }

    // ==================== 告警管理 ====================

    @Override
    public void createDeviceAlert(String factoryId, String deviceId, String alertType, String message) {
        log.info("创建设备告警: factoryId={}, deviceId={}, alertType={}", factoryId, deviceId, alertType);
        createDeviceAlertInternal(factoryId, deviceId, alertType, message, DeviceAlertLevel.WARNING);
    }

    @Override
    @Transactional
    public void checkDeviceOffline(String factoryId, String deviceId, Duration timeout) {
        log.debug("检查设备离线状态: factoryId={}, deviceId={}, timeout={}", factoryId, deviceId, timeout);

        Optional<IotDevice> deviceOpt = iotDeviceRepository.findById(deviceId);

        if (deviceOpt.isEmpty()) {
            log.warn("设备不存在: deviceId={}", deviceId);
            return;
        }

        IotDevice device = deviceOpt.get();
        LocalDateTime lastHeartbeat = device.getLastHeartbeat();

        if (lastHeartbeat == null) {
            log.debug("设备从未发送过心跳: deviceId={}", deviceId);
            return;
        }

        LocalDateTime timeoutThreshold = LocalDateTime.now().minus(timeout);

        if (lastHeartbeat.isBefore(timeoutThreshold) && device.getStatus() == DeviceStatus.ONLINE) {
            // 设备心跳超时，标记为离线并创建告警
            iotDeviceRepository.updateStatus(deviceId, DeviceStatus.OFFLINE);

            String message = String.format("设备心跳超时: 最后心跳时间 %s，超时阈值 %d 秒",
                    lastHeartbeat, timeout.getSeconds());

            createDeviceAlertInternal(factoryId, deviceId, "DEVICE_OFFLINE", message, DeviceAlertLevel.WARNING);

            log.warn("设备已标记为离线: deviceId={}, lastHeartbeat={}", deviceId, lastHeartbeat);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 创建设备告警（内部方法）
     */
    private void createDeviceAlertInternal(String factoryId, String deviceId, String alertType,
                                           String message, DeviceAlertLevel level) {
        try {
            // 获取设备关联的工厂设备ID
            Optional<IotDevice> deviceOpt = iotDeviceRepository.findById(deviceId);
            Long equipmentId = deviceOpt.map(IotDevice::getEquipmentId).orElse(null);

            if (equipmentId == null) {
                log.warn("IoT设备未关联工厂设备，无法创建告警: deviceId={}", deviceId);
                return;
            }

            // 使用 EquipmentAlertsService 创建告警
            CreateEquipmentAlertRequest request = CreateEquipmentAlertRequest.builder()
                    .equipmentId(equipmentId)
                    .alertType(alertType)
                    .level(level)
                    .message(message)
                    .details(String.format("{\"iotDeviceId\":\"%s\",\"timestamp\":\"%s\"}",
                            deviceId, LocalDateTime.now()))
                    .build();

            equipmentAlertsService.createAlert(factoryId, request);

            log.info("设备告警创建成功: factoryId={}, equipmentId={}, alertType={}",
                    factoryId, equipmentId, alertType);

        } catch (Exception e) {
            log.error("创建设备告警失败: factoryId={}, deviceId={}, error={}",
                    factoryId, deviceId, e.getMessage());
        }
    }
}
