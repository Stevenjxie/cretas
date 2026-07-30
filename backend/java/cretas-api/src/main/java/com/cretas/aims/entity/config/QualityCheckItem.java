package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.QualityCheckCategory;
import com.cretas.aims.entity.enums.QualitySeverity;
import com.cretas.aims.entity.enums.SamplingStrategy;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 质检项配置实体
 *
 * 用于定义质量检验的检查项目和标准
 * 支持感官、物理、化学、微生物等多类别检测项目
 * 可配置标准值、范围、抽样策略等
 *
 * 示例:
 * - 感官检测: 外观色泽、气味、口感
 * - 物理检测: 中心温度 ≤ 4°C、净含量 ≥ 标示值
 * - 化学检测: pH值 6.0-7.0、盐分 ≤ 3%
 * - 微生物检测: 菌落总数 ≤ 100000 CFU/g
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "quality_check_items",
       indexes = {
           @Index(name = "idx_qci_factory", columnList = "factory_id"),
           @Index(name = "idx_qci_category", columnList = "factory_id, category"),
           @Index(name = "idx_qci_code", columnList = "factory_id, item_code")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_qci_factory_code",
                            columnNames = {"factory_id", "item_code"})
       })
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class QualityCheckItem extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     * 为 null 时表示系统默认项目模板
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 项目编号
     * 如: QCI-001, SENSE-01, PHYS-01
     */
    @Column(name = "item_code", length = 50, nullable = false)
    private String itemCode;

    /**
     * 项目名称
     * 如: 外观色泽、中心温度、菌落总数
     */
    @Column(name = "item_name", length = 100, nullable = false)
    private String itemName;

    /**
     * 项目类别
     * SENSORY-感官, PHYSICAL-物理, CHEMICAL-化学, MICROBIOLOGICAL-微生物, PACKAGING-包装
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, nullable = false)
    private QualityCheckCategory category;

    /**
     * 项目描述
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 检测方法
     * 描述如何进行检测
     */
    @Column(name = "check_method", length = 500)
    private String checkMethod;

    /**
     * 检测标准
     * 引用的标准，如: GB 2733-2015
     */
    @Column(name = "standard_reference", length = 200)
    private String standardReference;

    // ==================== 标准值配置 ====================

    /**
     * 检测类型
     * NUMERIC - 数值型，需要填写具体数值
     * TEXT - 文本型，如感官描述
     * BOOLEAN - 布尔型，合格/不合格
     * RANGE - 范围型，需要在范围内
     */
    @Column(name = "value_type", length = 20)
    @Builder.Default
    private String valueType = "NUMERIC";

    /**
     * 标准值（文本/数值）
     * 如: "正常"、"无异味"、"4.0"
     */
    @Column(name = "standard_value", length = 100)
    private String standardValue;

    /**
     * 最小值（数值型适用）
     * 如: 温度 ≥ -18°C 时填 -18
     */
    @Column(name = "min_value", precision = 15, scale = 4)
    private BigDecimal minValue;

    /**
     * 最大值（数值型适用）
     * 如: 温度 ≤ 4°C 时填 4
     */
    @Column(name = "max_value", precision = 15, scale = 4)
    private BigDecimal maxValue;

    /**
     * 单位
     * 如: °C, %, g, CFU/g
     */
    @Column(name = "unit", length = 30)
    private String unit;

    /**
     * 允许误差
     * 如: ±0.5
     */
    @Column(name = "tolerance", precision = 10, scale = 4)
    private BigDecimal tolerance;

    // ==================== 抽样配置 ====================

    /**
     * 抽样策略
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sampling_strategy", length = 30)
    @Builder.Default
    private SamplingStrategy samplingStrategy = SamplingStrategy.RANDOM;

    /**
     * 抽样比例 (%)
     * 如: 10 表示抽检10%
     */
    @Column(name = "sampling_ratio", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal samplingRatio = new BigDecimal("10.00");

    /**
     * 最小抽样数量
     */
    @Column(name = "min_sample_size")
    @Builder.Default
    private Integer minSampleSize = 1;

    /**
     * AQL (可接受质量水平)
     * 用于AQL抽样方案
     */
    @Column(name = "aql_level", precision = 5, scale = 2)
    private BigDecimal aqlLevel;

    // ==================== 严重程度和控制 ====================

    /**
     * 严重程度
     * CRITICAL-关键, MAJOR-主要, MINOR-次要
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    @Builder.Default
    private QualitySeverity severity = QualitySeverity.MAJOR;

    /**
     * 是否必检项
     */
    @Column(name = "is_required")
    @Builder.Default
    private Boolean isRequired = true;

    /**
     * 不合格时是否需要拍照
     */
    @Column(name = "require_photo_on_fail")
    @Builder.Default
    private Boolean requirePhotoOnFail = false;

    /**
     * 不合格时是否需要备注
     */
    @Column(name = "require_note_on_fail")
    @Builder.Default
    private Boolean requireNoteOnFail = true;

    /**
     * 排序顺序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 版本号
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    // ==================== 业务方法 ====================

    /**
     * 递增版本号
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 0 : this.version) + 1;
    }

    /**
     * 判断检测值是否在合格范围内
     *
     * @param value 检测值
     * @return 是否合格
     */
    public boolean isValueInRange(BigDecimal value) {
        if (value == null) {
            return false;
        }

        boolean minOk = minValue == null || value.compareTo(minValue) >= 0;
        boolean maxOk = maxValue == null || value.compareTo(maxValue) <= 0;

        return minOk && maxOk;
    }

    /**
     * 判断是否为关键项
     */
    public boolean isCritical() {
        return severity == QualitySeverity.CRITICAL;
    }
}
