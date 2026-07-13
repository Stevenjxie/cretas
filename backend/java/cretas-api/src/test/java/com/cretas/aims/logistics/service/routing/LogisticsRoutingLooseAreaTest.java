package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.DistanceLookup;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Input;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.OrderInput;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.Result;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.TripResult;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm.VehicleInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 松区域(重叠) + 车队够: greedy 首匹配把整区堆给小排头车 → 观察当前(Task 3)趟数 vs 目标 2 趟。 */
class LogisticsRoutingLooseAreaTest {

    private static final double[] DEPOT = {120.68, 31.30};

    @Test
    void looseAreaSmallPrimary_currentBaseline() {
        // 区域 X 由 V1(cap5) + V2(cap20) + V3(cap20) 共同服务 (松区域)。12 店 ×2.5 = 30m³。
        // 理想: V2(20)+V3(10) = 2 趟。Greedy: 全堆 V1(排头) → packGroup 用 V1 cap → 6 个 5m³ 箱。
        List<OrderInput> orders = new ArrayList<>();
        Map<String, double[]> stores = new HashMap<>();
        stores.put("DEPOT", DEPOT);
        for (int i = 1; i <= 12; i++) {
            String code = String.format("S-%02d", i);
            orders.add(new OrderInput(code, code, "X", new BigDecimal("2.5"), new BigDecimal("100"), null, null));
            stores.put(code, new double[] {120.60 + i * 0.02, 31.30});
        }
        List<VehicleInput> vehicles = List.of(
                vehicle("V1", "5", Set.of("X")),
                vehicle("V2", "20", Set.of("X")),
                vehicle("V3", "20", Set.of("X")));

        Input input = new Input(orders, vehicles, Map.of(), Map.of(),
                planarKmLookup(stores), new BigDecimal("88"),
                coordsFor(orders, stores), DEPOT[0], DEPOT[1], RouteOptimizeMode.DISTANCE);
        Result r = LogisticsRoutingAlgorithm.run(input);

        System.out.println("=== looseArea current baseline ===");
        Map<String, Long> byVeh = new HashMap<>();
        for (TripResult t : r.trips()) {
            System.out.printf("  %-5s seq=%s vol=%s load=%s%% status=%s%n",
                    t.vehicleId(), t.vehicleTripSeq(), t.totalVolumeCbm(), t.loadRate(), t.status());
            if (t.vehicleId() != null) byVeh.merge(t.vehicleId(), 1L, Long::sum);
        }
        long assigned = r.trips().stream().filter(x -> x.vehicleId() != null).count();
        System.out.printf("assignedTrips=%d unassigned=%d tripsByVehicle=%s%n",
                assigned, r.unassignedOrderIds().size(), byVeh);

        // Task 1 目标: 松区域主车取容量最大 → 30m³ 装进 V2(20)+V3(10) = 2 趟 (不再堆小排头 4 趟)。
        org.junit.jupiter.api.Assertions.assertEquals(2, assigned,
                "松区域 30m³ 应用 2 辆大车共 2 趟, 不堆小排头多趟");
        org.junit.jupiter.api.Assertions.assertEquals(0, r.unassignedOrderIds().size());
        org.junit.jupiter.api.Assertions.assertFalse(byVeh.containsKey("V1"),
                "小排头 V1(5m³) 不应被用 (大车足够且更省趟)");
    }

    private static VehicleInput vehicle(String id, String cap, Set<String> areas) {
        return new VehicleInput(id, new BigDecimal(cap), new BigDecimal("9000"),
                new LinkedHashSet<>(areas), null, null);
    }

    private static Map<String, double[]> coordsFor(List<OrderInput> orders, Map<String, double[]> stores) {
        Map<String, double[]> c = new HashMap<>();
        for (OrderInput o : orders) c.put(o.orderId(), stores.get(o.storeCode()));
        return c;
    }

    private static DistanceLookup planarKmLookup(Map<String, double[]> stores) {
        return (from, to) -> {
            double[] f = stores.get(from);
            double[] t = stores.get(to);
            if (f == null || t == null) return null;
            double meanLatRad = Math.toRadians((f[1] + t[1]) / 2.0);
            double dx = (t[0] - f[0]) * Math.cos(meanLatRad);
            double dy = t[1] - f[1];
            return BigDecimal.valueOf(Math.sqrt(dx * dx + dy * dy) * 111.0).setScale(2, RoundingMode.HALF_UP);
        };
    }
}
