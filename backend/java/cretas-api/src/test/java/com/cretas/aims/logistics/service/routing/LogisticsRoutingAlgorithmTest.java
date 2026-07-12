package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.DriverBindingInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.DriverInfo;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Input;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.OrderInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Result;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.TripResult;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.VehicleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 3 排线算法 — 精确性 gate 单测。逐条对照
 * {@code docs/superpowers/specs/2026-07-11-logistics-routing-algorithm-precision.md} §7:
 * <ol>
 *   <li>对照测试 — 与 {@code routeEngine.spec.ts} 等价用例(见 "TS parity: ..." 组)</li>
 *   <li>确定性测试 — 同输入跑 2 次逐字段一致</li>
 *   <li>硬约束穷举 — 每条硬约束一个失败用例</li>
 *   <li>targetLoad 差异 — 70/88/98 产生可验证不同的车次数</li>
 *   <li>诚实降级 — 缺一条边 → NEEDS_ROUTE_DATA + totalDistanceKm=0, 断言无伪造公里数</li>
 * </ol>
 *
 * <p>纯算法测试 (无 Spring / 无 DB) — {@link LogisticsRoutingAlgorithm} 是无状态纯函数,
 * 直接构造 {@link Input} 驱动, 比 {@code @DataJpaTest} 更快更精确。持久化映射由
 * {@code LogisticsRoutingServiceTest} (@DataJpaTest) 单独覆盖。
 */
@DisplayName("LogisticsRoutingAlgorithm — 算法精确性 gate")
class LogisticsRoutingAlgorithmTest {

    private static final BigDecimal HUGE = new BigDecimal("100000");

    // ============================================================
    // 1. 确定性
    // ============================================================

    @Test
    @DisplayName("gate #2 确定性 — 同输入跑2次, trip_no/stop序/距离scale逐字段一致")
    void deterministicAcrossRepeatedRuns() {
        Input input = complexScenario();

        Result r1 = LogisticsRoutingAlgorithm.run(input);
        Result r2 = LogisticsRoutingAlgorithm.run(input);

        assertEquals(r1, r2, "same input must produce byte-identical Result across runs");
        assertEquals(r1.trips().size(), r2.trips().size());
        for (int i = 0; i < r1.trips().size(); i++) {
            TripResult t1 = r1.trips().get(i);
            TripResult t2 = r2.trips().get(i);
            assertEquals(t1.tripNo(), t2.tripNo(), "trip_no must match");
            assertEquals(t1.orderIdsInOrder(), t2.orderIdsInOrder(), "stop sequence must match");
            assertEquals(t1.totalDistanceKm(), t2.totalDistanceKm(), "distance (incl. scale) must match");
            assertEquals(t1.totalDistanceKm().scale(), t2.totalDistanceKm().scale(), "BigDecimal scale must match");
            assertEquals(t1.status(), t2.status());
            assertEquals(t1.vehicleId(), t2.vehicleId());
            assertEquals(t1.driverId(), t2.driverId());
        }
        assertEquals(r1.unassignedOrderIds(), r2.unassignedOrderIds());
    }

