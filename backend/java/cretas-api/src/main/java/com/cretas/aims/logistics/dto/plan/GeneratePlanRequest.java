package com.cretas.aims.logistics.dto.plan;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import lombok.Data;

import java.math.BigDecimal;

/** {@code POST /plans/generate} 请求体 — 镜像前端 {@code GeneratePlanRequest}。 */
@Data
public class GeneratePlanRequest {
    private String batchId;
    private BigDecimal targetLoadPct;
    /** 排线优化模式: TIME=时间最快 / DISTANCE=路程最短 (缺省 DISTANCE, 档1-B 2026-07-11)。 */
    private RouteOptimizeMode optimizeBy;
}
