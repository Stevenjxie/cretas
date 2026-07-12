package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档1-B (2026-07-11) — 路线 provider 解析/策略/坐标转换/fallback 链 纯单测。
 * 全部离线: 喂样例 JSON 字符串 / fake provider, 从不发起真实地图 API 请求。
 */
@DisplayName("RouteProviderChain + provider 解析 (纯单测, 零网络)")
class RouteProviderChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws Exception {
        return JSON.readValue(json, Map.class);
    }

    // ============================================================
    // AMap — polyline 解析 + strategy 映射
    // ============================================================

    @Nested
    @DisplayName("AmapClient 静态解析")
    class AmapParsing {

        @Test
        @DisplayName("direction 响应: steps[].polyline 顺序拼接, 接缝共点去重, 米→km / 秒→分")
        void parsesDirectionResponse() throws Exception {
            String body = """
                    {"status":"1","info":"OK","route":{"paths":[{
                      "distance":"12345","duration":"1800",
                      "steps":[
                        {"polyline":"120.6,31.3;120.7,31.4"},
                        {"polyline":"120.7,31.4;120.8,31.5"}
                      ]}]}}
                    """;
            Optional<DrivingRoute> route = AmapClient.parseDirectionResponse(parse(body));
            assertTrue(route.isPresent());
            assertEquals(new BigDecimal("12.35"), route.get().distanceKm());
            assertEquals(new BigDecimal("30.00"), route.get().durationMin());
            assertEquals("AMAP", route.get().provider());
            // 4 个原始点, 接缝 (120.7,31.4) 重复一次被去重 → 3 点
            List<double[]> polyline = route.get().polyline();
            assertEquals(3, polyline.size());
            assertEquals(120.6, polyline.get(0)[0]);
            assertEquals(31.3, polyline.get(0)[1]);
            assertEquals(120.8, polyline.get(2)[0]);
            assertEquals(31.5, polyline.get(2)[1]);
        }

        @Test
        @DisplayName("appendPolyline: 样例 \"120.6,31.3;120.7,31.4\" → [{lng,lat}] 两点")
        void appendPolylineParsesPairs() {
            List<double[]> acc = new ArrayList<>();
            AmapClient.appendPolyline(acc, "120.6,31.3;120.7,31.4");
            assertEquals(2, acc.size());
            assertEquals(120.6, acc.get(0)[0]);
            assertEquals(31.3, acc.get(0)[1]);
            assertEquals(120.7, acc.get(1)[0]);
            assertEquals(31.4, acc.get(1)[1]);
        }

        @Test
        @DisplayName("paths 为空 / 缺 steps → empty (诚实, 不返回半截结果)")
        void emptyPathsHonestlyEmpty() throws Exception {
            assertTrue(AmapClient.parseDirectionResponse(parse("{\"status\":\"1\",\"route\":{\"paths\":[]}}")).isEmpty());
            assertTrue(AmapClient.parseDirectionResponse(parse(
                    "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"1\",\"duration\":\"1\"}]}}")).isEmpty());
        }

        @Test
        @DisplayName("strategy 映射: TIME→0 (速度优先) / DISTANCE→2 / null→2 (默认距离最短)")
        void strategyMapping() {
            assertEquals(0, AmapClient.amapStrategy(RouteOptimizeMode.TIME));
            assertEquals(2, AmapClient.amapStrategy(RouteOptimizeMode.DISTANCE));
            assertEquals(2, AmapClient.amapStrategy(null));
        }
    }

    // ============================================================
    // Tencent — 压缩坐标解压 (lat,lng 序 + delta/1e6)
    // ============================================================

    @Nested
    @DisplayName("TencentMapClient 静态解析")
    class TencentParsing {

        @Test
        @DisplayName("polyline 解压: [lat0,lng0,dLat,dLng,...] delta/1e6 还原, 输出 {lng,lat}")
        void decompressesPolyline() {
            // 点1 (31.3, 120.6); 点2 = (31.3+100000/1e6, 120.6+200000/1e6) = (31.4, 120.8)
            List<double[]> points = TencentMapClient.decompressPolyline(
                    List.of(31.3, 120.6, 100000, 200000));
            assertEquals(2, points.size());
            assertEquals(120.6, points.get(0)[0], 1e-9);
            assertEquals(31.3, points.get(0)[1], 1e-9);
            assertEquals(120.8, points.get(1)[0], 1e-9);
            assertEquals(31.4, points.get(1)[1], 1e-9);
        }

        @Test
        @DisplayName("direction 响应: distance 米 / duration 已是分钟 / polyline 压缩数组")
        void parsesDirectionResponse() throws Exception {
            String body = """
                    {"status":0,"message":"query ok","result":{"routes":[{
                      "distance":15000,"duration":25,
                      "polyline":[31.3,120.6,100000,200000]}]}}
                    """;
            Optional<DrivingRoute> route = TencentMapClient.parseDirectionResponse(parse(body));
            assertTrue(route.isPresent());
            assertEquals(new BigDecimal("15.00"), route.get().distanceKm());
            assertEquals(new BigDecimal("25.00"), route.get().durationMin()); // 分钟原样, 不再 /60
            assertEquals("TENCENT", route.get().provider());
            assertEquals(2, route.get().polyline().size());
        }
    }

    // ============================================================
    // Baidu — BD-09 ↔ GCJ-02 转换 + path 解析
    // ============================================================

    @Nested
    @DisplayName("BaiduMapClient + CoordTransform")
    class BaiduParsing {

        @Test
        @DisplayName("CoordTransform round-trip: gcj→bd→gcj 误差 < 1e-5 度")
        void coordTransformRoundTrip() {
            double lng = 120.62;
            double lat = 31.30;
            double[] bd = CoordTransform.gcj02ToBd09(lng, lat);
            double[] back = CoordTransform.bd09ToGcj02(bd[0], bd[1]);
            assertEquals(lng, back[0], 1e-5);
            assertEquals(lat, back[1], 1e-5);
            // BD-09 相对 GCJ-02 有系统性偏移 (非恒等转换)
            assertTrue(Math.abs(bd[0] - lng) > 1e-4);
            assertTrue(Math.abs(bd[1] - lat) > 1e-4);
        }

        @Test
        @DisplayName("direction 响应: steps[].path (BD-09) → 折线逐点转 GCJ-02, 秒→分")
        void parsesDirectionResponseAndConvertsDatum() throws Exception {
            // 构造 path: 已知 GCJ-02 点转成 BD-09 写进响应, 断言解析结果转回 GCJ-02
            double[] bd1 = CoordTransform.gcj02ToBd09(120.6, 31.3);
            double[] bd2 = CoordTransform.gcj02ToBd09(120.7, 31.4);
            String body = String.format(java.util.Locale.ROOT, """
                    {"status":0,"result":{"routes":[{
                      "distance":9000,"duration":1200,
                      "steps":[{"path":"%.6f,%.6f;%.6f,%.6f"}]}]}}
                    """, bd1[0], bd1[1], bd2[0], bd2[1]);
            Optional<DrivingRoute> route = BaiduMapClient.parseDirectionResponse(parse(body));
            assertTrue(route.isPresent());
            assertEquals(new BigDecimal("9.00"), route.get().distanceKm());
            assertEquals(new BigDecimal("20.00"), route.get().durationMin());
            assertEquals("BAIDU", route.get().provider());
            List<double[]> polyline = route.get().polyline();
            assertEquals(2, polyline.size());
            assertEquals(120.6, polyline.get(0)[0], 1e-4); // path 6 位小数截断 + 转换 → 1e-4 容差
            assertEquals(31.3, polyline.get(0)[1], 1e-4);
            assertEquals(120.7, polyline.get(1)[0], 1e-4);
            assertEquals(31.4, polyline.get(1)[1], 1e-4);
        }
    }

    // ============================================================
    // Fallback 链 — 顺序 / 跳过未启用 / 全失败 honest empty
    // ============================================================

    @Nested
    @DisplayName("RouteProviderChain fallback")
    class ChainFallback {

        private DrivingRouteProvider provider(String name, boolean enabled, DrivingRoute result) {
            return new DrivingRouteProvider() {
                @Override
                public String providerName() {
                    return name;
                }

                @Override
                public boolean isEnabled() {
                    return enabled;
                }

                @Override
                public Optional<DrivingRoute> drivingRoute(double oLng, double oLat,
                        List<double[]> waypoints, double dLng, double dLat, RouteOptimizeMode mode) {
                    return Optional.ofNullable(result);
                }
            };
        }

        private DrivingRoute routeOf(String provider) {
            return new DrivingRoute(BigDecimal.ONE, BigDecimal.TEN, List.of(new double[] {120.6, 31.3}), provider);
        }

        @Test
        @DisplayName("首个启用且成功的 provider 胜出 (AMAP 未启用 → TENCENT 接管)")
        void firstEnabledSuccessWins() {
            RouteProviderChain chain = new RouteProviderChain(List.of(
                    provider("AMAP", false, routeOf("AMAP")),
                    provider("TENCENT", true, routeOf("TENCENT")),
                    provider("BAIDU", true, routeOf("BAIDU"))),
                    "AMAP,TENCENT,BAIDU");
            Optional<DrivingRoute> route = chain.drivingRoute(120.62, 31.30, List.of(), 120.7, 31.4,
                    RouteOptimizeMode.DISTANCE);
            assertTrue(route.isPresent());
            assertEquals("TENCENT", route.get().provider());
        }

        @Test
        @DisplayName("AMAP 启用但失败 (配额耗尽等) → 顺延 BAIDU")
        void failedProviderFallsThrough() {
            RouteProviderChain chain = new RouteProviderChain(List.of(
                    provider("AMAP", true, null),
                    provider("BAIDU", true, routeOf("BAIDU"))),
                    "AMAP,TENCENT,BAIDU"); // TENCENT 配置了但未注册 → 启动时跳过
            Optional<DrivingRoute> route = chain.drivingRoute(120.62, 31.30, List.of(), 120.7, 31.4,
                    RouteOptimizeMode.TIME);
            assertTrue(route.isPresent());
            assertEquals("BAIDU", route.get().provider());
        }

        @Test
        @DisplayName("全部未启用/失败 → empty (诚实降级, 调用方回落边距离) + anyEnabled=false")
        void allDisabledHonestlyEmpty() {
            RouteProviderChain chain = new RouteProviderChain(List.of(
                    provider("AMAP", false, routeOf("AMAP")),
                    provider("TENCENT", false, routeOf("TENCENT"))),
                    "AMAP,TENCENT,BAIDU");
            assertTrue(chain.drivingRoute(120.62, 31.30, List.of(), 120.7, 31.4, null).isEmpty());
            assertEquals(false, chain.anyEnabled());
        }

        @Test
        @DisplayName("配置顺序可倒转: BAIDU,AMAP → BAIDU 先试")
        void configurableOrder() {
            RouteProviderChain chain = new RouteProviderChain(List.of(
                    provider("AMAP", true, routeOf("AMAP")),
                    provider("BAIDU", true, routeOf("BAIDU"))),
                    "BAIDU,AMAP");
            Optional<DrivingRoute> route = chain.drivingRoute(120.62, 31.30, List.of(), 120.7, 31.4, null);
            assertTrue(route.isPresent());
            assertEquals("BAIDU", route.get().provider());
        }
    }
}
