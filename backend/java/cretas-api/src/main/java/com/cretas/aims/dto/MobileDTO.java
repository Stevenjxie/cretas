package com.cretas.aims.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
/**
 * 移动端数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class MobileDTO {
    /**
     * 移动端登录请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        private String factoryId; // 可选，如不提供则系统自动推断
        private DeviceInfo deviceInfo;
    }

    /**
     * 设备信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {
        @NotBlank(message = "设备ID不能为空")
        private String deviceId;
        private String deviceType; // iOS, Android, HarmonyOS
        private String osVersion;
        private String appVersion;
        private String manufacturer;
        private String model;
        private String networkType;
        private String carrier;
        private LocationInfo location;
        private Map<String, Object> extra;
    }

    /**
     * 位置信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationInfo {
        private Double latitude;
        private Double longitude;
        private String address;
        private String city;
        private String province;
        private String country;
    }

    /**
     * 登录响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private Long userId;
        private String username;
        private String factoryId;
        private String factoryName;
        private String factoryType;
        private String businessDomain;
        private String role;
        private List<String> permissions;
        private String token;
        private String refreshToken;
        private Long expiresIn;
        private UserProfile profile;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime lastLoginTime;

        /**
         * accessToken 别名（兼容前端）
         * 前端使用 accessToken 字段，后端使用 token 字段
         */
        @com.fasterxml.jackson.annotation.JsonProperty("accessToken")
        public String getAccessToken() {
            return token;
        }
    }

    /**
     * 用户简要信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile {
        private String name;
        private String avatar;
        private String department;
        private String position;
        private String phoneNumber;
        private String email;
    }

    /**
     * 刷新令牌请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshTokenRequest {
        private String refreshToken;
    }

    /**
     * 文件上传请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadRequest {
        @NotNull(message = "文件不能为空")
        private List<FileData> files;
        private String category;
        private Map<String, Object> metadata;
    }

    /**
     * 文件数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileData {
        private String filename;
        private String contentType;
        private String base64Data;
        private Long size;
    }

    /**
     * 文件上传响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResponse {
        private List<UploadedFile> files;
        private Integer successCount;
        private Integer failedCount;
    }

    /**
     * 上传的文件信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadedFile {
        private String id;
        private String url;
        private String thumbnailUrl;
        private String originalName;
        private String contentType;
        private Long size;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime uploadTime;
    }

    /**
     * 激活请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivationRequest {
        @NotBlank(message = "激活码不能为空")
        private String activationCode;
        @NotNull(message = "设备信息不能为空")
        private DeviceInfo deviceInfo;
    }

    /**
     * 激活响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivationResponse {
        private Boolean success;
        private String message;
        private String factoryId;
        private String factoryName;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime activatedAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime validUntil;
        private List<String> features;
        private Map<String, Object> configuration;
    }

    /**
     * 数据同步请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncRequest {
        private String lastSyncTime;
        private List<String> dataTypes;
        private Map<String, Object> localChanges;
    }

    /**
     * 数据同步响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncResponse {
        private Map<String, List<Object>> serverData;
        private Map<String, Integer> conflictCount;
        private String nextSyncToken;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime syncTime;
    }

    /**
     * 移动端仪表盘数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardData {
        // 今日统计
        private TodayStats todayStats;
        // 待办事项
        private List<TodoItem> todoItems;
        // 最近活动
        private List<ActivityLog> recentActivities;
        // 预警信息
        private List<Alert> alerts;
        // 快捷操作
        private List<QuickAction> quickActions;
    }

    /**
     * 今日统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodayStats {
        // ========== 现有字段 ==========
        private Integer productionCount;
        private Integer qualityCheckCount;
        private Integer materialReceived;
        private Integer ordersCompleted;
        private Double productionEfficiency;
        private Integer activeWorkers;

        // ========== 新增字段 (2025-11-20) ==========
        private Double todayOutputKg;        // 今日产量（千克）
        private Integer totalBatches;        // 总批次数
        private Integer totalWorkers;        // 总工人数
        private Integer activeEquipment;     // 活跃设备数
        private Integer totalEquipment;      // 总设备数
    }

    /**
     * 待办事项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoItem {
        private String id;
        private String title;
        private String description;
        private String priority; // HIGH, MEDIUM, LOW
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime dueTime;
    }

    /**
     * 活动日志
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityLog {
        private String id;
        private String type;
        private String title;
        private String description;
        private String operator;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime time;
    }

    /**
     * 预警信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alert {
        private String id;
        private String type; // WARNING, ERROR, INFO
        private String title;
        private String message;
        private String severity; // HIGH, MEDIUM, LOW
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime time;
    }

    /**
     * 快捷操作
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuickAction {
        private String id;
        private String label;
        private String icon;
        private String title;
        private String action;
        private String color;
        private Integer orderIndex;
    }

    /**
     * 推送通知注册
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushRegistration {
        @NotBlank(message = "设备令牌不能为空")
        private String deviceToken;
        @NotBlank(message = "平台不能为空")
        private String platform; // iOS, Android, HarmonyOS
        private List<String> topics;
        private Map<String, Object> preferences;
    }

    /**
     * 版本检查响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionCheckResponse {
        private String currentVersion;
        private String latestVersion;
        private Boolean updateRequired;
        private Boolean updateAvailable;
        private String downloadUrl;
        private String releaseNotes;
        private Long fileSize;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime releaseDate;
    }

    /**
     * 离线数据包
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OfflineDataPackage {
        private String packageId;
        private String version;
        private Map<String, Object> baseData;
        private Map<String, Object> configData;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime generatedAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expiresAt;
    }

    // ==================== 注册相关 ====================
    /**
     * 移动端注册第一阶段请求（验证手机号）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterPhaseOneRequest {
        @NotBlank(message = "手机号不能为空")
        private String phoneNumber;
        private String factoryId; // 可选，如不提供则通过手机号从白名单推断
    }

    /**
     * 移动端注册第一阶段响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterPhaseOneResponse {
        private Boolean success;
        private String tempToken;
        private Long expiresAt;
        private String phoneNumber;
        private String factoryId;
        private Boolean isNewUser;
        private String message;
    }

    /**
     * 移动端注册第二阶段请求（创建账户）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterPhaseTwoRequest {
        @NotBlank(message = "临时令牌不能为空")
        private String tempToken;
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "真实姓名不能为空")
        private String realName;
        private String factoryId; // 可选，从第一阶段响应中获取或自动推断
        private String position;
        private String email;
        private DeviceInfo deviceInfo;
    }

    /**
     * 移动端注册第二阶段响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterPhaseTwoResponse {
        private Boolean success;
        private Long userId;
        private String username;
        private String role;
        private String token;
        private UserProfile profile;
        private String message;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime registeredAt;
    }

    /**
     * AI成本分析请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AICostAnalysisRequest {
        @NotBlank(message = "批次ID不能为空")
        private String batchId;

        /**
         * 用户问题（follow-up时必填）
         */
        private String question;

        /**
         * Python Session ID（多轮对话时传递）
         */
        private String session_id;

        /**
         * 报告类型：default(默认批次分析), followup(追问), historical(历史综合报告)
         */
        private String reportType;

        /**
         * 历史报告的时间范围（仅historical类型时使用）
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startDate;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endDate;

        /**
         * 是否启用思考模式（默认true）
         * 思考模式下，AI会先进行深度推理再给出答案
         */
        @Builder.Default
        private Boolean enableThinking = true;

        /**
         * 思考预算（10-100，默认50）
         * 数值越大，思考越深入
         */
        @Builder.Default
        private Integer thinkingBudget = 50;
    }

    /**
     * AI成本分析响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AICostAnalysisResponse {
        /**
         * 是否成功
         */
        private Boolean success;

        /**
         * 报告ID（用于查询历史报告）
         */
        private Long reportId;

        /**
         * AI分析结果（Markdown格式）
         */
        private String analysis;

        /**
         * Python Session ID（用于后续follow-up）
         */
        @JsonProperty("session_id")  // JSON输出时使用下划线格式
        private String sessionId;

        /**
         * 对话消息计数
         */
        private Integer messageCount;

        /**
         * 配额信息
         */
        private AIQuotaInfo quota;

        /**
         * 是否命中缓存
         */
        private Boolean cacheHit;

        /**
         * 处理时间（毫秒）
         */
        private Long processingTimeMs;

        /**
         * 配额消耗量
         */
        private Integer quotaConsumed;

        /**
         * 错误信息（失败时返回）
         */
        private String errorMessage;

        /**
         * 报告生成时间
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime generatedAt;

        /**
         * 报告有效期至
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expiresAt;
    }

    /**
     * AI配额信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AIQuotaInfo {
        /**
         * 配额总数
         */
        private Integer total;

        /**
         * 已使用配额
         */
        private Integer used;

        /**
         * 剩余配额
         */
        private Integer remaining;

        /**
         * 使用率（百分比）
         */
        private Double usageRate;

        /**
         * 配额重置时间（下周一）
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime resetDate;

        /**
         * 是否已超额
         */
        private Boolean exceeded;
    }

    /**
     * AI报告列表请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AIReportListRequest {
        /**
         * 报告类型：batch, weekly, monthly, historical
         */
        private String reportType;

        /**
         * 时间范围开始
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startDate;

        /**
         * 时间范围结束
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endDate;
    }

    /**
     * AI报告列表响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AIReportListResponse {
        private List<AIReportSummary> reports;
        private Integer total;
    }

    /**
     * AI报告摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AIReportSummary {
        private Long id;
        private String batchId;
        private String reportType;
        private String summaryText; // 摘要（前200字）

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime periodStart;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime periodEnd;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createdAt;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expiresAt;

        private Boolean isAutoGenerated;
    }

    // ==================== 忘记密码相关 ====================

    /**
     * 发送验证码请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendVerificationCodeRequest {
        @NotBlank(message = "手机号不能为空")
        private String phoneNumber;

        /**
         * 验证码类型: password_reset, phone_verify, login_verify
         */
        @NotBlank(message = "验证码类型不能为空")
        private String verificationType;
    }

    /**
     * 发送验证码响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendVerificationCodeResponse {
        private Boolean success;
        private String message;

        /**
         * 验证码有效期（秒）
         */
        private Integer expiresIn;

        /**
         * 下次可发送时间（秒后）
         */
        private Integer retryAfter;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime sentAt;
    }

    /**
     * 验证重置验证码请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResetCodeRequest {
        @NotBlank(message = "手机号不能为空")
        private String phoneNumber;

        @NotBlank(message = "验证码不能为空")
        private String verificationCode;
    }

    /**
     * 验证重置验证码响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResetCodeResponse {
        private Boolean success;
        private String message;

        /**
         * 重置令牌（用于后续密码重置）
         */
        private String resetToken;

        /**
         * 重置令牌有效期（秒）
         */
        private Integer expiresIn;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime verifiedAt;
    }

    /**
     * 忘记密码-重置密码请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForgotPasswordRequest {
        @NotBlank(message = "手机号不能为空")
        private String phoneNumber;

        @NotBlank(message = "重置令牌不能为空")
        private String resetToken;

        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }

    /**
     * 忘记密码-重置密码响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForgotPasswordResponse {
        private Boolean success;
        private String message;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime resetAt;
    }

    // ==================== 人员报表相关 ====================

    /**
     * 人员总览统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PersonnelStatistics {
        /**
         * 总人数
         */
        private Integer totalEmployees;

        /**
         * 在岗人数
         */
        private Integer totalPresent;

        /**
         * 缺勤人数
         */
        private Integer totalAbsent;

        /**
         * 平均出勤率
         */
        private Double avgAttendanceRate;

        /**
         * 活跃部门数
         */
        private Integer activeDepartments;

        /**
         * 总工时
         */
        private Double totalWorkHours;

        /**
         * 人均工时
         */
        private Double avgWorkHoursPerEmployee;

        /**
         * 合同即将到期人数 (30天内)
         */
        private Integer expiringContractsCount;
    }

    /**
     * 工时排行榜项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkHoursRankingItem {
        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 用户名
         */
        private String userName;

        /**
         * 部门ID
         */
        private String departmentId;

        /**
         * 部门名称
         */
        private String departmentName;

        /**
         * 总工时
         */
        private Double totalWorkHours;

        /**
         * 加班工时
         */
        private Double totalOvertimeHours;

        /**
         * 出勤天数
         */
        private Integer attendanceDays;

        /**
         * 出勤率
         */
        private Double attendanceRate;
    }

    /**
     * 加班员工项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OvertimeEmployeeItem {
        private Long userId;
        private String userName;
        private Double overtimeHours;
    }

    /**
     * 加班统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OvertimeStatistics {
        /**
         * 总加班工时
         */
        private Double totalOvertimeHours;

        /**
         * 有加班的员工总数
         */
        private Integer totalEmployeesWithOvertime;

        /**
         * 人均加班工时
         */
        private Double avgOvertimeHoursPerEmployee;

        /**
         * 加班最多的员工（TOP 10）
         */
        private List<OvertimeEmployeeItem> topOvertimeEmployees;
    }

    /**
     * 人员绩效项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceItem {
        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 用户名
         */
        private String userName;

        /**
         * 部门名称
         */
        private String departmentName;

        /**
         * 工时
         */
        private Double workHours;

        /**
         * 出勤率
         */
        private Double attendanceRate;

        /**
         * 质量分数（基于质检记录）
         */
        private Double qualityScore;

        /**
         * 效率分数（基于生产效率）
         */
        private Double efficiencyScore;

        /**
         * 综合分数
         */
        private Double overallScore;
    }

    // ==================== 成本对比相关 ====================

    /**
     * 批次成本数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchCostData {
        /**
         * 批次ID
         */
        private String batchId;

        /**
         * 批次编号
         */
        private String batchNumber;

        /**
         * 产品类型/名称
         */
        private String productType;

        /**
         * 总成本
         */
        private Double totalCost;

        /**
         * 人工成本
         */
        private Double laborCost;

        /**
         * 原料成本
         */
        private Double materialCost;

        /**
         * 设备成本
         */
        private Double equipmentCost;

        /**
         * 其他成本
         */
        private Double otherCost;

        /**
         * 生产数量
         */
        private Double quantity;

        /**
         * 单位成本
         */
        private Double unitCost;

        /**
         * 生产日期
         */
        private String date;
    }

    // ==================== 设备告警相关 ====================

    /**
     * 确认告警请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcknowledgeAlertRequest {
        private String notes;  // 确认备注（可选）
    }

    /**
     * 解决告警请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResolveAlertRequest {
        private String resolutionNotes;  // 解决方案备注（可选）
    }

    /**
     * 忽略告警请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IgnoreAlertRequest {
        private String reason;  // 忽略原因（可选）
    }

    /**
     * 告警响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertResponse {
        private Integer id;
        private String factoryId;
        private String equipmentId;  // 修改为String，与FactoryEquipment.id一致
        private String equipmentName;
        private String alertType;
        private String level;          // CRITICAL, WARNING, INFO
        private String status;         // ACTIVE, ACKNOWLEDGED, RESOLVED, IGNORED
        private String message;
        private String details;
        private String triggeredAt;    // ISO格式时间
        private String acknowledgedAt;
        private String acknowledgedBy;
        private String resolvedAt;
        private String resolvedBy;
        private String resolutionNotes;
        private String ignoredAt;      // 忽略时间
        private String ignoredBy;      // 忽略人
        private String ignoreReason;   // 忽略原因
    }

    // ========== 工厂设置 DTOs ==========

    /**
     * 工作时间配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkingHours {
        private String startTime;  // "08:00"
        private String endTime;    // "17:00"
    }

    /**
     * 工作时间设置（存储在work_time_settings JSON字段）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkTimeSettings {
        private WorkingHours workingHours;
        private String lunchBreakStart;       // "12:00"
        private String lunchBreakEnd;         // "13:00"
        private boolean[] workingDays;        // [周一, 周二, ...周日] - 7个布尔值
        private Integer lateThresholdMinutes;
        private Integer earlyLeaveThresholdMinutes;
        private Boolean enableOvertimeTracking;
        private Boolean enableGPSChecking;
    }

    /**
     * 工厂设置响应（组合Factory和FactorySettings数据）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FactorySettingsResponse {
        // 基本信息（来自Factory表）
        private String factoryName;
        private String factoryAddress;
        private String contactPhone;
        private String contactEmail;

        // 工作时间配置（来自FactorySettings.workTimeSettings JSON）
        private WorkingHours workingHours;
        private String lunchBreakStart;
        private String lunchBreakEnd;
        private boolean[] workingDays;

        // 考勤配置（来自FactorySettings.workTimeSettings JSON）
        private Integer lateThresholdMinutes;
        private Integer earlyLeaveThresholdMinutes;

        // 功能开关（来自FactorySettings表）
        private Boolean enableOvertimeTracking;
        private Boolean enableGPSChecking;
    }

    /**
     * 更新工厂设置请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateFactorySettingsRequest {
        // 基本信息（更新Factory表）
        private String factoryName;
        private String factoryAddress;
        private String contactPhone;
        private String contactEmail;

        // 工作时间配置（更新FactorySettings.workTimeSettings JSON）
        private WorkingHours workingHours;
        private String lunchBreakStart;
        private String lunchBreakEnd;
        private boolean[] workingDays;

        // 考勤配置（更新FactorySettings.workTimeSettings JSON）
        private Integer lateThresholdMinutes;
        private Integer earlyLeaveThresholdMinutes;

        // 功能开关（更新FactorySettings表）
        private Boolean enableOvertimeTracking;
        private Boolean enableGPSChecking;
    }

    // ========== 用户反馈 DTOs ==========

    /**
     * 用户反馈请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitFeedbackRequest {
        private String type;            // "bug" | "feature" | "other"
        private String title;           // 反馈标题（必填）
        private String content;         // 反馈内容（必填）
        private String contact;         // 联系方式（可选）
        private List<String> screenshots; // 截图URL列表（可选）
    }

    /**
     * 用户反馈响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackResponse {
        private String feedbackId;      // 反馈ID
        private String type;
        private String title;
        private String content;
        private String contact;
        private String status;          // "pending" | "processing" | "resolved"
        private String createdAt;       // ISO格式时间
        private String resolvedAt;      // ISO格式时间（可选）
        private List<String> screenshots;
    }
}
