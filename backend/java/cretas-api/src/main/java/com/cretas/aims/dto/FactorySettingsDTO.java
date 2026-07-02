package com.cretas.aims.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工厂设置DTO
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "工厂设置信息")
public class FactorySettingsDTO {

    @Schema(description = "设置ID", example = "1")
    private Integer id;

    @Schema(description = "工厂ID", example = "FAC001", required = true)
    @NotBlank(message = "工厂ID不能为空")
    private String factoryId;

    // ==================== AI设置 ====================

    @Schema(description = "AI设置")
    private AISettings aiSettings;

    @Schema(description = "AI每周配额", example = "20")
    private Integer aiWeeklyQuota;

    // ==================== 用户注册设置 ====================

    @Schema(description = "允许自注册", example = "false")
    private Boolean allowSelfRegistration;

    @Schema(description = "需要管理员审批", example = "true")
    private Boolean requireAdminApproval;

    @Schema(description = "默认用户角色", example = "viewer")
    private String defaultUserRole;

    // ==================== 通知设置 ====================

    @Schema(description = "通知设置")
    private NotificationSettings notificationSettings;

    // ==================== 系统设置 ====================

    @Schema(description = "工作时间设置")
    private WorkTimeSettings workTimeSettings;

    @Schema(description = "生产设置")
    private ProductionSettings productionSettings;

    @Schema(description = "库存设置")
    private InventorySettings inventorySettings;

    @Schema(description = "数据保留设置")
    private DataRetentionSettings dataRetentionSettings;

    // ==================== 显示设置 ====================

    @Schema(description = "语言", example = "zh-CN")
    @Pattern(regexp = "^(zh-CN|en-US)$", message = "语言格式不正确")
    private String language;

    @Schema(description = "时区", example = "Asia/Shanghai")
    private String timezone;

    @Schema(description = "日期格式", example = "yyyy-MM-dd")
    private String dateFormat;

    @Schema(description = "货币", example = "CNY")
    private String currency;

    // ==================== 功能开关 ====================

    @Schema(description = "启用QR码", example = "true")
    private Boolean enableQrCode;

    @Schema(description = "启用批次管理", example = "true")
    private Boolean enableBatchManagement;

    @Schema(description = "启用质量检测", example = "true")
    private Boolean enableQualityCheck;

    @Schema(description = "启用成本核算", example = "true")
    private Boolean enableCostCalculation;

    @Schema(description = "启用设备管理", example = "true")
    private Boolean enableEquipmentManagement;

    @Schema(description = "启用考勤管理", example = "true")
    private Boolean enableAttendance;

    @Schema(description = "新建计划默认两点报工(true=仅头尾报, false=逐道报工)", example = "false")
    private Boolean skipProcessReportingDefault;

    /**
     * Update payload presence marker. Distinguishes an explicit JSON null
     * (reset to the DB default false) from an omitted field (leave as-is).
     */
    @JsonIgnore
    private boolean skipProcessReportingDefaultSpecified;

    public void setSkipProcessReportingDefault(Boolean skipProcessReportingDefault) {
        this.skipProcessReportingDefault = skipProcessReportingDefault;
        this.skipProcessReportingDefaultSpecified = true;
    }

    @Schema(description = "报工前必须领料确认(② Part B Gate, opt-in, false=报工照旧)", example = "false")
    private Boolean requireRequisitionBeforeReport;

    /**
     * Update payload presence marker. Distinguishes an explicit JSON null
     * (reset to the DB default false) from an omitted field (leave as-is).
     */
    @JsonIgnore
    private boolean requireRequisitionBeforeReportSpecified;

    public void setRequireRequisitionBeforeReport(Boolean requireRequisitionBeforeReport) {
        this.requireRequisitionBeforeReport = requireRequisitionBeforeReport;
        this.requireRequisitionBeforeReportSpecified = true;
    }

    @Schema(description = "最后修改时间")
    private LocalDateTime lastModifiedAt;

    /**
     * 工厂名称
     */
    private String factoryName;
    private String factoryAddress;
    private String contactPhone;
    private String contactEmail;

    private int workingHours;

    private List<Boolean> workingDays;

    @Builder.Default
    private int lateThresholdMinutes = 10;
    @Builder.Default
    private int earlyLeaveThresholdMinutes = 10;

