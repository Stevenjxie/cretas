package com.cretas.aims.logistics.mapper;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.plan.PlanSnapshotDto;
import com.cretas.aims.logistics.dto.plan.StopDto;
import com.cretas.aims.logistics.dto.plan.TripDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 组装 {@link PlanSnapshotDto}（plan + trips + stops + 未分配门店 + 车辆/司机展示字段）—
 * 每次写操作 (generate/regenerate/reorder/move/setVehicle/setDriver/confirmTrip/confirmPlan)
 * 都靠本类产出统一的最新快照返回给前端 (spec §11.3, handoff §12.3 "同一份 PlanSnapshot")。
 *
 * <p>纯映射，不做 DB 查询 —— 调用方 (service 层) 负责预先批量加载
 * plan/trips/stops/orders/vehicle/driver，避免 mapper 内部 N+1。
 */
@Component
public class LogisticsPlanMapper {

    /**
     * @param plan               计划本体
     * @param trips              该计划下全部有效车次
     * @param stopsByTripId      tripId -> 该车次有效停靠点 (已按 sequenceNo 排序)
     * @param orderById          deliveryOrderId -> 订单实体 (覆盖本批次全部订单，含未分配的)
     * @param vehicleById        vehicleId -> {@link Vehicle} (仅覆盖被引用到的车辆)
     * @param driverById         driverId -> {@link LogisticsDriver} (仅覆盖被引用到的司机)
     * @param unassignedOrderIds 本批次未分配到任何有效车次的订单 id 列表
     */
    public PlanSnapshotDto toSnapshot(
            LogisticsPlan plan,
            List<LogisticsTrip> trips,
            Map<String, List<LogisticsStop>> stopsByTripId,
            Map<String, LogisticsDeliveryOrder> orderById,
            Map<String, Vehicle> vehicleById,
            Map<String, LogisticsDriver> driverById,
            List<String> unassignedOrderIds) {

        List<TripDto> tripDtos = new ArrayList<>(trips.size());
        int assignedVehicleCount = 0;
        int additionalVehicleCount = 0;
        java.util.Set<String> distinctVehicleIds = new java.util.HashSet<>();
        for (LogisticsTrip trip : trips) {
            List<LogisticsStop> stops = stopsByTripId.getOrDefault(trip.getId(), Collections.emptyList());
            Vehicle vehicle = trip.getVehicleId() == null ? null : vehicleById.get(trip.getVehicleId());
            LogisticsDriver driver = trip.getDriverId() == null ? null : driverById.get(trip.getDriverId());
            tripDtos.add(toTripDto(trip, stops, orderById, vehicle, driver));
            if (trip.getVehicleId() != null) {
                distinctVehicleIds.add(trip.getVehicleId());
            } else {
                additionalVehicleCount++;
            }
        }
        assignedVehicleCount = distinctVehicleIds.size();

        return PlanSnapshotDto.builder()
                .id(plan.getId())
                .factoryId(plan.getFactoryId())
                .orderBatchId(plan.getOrderBatchId())
                .planDate(plan.getPlanDate())
                .planNumber(plan.getPlanNumber())
                .targetLoadPct(plan.getTargetLoadPct())
                .status(plan.getStatus())
                .distanceSource(plan.getDistanceSource())
                .optimizeBy(plan.getOptimizeBy())
                .totalStores(plan.getTotalStores())
                .totalTrips(plan.getTotalTrips())
                .totalDistanceKm(plan.getTotalDistanceKm())
                .createdBy(plan.getCreatedBy())
                .confirmedBy(plan.getConfirmedBy())
                .confirmedAt(plan.getConfirmedAt())
                .version(plan.getVersion())
                .trips(tripDtos)
                .unassignedStoreIds(unassignedOrderIds)
                .assignedVehicleCount(assignedVehicleCount)
                .additionalVehicleCount(additionalVehicleCount)
                .build();
    }

