package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档4 — 多趟排班 (车回仓补货再出发) gate 单测。
 *
 * <p>核心被测行为:
 * <ol>
 *   <li>溢出获救 — 区域组超容量装出第 2 箱且无第二辆车: 今天诚实落 NEEDS_VEHICLE;
 *       档4 在司机班次允许时把它排成同一辆车的第 2 趟 (回仓 + 20min 装货后出发)。</li>
 *   <li>班次太短 — 第 2 趟回仓会超过司机班次结束 → 不排 (保持 NEEDS_VEHICLE, 诚实)。</li>
 *   <li>迟回仓标记 — 单趟太长, 回仓晚于班次结束 → lateReturn=true (车次仍 DRAFT, 只标不瞒)。</li>
 *   <li>无坐标门禁 — DISTANCE 无坐标路径逐字段保持今天行为 (时刻字段全 null, 溢出箱仍 NEEDS_VEHICLE)。</li>
 *   <li>窗口感知出发 — 第 2 趟订单窗口 10:00 起: 出发 = 窗口开始 − 首段行驶 (回仓装货完在仓等窗口,
 *       不在门店干等)。</li>
 *   <li>确定性 — 多趟路径同输入两跑逐字段一致。</li>
 * </ol>
 *
 * <p>夹具几何: DEPOT (120.00, 31.00), 同纬度东向一字排开 S-01..S-04 (0.02° 间隔) + 远端 S-09 (0.30°)。
 * 1° ≈ 95.1 km (cos 31° 修正), 28 km/h → 0.02° ≈ 4.08 分钟, 0.30° 单程 ≈ 61 分钟。
 */
@DisplayName("LogisticsRoutingAlgorithm — 档4 多趟排班 (回仓补货再出发) gate")
class LogisticsRoutingMultiTripTest {

    private static final double[] DEPOT = {120.00, 31.00};

    private static final Map<String, double[]> STORE_COORDS = Map.of(
            "DEPOT", DEPOT,
            "S-01", new double[] {120.02, 31.00},
            "S-02", new double[] {120.04, 31.00},
            "S-03", new double[] {120.06, 31.00},
            "S-04", new double[] {120.08, 31.00},
            "S-09", new double[] {120.30, 31.00});

    /** 溢出夹具: 4 单 × 5cbm, V1 容量 10 → 装箱 [o1,o2] + [o3,o4], 无第二辆车。 */
    private static List<OrderInput> overflowOrders() {
        return List.of(
                order("o1", "S-01", "5"),
                order("o2", "S-02", "5"),
                order("o3", "S-03", "5"),
                order("o4", "S-04", "5"));
    }

    // ============================================================
    // 1. 溢出获救 — NEEDS_VEHICLE → 同车第 2 趟
    // ============================================================

    @Test
    @DisplayName("档4 — 无第二辆车的溢出箱: 司机班次允许 → 同车第 2 趟 (回仓+20min 后出发), 不再 NEEDS_VEHICLE")
    void overflowBecomesSecondTripOfSameVehicleWhenTimeAllows() {
        Input input = singleVehicleInput(overflowOrders(), "06:00", "20:00", true);

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult trip1 = result.trips().get(0);
        TripResult trip2 = result.trips().get(1);

        // 同一辆车 + 同一司机跑两趟
        assertEquals("V1", trip1.vehicleId());
        assertEquals("V1", trip2.vehicleId());
        assertEquals("D1", trip1.driverId());
        assertEquals("D1", trip2.driverId());
        assertEquals(TripStatus.DRAFT, trip1.status());
        assertEquals(TripStatus.DRAFT, trip2.status(), "rescued overflow box must no longer be NEEDS_VEHICLE");
        assertEquals(List.of("o1", "o2"), trip1.orderIdsInOrder());
        assertEquals(List.of("o3", "o4"), trip2.orderIdsInOrder());

        // 车内趟序 + 时刻链: 第 2 趟出发 = 第 1 趟回仓 + RELOAD(20min)
        assertEquals(1, trip1.vehicleTripSeq());
        assertEquals(2, trip2.vehicleTripSeq());
        assertNotNull(trip1.departMin());
        assertNotNull(trip1.returnToDepotMin());
        assertNotNull(trip2.departMin());
        assertNotNull(trip2.returnToDepotMin());
        assertEquals(360, trip1.departMin(), "trip 1 departs at driver shift start 06:00");
        assertTrue(trip1.returnToDepotMin() > trip1.departMin());
        assertEquals(trip1.returnToDepotMin() + 20, trip2.departMin(),
                "trip 2 departs exactly after return-to-depot + reload");
        assertTrue(trip2.returnToDepotMin() > trip2.departMin());

        // 班次/可用窗被尊重: 回仓在 20:00 之前, 不标迟回仓
        assertTrue(trip2.returnToDepotMin() <= 20 * 60);
        assertEquals(Boolean.FALSE, trip1.lateReturn());
        assertEquals(Boolean.FALSE, trip2.lateReturn());
        assertTrue(result.unassignedOrderIds().isEmpty());
    }

