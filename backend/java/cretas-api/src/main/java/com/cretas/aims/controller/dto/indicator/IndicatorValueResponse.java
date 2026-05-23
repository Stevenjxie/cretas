package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指标取值响应 DTO — wraps {@link IndicatorValueResult} for HTTP.
 *
 * <p>携带计算值 + 计算时刻 + 来源 (cache/python/precomputed) + 业务时间窗 + 指标基本信息.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标取值响应")
public class IndicatorValueResponse {

    @Schema(description = "指标编码")
    private String code;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "指标取值")
    private BigDecimal value;

    @Schema(description = "物理计算时刻")
    private LocalDateTime computedAt;

    @Schema(description = "缓存命中标识")
    private Boolean cacheHit;

    @Schema(description = "来源标签: cache / python / precomputed")
    private String source;

    @Schema(description = "业务时间窗起 (查询入参回显)")
    private LocalDate periodStart;

    @Schema(description = "业务时间窗止 (查询入参回显)")
    private LocalDate periodEnd;

    /**
     * 从 service 计算结果 + 指标元信息构造 DTO.
     */
    public static IndicatorValueResponse from(String code, String name, String unit,
                                              IndicatorValueResult result,
                                              LocalDate periodStart, LocalDate periodEnd) {
        return IndicatorValueResponse.builder()
                .code(code)
                .name(name)
                .unit(unit)
                .value(result.value())
                .computedAt(result.computedAt())
                .cacheHit(result.cacheHit())
                .source(result.source())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .build();
    }
}
