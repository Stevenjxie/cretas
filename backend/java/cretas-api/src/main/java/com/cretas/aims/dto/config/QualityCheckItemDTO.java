package com.cretas.aims.dto.config;

import com.cretas.aims.entity.enums.QualityCheckCategory;
import com.cretas.aims.entity.enums.QualitySeverity;
import com.cretas.aims.entity.enums.SamplingStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质检项配置 DTO
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckItemDTO {

    private String id;
    private String factoryId;
    private String itemCode;
    private String itemName;
    private QualityCheckCategory category;
    private String categoryDescription;
    private String description;
    private String checkMethod;
    private String standardReference;

    // 标准值配置
    private String valueType;
    private String standardValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private String unit;
    private BigDecimal tolerance;

    // 抽样配置
    private SamplingStrategy samplingStrategy;
    private String samplingStrategyDescription;
    private BigDecimal samplingRatio;
    private Integer minSampleSize;
    private BigDecimal aqlLevel;

    // 严重程度和控制
    private QualitySeverity severity;
    private String severityDescription;
    private Integer severityWeight;
    private Boolean isRequired;
    private Boolean requirePhotoOnFail;
    private Boolean requireNoteOnFail;
    private Integer sortOrder;
    private Boolean enabled;
    private Integer version;

    // 统计信息
    private Integer bindingCount;

    // 审计字段
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
