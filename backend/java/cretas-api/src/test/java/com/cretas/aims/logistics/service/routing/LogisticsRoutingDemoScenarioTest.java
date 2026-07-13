package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.DistanceLookup;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Input;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.OrderInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Result;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.TripResult;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.VehicleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 demo 场景回归 (DEMO_LOGISTICS, plan aa87eaf4, 21 门店 / 4 车 / 57.2m³ / 车队单轮 47m³ 不够)。
 *
 * <p>锁定「装载率目标不再硬拆同车多趟」修复 (Steve 2026-07-13): 88% 目标曾把 V-01 的 9.9m³ 拆成 2 趟、
 * V-02 的 29.1m³ 拆成 3 趟 (线上实测 8 趟 / 220.95km), 而物理可行是 <b>6 趟</b> (装到硬容量):
 * V-01×1(9.9≤10) + V-02×2(29.1≤30) + V-03×2(11.1>10) + V-04×1(7.1)。
 */
class LogisticsRoutingDemoScenarioTest {

    // 中心仓库 (苏州, 近似) — 趟数与仓库无关, 里程为平面近似。
    private static final double[] DEPOT = {120.68, 31.30};

    @Test
    @DisplayName("demo 场景装到硬容量 = 6 趟 (不再被 88% 目标硬拆成 8 趟)")
    void demoScenarioPacksToSixTripsNotEight() {
        List<OrderInput> orders = demoOrders();
        List<VehicleInput> vehicles = demoVehicles();
        Map<String, double[]> stores = storeCoords();

        Input input = new Input(
                orders, vehicles, Map.of(), Map.of(),
                planarKmLookup(stores), new BigDecimal("88"),
                coordsFor(orders, stores), DEPOT[0], DEPOT[1], RouteOptimizeMode.DISTANCE);

        Result result = LogisticsRoutingAlgorithm.run(input);

        // 诊断打印 (mvn -q 下可见)。
        System.out.println("=== demo scenario trips ===");
        for (TripResult t : result.trips()) {
            System.out.printf("  %-5s seq=%s vol=%s load=%s%% km=%s status=%s orders=%s%n",
                    t.vehicleId(), t.vehicleTripSeq(), t.totalVolumeCbm(), t.loadRate(),
                    t.totalDistanceKm(), t.status(), t.orderIdsInOrder());
        }
        long assignedTrips = result.trips().stream().filter(x -> x.vehicleId() != null).count();
        BigDecimal totalKm = result.trips().stream()
                .map(TripResult::totalDistanceKm).reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("assignedTrips=%d unassigned=%d totalKm=%s%n",
                assignedTrips, result.unassignedOrderIds().size(), totalKm);

        // 每辆车的趟数 = 装到硬容量的最少箱数。
        Map<String, Long> tripsByVehicle = new HashMap<>();
        for (TripResult t : result.trips()) {
            if (t.vehicleId() != null) {
                tripsByVehicle.merge(t.vehicleId(), 1L, Long::sum);
            }
        }
        assertThat(tripsByVehicle).containsEntry("V-01", 1L);  // 9.9 ≤ 10 → 1 趟 (曾 2)
        assertThat(tripsByVehicle).containsEntry("V-02", 2L);  // 29.1 → 2 趟 (曾 3)
        assertThat(tripsByVehicle).containsEntry("V-03", 2L);  // 11.1 > 10 → 2 趟 (合理)
        assertThat(tripsByVehicle).containsEntry("V-04", 1L);  // 7.1 → 1 趟
        assertThat(assignedTrips).isEqualTo(6);
        assertThat(result.unassignedOrderIds()).isEmpty();
    }

