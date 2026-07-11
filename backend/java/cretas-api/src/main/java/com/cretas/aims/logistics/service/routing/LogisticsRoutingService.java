package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.DistanceEdgeSource;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDistanceEdgeRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 3 — 确定性排线引擎 service 层。加载 {@code logistics.entity} 数据 → 映射为
 * {@link LogisticsRoutingAlgorithm.Input} → 跑纯算法 → 落库 {@link LogisticsPlan} +
 * {@link LogisticsTrip} + {@link LogisticsStop}。
 *
 * <p>算法本体 (分组/装箱/定车/定人/组几何/定态) 见 {@link LogisticsRoutingAlgorithm}，逐条对照
 * {@code docs/superpowers/specs/2026-07-11-logistics-routing-algorithm-precision.md}。本类只负责
 * "实体 ↔ 算法输入输出" 的映射 + 事务化持久化，不重复算法逻辑。
 *
 * <p><b>幂等策略</b> (spec 未强制具体规则，本实现选择): {@link #generatePlan} 对同一
 * {@code orderBatchId} 若已存在非 CANCELLED 计划，直接返回既有计划，不静默重复创建/不自动
 * supersede（对齐 {@code fool-proof-design.md} Rule 4 — 写操作幂等防重复；调用方若确实想
 * 重新排线，必须显式调用 {@link #regeneratePlan} 针对该计划 id 重建车次/停靠点）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogisticsRoutingService {

    private final LogisticsOrderBatchRepository orderBatchRepository;
    private final LogisticsDeliveryOrderRepository deliveryOrderRepository;
    private final LogisticsVehicleProfileRepository vehicleProfileRepository;
    private final LogisticsDriverRepository driverRepository;
    private final LogisticsVehicleDriverRepository vehicleDriverRepository;
    private final LogisticsDistanceEdgeRepository distanceEdgeRepository;
    private final LogisticsPlanRepository planRepository;
    private final LogisticsTripRepository tripRepository;
    private final LogisticsStopRepository stopRepository;
    private final AmapClient amapClient;
    private final RouteProviderChain routeProviderChain;

    /** 车场 (DEPOT) 坐标 — 用于补齐 NEEDS_ROUTE_DATA 车次的 DEPOT->首站 距离 (Phase 4, 2026-07-11)。 */
    @Value("${logistics.depot.lng:120.62}")
    private BigDecimal depotLng;

    @Value("${logistics.depot.lat:31.30}")
    private BigDecimal depotLat;

    /**
     * 生成排线计划 (兼容重载, 优化模式默认 DISTANCE=路程最短) — 见 4 参主方法。
     */
    @Transactional
    public LogisticsPlan generatePlan(String factoryId, String orderBatchId, BigDecimal targetLoadPct) {
        return generatePlan(factoryId, orderBatchId, targetLoadPct, RouteOptimizeMode.DISTANCE);
    }

    /**
     * 生成排线计划 — 若该批次已有非 CANCELLED 计划则直接返回既有计划 (幂等, 见类头说明)。
     *
     * @param optimizeBy 排线优化模式 (时间最快/路程最短); null 按 DISTANCE 处理。存于计划上,
     *                   {@link #regeneratePlan} 复用, 保证重生成与初次生成口径一致 (档1-B)。
     */
    @Transactional
    public LogisticsPlan generatePlan(String factoryId, String orderBatchId, BigDecimal targetLoadPct,
            RouteOptimizeMode optimizeBy) {
        List<LogisticsPlan> existingPlans = planRepository.findByFactoryIdOrderByPlanDateDesc(factoryId);
        for (LogisticsPlan candidate : existingPlans) {
            if (orderBatchId.equals(candidate.getOrderBatchId()) && candidate.getStatus() != PlanStatus.CANCELLED) {
                log.info("logistics.routing.generatePlan idempotent short-circuit: factoryId={}, orderBatchId={}, existingPlanId={}",
                        factoryId, orderBatchId, candidate.getId());
                return candidate;
            }
        }

        LogisticsOrderBatch batch = orderBatchRepository.findById(orderBatchId)
                .filter(b -> factoryId.equals(b.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsOrderBatch", "id", orderBatchId));

        LogisticsPlan plan = LogisticsPlan.builder()
                .factoryId(factoryId)
                .orderBatchId(orderBatchId)
                .planDate(batch.getBusinessDate())
                .planNumber("PLAN-" + batch.getBatchNumber())
                .targetLoadPct(targetLoadPct)
                .optimizeBy(optimizeBy == null ? RouteOptimizeMode.DISTANCE : optimizeBy)
                .build();
        plan = planRepository.saveAndFlush(plan);

        buildAndPersistTrips(factoryId, plan, orderBatchId, targetLoadPct);
        return planRepository.saveAndFlush(plan);
    }

    /** 重建计划 — 沿用计划存储的优化模式/装载率 (兼容重载)。 */
    @Transactional
    public LogisticsPlan regeneratePlan(String factoryId, String planId) {
        return regeneratePlan(factoryId, planId, null, null);
    }

    /**
     * 重建计划的车次/停靠点 — 清空该计划下既有车次+停靠点 (软删除), 重跑算法, 重新落库。
     * 计划本身 (id/planNumber/orderBatchId) 不变。
     *
     * @param optimizeBy    若非 null → 覆盖计划优化模式 (时间最快/路程最短) 后重建
     * @param targetLoadPct 若非 null → 覆盖计划目标装载率后重建
     */
    @Transactional
    public LogisticsPlan regeneratePlan(String factoryId, String planId,
            RouteOptimizeMode optimizeBy, BigDecimal targetLoadPct) {
        LogisticsPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsPlan", "id", planId));

        if (optimizeBy != null) {
            plan.setOptimizeBy(optimizeBy);
        }
        if (targetLoadPct != null) {
            plan.setTargetLoadPct(targetLoadPct);
        }
        clearTripsAndStops(planId);
        buildAndPersistTrips(factoryId, plan, plan.getOrderBatchId(), plan.getTargetLoadPct());
        return planRepository.saveAndFlush(plan);
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private void clearTripsAndStops(String planId) {
        List<LogisticsTrip> trips = tripRepository.findByPlanIdAndDeletedAtIsNull(planId);
        for (LogisticsTrip trip : trips) {
            List<LogisticsStop> stops = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip.getId());
            stopRepository.deleteAll(stops); // @SQLDelete → soft delete (deleted_at = NOW())
        }
        tripRepository.deleteAll(trips);
        stopRepository.flush();
        tripRepository.flush();
    }

    private void buildAndPersistTrips(String factoryId, LogisticsPlan plan, String orderBatchId, BigDecimal targetLoadPct) {
        List<LogisticsDeliveryOrder> orders = deliveryOrderRepository.findByFactoryIdAndBatchId(factoryId, orderBatchId)
                .stream()
                .filter(o -> o.getStatus() != DeliveryOrderStatus.CANCELLED)
                .toList();
        Map<String, LogisticsDeliveryOrder> orderById = orders.stream()
                .collect(Collectors.toMap(LogisticsDeliveryOrder::getId, o -> o));

        List<LogisticsVehicleProfile> vehicleProfiles =
                vehicleProfileRepository.findByFactoryIdAndActiveTrueAndDeletedAtIsNull(factoryId);

        Map<String, List<LogisticsVehicleDriver>> bindingsByVehicleId = new HashMap<>();
        for (LogisticsVehicleProfile vp : vehicleProfiles) {
            bindingsByVehicleId.put(vp.getVehicleId(),
                    vehicleDriverRepository.findByVehicleIdAndDeletedAtIsNull(vp.getVehicleId()));
        }

        Set<String> referencedDriverIds = bindingsByVehicleId.values().stream()
                .flatMap(List::stream)
                .filter(LogisticsVehicleDriver::getActive)
                .map(LogisticsVehicleDriver::getDriverId)
                .collect(Collectors.toSet());
        Map<String, LogisticsDriver> driverById = driverRepository
                .findByFactoryIdAndActiveTrueAndDeletedAtIsNull(factoryId).stream()
                .filter(d -> referencedDriverIds.contains(d.getId()))
                .collect(Collectors.toMap(LogisticsDriver::getId, d -> d));

        // ---- 映射为算法输入 ----
        List<LogisticsRoutingAlgorithm.OrderInput> orderInputs = orders.stream()
                .map(o -> new LogisticsRoutingAlgorithm.OrderInput(
                        o.getId(), o.getStoreCode(), o.getAreaCode(),
                        nvl(o.getVolumeCbm()), nvl(o.getWeightKg()),
                        o.getDeliveryWindowStart(), o.getDeliveryWindowEnd()))
                .toList();

        List<LogisticsRoutingAlgorithm.VehicleInput> vehicleInputs = vehicleProfiles.stream()
                .map(vp -> new LogisticsRoutingAlgorithm.VehicleInput(
                        vp.getVehicleId(), nvl(vp.getCapacityCbm()), nvl(vp.getMaxWeightKg()),
                        parseServiceAreas(vp.getServiceAreas()), vp.getAvailableFrom(), vp.getAvailableTo()))
                .toList();

        Map<String, List<LogisticsRoutingAlgorithm.DriverBindingInput>> driverBindingsByVehicleId = new HashMap<>();
        for (Map.Entry<String, List<LogisticsVehicleDriver>> entry : bindingsByVehicleId.entrySet()) {
            List<LogisticsRoutingAlgorithm.DriverBindingInput> bindings = entry.getValue().stream()
                    .filter(LogisticsVehicleDriver::getActive)
                    .map(b -> new LogisticsRoutingAlgorithm.DriverBindingInput(
                            b.getDriverId(), b.getRole(), b.getShiftStart(), b.getShiftEnd(),
                            b.getPriority() == null ? 0 : b.getPriority()))
                    .toList();
            driverBindingsByVehicleId.put(entry.getKey(), bindings);
        }

        Map<String, LogisticsRoutingAlgorithm.DriverInfo> driverInfoById = driverById.values().stream()
                .collect(Collectors.toMap(LogisticsDriver::getId,
                        d -> new LogisticsRoutingAlgorithm.DriverInfo(d.getId(), parseServiceAreas(d.getServiceAreas()))));

        LogisticsRoutingAlgorithm.DistanceLookup distanceLookup = (from, to) ->
                distanceEdgeRepository.findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(factoryId, from, to)
                        .map(edge -> edge.getDistanceKm())
                        .orElse(null);

        // 门店坐标映射 (orderId -> {lng, lat}) 供算法做车次内最近邻访问顺序优化；缺坐标的门店不入表 → 该箱不重排。
        Map<String, double[]> coordsByOrderId = new HashMap<>();
        for (LogisticsDeliveryOrder o : orders) {
            if (o.getLongitude() != null && o.getLatitude() != null) {
                coordsByOrderId.put(o.getId(),
                        new double[] {o.getLongitude().doubleValue(), o.getLatitude().doubleValue()});
            }
        }

        LogisticsRoutingAlgorithm.Input input = new LogisticsRoutingAlgorithm.Input(
                orderInputs, vehicleInputs, driverBindingsByVehicleId, driverInfoById, distanceLookup, targetLoadPct,
                coordsByOrderId, depotLng.doubleValue(), depotLat.doubleValue(), plan.getOptimizeBy());

        LogisticsRoutingAlgorithm.Result result = LogisticsRoutingAlgorithm.run(input);

        // ---- Phase 4: 用高德地图补齐 NEEDS_ROUTE_DATA 车次的缺边距离 (诚实降级不变: 失败仍 NEEDS_ROUTE_DATA) ----
        List<LogisticsRoutingAlgorithm.TripResult> patchedTrips =
                fillMissingRouteDataViaAmap(factoryId, result.trips(), orderById);

        // ---- 落库 Trip + Stop ----
        BigDecimal totalDistanceKm = BigDecimal.ZERO;
        int totalStores = 0;
        boolean needsAction = !result.unassignedOrderIds().isEmpty();

        List<LogisticsTrip> savedTrips = new ArrayList<>();
        for (LogisticsRoutingAlgorithm.TripResult tr : patchedTrips) {
            LogisticsTrip trip = LogisticsTrip.builder()
                    .factoryId(factoryId)
                    .planId(plan.getId())
                    .tripNo(tr.tripNo())
                    .vehicleId(tr.vehicleId())
                    .driverId(tr.driverId())
                    .status(tr.status())
                    .totalVolumeCbm(tr.totalVolumeCbm())
                    .totalWeightKg(tr.totalWeightKg())
                    .loadRate(tr.loadRate())
                    .weightLoadRate(tr.weightLoadRate())
                    .totalDistanceKm(tr.totalDistanceKm())
                    // 档4 多趟排班时刻 — 算法推演; 缺坐标/无车时为 null (诚实不伪造)
                    .plannedDepartMin(tr.departMin())
                    .returnToDepotMin(tr.returnToDepotMin())
                    .lateReturn(tr.lateReturn())
                    .vehicleTripSeq(tr.vehicleTripSeq())
                    .build();

            // ---- 档1-B: 道路路线一次计算 + 持久化 (缓存) ----
            // 条件: 已定车 + 有门店 + 非缺边态 (缺边态维持既有 NEEDS_ROUTE_DATA 语义, 不叠加口径)。
            // 成功: 折线/时长/总里程互相一致 (同一次 direction 结果 = 单一事实源), 之后查看计划
            // 直接读 geometry 列, 零地图 API 调用。失败: 保持边距离 + 空折线 (诚实降级)。
            if (tr.vehicleId() != null && !tr.orderIdsInOrder().isEmpty()
                    && tr.status() != TripStatus.NEEDS_ROUTE_DATA) {
                List<LogisticsDeliveryOrder> visitOrders = tr.orderIdsInOrder().stream()
                        .map(orderById::get)
                        .toList();
                Optional<DrivingRoute> road = computeRoadRoute(plan.getOptimizeBy(), visitOrders);
                if (road.isPresent()) {
                    applyRoadRoute(trip, road.get());
                }
            }

            trip = tripRepository.save(trip);
            savedTrips.add(trip);

            for (int i = 0; i < tr.orderIdsInOrder().size(); i++) {
                String orderId = tr.orderIdsInOrder().get(i);
                LogisticsDeliveryOrder order = orderById.get(orderId);
                BigDecimal legDistanceKm = (tr.status() == TripStatus.NEEDS_ROUTE_DATA || tr.segmentDistances().isEmpty())
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : tr.segmentDistances().get(i);

                LogisticsStop stop = LogisticsStop.builder()
                        .factoryId(factoryId)
                        .tripId(trip.getId())
                        .deliveryOrderId(orderId)
                        .sequenceNo(i + 1)
                        .legDistanceKm(legDistanceKm)
                        .arrivalWindowStart(order == null ? null : order.getDeliveryWindowStart())
                        .arrivalWindowEnd(order == null ? null : order.getDeliveryWindowEnd())
                        .build();
                stopRepository.save(stop);
            }

            // 道路路线成功时 trip.totalDistanceKm 已被 direction 结果覆盖 (单一事实源),
            // 计划总里程必须累加 trip 实体上的最终值, 而非算法边距离原值。
            totalDistanceKm = totalDistanceKm.add(trip.getTotalDistanceKm());
            totalStores += tr.orderIdsInOrder().size();
            if (tr.status() != TripStatus.DRAFT) {
                needsAction = true;
            }
        }

        plan.setTotalStores(totalStores);
        plan.setTotalTrips(savedTrips.size());
        plan.setTotalDistanceKm(totalDistanceKm.setScale(2, RoundingMode.HALF_UP));
        plan.setStatus(needsAction ? PlanStatus.NEEDS_ACTION : PlanStatus.DRAFT);
    }

    // ============================================================
    // Phase 4 — 高德地图补齐缺边 (诚实降级: 任何一步失败都保持 NEEDS_ROUTE_DATA, 绝不伪造 km)
    // ============================================================

    /** {@link #tryFillTripDistances} 的返回封装 — 携带本次尝试实际发起的高德调用数, 供上层汇总日志。 */
    private record AmapFillResult(LogisticsRoutingAlgorithm.TripResult trip, int amapCallsMade) {
    }

    /**
     * 对算法产出的车次逐一检查: 仅 {@code NEEDS_ROUTE_DATA} 车次尝试用高德地图补齐距离；
     * 其余车次原样透传。key 未配置时 ({@link AmapClient#isEnabled()} false) 直接跳过整个
     * 补齐流程 (纯读路径, 零额外查询/DB 开销)。
     */
    private List<LogisticsRoutingAlgorithm.TripResult> fillMissingRouteDataViaAmap(
            String factoryId, List<LogisticsRoutingAlgorithm.TripResult> trips,
            Map<String, LogisticsDeliveryOrder> orderById) {
        if (!amapClient.isEnabled()) {
            return trips;
        }
        List<LogisticsRoutingAlgorithm.TripResult> patched = new ArrayList<>(trips.size());
        int totalAmapCalls = 0;
        for (LogisticsRoutingAlgorithm.TripResult tr : trips) {
            if (tr.status() != TripStatus.NEEDS_ROUTE_DATA) {
                patched.add(tr);
                continue;
            }
            AmapFillResult fillResult = tryFillTripDistances(factoryId, tr, orderById);
            totalAmapCalls += fillResult.amapCallsMade();
            patched.add(fillResult.trip());
        }
        if (totalAmapCalls > 0) {
            log.info("logistics.routing.amap fillMissingRouteDataViaAmap factoryId={} amapCalls={}",
                    factoryId, totalAmapCalls);
        }
        return patched;
    }

    /**
     * 尝试为单个 {@code NEEDS_ROUTE_DATA} 车次补全全部腿的距离。任一环节失败 (订单缺坐标 /
     * 高德查询失败) 立即中止并原样返回入参 {@code tr} (车次继续保持 {@code NEEDS_ROUTE_DATA}，
     * 已成功查到的腿仍会被 upsert 进 {@code logistics_distance_edges} 缓存, 供下次
     * regenerate 复用, 不浪费已消耗的配额)。
     */
    private AmapFillResult tryFillTripDistances(String factoryId, LogisticsRoutingAlgorithm.TripResult tr,
            Map<String, LogisticsDeliveryOrder> orderById) {
        List<String> segmentKeys = tr.segmentKeys();
        if (segmentKeys.isEmpty()) {
            return new AmapFillResult(tr, 0);
        }

        // 先解析车次途经的每个 point (DEPOT + 各门店) 的坐标；任一门店订单缺经纬度则整车次放弃 (诚实)。
        Map<String, double[]> coordsByPoint = new LinkedHashMap<>();
        coordsByPoint.put("DEPOT", new double[] {depotLng.doubleValue(), depotLat.doubleValue()});
        for (String orderId : tr.orderIdsInOrder()) {
            LogisticsDeliveryOrder order = orderById.get(orderId);
            if (order == null || order.getLongitude() == null || order.getLatitude() == null) {
                return new AmapFillResult(tr, 0);
            }
            coordsByPoint.put(order.getStoreCode(),
                    new double[] {order.getLongitude().doubleValue(), order.getLatitude().doubleValue()});
        }

        List<BigDecimal> newDistances = new ArrayList<>(segmentKeys.size());
        int amapCalls = 0;
        for (String key : segmentKeys) {
            String[] parts = key.split("->", 2);
            if (parts.length != 2) {
                return new AmapFillResult(tr, amapCalls);
            }
            String from = parts[0];
            String to = parts[1];

            Optional<LogisticsDistanceEdge> cached = distanceEdgeRepository
                    .findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(factoryId, from, to);
            if (cached.isPresent()) {
                newDistances.add(cached.get().getDistanceKm());
                continue;
            }

            double[] originCoord = coordsByPoint.get(from);
            double[] destCoord = coordsByPoint.get(to);
            if (originCoord == null || destCoord == null) {
                return new AmapFillResult(tr, amapCalls);
            }

            amapCalls++;
            Optional<BigDecimal> distanceKm = amapClient.drivingDistanceKm(
                    originCoord[0], originCoord[1], destCoord[0], destCoord[1]);
            if (distanceKm.isEmpty()) {
                return new AmapFillResult(tr, amapCalls); // 诚实降级: 车次保持 NEEDS_ROUTE_DATA
            }

            LogisticsDistanceEdge edge = LogisticsDistanceEdge.builder()
                    .factoryId(factoryId).fromPointId(from).toPointId(to)
                    .distanceKm(distanceKm.get()).source(DistanceEdgeSource.MAP_PROVIDER)
                    .build();
            distanceEdgeRepository.save(edge); // 缓存 — 后续 regenerate / 其它车次复用同一条边不再重复调用
            newDistances.add(distanceKm.get());
        }

        BigDecimal total = newDistances.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        TripStatus newStatus = tr.vehicleId() == null ? TripStatus.NEEDS_VEHICLE
                : tr.driverId() == null ? TripStatus.NEEDS_DRIVER
                : TripStatus.DRAFT;

        LogisticsRoutingAlgorithm.TripResult filled = new LogisticsRoutingAlgorithm.TripResult(
                tr.tripNo(), tr.vehicleId(), tr.driverId(), tr.orderIdsInOrder(), tr.segmentKeys(),
                newDistances, total, tr.totalVolumeCbm(), tr.totalWeightKg(), tr.loadRate(), tr.weightLoadRate(),
                newStatus,
                // 档4 时刻字段原样透传 — 补边只改距离/状态, 不影响时刻推演 (时刻用坐标不用边)
                tr.departMin(), tr.returnToDepotMin(), tr.lateReturn(), tr.vehicleTripSeq());
        return new AmapFillResult(filled, amapCalls);
    }

    /**
     * DEPOT 车场坐标 {@code {lng, lat}} — 供人工调整 (reorder/move) 后重算构建 coords 映射。
     */
    public double[] depotCoord() {
        return new double[] {depotLng.doubleValue(), depotLat.doubleValue()};
    }

    // ============================================================
    // 档1-B — 道路路线计算 (多提供商 fallback 链) + 车次持久化 (缓存)
    // ============================================================

    /**
     * 为一个车次计算完整道路路线: DEPOT → 门店 (访问顺序) → 末站, 走多提供商 fallback 链
     * ({@link RouteProviderChain}, 默认 AMAP→TENCENT→BAIDU)。
     *
     * <p>诚实降级: 链整体未启用 / 任一门店缺经纬度 / 全部 provider 失败 →
     * {@link Optional#empty()} — 调用方保持既有边距离口径 + 空折线, 绝不伪造。
     *
     * @param optimizeBy         优化模式; null 按 DISTANCE
     * @param ordersInVisitOrder 车次门店订单, 按访问顺序 (含 null 元素 = 订单缺失 → empty)
     */
    public Optional<DrivingRoute> computeRoadRoute(RouteOptimizeMode optimizeBy,
            List<LogisticsDeliveryOrder> ordersInVisitOrder) {
        if (ordersInVisitOrder == null || ordersInVisitOrder.isEmpty() || !routeProviderChain.anyEnabled()) {
            return Optional.empty();
        }
        List<double[]> points = new ArrayList<>(ordersInVisitOrder.size());
        for (LogisticsDeliveryOrder order : ordersInVisitOrder) {
            if (order == null || order.getLongitude() == null || order.getLatitude() == null) {
                return Optional.empty(); // 缺坐标 → 无法规划, 诚实放弃整车次路线
            }
            points.add(new double[] {order.getLongitude().doubleValue(), order.getLatitude().doubleValue()});
        }
        double[] dest = points.get(points.size() - 1);
        List<double[]> waypoints = points.subList(0, points.size() - 1);
        return routeProviderChain.drivingRoute(
                depotLng.doubleValue(), depotLat.doubleValue(), waypoints, dest[0], dest[1],
                optimizeBy == null ? RouteOptimizeMode.DISTANCE : optimizeBy);
    }

    /**
     * 把 direction 结果落到车次实体: 道路折线 → {@code roadPath} 列 (JSONB
     * {@code [{"lng":..,"lat":..},...]}, GCJ-02 — <b>不是</b> {@code geometry} 列, 那是前端
     * SVG 兜底地图的 {x,y} 像素点, 语义不同不混用) + 时长 (分钟) + 总里程 (以 direction 里程
     * 覆盖边距离和, 保证"画的线/里程/时长"三者出自同一次规划 = 单一事实源) + provider 标识。
     */
    public static void applyRoadRoute(LogisticsTrip trip, DrivingRoute route) {
        trip.setRoadPath(polylineToRoadPath(route.polyline()));
        trip.setTotalDurationMin(route.durationMin());
        trip.setTotalDistanceKm(route.distanceKm());
        trip.setRouteProvider(route.provider());
    }

    /**
     * {@code {lng,lat}} 点串 → JSONB 持久化形状 {@code List<Map<String,Object>>}
     * (每点 {@code {"lng":..,"lat":..}})。⚠️ 必须保持 List-of-Map — JSONB 数组列
     * 曾因映射成对象触发 500 (见 {@link LogisticsTrip#getGeometry()} 注释)。
     */
    public static List<Map<String, Object>> polylineToRoadPath(List<double[]> polyline) {
        List<Map<String, Object>> roadPath = new ArrayList<>(polyline.size());
        for (double[] point : polyline) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("lng", point[0]);
            entry.put("lat", point[1]);
            roadPath.add(entry);
        }
        return roadPath;
    }

    /**
     * 解析单条边距离 (供人工调整后 {@code recomputeTrip} 复用初次生成的同一套高德补边逻辑)：
     * 先查已落库边 (含之前缓存的 {@code MAP_PROVIDER})，缺则用高德驾车距离补齐并缓存为 {@code MAP_PROVIDER} 边。
     * 任一环节失败 (amap 未启用 / 坐标缺失 / 高德查询失败) 返回 {@code null} → 调用方诚实置 NEEDS_ROUTE_DATA，绝不伪造 km。
     *
     * @param coordsByPoint pointId(DEPOT/storeCode) -> {@code {lng, lat}}；缺坐标的点无法补边
     */
    @Transactional
    public BigDecimal resolveEdgeKm(String factoryId, String fromPointId, String toPointId,
            Map<String, double[]> coordsByPoint) {
        Optional<LogisticsDistanceEdge> cached = distanceEdgeRepository
                .findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(factoryId, fromPointId, toPointId);
        if (cached.isPresent()) {
            return cached.get().getDistanceKm();
        }
        if (!amapClient.isEnabled()) {
            return null;
        }
        double[] origin = coordsByPoint.get(fromPointId);
        double[] dest = coordsByPoint.get(toPointId);
        if (origin == null || dest == null) {
            return null;
        }
        Optional<BigDecimal> km = amapClient.drivingDistanceKm(origin[0], origin[1], dest[0], dest[1]);
        if (km.isEmpty()) {
            return null;
        }
        LogisticsDistanceEdge edge = LogisticsDistanceEdge.builder()
                .factoryId(factoryId).fromPointId(fromPointId).toPointId(toPointId)
                .distanceKm(km.get()).source(DistanceEdgeSource.MAP_PROVIDER)
                .build();
        distanceEdgeRepository.save(edge);
        return km.get();
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** {@code service_areas} 列是逗号分隔字符串 (e.g. "姑苏,相城") — 见 V20261028_02 demo seed。 */
    private static Set<String> parseServiceAreas(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
