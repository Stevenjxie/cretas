package com.cretas.aims.service.impl;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.service.FactorySettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工厂设置服务实现
 * 处理JSON序列化/反序列化的工厂设置管理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-01
 */
@Service
@RequiredArgsConstructor
public class FactorySettingsServiceImpl implements FactorySettingsService {
    private static final Logger log = LoggerFactory.getLogger(FactorySettingsServiceImpl.class);

    private final FactorySettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final FactoryRepository factoryRepository;

    @Override
    @Transactional
    public FactorySettingsDTO getSettings(String factoryId) {
        // R21-F3 fix: lazy-init default settings row on first read instead of 404
        // Frontend /system/settings calls GET /settings + GET /settings/full on mount; returning 404
        // causes silent fallback to hardcoded defaults and prevents any subsequent save from working.
        Factory factory = factoryRepository.findById(factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("工厂不存在，ID: " + factoryId));

        // Race-safe lazy-init (Apr 21 2026): FE calls GET /settings + GET
        // /settings/full in parallel on page mount. Both race to insert the
        // default row; the loser hits the unique constraint on factory_id
        // and returns 409 to the user as a red toast. Catch the conflict
        // and re-read — the winner's row is now visible.
        FactorySettings settings = settingsRepository.findByFactoryId(factoryId)
                .orElseGet(() -> {
                    log.info("工厂设置不存在，自动创建默认配置: factoryId={}", factoryId);
                    FactorySettings seed = FactorySettings.builder()
                            .factoryId(factoryId)
                            .build();
                    try {
                        return settingsRepository.saveAndFlush(seed);
                    } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                        log.info("并发创建撞 unique, 重读已存在行: factoryId={}", factoryId);
                        return settingsRepository.findByFactoryId(factoryId)
                                .orElseThrow(() -> dup);
                    }
                });

        settings.setFactoryName(factory.getName());
        settings.setFactoryAddress(factory.getAddress());
        settings.setContactPhone(factory.getContactPhone());
        settings.setContactEmail(factory.getContactEmail());
        settings.setWorkingHours(10);

