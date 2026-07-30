package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;

/**
 * 编码规则配置实体
 *
 * 用于配置各类业务单据的编号生成规则
 * 支持前缀、日期格式、序列号、重置周期等配置
 *
 * 示例规则:
 * - 原材料批次: MB-{FACTORY}-{YYYYMMDD}-{SEQ:4} → MB-F001-20251229-0001
 * - 加工批次: PB-{FACTORY}-{YYYYMMDD}-{SEQ:5} → PB-F001-20251229-00001
 * - 出货记录: SH-{FACTORY}-{YYYYMM}-{SEQ:6} → SH-F001-202512-000001
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "encoding_rules",
       indexes = {
           @Index(name = "idx_encoding_rules_factory", columnList = "factory_id"),
           @Index(name = "idx_encoding_rules_entity", columnList = "factory_id, entity_type")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_encoding_rules",
                            columnNames = {"factory_id", "entity_type"})
       })
@Where(clause = "deleted_at IS NULL")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EncodingRule extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     * 为 null 时表示系统默认规则
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 实体类型
     * 如: MATERIAL_BATCH, PROCESSING_BATCH, SHIPMENT, QUALITY_INSPECTION
     */
    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    /**
     * 规则名称
     */
    @Column(name = "rule_name", length = 100, nullable = false)
    private String ruleName;

    /**
     * 规则描述
     */
    @Column(name = "rule_description", length = 500)
    private String ruleDescription;

    /**
     * 编码模板
     * 支持占位符: {PREFIX}, {FACTORY}, {YYYY}, {MM}, {DD}, {SEQ:N}
     * 例如: MB-{FACTORY}-{YYYYMMDD}-{SEQ:4}
     */
    @Column(name = "encoding_pattern", length = 200, nullable = false)
    private String encodingPattern;

    /**
     * 固定前缀
     * 如: MB, PB, SH, QI
     */
    @Column(name = "prefix", length = 20)
    private String prefix;

    /**
     * 日期格式
     * 如: YYYYMMDD, YYYYMM, YYYY
     */
    @Column(name = "date_format", length = 20)
    private String dateFormat;

    /**
     * 序列号长度
     * 如: 4 表示 0001-9999
     */
    @Column(name = "sequence_length")
    @Builder.Default
    private Integer sequenceLength = 4;

    /**
     * 序列号重置周期
     * DAILY - 每日重置
     * MONTHLY - 每月重置
     * YEARLY - 每年重置
     * NEVER - 不重置
     */
    @Column(name = "reset_cycle", length = 20)
    @Builder.Default
    private String resetCycle = "DAILY";

    /**
     * 当前序列号
     * 用于追踪下一个可用序号
     */
    @Column(name = "current_sequence")
    @Builder.Default
    private Long currentSequence = 0L;

    /**
     * 最后重置日期
     * 用于判断是否需要重置序列号
     */
    @Column(name = "last_reset_date", length = 20)
    private String lastResetDate;

    /**
     * 分隔符
     * 如: -, _, /
     */
    @Column(name = "separator", length = 5)
    @Builder.Default
    private String separator = "-";

    /**
     * 是否包含工厂代码
     */
    @Column(name = "include_factory_code")
    @Builder.Default
    private Boolean includeFactoryCode = true;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 业务版本号 (用于规则变更追踪, 手动 incrementVersion 递增).
     * 注意: 不是 JPA @Version optimistic lock — 后者使用 optLockVersion 字段.
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Canvas-P3 AUD-4 P1: JPA @Version 乐观锁 (Long).
     * 与业务 version (Integer 配置版本号) 互不干扰; Flyway V20260824_05 加列默认 0.
     * Phase B (PR #201) uses {@code getOptLockVersion()} as the canonical accessor.
     */
    @jakarta.persistence.Version
    @Column(name = "opt_lock_version", nullable = false)
    @Builder.Default
    private Long optLockVersion = 0L;

    /**
     * 获取下一个序列号并递增
     * @return 下一个序列号
     */
    public Long getNextSequence() {
        this.currentSequence = (this.currentSequence == null ? 0L : this.currentSequence) + 1;
        return this.currentSequence;
    }

    /**
     * 重置序列号
     */
    public void resetSequence() {
        this.currentSequence = 0L;
    }

    /**
     * 递增版本号
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 0 : this.version) + 1;
    }
}
