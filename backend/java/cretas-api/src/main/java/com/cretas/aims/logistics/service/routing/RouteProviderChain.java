package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 驾车路线多提供商 fallback 链 (档1-B, 2026-07-11): 按 {@code logistics.route.providers}
 * 配置顺序 (默认 {@code AMAP,TENCENT,BAIDU}) 逐个尝试, 首个成功即返回; 全部失败/全部未启用
 * → {@link Optional#empty()} — 调用方回落既有边距离路径 (诚实降级, 无折线不伪造)。
 *
 * <p>存在意义: 高德每日配额耗尽 / 服务抖动时, 排线不至于整体退化为无路线, 而是自动切换
 * 腾讯 → 百度。各 provider 由各自 key 是否配置独立门控 ({@link DrivingRouteProvider#isEnabled()});
 * 测试/CI 环境三个 key 全空 → 整链禁用 → 与引入本链之前行为完全一致 (23 个 routing 测试不受影响)。
 *
 * <p>配置中出现但未注册的 provider 名跳过 (warn 一次于启动日志); 配置为空串 → 整链禁用。
 */
@Slf4j
@Component
public class RouteProviderChain {

    private final List<DrivingRouteProvider> orderedProviders;

    public RouteProviderChain(
            List<DrivingRouteProvider> providers,
            @Value("${logistics.route.providers:AMAP,TENCENT,BAIDU}") String providersCsv) {
        Map<String, DrivingRouteProvider> byName = new LinkedHashMap<>();
        for (DrivingRouteProvider p : providers) {
            byName.put(p.providerName().toUpperCase(Locale.ROOT), p);
        }
        List<DrivingRouteProvider> ordered = new ArrayList<>();
        if (providersCsv != null && !providersCsv.isBlank()) {
            for (String rawName : providersCsv.split(",")) {
                String name = rawName.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                DrivingRouteProvider provider = byName.get(name);
                if (provider == null) {
                    log.warn("logistics.route.providers 配置了未注册的 provider: {} — 跳过", name);
                    continue;
                }
                ordered.add(provider);
            }
        }
        this.orderedProviders = List.copyOf(ordered);
        log.info("RouteProviderChain initialized; order={} enabled={}",
                orderedProviders.stream().map(DrivingRouteProvider::providerName).toList(),
                orderedProviders.stream().filter(DrivingRouteProvider::isEnabled)
                        .map(DrivingRouteProvider::providerName).toList());
    }

    /** 至少一个 provider 已配置 key → true。false 时调用方可直接跳过路线规划 (零开销)。 */
    public boolean anyEnabled() {
        return orderedProviders.stream().anyMatch(DrivingRouteProvider::isEnabled);
    }

    /**
     * 按配置顺序逐 provider 尝试驾车路线规划, 首个成功即返回 (结果的
     * {@link DrivingRoute#provider()} 标明胜出者)。坐标契约: GCJ-02 {lng, lat}
     * (见 {@link DrivingRouteProvider#drivingRoute})。
     */
    public Optional<DrivingRoute> drivingRoute(double originLng, double originLat,
            List<double[]> waypoints, double destLng, double destLat, RouteOptimizeMode mode) {
        for (DrivingRouteProvider provider : orderedProviders) {
            if (!provider.isEnabled()) {
                continue;
            }
            Optional<DrivingRoute> route = provider.drivingRoute(
                    originLng, originLat, waypoints, destLng, destLat, mode);
            if (route.isPresent()) {
                return route;
            }
            log.info("route provider {} 未返回路线, 尝试下一个 provider", provider.providerName());
        }
        return Optional.empty();
    }
}
