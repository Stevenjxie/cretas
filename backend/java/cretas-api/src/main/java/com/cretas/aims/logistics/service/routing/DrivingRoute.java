package com.cretas.aims.logistics.service.routing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次驾车路线规划结果 (多地图 provider 通用形状, 档1-B 2026-07-11)。
 *
 * @param distanceKm  全程道路里程 (公里, scale=2 HALF_UP)
 * @param durationMin 全程预计时长 (分钟, scale=2 HALF_UP)
 * @param polyline    道路折线点串, 每个元素 {@code {lng, lat}} — <b>恒为 GCJ-02 坐标系</b>
 *                    (前端高德底图直接可画；百度 BD-09 返回值在 client 内已转换)
 * @param provider    产出本结果的 provider 标识 ({@code AMAP} / {@code TENCENT} / {@code BAIDU})
 */
public record DrivingRoute(
        BigDecimal distanceKm,
        BigDecimal durationMin,
        List<double[]> polyline,
        String provider) {
}
