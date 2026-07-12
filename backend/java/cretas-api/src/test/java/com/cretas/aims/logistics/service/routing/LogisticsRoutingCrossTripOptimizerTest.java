package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.TripStatus;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档2 — Step C2 跨车次局部搜索 (段迁移 Or-opt + 单单交换) gate 单测。
 *
 * <p>核心被测行为:
 * <ol>
 *   <li>折返拆分 — Step A「区域→首个匹配车辆」把两个不相邻区域塞给一辆车 → 优化器把整段
 *       搬到空闲车, 全局总里程严格变短。</li>
 *   <li>容量顶满时的单单交换 — 迁移不可行, 只有交换能纠正地理错配。</li>
 *   <li>门禁: 无坐标 → 完全跳过, 输出与档1 贪心逐字段一致 (既有 23 个无坐标用例的隐式回归)。</li>
 *   <li>区域硬约束: 目标车 serviceAreas 不覆盖 → 有里程收益也不动。</li>
 *   <li>永不更差: 优化组合导致 NEEDS_DRIVER 恶化 → 回退 seed。</li>
 *   <li>确定性: 同输入两跑逐字段一致。</li>
 * </ol>
 */
@DisplayName("LogisticsRoutingAlgorithm — 档2 跨车次优化 gate")
class LogisticsRoutingCrossTripOptimizerTest {

    private static final double[] DEPOT = {120.00, 31.00};

    // 东簇 (DEPOT 以东) / 西簇 (DEPOT 以西) — 折返场景的两个不相邻地理簇。
    // 簇距 DEPOT ±0.80°(≈76km): 一车吃两簇的折返 vs 拆成两车, 里程差 ≈60km > 单车固定成本当量(40km, T3),
    // 故拆分在经济上划算 → 优化器应拆 (若簇很近, 省的里程 < 单车当量, T3 会保留 1 辆更满的车, 见算法 VEHICLE_FIXED_COST_KM)。
    private static final Map<String, double[]> STORE_COORDS = Map.of(
            "DEPOT", DEPOT,
            "S-A1", new double[] {120.80, 31.00},
            "S-A2", new double[] {120.82, 31.00},
            "S-B1", new double[] {119.20, 31.00},
            "S-B2", new double[] {119.18, 31.00});

    // ============================================================
    // 1. 折返拆分 — 整段搬到空闲车
    // ============================================================

    @Test
    @DisplayName("档2 — 一车吃东西两簇的折返车次被拆到空闲车, 总里程严格变短")
    void zigzagTripSplitsAcrossIdleVehicle() {
        Input withCoords = zigzagInput(true, Set.of("AREA-E", "AREA-W"), "08:00", "20:00", null, null);
        Input withoutCoords = zigzagInput(false, Set.of("AREA-E", "AREA-W"), "08:00", "20:00", null, null);

        Result seed = LogisticsRoutingAlgorithm.run(withoutCoords);
        Result optimized = LogisticsRoutingAlgorithm.run(withCoords);

        // seed (档1 贪心): V1 一车吃 4 单折返, V2 闲置
        assertEquals(1, seed.trips().size());
        assertEquals("V1", seed.trips().get(0).vehicleId());

        // 档2: 拆成 2 个车次, 每车次一个地理簇
        assertEquals(2, optimized.trips().size());
        Set<Set<String>> tripOrderSets = optimized.trips().stream()
                .map(t -> Set.copyOf(t.orderIdsInOrder()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("a1", "a2"), Set.of("b1", "b2")), tripOrderSets,
                "each trip must hold exactly one geographic cluster");
        assertEquals(Set.of("V1", "V2"),
                optimized.trips().stream().map(TripResult::vehicleId).collect(Collectors.toSet()));

        // 总里程严格变短 (真实 lookup km, 非平面近似)
        BigDecimal seedKm = totalKm(seed);
        BigDecimal optimizedKm = totalKm(optimized);
        assertTrue(optimizedKm.compareTo(seedKm) < 0,
                "optimized total km " + optimizedKm + " must be strictly less than greedy " + seedKm);

        // 硬约束保持: 每车次体积不超 目标装载率 上限, 司机都分到 → DRAFT
        for (TripResult trip : optimized.trips()) {
            assertTrue(trip.totalVolumeCbm().compareTo(new BigDecimal("8.8")) <= 0,
                    "trip volume must respect capacity × targetLoadPct");
            assertEquals(TripStatus.DRAFT, trip.status());
        }
        assertTrue(optimized.unassignedOrderIds().isEmpty());
    }

    // ============================================================
    // 2. 容量顶满 → 只有交换可行
    // ============================================================