        return convertToDTO(settings);
    }

    @Override
    @Transactional
    public FactorySettingsDTO saveSettings(String factoryId, FactorySettingsDTO dto) {
        FactorySettings settings = settingsRepository.findByFactoryId(factoryId)
                .orElse(FactorySettings.builder()
                        .factoryId(factoryId)
                        .build());

        updateEntityFromDTO(settings, dto);
        FactorySettings saved = settingsRepository.save(settings);

        log.info("保存工厂设置成功: factoryId={}", factoryId);
        return convertToDTO(saved);
    }

    @Override
    public FactorySettingsDTO.AISettings getAiSettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getAiSettings(), FactorySettingsDTO.AISettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.AISettings updateAiSettings(String factoryId, FactorySettingsDTO.AISettings aiSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setAiSettings(toJsonString(aiSettings));
        settingsRepository.save(settings);

        log.info("更新AI设置成功: factoryId={}", factoryId);
        return aiSettings;
    }

    @Override
    public Map<String, Object> getAiUsageStats(String factoryId, String period) {
        // TODO: 实现AI使用统计功能
        Map<String, Object> stats = new HashMap<>();
        stats.put("period", period);
        stats.put("totalRequests", 0);
        stats.put("totalCost", 0.0);
        stats.put("remainingQuota", 0);

        log.debug("获取AI使用统计: factoryId={}, period={}", factoryId, period);
        return stats;
    }

    @Override
    public FactorySettingsDTO.NotificationSettings getNotificationSettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getNotificationSettings(), FactorySettingsDTO.NotificationSettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.NotificationSettings updateNotificationSettings(String factoryId,
                                                                             FactorySettingsDTO.NotificationSettings notifSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setNotificationSettings(toJsonString(notifSettings));
        settingsRepository.save(settings);

        log.info("更新通知设置成功: factoryId={}", factoryId);
        return notifSettings;
    }

    @Override
    public FactorySettingsDTO.WorkTimeSettings getWorkTimeSettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getWorkTimeSettings(), FactorySettingsDTO.WorkTimeSettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.WorkTimeSettings updateWorkTimeSettings(String factoryId,
                                                                      FactorySettingsDTO.WorkTimeSettings workTimeSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setWorkTimeSettings(toJsonString(workTimeSettings));
        settingsRepository.save(settings);

        log.info("更新工作时间设置成功: factoryId={}", factoryId);
        return workTimeSettings;
    }

    @Override
    public FactorySettingsDTO.ProductionSettings getProductionSettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getProductionSettings(), FactorySettingsDTO.ProductionSettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.ProductionSettings updateProductionSettings(String factoryId,
                                                                          FactorySettingsDTO.ProductionSettings prodSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setProductionSettings(toJsonString(prodSettings));
        settingsRepository.save(settings);

        log.info("更新生产设置成功: factoryId={}", factoryId);
        return prodSettings;
    }

    @Override
    public FactorySettingsDTO.InventorySettings getInventorySettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getInventorySettings(), FactorySettingsDTO.InventorySettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.InventorySettings updateInventorySettings(String factoryId,
                                                                        FactorySettingsDTO.InventorySettings invSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setInventorySettings(toJsonString(invSettings));
        settingsRepository.save(settings);

        log.info("更新库存设置成功: factoryId={}", factoryId);
        return invSettings;
    }

    @Override
    public FactorySettingsDTO.DataRetentionSettings getDataRetentionSettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);
        return parseJsonField(settings.getDataRetentionSettings(), FactorySettingsDTO.DataRetentionSettings.class);
    }

    @Override
    @Transactional
    public FactorySettingsDTO.DataRetentionSettings updateDataRetentionSettings(String factoryId,
                                                                                FactorySettingsDTO.DataRetentionSettings dataSettings) {
        FactorySettings settings = getSettingsEntity(factoryId);
        settings.setDataRetentionSettings(toJsonString(dataSettings));
        settingsRepository.save(settings);

        log.info("更新数据保留设置成功: factoryId={}", factoryId);
        return dataSettings;
    }

    @Override
    @Transactional
    public void updateFeatureToggle(String factoryId, String feature, Boolean enabled) {
        FactorySettings settings = getSettingsEntity(factoryId);

        switch (feature.toLowerCase()) {
            case "qrcode":
                settings.setEnableQrCode(enabled);
                break;
            case "batchmanagement":
                settings.setEnableBatchManagement(enabled);
                break;
            case "qualitycheck":
                settings.setEnableQualityCheck(enabled);
                break;
            case "costcalculation":
                settings.setEnableCostCalculation(enabled);
                break;
            case "equipmentmanagement":
                settings.setEnableEquipmentManagement(enabled);
                break;
            case "attendance":
                settings.setEnableAttendance(enabled);
                break;
            default:
                throw new BusinessException(400, "未知的功能开关: " + feature)
                        .withHint("请使用支持的功能开关名称").withHintTarget("feature");
        }

        settingsRepository.save(settings);
        log.info("更新功能开关成功: factoryId={}, feature={}, enabled={}", factoryId, feature, enabled);
    }

    @Override
    public Map<String, Boolean> getFeatureToggles(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("qrCode", settings.getEnableQrCode());
        toggles.put("batchManagement", settings.getEnableBatchManagement());
        toggles.put("qualityCheck", settings.getEnableQualityCheck());
        toggles.put("costCalculation", settings.getEnableCostCalculation());
        toggles.put("equipmentManagement", settings.getEnableEquipmentManagement());
        toggles.put("attendance", settings.getEnableAttendance());

        return toggles;
    }

    @Override
    @Transactional
    public void updateDisplaySettings(String factoryId, String language, String timezone,
                                      String dateFormat, String currency) {
        FactorySettings settings = getSettingsEntity(factoryId);

        if (StringUtils.hasText(language)) {
            settings.setLanguage(language);
        }
        if (StringUtils.hasText(timezone)) {
            settings.setTimezone(timezone);
        }
        if (StringUtils.hasText(dateFormat)) {
            settings.setDateFormat(dateFormat);
        }
        if (StringUtils.hasText(currency)) {
            settings.setCurrency(currency);
        }

        settingsRepository.save(settings);
        log.info("更新显示设置成功: factoryId={}", factoryId);
    }

    @Override
    public Map<String, String> getDisplaySettings(String factoryId) {
        FactorySettings settings = getSettingsEntity(factoryId);

        Map<String, String> display = new HashMap<>();
        display.put("language", settings.getLanguage());
        display.put("timezone", settings.getTimezone());
        display.put("dateFormat", settings.getDateFormat());
        display.put("currency", settings.getCurrency());

        return display;
    }

    @Override
    @Transactional
    public FactorySettingsDTO resetToDefaults(String factoryId) {
        FactorySettings settings = settingsRepository.findByFactoryId(factoryId)
                .orElse(FactorySettings.builder()
                        .factoryId(factoryId)
                        .build());

        // 重置显示设置
        settings.setLanguage("zh-CN");
        settings.setTimezone("Asia/Shanghai");
        settings.setDateFormat("yyyy-MM-dd");
        settings.setCurrency("CNY");

        // 重置功能开关
        settings.setEnableQrCode(true);
        settings.setEnableBatchManagement(true);
        settings.setEnableQualityCheck(true);
        settings.setEnableCostCalculation(true);
        settings.setEnableEquipmentManagement(true);
        settings.setEnableAttendance(true);
        settings.setSkipProcessReportingDefault(false);

        // 重置用户注册设置
        settings.setAllowSelfRegistration(false);
        settings.setRequireAdminApproval(true);
        settings.setDefaultUserRole("viewer");

        // 重置AI配额
        settings.setAiWeeklyQuota(20);

        // 清空JSON设置（使用null或默认值）
        settings.setAiSettings(null);
        settings.setNotificationSettings(null);
        settings.setWorkTimeSettings(null);
        settings.setProductionSettings(null);
        settings.setInventorySettings(null);
        settings.setDataRetentionSettings(null);

        FactorySettings saved = settingsRepository.save(settings);
        log.info("重置工厂设置为默认值: factoryId={}", factoryId);

        return convertToDTO(saved);
    }

    @Override
    public String exportSettings(String factoryId) {
        FactorySettingsDTO dto = getSettings(factoryId);

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
            log.info("导出工厂设置成功: factoryId={}", factoryId);
            return json;
        } catch (JsonProcessingException e) {
            log.error("导出工厂设置失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "导出设置失败: " + e.getMessage())
                    .withHint("请稍后重试, 如果问题持续请联系管理员");
        }
    }

    @Override
    @Transactional
    public FactorySettingsDTO importSettings(String factoryId, String settingsJson) {
        try {
            FactorySettingsDTO dto = objectMapper.readValue(settingsJson, FactorySettingsDTO.class);
            dto.setFactoryId(factoryId); // 确保使用正确的工厂ID

            FactorySettingsDTO result = saveSettings(factoryId, dto);
            log.info("导入工厂设置成功: factoryId={}", factoryId);
            return result;

        } catch (JsonProcessingException e) {
            log.error("导入工厂设置失败: factoryId={}", factoryId, e);
            throw new BusinessException(400, "导入设置失败: " + e.getMessage())
                    .withHint("请检查 JSON 格式是否正确, 字段是否匹配").withHintTarget("settingsJson");
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取工厂设置实体
     */
    private FactorySettings getSettingsEntity(String factoryId) {
        // R21-F3 fix: lazy-init row instead of 404 so downstream sub-setting GETs
        // (/ai, /notifications, /work-time, /production, /inventory, /data-retention, /features, /display)
        // and PUT handlers both work for factories that have never been configured before.
        return settingsRepository.findByFactoryId(factoryId)
                .orElseGet(() -> {
                    log.info("工厂设置不存在，自动创建默认配置: factoryId={}", factoryId);
                    FactorySettings seed = FactorySettings.builder()
                            .factoryId(factoryId)
                            .build();
                    return settingsRepository.save(seed);
                });
    }

    /**
     * 将实体转换为DTO
     */
    private FactorySettingsDTO convertToDTO(FactorySettings settings) {
        return FactorySettingsDTO.builder()
                .id(settings.getId())
                .factoryId(settings.getFactoryId())
                // AI设置
                .aiSettings(parseJsonField(settings.getAiSettings(), FactorySettingsDTO.AISettings.class))
                .aiWeeklyQuota(settings.getAiWeeklyQuota())
                // 用户注册设置
                .allowSelfRegistration(settings.getAllowSelfRegistration())
                .requireAdminApproval(settings.getRequireAdminApproval())
                .defaultUserRole(settings.getDefaultUserRole())
                // 通知设置
                .notificationSettings(parseJsonField(settings.getNotificationSettings(),
                        FactorySettingsDTO.NotificationSettings.class))
                // 系统设置
                .workTimeSettings(parseJsonField(settings.getWorkTimeSettings(),
                        FactorySettingsDTO.WorkTimeSettings.class))
                .productionSettings(parseJsonField(settings.getProductionSettings(),
                        FactorySettingsDTO.ProductionSettings.class))
                .inventorySettings(parseJsonField(settings.getInventorySettings(),
                        FactorySettingsDTO.InventorySettings.class))
                .dataRetentionSettings(parseJsonField(settings.getDataRetentionSettings(),
                        FactorySettingsDTO.DataRetentionSettings.class))
                // 显示设置
                .language(settings.getLanguage())
                .timezone(settings.getTimezone())
                .dateFormat(settings.getDateFormat())
                .currency(settings.getCurrency())
                // 功能开关
                .enableQrCode(settings.getEnableQrCode())
                .enableBatchManagement(settings.getEnableBatchManagement())
                .enableQualityCheck(settings.getEnableQualityCheck())
                .enableCostCalculation(settings.getEnableCostCalculation())
                .enableEquipmentManagement(settings.getEnableEquipmentManagement())
                .enableAttendance(settings.getEnableAttendance())
                .skipProcessReportingDefault(settings.getSkipProcessReportingDefault())
                // 审计信息
                .lastModifiedAt(settings.getLastModifiedAt())
                .factoryName(settings.getFactoryName())
                .factoryAddress(settings.getFactoryAddress())
                .contactPhone(settings.getContactPhone())
                .contactEmail(settings.getContactEmail())
                .workingHours(settings.getWorkingHours())
                .workingDays(List.of(new Boolean[]{true, true, true, true, true, false, false}))
                .build();
    }

    /**
     * 将DTO更新到实体
     */
    private void updateEntityFromDTO(FactorySettings settings, FactorySettingsDTO dto) {
        // 更新AI设置
        if (dto.getAiSettings() != null) {
            settings.setAiSettings(toJsonString(dto.getAiSettings()));
        }
        if (dto.getAiWeeklyQuota() != null) {
            settings.setAiWeeklyQuota(dto.getAiWeeklyQuota());
        }

        // 更新用户注册设置
        if (dto.getAllowSelfRegistration() != null) {
            settings.setAllowSelfRegistration(dto.getAllowSelfRegistration());
        }
        if (dto.getRequireAdminApproval() != null) {
            settings.setRequireAdminApproval(dto.getRequireAdminApproval());
        }
        if (dto.getDefaultUserRole() != null) {
            settings.setDefaultUserRole(dto.getDefaultUserRole());
        }

        // 更新通知设置
        if (dto.getNotificationSettings() != null) {
            settings.setNotificationSettings(toJsonString(dto.getNotificationSettings()));
        }

        // 更新工作时间设置
        if (dto.getWorkTimeSettings() != null) {
            settings.setWorkTimeSettings(toJsonString(dto.getWorkTimeSettings()));
        }

        // 更新生产设置
        if (dto.getProductionSettings() != null) {
            settings.setProductionSettings(toJsonString(dto.getProductionSettings()));
        }

        // 更新库存设置
        if (dto.getInventorySettings() != null) {
            settings.setInventorySettings(toJsonString(dto.getInventorySettings()));
        }

        // 更新数据保留设置
        if (dto.getDataRetentionSettings() != null) {
            settings.setDataRetentionSettings(toJsonString(dto.getDataRetentionSettings()));
        }

        // 更新显示设置
        if (dto.getLanguage() != null) {
            settings.setLanguage(dto.getLanguage());
        }
        if (dto.getTimezone() != null) {
            settings.setTimezone(dto.getTimezone());
        }
        if (dto.getDateFormat() != null) {
            settings.setDateFormat(dto.getDateFormat());
        }
        if (dto.getCurrency() != null) {
            settings.setCurrency(dto.getCurrency());
        }

        // 更新功能开关
        if (dto.getEnableQrCode() != null) {
            settings.setEnableQrCode(dto.getEnableQrCode());
        }
        if (dto.getEnableBatchManagement() != null) {
            settings.setEnableBatchManagement(dto.getEnableBatchManagement());
        }
        if (dto.getEnableQualityCheck() != null) {
            settings.setEnableQualityCheck(dto.getEnableQualityCheck());
        }
        if (dto.getEnableCostCalculation() != null) {
            settings.setEnableCostCalculation(dto.getEnableCostCalculation());
        }
        if (dto.getEnableEquipmentManagement() != null) {
            settings.setEnableEquipmentManagement(dto.getEnableEquipmentManagement());
        }
        if (dto.getEnableAttendance() != null) {
            settings.setEnableAttendance(dto.getEnableAttendance());
        }
        if (dto.isSkipProcessReportingDefaultSpecified()) {
            settings.setSkipProcessReportingDefault(Boolean.TRUE.equals(dto.getSkipProcessReportingDefault()));
        }
    }

    /**
     * 解析JSON字符串为指定类型
     * 如果JSON为空或解析失败，返回默认对象
     */
    private <T> T parseJsonField(String jsonString, Class<T> clazz) {
        if (!StringUtils.hasText(jsonString)) {
            return createDefaultObject(clazz);
        }

        try {
            return objectMapper.readValue(jsonString, clazz);
        } catch (JsonProcessingException e) {
            log.warn("解析JSON字段失败，返回默认值: class={}, error={}", clazz.getSimpleName(), e.getMessage());
            return createDefaultObject(clazz);
        }
    }

    /**
     * 将对象转换为JSON字符串
     */
    private String toJsonString(Object obj) {
        if (obj == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("对象转JSON失败: class={}", obj.getClass().getSimpleName(), e);
            throw new BusinessException(500, "JSON序列化失败: " + e.getMessage())
                    .withHint("请稍后重试, 如果问题持续请联系管理员");
        }
    }

    /**
     * 创建默认对象实例
     */
    private <T> T createDefaultObject(Class<T> clazz) {
        try {
            // 使用Builder模式创建默认实例
            if (clazz == FactorySettingsDTO.AISettings.class) {
                return clazz.cast(FactorySettingsDTO.AISettings.builder()
                        .enabled(true)
                        .tone("professional")
                        .goal("cost_optimization")
                        .detailLevel("standard")
                        .build());
            } else if (clazz == FactorySettingsDTO.NotificationSettings.class) {
                return clazz.cast(FactorySettingsDTO.NotificationSettings.builder()
                        .emailEnabled(true)
                        .pushEnabled(true)
                        .wechatEnabled(false)
                        .build());
            } else if (clazz == FactorySettingsDTO.WorkTimeSettings.class) {
                return clazz.cast(FactorySettingsDTO.WorkTimeSettings.builder()
                        .startTime("08:00")
                        .endTime("18:00")
                        .workDays(List.of(new String[]{"1", "2", "3", "4", "5"}))
                        .build());
            } else if (clazz == FactorySettingsDTO.ProductionSettings.class) {
                return clazz.cast(FactorySettingsDTO.ProductionSettings.builder()
                        .defaultBatchSize(100)
                        .qualityCheckFrequency("10")
                        .autoApprovalThreshold(95)
                        .build());
            } else if (clazz == FactorySettingsDTO.InventorySettings.class) {
                return clazz.cast(FactorySettingsDTO.InventorySettings.builder()
                        .minStockAlert(100)
                        .maxStockLimit(10000)
                        .autoReorderPoint(200)
                        .build());
            } else if (clazz == FactorySettingsDTO.DataRetentionSettings.class) {
                return clazz.cast(FactorySettingsDTO.DataRetentionSettings.builder()
                        .logRetentionDays(90)
                        .dataArchiveDays(365)
                        .backupFrequency("daily")
                        .build());
            }

            // 如果没有特定的默认值，尝试使用无参构造器
            return clazz.getDeclaredConstructor().newInstance();

        } catch (Exception e) {
            log.error("创建默认对象失败: class={}", clazz.getSimpleName(), e);
            throw new BusinessException(500, "创建默认对象失败: " + e.getMessage())
                    .withHint("请稍后重试, 如果问题持续请联系管理员");
        }
    }
}
