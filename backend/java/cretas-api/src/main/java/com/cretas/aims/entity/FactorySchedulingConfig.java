package com.cretas.aims.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 工厂调度配置实体
 * 存储每个工厂的个性化调度参数
 *
 * 动态配置系统设计:
 * 1. 每个工厂可以有独立的参数配置
 * 2. 支持临时工和正式工的不同处理策略
 * 3. 参数可以基于反馈自动调整
 * 4. 支持SKU/产品复杂度的动态适配
 *
 * Canvas P3 batch 2 (2026-05-22): 增加 @Version 乐观锁 + soft-delete (deletedAt + @Where)
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "factory_scheduling_config")
@Where(clause = "deleted_at IS NULL")
public class FactorySchedulingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, unique = true)
    private String factoryId;

    // ==================== 基础配置 ====================

    /**
     * 是否启用动态配置
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * 是否启用多样性调整
     */
    @Column(name = "diversity_enabled")
    private Boolean diversityEnabled = true;

    // ==================== 权重参数 ====================

    /**
     * LinUCB分数权重 (0.4-0.8)
     */
    @Column(name = "linucb_weight")
    private Double linucbWeight = 0.60;

    /**
     * 公平性加分权重 (0.1-0.3)
     */
    @Column(name = "fairness_weight")
    private Double fairnessWeight = 0.15;

    /**
     * 技能维护加分权重 (0.1-0.3)
     */
    @Column(name = "skill_maintenance_weight")
    private Double skillMaintenanceWeight = 0.15;

    /**
     * 重复惩罚权重 (0.05-0.2)
     */
    @Column(name = "repetition_weight")
    private Double repetitionWeight = 0.10;

    // ==================== 时间参数 ====================

    /**
     * 技能遗忘判定天数 (默认30)
     */
    @Column(name = "skill_decay_days")
    private Integer skillDecayDays = 30;

    /**
     * 公平性计算周期天数 (默认14)
     */
    @Column(name = "fairness_period_days")
    private Integer fairnessPeriodDays = 14;

    /**
     * 重复判定天数 (默认3)
     */
    @Column(name = "repetition_days")
    private Integer repetitionDays = 3;

    /**
     * 最大同工序连续天数 (默认5)
     */
    @Column(name = "max_consecutive_days")
    private Integer maxConsecutiveDays = 5;

    // ==================== 临时工配置 ====================

    /**
     * 临时工LinUCB权重调整因子 (临时工能力评估不稳定)
     * 临时工LinUCB权重 = linucbWeight * tempWorkerLinucbFactor
     */
    @Column(name = "temp_worker_linucb_factor")
    private Double tempWorkerLinucbFactor = 0.7;

    /**
     * 临时工公平性权重调整因子 (临时工需要更多机会)
     */
    @Column(name = "temp_worker_fairness_factor")
    private Double tempWorkerFairnessFactor = 1.5;

    /**
     * 临时工技能遗忘天数 (临时工技能遗忘更快)
     */
    @Column(name = "temp_worker_skill_decay_days")
    private Integer tempWorkerSkillDecayDays = 14;

    /**
     * 临时工判定天数 (入职多少天内算临时工/新人)
     */
    @Column(name = "temp_worker_threshold_days")
    private Integer tempWorkerThresholdDays = 30;

    /**
     * 临时工最低分配数保证 (每周至少分配N次任务)
     */
    @Column(name = "temp_worker_min_assignments")
    private Integer tempWorkerMinAssignments = 3;

    // ==================== SKU复杂度配置 ====================

    /**
     * SKU复杂度权重 (复杂产品需要更高技能工人)
     */
    @Column(name = "sku_complexity_weight")
    private Double skuComplexityWeight = 0.15;

    /**
     * 高复杂度SKU技能阈值 (技能等级需达到此值才能处理)
     */
    @Column(name = "high_complexity_skill_threshold")
    private Integer highComplexitySkillThreshold = 3;

    /**
     * 低复杂度SKU适合新人学习 (设为true时低复杂度SKU优先分配给新人)
     */
    @Column(name = "low_complexity_for_training")
    private Boolean lowComplexityForTraining = true;

    // ==================== 自适应学习配置 ====================

    /**
     * 是否启用自适应学习 (根据反馈动态调整权重)
     */
    @Column(name = "adaptive_learning_enabled")
    private Boolean adaptiveLearningEnabled = true;

    /**
     * 学习率 (每次调整的幅度)
     */
    @Column(name = "learning_rate")
    private Double learningRate = 0.05;

    /**
     * 最小样本数 (收集足够样本后才开始调整)
     */
    @Column(name = "min_samples_for_adaptation")
    private Integer minSamplesForAdaptation = 50;

    /**
     * 效率提升目标 (低于此值触发权重调整)
     */
    @Column(name = "efficiency_target")
    private Double efficiencyTarget = 0.85;

    /**
     * 多样性目标 (低于此值触发权重调整)
     */
    @Column(name = "diversity_target")
    private Double diversityTarget = 0.70;

    // ==================== 异常检测配置 ====================

    /**
     * 是否启用异常检测
     */
    @Column(name = "anomaly_detection_enabled")
    private Boolean anomalyDetectionEnabled = true;

    /**
     * 效率异常阈值 (低于此值视为异常)
     */
    @Column(name = "efficiency_anomaly_threshold")
    private Double efficiencyAnomalyThreshold = 0.50;

    /**
     * 连续异常次数触发校准
     */
    @Column(name = "anomaly_count_for_calibration")
    private Integer anomalyCountForCalibration = 3;

    // ==================== APS 排产策略权重 ====================

    /**
     * 最早交期优先权重 (影响准时完成率)
     */
    @Column(name = "earliest_deadline_weight")
    private Double earliestDeadlineWeight = 0.20;

    /**
     * 最小换型时间权重 (影响换型效率)
     */
    @Column(name = "min_changeover_weight")
    private Double minChangeoverWeight = 0.15;

    /**
     * 产能匹配权重 (影响负载均衡)
     */
    @Column(name = "capacity_match_weight")
    private Double capacityMatchWeight = 0.15;

    /**
     * 最短工序优先权重 (影响吞吐量)
     */
    @Column(name = "shortest_process_weight")
    private Double shortestProcessWeight = 0.15;

    /**
     * 物料齐套优先权重 (影响物料等待时间)
     */
    @Column(name = "material_ready_weight")
    private Double materialReadyWeight = 0.15;

    /**
     * 紧急订单优先权重 (影响紧急订单准时率)
     */
    @Column(name = "urgency_first_weight")
    private Double urgencyFirstWeight = 0.20;

    // ==================== 审计字段 ====================

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 最后调整时间 (自适应学习最后一次调整参数的时间)
     */
    @Column(name = "last_adaptation_at")
    private LocalDateTime lastAdaptationAt;

    /**
     * 调整次数统计
     */
    @Column(name = "adaptation_count")
    private Integer adaptationCount = 0;

    /**
     * AUD-4 P1 JPA 乐观锁 version (Canvas P3-batch2 PR #198 + Canvas Phase B PR #201 共用).
     * 由 Flyway 默认 0 NOT NULL. Hibernate 自动 increment on save() — Lost Update prevention.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * 软删除时间戳 (Canvas P3 batch 2 加入).
     * NULL = 未删除. 配合 partial-unique index uk_fsc_factory_active.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 创建默认配置
     */
    public static FactorySchedulingConfig createDefault(String factoryId) {
        FactorySchedulingConfig config = new FactorySchedulingConfig();
        config.setFactoryId(factoryId);
        return config;
    }

    // ==================== APS 策略权重 Map 操作方法 ====================

    /**
     * 获取APS排产策略权重Map
     *
     * @return 策略名称 -> 权重值 的Map
     */
    public java.util.Map<String, Double> getStrategyWeightsMap() {
        java.util.Map<String, Double> weights = new java.util.HashMap<>();
        weights.put("earliest_deadline", earliestDeadlineWeight != null ? earliestDeadlineWeight : 0.20);
        weights.put("min_changeover", minChangeoverWeight != null ? minChangeoverWeight : 0.15);
        weights.put("capacity_match", capacityMatchWeight != null ? capacityMatchWeight : 0.15);
        weights.put("shortest_process", shortestProcessWeight != null ? shortestProcessWeight : 0.15);
        weights.put("material_ready", materialReadyWeight != null ? materialReadyWeight : 0.15);
        weights.put("urgency_first", urgencyFirstWeight != null ? urgencyFirstWeight : 0.20);
        return weights;
    }

    /**
     * 从Map设置APS排产策略权重
     *
     * @param weights 策略名称 -> 权重值 的Map
     */
    public void setStrategyWeightsFromMap(java.util.Map<String, Double> weights) {
        if (weights == null) {
            return;
        }
        if (weights.containsKey("earliest_deadline")) {
            this.earliestDeadlineWeight = weights.get("earliest_deadline");
        }
        if (weights.containsKey("min_changeover")) {
            this.minChangeoverWeight = weights.get("min_changeover");
        }
        if (weights.containsKey("capacity_match")) {
            this.capacityMatchWeight = weights.get("capacity_match");
        }
        if (weights.containsKey("shortest_process")) {
            this.shortestProcessWeight = weights.get("shortest_process");
        }
        if (weights.containsKey("material_ready")) {
            this.materialReadyWeight = weights.get("material_ready");
        }
        if (weights.containsKey("urgency_first")) {
            this.urgencyFirstWeight = weights.get("urgency_first");
        }
    }

    /**
     * 获取默认APS策略权重Map
     *
     * @return 默认权重Map
     */
    public static java.util.Map<String, Double> getDefaultStrategyWeights() {
        java.util.Map<String, Double> weights = new java.util.HashMap<>();
        weights.put("earliest_deadline", 0.20);
        weights.put("min_changeover", 0.15);
        weights.put("capacity_match", 0.15);
        weights.put("shortest_process", 0.15);
        weights.put("material_ready", 0.15);
        weights.put("urgency_first", 0.20);
        return weights;
    }
}
