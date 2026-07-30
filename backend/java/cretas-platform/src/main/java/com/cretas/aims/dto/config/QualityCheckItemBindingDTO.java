package com.cretas.aims.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质检项绑定 DTO
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityCheckItemBindingDTO {

    private String id;
    private String factoryId;
    private String productTypeId;
    private String productTypeName;
    private String qualityCheckItemId;

    // 关联的质检项信息
    private QualityCheckItemDTO qualityCheckItem;

    // 覆盖配置
    private String overrideStandardValue;
    private BigDecimal overrideMinValue;
    private BigDecimal overrideMaxValue;
    private BigDecimal overrideSamplingRatio;
    private Boolean overrideIsRequired;

    // 生效的值（如果有覆盖则用覆盖值，否则用默认值）
    private String effectiveStandardValue;
    private BigDecimal effectiveMinValue;
    private BigDecimal effectiveMaxValue;
    private BigDecimal effectiveSamplingRatio;
    private Boolean effectiveIsRequired;

    private Integer sortOrder;
    private Boolean enabled;
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
