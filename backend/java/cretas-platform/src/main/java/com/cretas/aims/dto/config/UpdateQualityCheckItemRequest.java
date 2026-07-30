package com.cretas.aims.dto.config;

import com.cretas.aims.entity.enums.QualityCheckCategory;
import com.cretas.aims.entity.enums.QualitySeverity;
import com.cretas.aims.entity.enums.SamplingStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 更新质检项请求
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateQualityCheckItemRequest {

    @Size(max = 100, message = "项目名称不能超过100个字符")
    private String itemName;

    private QualityCheckCategory category;

    @Size(max = 500, message = "项目描述不能超过500个字符")
    private String description;

    @Size(max = 500, message = "检测方法不能超过500个字符")
    private String checkMethod;

    @Size(max = 200, message = "检测标准不能超过200个字符")
    private String standardReference;

    // 标准值配置
    private String valueType;
    private String standardValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;

    @Size(max = 30, message = "单位不能超过30个字符")
    private String unit;

    private BigDecimal tolerance;

    // 抽样配置
    private SamplingStrategy samplingStrategy;
    private BigDecimal samplingRatio;
    private Integer minSampleSize;
    private BigDecimal aqlLevel;

    // 严重程度和控制
    private QualitySeverity severity;
    private Boolean isRequired;
    private Boolean requirePhotoOnFail;
    private Boolean requireNoteOnFail;
    private Integer sortOrder;
    private Boolean enabled;
}