    // ============================================================
    // 2. 班次太短 — 不排第 2 趟 (诚实 NEEDS_VEHICLE)
    // ============================================================

    @Test
    @DisplayName("档4 — 司机班次 08:00-09:00 装不下第 2 趟 → 溢出箱保持 NEEDS_VEHICLE (诚实, 不排超班车次)")
    void secondTripNotScheduledWhenDriverShiftTooShort() {
        // 第 1 趟 ≈ 36 分钟 (回仓 ~08:36), +20 装货后第 2 趟回仓 ~09:29 > 班次结束 09:00 → 不可行
        Input input = singleVehicleInput(overflowOrders(), "08:00", "09:00", true);

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult trip1 = result.trips().get(0);
        TripResult trip2 = result.trips().get(1);
        assertEquals("V1", trip1.vehicleId());
        assertEquals(TripStatus.DRAFT, trip1.status());
        assertEquals(480, trip1.departMin());
        assertEquals(Boolean.FALSE, trip1.lateReturn(), "trip 1 alone fits the shift");

        assertNull(trip2.vehicleId(), "no time for a 2nd trip → stays honestly unassigned");
        assertEquals(TripStatus.NEEDS_VEHICLE, trip2.status());
        assertNull(trip2.departMin());
        assertNull(trip2.returnToDepotMin());
        assertNull(trip2.lateReturn());
        assertNull(trip2.vehicleTripSeq());
    }

    // ============================================================
    // 3. 迟回仓标记
    // ============================================================

    @Test
    @DisplayName("档4 — 单趟回仓晚于司机班次结束 → lateReturn=true (只标不瞒, 车次仍 DRAFT)")
    void lateReturnToDepotIsFlagged() {
        // 远端 S-09 单程 ≈ 61 分钟: 08:00 出发 → 回仓 ≈ 10:12 > 班次结束 09:00
        List<OrderInput> orders = List.of(order("f1", "S-09", "5"));
        Input input = singleVehicleInput(orders, "08:00", "09:00", true);

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(1, result.trips().size());
        TripResult trip = result.trips().get(0);
        assertEquals(TripStatus.DRAFT, trip.status(), "late return is a flag, not a blocker");
        assertEquals(480, trip.departMin());
        assertNotNull(trip.returnToDepotMin());
        assertTrue(trip.returnToDepotMin() > 9 * 60,
                "return " + trip.returnToDepotMin() + " must be past shift end 540");
        assertEquals(Boolean.TRUE, trip.lateReturn());
        assertEquals(1, trip.vehicleTripSeq());
    }

    // ============================================================
    // 4. 无坐标门禁 — 今天的行为逐字段保持
    // ============================================================

