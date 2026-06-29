package com.cretas.aims.dto.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 工厂成本设置 DTO —— GET 返回 / PUT 请求体共用.
 *
 * <p>{@code laborHourlyRate} 为 null 表示未配置 (前端显示"按默认 ¥26/工时");
 * PUT 时由 Service 校验必须 &gt; 0。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostSettingsDTO {

    /** 工时单价 ¥/工时; null = 未配置 */
    private BigDecimal laborHourlyRate;
}