    private static List<OrderInput> demoOrders() {
        List<OrderInput> o = new ArrayList<>();
        // storeCode, area, volume, weight, windowStart, windowEnd
        o.add(order("CX-201", "吴中", "2.500", "640", "09:00", "12:00"));
        o.add(order("CX-202", "吴中", "2.200", "560", "10:00", "13:00"));
        o.add(order("CX-203", "吴中", "2.400", "600", "10:30", "13:30"));
        o.add(order("CX-204", "吴中", "1.900", "470", "13:00", "16:00"));
        o.add(order("CX-501", "吴江", "2.000", "510", "13:00", "16:00"));
        o.add(order("CX-502", "吴江", "2.700", "680", "11:00", "14:00"));
        o.add(order("CX-101", "园区", "4.500", "1100", "08:00", "10:00"));
        o.add(order("CX-102", "园区", "3.600", "900", "08:00", "10:30"));
        o.add(order("CX-103", "园区", "3.200", "820", "08:30", "11:00"));
        o.add(order("CX-104", "园区", "3.800", "940", "09:00", "12:00"));
        o.add(order("CX-105", "园区", "2.000", "510", "10:00", "13:00"));
        o.add(order("CX-106", "园区", "3.000", "760", "11:00", "14:00"));
        o.add(order("CX-301", "姑苏", "2.700", "680", "09:00", "12:00"));
        o.add(order("CX-302", "姑苏", "2.300", "600", "10:00", "13:00"));
        o.add(order("CX-503", "昆山", "2.400", "600", "12:00", "15:00"));
        o.add(order("CX-303", "相城", "2.900", "720", "08:30", "11:30"));
        o.add(order("CX-304", "相城", "2.000", "510", "11:00", "14:00"));
        o.add(order("CX-403", "虎丘", "3.400", "850", "08:30", "10:30"));
        o.add(order("CX-404", "虎丘", "2.200", "560", "10:00", "13:00"));
        o.add(order("CX-401", "高新", "3.000", "760", "08:00", "11:00"));
        o.add(order("CX-402", "高新", "2.500", "640", "09:30", "12:30"));
        return o;
    }

    private static List<VehicleInput> demoVehicles() {
        return List.of(
                vehicle("V-01", "10", "3200", Set.of("姑苏", "相城"), "08:00", "18:00"),
                vehicle("V-02", "15", "3800", Set.of("园区", "吴中"), "09:00", "20:00"),
                vehicle("V-03", "10", "3000", Set.of("高新", "虎丘"), "08:30", "18:30"),
                vehicle("V-04", "12", "3800", Set.of("吴江", "昆山", "吴中"), null, null));
    }

    private static Map<String, double[]> storeCoords() {
        Map<String, double[]> s = new HashMap<>();
        s.put("DEPOT", DEPOT);
        s.put("CX-201", new double[] {120.6150, 31.2520});
        s.put("CX-202", new double[] {120.6350, 31.2350});
        s.put("CX-203", new double[] {120.5850, 31.2620});
        s.put("CX-204", new double[] {120.7800, 31.2700});
        s.put("CX-501", new double[] {120.6400, 31.1620});
        s.put("CX-502", new double[] {120.6450, 31.1800});
        s.put("CX-101", new double[] {120.7220, 31.3200});
        s.put("CX-102", new double[] {120.7420, 31.3050});
        s.put("CX-103", new double[] {120.7000, 31.3150});
        s.put("CX-104", new double[] {120.7300, 31.2900});
        s.put("CX-105", new double[] {120.7350, 31.2700});
        s.put("CX-106", new double[] {120.7550, 31.3300});
        s.put("CX-301", new double[] {120.6250, 31.3120});
        s.put("CX-302", new double[] {120.6300, 31.3200});
        s.put("CX-503", new double[] {120.8600, 31.3000});
        s.put("CX-303", new double[] {120.6320, 31.4280});
        s.put("CX-304", new double[] {120.6000, 31.4500});
        s.put("CX-403", new double[] {120.5620, 31.3350});
        s.put("CX-404", new double[] {120.5200, 31.3600});
        s.put("CX-401", new double[] {120.5480, 31.3020});
        s.put("CX-402", new double[] {120.5520, 31.2820});
        return s;
    }

    private static OrderInput order(String storeCode, String area, String vol, String wt,
            String ws, String we) {
        return new OrderInput(storeCode, storeCode, area, new BigDecimal(vol), new BigDecimal(wt), ws, we);
    }

    private static VehicleInput vehicle(String id, String cap, String maxWt, Set<String> areas,
            String from, String to) {
        return new VehicleInput(id, new BigDecimal(cap), new BigDecimal(maxWt),
                new LinkedHashSet<>(areas), from, to);
    }

    private static Map<String, double[]> coordsFor(List<OrderInput> orders, Map<String, double[]> stores) {
        Map<String, double[]> coords = new HashMap<>();
        for (OrderInput o : orders) {
            coords.put(o.orderId(), stores.get(o.storeCode()));
        }
        return coords;
    }

    private static DistanceLookup planarKmLookup(Map<String, double[]> stores) {
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
}
