package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

import java.math.BigDecimal;

/** {@code PUT /orders/{orderId}/location} 请求体 — 经纬度必须同时提供。 */
@Data
public class UpdateLocationRequest {
    private BigDecimal longitude;
    private BigDecimal latitude;
}