    @Test
    @DisplayName("门禁 — DISTANCE 无坐标: 溢出箱仍 NEEDS_VEHICLE, 时刻字段全 null (与档4 之前行为一致)")
    void noCoordsDistancePathIsUnchanged() {
        Input input = singleVehicleInput(overflowOrders(), "06:00", "20:00", false);

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult trip1 = result.trips().get(0);
        TripResult trip2 = result.trips().get(1);
        assertEquals("V1", trip1.vehicleId());
        assertEquals(List.of("o1", "o2"), trip1.orderIdsInOrder(),
                "without coords the (area, storeCode) stable order must be preserved verbatim");
        assertNull(trip2.vehicleId());
        assertEquals(TripStatus.NEEDS_VEHICLE, trip2.status());
        for (TripResult trip : result.trips()) {
            assertNull(trip.departMin());
            assertNull(trip.returnToDepotMin());
            assertNull(trip.lateReturn());
            assertNull(trip.vehicleTripSeq());
        }
    }

    // ============================================================
    // 5. 窗口感知出发 — 第 2 趟等窗口在仓出发
    // ============================================================

    @Test
    @DisplayName("档4 — 第 2 趟订单窗口 10:00 起: 出发推迟到 窗口开始 − 首段行驶 (在仓等, 不在店门口干等)")
    void secondTripWaitsAtDepotForItsDeliveryWindow() {
        List<OrderInput> orders = List.of(
                order("o1", "S-01", "5"),
                order("o2", "S-02", "5"),
                orderWithWindow("o3", "S-03", "5", "10:00", "12:00"),
                orderWithWindow("o4", "S-04", "5", "10:00", "12:00"));
        Input input = singleVehicleInput(orders, "06:00", "20:00", true);

        Result result = LogisticsRoutingAlgorithm.run(input);

        assertEquals(2, result.trips().size());
        TripResult trip2 = result.trips().get(1);
        assertEquals("V1", trip2.vehicleId());
        assertEquals(2, trip2.vehicleTripSeq());
        // 首段 DEPOT→S-03 ≈ 12.2 分钟 → 出发 ≈ 600 − 12.2 ≈ 588 (在仓等到窗口临近才出发)
        assertNotNull(trip2.departMin());
        assertEquals(588, trip2.departMin(), 1.0);
        assertEquals(Boolean.FALSE, trip2.lateReturn());
    }

    // ============================================================
    // 6. 确定性
    // ============================================================

    @Test
    @DisplayName("确定性 — 档4 多趟路径同输入两跑逐字段一致")
    void multiTripPathIsDeterministic() {
        Input input = singleVehicleInput(overflowOrders(), "06:00", "20:00", true);

        Result r1 = LogisticsRoutingAlgorithm.run(input);
        Result r2 = LogisticsRoutingAlgorithm.run(input);

        assertEquals(r1, r2, "multi-trip scheduling must stay fully deterministic");
        // 防御: 确定性夹具确实走了多趟路径
        assertFalse(r1.trips().stream().anyMatch(t -> t.vehicleId() == null));
    }

    // ============================================================
    // 夹具
    // ============================================================

    private static Input singleVehicleInput(List<OrderInput> orders,
            String shiftStart, String shiftEnd, boolean withCoords) {
        List<VehicleInput> vehicles = List.of(new VehicleInput(
                "V1", new BigDecimal("10"), new BigDecimal("5000"),
                new LinkedHashSet<>(Set.of("AREA-X")), null, null));
        Map<String, List<DriverBindingInput>> bindings = Map.of("V1", List.of(
                new DriverBindingInput("D1", DriverRole.PRIMARY, shiftStart, shiftEnd, 0)));
        if (!withCoords) {
            return new Input(orders, vehicles, bindings, Map.of(), planarKmLookup(), new BigDecimal("100"));
        }
        return new Input(orders, vehicles, bindings, Map.of(), planarKmLookup(), new BigDecimal("100"),
                coordsFor(orders), DEPOT[0], DEPOT[1], RouteOptimizeMode.DISTANCE);
    }

    private static OrderInput order(String id, String storeCode, String vol) {
        return new OrderInput(id, storeCode, "AREA-X", new BigDecimal(vol), new BigDecimal("100"), null, null);
    }

    private static OrderInput orderWithWindow(String id, String storeCode, String vol, String start, String end) {
        return new OrderInput(id, storeCode, "AREA-X", new BigDecimal(vol), new BigDecimal("100"), start, end);
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
}