    public TripDto toTripDto(LogisticsTrip trip, List<LogisticsStop> stops,
            Map<String, LogisticsDeliveryOrder> orderById, Vehicle vehicle, LogisticsDriver driver) {
        List<String> storeIds = new ArrayList<>(stops.size());
        List<String> segmentKeys = new ArrayList<>(stops.size());
        List<java.math.BigDecimal> segmentDistances = new ArrayList<>(stops.size());
        List<StopDto> stopDtos = new ArrayList<>(stops.size());

        String prevKey = "DEPOT";
        for (LogisticsStop stop : stops) {
            LogisticsDeliveryOrder order = orderById.get(stop.getDeliveryOrderId());
            storeIds.add(stop.getDeliveryOrderId());
            String storeCode = order == null ? stop.getDeliveryOrderId() : order.getStoreCode();
            segmentKeys.add(prevKey + "->" + storeCode);
            prevKey = storeCode;
            segmentDistances.add(stop.getLegDistanceKm());
            stopDtos.add(toStopDto(stop, order));
        }

        return TripDto.builder()
                .id(trip.getId())
                .planId(trip.getPlanId())
                .tripNo(trip.getTripNo())
                .vehicleId(trip.getVehicleId())
                .driverId(trip.getDriverId())
                .vehiclePlate(vehicle == null ? null : vehicle.getPlateNumber())
                .driverName(driver == null ? null : driver.getName())
                .storeIds(storeIds)
                .segmentKeys(segmentKeys)
                // geometry = 前端 SVG 兜底地图 {x,y} 像素点语义, 后端不产出 → 恒空数组;
                // 道路折线走独立的 roadPath (档1-B, 从 road_path 持久化列直读缓存)。
                .geometry(Collections.emptyList())
                .roadPath(trip.getRoadPath() == null ? Collections.emptyList() : trip.getRoadPath())
                .segmentDistances(segmentDistances)
                .totalDistanceKm(trip.getTotalDistanceKm())
                .totalDurationMin(trip.getTotalDurationMin())
                .routeProvider(trip.getRouteProvider())
                .totalVolumeCbm(trip.getTotalVolumeCbm())
                .totalWeightKg(trip.getTotalWeightKg())
                .loadRate(trip.getLoadRate())
                .weightLoadRate(trip.getWeightLoadRate())
                .status(toWireStatus(trip.getStatus()))
                .stops(stopDtos)
                .version(trip.getVersion())
                // 档4 多趟排班时刻 (nullable — 缺坐标/无车/人工调整后为 null, 诚实不伪造)
                .plannedDepartMin(trip.getPlannedDepartMin())
                .returnToDepotMin(trip.getReturnToDepotMin())
                .lateReturn(trip.getLateReturn())
                .vehicleTripSeq(trip.getVehicleTripSeq())
                .build();
    }

    public StopDto toStopDto(LogisticsStop stop, LogisticsDeliveryOrder order) {
        return StopDto.builder()
                .id(stop.getId())
                .tripId(stop.getTripId())
                .deliveryOrderId(stop.getDeliveryOrderId())
                .sequenceNo(stop.getSequenceNo())
                .legDistanceKm(stop.getLegDistanceKm())
                .arrivalWindowStart(stop.getArrivalWindowStart())
                .arrivalWindowEnd(stop.getArrivalWindowEnd())
                .order(order == null ? null : toOrderDto(order))
                .build();
    }

    /** 镜像 {@code LogisticsOrderImportServiceImpl} 的 DeliveryOrderDto 字段映射（Phase 2 既有约定）。 */
    public DeliveryOrderDto toOrderDto(LogisticsDeliveryOrder order) {
        return DeliveryOrderDto.builder()
                .id(order.getId())
                .factoryId(order.getFactoryId())
                .batchId(order.getBatchId())
                .storeCode(order.getStoreCode())
                .storeName(order.getStoreName())
                .address(order.getAddress())
                .areaCode(order.getAreaCode())
                .pieces(order.getPieces())
                .boxes(order.getBoxes())
                .weightKg(order.getWeightKg())
                .volumeCbm(order.getVolumeCbm())
                .windowStart(order.getDeliveryWindowStart())
                .windowEnd(order.getDeliveryWindowEnd())
                .longitude(order.getLongitude())
                .latitude(order.getLatitude())
                .locationStatus(order.getLocationStatus())
                .status(order.getStatus())
                .sourceRowNumber(order.getSourceRowNumber())
                .version(order.getVersion())
                .build();
    }

    /**
     * Java {@link TripStatus} 枚举 (UPPERCASE) -&gt; 前端契约 lowercase 字符串
     * (对齐 {@code web-admin/src/api/logistics.ts} {@code TripStatus} type 和现有
     * Vue 组件消费的字面量，见 {@link TripDto} 类注释)。
     */
    public static String toWireStatus(TripStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }
}
