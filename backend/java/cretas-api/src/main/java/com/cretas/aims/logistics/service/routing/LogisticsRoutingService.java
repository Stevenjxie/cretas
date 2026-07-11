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

    /** 车场 (DEPOT) 坐标 — 用于补齐 NEEDS_ROUTE_DATA 车次的 DEPOT->首站 距离 (Phase 4, 2026-07-11)。 */
    @Value("${logistics.depot.lng:120.62}")
    private BigDecimal depotLng;

    @Value("${logistics.depot.lat:31.30}")
    private BigDecimal depotLat;

    /**
     * 生成排线计划 — 若该批次已有非 CANCELLED 计划则直接返回既有计划 (幂等, 见类头说明)。
     */
    @Transactional
    public LogisticsPlan generatePlan(String factoryId, String orderBatchId, BigDecimal targetLoadPct) {
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
                .build();
        plan = planRepository.saveAndFlush(plan);

        buildAndPersistTrips(factoryId, plan, orderBatchId, targetLoadPct);
        return planRepository.saveAndFlush(plan);
    }

    /**
     * 重建计划的车次/停靠点 — 清空该计划下既有车次+停靠点 (软删除), 重跑算法, 重新落库。
     * 计划本身 (id/planNumber/orderBatchId) 不变, 仅 targetLoadPct 若传参变化会重新生效。
     */
    @Transactional
    public LogisticsPlan regeneratePlan(String factoryId, String planId) {
        LogisticsPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsPlan", "id", planId));

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
                coordsByOrderId, depotLng.doubleValue(), depotLat.doubleValue());

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
                    .build();
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

            totalDistanceKm = totalDistanceKm.add(tr.totalDistanceKm());
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
                newStatus);
        return new AmapFillResult(filled, amapCalls);
    }

    /**
     * DEPOT 车场坐标 {@code {lng, lat}} — 供人工调整 (reorder/move) 后重算构建 coords 映射。
     */
    public double[] depotCoord() {
        return new double[] {depotLng.doubleValue(), depotLat.doubleValue()};
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
