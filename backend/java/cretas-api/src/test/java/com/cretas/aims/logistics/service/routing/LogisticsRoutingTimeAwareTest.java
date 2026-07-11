package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.DriverBindingInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Input;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.OrderInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Result;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.TripResult;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.VehicleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档3 — TIME 模式 (行驶时间 + 迟到惩罚) gate 单测。
 *
 * <p>核心被测行为:
 * <ol>
 *   <li>车次内 — DISTANCE 最优顺序导致远端紧窗店迟到; TIME 模式牺牲里程重排 (先跑远端) 把迟到清零。</li>
 *   <li>跨车次 — 单车吃 3 单必迟到; TIME 模式把紧窗远单搬到空闲车 (总里程变长, 迟到清零);
 *       DISTANCE 模式同一夹具保持单车 (拆分让里程严格变长, 距离目标拒绝)。</li>
 *   <li>DISTANCE 不变性 — 同一夹具, 9 参兼容构造器 (无模式) 与显式 DISTANCE 逐字段一致。</li>
 *   <li>无窗口 — TIME 模式退化为纯行驶时间最小化 (∝ 里程), 顺序与 DISTANCE 一致, 迟到 0。</li>
 *   <li>确定性 — TIME 路径同输入两跑逐字段一致。</li>
 * </ol>
 *
 * <p>夹具几何: DEPOT (120.00, 31.00), 同纬度东向一字排开 — 近端 S-01 (0.02°) / S-02 (0.04°),
 * 远端 S-03 (0.30°)。1° ≈ 95.1 km (cos 31° 修正), 28 km/h → 远端单程 ≈ 61 分钟, 近端 ≈ 4-8 分钟。
 */
@DisplayName("LogisticsRoutingAlgorithm — 档3 TIME 模式 (时间+迟到) gate")
class LogisticsRoutingTimeAwareTest {

    private static final double[] DEPOT = {120.00, 31.00};

    private static final Map<String, double[]> STORE_COORDS = Map.of(
            "DEPOT", DEPOT,
            "S-01", new double[] {120.02, 31.00},
            "S-02", new double[] {120.04, 31.00},
            "S-03", new double[] {120.30, 31.00});

    // ============================================================
    // (a) 车次内 — TIME 重排守窗, DISTANCE 保持里程最优
    // ============================================================

    @Test
    @DisplayName("车次内 — 远端店紧窗 08:00-08:40: DISTANCE 顺序迟到 >30min, TIME 先跑远端迟到清零")
    void withinTripTimeModeReordersToMeetWindow() {
        // 近端两店窗口宽松 (09:00-18:00), 远端 S-03 紧窗 (08:00-08:40)。
        List<OrderInput> orders = List.of(
                orderWithWindow("n1", "S-01", "2", "09:00", "18:00"),
                orderWithWindow("n2", "S-02", "2", "09:00", "18:00"),
                orderWithWindow("f1", "S-03", "2", "08:00", "08:40"));

        Result distance = LogisticsRoutingAlgorithm.run(singleVehicleInput(orders, RouteOptimizeMode.DISTANCE));
        Result time = LogisticsRoutingAlgorithm.run(singleVehicleInput(orders, RouteOptimizeMode.TIME));

        // 都是单车次 (一辆车, 体积 6 ≤ 10)
        assertEquals(1, distance.trips().size());
        assertEquals(1, time.trips().size());

        // DISTANCE: 最近邻+2-opt 里程最优 = 近→远 [n1, n2, f1]
        assertEquals(List.of("n1", "n2", "f1"), distance.trips().get(0).orderIdsInOrder());
        // TIME: 先跑远端紧窗店再回近端松窗店
        assertEquals(List.of("f1", "n2", "n1"), time.trips().get(0).orderIdsInOrder());

        // TIME 迟到清零, DISTANCE 迟到 ≈ 37 分钟 (远端最后到 ≈ 09:17, 窗口 08:40 截止)
        double latenessDistance = LogisticsRoutingAlgorithm.totalLatenessMin(
                distance, singleVehicleInput(orders, RouteOptimizeMode.DISTANCE));
        double latenessTime = LogisticsRoutingAlgorithm.totalLatenessMin(
                time, singleVehicleInput(orders, RouteOptimizeMode.TIME));
        assertTrue(latenessDistance > 30.0,
                "DISTANCE-optimal order must be late at the far tight-window stop, got " + latenessDistance);
        assertEquals(0.0, latenessTime, 1e-6, "TIME mode must eliminate lateness on this fixture");

        // 时间换里程: TIME 总里程严格更长 (先远后近折返), 但迟到更少
        assertTrue(totalKm(time).compareTo(totalKm(distance)) > 0,
                "TIME mode trades distance (" + totalKm(time) + " km vs " + totalKm(distance) + " km) for punctuality");
    }

