package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 工厂设置实体类
 * 管理工厂的各种配置和设置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "factory_settings",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id"})
       }
)
@Where(clause = "deleted_at IS NULL")
public class FactorySettings extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;
    /**
     * 工厂ID（唯一）
     */
    @Column(name = "factory_id", nullable = false, unique = true, length = 50)
    private String factoryId;

    /**
     * 工厂名称
     */
    @Column(name = "factory_name")
    private String factoryName;

    @Column(name = "factory_address")
    private String factoryAddress;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "working_hours")
    private int workingHours;

    // ==================== AI设置 ====================
    /**
     * AI设置JSON
     * 包含: enabled, tone, goal, detailLevel, industryStandards, customPrompt
     */
    @Column(name = "ai_settings", columnDefinition = "TEXT")
    private String aiSettings;

    /**
     * AI每周配额（只读，由平台管理员设置）
     */
    @Column(name = "ai_weekly_quota")
    @Builder.Default
    private Integer aiWeeklyQuota = 20;
    // ==================== 用户注册设置 ====================
    /**
     * 是否允许自注册
     */
    @Column(name = "allow_self_registration")
    @Builder.Default
    private Boolean allowSelfRegistration = false;

    /**
     * 是否需要管理员审批
     */
    @Column(name = "require_admin_approval")
    @Builder.Default
    private Boolean requireAdminApproval = true;

    /**
     * 默认用户角色
     */
    @Column(name = "default_user_role", length = 50)
    @Builder.Default
    private String defaultUserRole = "viewer";

    // ==================== 通知设置 ====================
    /**
     * 通知设置JSON
     * 包含: email, sms, push, wechat等通知渠道配置
     */
    @Column(name = "notification_settings", columnDefinition = "TEXT")
    private String notificationSettings;

    // ==================== 系统设置 ====================
    /**
     * 工作时间设置JSON
     * 包含: startTime, endTime, workDays, holidays
     */
    @Column(name = "work_time_settings", columnDefinition = "TEXT")
    private String workTimeSettings;

    /**
     * 生产设置JSON
     * 包含: defaultBatchSize, qualityCheckFrequency, autoApprovalThreshold
     */
    @Column(name = "production_settings", columnDefinition = "TEXT")
    private String productionSettings;

    /**
     * 库存设置JSON
     * 包含: minStockAlert, maxStockLimit, autoReorderPoint
     */
    @Column(name = "inventory_settings", columnDefinition = "TEXT")
    private String inventorySettings;

    /**
     * 数据保留设置JSON
     * 包含: logRetentionDays, dataArchiveDays, backupFrequency
     */
    @Column(name = "data_retention_settings", columnDefinition = "TEXT")
    private String dataRetentionSettings;

    // ==================== 显示设置 ====================
    /**
     * 语言设置
     */
    @Column(name = "language", length = 10)
    @Builder.Default
    private String language = "zh-CN";

    /**
     * 时区设置
     */
    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "Asia/Shanghai";

    /**
     * 日期格式
     */
    @Column(name = "date_format", length = 20)
    @Builder.Default
    private String dateFormat = "yyyy-MM-dd";

    /**
     * 货币符号
     */
    @Column(name = "currency", length = 10)
    @Builder.Default
    private String currency = "CNY";

    // ==================== 功能开关 ====================
    /**
     * 是否启用QR码功能
     */
    @Column(name = "enable_qr_code")
    @Builder.Default
    private Boolean enableQrCode = true;

    /**
     * 是否启用批次管理
     */
    @Column(name = "enable_batch_management")
    @Builder.Default
    private Boolean enableBatchManagement = true;

    /**
     * 是否启用质量检测
     */
    @Column(name = "enable_quality_check")
    @Builder.Default
    private Boolean enableQualityCheck = true;

    /**
     * 是否启用成本核算
     */
    @Column(name = "enable_cost_calculation")
    @Builder.Default
    private Boolean enableCostCalculation = true;

    /**
     * 是否启用设备管理
     */
    @Column(name = "enable_equipment_management")
    @Builder.Default
    private Boolean enableEquipmentManagement = true;

    /**
     * 是否启用考勤管理
     */
    @Column(name = "enable_attendance")
    @Builder.Default
    private Boolean enableAttendance = true;

    /**
     * 工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全红线, V20261018_02).
     *
     * <p>createProductionPlan 对 skipProcessReporting=null 的新建计划取此工厂级默认值:
     * <ul>
     *   <li>true  → 新建计划默认两点报工 (领料入+产出出)。仅六扇门类要两点的工厂 (F006) 置 true。</li>
     *   <li>false → 新建计划默认逐道报工 (保留溯源/成本/出成率/自学习/人效)。其他工厂默认。</li>
     * </ul>
     * 列 DEFAULT FALSE → 未显式 seed 的工厂 (现有 + 未来) 默认逐道, 零回归。
     */
    @Column(name = "skip_process_reporting_default")
    @Builder.Default
    private Boolean skipProcessReportingDefault = false;

    /**
     * 工厂级"报工前必须领料确认"开关 (② Part B 生产领料单 Gate, V20261027_32).
     *
     * <p>opt-in, 默认 false (向后兼容: 现有工厂如 LIUSHANMEN 料流仍是报工直接从原料仓消耗, 零回归):
     * <ul>
     *   <li>false → 报工照旧, 从物料所在原料仓/物流仓消耗 (② Part A 宽松 ensureRawMaterialWarehouse)。</li>
     *   <li>true  → 报工前该生产计划必须有仓管已确认(拣货+调拨, 状态 TRANSFERRED/ISSUED/IN_USE)的领料单,
     *       且覆盖被消耗物料, 否则 BLOCKING + 指引"请先在该计划生成领料单并由仓管确认领料到生产仓"。</li>
     * </ul>
     * 列 DEFAULT FALSE → 未显式配置的工厂 (现有 + 未来) 默认关闭。需人工在工厂配置页 per-factory 开启,
     * 用于强制客户张权(F006 仓管场景)诉求的"仓管没确认领料，生产不能报工"料流。
     */
    @Column(name = "require_requisition_before_report")
    @Builder.Default
    private Boolean requireRequisitionBeforeReport = false;

    // ==================== 审计字段 ====================
    /**
     * 创建人ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 更新人ID
     */
    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * 最后修改时间
     */
    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    /**
     * AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub (2026-05-22).
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    // 关联关系
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
    @PreUpdate
    public void preUpdate() {
        this.lastModifiedAt = LocalDateTime.now();
    }
}
