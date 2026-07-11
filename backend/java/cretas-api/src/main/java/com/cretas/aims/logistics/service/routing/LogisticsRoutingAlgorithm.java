package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p><b>档2 (2026-07-11)</b>: Step C2 跨车次局部搜索 (段迁移 Or-opt + 单单交换) 打破 Step A
 * 「区域→首个匹配车辆」硬绑定的次优 (一车吃两个不相邻区域 → 宽幅折返)。门禁: 全部有车订单
 * 必须有坐标, 否则完全跳过, 输出与档1 贪心逐字段一致; 且优化结果全局平面里程没有严格变短或
 * NEEDS_DRIVER 恶化 → 回退 seed (永不比今天更差)。见 {@link #optimizeAcrossTrips}。
 *
 * <p><b>档3 (2026-07-12)</b>: 目标模式感知 — {@link Input#optimizeBy()} 为
 * {@link RouteOptimizeMode#TIME} 时, 车次内 2-opt 与跨车次搜索的目标从"平面总里程"换成
 * "行驶时间 + 迟到惩罚" (到达模型 mirror 前端 RouteCards.vue tripEtas, 见 {@link #tripTimeCost});
 * DISTANCE (或 null) 保持既有逐字节行为。永不更差门禁按<b>当前生效目标</b>比较。
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
            double depotLat,
            // 优化目标 (档3, 2026-07-12): TIME=行驶时间+迟到惩罚, DISTANCE=平面总里程 (默认)。
            // null → DISTANCE (兼容既有调用点 — 行为与加模式之前逐字段一致)。
            RouteOptimizeMode optimizeBy) {

        /** 兼容构造器：不带坐标 → 不做最近邻排序 (保持原顺序)。供不需要顺序优化的调用/测试使用。 */
        public Input(
                List<OrderInput> orders,
                List<VehicleInput> vehicles,
                Map<String, List<DriverBindingInput>> driverBindingsByVehicleId,
                Map<String, DriverInfo> driverInfoById,
                DistanceLookup distanceLookup,
                BigDecimal targetLoadPct) {
            this(orders, vehicles, driverBindingsByVehicleId, driverInfoById, distanceLookup, targetLoadPct,
                    Map.of(), 0.0, 0.0, null);
        }

        /** 兼容构造器：带坐标不带模式 → DISTANCE (加 optimizeBy 之前的既有语义, 既有调用/测试不变)。 */
        public Input(
                List<OrderInput> orders,
                List<VehicleInput> vehicles,
                Map<String, List<DriverBindingInput>> driverBindingsByVehicleId,
                Map<String, DriverInfo> driverInfoById,
                DistanceLookup distanceLookup,
                BigDecimal targetLoadPct,
                Map<String, double[]> coordsByOrderId,
                double depotLng,
                double depotLat) {
            this(orders, vehicles, driverBindingsByVehicleId, driverInfoById, distanceLookup, targetLoadPct,
                    coordsByOrderId, depotLng, depotLat, null);
        }

        /** 生效优化模式 — null 诚实回落 DISTANCE (今天的行为)。 */
        public RouteOptimizeMode effectiveOptimizeBy() {
            return optimizeBy == null ? RouteOptimizeMode.DISTANCE : optimizeBy;
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

        // ---------------- Step B/C — 组内稳定装箱 + 逐箱定车 (seed 组合, 贪心) ----------------
        List<BoxAssign> seedBoxes = new ArrayList<>();
        for (Map.Entry<String, List<OrderInput>> entry : groups.entrySet()) {
            VehicleInput primaryVehicle = vehicleById.get(entry.getKey());
            List<List<OrderInput>> packed = packGroup(entry.getValue(), primaryVehicle, targetLoadPct);
            for (int boxIndex = 0; boxIndex < packed.size(); boxIndex++) {
                List<OrderInput> boxOrders = packed.get(boxIndex);
                VehicleInput vehicle;
                if (boxIndex == 0) {
                    vehicle = primaryVehicle;
                } else {
                    // matchesVehicle 只看 体积/重量总量 + 每单区域 + 时间窗覆盖 — 与箱内顺序无关,
                    // 故先定车后 (finalizeTrips 里) 再做顺序优化, 结果与档1 逐字段一致。
                    vehicle = vehiclesSorted.stream()
                            .filter(v -> !usedVehicleIds.contains(v.vehicleId()))
                            .filter(v -> matchesVehicle(v, boxOrders))
                            .findFirst()
                            .orElse(null);
                    if (vehicle != null) {
                        usedVehicleIds.add(vehicle.vehicleId());
                    }
                }
                seedBoxes.add(new BoxAssign(vehicle, boxOrders));
            }
        }

        // ---------------- Step C2 (档2) — 跨车次局部搜索 (打破 Step A 区域→首车硬绑定) ----------------
        // 门禁: 全部有车订单必须有坐标, 否则完全跳过 → 输出与档1 贪心逐字段一致 (诚实降级, 不猜)。
        Result seedResult = finalizeTrips(seedBoxes, input, unassigned);
        List<BoxAssign> optimizedBoxes = optimizeAcrossTrips(seedBoxes, vehiclesSorted, usedVehicleIds, input);
        if (optimizedBoxes == null) {
            return seedResult;
        }
        Result optimizedResult = finalizeTrips(optimizedBoxes, input, unassigned);
        // 永不更差: 优化组合最终目标 (平面近似总里程) 没有严格变短, 或司机缺配比 seed 恶化 → 回退 seed。
        return pickBetterHonestly(seedResult, optimizedResult, input);
    }

    // ============================================================
    // Step B2/D/E/F — 对既定「车辆→订单箱」组合做 顺序优化/定人/组几何/定态
    // ============================================================

    /** 「车辆 → 订单箱」组合中的一箱; vehicle=null 即无可用车 (NEEDS_VEHICLE 车次)。 */
    private record BoxAssign(VehicleInput vehicle, List<OrderInput> orders) {
    }

    /**
     * 对既定组合按档1 语义收尾: 车次内顺序优化 (最近邻+2-opt)、司机分配 (含占用防冲突)、
     * 几何组装 (缺边诚实降级)、定态、连续 tripNo。对 seed 组合调用时输出与档1 逐字段一致。
     */
    private static Result finalizeTrips(List<BoxAssign> boxes, Input input, List<String> unassigned) {
        List<TripResult> trips = new ArrayList<>();
        int tripNoCounter = 1;
        // 司机占用窗口跟踪 (硬约束 6) — driverId -> 已分配的时间窗列表
        Map<String, List<TimeWindow>> driverOccupied = new LinkedHashMap<>();

        for (BoxAssign box : boxes) {
            // 车次内门店访问顺序优化：最近邻(种子) + 2-opt(去交叉) —— 让 ①②③ 有合理先后、
            // 线路不来回穿插 (原顺序是按 区域+编码 排, 地理上会绕)。缺坐标则保持原顺序 (诚实)。
            // TIME 模式: 2-opt 目标换成 行驶时间+迟到惩罚 (按送达窗口重排, 见 tripTimeCost)。
            List<OrderInput> boxOrders = optimizeRouteOrder(
                    box.orders(), input.coordsByOrderId(), input.depotLng(), input.depotLat(),
                    input.effectiveOptimizeBy());
            VehicleInput vehicle = box.vehicle();

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

        return new Result(trips, unassigned);
    }

    // ============================================================
    // Step C2 (档2) — 跨车次局部搜索: 段迁移 (Or-opt 长度1-3) + 单单交换
    // ============================================================

    /** 局部搜索安全上限 — 每步严格改进故必然终止, guard 只防御极端病态输入。 */
    private static final int MAX_CROSS_TRIP_MOVES = 400;
    private static final double EPS = 1e-9;

    /**
     * 档2 跨车次优化 — 在 seed 组合 (Step A/B/C 贪心) 之上做确定性 best-improvement 局部搜索,
     * 打破「区域→首个匹配车辆」硬绑定造成的宽幅折返车次 (一车吃两个不相邻区域):
     * <ul>
     *   <li><b>段迁移 (Or-opt)</b>: 把 1-3 个连续订单整段搬到另一辆车 (含尚未启用的空闲车 → 可拆分折返车次)。</li>
     *   <li><b>单单交换</b>: 两车各出一单互换 (容量顶满、迁移不可行时的补充邻域)。</li>
     * </ul>
     * 每步只接受满足全部硬约束 (目标车 serviceAreas 覆盖每单区域 — 区域保持硬约束; 体积 ≤ 目标车
     * targetLoadPct 软目标容量 — 比硬容量更保守, 不突破用户装载率旋钮; 重量 ≤ maxWeightKg;
     * 车辆可用时窗覆盖新车次时窗; 一车一箱不变) 且严格缩短全局平面近似总里程 (与档1 车次内 2-opt
     * 同一把尺) 的移动。司机班次可行性不在移动级检查 — 由 {@link #finalizeTrips} 重新分配 +
     * {@link #pickBetterHonestly} 的 NEEDS_DRIVER 不回退门禁兜底。
     *
     * <p><b>诚实降级</b>: 坐标映射为空、或任一有车订单缺坐标 → 返回 null (完全不动, 输出 = 档1 贪心)。
     * 无车箱 (NEEDS_VEHICLE) 不参与搜索, 原样透传。新组合产生的新相邻段可能暂缺距离边 →
     * 沿用档1 B2 车次内重排的既有先例: 车次诚实置 NEEDS_ROUTE_DATA + 0km, 由 service 层高德补边缓存。
     *
     * @return 优化后的组合 (顺序: 原组合序, 新启用车箱按 vehicleId 序附尾, 被搬空的箱剔除);
     *         门禁未过或无任何改进移动 → null (调用方直接用 seed 结果)。
     */
    private static List<BoxAssign> optimizeAcrossTrips(List<BoxAssign> seedBoxes, List<VehicleInput> vehiclesSorted,
            Set<String> usedVehicleIds, Input input) {
        Map<String, double[]> coords = input.coordsByOrderId();
        if (coords == null || coords.isEmpty()) {
            return null;
        }
        for (BoxAssign box : seedBoxes) {
            if (box.vehicle() == null) {
                continue;
            }
            for (OrderInput o : box.orders()) {
                if (!coords.containsKey(o.orderId())) {
                    return null; // 诚实降级: 缺任一坐标 → 完全不动 seed
                }
            }
        }

        double[] depot = {input.depotLng(), input.depotLat()};
        BigDecimal targetLoadPct = input.targetLoadPct();
        RouteOptimizeMode mode = input.effectiveOptimizeBy();

        // 工作副本 (深拷贝订单列表, seed 组合保持原样供回退); 无车箱不参与搜索。
        List<WorkBox> boxes = new ArrayList<>();
        for (BoxAssign box : seedBoxes) {
            boxes.add(new WorkBox(box.vehicle(), box.orders()));
        }
        // 尚未启用的车辆 → 空候选箱 (vehicleId ASC, 确定性), 允许把折返区段拆到空闲车上。
        for (VehicleInput v : vehiclesSorted) {
            if (!usedVehicleIds.contains(v.vehicleId())) {
                boxes.add(new WorkBox(v, List.of()));
            }
        }

        boolean anyMove = false;
        for (int guard = 0; guard < MAX_CROSS_TRIP_MOVES; guard++) {
            RelocateMove bestRelocate = findBestRelocate(boxes, coords, depot, targetLoadPct, mode);
            SwapMove bestSwap = findBestSwap(boxes, coords, depot, targetLoadPct, mode);
            double relocateDelta = bestRelocate == null ? 0.0 : bestRelocate.delta();
            double swapDelta = bestSwap == null ? 0.0 : bestSwap.delta();
            if (relocateDelta >= -EPS && swapDelta >= -EPS) {
                break; // 无改进移动 → 局部最优, 收敛
            }
            if (relocateDelta <= swapDelta) {
                applyRelocate(boxes, bestRelocate);
            } else {
                applySwap(boxes, bestSwap);
            }
            anyMove = true;
        }
        if (!anyMove) {
            return null;
        }

        List<BoxAssign> out = new ArrayList<>();
        for (WorkBox wb : boxes) {
            if (wb.orders.isEmpty()) {
                continue; // 被整箱搬空的车次消失; 从未启用的空候选箱同样剔除
            }
            out.add(new BoxAssign(wb.vehicle, wb.orders));
        }
        return out;
    }

    /** 搜索期可变箱 — 维护体积/重量累计, 避免每次候选评估重算总量。 */
    private static final class WorkBox {
        final VehicleInput vehicle; // null = NEEDS_VEHICLE 箱, 不参与搜索
        final List<OrderInput> orders;
        BigDecimal volume;
        BigDecimal weight;

        WorkBox(VehicleInput vehicle, List<OrderInput> orders) {
            this.vehicle = vehicle;
            this.orders = new ArrayList<>(orders);
            this.volume = sumVolume(this.orders);
            this.weight = sumWeight(this.orders);
        }

        boolean participates() {
            return vehicle != null;
        }
    }

    private record RelocateMove(int fromBox, int start, int len, int toBox, int insertPos, double delta) {
    }

    private record SwapMove(int boxA, int idxA, int boxB, int idxB, double delta) {
    }

    /**
     * 全邻域扫描找最优段迁移 (best-improvement, 扫描序 + 严格更优才替换 → 确定性)。
     * DISTANCE 模式用 O(1) 端点增量 (与档2 上线时逐位一致); TIME 模式迟到惩罚非局部 (段前移动
     * 改变后续所有到达时刻) → 整条路径重评 (tripTimeCost), 规模小 (车次内几站) 可承受。
     */
    private static RelocateMove findBestRelocate(List<WorkBox> boxes, Map<String, double[]> coords,
            double[] depot, BigDecimal targetLoadPct, RouteOptimizeMode mode) {
        boolean timeMode = mode == RouteOptimizeMode.TIME;
        RelocateMove best = null;
        for (int len = 1; len <= 3; len++) {
            for (int si = 0; si < boxes.size(); si++) {
                WorkBox src = boxes.get(si);
                if (!src.participates() || src.orders.size() < len) {
                    continue;
                }
                for (int start = 0; start + len <= src.orders.size(); start++) {
                    List<OrderInput> segment = src.orders.subList(start, start + len);
                    BigDecimal segVolume = sumVolume(segment);
                    BigDecimal segWeight = sumWeight(segment);
                    double removalDelta = timeMode
                            ? tripTimeCost(listWithoutSegment(src.orders, start, len), coords, depot)
                                    - tripTimeCost(src.orders, coords, depot)
                            : segmentRemovalDelta(src.orders, start, len, coords, depot);
                    for (int ti = 0; ti < boxes.size(); ti++) {
                        if (ti == si) {
                            continue;
                        }
                        WorkBox dst = boxes.get(ti);
                        if (!dst.participates() || !canAccept(dst, segment, segVolume, segWeight, targetLoadPct)) {
                            continue;
                        }
                        int bestPos = -1;
                        double bestInsertion = Double.MAX_VALUE;
                        for (int p = 0; p <= dst.orders.size(); p++) {
                            double ins = timeMode
                                    ? tripTimeCost(listWithSegmentAt(dst.orders, p, segment), coords, depot)
                                            - tripTimeCost(dst.orders, coords, depot)
                                    : segmentInsertionDelta(dst.orders, p, segment, coords, depot);
                            if (ins < bestInsertion - EPS) {
                                bestInsertion = ins;
                                bestPos = p;
                            }
                        }
                        double delta = removalDelta + bestInsertion;
                        if (delta < -EPS && (best == null || delta < best.delta() - EPS)) {
                            best = new RelocateMove(si, start, len, ti, bestPos, delta);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * 全邻域扫描找最优单单交换 (双向可行性: 区域/容量/重量/时窗都要在交换后的两箱各自成立)。
     * DISTANCE 模式用 O(1) 位置替换增量; TIME 模式整条路径重评 (迟到惩罚非局部, 同 relocate)。
     */
    private static SwapMove findBestSwap(List<WorkBox> boxes, Map<String, double[]> coords,
            double[] depot, BigDecimal targetLoadPct, RouteOptimizeMode mode) {
        boolean timeMode = mode == RouteOptimizeMode.TIME;
        SwapMove best = null;
        for (int bi = 0; bi < boxes.size(); bi++) {
            WorkBox boxA = boxes.get(bi);
            if (!boxA.participates() || boxA.orders.isEmpty()) {
                continue;
            }
            for (int bj = bi + 1; bj < boxes.size(); bj++) {
                WorkBox boxB = boxes.get(bj);
                if (!boxB.participates() || boxB.orders.isEmpty()) {
                    continue;
                }
                for (int ia = 0; ia < boxA.orders.size(); ia++) {
                    OrderInput a = boxA.orders.get(ia);
                    for (int ib = 0; ib < boxB.orders.size(); ib++) {
                        OrderInput b = boxB.orders.get(ib);
                        if (!swapFeasible(boxA, a, b, targetLoadPct) || !swapFeasible(boxB, b, a, targetLoadPct)) {
                            continue;
                        }
                        double delta;
                        if (timeMode) {
                            delta = tripTimeCost(listWithReplacement(boxA.orders, ia, b), coords, depot)
                                    - tripTimeCost(boxA.orders, coords, depot)
                                    + tripTimeCost(listWithReplacement(boxB.orders, ib, a), coords, depot)
                                    - tripTimeCost(boxB.orders, coords, depot);
                        } else {
                            delta = replaceDelta(boxA.orders, ia, b, coords, depot)
                                    + replaceDelta(boxB.orders, ib, a, coords, depot);
                        }
                        if (delta < -EPS && (best == null || delta < best.delta() - EPS)) {
                            best = new SwapMove(bi, ia, bj, ib, delta);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void applyRelocate(List<WorkBox> boxes, RelocateMove move) {
        WorkBox src = boxes.get(move.fromBox());
        WorkBox dst = boxes.get(move.toBox());
        List<OrderInput> segmentView = src.orders.subList(move.start(), move.start() + move.len());
        List<OrderInput> segment = new ArrayList<>(segmentView);
        segmentView.clear();
        dst.orders.addAll(move.insertPos(), segment);
        BigDecimal segVolume = sumVolume(segment);
        BigDecimal segWeight = sumWeight(segment);
        src.volume = src.volume.subtract(segVolume);
        src.weight = src.weight.subtract(segWeight);
        dst.volume = dst.volume.add(segVolume);
        dst.weight = dst.weight.add(segWeight);
    }

    private static void applySwap(List<WorkBox> boxes, SwapMove move) {
        WorkBox boxA = boxes.get(move.boxA());
        WorkBox boxB = boxes.get(move.boxB());
        OrderInput a = boxA.orders.get(move.idxA());
        OrderInput b = boxB.orders.get(move.idxB());
        boxA.orders.set(move.idxA(), b);
        boxB.orders.set(move.idxB(), a);
        boxA.volume = boxA.volume.subtract(a.volumeCbm()).add(b.volumeCbm());
        boxA.weight = boxA.weight.subtract(a.weightKg()).add(b.weightKg());
        boxB.volume = boxB.volume.subtract(b.volumeCbm()).add(a.volumeCbm());
        boxB.weight = boxB.weight.subtract(b.weightKg()).add(a.weightKg());
    }

    /** 目标箱能否整段收下 segment: 区域(硬)/体积(≤软目标容量)/重量(硬)/车辆时窗覆盖新并集窗。 */
    private static boolean canAccept(WorkBox dst, List<OrderInput> segment,
            BigDecimal segVolume, BigDecimal segWeight, BigDecimal targetLoadPct) {
        for (OrderInput o : segment) {
            if (!areaMatches(dst.vehicle.serviceAreas(), o.areaCode())) {
                return false;
            }
        }
        if (dst.volume.add(segVolume).compareTo(softTargetCap(dst.vehicle, targetLoadPct)) > 0) {
            return false;
        }
        if (dst.weight.add(segWeight).compareTo(dst.vehicle.maxWeightKg()) > 0) {
            return false;
        }
        List<OrderInput> hypothetical = new ArrayList<>(dst.orders);
        hypothetical.addAll(segment);
        return windowCovers(dst.vehicle.availableFrom(), dst.vehicle.availableTo(), computeTripWindow(hypothetical));
    }

    /** 交换后 box (出 out、进 in) 是否仍可行 — 区域/体积/重量/时窗四检。 */
    private static boolean swapFeasible(WorkBox box, OrderInput out, OrderInput in, BigDecimal targetLoadPct) {
        if (!areaMatches(box.vehicle.serviceAreas(), in.areaCode())) {
            return false;
        }
        if (box.volume.subtract(out.volumeCbm()).add(in.volumeCbm())
                .compareTo(softTargetCap(box.vehicle, targetLoadPct)) > 0) {
            return false;
        }
        if (box.weight.subtract(out.weightKg()).add(in.weightKg())
                .compareTo(box.vehicle.maxWeightKg()) > 0) {
            return false;
        }
        List<OrderInput> hypothetical = new ArrayList<>(box.orders);
        hypothetical.remove(out);
        hypothetical.add(in);
        return windowCovers(box.vehicle.availableFrom(), box.vehicle.availableTo(), computeTripWindow(hypothetical));
    }

    /** 软目标容量 = capacityCbm × targetLoadPct / 100 (与 {@link #packGroup} 同 scale) — 优化期体积上限。 */
    private static BigDecimal softTargetCap(VehicleInput vehicle, BigDecimal targetLoadPct) {
        return vehicle.capacityCbm().multiply(targetLoadPct).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    /** 从开放路径 (DEPOT 起点) 中摘除 route[start..start+len-1] 的平面长度变化 (负 = 变短)。 */
    private static double segmentRemovalDelta(List<OrderInput> route, int start, int len,
            Map<String, double[]> coords, double[] depot) {
        double[] prev = start == 0 ? depot : coords.get(route.get(start - 1).orderId());
        double removed = planarDist(prev, coords.get(route.get(start).orderId()));
        for (int k = start; k < start + len - 1; k++) {
            removed += planarDist(coords.get(route.get(k).orderId()), coords.get(route.get(k + 1).orderId()));
        }
        double added = 0.0;
        if (start + len < route.size()) {
            double[] next = coords.get(route.get(start + len).orderId());
            removed += planarDist(coords.get(route.get(start + len - 1).orderId()), next);
            added = planarDist(prev, next);
        }
        return added - removed;
    }

    /** 把 segment (保持内部顺序) 插入开放路径 position p 的平面长度变化。 */
    private static double segmentInsertionDelta(List<OrderInput> route, int p, List<OrderInput> segment,
            Map<String, double[]> coords, double[] depot) {
        double[] prev = p == 0 ? depot : coords.get(route.get(p - 1).orderId());
        double added = planarDist(prev, coords.get(segment.get(0).orderId()));
        for (int k = 0; k < segment.size() - 1; k++) {
            added += planarDist(coords.get(segment.get(k).orderId()), coords.get(segment.get(k + 1).orderId()));
        }
        double removed = 0.0;
        if (p < route.size()) {
            double[] next = coords.get(route.get(p).orderId());
            added += planarDist(coords.get(segment.get(segment.size() - 1).orderId()), next);
            removed = planarDist(prev, next);
        }
        return added - removed;
    }

    /** 把开放路径 index i 的订单换成 x 的平面长度变化 (位置替换评估; 终序由 finalize 的 NN+2-opt 收拾)。 */
    private static double replaceDelta(List<OrderInput> route, int i, OrderInput x,
            Map<String, double[]> coords, double[] depot) {
        double[] prev = i == 0 ? depot : coords.get(route.get(i - 1).orderId());
        double[] old = coords.get(route.get(i).orderId());
        double[] nw = coords.get(x.orderId());
        double delta = planarDist(prev, nw) - planarDist(prev, old);
        if (i + 1 < route.size()) {
            double[] next = coords.get(route.get(i + 1).orderId());
            delta += planarDist(nw, next) - planarDist(old, next);
        }
        return delta;
    }

    /**
     * 永不更差门禁: 只有当优化结果 (a) 按<b>当前生效目标</b> (DISTANCE=平面总里程 /
     * TIME=行驶时间+迟到惩罚) 严格更优, 且 (b) NEEDS_DRIVER 车次数不比 seed 多
     * (跨车搬单可能让新车分不到班次覆盖的司机 — 移动级不查司机, 这里整体兜底),
     * 才采用优化结果; 否则回退 seed (= 档1 贪心, 今天的行为)。
     */
    private static Result pickBetterHonestly(Result seed, Result optimized, Input input) {
        long seedNeedsDriver = countStatus(seed, TripStatus.NEEDS_DRIVER);
        long optimizedNeedsDriver = countStatus(optimized, TripStatus.NEEDS_DRIVER);
        if (optimizedNeedsDriver > seedNeedsDriver) {
            return seed;
        }
        double seedObjective = globalObjective(seed, input);
        double optimizedObjective = globalObjective(optimized, input);
        return optimizedObjective < seedObjective - EPS ? optimized : seed;
    }

    /** 按生效模式的全局目标 — DISTANCE: 平面总里程 (度, 相对比较); TIME: 行驶时间+迟到惩罚 (分钟)。 */
    private static double globalObjective(Result result, Input input) {
        if (input.effectiveOptimizeBy() == RouteOptimizeMode.TIME) {
            return timeObjective(result, input);
        }
        return planarObjective(result, input);
    }

    private static long countStatus(Result result, TripStatus status) {
        return result.trips().stream().filter(t -> t.status() == status).count();
    }

    /**
     * 全局平面近似目标 = Σ 每车次开放路径 (DEPOT→s1→...→sk) 平面长度。任一站缺坐标的车次跳过
     * (只可能是未参与优化的无车箱, seed/optimized 内容一致, 两侧一致跳过 → 比较公平)。
     */
    private static double planarObjective(Result result, Input input) {
        Map<String, double[]> coords = input.coordsByOrderId();
        double[] depot = {input.depotLng(), input.depotLat()};
        double total = 0.0;
        for (TripResult trip : result.trips()) {
            boolean allPresent = true;
            for (String orderId : trip.orderIdsInOrder()) {
                if (!coords.containsKey(orderId)) {
                    allPresent = false;
                    break;
                }
            }
            if (!allPresent) {
                continue;
            }
            double[] prev = depot;
            for (String orderId : trip.orderIdsInOrder()) {
                double[] cur = coords.get(orderId);
                total += planarDist(prev, cur);
                prev = cur;
            }
        }
        return total;
    }

    // ============================================================
    // TIME 模式目标 — 行驶时间 + 迟到惩罚 (档3, 2026-07-12)
    // ============================================================
    //
    // 到达模型与前端 RouteCards.vue tripEtas 同一口径 (预估, 非承诺):
    //   出发 = (车次内最早送达窗口开始) − 首段行驶时间; 无任何可解析窗口 → 无迟到约束。
    //   到达(第 i 站) = 出发 + 累计行驶时间 + i × DWELL_MIN (每站卸货停留)。
    //   迟到(站) = max(0, 到达 − 窗口结束); 无窗口结束的站不计迟到 (诚实, 不猜)。
    // 行驶时间是平面近似里程 ÷ 城市均速的估算 (真实高德时长在持久化阶段才有, 算法内不调地图 API)。

    /** 平面近似 度→km 换算 (纬度修正后 1° ≈ 111 km) — 与测试合成 lookup 同一常数。 */
    private static final double KM_PER_DEGREE = 111.0;
    /** 城市配送均速 (km/h) — 行驶时间估算用。 */
    private static final double URBAN_SPEED_KMH = 28.0;
    /** 每站卸货停留 (分钟) — mirror 前端 RouteCards.vue DWELL_MIN。 */
    private static final double DWELL_MIN = 10.0;
    /** 迟到权重 — 足够大让"守住窗口"支配目标, 其次才最小化行驶时间。 */
    private static final double LATENESS_WEIGHT = 1000.0;
    /** "HH:MM" 宽松解析 (mirror 前端 parseHm, 接受 8:00) — 解析失败 → null (无窗口, 不猜)。 */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    /** 两点间行驶时间估算 (分钟) = 平面近似 km ÷ 城市均速 × 60。 */
    private static double travelMin(double[] p, double[] q) {
        return planarDist(p, q) * KM_PER_DEGREE / URBAN_SPEED_KMH * 60.0;
    }

    /**
     * TIME 模式单车次成本 = 总行驶时间(分钟) + LATENESS_WEIGHT × 总迟到(分钟)。
     * 车次内无任何可解析窗口开始 → 迟到按 0 (无约束), 退化为纯行驶时间最小化。
     * 调用方保证 route 内每站坐标齐全 (与档2 平面目标同一门禁)。
     */
    private static double tripTimeCost(List<OrderInput> route, Map<String, double[]> coords, double[] depot) {
        if (route.isEmpty()) {
            return 0.0;
        }
        double[] legMin = new double[route.size()];
        double totalTravel = 0.0;
        double[] prev = depot;
        for (int i = 0; i < route.size(); i++) {
            double[] cur = coords.get(route.get(i).orderId());
            legMin[i] = travelMin(prev, cur);
            totalTravel += legMin[i];
            prev = cur;
        }
        return totalTravel + LATENESS_WEIGHT * tripLatenessMin(route, legMin);
    }

    /** 按到达模型算车次总迟到分钟 — legMin[i] = 第 i 段行驶时间 (前一站/DEPOT → 第 i 站)。 */
    private static double tripLatenessMin(List<OrderInput> route, double[] legMin) {
        Integer earliestStart = null;
        for (OrderInput o : route) {
            Integer s = parseWindowMin(o.windowStart());
            if (s != null && (earliestStart == null || s < earliestStart)) {
                earliestStart = s;
            }
        }
        if (earliestStart == null) {
            return 0.0; // 无可解析窗口 → 无迟到约束
        }
        double depart = earliestStart - legMin[0];
        double lateness = 0.0;
        double cumTravel = 0.0;
        for (int i = 0; i < route.size(); i++) {
            cumTravel += legMin[i];
            double arrival = depart + cumTravel + i * DWELL_MIN;
            Integer end = parseWindowMin(route.get(i).windowEnd());
            if (end != null) {
                lateness += Math.max(0.0, arrival - end);
            }
        }
        return lateness;
    }

    /** 宽松 "H:MM"/"HH:MM" → 当日分钟; 空/格式非法/越界 → null (视为无窗口)。 */
    private static Integer parseWindowMin(String hm) {
        if (hm == null || hm.isBlank()) {
            return null;
        }
        Matcher m = WINDOW_PATTERN.matcher(hm.trim());
        if (!m.matches()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) {
            return null;
        }
        return h * 60 + min;
    }

    /** TIME 模式全局目标 = Σ 每车次 tripTimeCost。缺坐标车次跳过 (同 planarObjective 的公平比较语义)。 */
    private static double timeObjective(Result result, Input input) {
        Map<String, double[]> coords = input.coordsByOrderId();
        Map<String, OrderInput> orderById = input.orders().stream()
                .collect(Collectors.toMap(OrderInput::orderId, o -> o, (a, b) -> a));
        double[] depot = {input.depotLng(), input.depotLat()};
        double total = 0.0;
        for (TripResult trip : result.trips()) {
            List<OrderInput> route = new ArrayList<>(trip.orderIdsInOrder().size());
            boolean allPresent = true;
            for (String orderId : trip.orderIdsInOrder()) {
                OrderInput o = orderById.get(orderId);
                if (o == null || !coords.containsKey(orderId)) {
                    allPresent = false;
                    break;
                }
                route.add(o);
            }
            if (!allPresent) {
                continue;
            }
            total += tripTimeCost(route, coords, depot);
        }
        return total;
    }

    /**
     * 供测试断言用 (包内可见): 按 TIME 到达模型 计算整个排线结果的总迟到分钟数
     * (不乘权重, 不含行驶时间)。缺坐标车次跳过 — 与目标口径一致。
     */
    static double totalLatenessMin(Result result, Input input) {
        Map<String, double[]> coords = input.coordsByOrderId();
        Map<String, OrderInput> orderById = input.orders().stream()
                .collect(Collectors.toMap(OrderInput::orderId, o -> o, (a, b) -> a));
        double[] depot = {input.depotLng(), input.depotLat()};
        double total = 0.0;
        for (TripResult trip : result.trips()) {
            List<OrderInput> route = new ArrayList<>(trip.orderIdsInOrder().size());
            boolean allPresent = true;
            for (String orderId : trip.orderIdsInOrder()) {
                OrderInput o = orderById.get(orderId);
                if (o == null || !coords.containsKey(orderId)) {
                    allPresent = false;
                    break;
                }
                route.add(o);
            }
            if (!allPresent || route.isEmpty()) {
                continue;
            }
            double[] legMin = new double[route.size()];
            double[] prev = depot;
            for (int i = 0; i < route.size(); i++) {
                double[] cur = coords.get(route.get(i).orderId());
                legMin[i] = travelMin(prev, cur);
                prev = cur;
            }
            total += tripLatenessMin(route, legMin);
        }
        return total;
    }

    /** route 摘除 [start, start+len) 段后的拷贝 (TIME 模式 relocate 候选评估用)。 */
    private static List<OrderInput> listWithoutSegment(List<OrderInput> route, int start, int len) {
        List<OrderInput> out = new ArrayList<>(route.size() - len);
        for (int i = 0; i < route.size(); i++) {
            if (i < start || i >= start + len) {
                out.add(route.get(i));
            }
        }
        return out;
    }

    /** segment (保持内部顺序) 插到 route 位置 p 后的拷贝 (TIME 模式 relocate 候选评估用)。 */
    private static List<OrderInput> listWithSegmentAt(List<OrderInput> route, int p, List<OrderInput> segment) {
        List<OrderInput> out = new ArrayList<>(route.size() + segment.size());
        out.addAll(route.subList(0, p));
        out.addAll(segment);
        out.addAll(route.subList(p, route.size()));
        return out;
    }

    /** route 位置 i 换成 x 后的拷贝 (TIME 模式 swap 候选评估用)。 */
    private static List<OrderInput> listWithReplacement(List<OrderInput> route, int i, OrderInput x) {
        List<OrderInput> out = new ArrayList<>(route);
        out.set(i, x);
        return out;
    }

    private static BigDecimal sumVolume(List<OrderInput> orders) {
        return orders.stream().map(OrderInput::volumeCbm).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumWeight(List<OrderInput> orders) {
        return orders.stream().map(OrderInput::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
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
     *
     * <p>TIME 模式 (档3): 2-opt 目标换成 行驶时间+迟到惩罚 ({@link #tripTimeCost}) — 会为守住
     * 送达窗口牺牲部分里程 (e.g. 先跑远端紧窗店再回近端松窗店)。迟到惩罚非局部 (反转改变后续
     * 全部到达时刻) → 整条路径重评; 且 2 站车次也参与 (顺序影响到达时刻)。永不更差:
     * 结果按 TIME 目标不优于原(区域+编码)顺序 → 回退原顺序。DISTANCE 模式逐字节保持既有行为。
     */
    static List<OrderInput> optimizeRouteOrder(
            List<OrderInput> box, Map<String, double[]> coordsByOrderId, double depotLng, double depotLat,
            RouteOptimizeMode mode) {
        if (mode == RouteOptimizeMode.TIME) {
            return optimizeRouteOrderByTime(box, coordsByOrderId, depotLng, depotLat);
        }
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

    /**
     * TIME 模式车次内顺序优化 — 最近邻种子 + 全评估 2-opt (目标 = tripTimeCost)。
     * 坐标门禁与 DISTANCE 模式一致 (缺任一坐标 → 保持原顺序); 2 站也优化 (到达时刻依赖顺序)。
     */
    private static List<OrderInput> optimizeRouteOrderByTime(
            List<OrderInput> box, Map<String, double[]> coordsByOrderId, double depotLng, double depotLat) {
        if (box.size() <= 1 || coordsByOrderId == null || coordsByOrderId.isEmpty()) {
            return box;
        }
        for (OrderInput o : box) {
            if (!coordsByOrderId.containsKey(o.orderId())) {
                return box;
            }
        }
        double[] depot = {depotLng, depotLat};
        // 拷贝防御: size<=2 时 orderByNearestNeighbor 直接返回 box 本身, 2-opt 不可原地改调用方列表。
        List<OrderInput> route = new ArrayList<>(
                orderByNearestNeighbor(box, coordsByOrderId, depotLng, depotLat));
        double cost = tripTimeCost(route, coordsByOrderId, depot);
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 60) {
            improved = false;
            for (int i = 0; i < route.size() - 1; i++) {
                for (int j = i + 1; j < route.size(); j++) {
                    reverseSegment(route, i, j);
                    double newCost = tripTimeCost(route, coordsByOrderId, depot);
                    if (newCost < cost - EPS) {
                        cost = newCost;
                        improved = true;
                    } else {
                        reverseSegment(route, i, j); // 无改进 → 复原
                    }
                }
            }
        }
        // 永不更差 (TIME 目标): 最近邻种子对窗口无感, 极端窗口分布下可能劣于原(区域+编码)顺序。
        if (tripTimeCost(box, coordsByOrderId, depot) < cost - EPS) {
            return box;
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
