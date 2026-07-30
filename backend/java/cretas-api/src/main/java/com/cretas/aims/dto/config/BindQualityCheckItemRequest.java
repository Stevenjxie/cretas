package com.cretas.aims.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 绑定质检项到产品请求
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindQualityCheckItemRequest {

    @NotBlank(message = "产品类型ID不能为空")
    private String productTypeId;

    @NotBlank(message = "质检项ID不能为空")
    private String qualityCheckItemId;

    // 覆盖配置（可选）
    private String overrideStandardValue;
    private BigDecimal overrideMinValue;
    private BigDecimal overrideMaxValue;
    private BigDecimal overrideSamplingRatio;
    private Boolean overrideIsRequired;

    private Integer sortOrder;

    @Builder.Default
    private Boolean enabled = true;

    private String notes;
}
