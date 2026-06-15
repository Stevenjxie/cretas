package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.DeviceActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DeviceActivationRepository;
import com.cretas.aims.service.mobile.MobileDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 移动端设备管理服务实现
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobileDeviceServiceImpl implements MobileDeviceService {

    private final DeviceActivationRepository deviceActivationRepository;

    // 模拟设备登录记录（实际应使用数据库）
    private final Map<Long, List<MobileDTO.DeviceInfo>> userDevices = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public MobileDTO.ActivationResponse activateDevice(MobileDTO.ActivationRequest request) {
        log.info("设备激活: code={}, deviceId={}",
                request.getActivationCode(), request.getDeviceInfo().getDeviceId());

        // 查找激活码
        DeviceActivation activation = deviceActivationRepository
                .findByActivationCode(request.getActivationCode())
                .orElseThrow(() -> new BusinessException(404, "无效的激活码")
                        .withHint("请联系管理员获取有效激活码").withHintTarget("activationCode"));

        // 验证激活码状态
        if (!"PENDING".equals(activation.getStatus())) {
            throw new BusinessException(409, "激活码已被使用或已过期")
                    .withHint("请联系管理员获取新的激活码").withHintTarget("activationCode");
        }

        // 检查是否过期
        if (activation.getExpiresAt() != null &&
            LocalDateTime.now().isAfter(activation.getExpiresAt())) {
            activation.setStatus("EXPIRED");
            deviceActivationRepository.save(activation);
            throw new BusinessException(409, "激活码已过期")
                    .withHint("请联系管理员获取新的激活码").withHintTarget("activationCode");
        }

        // 更新激活信息
        MobileDTO.DeviceInfo deviceInfo = request.getDeviceInfo();
        activation.setDeviceId(deviceInfo.getDeviceId());
        activation.setDeviceType(deviceInfo.getDeviceType());
        activation.setDeviceModel(deviceInfo.getModel());
        activation.setOsType(deviceInfo.getDeviceType());
        activation.setOsVersion(deviceInfo.getOsVersion());
        activation.setAppVersion(deviceInfo.getAppVersion());
        activation.setStatus("ACTIVATED");
        activation.setActivatedAt(LocalDateTime.now());
        deviceActivationRepository.save(activation);

        return MobileDTO.ActivationResponse.builder()
                .success(true)
                .factoryId(activation.getFactoryId())
                .factoryName(activation.getFactory() != null ? activation.getFactory().getName() : null)
                .activatedAt(activation.getActivatedAt())
                .validUntil(activation.getExpiresAt())
                .features(Arrays.asList("basic", "camera", "offline", "sync"))
                .configuration(new HashMap<>())
                .build();
    }

    @Override
    public void recordDeviceLogin(Long userId, MobileDTO.DeviceInfo deviceInfo) {
        log.debug("记录设备登录: userId={}, deviceId={}", userId, deviceInfo.getDeviceId());

        userDevices.computeIfAbsent(userId, k -> new ArrayList<>());
        List<MobileDTO.DeviceInfo> devices = userDevices.get(userId);

        // 移除旧的相同设备记录
        devices.removeIf(d -> Objects.equals(d.getDeviceId(), deviceInfo.getDeviceId()));

        // 添加新记录
        devices.add(deviceInfo);

        // 限制每个用户最多5个设备
        if (devices.size() > 5) {
            devices.remove(0);
        }
    }

    @Override
    public List<MobileDTO.DeviceInfo> getUserDevices(Long userId) {
        log.debug("获取用户设备列表: userId={}", userId);
        return userDevices.getOrDefault(userId, new ArrayList<>());
    }

    @Override
    public void removeDevice(Long userId, String deviceId) {
        log.info("移除设备: userId={}, deviceId={}", userId, deviceId);
        List<MobileDTO.DeviceInfo> devices = userDevices.get(userId);
        if (devices != null) {
            devices.removeIf(d -> Objects.equals(d.getDeviceId(), deviceId));
        }
    }

    @Override
    public void registerPushNotification(Long userId, MobileDTO.PushRegistration registration) {
        log.info("注册推送通知: userId={}, platform={}", userId, registration.getPlatform());
        // TODO: 实现推送通知注册逻辑
    }

    @Override
    public void unregisterPushNotification(Long userId, String deviceToken) {
        log.info("取消推送通知: userId={}, token={}", userId, deviceToken);
        // TODO: 实现取消推送通知逻辑
    }
}