    // ============================================================
    // (b) 跨车次 — TIME 搬单守窗, DISTANCE 不拆
    // ============================================================

    @Test
    @DisplayName("跨车次 — 3 店全紧窗 08:00-08:30 单车必迟到: TIME 把远单搬到空闲车迟到清零, DISTANCE 保持单车")
    void crossVehicleTimeModeRelocatesToMeetWindow() {
        // 3 店同区同窗 08:00-08:30 — 单车任何顺序必有一站迟到 (远端单程 ≈ 61min);
        // 拆两车 (近簇 + 远单) 都能准点, 但总里程更长 → DISTANCE 拒绝, TIME 接受。
        List<OrderInput> orders = List.of(
                orderWithWindow("n1", "S-01", "2", "08:00", "08:30"),
                orderWithWindow("n2", "S-02", "2", "08:00", "08:30"),
                orderWithWindow("f1", "S-03", "2", "08:00", "08:30"));

        Result distance = LogisticsRoutingAlgorithm.run(twoVehicleInput(orders, RouteOptimizeMode.DISTANCE));
        Result time = LogisticsRoutingAlgorithm.run(twoVehicleInput(orders, RouteOptimizeMode.TIME));

        // DISTANCE: 拆分只会加里程 → 保持单车 (V2 闲置)
        assertEquals(1, distance.trips().size());
        double latenessDistance = LogisticsRoutingAlgorithm.totalLatenessMin(
                distance, twoVehicleInput(orders, RouteOptimizeMode.DISTANCE));
        assertTrue(latenessDistance > 40.0,
                "single-vehicle DISTANCE plan must be late somewhere, got " + latenessDistance);

        // TIME: 远单 f1 独立成车次 (搬到空闲车), 近簇留原车 → 全部准点
        assertEquals(2, time.trips().size());
        TripResult farTrip = time.trips().stream()
                .filter(t -> t.orderIdsInOrder().contains("f1")).findFirst().orElseThrow();
        assertEquals(List.of("f1"), farTrip.orderIdsInOrder(), "far tight-window order must ride alone");
        TripResult nearTrip = time.trips().stream()
                .filter(t -> !t.orderIdsInOrder().contains("f1")).findFirst().orElseThrow();
        assertEquals(Set.of("n1", "n2"), Set.copyOf(nearTrip.orderIdsInOrder()));
        assertNotEquals(farTrip.vehicleId(), nearTrip.vehicleId());

        double latenessTime = LogisticsRoutingAlgorithm.totalLatenessMin(
                time, twoVehicleInput(orders, RouteOptimizeMode.TIME));
        assertEquals(0.0, latenessTime, 1e-6, "TIME mode split must meet every window");

        // 时间换里程: 拆分总里程严格更长
        assertTrue(totalKm(time).compareTo(totalKm(distance)) > 0,
                "TIME split trades distance (" + totalKm(time) + " km vs " + totalKm(distance) + " km) for punctuality");
    }

    // ============================================================
    // (c) DISTANCE 不变性 — 兼容构造器 (无模式) ≡ 显式 DISTANCE
    // ============================================================

    @Test
    @DisplayName("DISTANCE 不变性 — 9 参兼容构造器 (null 模式) 与显式 DISTANCE 逐字段一致")
    void distanceModeIsByteIdenticalToLegacyConstructor() {
        List<OrderInput> orders = List.of(
                orderWithWindow("n1", "S-01", "2", "09:00", "18:00"),
                orderWithWindow("n2", "S-02", "2", "09:00", "18:00"),
                orderWithWindow("f1", "S-03", "2", "08:00", "08:40"));
        Map<String, double[]> coords = coordsFor(orders);

        // 9 参兼容构造器 (加 optimizeBy 之前的既有调用形态)
        Input legacy = new Input(orders, twoVehicles(), twoDriverBindings(), Map.of(),
                planarKmLookup(), new BigDecimal("100"), coords, DEPOT[0], DEPOT[1]);
        Input explicitDistance = twoVehicleInput(orders, RouteOptimizeMode.DISTANCE);

        assertEquals(LogisticsRoutingAlgorithm.run(legacy), LogisticsRoutingAlgorithm.run(explicitDistance),
                "null mode must behave exactly like explicit DISTANCE");
    }

    // ============================================================
    // (d) 无窗口 — TIME 退化为行驶时间最小化
    // ============================================================

