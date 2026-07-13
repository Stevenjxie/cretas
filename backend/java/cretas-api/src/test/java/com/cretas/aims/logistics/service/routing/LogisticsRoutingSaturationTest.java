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
import java.util.TreeMap;

/**
 * 饱和运力压测 (Steve 2026-07-13): 100 订单 vs 200 辆车 (车队远超需求, ~20x 冗余), 无时间窗。
 * 期望「够」场景表现: 算法<b>只用需要的少数车、每车装满</b>, 而不是把订单摊薄到 200 辆。
 * 观测: 用车数 / 趟数 / 装载率分布 / 未分配 / 耗时 (200 空闲车进 C2 的性能)。
 */
class LogisticsRoutingSaturationTest {

    private static final int NUM_AREAS = 10;

    @Test
    void saturation_100orders_200vehicles_noWindows() {
        List<OrderInput> orders = new ArrayList<>();
        Map<String, double[]> stores = new HashMap<>();
        stores.put("DEPOT", new double[] {120.65, 31.30});
        double totalDemand = 0.0;
        for (int i = 0; i < 100; i++) {
            String code = String.format("O-%03d", i);
            int area = i % NUM_AREAS;
            // 确定性伪随机体积 1.0~4.0 m³ (不用 Math.random, 保持可复现)
            double vol = 1.0 + ((i * 13) % 31) / 10.0;
            totalDemand += vol;
            orders.add(new OrderInput(code, code, "A" + area,
                    BigDecimal.valueOf(vol), BigDecimal.valueOf(vol * 250), null, null)); // 无时间窗
            // 每区一个地理簇, 单在簇附近散开
            double cx = 120.50 + (area % 5) * 0.10;
            double cy = 31.20 + (area / 5) * 0.12;
            stores.put(code, new double[] {cx + ((i * 7) % 9 - 4) * 0.01, cy + ((i * 11) % 9 - 4) * 0.01});
        }

        // 200 辆车: 每区 20 辆 (紧区域, 一车服务一区), 容量 10~15 m³, 饱和。
        List<VehicleInput> vehicles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int area = i % NUM_AREAS;
            double cap = 10 + (i % 6); // 10~15
            vehicles.add(new VehicleInput(String.format("V-%03d", i),
                    BigDecimal.valueOf(cap), BigDecimal.valueOf(cap * 300),
                    new LinkedHashSet<>(Set.of("A" + area)), null, null));
        }
        double fleetCap = vehicles.stream().mapToDouble(v -> v.capacityCbm().doubleValue()).sum();

        Input input = new Input(orders, vehicles, Map.of(), Map.of(),
                planarKmLookup(stores), new BigDecimal("88"),
                coordsFor(orders, stores), 120.65, 31.30, RouteOptimizeMode.DISTANCE);

        long t0 = System.nanoTime();
        Result r = LogisticsRoutingAlgorithm.run(input);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        Set<String> usedVehicles = new LinkedHashSet<>();
        long assignedTrips = 0;
        double sumLoad = 0.0;
        int lowLoadTrips = 0; // 装载率 < 50%
        TreeMap<Integer, Integer> loadBuckets = new TreeMap<>(); // 装载率区间 -> 车次数
        double totalKm = 0.0;
        for (TripResult t : r.trips()) {
            totalKm += t.totalDistanceKm() == null ? 0 : t.totalDistanceKm().doubleValue();
            if (t.vehicleId() == null) {
                continue;
            }
            usedVehicles.add(t.vehicleId());
            assignedTrips++;
            double load = t.loadRate() == null ? 0 : t.loadRate().doubleValue() * 100.0;
            sumLoad += load;
            if (load < 50.0) {
                lowLoadTrips++;
            }
            loadBuckets.merge((int) (load / 10) * 10, 1, Integer::sum);
        }

        System.out.println("========== 饱和压测: 100 订单 / 200 车 / 无时间窗 ==========");
        System.out.printf("总需求=%.1fm³  车队单轮容量=%.0fm³ (冗余 %.1fx)%n",
                totalDemand, fleetCap, fleetCap / totalDemand);
        System.out.printf("用车数=%d / 200   趟数=%d   未分配=%d%n",
                usedVehicles.size(), assignedTrips, r.unassignedOrderIds().size());
        System.out.printf("平均装载率=%.1f%%   低载(<50%%)车次=%d   总里程≈%.0fkm%n",
                assignedTrips == 0 ? 0 : sumLoad / assignedTrips, lowLoadTrips, totalKm);
        System.out.println("装载率分布(区间起点% -> 车次数): " + loadBuckets);
        System.out.printf("耗时=%dms%n", ms);

        double avgLoad = assignedTrips == 0 ? 0 : sumLoad / assignedTrips;
        // 「够」场景铁律: 运力 10x 冗余时算法只用<b>需要的少数车、每车装满</b>, 绝不摊薄到 200 辆。
        org.junit.jupiter.api.Assertions.assertEquals(0, r.unassignedOrderIds().size(), "100 单应全部排入");
        org.junit.jupiter.api.Assertions.assertTrue(usedVehicles.size() <= 30,
                "只用需要的车 (预期 ~20, 断言 ≤30), 实际用了 " + usedVehicles.size() + " 辆");
        org.junit.jupiter.api.Assertions.assertTrue(usedVehicles.size() >= 10,
                "10 个区至少各 1 辆, 实际 " + usedVehicles.size());
        org.junit.jupiter.api.Assertions.assertEquals(usedVehicles.size(), assignedTrips,
                "够场景每车 1 趟 (无需回仓补货)");
        org.junit.jupiter.api.Assertions.assertTrue(avgLoad >= 80.0,
                "平均装载率应高 (预期 ~89%, 断言 ≥80%), 实际 " + String.format("%.1f", avgLoad) + "%");
        org.junit.jupiter.api.Assertions.assertEquals(0, lowLoadTrips, "不应有低载(<50%)车次");
    }

    private static Map<String, double[]> coordsFor(List<OrderInput> orders, Map<String, double[]> stores) {
        Map<String, double[]> c = new HashMap<>();
        for (OrderInput o : orders) {
            c.put(o.orderId(), stores.get(o.storeCode()));
        }
        return c;
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
            return BigDecimal.valueOf(Math.sqrt(dx * dx + dy * dy) * 111.0).setScale(2, RoundingMode.HALF_UP);
        };
    }
}
