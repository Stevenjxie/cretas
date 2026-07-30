package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;

import java.util.List;
import java.util.Optional;

/**
 * 驾车路线规划 provider 抽象 (档1-B 多提供商 fallback, 2026-07-11) —
 * 实现: {@link AmapClient} (高德, 主) / {@link TencentMapClient} (腾讯) /
 * {@link BaiduMapClient} (百度)。链式调用见 {@link RouteProviderChain}。
 *
 * <p><b>诚实降级铁律</b> (对齐 {@link AmapClient} 类头): key 未配置 / 预算耗尽 /
 * HTTP 或解析失败 / 响应非成功 → 一律 {@link Optional#empty()}, 绝不伪造路线。
 *
 * <p><b>坐标契约</b>: 入参与返回折线一律 <b>GCJ-02, {lng, lat} 顺序</b>。各 provider
 * 的请求坐标顺序 (腾讯/百度是 lat,lng) 与坐标系差异 (百度 BD-09) 在实现内部消化。
 */
public interface DrivingRouteProvider {

    /** provider 标识 (配置 {@code logistics.route.providers} 用) — AMAP / TENCENT / BAIDU。 */
    String providerName();

    /** key 未配置 → false, 链路中直接跳过 (不发请求)。 */
    boolean isEnabled();

    /**
     * 驾车路线规划: DEPOT → 途经点 (访问顺序) → 终点。
     *
     * @param originLng GCJ-02 起点经度 (车场)
     * @param originLat GCJ-02 起点纬度
     * @param waypoints 途经点 {@code {lng, lat}} 列表 (GCJ-02, 可空/空列表 = 直达)
     * @param destLng   GCJ-02 终点经度 (最后一个门店)
     * @param destLat   GCJ-02 终点纬度
     * @param mode      优化模式 (时间最快 / 路程最短); null 按 DISTANCE 处理
     * @return 成功 → 路线; 任何失败 → {@link Optional#empty()} (诚实降级)
     */
    Optional<DrivingRoute> drivingRoute(double originLng, double originLat,
            List<double[]> waypoints, double destLng, double destLat, RouteOptimizeMode mode);
}
