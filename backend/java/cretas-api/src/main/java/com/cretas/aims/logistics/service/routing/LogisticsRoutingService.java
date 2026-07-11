package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        LogisticsRoutingAlgorithm.Input input = new LogisticsRoutingAlgorithm.Input(
                orderInputs, vehicleInputs, driverBindingsByVehicleId, driverInfoById, distanceLookup, targetLoadPct);

        LogisticsRoutingAlgorithm.Result result = LogisticsRoutingAlgorithm.run(input);

        // ---- 落库 Trip + Stop ----
        BigDecimal totalDistanceKm = BigDecimal.ZERO;
        int totalStores = 0;
        boolean needsAction = !result.unassignedOrderIds().isEmpty();

        List<LogisticsTrip> savedTrips = new ArrayList<>();
        for (LogisticsRoutingAlgorithm.TripResult tr : result.trips()) {
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
