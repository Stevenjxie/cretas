package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 指标阈值响应 DTO — GREEN/YELLOW/RED 三色预警规则.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标阈值响应")
public class ThresholdResponse {

    @Schema(description = "阈值 ID")
    private String id;

    @Schema(description = "指标 ID")
    private String indicatorId;

    @Schema(description = "告警级别: GREEN / YELLOW / RED")
    private String alertLevel;

    @Schema(description = "运算符: GT / GTE / LT / LTE / EQ / BETWEEN")
    private String operator;

    @Schema(description = "阈值数值")
    private BigDecimal thresholdValue;

    @Schema(description = "BETWEEN 上限 (其它运算符忽略)")
    private BigDecimal thresholdValueUpper;

    @Schema(description = "是否启用")
    private Boolean isActive;

    /**
     * 从实体投影 DTO.
     */
    public static ThresholdResponse fromEntity(IndicatorThreshold t) {
        return ThresholdResponse.builder()
                .id(t.getId())
                .indicatorId(t.getIndicatorId())
                .alertLevel(t.getAlertLevel())
                .operator(t.getOperator())
                .thresholdValue(t.getThresholdValue())
                .thresholdValueUpper(t.getThresholdValueUpper())
                .isActive(t.getIsActive())
                .build();
    }
}