    @Test
    @DisplayName("档2 — 两车都装满时, 单单交换纠正东西簇错配 (迁移不可行)")
    void swapFixesCrossClusterAssignmentWhenCapacityTight() {
        // 同区域 AREA-X, storeCode 排序把东西簇交错装箱: box1=[东S-01,西S-02], box2=[东S-03,西S-04]
        Map<String, double[]> stores = Map.of(
                "DEPOT", DEPOT,
                "S-01", new double[] {120.10, 31.00},
                "S-02", new double[] {119.90, 31.00},
                "S-03", new double[] {120.12, 31.00},
                "S-04", new double[] {119.88, 31.00});
        List<OrderInput> orders = List.of(
                order("o1", "S-01", "AREA-X", "5"),
                order("o2", "S-02", "AREA-X", "5"),
                order("o3", "S-03", "AREA-X", "5"),
                order("o4", "S-04", "AREA-X", "5"));
        List<VehicleInput> vehicles = List.of(
                vehicle("V1", "10", Set.of("AREA-X")),
                vehicle("V2", "10", Set.of("AREA-X")));
        Map<String, List<DriverBindingInput>> bindings = Map.of(
                "V1", List.of(binding("D1", null, null)),
                "V2", List.of(binding("D2", null, null)));
        Map<String, double[]> coords = coordsFor(orders, stores);

        Result seed = LogisticsRoutingAlgorithm.run(new Input(
                orders, vehicles, bindings, Map.of(), planarKmLookup(stores), new BigDecimal("100")));
        Result optimized = LogisticsRoutingAlgorithm.run(new Input(
                orders, vehicles, bindings, Map.of(), planarKmLookup(stores), new BigDecimal("100"),
                coords, DEPOT[0], DEPOT[1]));

        // seed: 交错装箱 (每箱一东一西, 两车都折返)
        assertEquals(List.of(Set.of("o1", "o2"), Set.of("o3", "o4")),
                seed.trips().stream().map(t -> Set.copyOf(t.orderIdsInOrder())).toList());

        // 档2: 交换后每车次单簇 (容量 5+5=10 顶满, 任何迁移都超容 → 只有交换可行)
        assertEquals(2, optimized.trips().size());
        Set<Set<String>> tripOrderSets = optimized.trips().stream()
                .map(t -> Set.copyOf(t.orderIdsInOrder()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("o1", "o3"), Set.of("o2", "o4")), tripOrderSets,
                "swap must regroup orders into single-cluster trips");
        assertTrue(totalKm(optimized).compareTo(totalKm(seed)) < 0);
        for (TripResult trip : optimized.trips()) {
            assertTrue(trip.totalVolumeCbm().compareTo(new BigDecimal("10")) <= 0, "hard capacity respected");
        }
    }

    // ============================================================
    // 3. 门禁 — 无坐标完全跳过 (档1 行为不变)
    // ============================================================

    @Test
    @DisplayName("门禁 — 无坐标 → 优化器完全跳过, 输出与档1 贪心一致 (1 车折返, V2 闲置)")
    void withoutCoordsKeepsLegacyGreedyOutput() {
        Result result = LogisticsRoutingAlgorithm.run(
                zigzagInput(false, Set.of("AREA-E", "AREA-W"), "08:00", "20:00", null, null));

        assertEquals(1, result.trips().size(), "greedy area-first binding keeps the single zig-zag trip");
        assertEquals("V1", result.trips().get(0).vehicleId());
        assertEquals(List.of("a1", "a2", "b1", "b2"), result.trips().get(0).orderIdsInOrder(),
                "without coords the (area, storeCode) stable order must be preserved verbatim");
    }

    // ============================================================
    // 4. 区域硬约束
    // ============================================================