    /**
     * AI设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AISettings {
        @Schema(description = "是否启用", example = "true")
        private Boolean enabled;

        @Schema(description = "语气", example = "professional")
        @Pattern(regexp = "^(professional|friendly|concise)$", message = "语气类型不正确")
        private String tone;

        @Schema(description = "目标", example = "cost_optimization")
        @Pattern(regexp = "^(cost_optimization|efficiency|profit)$", message = "目标类型不正确")
        private String goal;

        @Schema(description = "详细级别", example = "standard")
        @Pattern(regexp = "^(brief|standard|detailed)$", message = "详细级别不正确")
        private String detailLevel;

        @Schema(description = "行业标准")
        private IndustryStandards industryStandards;

        @Schema(description = "自定义提示")
        private String customPrompt;
    }

    /**
     * 行业标准
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class IndustryStandards {
        @Schema(description = "人工成本百分比", example = "30")
        private Integer laborCostPercentage;

        @Schema(description = "设备利用率", example = "80")
        private Integer equipmentUtilization;

        @Schema(description = "利润率", example = "20")
        private Integer profitMargin;
    }

    /**
     * 通知设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NotificationSettings {
        @Schema(description = "启用邮件通知", example = "true")
        private Boolean emailEnabled;

        @Schema(description = "启用推送通知", example = "true")
        private Boolean pushEnabled;

        @Schema(description = "启用微信通知", example = "true")
        private Boolean wechatEnabled;
    }

    /**
     * 工作时间设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkTimeSettings {
        @Schema(description = "开始时间", example = "08:00")
        private String startTime;

        @Schema(description = "结束时间", example = "18:00")
        private String endTime;

        @Schema(description = "工作日", example = "[1,2,3,4,5]")
        private List<String> workDays;

        @Schema(description = "节假日")
        private String holidays;
    }

    /**
     * 生产设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProductionSettings {
        @Schema(description = "默认批次大小", example = "100")
        private Integer defaultBatchSize;

        @Schema(description = "质检频率", example = "10")
        private String qualityCheckFrequency;

        @Schema(description = "自动审批阈值", example = "95")
        private Integer autoApprovalThreshold;

        @Schema(description = "出成率报工超收容差 (0.30 = 30%), null 时默认 30%", example = "0.30")
        private java.math.BigDecimal yieldOverReceiptTolerance;

        @Schema(description = "辅料/投料多投对账预警阈值 (0.05 = 5%), null 时默认 5%", example = "0.05")
        private java.math.BigDecimal auxVarianceThreshold;
    }

    /**
     * 库存设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InventorySettings {
        @Schema(description = "最低库存预警", example = "100")
        private Integer minStockAlert;

        @Schema(description = "最高库存限制", example = "10000")
        private Integer maxStockLimit;

        @Schema(description = "自动补货点", example = "200")
        private Integer autoReorderPoint;
    }

    /**
     * 数据保留设置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DataRetentionSettings {
        @Schema(description = "日志保留天数", example = "90")
        private Integer logRetentionDays;

        @Schema(description = "数据归档天数", example = "365")
        private Integer dataArchiveDays;

        @Schema(description = "备份频率", example = "daily")
        @Pattern(regexp = "^(daily|weekly|monthly)$", message = "备份频率格式不正确")
        private String backupFrequency;
    }

    /**
     * 基础设置 (前端系统设置页面使用)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BasicSettings {
        @Schema(description = "工厂名称", example = "测试工厂")
        private String factoryName;

        @Schema(description = "时区", example = "Asia/Shanghai")
        private String timezone;

        @Schema(description = "语言", example = "zh-CN")
        private String language;

        @Schema(description = "日期格式", example = "YYYY-MM-DD")
        private String dateFormat;

        @Schema(description = "工作开始时间", example = "08:00")
        private String workStartTime;

        @Schema(description = "工作结束时间", example = "17:00")
        private String workEndTime;
    }

    /**
     * 通知设置 (前端格式)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WebNotificationSettings {
        @Schema(description = "邮件通知", example = "true")
        private Boolean emailNotification;

        @Schema(description = "短信通知", example = "false")
        private Boolean smsNotification;

        @Schema(description = "告警通知", example = "true")
        private Boolean alertNotification;

        @Schema(description = "维护提醒", example = "true")
        private Boolean maintenanceReminder;

        @Schema(description = "提前提醒天数", example = "3")
        private Integer reminderDays;
    }

    /**
     * 安全设置 (前端格式)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SecuritySettings {
        @Schema(description = "密码最小长度", example = "8")
        private Integer passwordMinLength;

        @Schema(description = "要求包含大写字母", example = "true")
        private Boolean passwordRequireUppercase;

        @Schema(description = "要求包含数字", example = "true")
        private Boolean passwordRequireNumber;

        @Schema(description = "会话超时时间(分钟)", example = "30")
        private Integer sessionTimeout;

        @Schema(description = "最大登录尝试次数", example = "5")
        private Integer maxLoginAttempts;
    }

    /**
     * 系统状态
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SystemStatus {
        @Schema(description = "系统版本", example = "1.0.0")
        private String version;

        @Schema(description = "上次备份时间", example = "2025-12-27 10:00:00")
        private String lastBackup;

        @Schema(description = "数据库大小", example = "128MB")
        private String databaseSize;

        @Schema(description = "服务器状态", example = "running")
        private String serverStatus;
    }

    /**
     * 前端设置页面响应格式
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WebSettingsResponse {
        @Schema(description = "基础设置")
        private BasicSettings basic;

        @Schema(description = "通知设置")
        private WebNotificationSettings notification;

        @Schema(description = "安全设置")
        private SecuritySettings security;
    }
}
