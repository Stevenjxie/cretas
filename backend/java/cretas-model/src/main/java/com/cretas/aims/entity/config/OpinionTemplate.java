package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Where;

/**
 * 审批意见快捷模板 (Sprint 4 Wave 2 Chat J — C-OPINION-1 foundation).
 *
 * <p>用于审批 dialog 提供常用语预设 (e.g. "同意" / "请补充材料" / "金额过高需总监审")。
 * 减少审批人手动输入,统一意见措辞便于后续 AI 分析。
 *
 * <p>{@code decisionType} 与 {@link ApprovalChainConfig.DecisionType} 同枚举,但此处用
 * String 储存 (允许种子 / 配置数据不强制 enum 校验)。
 *
 * <p>{@code factoryId} = NULL 表示系统预设 (跨工厂可见);非 NULL 表示工厂自定义。
 *
 * <p><b>Status</b>: Foundation (entity + repo only). Full service / controller / Vue dialog
 * 集成 follow-up chat ship.
 *
 * @since 2026-05-24
 */
@Entity
@Table(name = "opinion_templates")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OpinionTemplate extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /** NULL = 系统预设; 非 NULL = 工厂自定义. */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /** 与 {@link ApprovalChainConfig.DecisionType} 同枚举值的字符串表示. */
    @Column(name = "decision_type", nullable = false, length = 50)
    private String decisionType;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