    @Test
    @DisplayName("无窗口 — TIME 模式退化为行驶时间最小化: 访问顺序与 DISTANCE 一致, 迟到 0")
    void noWindowTripsFallBackToTravelTimeMinimization() {
        List<OrderInput> orders = List.of(
                order("n1", "S-01", "2"),
                order("n2", "S-02", "2"),
                order("f1", "S-03", "2"));

        Result distance = LogisticsRoutingAlgorithm.run(singleVehicleInput(orders, RouteOptimizeMode.DISTANCE));
        Result time = LogisticsRoutingAlgorithm.run(singleVehicleInput(orders, RouteOptimizeMode.TIME));

        assertEquals(1, time.trips().size());
        // 行驶时间 ∝ 平面里程 → 无窗口时 TIME 与 DISTANCE 选同一条近→远最短路
        assertEquals(distance.trips().get(0).orderIdsInOrder(), time.trips().get(0).orderIdsInOrder());
        assertEquals(totalKm(distance), totalKm(time));
        assertEquals(0.0, LogisticsRoutingAlgorithm.totalLatenessMin(
                time, singleVehicleInput(orders, RouteOptimizeMode.TIME)), 1e-9);
    }

    // ============================================================
    // (e) 确定性
    // ============================================================

    @Test
    @DisplayName("确定性 — TIME 模式同输入两跑逐字段一致")
    void timeModeIsDeterministic() {
        List<OrderInput> orders = List.of(
                orderWithWindow("n1", "S-01", "2", "08:00", "08:30"),
                orderWithWindow("n2", "S-02", "2", "08:00", "08:30"),
                orderWithWindow("f1", "S-03", "2", "08:00", "08:30"));
        Input input = twoVehicleInput(orders, RouteOptimizeMode.TIME);

        assertEquals(LogisticsRoutingAlgorithm.run(input), LogisticsRoutingAlgorithm.run(input),
                "TIME optimizer path must stay fully deterministic");
    }

    // ============================================================
    // 夹具
    // ============================================================

    private static Input singleVehicleInput(List<OrderInput> orders, RouteOptimizeMode mode) {
        List<VehicleInput> vehicles = List.of(vehicle("V1"));
        Map<String, List<DriverBindingInput>> bindings = Map.of("V1", List.of(binding("D1")));
        return new Input(orders, vehicles, bindings, Map.of(), planarKmLookup(), new BigDecimal("100"),
                coordsFor(orders), DEPOT[0], DEPOT[1], mode);
    }

    private static Input twoVehicleInput(List<OrderInput> orders, RouteOptimizeMode mode) {
        return new Input(orders, twoVehicles(), twoDriverBindings(), Map.of(),
                planarKmLookup(), new BigDecimal("100"), coordsFor(orders), DEPOT[0], DEPOT[1], mode);
    }

    private static List<VehicleInput> twoVehicles() {
        return List.of(vehicle("V1"), vehicle("V2"));
    }

    private static Map<String, List<DriverBindingInput>> twoDriverBindings() {
        return Map.of("V1", List.of(binding("D1")), "V2", List.of(binding("D2")));
    }

    private static OrderInput order(String id, String storeCode, String vol) {
        return new OrderInput(id, storeCode, "AREA-X", new BigDecimal(vol), new BigDecimal("100"), null, null);
    }

    private static OrderInput orderWithWindow(String id, String storeCode, String vol, String start, String end) {
        return new OrderInput(id, storeCode, "AREA-X", new BigDecimal(vol), new BigDecimal("100"), start, end);
    }

    private static VehicleInput vehicle(String id) {
        return new VehicleInput(id, new BigDecimal("10"), new BigDecimal("5000"),
                new LinkedHashSet<>(Set.of("AREA-X")), null, null);
    }

    private static DriverBindingInput binding(String driverId) {
        return new DriverBindingInput(driverId, DriverRole.PRIMARY, null, null, 0);
    }

    /** orderId -> {lng, lat} (mirror service 层 coordsByOrderId 构造)。 */
    private static Map<String, double[]> coordsFor(List<OrderInput> orders) {
        Map<String, double[]> coords = new HashMap<>();
        for (OrderInput o : orders) {
            coords.put(o.orderId(), STORE_COORDS.get(o.storeCode()));
        }
        return coords;
    }

    /** 任意两点 (DEPOT/门店) 的合成 km lookup — 平面近似 × 111, 全对可查 (无缺边噪音)。 */
    private static LogisticsRoutingAlgorithm.DistanceLookup planarKmLookup() {
        return (from, to) -> {
            double[] f = STORE_COORDS.get(from);
            double[] t = STORE_COORDS.get(to);
            if (f == null || t == null) {
                return null;
            }
            double meanLatRad = Math.toRadians((f[1] + t[1]) / 2.0);
            double dx = (t[0] - f[0]) * Math.cos(meanLatRad);
            double dy = t[1] - f[1];
            return BigDecimal.valueOf(Math.sqrt(dx * dx + dy * dy) * 111.0)
                    .setScale(2, RoundingMode.HALF_UP);
        };
    }

    private static BigDecimal totalKm(Result result) {
        return result.trips().stream()
                .map(TripResult::totalDistanceKm)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
