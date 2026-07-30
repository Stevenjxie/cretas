package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 方言/口语映射实体
 *
 * 存储方言/口语表达到标准表达的映射关系，
 * 支持自学习和工厂级别的定制化映射。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-22
 */
@Entity
@Table(name = "dialect_mappings", indexes = {
        @Index(name = "idx_dialect_expr", columnList = "dialect_expr"),
        @Index(name = "idx_factory_id", columnList = "factory_id"),
        @Index(name = "idx_confidence", columnList = "confidence")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class DialectMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 方言/口语表达
     */
    @Column(name = "dialect_expr", nullable = false, length = 100)
    private String dialectExpr;

    /**
     * 标准表达
     */
    @Column(name = "standard_expr", nullable = false, length = 100)
    private String standardExpr;

    /**
     * 工厂ID（可为空，表示全局映射）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 映射置信度 (0.0 - 1.0)
     * 预置映射为 1.0，学习的映射根据使用反馈调整
     */
    @Column(name = "confidence", nullable = false)
    private Double confidence;

    /**
     * 使用次数（用于统计和置信度调整）
     */
    @Column(name = "use_count", nullable = false)
    private Integer useCount;

    /**
     * 成功次数（用户接受的次数）
     */
    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    /**
     * 映射类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_type", nullable = false, length = 20)
    private MappingType mappingType;

    /**
     * 来源（预置/学习/用户反馈）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private MappingSource source;

    /**
     * 是否启用
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /**
     * 映射类型枚举
     */
    public enum MappingType {
        /** 时间表达 */
        TIME,
        /** 动词口语化 */
        VERB,
        /** 名词口语化 */
        NOUN,
        /** 疑问词 */
        QUESTION,
        /** 语气词（需删除） */
        FILLER,
        /** 方言 */
        DIALECT,
        /** 其他 */
        OTHER
    }

    /**
     * 映射来源枚举
     */
    public enum MappingSource {
        /** 预置（系统内置） */
        PRESET,
        /** 自学习 */
        LEARNED,
        /** 用户反馈 */
        USER_FEEDBACK,
        /** 管理员添加 */
        ADMIN
    }

    /**
     * 增加使用次数
     */
    public void incrementUseCount() {
        this.useCount = (this.useCount == null ? 0 : this.useCount) + 1;
    }

    /**
     * 增加成功次数并调整置信度
     */
    public void recordSuccess() {
        this.successCount = (this.successCount == null ? 0 : this.successCount) + 1;
        updateConfidence();
    }

    /**
     * 根据使用统计更新置信度
     */
    private void updateConfidence() {
        if (this.useCount != null && this.useCount > 0) {
            // 预置映射最低置信度为 0.8
            double calculatedConfidence = (double) this.successCount / this.useCount;
            if (this.source == MappingSource.PRESET) {
                this.confidence = Math.max(0.8, calculatedConfidence);
            } else {
                this.confidence = calculatedConfidence;
            }
        }
    }
}
