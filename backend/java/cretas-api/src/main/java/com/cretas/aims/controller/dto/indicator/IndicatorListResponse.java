package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标列表响应 DTO — 列表视图（指标中心首页用）.
 *
 * <p>不含完整 config / 阈值，仅展示卡片所需字段。详情用 {@link IndicatorDetailResponse}.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标列表响应 (列表视图)")
public class IndicatorListResponse {

    @Schema(description = "指标 ID")
    private String id;

    @Schema(description = "指标编码", example = "FACTORY_YIELD_RATE")
    private String code;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "分类: FACTORY / RESTAURANT / QUALITY")
    private String category;

    @Schema(description = "单位 (e.g. %)")
    private String unit;

    @Schema(description = "计算策略: REALTIME / CACHED / PRECOMPUTED")
    private String computeStrategy;

    @Schema(description = "缓存 TTL 秒")
    private Integer cacheTtlSeconds;

    @Schema(description = "上次计算值")
    private BigDecimal lastValue;

    @Schema(description = "上次计算时刻")
    private LocalDateTime lastComputedAt;

    @Schema(description = "展示顺序")
    private Integer displayOrder;

    @Schema(description = "是否启用")
    private Boolean isActive;

    /**
     * 从实体投影 DTO.
     */
    public static IndicatorListResponse fromEntity(Indicator ind) {
        return IndicatorListResponse.builder()
                .id(ind.getId())
                .code(ind.getCode())
                .name(ind.getName())
                .category(ind.getCategory())
                .unit(ind.getUnit())
                .computeStrategy(ind.getComputeStrategy())
                .cacheTtlSeconds(ind.getCacheTtlSeconds())
                .lastValue(ind.getLastValue())
                .lastComputedAt(ind.getLastComputedAt())
                .displayOrder(ind.getDisplayOrder())
                .isActive(ind.getIsActive())
                .build();
    }
}
