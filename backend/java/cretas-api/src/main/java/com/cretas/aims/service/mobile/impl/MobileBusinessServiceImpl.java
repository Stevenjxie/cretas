package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.EquipmentAlert;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.UserFeedback;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.AlertStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserFeedbackRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.mobile.MobileBusinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 移动端业务服务实现
 * <p>
 * 包含仪表盘、人员报表、设备告警、工厂设置、文件上传、崩溃上报等。
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobileBusinessServiceImpl implements MobileBusinessService {

    private final UserRepository userRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final EquipmentRepository equipmentRepository;
    private final TimeClockRecordRepository timeClockRecordRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final EquipmentAlertRepository equipmentAlertRepository;
    private final FactoryRepository factoryRepository;
    private final FactorySettingsRepository factorySettingsRepository;
    private final UserFeedbackRepository userFeedbackRepository;
    private final OssService ossService;
    private final ObjectMapper objectMapper;

    @Value("${app.version.latest:1.0.1}")
    private String latestVersion;

    /** 低于此版本 (versionName) 强制更新；默认 1.0.0 = 现存用户不被强制 */
    @Value("${cretas.app.min-version:1.0.0}")
    private String minVersion;

    /** Android APK 显式直链；配置后优先返回，用于 bN 文件名绕过 CDN/手机缓存 */
    @Value("${app.version.android.download-url:}")
    private String androidDownloadUrl;

    /** Android APK 直链前缀，最终 URL = base + latestVersion + ".apk" */
    @Value("${app.version.android.download-base:https://dl.cretaceousfuture.com/cretas-v}")
    private String androidDownloadBase;

    /** iOS 跳转地址（App Store 国区） */
    @Value("${app.version.ios.download-url:}")
    private String iosDownloadUrl;

    @Value("${app.version.release-notes:}")
    private String releaseNotes;

    /** 最新 APK 字节数（下载页/网关展示），0 表示未配置 */
    @Value("${app.version.file-size:0}")
    private Long fileSize;

    // ==================== 仪表盘 & 同步 ====================

    @Override
    public MobileDTO.DashboardData getDashboardData(String factoryId, Long userId) {
        log.debug("获取仪表盘数据: factoryId={}, userId={}", factoryId, userId);

        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        // 1. 今日产量（千克）- 使用quantity字段，只统计已完成的批次
        Double todayOutputKg = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startOfDay, endOfDay)
                .stream()
                .filter(batch -> batch.getStatus() == ProductionBatchStatus.COMPLETED)
                .filter(batch -> batch.getQuantity() != null)
                .mapToDouble(batch -> batch.getQuantity().doubleValue())
                .sum();

        // 2. 总批次数
        Long totalBatchesLong = productionBatchRepository.countByFactoryId(factoryId);
        Integer totalBatches = totalBatchesLong != null ? totalBatchesLong.intValue() : 0;

        // 3. 总工人数
        Long totalWorkersLong = userRepository.countByFactoryId(factoryId);
        Integer totalWorkers = totalWorkersLong != null ? totalWorkersLong.intValue() : 0;

        // 4. 活跃设备数 (状态为RUNNING)
        Long activeEquipmentLong = equipmentRepository.countByFactoryIdAndStatus(factoryId, "RUNNING");
        Integer activeEquipment = activeEquipmentLong != null ? activeEquipmentLong.intValue() : 0;

        // 5. 总设备数
        Long totalEquipmentLong = equipmentRepository.countByFactoryId(factoryId);
        Integer totalEquipment = totalEquipmentLong != null ? totalEquipmentLong.intValue() : 0;

        log.debug("今日统计: 产量={}kg, 批次={}, 工人={}, 设备={}/{}",
                todayOutputKg, totalBatches, totalWorkers, activeEquipment, totalEquipment);

        return MobileDTO.DashboardData.builder()
                .todayStats(MobileDTO.TodayStats.builder()
                        .productionCount(156)
                        .qualityCheckCount(145)
                        .materialReceived(23)
                        .ordersCompleted(8)
                        .productionEfficiency(92.5)
                        .activeWorkers(45)
                        .todayOutputKg(todayOutputKg)
                        .totalBatches(totalBatches)
                        .totalWorkers(totalWorkers)
                        .activeEquipment(activeEquipment)
                        .totalEquipment(totalEquipment)
                        .build())
                .todoItems(Arrays.asList(
                        MobileDTO.TodoItem.builder()
                                .id("1")
                                .title("质检任务")
                                .description("批次#20250109-001需要质检")
                                .priority("HIGH")
                                .status("PENDING")
                                .dueTime(LocalDateTime.now().plusHours(2))
                                .build()
                ))
                .recentActivities(Arrays.asList(
                        MobileDTO.ActivityLog.builder()
                                .type("PRODUCTION")
                                .title("生产完成")
                                .description("批次#20250109-001生产完成")
                                .operator("张三")
                                .time(LocalDateTime.now().minusHours(1))
                                .build()
                ))
                .alerts(Arrays.asList(
                        MobileDTO.Alert.builder()
                                .type("WARNING")
                                .title("库存预警")
                                .message("原材料A库存不足")
                                .severity("MEDIUM")
                                .time(LocalDateTime.now())
                                .build()
                ))
                .quickActions(Arrays.asList(
                        MobileDTO.QuickAction.builder()
                                .icon("scan")
                                .title("扫码录入")
                                .action("SCAN_INPUT")
                                .color("#4CAF50")
                                .orderIndex(1)
                                .build()
                ))
                .build();
    }

    @Override
    @Transactional
    public MobileDTO.SyncResponse syncData(String factoryId, MobileDTO.SyncRequest request) {
        log.info("数据同步: factoryId={}, dataTypes={}", factoryId, request.getDataTypes());

        // TODO: 实现实际的数据同步逻辑
        return MobileDTO.SyncResponse.builder()
                .serverData(new HashMap<>())
                .conflictCount(new HashMap<>())
                .nextSyncToken(UUID.randomUUID().toString())
                .syncTime(LocalDateTime.now())
                .build();
    }

    @Override
    public MobileDTO.OfflineDataPackage getOfflineDataPackage(String factoryId, Long userId) {
        log.info("获取离线数据包: factoryId={}, userId={}", factoryId, userId);

        // TODO: 生成实际的离线数据包
        return MobileDTO.OfflineDataPackage.builder()
                .packageId(UUID.randomUUID().toString())
                .version("1.0.0")
                .baseData(new HashMap<>())
                .configData(new HashMap<>())
                .generatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    // ==================== 版本 & 配置 ====================

    @Override
    public MobileDTO.VersionCheckResponse checkVersion(String currentVersion, String platform) {
        log.debug("检查版本: current={}, platform={}, latest={}, min={}",
                currentVersion, platform, latestVersion, minVersion);

        // versionName 语义比较；阈值由环境变量驱动 (蓝绿/多实例共享 .env.prod 避免漂移)
        boolean updateAvailable = compareVersion(currentVersion, latestVersion) < 0;
        boolean updateRequired = compareVersion(currentVersion, minVersion) < 0;

        String downloadUrl = "ios".equalsIgnoreCase(platform)
                ? iosDownloadUrl
                : resolveAndroidDownloadUrl();

        return MobileDTO.VersionCheckResponse.builder()
                .currentVersion(currentVersion)
                .latestVersion(latestVersion)
                .minimumVersion(minVersion)
                .updateRequired(updateRequired)
                .updateAvailable(updateAvailable)
                .downloadUrl(downloadUrl)
                .releaseNotes(releaseNotes == null || releaseNotes.isBlank() ? null : releaseNotes)
                .fileSize(fileSize != null && fileSize > 0 ? fileSize : null)
                .build();
    }

    private String resolveAndroidDownloadUrl() {
        return StringUtils.hasText(androidDownloadUrl)
                ? androidDownloadUrl
                : androidDownloadBase + latestVersion + ".apk";
    }

    /**
     * 比较两个 versionName（如 "1.0.1" vs "1.0.10"）。返回 &lt;0 / 0 / &gt;0。
     * 逐段数字比较，缺段补 0，非数字前缀按其前导数字解析（容忍 "1-rc1"）。
     * null/空按 "0"（最旧）处理 —— currentVersion 缺失时会被判定为需强制更新。
     * package-private 便于单元测试直接断言。
     */
    static int compareVersion(String a, String b) {
        String[] pa = (a == null || a.isBlank() ? "0" : a.trim()).split("\\.");
        String[] pb = (b == null || b.isBlank() ? "0" : b.trim()).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? leadingInt(pa[i]) : 0;
            int vb = i < pb.length ? leadingInt(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Object getMobileConfig(String factoryId, String platform) {
        log.debug("获取移动端配置: factoryId={}, platform={}", factoryId, platform);

        // TODO: 从数据库获取实际配置
        Map<String, Object> config = new HashMap<>();
        config.put("theme", "light");
        config.put("language", "zh-CN");
        config.put("features", Arrays.asList("scan", "camera", "location"));
        config.put("syncInterval", 300); // 5分钟
        config.put("offlineMode", true);

        return config;
    }

    // ==================== 文件上传 ====================

    @Override
    @Transactional
    public MobileDTO.UploadResponse uploadFiles(List<MultipartFile> files, String category, String metadata) {
        log.info("移动端文件上传: files={}, category={}", files.size(), category);

        MobileDTO.UploadResponse response = MobileDTO.UploadResponse.builder()
                .files(new ArrayList<>())
                .successCount(0)
                .failedCount(0)
                .build();

        // 从 metadata 中提取 factoryId，如果没有则使用默认值
        String factoryId = "default";
        if (metadata != null && !metadata.isEmpty()) {
            try {
                var metadataMap = objectMapper.readValue(metadata, Map.class);
                if (metadataMap.containsKey("factoryId")) {
                    factoryId = String.valueOf(metadataMap.get("factoryId"));
                }
            } catch (Exception e) {
                log.debug("解析 metadata 失败，使用默认 factoryId: {}", e.getMessage());
            }
        }

        for (MultipartFile file : files) {
            try {
                // 使用 OSS 服务上传文件
                String ossUrl = ossService.uploadFile(file, category, factoryId);

                // 添加到响应
                MobileDTO.UploadedFile uploadedFile = MobileDTO.UploadedFile.builder()
                        .id(UUID.randomUUID().toString())
                        .url(ossUrl)
                        .originalName(file.getOriginalFilename())
                        .size(file.getSize())
                        .contentType(file.getContentType())
                        .uploadTime(LocalDateTime.now())
                        .build();

                response.getFiles().add(uploadedFile);
                response.setSuccessCount(response.getSuccessCount() + 1);
                log.info("文件上传成功: {} -> {}", file.getOriginalFilename(), ossUrl);
            } catch (Exception e) {
                log.error("文件上传失败: {}", file.getOriginalFilename(), e);
                response.setFailedCount(response.getFailedCount() + 1);
            }
        }

        return response;
    }

    // ==================== 崩溃 & 性能上报 ====================

    @Override
    public void reportCrash(MobileDTO.DeviceInfo deviceInfo, String crashLog) {
        log.error("崩溃报告 - 设备: {}, 日志: {}", deviceInfo, crashLog);
        // TODO: 存储崩溃日志到数据库或日志分析系统
    }

    @Override
    public void reportPerformance(MobileDTO.DeviceInfo deviceInfo, Object performanceData) {
        log.info("性能报告 - 设备: {}, 数据: {}", deviceInfo, performanceData);
        // TODO: 存储性能数据用于分析
    }

    // ==================== 人员报表 ====================

    @Override
    public MobileDTO.PersonnelStatistics getPersonnelStatistics(String factoryId, String startDate, String endDate) {
        log.info("获取人员统计: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);

        // 获取工厂所有用户
        List<User> allUsers = userRepository.findByFactoryId(factoryId);
        int totalEmployees = allUsers.size();

        // 解析日期范围（如果提供）
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusMonths(1);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

        // 转换为LocalDateTime范围
        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.plusDays(1).atStartOfDay();

        // 从time_clock_record表批量查询实际考勤数据（避免N+1查询问题）
        List<Long> userIds = allUsers.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        List<TimeClockRecord> allRecords = userIds.isEmpty()
                ? new ArrayList<>()
                : timeClockRecordRepository.findByFactoryIdAndUserIdInAndClockDateBetween(
                        factoryId, userIds, startDateTime, endDateTime);

        // 统计实际出勤人数（在日期范围内有打卡记录的人数）
        Set<Long> presentUserIds = allRecords.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());
        int totalPresent = presentUserIds.size();

        // 缺勤人数
        int totalAbsent = totalEmployees - totalPresent;

        // 计算平均出勤率（出勤人数 / 总人数）
        double avgAttendanceRate = totalEmployees > 0
                ? ((double) totalPresent / totalEmployees) * 100
                : 0.0;

        // 统计活跃部门数（有员工的部门）
        long activeDepartments = allUsers.stream()
                .map(User::getDepartment)
                .filter(dept -> dept != null && !dept.isEmpty())
                .distinct()
                .count();

        // 计算实际总工时（分钟转小时）
        double totalWorkMinutes = allRecords.stream()
                .mapToDouble(record -> record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0.0)
                .sum();
        double totalWorkHours = totalWorkMinutes / 60.0;

        // 计算平均每人工时
        double avgWorkHoursPerEmployee = totalEmployees > 0
                ? totalWorkHours / totalEmployees
                : 0.0;

        // 查询合同即将到期人数 (30天内)
        LocalDate warningDate = LocalDate.now().plusDays(30);
        long expiringContracts = userRepository.countExpiringContracts(factoryId, warningDate);

        log.info("人员统计完成: 总人数={}, 出勤={}, 缺勤={}, 总工时={}小时, 合同即将到期={}",
                totalEmployees, totalPresent, totalAbsent, String.format("%.1f", totalWorkHours), expiringContracts);

        return MobileDTO.PersonnelStatistics.builder()
                .totalEmployees(totalEmployees)
                .totalPresent(totalPresent)
                .totalAbsent(totalAbsent)
                .avgAttendanceRate(avgAttendanceRate)
                .activeDepartments((int) activeDepartments)
                .totalWorkHours(totalWorkHours)
                .avgWorkHoursPerEmployee(avgWorkHoursPerEmployee)
                .expiringContractsCount((int) expiringContracts)
                .build();
    }

    @Override
    public List<MobileDTO.WorkHoursRankingItem> getWorkHoursRanking(String factoryId, String startDate, String endDate, Integer limit) {
        log.info("获取工时排行: factoryId={}, startDate={}, endDate={}, limit={}", factoryId, startDate, endDate, limit);

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.plusDays(1).atStartOfDay();

        // 获取工厂所有激活用户
        List<User> activeUsers = userRepository.findByFactoryIdAndIsActive(factoryId, true);

        // 批量查询所有用户的打卡记录（避免N+1查询问题）
        List<Long> userIds = activeUsers.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        List<TimeClockRecord> allRecords = userIds.isEmpty()
                ? new ArrayList<>()
                : timeClockRecordRepository.findByFactoryIdAndUserIdInAndClockDateBetween(
                        factoryId, userIds, startDateTime, endDateTime);

        // 按用户ID分组
        Map<Long, List<TimeClockRecord>> recordsByUser = allRecords.stream()
                .collect(Collectors.groupingBy(TimeClockRecord::getUserId));

        // 计算日期范围内的总天数
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;

        // 计算每个用户的工时数据
        List<MobileDTO.WorkHoursRankingItem> ranking = new ArrayList<>();

        for (User user : activeUsers) {
            List<TimeClockRecord> records = recordsByUser.getOrDefault(user.getId(), List.of());

            if (records.isEmpty()) {
                continue; // 没有打卡记录的用户不参与排行
            }

            double totalWorkMinutes = records.stream()
                    .mapToDouble(r -> r.getWorkDurationMinutes() != null ? r.getWorkDurationMinutes() : 0.0)
                    .sum();
            double totalWorkHours = totalWorkMinutes / 60.0;

            double totalOvertimeMinutes = records.stream()
                    .mapToDouble(r -> r.getOvertimeMinutes() != null ? r.getOvertimeMinutes() : 0.0)
                    .sum();
            double totalOvertimeHours = totalOvertimeMinutes / 60.0;

            int attendanceDays = records.size();

            double attendanceRate = totalDays > 0
                    ? ((double) attendanceDays / totalDays) * 100
                    : 0.0;

            ranking.add(MobileDTO.WorkHoursRankingItem.builder()
                    .userId(user.getId())
                    .userName(user.getFullName() != null ? user.getFullName() : user.getUsername())
                    .departmentId(user.getDepartment())
                    .departmentName(user.getDepartment() != null ? user.getDepartment() : "未分配")
                    .totalWorkHours(totalWorkHours)
                    .totalOvertimeHours(totalOvertimeHours)
                    .attendanceDays(attendanceDays)
                    .attendanceRate(attendanceRate)
                    .build());
        }

        // 按总工时降序排序
        ranking.sort((a, b) -> Double.compare(b.getTotalWorkHours(), a.getTotalWorkHours()));

        // 返回前N名
        int resultSize = Math.min(limit, ranking.size());
        log.info("工时排行计算完成: 共{}人, 返回前{}名", ranking.size(), resultSize);

        return ranking.subList(0, resultSize);
    }

    @Override
    public MobileDTO.OvertimeStatistics getOvertimeStatistics(String factoryId, String startDate, String endDate, String departmentId) {
        log.info("获取加班统计: factoryId={}, startDate={}, endDate={}, departmentId={}", factoryId, startDate, endDate, departmentId);

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.plusDays(1).atStartOfDay();

        // 获取用户列表（可选按部门筛选）
        List<User> users;
        if (departmentId != null && !departmentId.isEmpty()) {
            users = userRepository.findByFactoryIdAndPosition(factoryId, departmentId);
        } else {
            users = userRepository.findByFactoryIdAndIsActive(factoryId, true);
        }

        // 批量查询所有用户的打卡记录（避免N+1查询问题）
        List<Long> userIds = users.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        List<TimeClockRecord> allRecords = userIds.isEmpty()
                ? new ArrayList<>()
                : timeClockRecordRepository.findByFactoryIdAndUserIdInAndClockDateBetween(
                        factoryId, userIds, startDateTime, endDateTime);

        // 按用户ID分组
        Map<Long, List<TimeClockRecord>> recordsByUser = allRecords.stream()
                .collect(Collectors.groupingBy(TimeClockRecord::getUserId));

        // 统计每个用户的加班数据
        Map<Long, Double> userOvertimeMap = new HashMap<>();
        double totalOvertimeMinutes = 0.0;

        for (User user : users) {
            List<TimeClockRecord> records = recordsByUser.getOrDefault(user.getId(), List.of());

            double userOvertimeMinutes = records.stream()
                    .mapToDouble(r -> r.getOvertimeMinutes() != null ? r.getOvertimeMinutes() : 0.0)
                    .sum();

            if (userOvertimeMinutes > 0) {
                userOvertimeMap.put(user.getId(), userOvertimeMinutes);
                totalOvertimeMinutes += userOvertimeMinutes;
            }
        }

        double totalOvertimeHours = totalOvertimeMinutes / 60.0;
        int totalEmployeesWithOvertime = userOvertimeMap.size();
        double avgOvertimeHoursPerEmployee = totalEmployeesWithOvertime > 0
                ? totalOvertimeHours / totalEmployeesWithOvertime
                : 0.0;

        // 按加班时长排序，获取TOP 10
        List<Map.Entry<Long, Double>> sortedEntries = userOvertimeMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(10)
                .collect(Collectors.toList());

        List<MobileDTO.OvertimeEmployeeItem> topOvertimeEmployees = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : sortedEntries) {
            Long userId = entry.getKey();
            double overtimeMinutes = entry.getValue();

            User user = users.stream()
                    .filter(u -> u.getId().equals(userId))
                    .findFirst()
                    .orElse(null);

            if (user != null) {
                topOvertimeEmployees.add(MobileDTO.OvertimeEmployeeItem.builder()
                        .userId(user.getId())
                        .userName(user.getFullName() != null ? user.getFullName() : user.getUsername())
                        .overtimeHours(overtimeMinutes / 60.0)
                        .build());
            }
        }

        log.info("加班统计完成: 总加班{}小时, {}人有加班记录, TOP 10已生成",
                String.format("%.1f", totalOvertimeHours), totalEmployeesWithOvertime);

        return MobileDTO.OvertimeStatistics.builder()
                .totalOvertimeHours(totalOvertimeHours)
                .totalEmployeesWithOvertime(totalEmployeesWithOvertime)
                .avgOvertimeHoursPerEmployee(avgOvertimeHoursPerEmployee)
                .topOvertimeEmployees(topOvertimeEmployees)
                .build();
    }

    @Override
    public List<MobileDTO.PerformanceItem> getPersonnelPerformance(String factoryId, String startDate, String endDate, Long userId) {
        log.info("获取人员绩效: factoryId={}, startDate={}, endDate={}, userId={}", factoryId, startDate, endDate, userId);

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        LocalDateTime startDateTime = start.atStartOfDay();
        LocalDateTime endDateTime = end.plusDays(1).atStartOfDay();

        // 获取用户列表
        List<User> users;
        if (userId != null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
            users = Arrays.asList(user);
        } else {
            users = userRepository.findByFactoryIdAndIsActive(factoryId, true);
        }

        // ========== 批量预加载所有关联数据（避免3N+1查询问题）==========

        List<Long> userIds = users.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        // 1. 批量查询所有用户的打卡记录
        List<TimeClockRecord> allTimeClockRecords = userIds.isEmpty()
                ? new ArrayList<>()
                : timeClockRecordRepository.findByFactoryIdAndUserIdInAndClockDateBetween(
                        factoryId, userIds, startDateTime, endDateTime);

        Map<Long, List<TimeClockRecord>> timeClockRecordsByUser = allTimeClockRecords.stream()
                .collect(Collectors.groupingBy(TimeClockRecord::getUserId));

        // 2. 一次性查询所有质检记录
        List<QualityInspection> allInspections = qualityInspectionRepository
                .findByFactoryIdAndDateRange(factoryId, start, end);

        Map<Long, List<QualityInspection>> inspectionsByInspector = allInspections.stream()
                .filter(qi -> qi.getInspectorId() != null)
                .collect(Collectors.groupingBy(QualityInspection::getInspectorId));

        // 3. 一次性查询所有生产批次
        List<ProductionBatch> allBatches = productionBatchRepository
                .findBatchesInDateRange(factoryId, startDateTime, endDateTime);

        Map<Long, List<ProductionBatch>> batchesBySupervisor = allBatches.stream()
                .filter(batch -> batch.getSupervisorId() != null)
                .collect(Collectors.groupingBy(ProductionBatch::getSupervisorId));

        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;

        // 计算每个用户的绩效数据
        List<MobileDTO.PerformanceItem> performance = new ArrayList<>();

        for (User user : users) {
            // 1. 打卡记录
            List<TimeClockRecord> records = timeClockRecordsByUser.getOrDefault(user.getId(), List.of());

            double workMinutes = records.stream()
                    .mapToDouble(r -> r.getWorkDurationMinutes() != null ? r.getWorkDurationMinutes() : 0.0)
                    .sum();
            double workHours = workMinutes / 60.0;

            int attendanceDays = records.size();
            double attendanceRate = totalDays > 0
                    ? ((double) attendanceDays / totalDays) * 100
                    : 0.0;

            // 2. 质检记录
            List<QualityInspection> userInspections = inspectionsByInspector.getOrDefault(user.getId(), List.of());

            double qualityScore = 0.0;
            if (!userInspections.isEmpty()) {
                double avgPassRate = userInspections.stream()
                        .filter(qi -> qi.getPassRate() != null)
                        .mapToDouble(qi -> qi.getPassRate().doubleValue())
                        .average()
                        .orElse(0.0);
                qualityScore = avgPassRate;
            } else {
                qualityScore = 85.0; // 无质检记录则给予默认分数
            }

            // 3. 生产批次
            List<ProductionBatch> userBatches = batchesBySupervisor.getOrDefault(user.getId(), List.of());

            double efficiencyScore = 0.0;
            if (!userBatches.isEmpty()) {
                long completedCount = userBatches.stream()
                        .filter(batch -> "COMPLETED".equals(batch.getStatus())
                                || ProductionBatchStatus.COMPLETED.name().equals(batch.getStatus()))
                        .count();
                efficiencyScore = ((double) completedCount / userBatches.size()) * 100;
            } else {
                efficiencyScore = 80.0; // 无批次记录则给予默认分数
            }

            // 4. 综合分数（权重：出勤率30%, 质量40%, 效率30%）
            double overallScore = (attendanceRate * 0.3 + qualityScore * 0.4 + efficiencyScore * 0.3);

            performance.add(MobileDTO.PerformanceItem.builder()
                    .userId(user.getId())
                    .userName(user.getFullName() != null ? user.getFullName() : user.getUsername())
                    .departmentName(user.getDepartment() != null ? user.getDepartment() : "未分配")
                    .workHours(workHours)
                    .attendanceRate(attendanceRate)
                    .qualityScore(qualityScore)
                    .efficiencyScore(efficiencyScore)
                    .overallScore(overallScore)
                    .build());
        }

        // 按综合分数降序排序
        performance.sort((a, b) -> Double.compare(b.getOverallScore(), a.getOverallScore()));

        log.info("人员绩效计算完成: 共{}人", performance.size());

        return performance;
    }

    // ==================== 成本对比 ====================

    @Override
    public List<MobileDTO.BatchCostData> getBatchCostComparison(String factoryId, List<String> batchIds) {
        log.info("获取批次成本对比: factoryId={}, batchIds={}", factoryId, batchIds);

        List<MobileDTO.BatchCostData> costDataList = new ArrayList<>();

        // 批量查询所有批次 - 解决 N+1 问题
        Set<Long> batchIdSet = batchIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        Map<Long, ProductionBatch> batchMap = productionBatchRepository
                .findByIdInAndFactoryId(batchIdSet, factoryId)
                .stream()
                .collect(Collectors.toMap(ProductionBatch::getId, java.util.function.Function.identity()));

        for (String batchId : batchIds) {
            ProductionBatch batch = batchMap.get(Long.valueOf(batchId));

            if (batch == null) {
                log.warn("批次不存在: factoryId={}, batchId={}", factoryId, batchId);
                continue;
            }

            Double totalCost = batch.getTotalCost() != null ? batch.getTotalCost().doubleValue() : 0.0;
            Double laborCost = batch.getLaborCost() != null ? batch.getLaborCost().doubleValue() : 0.0;
            Double materialCost = batch.getMaterialCost() != null ? batch.getMaterialCost().doubleValue() : 0.0;
            Double equipmentCost = batch.getEquipmentCost() != null ? batch.getEquipmentCost().doubleValue() : 0.0;
            Double otherCost = batch.getOtherCost() != null ? batch.getOtherCost().doubleValue() : 0.0;
            Double quantity = batch.getQuantity() != null ? batch.getQuantity().doubleValue() : 0.0;
            Double unitCost = quantity > 0 ? totalCost / quantity : 0.0;

            String date = batch.getStartTime() != null
                    ? batch.getStartTime().toLocalDate().toString()
                    : "";

            MobileDTO.BatchCostData costData = MobileDTO.BatchCostData.builder()
                    .batchId(String.valueOf(batch.getId()))
                    .batchNumber(batch.getBatchNumber())
                    .productType(batch.getProductName())
                    .totalCost(totalCost)
                    .laborCost(laborCost)
                    .materialCost(materialCost)
                    .equipmentCost(equipmentCost)
                    .otherCost(otherCost)
                    .quantity(quantity)
                    .unitCost(unitCost)
                    .date(date)
                    .build();

            costDataList.add(costData);
        }

        log.info("批次成本对比完成: 共{}个批次", costDataList.size());

        return costDataList;
    }

    // ==================== 设备告警 ====================

    @Override
    public com.cretas.aims.dto.common.PageResponse<MobileDTO.AlertResponse> getEquipmentAlerts(String factoryId, String status, com.cretas.aims.dto.common.PageRequest pageRequest) {
        log.info("获取设备告警列表: factoryId={}, status={}, page={}, size={}",
                factoryId, status, pageRequest.getPage(), pageRequest.getSize());

        org.springframework.data.domain.PageRequest springPageRequest =
                org.springframework.data.domain.PageRequest.of(
                    pageRequest.getPage() - 1,
                    pageRequest.getSize(),
                    org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "triggeredAt")
                );

        org.springframework.data.domain.Page<EquipmentAlert> page;
        if (status != null && !status.trim().isEmpty()) {
            AlertStatus alertStatus = AlertStatus.valueOf(status.toUpperCase());
            page = equipmentAlertRepository.findByFactoryIdAndStatus(factoryId, alertStatus, springPageRequest);
        } else {
            page = equipmentAlertRepository.findByFactoryId(factoryId, springPageRequest);
        }

        // 批量预加载所有设备信息（避免N+1查询问题）
        Set<Long> equipmentIds = page.getContent().stream()
                .map(EquipmentAlert::getEquipmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, FactoryEquipment> equipmentMap = equipmentIds.isEmpty()
                ? new HashMap<>()
                : equipmentRepository.findAllById(equipmentIds).stream()
                        .collect(Collectors.toMap(FactoryEquipment::getId, java.util.function.Function.identity()));

        List<MobileDTO.AlertResponse> alertResponses = page.getContent().stream()
                .map(alert -> convertToAlertResponse(alert, equipmentMap))
                .collect(Collectors.toList());

        com.cretas.aims.dto.common.PageResponse<MobileDTO.AlertResponse> response = new com.cretas.aims.dto.common.PageResponse<>();
        response.setContent(alertResponses);
        response.setPage(pageRequest.getPage());
        response.setSize(pageRequest.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setFirst(page.isFirst());
        response.setLast(page.isLast());
        response.setCurrentPage(pageRequest.getPage());

        log.info("获取设备告警列表成功: 共{}条记录", page.getTotalElements());
        return response;
    }

    @Override
    @Transactional
    public MobileDTO.AlertResponse acknowledgeAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.AcknowledgeAlertRequest request) {
        log.info("确认设备告警: factoryId={}, alertId={}, userId={}", factoryId, alertId, userId);

        EquipmentAlert alert = getOrCreateAlert(factoryId, alertId);

        if (alert.getStatus() == AlertStatus.RESOLVED) {
            throw new BusinessException(409, "告警已解决，无法确认")
                    .withHint("请刷新告警列表查看最新状态");
        }

        if (alert.getStatus() == AlertStatus.ACKNOWLEDGED) {
            throw new BusinessException(409, "告警已被确认")
                    .withHint("请刷新告警列表查看最新状态");
        }

        alert.setStatus(AlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setAcknowledgedBy(userId);
        alert.setAcknowledgedByName(username);

        equipmentAlertRepository.save(alert);

        log.info("告警确认成功: alertId={}, userId={}", alertId, userId);

        return convertToAlertResponse(alert);
    }

    @Override
    @Transactional
    public MobileDTO.AlertResponse resolveAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.ResolveAlertRequest request) {
        log.info("解决设备告警: factoryId={}, alertId={}, userId={}", factoryId, alertId, userId);

        EquipmentAlert alert = getOrCreateAlert(factoryId, alertId);

        if (alert.getStatus() == AlertStatus.RESOLVED) {
            throw new BusinessException(409, "告警已解决")
                    .withHint("请刷新告警列表查看最新状态");
        }

        // 如果告警还未确认，先设置确认信息
        if (alert.getAcknowledgedAt() == null) {
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setAcknowledgedAt(LocalDateTime.now());
            alert.setAcknowledgedBy(userId);
            alert.setAcknowledgedByName(username);
        }

        alert.setStatus(AlertStatus.RESOLVED);
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolvedBy(userId);
        alert.setResolvedByName(username);

        if (request != null && StringUtils.hasText(request.getResolutionNotes())) {
            alert.setResolutionNotes(request.getResolutionNotes());
        }

        equipmentAlertRepository.save(alert);

        log.info("告警解决成功: alertId={}, userId={}", alertId, userId);

        return convertToAlertResponse(alert);
    }

    // ==================== 工厂设置 ====================

    @Override
    public MobileDTO.FactorySettingsResponse getFactorySettings(String factoryId) {
        Factory factory = factoryRepository.findById(factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("工厂不存在: factoryId=" + factoryId));

        FactorySettings settings = factorySettingsRepository.findByFactoryId(factoryId)
                .orElseGet(() -> createDefaultFactorySettings(factoryId));

        MobileDTO.WorkTimeSettings workTimeSettings = parseWorkTimeSettings(settings.getWorkTimeSettings());

        return MobileDTO.FactorySettingsResponse.builder()
                .factoryName(factory.getName())
                .factoryAddress(factory.getAddress())
                .contactPhone(factory.getContactPhone())
                .contactEmail(factory.getContactEmail())
                .workingHours(workTimeSettings.getWorkingHours())
                .lunchBreakStart(workTimeSettings.getLunchBreakStart())
                .lunchBreakEnd(workTimeSettings.getLunchBreakEnd())
                .workingDays(workTimeSettings.getWorkingDays())
                .lateThresholdMinutes(workTimeSettings.getLateThresholdMinutes())
                .earlyLeaveThresholdMinutes(workTimeSettings.getEarlyLeaveThresholdMinutes())
                .enableOvertimeTracking(workTimeSettings.getEnableOvertimeTracking())
                .enableGPSChecking(workTimeSettings.getEnableGPSChecking())
                .build();
    }

    @Override
    @Transactional
    public MobileDTO.FactorySettingsResponse updateFactorySettings(
            String factoryId,
            MobileDTO.UpdateFactorySettingsRequest request,
            Long userId) {

        Factory factory = factoryRepository.findById(factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("工厂不存在: factoryId=" + factoryId));

        if (request.getFactoryName() != null) {
            factory.setName(request.getFactoryName());
        }
        if (request.getFactoryAddress() != null) {
            factory.setAddress(request.getFactoryAddress());
        }
        if (request.getContactPhone() != null) {
            factory.setContactPhone(request.getContactPhone());
        }
        if (request.getContactEmail() != null) {
            factory.setContactEmail(request.getContactEmail());
        }
        factoryRepository.save(factory);

        FactorySettings settings = factorySettingsRepository.findByFactoryId(factoryId)
                .orElseGet(() -> createDefaultFactorySettings(factoryId));

        MobileDTO.WorkTimeSettings workTimeSettings = MobileDTO.WorkTimeSettings.builder()
                .workingHours(request.getWorkingHours() != null ? request.getWorkingHours() :
                        MobileDTO.WorkingHours.builder().startTime("08:00").endTime("17:00").build())
                .lunchBreakStart(request.getLunchBreakStart() != null ? request.getLunchBreakStart() : "12:00")
                .lunchBreakEnd(request.getLunchBreakEnd() != null ? request.getLunchBreakEnd() : "13:00")
                .workingDays(request.getWorkingDays() != null ? request.getWorkingDays() :
                        new boolean[]{true, true, true, true, true, false, false})
                .lateThresholdMinutes(request.getLateThresholdMinutes() != null ? request.getLateThresholdMinutes() : 10)
                .earlyLeaveThresholdMinutes(request.getEarlyLeaveThresholdMinutes() != null ?
                        request.getEarlyLeaveThresholdMinutes() : 10)
                .enableOvertimeTracking(request.getEnableOvertimeTracking() != null ?
                        request.getEnableOvertimeTracking() : true)
                .enableGPSChecking(request.getEnableGPSChecking() != null ? request.getEnableGPSChecking() : true)
                .build();

        String workTimeSettingsJson = serializeWorkTimeSettings(workTimeSettings);
        settings.setWorkTimeSettings(workTimeSettingsJson);
        settings.setUpdatedBy(userId.longValue());
        factorySettingsRepository.save(settings);

        log.info("工厂设置已更新: factoryId={}, userId={}", factoryId, userId);

        return getFactorySettings(factoryId);
    }

    // ==================== 用户反馈 ====================

    @Override
    @Transactional
    public MobileDTO.FeedbackResponse submitFeedback(
            String factoryId,
            MobileDTO.SubmitFeedbackRequest request,
            Long userId) {

        if (!request.getType().matches("bug|feature|other")) {
            throw new BusinessException(400, "无效的反馈类型: " + request.getType())
                    .withHint("请选择有效的反馈类型").withHintTarget("type");
        }

        if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "反馈标题不能为空")
                    .withHint("请填写反馈标题").withHintTarget("title");
        }
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new BusinessException(400, "反馈内容不能为空")
                    .withHint("请填写反馈内容").withHintTarget("content");
        }
        if (request.getContent().trim().length() < 10) {
            throw new BusinessException(400, "反馈内容至少10个字符")
                    .withHint("请详细描述问题, 至少 10 字").withHintTarget("content");
        }

        String screenshotsJson = null;
        if (request.getScreenshots() != null && !request.getScreenshots().isEmpty()) {
            try {
                screenshotsJson = objectMapper.writeValueAsString(request.getScreenshots());
            } catch (Exception e) {
                log.error("序列化截图列表失败", e);
                throw new BusinessException(400, "截图数据格式错误")
                        .withHint("请重新选择有效的截图").withHintTarget("screenshot");
            }
        }

        UserFeedback feedback = UserFeedback.builder()
                .factoryId(factoryId)
                .userId(userId.longValue())
                .type(request.getType())
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .contact(request.getContact() != null ? request.getContact().trim() : null)
                .screenshots(screenshotsJson)
                .status("pending")
                .build();

        UserFeedback savedFeedback = userFeedbackRepository.save(feedback);

        log.info("用户反馈提交成功: feedbackId={}, userId={}, type={}",
                savedFeedback.getId(), userId, request.getType());

        return convertToFeedbackResponse(savedFeedback);
    }

    // ==================== Private Helpers ====================

    /**
     * 获取或创建告警记录（支持动态ID）
     */
    private EquipmentAlert getOrCreateAlert(String factoryId, String alertId) {
        // 1. 尝试作为数字ID查询
        if (alertId.matches("\\d+")) {
            Integer numericId = Integer.parseInt(alertId);
            return equipmentAlertRepository.findByFactoryIdAndId(factoryId, numericId)
                    .orElseThrow(() -> new ResourceNotFoundException("告警不存在: alertId=" + alertId));
        }

        // 2. 处理动态ID格式：MAINT_{equipmentId} 或 WARRANTY_{equipmentId}
        if (alertId.startsWith("MAINT_") || alertId.startsWith("WARRANTY_")) {
            String[] parts = alertId.split("_");
            if (parts.length != 2) {
                throw new BusinessException(400, "无效的告警ID格式: " + alertId)
                        .withHint("请刷新告警列表后重新选择").withHintTarget("alertId");
            }

            String alertType = parts[0];
            String equipmentId = parts[1];

            FactoryEquipment equipment = equipmentRepository.findById(Long.valueOf(equipmentId))
                    .orElseThrow(() -> new ResourceNotFoundException("设备不存在: equipmentId=" + equipmentId));

            EquipmentAlert newAlert;
            if ("MAINT".equals(alertType)) {
                newAlert = createMaintenanceAlert(factoryId, equipment);
            } else {
                newAlert = createWarrantyAlert(factoryId, equipment);
            }

            return equipmentAlertRepository.save(newAlert);
        }

        throw new BusinessException(400, "不支持的告警ID格式: " + alertId)
                .withHint("请刷新告警列表后重新选择").withHintTarget("alertId");
    }

    /**
     * 创建维护告警
     */
    private EquipmentAlert createMaintenanceAlert(String factoryId, FactoryEquipment equipment) {
        LocalDate nextMaintenanceDate = equipment.getNextMaintenanceDate();
        LocalDateTime triggeredAt = nextMaintenanceDate != null
                ? nextMaintenanceDate.atStartOfDay()
                : LocalDateTime.now();

        long daysOverdue = 0;
        if (nextMaintenanceDate != null) {
            daysOverdue = LocalDate.now().toEpochDay() - nextMaintenanceDate.toEpochDay();
        }

        String message;
        com.cretas.aims.entity.enums.DeviceAlertLevel level;

        if (daysOverdue > 7) {
            level = com.cretas.aims.entity.enums.DeviceAlertLevel.CRITICAL;
            message = String.format("设备维护已逾期 %d 天", daysOverdue);
        } else if (daysOverdue > 0) {
            level = com.cretas.aims.entity.enums.DeviceAlertLevel.WARNING;
            message = String.format("设备维护已逾期 %d 天", daysOverdue);
        } else {
            level = com.cretas.aims.entity.enums.DeviceAlertLevel.WARNING;
            message = "设备即将到达维护周期";
        }

        String details = String.format("上次维护: %s\n下次维护: %s",
                equipment.getLastMaintenanceDate() != null ? equipment.getLastMaintenanceDate().toString() : "未记录",
                nextMaintenanceDate != null ? nextMaintenanceDate.toString() : "未设置");

        return EquipmentAlert.builder()
                .factoryId(factoryId)
                .equipmentId(equipment.getId())
                .alertType("维护提醒")
                .level(level)
                .status(AlertStatus.ACTIVE)
                .message(message)
                .details(details)
                .triggeredAt(triggeredAt)
                .build();
    }

    /**
     * 创建保修告警
     */
    private EquipmentAlert createWarrantyAlert(String factoryId, FactoryEquipment equipment) {
        LocalDate warrantyExpiryDate = null;
        if (equipment.getPurchaseDate() != null) {
            warrantyExpiryDate = equipment.getPurchaseDate().plusYears(2);
        }

        LocalDateTime triggeredAt = warrantyExpiryDate != null
                ? warrantyExpiryDate.atStartOfDay()
                : LocalDateTime.now();

        long daysRemaining = 0;
        if (warrantyExpiryDate != null) {
            daysRemaining = warrantyExpiryDate.toEpochDay() - LocalDate.now().toEpochDay();
        }

        String message;
        com.cretas.aims.entity.enums.DeviceAlertLevel level;

        if (daysRemaining <= 7) {
            level = com.cretas.aims.entity.enums.DeviceAlertLevel.WARNING;
            message = String.format("保修将在 %d 天后到期", daysRemaining);
        } else {
            level = com.cretas.aims.entity.enums.DeviceAlertLevel.INFO;
            message = String.format("保修将在 %d 天后到期", daysRemaining);
        }

        String details = String.format("购买日期: %s\n保修到期: %s\n制造商: %s",
                equipment.getPurchaseDate() != null ? equipment.getPurchaseDate().toString() : "未知",
                warrantyExpiryDate != null ? warrantyExpiryDate.toString() : "未知",
                equipment.getManufacturer() != null ? equipment.getManufacturer() : "未知");

        return EquipmentAlert.builder()
                .factoryId(factoryId)
                .equipmentId(equipment.getId())
                .alertType("保修即将到期")
                .level(level)
                .status(AlertStatus.ACTIVE)
                .message(message)
                .details(details)
                .triggeredAt(triggeredAt)
                .build();
    }

    /**
     * 转换告警实体为响应DTO（单个告警版本）
     */
    private MobileDTO.AlertResponse convertToAlertResponse(EquipmentAlert alert) {
        String equipmentName = equipmentRepository.findById(alert.getEquipmentId())
                .map(eq -> eq.getEquipmentName())
                .orElse("未知设备");

        return convertToAlertResponseInternal(alert, equipmentName);
    }

    /**
     * 转换告警实体为响应DTO（批量版本，使用预加载的设备Map避免N+1查询）
     */
    private MobileDTO.AlertResponse convertToAlertResponse(EquipmentAlert alert, Map<Long, FactoryEquipment> equipmentMap) {
        String equipmentName = "未知设备";
        if (alert.getEquipmentId() != null && equipmentMap.containsKey(alert.getEquipmentId())) {
            FactoryEquipment equipment = equipmentMap.get(alert.getEquipmentId());
            equipmentName = equipment.getEquipmentName() != null ? equipment.getEquipmentName() : "未知设备";
        }

        return convertToAlertResponseInternal(alert, equipmentName);
    }

    /**
     * 转换告警实体为响应DTO的内部实现
     */
    private MobileDTO.AlertResponse convertToAlertResponseInternal(EquipmentAlert alert, String equipmentName) {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

        return MobileDTO.AlertResponse.builder()
                .id(alert.getId())
                .factoryId(alert.getFactoryId())
                .equipmentId(String.valueOf(alert.getEquipmentId()))
                .equipmentName(equipmentName)
                .alertType(alert.getAlertType())
                .level(alert.getLevel().name())
                .status(alert.getStatus().name())
                .message(alert.getMessage())
                .details(alert.getDetails())
                .triggeredAt(alert.getTriggeredAt() != null ? alert.getTriggeredAt().format(formatter) : null)
                .acknowledgedAt(alert.getAcknowledgedAt() != null ? alert.getAcknowledgedAt().format(formatter) : null)
                .acknowledgedBy(alert.getAcknowledgedByName())
                .resolvedAt(alert.getResolvedAt() != null ? alert.getResolvedAt().format(formatter) : null)
                .resolvedBy(alert.getResolvedByName())
                .resolutionNotes(alert.getResolutionNotes())
                .build();
    }

    /**
     * 创建默认工厂设置
     */
    private FactorySettings createDefaultFactorySettings(String factoryId) {
        MobileDTO.WorkTimeSettings defaultWorkTime = MobileDTO.WorkTimeSettings.builder()
                .workingHours(MobileDTO.WorkingHours.builder()
                        .startTime("08:00")
                        .endTime("17:00")
                        .build())
                .lunchBreakStart("12:00")
                .lunchBreakEnd("13:00")
                .workingDays(new boolean[]{true, true, true, true, true, false, false})
                .lateThresholdMinutes(10)
                .earlyLeaveThresholdMinutes(10)
                .enableOvertimeTracking(true)
                .enableGPSChecking(true)
                .build();

        String workTimeJson = serializeWorkTimeSettings(defaultWorkTime);

        FactorySettings settings = FactorySettings.builder()
                .factoryId(factoryId)
                .workTimeSettings(workTimeJson)
                .allowSelfRegistration(false)
                .requireAdminApproval(true)
                .defaultUserRole("viewer")
                .language("zh-CN")
                .timezone("Asia/Shanghai")
                .dateFormat("yyyy-MM-dd")
                .currency("CNY")
                .enableQrCode(true)
                .enableBatchManagement(true)
                .enableQualityCheck(true)
                .enableCostCalculation(true)
                .enableEquipmentManagement(true)
                .enableAttendance(true)
                .aiWeeklyQuota(20)
                .build();

        return factorySettingsRepository.save(settings);
    }

    /**
     * 解析工作时间设置JSON
     */
    private MobileDTO.WorkTimeSettings parseWorkTimeSettings(String json) {
        if (json == null || json.trim().isEmpty()) {
            return MobileDTO.WorkTimeSettings.builder()
                    .workingHours(MobileDTO.WorkingHours.builder()
                            .startTime("08:00")
                            .endTime("17:00")
                            .build())
                    .lunchBreakStart("12:00")
                    .lunchBreakEnd("13:00")
                    .workingDays(new boolean[]{true, true, true, true, true, false, false})
                    .lateThresholdMinutes(10)
                    .earlyLeaveThresholdMinutes(10)
                    .enableOvertimeTracking(true)
                    .enableGPSChecking(true)
                    .build();
        }

        try {
            return objectMapper.readValue(json, MobileDTO.WorkTimeSettings.class);
        } catch (Exception e) {
            log.error("解析工作时间设置JSON失败: {}", json, e);
            throw new BusinessException(400, "工作时间设置格式错误")
                    .withHint("请检查工作时间配置 JSON 格式").withHintTarget("workSchedule");
        }
    }

    /**
     * 序列化工作时间设置为JSON
     */
    private String serializeWorkTimeSettings(MobileDTO.WorkTimeSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            log.error("序列化工作时间设置失败", e);
            throw new BusinessException(500, "工作时间设置序列化失败")
                    .withHint("请稍后重试, 如果问题持续请联系管理员");
        }
    }

    /**
     * 转换反馈实体为响应DTO
     */
    private MobileDTO.FeedbackResponse convertToFeedbackResponse(UserFeedback feedback) {
        List<String> screenshots = new ArrayList<>();
        if (feedback.getScreenshots() != null && !feedback.getScreenshots().trim().isEmpty()) {
            try {
                screenshots = objectMapper.readValue(
                        feedback.getScreenshots(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            } catch (Exception e) {
                log.error("解析截图JSON失败: {}", feedback.getScreenshots(), e);
            }
        }

        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

        return MobileDTO.FeedbackResponse.builder()
                .feedbackId(String.valueOf(feedback.getId()))
                .type(feedback.getType())
                .title(feedback.getTitle())
                .content(feedback.getContent())
                .contact(feedback.getContact())
                .status(feedback.getStatus())
                .createdAt(feedback.getCreatedAt() != null ?
                        feedback.getCreatedAt().format(formatter) : null)
                .resolvedAt(feedback.getResolvedAt() != null ?
                        feedback.getResolvedAt().format(formatter) : null)
                .screenshots(screenshots)
                .build();
    }
}
