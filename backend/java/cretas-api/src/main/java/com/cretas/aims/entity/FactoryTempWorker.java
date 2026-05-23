package com.cretas.aims.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 临时工记录实体
 * 追踪临时工状态和绩效
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "factory_temp_worker")
public class FactoryTempWorker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * 是否为临时工
     */
    @Column(name = "is_temp_worker")
    private Boolean isTempWorker = true;

    /**
     * 入职日期
     */
    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    /**
     * 预计结束日期 (临时工)
     */
    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    /**
     * 是否已转正
     */
    @Column(name = "converted_to_permanent")
    private Boolean convertedToPermanent = false;

    /**
     * 转正日期
     */
    @Column(name = "conversion_date")
    private LocalDate conversionDate;

    /**
     * 初始技能等级
     */
    @Column(name = "initial_skill_level")
    private Integer initialSkillLevel = 1;

    /**
     * 当前技能等级
     */
    @Column(name = "current_skill_level")
    private Integer currentSkillLevel = 1;

    /**
     * 技能成长率 (每周技能提升速度)
     */
    @Column(name = "skill_growth_rate")
    private Double skillGrowthRate = 0.0;

    /**
     * 总分配次数
     */
    @Column(name = "total_assignments")
    private Integer totalAssignments = 0;

    /**
     * 平均效率
     */
    @Column(name = "avg_efficiency")
    private Double avgEfficiency = 0.0;

    /**
     * 可靠性评分 (出勤率、按时完成率等)
     */
    @Column(name = "reliability_score")
    private Double reliabilityScore = 0.5;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub (2026-05-22).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

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
     * 判断工人当前是否仍为临时工
     */
    public boolean isCurrentlyTempWorker() {
        if (convertedToPermanent) {
            return false;
        }
        if (expectedEndDate != null && LocalDate.now().isAfter(expectedEndDate)) {
            return false; // 已过期
        }
        return isTempWorker;
    }

    /**
     * 获取在职天数
     */
    public long getDaysEmployed() {
        return java.time.temporal.ChronoUnit.DAYS.between(hireDate, LocalDate.now());
    }
}
