package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.OpportunityStage;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 商机阶段变更审计 history 表 — Sprint 7 wave 2 Track T4 (2026-05-20).
 *
 * <p>每次 transitionStage 调用都会写一条 history 记录, 用于:
 * <ul>
 *   <li>商机生命周期审计 (谁在何时把哪个商机从 X 转到 Y, 原因)</li>
 *   <li>funnel 分析 (e.g. DEMO → CLOSED_LOST 平均耗时)</li>
 *   <li>销售考核 (后向回退次数 / forward 推进效率)</li>
 * </ul>
 *
 * <p>fromStage 可为 null (初次创建时无 from), toStage 必填.
 * reason 非必填 (backward 转换强制), 但 service 层会强制 require for backward / closed-to-active.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"opportunity"})
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "opportunity_stage_history",
        indexes = {
                @Index(name = "idx_osh_opp_changed", columnList = "opportunity_id, changed_at"),
                @Index(name = "idx_osh_factory", columnList = "factory_id")
        })
@SQLDelete(sql = "UPDATE opportunity_stage_history SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class OpportunityStageHistory extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** factory RLS scope. */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** FK to sales_opportunities.id. */
    @Column(name = "opportunity_id", nullable = false, length = 36)
    private String opportunityId;

    /** 来源阶段 (nullable for first creation). */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 20)
    private OpportunityStage fromStage;

    /** 目标阶段 (必填). */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", nullable = false, length = 20)
    private OpportunityStage toStage;

    /**
     * 变更原因 (backward 转换强制非空, forward 可空, terminal-to-active 强制非空).
     * R3 防呆: dropdown + textarea (业务员选标准原因 + 补充).
     */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 变更人 (业务员 User.id). */
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    /** 变更时间 (= request 收到时间, 不是 createdAt). */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SalesOpportunity opportunity;
}