    @Test
    @DisplayName("档2 — 目标车 serviceAreas 不覆盖 → 有里程收益也绝不搬 (区域保持硬约束)")
    void areaHardConstraintBlocksOtherwiseImprovingMove() {
        // V2 只跑 AREA-N — 与两簇订单都不匹配 → 无任何可行移动 → 保持 seed 单车折返
        Input input = zigzagInputWithV2Areas(Set.of("AREA-N"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(1, result.trips().size());
        assertEquals("V1", result.trips().get(0).vehicleId());
        assertEquals(4, result.trips().get(0).orderIdsInOrder().size());
    }

    // ============================================================
    // 5. 永不更差 — NEEDS_DRIVER 恶化回退 seed
    // ============================================================

    @Test
    @DisplayName("档2 — 优化组合让新车分不到班次覆盖的司机 (NEEDS_DRIVER 恶化) → 回退 seed")
    void driverShiftRegressionFallsBackToSeed() {
        // b 簇订单窗口 09:00-10:00; V2 只跑 AREA-W (只可能接收 b 簇), 但其唯一司机 D2 班次 12:00-18:00
        // 不覆盖 → 优化组合 NEEDS_DRIVER=1 > seed 0 → pickBetterHonestly 回退 seed。
        Input input = zigzagInput(true, Set.of("AREA-W"), "08:00", "20:00", "12:00", "18:00");

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(1, result.trips().size(), "must fall back to the seed single trip");
        TripResult trip = result.trips().get(0);
        assertEquals("V1", trip.vehicleId());
        assertEquals("D1", trip.driverId());
        assertEquals(TripStatus.DRAFT, trip.status());
        assertEquals(4, trip.orderIdsInOrder().size());
    }

    @Test
    @DisplayName("对照 — 同场景 V2 司机班次覆盖时, 拆分正常发生 (证明上一测试拦的是司机门禁)")
    void sameScenarioSplitsWhenDriverShiftCovers() {
        Input input = zigzagInput(true, Set.of("AREA-W"), "08:00", "20:00", "08:00", "20:00");

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult v2Trip = result.trips().stream()
                .filter(t -> "V2".equals(t.vehicleId())).findFirst().orElseThrow();
        assertEquals(Set.of("b1", "b2"), Set.copyOf(v2Trip.orderIdsInOrder()));
        assertEquals("D2", v2Trip.driverId());
        assertEquals(TripStatus.DRAFT, v2Trip.status());
    }

    // ============================================================
    // 6. 确定性
    // ============================================================

    @Test
    @DisplayName("确定性 — 档2 优化路径同输入两跑逐字段一致")
    void deterministicAcrossRepeatedRuns() {
        Input input = zigzagInput(true, Set.of("AREA-E", "AREA-W"), "08:00", "20:00", null, null);

        Result r1 = LogisticsRoutingAlgorithm.run(input);
        Result r2 = LogisticsRoutingAlgorithm.run(input);

        assertEquals(r1, r2, "optimizer path must stay fully deterministic");
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 折返场景: 东簇 a1/a2 (AREA-E) + 西簇 b1/b2 (AREA-W), 各 2cbm;
     * V1 跑两区 (Step A 首选, 吃下全部 4 单), V2 空闲。
     *
     * @param v2Areas   V2 服务区域 (控制优化器能否把某簇搬给它)
     * @param d1Start/d1End V1 司机 D1 班次; d2Start/d2End V2 司机 D2 班次 (null = 无限制)
     */
    private static Input zigzagInput(boolean withCoords, Set<String> v2Areas,
            String d1Start, String d1End, String d2Start, String d2End) {
        List<OrderInput> orders = List.of(
                order("a1", "S-A1", "AREA-E", "2"),
                order("a2", "S-A2", "AREA-E", "2"),
                orderWithWindow("b1", "S-B1", "AREA-W", "2", "09:00", "10:00"),
                orderWithWindow("b2", "S-B2", "AREA-W", "2", "09:00", "10:00"));
        List<VehicleInput> vehicles = List.of(
                vehicle("V1", "10", Set.of("AREA-E", "AREA-W")),
                vehicle("V2", "10", v2Areas));
        Map<String, List<DriverBindingInput>> bindings = Map.of(
                "V1", List.of(binding("D1", d1Start, d1End)),
                "V2", List.of(binding("D2", d2Start, d2End)));
        if (!withCoords) {
            return new Input(orders, vehicles, bindings, Map.of(),
                    planarKmLookup(STORE_COORDS), new BigDecimal("88"));
        }
        return new Input(orders, vehicles, bindings, Map.of(),
                planarKmLookup(STORE_COORDS), new BigDecimal("88"),
                coordsFor(orders, STORE_COORDS), DEPOT[0], DEPOT[1]);
    }

    private static Input zigzagInputWithV2Areas(Set<String> v2Areas) {
        return zigzagInput(true, v2Areas, null, null, null, null);
    }

    private static OrderInput order(String id, String storeCode, String area, String vol) {
        return new OrderInput(id, storeCode, area, new BigDecimal(vol), new BigDecimal("100"), null, null);
    }

    private static OrderInput orderWithWindow(String id, String storeCode, String area, String vol,
            String start, String end) {
        return new OrderInput(id, storeCode, area, new BigDecimal(vol), new BigDecimal("100"), start, end);
    }

    private static VehicleInput vehicle(String id, String capacity, Set<String> areas) {
        return new VehicleInput(id, new BigDecimal(capacity), new BigDecimal("5000"),
                new LinkedHashSet<>(areas), null, null);
    }

    private static DriverBindingInput binding(String driverId, String shiftStart, String shiftEnd) {
        return new DriverBindingInput(driverId, DriverRole.PRIMARY, shiftStart, shiftEnd, 0);
    }

    /** orderId -> {lng, lat}, 由门店坐标表映射 (mirror service 层 coordsByOrderId 构造)。 */
    private static Map<String, double[]> coordsFor(List<OrderInput> orders, Map<String, double[]> stores) {
        Map<String, double[]> coords = new HashMap<>();
        for (OrderInput o : orders) {
            coords.put(o.orderId(), stores.get(o.storeCode()));
        }
        return coords;
    }

    /** 任意两点 (DEPOT/门店) 的合成 km lookup — 平面近似 × 111, 全对可查 (无缺边噪音)。 */
    private static LogisticsRoutingAlgorithm.DistanceLookup planarKmLookup(Map<String, double[]> stores) {
        return (from, to) -> {
            double[] f = stores.get(from);
            double[] t = stores.get(to);
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
