package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.TripStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 3 确定性排线算法 — 纯函数, 无 Spring / 无 DB 依赖 (只依赖传入的 in-memory 输入)。
 *
 * <p>逐条对照 {@code docs/superpowers/specs/2026-07-11-logistics-routing-algorithm-precision.md}
 * §4 (Step A-H) 实现, port 自前端参考实现
 * {@code web-admin/src/views/scheduling/logistics/routeEngine.ts}，并按精确规格增强硬约束
 * (重量 / 车辆冲突 / 司机冲突 / 缺边诚实降级)。
 *
 * <p><b>铁律</b>: 相同 {@link Input} → 完全相同 {@link Result}（确定性）；缺一条距离边 →
 * 该车次 {@code NEEDS_ROUTE_DATA} 且 {@code totalDistanceKm=0}，绝不伪造/直线降级公里数。
 *
 * <p>持久化 (Plan/Trip/Stop 落库) 由 {@link LogisticsRoutingService} 负责，本类只产出
 * in-memory 结果，便于脱离 Spring/DB 做算法精确性单测。
 */
public final class LogisticsRoutingAlgorithm {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 无法确定车次所需时间窗时的保守占位窗口 (00:00-23:59) — 用于司机/车辆冲突判定的兜底, 见类头 Step D 说明。 */
    private static final LocalTime ALL_DAY_START = LocalTime.MIN;
    private static final LocalTime ALL_DAY_END = LocalTime.of(23, 59);

    private LogisticsRoutingAlgorithm() {
    }

    // ============================================================
    // 输入模型
    // ============================================================

    /** 距离边查询 — 缺边返回 null (诚实降级, 不猜测/不伪造)。 */
    @FunctionalInterface
    public interface DistanceLookup {
        BigDecimal find(String fromPointId, String toPointId);
    }

    public record OrderInput(
            String orderId,
            String storeCode,
            String areaCode,
            BigDecimal volumeCbm,
            BigDecimal weightKg,
            String windowStart,
            String windowEnd) {
    }

    public record VehicleInput(
            String vehicleId,
            BigDecimal capacityCbm,
            BigDecimal maxWeightKg,
            Set<String> serviceAreas,
            String availableFrom,
            String availableTo) {
    }

    public record DriverBindingInput(
            String driverId,
            DriverRole role,
            String shiftStart,
            String shiftEnd,
            int priority) {
    }

    public record DriverInfo(String driverId, Set<String> serviceAreas) {
    }

    public record Input(
            List<OrderInput> orders,
            List<VehicleInput> vehicles,
            Map<String, List<DriverBindingInput>> driverBindingsByVehicleId,
            Map<String, DriverInfo> driverInfoById,
            DistanceLookup distanceLookup,
            BigDecimal targetLoadPct,
            // 车次内门店访问顺序做「最近邻」优化用的坐标 (orderId -> {lng, lat})；
            // 为空或某门店缺坐标 → 该箱保持原(区域+编码)顺序 (诚实, 不猜)。
            Map<String, double[]> coordsByOrderId,
            double depotLng,
            double depotLat) {

        /** 兼容构造器：不带坐标 → 不做最近邻排序 (保持原顺序)。供不需要顺序优化的调用/测试使用。 */
        public Input(
                List<OrderInput> orders,
                List<VehicleInput> vehicles,
                Map<String, List<DriverBindingInput>> driverBindingsByVehicleId,
                Map<String, DriverInfo> driverInfoById,
                DistanceLookup distanceLookup,
                BigDecimal targetLoadPct) {
            this(orders, vehicles, driverBindingsByVehicleId, driverInfoById, distanceLookup, targetLoadPct,
                    Map.of(), 0.0, 0.0);
        }
    }

    // ============================================================
    // 输出模型
    // ============================================================

    public record TripResult(
            int tripNo,
            String vehicleId,
            String driverId,
            List<String> orderIdsInOrder,
            List<String> segmentKeys,
            List<BigDecimal> segmentDistances,
            BigDecimal totalDistanceKm,
            BigDecimal totalVolumeCbm,
            BigDecimal totalWeightKg,
            BigDecimal loadRate,
            BigDecimal weightLoadRate,
            TripStatus status) {
    }

