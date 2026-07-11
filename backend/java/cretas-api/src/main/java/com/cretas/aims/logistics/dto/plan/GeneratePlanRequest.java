package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

import java.math.BigDecimal;

/** {@code POST /plans/generate} 请求体 — 镜像前端 {@code GeneratePlanRequest}。 */
@Data
public class GeneratePlanRequest {
    private String batchId;
    private BigDecimal targetLoadPct;
}
