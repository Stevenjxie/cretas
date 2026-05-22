package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标详情响应 DTO — 包含完整字段 + thresholds[].
 *
 * <p>用于 {@code GET /indicators/{code}}; 区别于列表 DTO 多了 description / config / thresholds.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标详情响应 (完整字段 + 阈值)")
public class IndicatorDetailResponse {

    @Schema(description = "指标 ID")
    private String id;

    @Schema(description = "指标编码")
    private String code;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "分类: FACTORY / RESTAURANT / QUALITY")
    private String category;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "是否启用")
    private Boolean isActive;

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

    @Schema(description = "JSONB 配置")
    private Map<String, Object> config;

    @Schema(description = "阈值列表 (GREEN/YELLOW/RED)")
    private List<ThresholdResponse> thresholds;

    /**
     * 从实体 + 阈值列表投影 DTO.
     */
    public static IndicatorDetailResponse fromEntity(Indicator ind, List<IndicatorThreshold> thresholdEntities) {
        List<ThresholdResponse> thresholds = thresholdEntities == null
                ? List.of()
                : thresholdEntities.stream().map(ThresholdResponse::fromEntity).collect(Collectors.toList());
        return IndicatorDetailResponse.builder()
                .id(ind.getId())
                .code(ind.getCode())
                .name(ind.getName())
                .description(ind.getDescription())
                .category(ind.getCategory())
                .unit(ind.getUnit())
                .isActive(ind.getIsActive())
                .computeStrategy(ind.getComputeStrategy())
                .cacheTtlSeconds(ind.getCacheTtlSeconds())
                .lastValue(ind.getLastValue())
                .lastComputedAt(ind.getLastComputedAt())
                .displayOrder(ind.getDisplayOrder())
                .config(ind.getConfig())
                .thresholds(thresholds)
                .build();
    }
}
