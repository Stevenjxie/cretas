package com.cretas.aims.logistics.service.routing;

/**
 * GCJ-02 ↔ BD-09 坐标转换 (标准公式, 档1-B 2026-07-11)。
 *
 * <p>系统内持久化坐标 (订单经纬度 / 车次折线) 统一 <b>GCJ-02</b> (高德/腾讯原生)。
 * 百度地图 API 使用 BD-09: 请求前 {@link #gcj02ToBd09}, 返回折线 {@link #bd09ToGcj02}
 * 后再入库, 保证前端高德底图渲染不偏移。
 *
 * <p>公式为公开的标准算法 (x_pi = π × 3000 / 180), 往返误差 &lt; 1e-6 度 (~0.1m),
 * 见 {@code RouteProviderChainTest} round-trip 断言。
 */
public final class CoordTransform {

    private static final double X_PI = 3.14159265358979324 * 3000.0 / 180.0;

    private CoordTransform() {
    }

    /** GCJ-02 → BD-09。@return {@code {lng, lat}} */
    public static double[] gcj02ToBd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[] {z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    /** BD-09 → GCJ-02。@return {@code {lng, lat}} */
    public static double[] bd09ToGcj02(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[] {z * Math.cos(theta), z * Math.sin(theta)};
    }
}