    private Input complexScenario() {
        List<OrderInput> orders = List.of(
                order("o1", "S-001", "AREA-A", "3", "300"),
                order("o2", "S-002", "AREA-A", "3", "300"),
                order("o3", "S-003", "AREA-A", "3", "300"), // triggers hard-cap split within AREA-A
                order("o4", "S-004", "AREA-B", "2", "200"), // no distance edge for DEPOT->S-004 → NEEDS_ROUTE_DATA
                order("o5", "S-005", "AREA-C", "999", "1")  // volume over any vehicle capacity → unassigned
        );
        List<VehicleInput> vehicles = List.of(
                vehicle("V1", "8", "5000", "AREA-A"),
                vehicle("V2", "8", "5000", "AREA-A", "AREA-B"),
                vehicle("V3", "8", "5000", "AREA-B")
        );
        Map<String, List<DriverBindingInput>> bindings = new HashMap<>();
        bindings.put("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0)));
        bindings.put("V2", List.of(new DriverBindingInput("D2", DriverRole.PRIMARY, null, null, 0)));
        bindings.put("V3", List.of(new DriverBindingInput("D3", DriverRole.PRIMARY, null, null, 0)));

        Map<String, BigDecimal> registry = new LinkedHashMap<>();
        registry.put("DEPOT->S-001", bd("10"));
        registry.put("S-001->S-002", bd("5"));
        registry.put("S-002->S-003", bd("4"));
        registry.put("DEPOT->S-003", bd("9"));
        // DEPOT->S-004 intentionally missing → honest degrade for AREA-B trip

        return new Input(orders, vehicles, bindings, Map.of(), lookup(registry), bd("88"));
    }

    // ============================================================
    // 2. 硬约束穷举
    // ============================================================

    @Test
    @DisplayName("硬约束1 — 单店体积超车辆capacityCbm → unassigned")
    void overVolumeUnassigned() {
        Input input = new Input(
                List.of(order("o1", "S-001", "AREA-A", "12", "100")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(Map.of()), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertTrue(result.trips().isEmpty());
        assertEquals(List.of("o1"), result.unassignedOrderIds());
    }

    @Test
    @DisplayName("硬约束2 — 单店重量超车辆maxWeightKg → unassigned (Java对routeEngine.ts的增强)")
    void overWeightUnassigned() {
        Input input = new Input(
                List.of(order("o1", "S-001", "AREA-A", "1", "6000")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(Map.of()), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertTrue(result.trips().isEmpty());
        assertEquals(List.of("o1"), result.unassignedOrderIds());
    }

    @Test
    @DisplayName("硬约束5 — 无可用第二车 → NEEDS_VEHICLE, vehicleId=null (车辆冲突/无重复占用)")
    void missingVehicleNeedsVehicle() {
        Map<String, BigDecimal> registry = Map.of(
                "DEPOT->S-001", bd("10"), "S-001->S-002", bd("5"), "DEPOT->S-002", bd("8"));
        Input input = new Input(
                List.of(
                        order("o1", "S-001", "AREA-A", "6", "100"),
                        order("o2", "S-002", "AREA-A", "6", "100")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")), // single vehicle → overflow box has no candidate
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("50"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        assertEquals("V1", result.trips().get(0).vehicleId());
        assertNull(result.trips().get(1).vehicleId());
        assertEquals(TripStatus.NEEDS_VEHICLE, result.trips().get(1).status());
        assertTrue(result.unassignedOrderIds().isEmpty(), "orders are routed (to a needs_vehicle trip), not silently dropped");
    }

    @Test
    @DisplayName("硬约束7 — 车已定但无司机覆盖 → NEEDS_DRIVER, driverId=null")
    void missingDriverNeedsDriver() {
        Map<String, BigDecimal> registry = Map.of("DEPOT->S-001", bd("10"));
        Input input = new Input(
                List.of(order("o1", "S-001", "AREA-A", "1", "100")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")),
                Map.of(), // no driver bindings at all for V1
                Map.of(), lookup(registry), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(1, result.trips().size());
        TripResult trip = result.trips().get(0);
        assertEquals("V1", trip.vehicleId());
        assertNull(trip.driverId());
        assertEquals(TripStatus.NEEDS_DRIVER, trip.status());
    }

    @Test
    @DisplayName("硬约束7b — 主司机班次不覆盖但备份司机覆盖 → 退BACKUP选中")
    void fallsBackToBackupDriverWhenPrimaryShiftDoesNotCover() {
        Map<String, BigDecimal> registry = Map.of("DEPOT->S-001", bd("10"));
        Input input = new Input(
                List.of(orderWithWindow("o1", "S-001", "AREA-A", "1", "100", "09:00", "10:00")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")),
                Map.of("V1", List.of(
                        new DriverBindingInput("D1-PRIMARY", DriverRole.PRIMARY, "12:00", "18:00", 0), // does not cover 09:00-10:00
                        new DriverBindingInput("D2-BACKUP", DriverRole.BACKUP, "08:00", "20:00", 1))),
                Map.of(), lookup(registry), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals("D2-BACKUP", result.trips().get(0).driverId());
        assertEquals(TripStatus.DRAFT, result.trips().get(0).status());
    }

    @Test
    @DisplayName("硬约束6 — 同一司机重叠车次不可重复占用, 无空闲候选则第二车次 NEEDS_DRIVER")
    void driverDoubleBookingRejected() {
        Map<String, BigDecimal> registry = Map.of(
                "DEPOT->S-001", bd("10"), "DEPOT->S-002", bd("11"));
        // 2 组各自1单, 各自不同车辆, 但两车共用同一个司机 D1 (仅有的司机) — 第二车次不能重复占用 D1
        Input input = new Input(
                List.of(
                        orderWithWindow("o1", "S-001", "AREA-A", "1", "100", "09:00", "10:00"),
                        orderWithWindow("o2", "S-002", "AREA-B", "1", "100", "09:30", "10:30")), // overlaps o1's window
                List.of(
                        vehicle("V1", "10", "5000", "AREA-A"),
                        vehicle("V2", "10", "5000", "AREA-B")),
                Map.of(
                        "V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0)),
                        "V2", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult trip1 = result.trips().stream().filter(t -> "V1".equals(t.vehicleId())).findFirst().orElseThrow();
        TripResult trip2 = result.trips().stream().filter(t -> "V2".equals(t.vehicleId())).findFirst().orElseThrow();
        assertEquals("D1", trip1.driverId(), "first-processed trip (S-001, areaCode ASC) claims D1");
        assertNull(trip2.driverId(), "second trip cannot double-book D1 on an overlapping window");
        assertEquals(TripStatus.NEEDS_DRIVER, trip2.status());
    }

    @Test
    @DisplayName("硬约束8/诚实降级 — 缺一条距离边 → NEEDS_ROUTE_DATA, totalDistanceKm=0 (绝无伪造公里数)")
    void missingDistanceEdgeYieldsHonestDegradeZeroKm() {
        Input input = new Input(
                List.of(order("o1", "S-001", "AREA-A", "1", "100")),
                List.of(vehicle("V1", "10", "5000", "AREA-A")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(Map.of()) /* no edges maintained at all */, bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        TripResult trip = result.trips().get(0);
        assertEquals(TripStatus.NEEDS_ROUTE_DATA, trip.status());
        assertEquals(0, trip.totalDistanceKm().compareTo(BigDecimal.ZERO), "must be exactly zero, never a fabricated/guessed distance");
        assertTrue(trip.segmentDistances().isEmpty(), "no partial/fabricated distances leak through on honest degrade");
    }

    @Test
    @DisplayName("硬约束3/单店超硬容量 — 唯一匹配车辆容量不足 → unassigned (不拆单)")
    void singleStoreOverAllVehicleCapacityUnassigned() {
        // TS parity: routeEngine.spec.ts "allows one target overflow but rejects a store over hard cap"
        Map<String, BigDecimal> registry = Map.of("DEPOT->A", bd("10"), "DEPOT->B", bd("14"), "A->B", bd("12"));
        Input input = new Input(
                List.of(order("A", "A", "吴中", "9", "900"), order("B", "B", "吴中", "11", "1100")),
                List.of(vehicle("V1", "10", "3000", "吴中")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("80"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(List.of(List.of("A")), result.trips().stream().map(TripResult::orderIdsInOrder).toList());
        assertEquals(List.of("B"), result.unassignedOrderIds());
    }

    // ============================================================
    // 3. targetLoad 差异 (70/88/98 → 可验证不同车次数)
    // ============================================================

    @Test
    @DisplayName("装载率目标不硬拆同车多趟 — 单车必送负载恒装到硬容量最少箱数 (Steve 2026-07-13)")
    void targetLoadPercentageDoesNotForceExtraSameVehicleTrips() {
        Map<String, BigDecimal> registry = new LinkedHashMap<>();
        registry.put("DEPOT->S-001", bd("5"));
        registry.put("S-001->S-002", bd("3"));
        registry.put("S-002->S-003", bd("3"));
        registry.put("S-003->S-004", bd("3"));
        registry.put("S-004->S-005", bd("3"));
        registry.put("S-005->S-006", bd("3"));
        registry.put("DEPOT->S-002", bd("6"));
        registry.put("DEPOT->S-003", bd("7"));
        registry.put("DEPOT->S-004", bd("8"));
        registry.put("DEPOT->S-005", bd("9"));
        registry.put("DEPOT->S-006", bd("10"));

        List<OrderInput> orders = List.of(
                order("o1", "S-001", "AREA-A", "2.5", "100"),
                order("o2", "S-002", "AREA-A", "2.5", "100"),
                order("o3", "S-003", "AREA-A", "2.5", "100"),
                order("o4", "S-004", "AREA-A", "2.5", "100"),
                order("o5", "S-005", "AREA-A", "2.5", "100"),
                order("o6", "S-006", "AREA-A", "2.5", "100"));
        List<VehicleInput> vehicles = List.of(vehicle("V1", "10", "5000", "AREA-A"));
        Map<String, List<DriverBindingInput>> bindings =
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0)));

        Result at70 = LogisticsRoutingAlgorithm.run(new Input(orders, vehicles, bindings, Map.of(), lookup(registry), bd("70")));
        Result at88 = LogisticsRoutingAlgorithm.run(new Input(orders, vehicles, bindings, Map.of(), lookup(registry), bd("88")));
        Result at98 = LogisticsRoutingAlgorithm.run(new Input(orders, vehicles, bindings, Map.of(), lookup(registry), bd("98")));

        // 装载率目标不再硬拆同车多趟 (Steve 2026-07-13): 6×2.5=15m³ 必送, V1 cap 10 → 硬容量最少箱数
        // = ceil(15/10) = 2, 与装载率目标无关。旧行为 70% 软目标把它硬拆成 3 趟 —— 那正是被修掉的空趟浪费。
        assertEquals(2, at70.trips().size(), "装载率目标不再强制多开箱: 15m³/10m³ 恒 2 趟 (旧 70% 曾拆 3 趟)");
        assertEquals(2, at88.trips().size());
        assertEquals(2, at98.trips().size());
        assertEquals(at70.trips().size(), at88.trips().size(), "趟数不再随装载率目标变");
        assertEquals(
                at70.trips().stream().map(TripResult::orderIdsInOrder).toList(),
                at98.trips().stream().map(TripResult::orderIdsInOrder).toList(),
                "同一必送负载装到最少箱数 → 装箱结果与装载率目标无关");
    }

    @Test
    @DisplayName("targetLoadPct 边界校验 — 0 / 负数 / >100 / null 抛异常 (对齐 TS RangeError)")
    void rejectsInvalidTargetLoadPct() {
        Input base = new Input(List.of(), List.of(), Map.of(), Map.of(), lookup(Map.of()), null);
        assertThrows(IllegalArgumentException.class, () -> LogisticsRoutingAlgorithm.run(withTarget(base, "0")));
        assertThrows(IllegalArgumentException.class, () -> LogisticsRoutingAlgorithm.run(withTarget(base, "-1")));
        assertThrows(IllegalArgumentException.class, () -> LogisticsRoutingAlgorithm.run(withTarget(base, "101")));
        assertThrows(IllegalArgumentException.class, () -> LogisticsRoutingAlgorithm.run(base));
    }

    private static Input withTarget(Input base, String pct) {
        return new Input(base.orders(), base.vehicles(), base.driverBindingsByVehicleId(),
                base.driverInfoById(), base.distanceLookup(), bd(pct));
    }

    // ============================================================
    // 4. 每单精确一次分配 (无重复/无静默丢单)
    // ============================================================

    @Test
    @DisplayName("gate — 每个 order 恰好分配一次或明确 unassigned, 无重复无静默丢单")
    void everyOrderAssignedExactlyOnceOrUnassigned() {
        Input input = complexScenario();
        Result result = LogisticsRoutingAlgorithm.run(input);

        List<String> allAssigned = result.trips().stream()
                .flatMap(t -> t.orderIdsInOrder().stream())
                .toList();
        Set<String> allAssignedSet = new LinkedHashSet<>(allAssigned);
        assertEquals(allAssigned.size(), allAssignedSet.size(), "no order appears twice across trips");

        Set<String> union = new LinkedHashSet<>(allAssigned);
        union.addAll(result.unassignedOrderIds());
        Set<String> expected = input.orders().stream().map(OrderInput::orderId).collect(Collectors.toSet());
        assertEquals(expected, union, "every input order must be either assigned or explicitly unassigned");

        Set<String> assignedAndUnassignedOverlap = new LinkedHashSet<>(allAssignedSet);
        assignedAndUnassignedOverlap.retainAll(result.unassignedOrderIds());
        assertTrue(assignedAndUnassignedOverlap.isEmpty(), "an order cannot be both assigned and unassigned");
    }

    // ============================================================
    // 5. TS parity — 对照 web-admin/.../routeEngine.spec.ts
    // ============================================================

    @Test
    @DisplayName("TS parity: creates a real next trip and removes overflow from the first trip")
    void tsParity_overflowCreatesSecondTripNeedsVehicle() {
        Map<String, BigDecimal> registry = Map.of(
                "DEPOT->A", bd("10"), "DEPOT->B", bd("14"), "A->B", bd("12"),
                "B->C", bd("9"), "DEPOT->C", bd("18"));
        Input input = new Input(
                List.of(
                        order("A", "A", "吴中", "4", "400"),
                        order("B", "B", "吴中", "4", "400"),
                        order("C", "C", "吴中", "3", "300")),
                List.of(vehicle("V1", "10", "3000", "吴中")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("88"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        assertEquals(List.of("A", "B"), result.trips().get(0).orderIdsInOrder());
        assertEquals(List.of("C"), result.trips().get(1).orderIdsInOrder());
        assertEquals(0, result.trips().get(0).totalVolumeCbm().compareTo(bd("8")));
        assertNull(result.trips().get(1).vehicleId());
        assertEquals(TripStatus.NEEDS_VEHICLE, result.trips().get(1).status());
    }

    @Test
    @DisplayName("TS parity: does not assign an overflow trip to a matching vehicle with insufficient capacity")
    void tsParity_overflowVehicleWithInsufficientCapacityStaysUnassignedVehicle() {
        // 硬容量真溢出: A+B=14m³ > V1 cap 10 → 必然 2 箱; 溢出箱 6m³ 找不到能整体接住的车 (V2 cap 3 < 6)
        // → 诚实落 NEEDS_VEHICLE (无坐标 → 档4 不救)。(装载率目标不再是拆箱原因 — 这里是硬容量真的不够。)
        Map<String, BigDecimal> registry = Map.of("DEPOT->A", bd("10"), "DEPOT->B", bd("14"), "A->B", bd("12"));
        Input input = new Input(
                List.of(order("A", "A", "吴中", "8", "800"), order("B", "B", "吴中", "6", "600")),
                List.of(vehicle("V1", "10", "3000", "吴中"), vehicle("V2", "3", "3000", "吴中")),
                Map.of(
                        "V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0)),
                        "V2", List.of(new DriverBindingInput("D2", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("60"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(Arrays.asList("V1", null), result.trips().stream().map(TripResult::vehicleId).toList());
        assertEquals(0, result.trips().get(1).totalVolumeCbm().compareTo(bd("6")));
    }

    @Test
    @DisplayName("TS parity: reserves every non-empty group primary before assigning overflow vehicles")
    void tsParity_groupPrimaryReservedBeforeOverflowAssignment() {
        Input input = new Input(
                List.of(
                        order("A1", "A1", "AREA-A", "6", "600"),
                        order("A2", "A2", "AREA-A", "6", "600"),
                        order("B1", "B1", "AREA-B", "2", "200")),
                List.of(vehicle("V1", "10", "3000", "AREA-A"), vehicle("V2", "10", "3000", "AREA-A", "AREA-B")),
                Map.of(
                        "V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0)),
                        "V2", List.of(new DriverBindingInput("D2", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(Map.of()) /* TS test uses roadSegments: {} */, bd("60"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(Arrays.asList("V1", null, "V2"), result.trips().stream().map(TripResult::vehicleId).toList());
        assertEquals(1, result.trips().stream().filter(t -> "V2".equals(t.vehicleId())).count());
    }

    @Test
    @DisplayName("TS parity: never packs a valid target load beyond the primary vehicle hard cap")
    void tsParity_neverExceedsHardCapEvenAtFullTarget() {
        Map<String, BigDecimal> registry = Map.of("DEPOT->A", bd("10"), "DEPOT->B", bd("14"), "A->B", bd("12"));
        Input input = new Input(
                List.of(order("A", "A", "吴中", "6", "600"), order("B", "B", "吴中", "5", "500")),
                List.of(vehicle("V1", "10", "3000", "吴中")),
                Map.of("V1", List.of(new DriverBindingInput("D1", DriverRole.PRIMARY, null, null, 0))),
                Map.of(), lookup(registry), bd("100"));

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(List.of(List.of("A"), List.of("B")), result.trips().stream().map(TripResult::orderIdsInOrder).toList());
        assertTrue(result.trips().stream().allMatch(t -> t.totalVolumeCbm().compareTo(bd("10")) <= 0));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static OrderInput order(String id, String storeCode, String area, String vol, String weight) {
        return new OrderInput(id, storeCode, area, bd(vol), bd(weight), null, null);
    }

    private static OrderInput orderWithWindow(String id, String storeCode, String area, String vol, String weight,
                                               String start, String end) {
        return new OrderInput(id, storeCode, area, bd(vol), bd(weight), start, end);
    }

    private static VehicleInput vehicle(String id, String capacity, String maxWeight, String... areas) {
        return new VehicleInput(id, bd(capacity), bd(maxWeight), new LinkedHashSet<>(List.of(areas)), null, null);
    }

    /** {@code Map<"from->to", km>} → {@link LogisticsRoutingAlgorithm.DistanceLookup} (2-arg, not a bare Map::get). */
    private static LogisticsRoutingAlgorithm.DistanceLookup lookup(Map<String, BigDecimal> registry) {
        return (from, to) -> registry.get(from + "->" + to);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
