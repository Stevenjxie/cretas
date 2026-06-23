package com.cretas.aims.service.impl;

import com.cretas.aims.dto.blueprint.*;
import com.cretas.aims.entity.config.BlueprintVersionHistory;
import com.cretas.aims.entity.config.FactoryBlueprintBinding;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.repository.config.BlueprintVersionHistoryRepository;
import com.cretas.aims.repository.config.FactoryBlueprintBindingRepository;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.service.BlueprintVersionService;
import com.cretas.aims.service.FactoryBlueprintService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cretas.aims.exception.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 蓝图版本管理服务实现
 *
 * Sprint 3 任务: S3-7 蓝图版本管理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlueprintVersionServiceImpl implements BlueprintVersionService {

    private final BlueprintVersionHistoryRepository versionHistoryRepository;
    private final FactoryBlueprintBindingRepository bindingRepository;
    private final FactoryTypeBlueprintRepository blueprintRepository;
    private final ObjectMapper objectMapper;

    // ==================== 版本历史管理 ====================

    @Override
    public List<BlueprintVersionDTO> getVersionHistory(String blueprintId) {
        log.info("获取蓝图版本历史: {}", blueprintId);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        return versionHistoryRepository.findByBlueprintIdOrderByVersionDesc(blueprintId)
                .stream()
                .map(h -> convertToVersionDTO(h, blueprint.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public BlueprintVersionDTO getVersion(String blueprintId, Integer version) {
        log.info("获取蓝图版本: {} v{}", blueprintId, version);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        BlueprintVersionHistory history = versionHistoryRepository
                .findByBlueprintIdAndVersion(blueprintId, version)
                .orElseThrow(() -> new EntityNotFoundException(
                        "版本不存在: " + blueprintId + " v" + version));

        return convertToVersionDTO(history, blueprint.getName());
    }

    @Override
    public BlueprintVersionDTO getLatestVersion(String blueprintId) {
        log.info("获取蓝图最新版本: {}", blueprintId);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        return versionHistoryRepository.findFirstByBlueprintIdOrderByVersionDesc(blueprintId)
                .map(h -> convertToVersionDTO(h, blueprint.getName()))
                .orElse(null);
    }

    @Override
    @Transactional
    public BlueprintVersionDTO createVersion(String blueprintId, String changeType,
                                              String changeDescription, Long createdBy) {
        log.info("创建新版本: {} ({})", blueprintId, changeType);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        // 获取下一个版本号
        Integer maxVersion = versionHistoryRepository.getMaxVersionByBlueprintId(blueprintId);
        Integer nextVersion = maxVersion + 1;

        // 获取上一版本用于计算差异
        BlueprintVersionHistory previousVersion = versionHistoryRepository
                .findFirstByBlueprintIdOrderByVersionDesc(blueprintId)
                .orElse(null);

        // 创建快照
        String snapshotData = createSnapshot(blueprint);

        // 计算变更摘要
        String changeSummary = calculateChangeSummary(previousVersion, blueprint);

        // 创建版本历史记录
        BlueprintVersionHistory history = BlueprintVersionHistory.builder()
                .blueprintId(blueprintId)
                .version(nextVersion)
                .changeType(changeType)
                .changeDescription(changeDescription)
                .snapshotData(snapshotData)
                .changeSummary(changeSummary)
                .isPublished(false)
                .createdBy(createdBy)
                .build();

        history = versionHistoryRepository.save(history);

        // 更新蓝图版本号
        blueprint.setVersion(nextVersion);
        blueprintRepository.save(blueprint);

        log.info("成功创建版本: {} v{}", blueprintId, nextVersion);
        return convertToVersionDTO(history, blueprint.getName());
    }

    @Override
    public BlueprintVersionDTO.VersionChangeSummary compareVersions(String blueprintId,
                                                                     Integer fromVersion, Integer toVersion) {
        log.info("比较版本: {} v{} -> v{}", blueprintId, fromVersion, toVersion);

        BlueprintVersionHistory from = versionHistoryRepository
                .findByBlueprintIdAndVersion(blueprintId, fromVersion)
                .orElseThrow(() -> new EntityNotFoundException("版本不存在: v" + fromVersion));

        BlueprintVersionHistory to = versionHistoryRepository
                .findByBlueprintIdAndVersion(blueprintId, toVersion)
                .orElseThrow(() -> new EntityNotFoundException("版本不存在: v" + toVersion));

        return calculateDiff(from.getSnapshotData(), to.getSnapshotData());
    }

    // ==================== 版本发布 ====================

    @Override
    @Transactional
    public BlueprintVersionDTO publishVersion(String blueprintId, PublishVersionRequest request) {
        log.info("发布版本: {}", blueprintId);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        // 创建新版本
        BlueprintVersionDTO newVersion = createVersion(
                blueprintId, "PUBLISH", request.getChangeDescription(), request.getPublishedBy());

        // 标记为已发布
        BlueprintVersionHistory history = versionHistoryRepository
                .findByBlueprintIdAndVersion(blueprintId, newVersion.getVersion())
                .orElseThrow();

        history.setIsPublished(true);
        history.setPublishedAt(LocalDateTime.now());
        versionHistoryRepository.save(history);

        // 更新绑定工厂的最新版本
        List<FactoryBlueprintBinding> bindings = bindingRepository
                .findByBlueprintIdAndDeletedAtIsNull(blueprintId);

        for (FactoryBlueprintBinding binding : bindings) {
            binding.setLatestVersion(newVersion.getVersion());
            binding.setPendingVersion(newVersion.getVersion());
            binding.setNotificationStatus("PENDING");
        }
        bindingRepository.saveAll(bindings);

        // 通知工厂
        if (Boolean.TRUE.equals(request.getNotifyFactories())) {
            notifyFactoriesOfNewVersion(blueprintId, newVersion.getVersion());
        }

        // 处理自动更新
        processAutoUpdates(blueprintId);

        newVersion.setIsPublished(true);
        newVersion.setPublishedAt(history.getPublishedAt());

        log.info("成功发布版本: {} v{}", blueprintId, newVersion.getVersion());
        return newVersion;
    }

    @Override
    public List<BlueprintVersionDTO> getPublishedVersions(String blueprintId) {
        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        return versionHistoryRepository.findByBlueprintIdAndIsPublishedTrueOrderByVersionDesc(blueprintId)
                .stream()
                .map(h -> convertToVersionDTO(h, blueprint.getName()))
                .collect(Collectors.toList());
    }

    // ==================== 工厂绑定管理 ====================

    @Override
    public List<FactoryBindingDTO> getBindingFactories(String blueprintId) {
        log.info("获取蓝图绑定的工厂: {}", blueprintId);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        return bindingRepository.findByBlueprintIdAndDeletedAtIsNull(blueprintId)
                .stream()
                .map(b -> convertToBindingDTO(b, blueprint.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public FactoryBindingDTO getFactoryBinding(String factoryId) {
        return bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .map(b -> {
                    FactoryTypeBlueprint blueprint = blueprintRepository
                            .findByIdAndDeletedAtIsNull(b.getBlueprintId())
                            .orElse(null);
                    String blueprintName = blueprint != null ? blueprint.getName() : "未知蓝图";
                    return convertToBindingDTO(b, blueprintName);
                })
                .orElse(null);
    }

    @Override
    @Transactional
    public FactoryBindingDTO createBinding(String factoryId, String blueprintId, Integer version) {
        log.info("创建工厂蓝图绑定: {} -> {} v{}", factoryId, blueprintId, version);

        // 检查是否已存在绑定
        if (bindingRepository.existsByFactoryIdAndDeletedAtIsNull(factoryId)) {
            throw new IllegalStateException("工厂已绑定蓝图: " + factoryId);
        }

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        FactoryBlueprintBinding binding = FactoryBlueprintBinding.builder()
                .factoryId(factoryId)
                .blueprintId(blueprintId)
                .appliedVersion(version)
                .latestVersion(blueprint.getVersion())
                .autoUpdate(false)
                .updatePolicy("MANUAL")
                .lastAppliedAt(LocalDateTime.now())
                .lastCheckedAt(LocalDateTime.now())
                .notificationStatus("NONE")
                .build();

        binding = bindingRepository.save(binding);

        log.info("成功创建绑定: {}", binding.getId());
        return convertToBindingDTO(binding, blueprint.getName());
    }

    @Override
    @Transactional
    public FactoryBindingDTO updateBindingSettings(String factoryId, Boolean autoUpdate, String updatePolicy) {
        log.info("更新绑定设置: {} autoUpdate={} policy={}", factoryId, autoUpdate, updatePolicy);

        FactoryBlueprintBinding binding = bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .orElseThrow(() -> new EntityNotFoundException("工厂未绑定蓝图: " + factoryId));

        if (autoUpdate != null) {
            binding.setAutoUpdate(autoUpdate);
        }
        if (updatePolicy != null) {
            binding.setUpdatePolicy(updatePolicy);
        }

        binding = bindingRepository.save(binding);

        FactoryTypeBlueprint blueprint = blueprintRepository
                .findByIdAndDeletedAtIsNull(binding.getBlueprintId())
                .orElse(null);
        String blueprintName = blueprint != null ? blueprint.getName() : "未知蓝图";

        return convertToBindingDTO(binding, blueprintName);
    }

    // ==================== 版本升级推送 ====================

    @Override
    public List<FactoryBindingDTO> getOutdatedFactories(String blueprintId) {
        log.info("获取需要升级的工厂: {}", blueprintId);

        FactoryTypeBlueprint blueprint = getBlueprintOrThrow(blueprintId);

        return bindingRepository.findOutdatedFactories(blueprintId)
                .stream()
                .map(b -> convertToBindingDTO(b, blueprint.getName()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public VersionUpgradeResult upgradeFactory(String factoryId, UpgradeFactoryRequest request) {
        log.info("升级工厂版本: {} -> v{}", factoryId, request.getTargetVersion());

        FactoryBlueprintBinding binding = bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .orElseThrow(() -> new EntityNotFoundException("工厂未绑定蓝图: " + factoryId));

        Integer fromVersion = binding.getAppliedVersion();
        Integer targetVersionParam = request.getTargetVersion();

        // 如果未指定目标版本，升级到最新
        final Integer toVersion = (targetVersionParam != null)
                ? targetVersionParam
                : binding.getLatestVersion();

        // 检查版本有效性
        if (toVersion <= fromVersion) {
            return VersionUpgradeResult.builder()
                    .success(false)
                    .factoryId(factoryId)
                    .fromVersion(fromVersion)
                    .toVersion(toVersion)
                    .errors(Collections.singletonList("目标版本必须大于当前版本"))
                    .summary("升级失败: 目标版本无效")
                    .build();
        }

        // 获取目标版本快照
        BlueprintVersionHistory targetHistory = versionHistoryRepository
                .findByBlueprintIdAndVersion(binding.getBlueprintId(), toVersion)
                .orElseThrow(() -> new EntityNotFoundException("目标版本不存在: v" + toVersion));

        // 执行升级
        List<VersionUpgradeResult.UpgradeDetail> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // 应用版本变更
            details = applyVersionUpgrade(factoryId, targetHistory, request.getForceUpgrade());

            // 更新绑定记录
            binding.setAppliedVersion(toVersion);
            binding.setLastAppliedAt(LocalDateTime.now());
            binding.setPendingVersion(null);
            binding.setNotificationStatus("NONE");
            bindingRepository.save(binding);

            log.info("成功升级工厂 {} 从 v{} 到 v{}", factoryId, fromVersion, toVersion);

            return VersionUpgradeResult.builder()
                    .success(true)
                    .factoryId(factoryId)
                    .fromVersion(fromVersion)
                    .toVersion(toVersion)
                    .upgradedAt(LocalDateTime.now())
                    .details(details)
                    .warnings(warnings)
                    .summary(String.format("成功从 v%d 升级到 v%d", fromVersion, toVersion))
                    .build();

        } catch (Exception e) {
            log.error("升级工厂失败: {}", factoryId, e);
            return VersionUpgradeResult.builder()
                    .success(false)
                    .factoryId(factoryId)
                    .fromVersion(fromVersion)
                    .toVersion(toVersion)
                    .errors(Collections.singletonList(e.getMessage()))
                    .summary("升级失败: " + e.getMessage())
                    .build();
        }
    }

    @Override
    @Transactional
    public List<VersionUpgradeResult> batchUpgradeFactories(List<String> factoryIds, UpgradeFactoryRequest request) {
        log.info("批量升级工厂: {} 个", factoryIds.size());

        return factoryIds.stream()
                .map(factoryId -> upgradeFactory(factoryId, request))
                .collect(Collectors.toList());
    }

    @Override
    public VersionUpgradeResult previewUpgrade(String factoryId, Integer targetVersion) {
        log.info("预览升级效果: {} -> v{}", factoryId, targetVersion);

        FactoryBlueprintBinding binding = bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .orElseThrow(() -> new EntityNotFoundException("工厂未绑定蓝图: " + factoryId));

        Integer fromVersion = binding.getAppliedVersion();
        if (targetVersion == null) {
            targetVersion = binding.getLatestVersion();
        }

        // 计算差异
        BlueprintVersionDTO.VersionChangeSummary diff = compareVersions(
                binding.getBlueprintId(), fromVersion, targetVersion);

        // 构建预览详情
        List<VersionUpgradeResult.UpgradeDetail> details = buildPreviewDetails(diff);

        return VersionUpgradeResult.builder()
                .success(true)
                .factoryId(factoryId)
                .fromVersion(fromVersion)
                .toVersion(targetVersion)
                .details(details)
                .summary(String.format("[预览] 从 v%d 升级到 v%d", fromVersion, targetVersion))
                .build();
    }

    @Override
    public void notifyFactoriesOfNewVersion(String blueprintId, Integer version) {
        log.info("通知工厂新版本: {} v{}", blueprintId, version);

        List<FactoryBlueprintBinding> bindings = bindingRepository
                .findByBlueprintIdAndDeletedAtIsNull(blueprintId);

        for (FactoryBlueprintBinding binding : bindings) {
            if (binding.getAppliedVersion() < version) {
                binding.setNotificationStatus("NOTIFIED");
                binding.setPendingVersion(version);
                // TODO: 发送实际通知 (推送、邮件等)
                log.info("已通知工厂 {} 有新版本 v{}", binding.getFactoryId(), version);
            }
        }

        bindingRepository.saveAll(bindings);
    }

    @Override
    @Transactional
    public int processAutoUpdates(String blueprintId) {
        log.info("处理自动更新: {}", blueprintId);

        List<FactoryBlueprintBinding> autoUpdateFactories = bindingRepository
                .findByBlueprintIdAndAutoUpdateTrueAndDeletedAtIsNull(blueprintId);

        int upgraded = 0;
        for (FactoryBlueprintBinding binding : autoUpdateFactories) {
            if (binding.getAppliedVersion() < binding.getLatestVersion()) {
                try {
                    UpgradeFactoryRequest request = UpgradeFactoryRequest.builder()
                            .targetVersion(binding.getLatestVersion())
                            .forceUpgrade(false)
                            .upgradeNote("自动更新")
                            .build();

                    VersionUpgradeResult result = upgradeFactory(binding.getFactoryId(), request);
                    if (result.getSuccess()) {
                        upgraded++;
                    }
                } catch (Exception e) {
                    log.error("自动更新工厂失败: {}", binding.getFactoryId(), e);
                }
            }
        }

        log.info("自动更新完成: 成功 {} 个", upgraded);
        return upgraded;
    }

    @Override
    @Transactional
    public VersionUpgradeResult rollbackFactory(String factoryId, Integer targetVersion, String reason) {
        log.info("回滚工厂版本: {} -> v{}, 原因: {}", factoryId, targetVersion, reason);

        FactoryBlueprintBinding binding = bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .orElseThrow(() -> new EntityNotFoundException("工厂未绑定蓝图: " + factoryId));

        Integer currentVersion = binding.getAppliedVersion();

        if (targetVersion >= currentVersion) {
            return VersionUpgradeResult.builder()
                    .success(false)
                    .factoryId(factoryId)
                    .fromVersion(currentVersion)
                    .toVersion(targetVersion)
                    .errors(Collections.singletonList("回滚目标版本必须小于当前版本"))
                    .summary("回滚失败: 目标版本无效")
                    .build();
        }

        // 获取目标版本快照
        BlueprintVersionHistory targetHistory = versionHistoryRepository
                .findByBlueprintIdAndVersion(binding.getBlueprintId(), targetVersion)
                .orElseThrow(() -> new EntityNotFoundException("目标版本不存在: v" + targetVersion));

        try {
            // 应用回滚
            List<VersionUpgradeResult.UpgradeDetail> details = applyVersionUpgrade(
                    factoryId, targetHistory, true);

            // 更新绑定记录
            binding.setAppliedVersion(targetVersion);
            binding.setLastAppliedAt(LocalDateTime.now());
            bindingRepository.save(binding);

            log.info("成功回滚工厂 {} 从 v{} 到 v{}", factoryId, currentVersion, targetVersion);

            return VersionUpgradeResult.builder()
                    .success(true)
                    .factoryId(factoryId)
                    .fromVersion(currentVersion)
                    .toVersion(targetVersion)
                    .upgradedAt(LocalDateTime.now())
                    .details(details)
                    .summary(String.format("成功回滚从 v%d 到 v%d, 原因: %s",
                            currentVersion, targetVersion, reason))
                    .build();

        } catch (Exception e) {
            log.error("回滚工厂失败: {}", factoryId, e);
            return VersionUpgradeResult.builder()
                    .success(false)
                    .factoryId(factoryId)
                    .fromVersion(currentVersion)
                    .toVersion(targetVersion)
                    .errors(Collections.singletonList(e.getMessage()))
                    .summary("回滚失败: " + e.getMessage())
                    .build();
        }
    }

    // ==================== 私有辅助方法 ====================

    private FactoryTypeBlueprint getBlueprintOrThrow(String blueprintId) {
        return blueprintRepository.findByIdAndDeletedAtIsNull(blueprintId)
                .orElseThrow(() -> new EntityNotFoundException("蓝图不存在: " + blueprintId));
    }

    private BlueprintVersionDTO convertToVersionDTO(BlueprintVersionHistory history, String blueprintName) {
        BlueprintVersionDTO.VersionChangeSummary summary = null;
        if (history.getChangeSummary() != null) {
            try {
                summary = objectMapper.readValue(history.getChangeSummary(),
                        BlueprintVersionDTO.VersionChangeSummary.class);
            } catch (JsonProcessingException e) {
                log.warn("解析变更摘要失败", e);
            }
        }

        return BlueprintVersionDTO.builder()
                .id(history.getId())
                .blueprintId(history.getBlueprintId())
                .blueprintName(blueprintName)
                .version(history.getVersion())
                .changeType(history.getChangeType())
                .changeDescription(history.getChangeDescription())
                .isPublished(history.getIsPublished())
                .publishedAt(history.getPublishedAt())
                .createdAt(history.getCreatedAt())
                .createdBy(history.getCreatedBy())
                .changeSummary(summary)
                .build();
    }

    private FactoryBindingDTO convertToBindingDTO(FactoryBlueprintBinding binding, String blueprintName) {
        return FactoryBindingDTO.builder()
                .id(binding.getId())
                .factoryId(binding.getFactoryId())
                .blueprintId(binding.getBlueprintId())
                .blueprintName(blueprintName)
                .appliedVersion(binding.getAppliedVersion())
                .latestVersion(binding.getLatestVersion())
                .hasUpdate(binding.getLatestVersion() != null &&
                        binding.getAppliedVersion() < binding.getLatestVersion())
                .autoUpdate(binding.getAutoUpdate())
                .updatePolicy(binding.getUpdatePolicy())
                .pendingVersion(binding.getPendingVersion())
                .notificationStatus(binding.getNotificationStatus())
                .lastAppliedAt(binding.getLastAppliedAt())
                .lastCheckedAt(binding.getLastCheckedAt())
                .build();
    }

    private String createSnapshot(FactoryTypeBlueprint blueprint) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("name", blueprint.getName());
            snapshot.put("description", blueprint.getDescription());
            snapshot.put("industryType", blueprint.getIndustryType());
            snapshot.put("defaultConfig", blueprint.getDefaultConfig());
            snapshot.put("formTemplates", blueprint.getFormTemplates());
            snapshot.put("ruleTemplates", blueprint.getRuleTemplates());
            snapshot.put("productTypeTemplates", blueprint.getProductTypeTemplates());
            snapshot.put("departmentTemplates", blueprint.getDepartmentTemplates());
            snapshot.put("version", blueprint.getVersion());
            snapshot.put("snapshotTime", LocalDateTime.now().toString());

            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("创建快照失败", e);
            return "{}";
        }
    }

    private String calculateChangeSummary(BlueprintVersionHistory previous, FactoryTypeBlueprint current) {
        BlueprintVersionDTO.VersionChangeSummary summary = BlueprintVersionDTO.VersionChangeSummary.builder()
                .addedFormTemplates(0)
                .modifiedFormTemplates(0)
                .removedFormTemplates(0)
                .addedRuleTemplates(0)
                .modifiedRuleTemplates(0)
                .removedRuleTemplates(0)
                .addedProductTypes(0)
                .modifiedProductTypes(0)
                .removedProductTypes(0)
                .configChanges(new ArrayList<>())
                .build();

        if (previous != null && previous.getSnapshotData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> prevSnapshot = objectMapper.readValue(
                        previous.getSnapshotData(), Map.class);

                // 比较表单模板
                summary.setAddedFormTemplates(compareJsonArrayCount(
                        (String) prevSnapshot.get("formTemplates"),
                        current.getFormTemplates(), true));
                summary.setRemovedFormTemplates(compareJsonArrayCount(
                        (String) prevSnapshot.get("formTemplates"),
                        current.getFormTemplates(), false));

                // 比较规则模板
                summary.setAddedRuleTemplates(compareJsonArrayCount(
                        (String) prevSnapshot.get("ruleTemplates"),
                        current.getRuleTemplates(), true));
                summary.setRemovedRuleTemplates(compareJsonArrayCount(
                        (String) prevSnapshot.get("ruleTemplates"),
                        current.getRuleTemplates(), false));

                // 比较产品类型
                summary.setAddedProductTypes(compareJsonArrayCount(
                        (String) prevSnapshot.get("productTypeTemplates"),
                        current.getProductTypeTemplates(), true));
                summary.setRemovedProductTypes(compareJsonArrayCount(
                        (String) prevSnapshot.get("productTypeTemplates"),
                        current.getProductTypeTemplates(), false));

            } catch (JsonProcessingException e) {
                log.warn("计算变更摘要失败", e);
            }
        }

        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private int compareJsonArrayCount(String oldJson, String newJson, boolean countAdded) {
        try {
            List<?> oldList = oldJson != null ?
                    objectMapper.readValue(oldJson, List.class) : Collections.emptyList();
            List<?> newList = newJson != null ?
                    objectMapper.readValue(newJson, List.class) : Collections.emptyList();

            if (countAdded) {
                return Math.max(0, newList.size() - oldList.size());
            } else {
                return Math.max(0, oldList.size() - newList.size());
            }
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private BlueprintVersionDTO.VersionChangeSummary calculateDiff(String fromSnapshot, String toSnapshot) {
        // 简化实现：基于快照比较
        BlueprintVersionDTO.VersionChangeSummary summary = BlueprintVersionDTO.VersionChangeSummary.builder()
                .configChanges(new ArrayList<>())
                .build();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> from = objectMapper.readValue(fromSnapshot, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> to = objectMapper.readValue(toSnapshot, Map.class);

            // 比较各组件数量变化
            summary.setAddedFormTemplates(compareJsonArrayCount(
                    (String) from.get("formTemplates"),
                    (String) to.get("formTemplates"), true));
            summary.setRemovedFormTemplates(compareJsonArrayCount(
                    (String) from.get("formTemplates"),
                    (String) to.get("formTemplates"), false));

            summary.setAddedRuleTemplates(compareJsonArrayCount(
                    (String) from.get("ruleTemplates"),
                    (String) to.get("ruleTemplates"), true));
            summary.setRemovedRuleTemplates(compareJsonArrayCount(
                    (String) from.get("ruleTemplates"),
                    (String) to.get("ruleTemplates"), false));

            summary.setAddedProductTypes(compareJsonArrayCount(
                    (String) from.get("productTypeTemplates"),
                    (String) to.get("productTypeTemplates"), true));
            summary.setRemovedProductTypes(compareJsonArrayCount(
                    (String) from.get("productTypeTemplates"),
                    (String) to.get("productTypeTemplates"), false));

        } catch (JsonProcessingException e) {
            log.warn("计算差异失败", e);
        }

        return summary;
    }

    private List<VersionUpgradeResult.UpgradeDetail> applyVersionUpgrade(
            String factoryId, BlueprintVersionHistory targetHistory, Boolean forceUpgrade) {

        List<VersionUpgradeResult.UpgradeDetail> details = new ArrayList<>();

        // TODO: 实际应用蓝图变更到工厂
        // 这里需要调用 FactoryBlueprintService 来应用配置

        details.add(VersionUpgradeResult.UpgradeDetail.builder()
                .componentType("BLUEPRINT")
                .componentName("蓝图配置")
                .action("UPDATED")
                .note("应用版本 v" + targetHistory.getVersion())
                .build());

        return details;
    }

    private List<VersionUpgradeResult.UpgradeDetail> buildPreviewDetails(
            BlueprintVersionDTO.VersionChangeSummary diff) {

        List<VersionUpgradeResult.UpgradeDetail> details = new ArrayList<>();

        if (diff.getAddedFormTemplates() != null && diff.getAddedFormTemplates() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("FORM_TEMPLATE")
                    .componentName("表单模板")
                    .action("ADDED")
                    .note("新增 " + diff.getAddedFormTemplates() + " 个")
                    .build());
        }

        if (diff.getRemovedFormTemplates() != null && diff.getRemovedFormTemplates() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("FORM_TEMPLATE")
                    .componentName("表单模板")
                    .action("REMOVED")
                    .note("移除 " + diff.getRemovedFormTemplates() + " 个")
                    .build());
        }

        if (diff.getAddedRuleTemplates() != null && diff.getAddedRuleTemplates() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("RULE")
                    .componentName("规则模板")
                    .action("ADDED")
                    .note("新增 " + diff.getAddedRuleTemplates() + " 个")
                    .build());
        }

        if (diff.getRemovedRuleTemplates() != null && diff.getRemovedRuleTemplates() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("RULE")
                    .componentName("规则模板")
                    .action("REMOVED")
                    .note("移除 " + diff.getRemovedRuleTemplates() + " 个")
                    .build());
        }

        if (diff.getAddedProductTypes() != null && diff.getAddedProductTypes() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("PRODUCT_TYPE")
                    .componentName("产品类型")
                    .action("ADDED")
                    .note("新增 " + diff.getAddedProductTypes() + " 个")
                    .build());
        }

        if (diff.getRemovedProductTypes() != null && diff.getRemovedProductTypes() > 0) {
            details.add(VersionUpgradeResult.UpgradeDetail.builder()
                    .componentType("PRODUCT_TYPE")
                    .componentName("产品类型")
                    .action("REMOVED")
                    .note("移除 " + diff.getRemovedProductTypes() + " 个")
                    .build());
        }

        return details;
    }
}
