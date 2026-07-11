package com.cretas.aims.logistics.service.impl;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.logistics.dto.plan.MoveStopRequest;
import com.cretas.aims.logistics.dto.plan.PlanDto;
import com.cretas.aims.logistics.dto.plan.PlanSnapshotDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.RouteOptimizeMode;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.mapper.LogisticsPlanMapper;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDistanceEdgeRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import com.cretas.aims.logistics.service.LogisticsPlanService;
import com.cretas.aims.logistics.service.routing.DrivingRoute;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingAlgorithm;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingService;
import com.cretas.aims.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 4 — 计划生命周期 + 人工调整 + 确认状态机实现 (handoff §10, §11.3; spec §6/§7 决策 8).
 *
 * <p><b>几何/公里数复用</b>：reorder/move 后重算车次距离不重复实现 DEPOT-&gt;s1-&gt;s2… 组装 +
 * 缺边诚实降级逻辑，而是直接调用 Phase 3 {@link LogisticsRoutingAlgorithm#assembleGeometry}
 * （该方法可见性已从 package-private 放宽为 public，纯视觉/无逻辑变更，见该类头注释）——
 * 保证人工调整后的重算与初次生成路径在"缺边就诚实置零"这件事上永远一致，不会出现两条实现
 * 各自漂移的风险。
 *
 * <p><b>Stop 重排序的并发安全</b>：{@code (trip_id, sequence_no)} 是 DB 唯一索引，批量重排/
 * 跨车次移动时用"负数占位两阶段更新"（{@link #renumberSequential}）规避 Postgres 立即检查
 * 唯一约束（非 deferred）导致的瞬时碰撞。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsPlanServiceImpl implements LogisticsPlanService {

    private final LogisticsPlanRepository planRepository;
    private final LogisticsTripRepository tripRepository;
    private final LogisticsStopRepository stopRepository;
    private final LogisticsDeliveryOrderRepository orderRepository;
    private final LogisticsVehicleProfileRepository vehicleProfileRepository;
    private final LogisticsDriverRepository driverRepository;
    private final LogisticsVehicleDriverRepository vehicleDriverRepository;
    private final LogisticsDistanceEdgeRepository distanceEdgeRepository;
    private final VehicleRepository vehicleRepository;
    private final LogisticsRoutingService routingService;
    private final LogisticsPlanMapper mapper;

    // ============================================================
    // 生成 / 列表 / 详情 / 重生成
    // ============================================================

    @Override
    @Transactional
    public PlanSnapshotDto generatePlan(String factoryId, String batchId, BigDecimal targetLoadPct,
            RouteOptimizeMode optimizeBy) {
        LogisticsPlan plan = routingService.generatePlan(factoryId, batchId, targetLoadPct, optimizeBy);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    public Page<PlanDto> listPlans(String factoryId, PlanStatus status, Pageable pageable) {
        List<LogisticsPlan> all = planRepository.findByFactoryIdOrderByPlanDateDesc(factoryId);
        List<LogisticsPlan> filtered = status == null
                ? all
                : all.stream().filter(p -> p.getStatus() == status).toList();

        int total = filtered.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<PlanDto> pageContent = filtered.subList(start, end).stream().map(this::toPlanDto).toList();
        return new PageImpl<>(pageContent, pageable, total);
    }

    @Override
    public PlanSnapshotDto getPlan(String factoryId, String planId) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    @Transactional
    public PlanSnapshotDto regeneratePlan(String factoryId, String planId) {
        return regeneratePlan(factoryId, planId, null, null);
    }

    @Override
    @Transactional
    public PlanSnapshotDto regeneratePlan(String factoryId, String planId,
            RouteOptimizeMode optimizeBy, BigDecimal targetLoadPct) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsPlan regenerated = routingService.regeneratePlan(factoryId, planId, optimizeBy, targetLoadPct);
        return buildSnapshot(factoryId, regenerated);
    }

    // ============================================================
    // 人工调整：reorder / move / setVehicle / setDriver
    // ============================================================

    @Override
    @Transactional
    public PlanSnapshotDto reorderStops(String factoryId, String planId, String tripId, List<String> storeIds, Long version) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsTrip trip = loadTrip(factoryId, planId, tripId);
        assertTripVersion(trip, version);
        assertTripNotConfirmed(trip);

        List<LogisticsStop> stops = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(tripId);
        if (storeIds == null || storeIds.size() != stops.size()
                || !new HashSet<>(storeIds).equals(stops.stream().map(LogisticsStop::getDeliveryOrderId).collect(Collectors.toSet()))
                || new HashSet<>(storeIds).size() != storeIds.size()) {
            throw new BusinessException(400, "storeIds 必须是该车次现有门店的完整排列，不能增删门店（增删请用移动接口）")
                    .withHintTarget("storeIds");
        }

        Map<String, LogisticsStop> stopByOrderId = stops.stream()
                .collect(Collectors.toMap(LogisticsStop::getDeliveryOrderId, s -> s));
        List<LogisticsStop> reordered = storeIds.stream().map(stopByOrderId::get).collect(Collectors.toCollection(ArrayList::new));
        renumberSequential(reordered);

        Map<String, LogisticsDeliveryOrder> orderById = loadOrdersForBatch(factoryId, plan.getOrderBatchId());
        recomputeTrip(factoryId, trip, reordered, orderById, plan.getOptimizeBy(), true);
        tripRepository.save(trip);

        recomputePlanTotals(factoryId, plan);
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    @Transactional
    public PlanSnapshotDto moveStop(String factoryId, String planId, String tripId, MoveStopRequest request) {
        if (request == null || request.getDeliveryOrderId() == null || request.getDeliveryOrderId().isBlank()) {
            throw new BusinessException(400, "deliveryOrderId 必填").withHintTarget("deliveryOrderId");
        }

        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsTrip sourceTrip = loadTrip(factoryId, planId, tripId);
        assertTripVersion(sourceTrip, request.getVersion());
        assertTripNotConfirmed(sourceTrip);

        LogisticsStop movingStop = stopRepository.findByDeliveryOrderIdAndDeletedAtIsNull(request.getDeliveryOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsStop", "deliveryOrderId", request.getDeliveryOrderId()));
        if (!tripId.equals(movingStop.getTripId())) {
            throw new BusinessException(409, "该门店订单当前不在指定车次中，请刷新后重试")
                    .withCode("VERSION_CONFLICT").withHint("刷新页面获取最新数据后重试");
        }

        Map<String, LogisticsDeliveryOrder> orderById = loadOrdersForBatch(factoryId, plan.getOrderBatchId());
        LogisticsDeliveryOrder movingOrder = orderById.get(request.getDeliveryOrderId());
        if (movingOrder == null) {
            throw new ResourceNotFoundException("LogisticsDeliveryOrder", "id", request.getDeliveryOrderId());
        }

        boolean createNewTrip = request.getTargetTripId() == null || request.getTargetTripId().isBlank();
        LogisticsTrip targetTrip;
        List<LogisticsStop> targetExisting;
        if (createNewTrip) {
            int nextTripNo = tripRepository.findByPlanIdAndDeletedAtIsNull(planId).stream()
                    .mapToInt(LogisticsTrip::getTripNo).max().orElse(0) + 1;
            targetTrip = tripRepository.save(LogisticsTrip.builder()
                    .factoryId(factoryId).planId(planId).tripNo(nextTripNo)
                    .status(TripStatus.NEEDS_VEHICLE)
                    .build());
            targetExisting = new ArrayList<>();
        } else {
            if (request.getTargetTripId().equals(tripId)) {
                throw new BusinessException(400, "目标车次不能与源车次相同，请用整体重排接口").withHintTarget("targetTripId");
            }
            targetTrip = loadTrip(factoryId, planId, request.getTargetTripId());
            assertTripNotConfirmed(targetTrip);
            targetExisting = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(targetTrip.getId());
        }

        // 硬约束再校验：容量 + 重量（仅当目标车次已定车辆；未定车则暂无上限可违反）
        if (targetTrip.getVehicleId() != null) {
            LogisticsVehicleProfile vp = vehicleProfileRepository
                    .findByVehicleIdAndDeletedAtIsNull(targetTrip.getVehicleId()).orElse(null);
            if (vp != null) {
                BigDecimal currentVolume = sumField(targetExisting, orderById, LogisticsDeliveryOrder::getVolumeCbm);
                BigDecimal currentWeight = sumField(targetExisting, orderById, LogisticsDeliveryOrder::getWeightKg);
                BigDecimal newVolume = currentVolume.add(nvl(movingOrder.getVolumeCbm()));
                BigDecimal newWeight = currentWeight.add(nvl(movingOrder.getWeightKg()));
                if (newVolume.compareTo(vp.getCapacityCbm()) > 0) {
                    throw new BusinessException(409, String.format(
                            "目标车次超出容量：当前已装 %s m³ + 该门店 %s m³ > 车辆容量 %s m³，请选择其他车次",
                            currentVolume, nvl(movingOrder.getVolumeCbm()), vp.getCapacityCbm()))
                            .withCode("CAPACITY_EXCEEDED").withHint("请选择其他车次，或先调整该车次车辆");
                }
                if (newWeight.compareTo(vp.getMaxWeightKg()) > 0) {
                    throw new BusinessException(409, String.format(
                            "目标车次超出载重：当前已装 %s kg + 该门店 %s kg > 车辆载重 %s kg，请选择其他车次",
                            currentWeight, nvl(movingOrder.getWeightKg()), vp.getMaxWeightKg()))
                            .withCode("WEIGHT_EXCEEDED").withHint("请选择其他车次，或先调整该车次车辆");
                }
            }
        }

        // 一订单一车次：更新同一 stop 行的 trip_id（不删除重建），DB uq_ls_order_single_trip 天然保持满足
        movingStop.setTripId(targetTrip.getId());
        List<LogisticsStop> targetFinal = new ArrayList<>(targetExisting);
        int insertAt = request.getTargetIndex() == null
                ? targetFinal.size()
                : Math.max(0, Math.min(request.getTargetIndex(), targetFinal.size()));
        targetFinal.add(insertAt, movingStop);
        renumberSequential(targetFinal);
        recomputeTrip(factoryId, targetTrip, targetFinal, orderById, plan.getOptimizeBy(), true);
        tripRepository.save(targetTrip);

        List<LogisticsStop> sourceRemaining = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(tripId).stream()
                .filter(s -> !s.getId().equals(movingStop.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (sourceRemaining.isEmpty()) {
            tripRepository.delete(sourceTrip); // @SQLDelete → 软删除，避免遗留空车次
        } else {
            renumberSequential(sourceRemaining);
            recomputeTrip(factoryId, sourceTrip, sourceRemaining, orderById, plan.getOptimizeBy(), true);
            tripRepository.save(sourceTrip);
        }

        recomputePlanTotals(factoryId, plan);
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    @Transactional
    public PlanSnapshotDto setTripVehicle(String factoryId, String planId, String tripId, String vehicleId, Long version) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsTrip trip = loadTrip(factoryId, planId, tripId);
        assertTripVersion(trip, version);
        assertTripNotConfirmed(trip);

        List<LogisticsStop> stops = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(tripId);
        Map<String, LogisticsDeliveryOrder> orderById = loadOrdersForBatch(factoryId, plan.getOrderBatchId());

        if (vehicleId == null || vehicleId.isBlank()) {
            trip.setVehicleId(null);
            trip.setDriverId(null); // 司机绑定依附于车辆（司机-车辆绑定表），解除车辆时一并清空，需重新指定
        } else {
            Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
            LogisticsVehicleProfile vp = vehicleProfileRepository.findByVehicleIdAndDeletedAtIsNull(vehicleId)
                    .orElseThrow(() -> new BusinessException(400, "该车辆尚未维护物流信息（容量/方数），请先在车辆管理中配置")
                            .withHintTarget("vehicleId"));
            if (!Boolean.TRUE.equals(vp.getActive())) {
                throw new BusinessException(400, "该车辆当前已停用，不能分配").withHintTarget("vehicleId");
            }

            if (!stops.isEmpty()) {
                BigDecimal totalVolume = sumField(stops, orderById, LogisticsDeliveryOrder::getVolumeCbm);
                BigDecimal totalWeight = sumField(stops, orderById, LogisticsDeliveryOrder::getWeightKg);
                if (totalVolume.compareTo(vp.getCapacityCbm()) > 0) {
                    throw new BusinessException(409, String.format(
                            "该车次总方数 %s m³ 超出车辆容量 %s m³，无法分配", totalVolume, vp.getCapacityCbm()))
                            .withCode("CAPACITY_EXCEEDED").withHint("请选择容量更大的车辆");
                }
                if (totalWeight.compareTo(vp.getMaxWeightKg()) > 0) {
                    throw new BusinessException(409, String.format(
                            "该车次总重量 %s kg 超出车辆载重 %s kg，无法分配", totalWeight, vp.getMaxWeightKg()))
                            .withCode("WEIGHT_EXCEEDED").withHint("请选择载重更大的车辆");
                }
                Set<String> serviceAreas = parseCsv(vp.getServiceAreas());
                if (!vehicleRegionCoversAll(serviceAreas, stops, orderById)) {
                    throw new BusinessException(409, "该车辆服务区域未覆盖本车次全部门店所在区域，无法分配")
                            .withCode("REGION_MISMATCH").withHint("请选择服务区域覆盖这些门店的车辆，或先在车辆管理中补充区域");
                }
            }

            boolean conflict = tripRepository.findByPlanIdAndDeletedAtIsNull(planId).stream()
                    .anyMatch(t -> !t.getId().equals(tripId) && vehicleId.equals(t.getVehicleId()));
            if (conflict) {
                throw new BusinessException(409, "该车辆已分配给本计划的另一车次，一个计划内一车最多一活跃车次")
                        .withCode("VEHICLE_CONFLICT").withHint("请选择其他车辆，或先取消该车辆在另一车次的分配");
            }

            trip.setVehicleId(vehicleId);
            if (trip.getDriverId() != null) {
                boolean stillBound = vehicleDriverRepository.findByVehicleIdAndDeletedAtIsNull(vehicleId).stream()
                        .anyMatch(b -> Boolean.TRUE.equals(b.getActive()) && b.getDriverId().equals(trip.getDriverId()));
                if (!stillBound) {
                    trip.setDriverId(null); // 原司机未绑定新车辆，需重新指定
                }
            }
        }

        recomputeTrip(factoryId, trip, stops, orderById, plan.getOptimizeBy(), false);
        tripRepository.save(trip);
        recomputePlanTotals(factoryId, plan);
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    @Transactional
    public PlanSnapshotDto setTripDriver(String factoryId, String planId, String tripId, String driverId, Long version) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsTrip trip = loadTrip(factoryId, planId, tripId);
        assertTripVersion(trip, version);
        assertTripNotConfirmed(trip);

        List<LogisticsStop> stops = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(tripId);
        Map<String, LogisticsDeliveryOrder> orderById = loadOrdersForBatch(factoryId, plan.getOrderBatchId());

        if (driverId == null || driverId.isBlank()) {
            trip.setDriverId(null);
        } else {
            if (trip.getVehicleId() == null) {
                throw new BusinessException(400, "请先分配车辆，再指定司机").withHintTarget("vehicleId");
            }
            LogisticsDriver driver = driverRepository.findByIdAndFactoryId(driverId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("LogisticsDriver", "id", driverId));
            if (!Boolean.TRUE.equals(driver.getActive())) {
                throw new BusinessException(400, "该司机当前已停用，不能分配").withHintTarget("driverId");
            }

            if (!stops.isEmpty()) {
                Set<String> driverAreas = parseCsv(driver.getServiceAreas());
                if (!driverRegionCoversAll(driverAreas, stops, orderById)) {
                    throw new BusinessException(409, "该司机服务区域未覆盖本车次全部门店所在区域，无法分配")
                            .withCode("REGION_MISMATCH").withHint("请选择服务区域覆盖这些门店的司机");
                }
                TimeWindow tripWindow = computeTripWindow(stops);
                if (!windowCovers(driver.getAvailableFrom(), driver.getAvailableTo(), tripWindow)) {
                    throw new BusinessException(409, "该司机班次时间未覆盖本车次所需送达时间窗，无法分配")
                            .withCode("SHIFT_MISMATCH").withHint("请选择班次覆盖该时间窗的司机");
                }
            }

            boolean conflict = tripRepository.findByPlanIdAndDeletedAtIsNull(planId).stream()
                    .anyMatch(t -> !t.getId().equals(tripId) && driverId.equals(t.getDriverId()));
            if (conflict) {
                throw new BusinessException(409, "该司机已分配给本计划的另一车次")
                        .withCode("DRIVER_CONFLICT").withHint("请选择其他司机，或先取消该司机在另一车次的分配");
            }

            trip.setDriverId(driverId);
        }

        recomputeTrip(factoryId, trip, stops, orderById, plan.getOptimizeBy(), false);
        tripRepository.save(trip);
        recomputePlanTotals(factoryId, plan);
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    // ============================================================
    // 确认状态机
    // ============================================================

    @Override
    @Transactional
    public PlanSnapshotDto confirmTrip(String factoryId, String planId, String tripId, Long version, Long userId) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        assertPlanNotConfirmed(plan);
        LogisticsTrip trip = loadTrip(factoryId, planId, tripId);
        assertTripVersion(trip, version);

        if (trip.getStatus() == TripStatus.CONFIRMED) {
            return buildSnapshot(factoryId, plan); // 幂等：重复确认同一车次不报错 (fool-proof-design Rule 4)
        }
        if (trip.getStatus() == TripStatus.NEEDS_VEHICLE) {
            throw new BusinessException(409, "该车次尚未分配车辆，无法确认")
                    .withCode("NEEDS_VEHICLE").withHint("请先分配车辆").withHintTarget("分配车辆");
        }
        if (trip.getStatus() == TripStatus.NEEDS_DRIVER) {
            throw new BusinessException(409, "该车次尚未分配司机，无法确认")
                    .withCode("NEEDS_DRIVER").withHint("请先分配司机").withHintTarget("分配司机");
        }
        if (trip.getStatus() == TripStatus.NEEDS_ROUTE_DATA) {
            throw new BusinessException(409, "该车次缺少路线距离数据，无法确认")
                    .withCode("NEEDS_ROUTE_DATA").withHint("请先补充该车次所有相邻门店间的距离数据");
        }

        trip.setStatus(TripStatus.CONFIRMED);
        tripRepository.save(trip);
        recomputePlanTotals(factoryId, plan);
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    @Override
    @Transactional
    public PlanSnapshotDto confirmPlan(String factoryId, String planId, Long version, Long userId) {
        LogisticsPlan plan = loadPlan(factoryId, planId);
        if (plan.getStatus() == PlanStatus.CONFIRMED || plan.getStatus() == PlanStatus.EXPORTED) {
            return buildSnapshot(factoryId, plan); // 幂等：重复确认同一计划不报错
        }
        if (version != null && !version.equals(plan.getVersion())) {
            throw new BusinessException(409, "计划已被他人修改，请刷新后重试")
                    .withCode("VERSION_CONFLICT").withHint("刷新页面获取最新数据后重试");
        }

        List<LogisticsTrip> trips = tripRepository.findByPlanIdAndDeletedAtIsNull(planId);
        List<LogisticsTrip> notConfirmed = trips.stream().filter(t -> t.getStatus() != TripStatus.CONFIRMED).toList();
        List<String> unassigned = computeUnassignedOrderIds(factoryId, plan, trips);

        if (!notConfirmed.isEmpty() || !unassigned.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (!notConfirmed.isEmpty()) {
                String tripNos = notConfirmed.stream().map(t -> "#" + t.getTripNo())
                        .collect(Collectors.joining("、"));
                reasons.add(notConfirmed.size() + " 个车次尚未确认（" + tripNos + "）");
            }
            if (!unassigned.isEmpty()) {
                reasons.add(unassigned.size() + " 家门店尚未分配到任何车次");
            }
            throw new BusinessException(409, "计划无法确认：" + String.join("；", reasons))
                    .withCode("PLAN_NOT_READY").withHint("请先处理未确认车次或未分配门店");
        }

        plan.setStatus(PlanStatus.CONFIRMED);
        plan.setConfirmedBy(userId == null ? null : String.valueOf(userId));
        plan.setConfirmedAt(LocalDateTime.now());
        planRepository.save(plan);
        return buildSnapshot(factoryId, plan);
    }

    // ============================================================
    // 内部：加载 + 校验 helper
    // ============================================================

    private LogisticsPlan loadPlan(String factoryId, String planId) {
        return planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsPlan", "id", planId));
    }

    private LogisticsTrip loadTrip(String factoryId, String planId, String tripId) {
        return tripRepository.findById(tripId)
                .filter(t -> factoryId.equals(t.getFactoryId()) && planId.equals(t.getPlanId()))
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsTrip", "id", tripId));
    }

    private void assertPlanNotConfirmed(LogisticsPlan plan) {
        if (plan.getStatus() == PlanStatus.CONFIRMED || plan.getStatus() == PlanStatus.EXPORTED) {
            throw new BusinessException(409, "计划已确认，不能修改")
                    .withCode("PLAN_CONFIRMED").withHint("如需调整，请联系管理员处理");
        }
    }

    private void assertTripNotConfirmed(LogisticsTrip trip) {
        if (trip.getStatus() == TripStatus.CONFIRMED) {
            throw new BusinessException(409, "该车次已确认，不能修改")
                    .withCode("TRIP_CONFIRMED").withHint("如需调整，请先联系管理员处理该车次的确认状态");
        }
    }

    private void assertTripVersion(LogisticsTrip trip, Long version) {
        if (version != null && !version.equals(trip.getVersion())) {
            throw new BusinessException(409, "该车次已被他人修改，请刷新后重试")
                    .withCode("VERSION_CONFLICT").withHint("刷新页面获取最新数据后重试");
        }
    }

    // ============================================================
    // 内部：重算（复用 Phase 3 几何/距离组装，见类头注释）
    // ============================================================

    /**
     * 重算一个车次的距离/体积/重量/装载率/状态 —— 几何组装 100% 复用
     * {@link LogisticsRoutingAlgorithm#assembleGeometry}（同一段代码，同一套缺边诚实降级规则），
     * 不在这里重新实现 km/status 逻辑。
     *
     * <p><b>道路路线缓存维护 (档1-B)</b>：{@code stopsChanged=true} (reorder/move) 时旧折线
     * 已失真 → 先清空 (绝不给前端画过期的错误路线)，随后与"车次已定车但尚无折线"的情况一起
     * 走同一条补算路径 (多提供商链, 见 {@link LogisticsRoutingService#computeRoadRoute})：
     * 成功 → 折线/时长/总里程以 direction 结果为单一事实源；失败 → 无折线 + 保持边距离和
     * (诚实降级)。{@code stopsChanged=false} (setVehicle/setDriver) 且已有折线 → 不动、
     * 不浪费地图配额 (道路路线与车辆/司机无关)。
     */
    private void recomputeTrip(String factoryId, LogisticsTrip trip, List<LogisticsStop> orderedStops,
            Map<String, LogisticsDeliveryOrder> orderById, RouteOptimizeMode optimizeBy, boolean stopsChanged) {
        List<LogisticsRoutingAlgorithm.OrderInput> orderInputs = orderedStops.stream()
                .map(stop -> {
                    LogisticsDeliveryOrder o = orderById.get(stop.getDeliveryOrderId());
                    return new LogisticsRoutingAlgorithm.OrderInput(
                            stop.getDeliveryOrderId(),
                            o == null ? stop.getDeliveryOrderId() : o.getStoreCode(),
                            o == null ? null : o.getAreaCode(),
                            o == null ? BigDecimal.ZERO : nvl(o.getVolumeCbm()),
                            o == null ? BigDecimal.ZERO : nvl(o.getWeightKg()),
                            o == null ? null : o.getDeliveryWindowStart(),
                            o == null ? null : o.getDeliveryWindowEnd());
                })
                .toList();

        // 人工调整 (reorder/move) 会产生新的相邻门店对 —— 这些新边可能尚无距离数据。
        // 复用初次生成的同一套高德补边逻辑 (routingService.resolveEdgeKm)：先查库边, 缺则调高德驾车距离
        // 并缓存为 MAP_PROVIDER 边; 失败仍返 null → 诚实置 NEEDS_ROUTE_DATA。构建各途经点坐标供补边用。
        Map<String, double[]> coordsByPoint = new LinkedHashMap<>();
        coordsByPoint.put("DEPOT", routingService.depotCoord());
        for (LogisticsStop stop : orderedStops) {
            LogisticsDeliveryOrder o = orderById.get(stop.getDeliveryOrderId());
            if (o != null && o.getLongitude() != null && o.getLatitude() != null) {
                coordsByPoint.put(o.getStoreCode(),
                        new double[] {o.getLongitude().doubleValue(), o.getLatitude().doubleValue()});
            }
        }
        LogisticsRoutingAlgorithm.DistanceLookup lookup = (from, to) ->
                routingService.resolveEdgeKm(factoryId, from, to, coordsByPoint);

        LogisticsRoutingAlgorithm.GeometryResult geometry =
                LogisticsRoutingAlgorithm.assembleGeometry(orderInputs, lookup);

        BigDecimal totalDistanceKm = geometry.missing()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : geometry.distances().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        for (int i = 0; i < orderedStops.size(); i++) {
            BigDecimal legKm = geometry.missing()
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : geometry.distances().get(i);
            orderedStops.get(i).setLegDistanceKm(legKm);
        }
        if (!orderedStops.isEmpty()) {
            stopRepository.saveAll(orderedStops);
        }

        BigDecimal totalVolumeCbm = orderInputs.stream()
                .map(LogisticsRoutingAlgorithm.OrderInput::volumeCbm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeightKg = orderInputs.stream()
                .map(LogisticsRoutingAlgorithm.OrderInput::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal loadRate = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal weightLoadRate = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        if (trip.getVehicleId() != null) {
            LogisticsVehicleProfile vp = vehicleProfileRepository
                    .findByVehicleIdAndDeletedAtIsNull(trip.getVehicleId()).orElse(null);
            if (vp != null) {
                if (vp.getCapacityCbm() != null && vp.getCapacityCbm().compareTo(BigDecimal.ZERO) > 0) {
                    loadRate = totalVolumeCbm.divide(vp.getCapacityCbm(), 4, RoundingMode.HALF_UP);
                }
                if (vp.getMaxWeightKg() != null && vp.getMaxWeightKg().compareTo(BigDecimal.ZERO) > 0) {
                    weightLoadRate = totalWeightKg.divide(vp.getMaxWeightKg(), 4, RoundingMode.HALF_UP);
                }
            }
        }

        TripStatus status;
        if (geometry.missing()) {
            status = TripStatus.NEEDS_ROUTE_DATA;
        } else if (trip.getVehicleId() == null) {
            status = TripStatus.NEEDS_VEHICLE;
        } else if (trip.getDriverId() == null) {
            status = TripStatus.NEEDS_DRIVER;
        } else {
            status = TripStatus.DRAFT;
        }

        trip.setTotalDistanceKm(totalDistanceKm);
        trip.setTotalVolumeCbm(totalVolumeCbm);
        trip.setTotalWeightKg(totalWeightKg);
        trip.setLoadRate(loadRate);
        trip.setWeightLoadRate(weightLoadRate);
        trip.setStatus(status);

        // ---- 档1-B: 道路路线缓存维护 (见方法头注释; roadPath 列, 不动 SVG 语义的 geometry 列) ----
        if (stopsChanged) {
            trip.setRoadPath(null);
            trip.setTotalDurationMin(null);
            trip.setRouteProvider(null);
        }
        boolean roadPathMissing = trip.getRoadPath() == null || trip.getRoadPath().isEmpty();
        if (roadPathMissing && status != TripStatus.NEEDS_ROUTE_DATA
                && trip.getVehicleId() != null && !orderedStops.isEmpty()) {
            List<LogisticsDeliveryOrder> visitOrders = orderedStops.stream()
                    .map(s -> orderById.get(s.getDeliveryOrderId()))
                    .toList();
            Optional<DrivingRoute> road = routingService.computeRoadRoute(optimizeBy, visitOrders);
            if (road.isPresent()) {
                LogisticsRoutingService.applyRoadRoute(trip, road.get());
            }
        }
    }

    /**
     * (trip_id, sequence_no) 是 DB 唯一索引（非 deferred，Postgres 逐语句立即检查）。
     * 批量重排/跨车次移动若直接把目标顺序 1..n 写回，可能在同一批更新内瞬时撞车（如交换
     * 两个 stop 的顺序号）。用"负数临时占位"两阶段更新彻底规避。
     */
    private void renumberSequential(List<LogisticsStop> stopsInFinalOrder) {
        if (stopsInFinalOrder.isEmpty()) {
            return;
        }
        for (int i = 0; i < stopsInFinalOrder.size(); i++) {
            stopsInFinalOrder.get(i).setSequenceNo(-(i + 1));
        }
        stopRepository.saveAll(stopsInFinalOrder);
        stopRepository.flush();
        for (int i = 0; i < stopsInFinalOrder.size(); i++) {
            stopsInFinalOrder.get(i).setSequenceNo(i + 1);
        }
        stopRepository.saveAll(stopsInFinalOrder);
        stopRepository.flush();
    }

    // ============================================================
    // 内部：计划聚合重算 + 未分配门店
    // ============================================================

    private void recomputePlanTotals(String factoryId, LogisticsPlan plan) {
        List<LogisticsTrip> trips = tripRepository.findByPlanIdAndDeletedAtIsNull(plan.getId());
        int totalStores = 0;
        BigDecimal totalDistanceKm = BigDecimal.ZERO;
        boolean needsAction = false;
        for (LogisticsTrip t : trips) {
            int stopCount = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(t.getId()).size();
            totalStores += stopCount;
            totalDistanceKm = totalDistanceKm.add(nvl(t.getTotalDistanceKm()));
            if (t.getStatus() == TripStatus.NEEDS_VEHICLE || t.getStatus() == TripStatus.NEEDS_DRIVER
                    || t.getStatus() == TripStatus.NEEDS_ROUTE_DATA) {
                needsAction = true;
            }
        }
        if (!computeUnassignedOrderIds(factoryId, plan, trips).isEmpty()) {
            needsAction = true;
        }

        plan.setTotalStores(totalStores);
        plan.setTotalTrips(trips.size());
        plan.setTotalDistanceKm(totalDistanceKm.setScale(2, RoundingMode.HALF_UP));
        // 防御性：本方法只从"计划未确认"路径调用，但保留守卫，避免未来误用时误改已确认计划的状态
        if (plan.getStatus() != PlanStatus.CONFIRMED && plan.getStatus() != PlanStatus.EXPORTED) {
            plan.setStatus(needsAction ? PlanStatus.NEEDS_ACTION : PlanStatus.DRAFT);
        }
    }

    private List<String> computeUnassignedOrderIds(String factoryId, LogisticsPlan plan, List<LogisticsTrip> trips) {
        List<LogisticsDeliveryOrder> orders = orderRepository.findByFactoryIdAndBatchId(factoryId, plan.getOrderBatchId());
        Set<String> assigned = new HashSet<>();
        for (LogisticsTrip t : trips) {
            for (LogisticsStop s : stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(t.getId())) {
                assigned.add(s.getDeliveryOrderId());
            }
        }
        return orders.stream()
                .filter(o -> o.getStatus() != DeliveryOrderStatus.CANCELLED)
                .map(LogisticsDeliveryOrder::getId)
                .filter(id -> !assigned.contains(id))
                .toList();
    }

    // ============================================================
    // 内部：快照组装
    // ============================================================

    private PlanSnapshotDto buildSnapshot(String factoryId, LogisticsPlan plan) {
        List<LogisticsTrip> trips = tripRepository.findByPlanIdAndDeletedAtIsNull(plan.getId());
        Map<String, List<LogisticsStop>> stopsByTripId = new HashMap<>();
        Set<String> assignedOrderIds = new HashSet<>();
        for (LogisticsTrip t : trips) {
            List<LogisticsStop> stops = stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(t.getId());
            stopsByTripId.put(t.getId(), stops);
            for (LogisticsStop s : stops) {
                assignedOrderIds.add(s.getDeliveryOrderId());
            }
        }

        Map<String, LogisticsDeliveryOrder> orderById = loadOrdersForBatch(factoryId, plan.getOrderBatchId());
        List<String> unassignedIds = orderById.values().stream()
                .filter(o -> o.getStatus() != DeliveryOrderStatus.CANCELLED)
                .map(LogisticsDeliveryOrder::getId)
                .filter(id -> !assignedOrderIds.contains(id))
                .toList();

        Set<String> vehicleIds = trips.stream().map(LogisticsTrip::getVehicleId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, Vehicle> vehicleById = vehicleIds.isEmpty()
                ? Collections.emptyMap()
                : vehicleRepository.findAllById(vehicleIds).stream()
                        .collect(Collectors.toMap(Vehicle::getId, v -> v));

        Set<String> driverIds = trips.stream().map(LogisticsTrip::getDriverId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, LogisticsDriver> driverById = driverIds.isEmpty()
                ? Collections.emptyMap()
                : driverRepository.findAllById(driverIds).stream()
                        .collect(Collectors.toMap(LogisticsDriver::getId, d -> d));

        return mapper.toSnapshot(plan, trips, stopsByTripId, orderById, vehicleById, driverById, unassignedIds);
    }

    private PlanDto toPlanDto(LogisticsPlan p) {
        return PlanDto.builder()
                .id(p.getId())
                .factoryId(p.getFactoryId())
                .orderBatchId(p.getOrderBatchId())
                .planDate(p.getPlanDate())
                .planNumber(p.getPlanNumber())
                .targetLoadPct(p.getTargetLoadPct())
                .status(p.getStatus())
                .distanceSource(p.getDistanceSource())
                .optimizeBy(p.getOptimizeBy())
                .totalStores(p.getTotalStores())
                .totalTrips(p.getTotalTrips())
                .totalDistanceKm(p.getTotalDistanceKm())
                .createdBy(p.getCreatedBy())
                .confirmedBy(p.getConfirmedBy())
                .confirmedAt(p.getConfirmedAt())
                .version(p.getVersion())
                .build();
    }

    private Map<String, LogisticsDeliveryOrder> loadOrdersForBatch(String factoryId, String batchId) {
        return orderRepository.findByFactoryIdAndBatchId(factoryId, batchId).stream()
                .collect(Collectors.toMap(LogisticsDeliveryOrder::getId, o -> o));
    }

    // ============================================================
    // 内部：小工具（区域/班次/求和 —— 与 LogisticsRoutingAlgorithm 语义一致的独立小实现，
    // 不是"重算 km/status"逻辑，见类头注释区分）
    // ============================================================

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal sumField(List<LogisticsStop> stops, Map<String, LogisticsDeliveryOrder> orderById,
            java.util.function.Function<LogisticsDeliveryOrder, BigDecimal> extractor) {
        BigDecimal total = BigDecimal.ZERO;
        for (LogisticsStop s : stops) {
            LogisticsDeliveryOrder o = orderById.get(s.getDeliveryOrderId());
            if (o != null) {
                total = total.add(nvl(extractor.apply(o)));
            }
        }
        return total;
    }

    /** {@code service_areas} 逗号分隔字符串 -&gt; Set（同 {@code LogisticsRoutingService#parseServiceAreas}）。 */
    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** 车辆区域覆盖 —— 未配置区域(空) = 覆盖不了任何门店（严格，同算法 Step A {@code areaMatches}）。 */
    private static boolean vehicleRegionCoversAll(Set<String> serviceAreas, List<LogisticsStop> stops,
            Map<String, LogisticsDeliveryOrder> orderById) {
        if (serviceAreas == null || serviceAreas.isEmpty()) {
            return false;
        }
        for (LogisticsStop s : stops) {
            LogisticsDeliveryOrder o = orderById.get(s.getDeliveryOrderId());
            String area = o == null ? null : o.getAreaCode();
            if (area == null || !serviceAreas.contains(area)) {
                return false;
            }
        }
        return true;
    }

    /** 司机区域覆盖 —— 未配置区域(空) = 无限制（宽松，同算法 Step D {@code regionCovers}）。 */
    private static boolean driverRegionCoversAll(Set<String> serviceAreas, List<LogisticsStop> stops,
            Map<String, LogisticsDeliveryOrder> orderById) {
        if (serviceAreas == null || serviceAreas.isEmpty()) {
            return true;
        }
        for (LogisticsStop s : stops) {
            LogisticsDeliveryOrder o = orderById.get(s.getDeliveryOrderId());
            String area = o == null ? null : o.getAreaCode();
            if (area == null || !serviceAreas.contains(area)) {
                return false;
            }
        }
        return true;
    }

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    /** 车次所需时间窗 = 停靠点送达窗口并集（同算法 Step D {@code computeTripWindow}，源用 stop 上已落的窗口）。 */
    private static TimeWindow computeTripWindow(List<LogisticsStop> stops) {
        LocalTime start = null;
        LocalTime end = null;
        for (LogisticsStop s : stops) {
            if (s.getArrivalWindowStart() == null || s.getArrivalWindowStart().isBlank()
                    || s.getArrivalWindowEnd() == null || s.getArrivalWindowEnd().isBlank()) {
                continue;
            }
            LocalTime st = LocalTime.parse(s.getArrivalWindowStart());
            LocalTime en = LocalTime.parse(s.getArrivalWindowEnd());
            start = (start == null || st.isBefore(start)) ? st : start;
            end = (end == null || en.isAfter(end)) ? en : end;
        }
        return (start == null || end == null) ? null : new TimeWindow(start, end);
    }

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
}