    public record Result(List<TripResult> trips, List<String> unassignedOrderIds) {
    }

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    /**
     * Public (widened from package-private, Phase 4 2026-07-11) so
     * {@code com.cretas.aims.logistics.service.LogisticsPlanServiceImpl} can reuse the exact
     * same geometry/km assembly when recomputing a trip after a human adjustment
     * (reorder/move), instead of re-implementing distance-edge lookup + honest degradation.
     * No logic changed — visibility only.
     */
    public record GeometryResult(List<String> keys, List<BigDecimal> distances, boolean missing) {
    }

    // ============================================================
    // 算法入口
    // ============================================================

    public static Result run(Input input) {
        BigDecimal targetLoadPct = input.targetLoadPct();
        if (targetLoadPct == null
                || targetLoadPct.compareTo(BigDecimal.ZERO) <= 0
                || targetLoadPct.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("targetLoadPct must be greater than 0 and at most 100, got: " + targetLoadPct);
        }

        // Step A 前置: order 稳定排序 areaCode ASC, storeCode ASC (spec §4/§6)
        List<OrderInput> sortedOrders = input.orders().stream()
                .sorted(Comparator
                        .comparing((OrderInput o) -> Optional.ofNullable(o.areaCode()).orElse(""))
                        .thenComparing(OrderInput::storeCode))
                .toList();

        // vehicle 候选序: vehicleId ASC (spec §6)
        List<VehicleInput> vehiclesSorted = input.vehicles().stream()
                .sorted(Comparator.comparing(VehicleInput::vehicleId))
                .toList();
        Map<String, VehicleInput> vehicleById = vehiclesSorted.stream()
                .collect(Collectors.toMap(VehicleInput::vehicleId, v -> v, (a, b) -> a, LinkedHashMap::new));

        // ---------------- Step A — 门店归组到车辆 ----------------
        Map<String, List<OrderInput>> groups = new LinkedHashMap<>();
        List<String> unassigned = new ArrayList<>();

        for (OrderInput order : sortedOrders) {
            VehicleInput vehicle = findFirstServiceAreaMatch(vehiclesSorted, order.areaCode());
            boolean overVolume = vehicle != null && order.volumeCbm().compareTo(vehicle.capacityCbm()) > 0;
            boolean overWeight = vehicle != null && order.weightKg().compareTo(vehicle.maxWeightKg()) > 0;
            if (vehicle == null || overVolume || overWeight) {
                unassigned.add(order.orderId());
                continue;
            }
            groups.computeIfAbsent(vehicle.vehicleId(), k -> new ArrayList<>()).add(order);
        }

        // 车辆一次性占用: 每车在一个 plan 内最多一个活跃车次 (硬约束 5) — 组的 primary vehicle 立刻标记已用,
        // 与 routeEngine.ts 的 `new Set(groups.keys())` 语义一致。
        Set<String> usedVehicleIds = new LinkedHashSet<>(groups.keySet());

        List<TripResult> trips = new ArrayList<>();
        int tripNoCounter = 1;
        // 司机占用窗口跟踪 (硬约束 6) — driverId -> 已分配的时间窗列表
        Map<String, List<TimeWindow>> driverOccupied = new LinkedHashMap<>();

        for (Map.Entry<String, List<OrderInput>> entry : groups.entrySet()) {
            VehicleInput primaryVehicle = vehicleById.get(entry.getKey());
            List<OrderInput> groupOrders = entry.getValue();

            // ---------------- Step B — 组内稳定装箱 ----------------
            List<List<OrderInput>> packed = packGroup(groupOrders, primaryVehicle, targetLoadPct);

            // ---------------- Step C/D/E/F — 逐箱定车/定人/组几何/定态 ----------------
            for (int boxIndex = 0; boxIndex < packed.size(); boxIndex++) {
                // 车次内门店访问顺序优化：最近邻(种子) + 2-opt(去交叉) —— 让 ①②③ 有合理先后、
                // 线路不来回穿插 (原顺序是按 区域+编码 排, 地理上会绕)。缺坐标则保持原顺序 (诚实)。
                List<OrderInput> boxOrders = optimizeRouteOrder(
                        packed.get(boxIndex), input.coordsByOrderId(), input.depotLng(), input.depotLat());
                VehicleInput vehicle;
                if (boxIndex == 0) {
                    vehicle = primaryVehicle;
                } else {
                    vehicle = vehiclesSorted.stream()
                            .filter(v -> !usedVehicleIds.contains(v.vehicleId()))
                            .filter(v -> matchesVehicle(v, boxOrders))
                            .findFirst()
                            .orElse(null);
                    if (vehicle != null) {
                        usedVehicleIds.add(vehicle.vehicleId());
                    }
                }

                String driverId = null;
                if (vehicle != null) {
                    driverId = assignDriver(vehicle, boxOrders, input, driverOccupied);
                }

                GeometryResult geometry = assembleGeometry(boxOrders, input.distanceLookup());
                BigDecimal totalDistanceKm = geometry.missing()
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : geometry.distances().stream()
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, RoundingMode.HALF_UP);

                BigDecimal totalVolumeCbm = boxOrders.stream()
                        .map(OrderInput::volumeCbm).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalWeightKg = boxOrders.stream()
                        .map(OrderInput::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal loadRate = (vehicle != null && vehicle.capacityCbm().compareTo(BigDecimal.ZERO) > 0)
                        ? totalVolumeCbm.divide(vehicle.capacityCbm(), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                BigDecimal weightLoadRate = (vehicle != null && vehicle.maxWeightKg().compareTo(BigDecimal.ZERO) > 0)
                        ? totalWeightKg.divide(vehicle.maxWeightKg(), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

                // Step F 态优先级 (与 routeEngine.ts 一致: 缺边 > 缺车; 本实现增补缺车 > 缺司机 > DRAFT)
                TripStatus status;
                if (geometry.missing()) {
                    status = TripStatus.NEEDS_ROUTE_DATA;
                } else if (vehicle == null) {
                    status = TripStatus.NEEDS_VEHICLE;
                } else if (driverId == null) {
                    status = TripStatus.NEEDS_DRIVER;
                } else {
                    status = TripStatus.DRAFT;
                }

                trips.add(new TripResult(
                        tripNoCounter++,
                        vehicle == null ? null : vehicle.vehicleId(),
                        driverId,
                        boxOrders.stream().map(OrderInput::orderId).toList(),
                        geometry.keys(),
                        geometry.missing() ? List.of() : geometry.distances(),
                        totalDistanceKm,
                        totalVolumeCbm,
                        totalWeightKg,
                        loadRate,
                        weightLoadRate,
                        status));
            }
        }

        return new Result(trips, unassigned);
    }

    // ============================================================
    // Step A helper
    // ============================================================

    private static VehicleInput findFirstServiceAreaMatch(List<VehicleInput> vehiclesSorted, String areaCode) {
        if (areaCode == null || areaCode.isBlank()) {
            return null;
        }
        return vehiclesSorted.stream()
                .filter(v -> areaMatches(v.serviceAreas(), areaCode))
                .findFirst()
                .orElse(null);
    }

    private static boolean areaMatches(Set<String> serviceAreas, String areaCode) {
        return serviceAreas != null && !serviceAreas.isEmpty() && serviceAreas.contains(areaCode);
    }

    // ============================================================
    // Step B — 稳定装箱 (硬容量优先, 软目标居次; 体积+重量双硬约束)
    // ============================================================

    private static List<List<OrderInput>> packGroup(List<OrderInput> groupOrders, VehicleInput primaryVehicle, BigDecimal targetLoadPct) {
        BigDecimal targetCap = primaryVehicle.capacityCbm()
                .multiply(targetLoadPct)
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);

        List<List<OrderInput>> packed = new ArrayList<>();
        List<OrderInput> current = new ArrayList<>();
        BigDecimal cumVolume = BigDecimal.ZERO;
        BigDecimal cumWeight = BigDecimal.ZERO;

        for (OrderInput order : groupOrders) {
            BigDecimal nextVolume = cumVolume.add(order.volumeCbm());
            BigDecimal nextWeight = cumWeight.add(order.weightKg());

            boolean overHardCap = !current.isEmpty()
                    && (nextVolume.compareTo(primaryVehicle.capacityCbm()) > 0
                        || nextWeight.compareTo(primaryVehicle.maxWeightKg()) > 0);
            boolean overSoftTarget = !current.isEmpty() && !overHardCap
                    && nextVolume.compareTo(targetCap) > 0;

            if (overHardCap || overSoftTarget) {
                packed.add(current);
                current = new ArrayList<>();
                cumVolume = BigDecimal.ZERO;
                cumWeight = BigDecimal.ZERO;
            }
            current.add(order);
            cumVolume = cumVolume.add(order.volumeCbm());
            cumWeight = cumWeight.add(order.weightKg());
        }
        if (!current.isEmpty()) {
            packed.add(current);
        }
        return packed;
    }

    // ============================================================
    // Step B2 — 车次内门店访问顺序：从配送中心出发的最近邻 (贪心)
    // ============================================================

    /**
     * 车次内访问顺序优化：最近邻(种子) + 2-opt(局部搜索去交叉)。产出有合理先后、线路不穿插的顺序。
     * 任一门店缺坐标 (或坐标映射为空) → 原样返回，保持原(区域+编码)顺序 (诚实降级，不猜)。
     * 优化基于纬度修正的平面近似距离 (只决定顺序)；每站/总里程仍走 assembleGeometry 的真实高德边。
     */
    static List<OrderInput> optimizeRouteOrder(
            List<OrderInput> box, Map<String, double[]> coordsByOrderId, double depotLng, double depotLat) {
        List<OrderInput> route = orderByNearestNeighbor(box, coordsByOrderId, depotLng, depotLat);
        if (route.size() <= 2 || coordsByOrderId == null || coordsByOrderId.isEmpty()) {
            return route;
        }
        for (OrderInput o : route) {
            if (!coordsByOrderId.containsKey(o.orderId())) {
                return route;
            }
        }
        // 2-opt：反复反转能缩短总路径的区段，直到无改进 (开放路径，起点固定为配送中心)。
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 60) {
            improved = false;
            for (int i = 0; i < route.size() - 1; i++) {
                for (int j = i + 1; j < route.size(); j++) {
                    if (twoOptDelta(route, coordsByOrderId, depotLng, depotLat, i, j) < -1e-9) {
                        reverseSegment(route, i, j);
                        improved = true;
                    }
                }
            }
        }
        return route;
    }

    /** 反转 route[i..j] 对开放路径总长的变化量 (负 = 更短)。对称距离下内部段长度不变，只比较两端接边。 */
    private static double twoOptDelta(List<OrderInput> route, Map<String, double[]> coords,
            double depotLng, double depotLat, int i, int j) {
        double[] prev = i == 0 ? new double[] {depotLng, depotLat} : coords.get(route.get(i - 1).orderId());
        double[] a = coords.get(route.get(i).orderId());
        double[] b = coords.get(route.get(j).orderId());
        double oldLen = planarDist(prev, a);
        double newLen = planarDist(prev, b);
        if (j + 1 < route.size()) {
            double[] next = coords.get(route.get(j + 1).orderId());
            oldLen += planarDist(b, next);
            newLen += planarDist(a, next);
        }
        return newLen - oldLen;
    }

    private static void reverseSegment(List<OrderInput> route, int i, int j) {
        while (i < j) {
            OrderInput tmp = route.get(i);
            route.set(i, route.get(j));
            route.set(j, tmp);
            i++;
            j--;
        }
    }

    private static double planarDist(double[] p, double[] q) {
        return Math.sqrt(planarDistanceSq(p[0], p[1], q[0], q[1]));
    }

    /**
     * 把一箱门店按「从配送中心出发、每次去最近的下一个门店」重排 (最近邻种子)。
     */
    private static List<OrderInput> orderByNearestNeighbor(
            List<OrderInput> box, Map<String, double[]> coordsByOrderId, double depotLng, double depotLat) {
        if (box.size() <= 2 || coordsByOrderId == null || coordsByOrderId.isEmpty()) {
            return box;
        }
        for (OrderInput o : box) {
            if (!coordsByOrderId.containsKey(o.orderId())) {
                return box; // 有门店缺坐标 → 不重排
            }
        }
        List<OrderInput> remaining = new ArrayList<>(box);
        List<OrderInput> ordered = new ArrayList<>(box.size());
        double curLng = depotLng;
        double curLat = depotLat;
        while (!remaining.isEmpty()) {
            OrderInput best = null;
            double bestDist = Double.MAX_VALUE;
            for (OrderInput o : remaining) {
                double[] c = coordsByOrderId.get(o.orderId());
                double dist = planarDistanceSq(curLng, curLat, c[0], c[1]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = o;
                }
            }
            ordered.add(best);
            remaining.remove(best);
            double[] bc = coordsByOrderId.get(best.orderId());
            curLng = bc[0];
            curLat = bc[1];
        }
        return ordered;
    }

    /** 纬度修正的平面平方距离 (经度按 cos(lat) 压缩)；只用于最近邻比较，不是真实里程。 */
    private static double planarDistanceSq(double lng1, double lat1, double lng2, double lat2) {
        double meanLatRad = Math.toRadians((lat1 + lat2) / 2.0);
        double dx = (lng2 - lng1) * Math.cos(meanLatRad);
        double dy = lat2 - lat1;
        return dx * dx + dy * dy;
    }

    // ============================================================
    // Step C — 车辆候选匹配 (区域 + 硬容量/重量 + 班次可用)
    // ============================================================

    private static boolean matchesVehicle(VehicleInput vehicle, List<OrderInput> boxOrders) {
        BigDecimal totalVolume = boxOrders.stream().map(OrderInput::volumeCbm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeight = boxOrders.stream().map(OrderInput::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalVolume.compareTo(vehicle.capacityCbm()) > 0) {
            return false;
        }
        if (totalWeight.compareTo(vehicle.maxWeightKg()) > 0) {
            return false;
        }
        for (OrderInput o : boxOrders) {
            if (!areaMatches(vehicle.serviceAreas(), o.areaCode())) {
                return false;
            }
        }
        TimeWindow tripWindow = computeTripWindow(boxOrders);
        return windowCovers(vehicle.availableFrom(), vehicle.availableTo(), tripWindow);
    }

    // ============================================================
    // Step D — 司机分配 (PRIMARY 优先, priority ASC, driverId ASC; 班次+区域+防冲突)
    // ============================================================

    private static String assignDriver(VehicleInput vehicle, List<OrderInput> boxOrders, Input input,
                                        Map<String, List<TimeWindow>> driverOccupied) {
        List<DriverBindingInput> bindings = input.driverBindingsByVehicleId()
                .getOrDefault(vehicle.vehicleId(), List.of());
        List<DriverBindingInput> sortedBindings = bindings.stream()
                .sorted(Comparator
                        .comparing((DriverBindingInput d) -> d.role() == DriverRole.PRIMARY ? 0 : 1)
                        .thenComparing(DriverBindingInput::priority)
                        .thenComparing(DriverBindingInput::driverId))
                .toList();

        TimeWindow tripWindow = computeTripWindow(boxOrders);

        for (DriverBindingInput binding : sortedBindings) {
            if (!windowCovers(binding.shiftStart(), binding.shiftEnd(), tripWindow)) {
                continue;
            }
            DriverInfo info = input.driverInfoById().get(binding.driverId());
            if (!regionCovers(info == null ? null : info.serviceAreas(), boxOrders)) {
                continue;
            }
            TimeWindow occupyWindow = effectiveOccupyWindow(tripWindow, binding.shiftStart(), binding.shiftEnd());
            List<TimeWindow> existing = driverOccupied.getOrDefault(binding.driverId(), List.of());
            if (existing.stream().anyMatch(w -> overlaps(w, occupyWindow))) {
                continue;
            }
            driverOccupied.computeIfAbsent(binding.driverId(), k -> new ArrayList<>()).add(occupyWindow);
            return binding.driverId();
        }
        return null;
    }

    private static boolean regionCovers(Set<String> driverServiceAreas, List<OrderInput> boxOrders) {
        if (driverServiceAreas == null || driverServiceAreas.isEmpty()) {
            // 司机未配置专属区域限制 → 沿用车辆层已校验过的区域覆盖 (spec §4 Step D 增强, 无额外限制)
            return true;
        }
        return boxOrders.stream().allMatch(o -> driverServiceAreas.contains(o.areaCode()));
    }

    // ============================================================
    // Step E — 几何组装 (DEPOT->s1->s2->... ; 缺边诚实降级)
    // ============================================================

    public static GeometryResult assembleGeometry(List<OrderInput> boxOrders, DistanceLookup lookup) {
        List<String> keys = new ArrayList<>();
        List<BigDecimal> distances = new ArrayList<>();
        String prev = "DEPOT";
        boolean missing = false;
        for (OrderInput o : boxOrders) {
            keys.add(prev + "->" + o.storeCode());
            BigDecimal distance = lookup.find(prev, o.storeCode());
            if (distance == null) {
                missing = true;
            } else if (!missing) {
                distances.add(distance);
            }
            prev = o.storeCode();
        }
        return new GeometryResult(keys, missing ? List.of() : distances, missing);
    }

    // ============================================================
    // 时间窗 helper (车辆/司机班次覆盖 + 冲突判定共用)
    // ============================================================

    /** 车次所需时间窗 = 箱内订单送达窗口的并集 (仅取同时含 start+end 的订单); 全无 → null (未知, 不设限)。 */
    private static TimeWindow computeTripWindow(List<OrderInput> boxOrders) {
        LocalTime start = null;
        LocalTime end = null;
        for (OrderInput o : boxOrders) {
            if (o.windowStart() == null || o.windowStart().isBlank()
                    || o.windowEnd() == null || o.windowEnd().isBlank()) {
                continue;
            }
            LocalTime s = LocalTime.parse(o.windowStart());
            LocalTime e = LocalTime.parse(o.windowEnd());
            start = (start == null || s.isBefore(start)) ? s : start;
            end = (end == null || e.isAfter(end)) ? e : end;
        }
        return (start == null || end == null) ? null : new TimeWindow(start, end);
    }

    /** shiftStart/shiftEnd (或 availableFrom/availableTo) 是否覆盖所需窗口; 任一侧未知 → 视为无限制(可用)。 */
    private static boolean windowCovers(String rangeStart, String rangeEnd, TimeWindow required) {
        if (required == null) {
            return true;
        }
        if (rangeStart == null || rangeStart.isBlank() || rangeEnd == null || rangeEnd.isBlank()) {
            return true;
        }
        LocalTime s = LocalTime.parse(rangeStart);
        LocalTime e = LocalTime.parse(rangeEnd);
        return !s.isAfter(required.start()) && !e.isBefore(required.end());
    }

    /** 司机/车辆实际占用的窗口 — 优先用车次所需窗口 (更精确); 否则退化到 shift 窗; 都未知则用全天占位 (保守, 防止误判"不冲突")。 */
    private static TimeWindow effectiveOccupyWindow(TimeWindow tripWindow, String shiftStart, String shiftEnd) {
        if (tripWindow != null) {
            return tripWindow;
        }
        if (shiftStart != null && !shiftStart.isBlank() && shiftEnd != null && !shiftEnd.isBlank()) {
            return new TimeWindow(LocalTime.parse(shiftStart), LocalTime.parse(shiftEnd));
        }
        return new TimeWindow(ALL_DAY_START, ALL_DAY_END);
    }

    private static boolean overlaps(TimeWindow a, TimeWindow b) {
        return a.start().isBefore(b.end()) && b.start().isBefore(a.end());
    }
}
