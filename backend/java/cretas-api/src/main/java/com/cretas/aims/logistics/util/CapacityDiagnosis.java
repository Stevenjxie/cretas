package com.cretas.aims.logistics.util;

import com.cretas.aims.logistics.dto.plan.CapacityDiagnosisDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.CapacityVerdict;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运力诊断纯计算 — 独立于 {@code service/routing} 排线算法包（不改动、不依赖该包任何类，
 * 见 feat/logi-capacity-diagnosis brief 硬约束）。只读 trips/orders/车辆档案算出「够不够」，
 * 不做任何 DB 查询/写入（同 {@code LogisticsPlanMapper} 的"纯映射，不查库"风格）。
 *
 * <p>诊断口径（诚实计算，不编造数据 — CLAUDE.md 核心原则「禁止降级处理」）：
 * <ul>
 *   <li>{@code totalDemandCbm/Kg} = 本批非取消订单体积/重量之和。</li>
 *   <li>{@code fleetSingleRoundCbm/Kg} = 该工厂在册活跃车辆容量/载重之和（"整支车队"口径，
 *       与本计划实际用了几辆车无关 — 回答"车队理论上单轮能装多少"）。</li>
 *   <li>{@code vehicleCount} = 本计划已分配车次里 distinct 车辆数；
 *       {@code usedTripCount} = 已分配车次数（可能 &gt; vehicleCount，即某车跑多趟）。</li>
 *   <li>{@code UNSERVABLE}：仍有订单未排入任何车次（无车覆盖区域 / 单件超最大车）。</li>
 *   <li>{@code INSUFFICIENT}：全部订单已排入车次，但 usedTripCount &gt; vehicleCount
 *       （至少一辆车需回仓补货再出发 — 单轮运力不足，非"排不下"）。</li>
 *   <li>{@code SUFFICIENT}：其余情况（一轮送完，无需回仓）。</li>
 * </ul>
 */
public final class CapacityDiagnosis {

    private CapacityDiagnosis() {
    }

    public static CapacityDiagnosisDto diagnose(
            Collection<LogisticsDeliveryOrder> orders,
            List<LogisticsTrip> trips,
            Collection<LogisticsVehicleProfile> fleetVehicles,
            int unassignedCount) {

        List<LogisticsDeliveryOrder> activeOrders = orders == null ? List.of() : orders.stream()
                .filter(o -> o.getStatus() != DeliveryOrderStatus.CANCELLED)
                .toList();

        BigDecimal totalDemandCbm = activeOrders.stream()
                .map(o -> nvl(o.getVolumeCbm())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDemandKg = activeOrders.stream()
                .map(o -> nvl(o.getWeightKg())).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 只统计「服务本批订单所在区域」的车队 —— 与本批完全不相关区域的车不算进运力
        // (否则同一 factory 下服务别的区域的车会虚增"车队容量", 让"够/不够"判定失真;
        //  同一系统里可并存"够"和"不够"两套场景各自诊断准确)。
        // 订单无区域信息时诚实退化: 不过滤, 用全部在册车 (与本改动前行为逐字段一致)。
        Set<String> orderAreas = activeOrders.stream()
                .map(LogisticsDeliveryOrder::getAreaCode)
                .filter(a -> a != null && !a.isBlank())
                .collect(Collectors.toSet());
        Collection<LogisticsVehicleProfile> fleetAll = fleetVehicles == null ? List.of() : fleetVehicles;
        Collection<LogisticsVehicleProfile> fleet = orderAreas.isEmpty()
                ? fleetAll
                : fleetAll.stream().filter(v -> servesAnyArea(v.getServiceAreas(), orderAreas)).toList();
        BigDecimal fleetSingleRoundCbm = fleet.stream()
                .map(v -> nvl(v.getCapacityCbm())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fleetSingleRoundKg = fleet.stream()
                .map(v -> nvl(v.getMaxWeightKg())).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LogisticsTrip> tripList = trips == null ? List.of() : trips;
        List<LogisticsTrip> usedTrips = tripList.stream()
                .filter(t -> t.getVehicleId() != null)
                .toList();
        Map<String, Long> tripCountByVehicle = usedTrips.stream()
                .collect(Collectors.groupingBy(LogisticsTrip::getVehicleId, Collectors.counting()));
        int vehicleCount = tripCountByVehicle.size();
        int usedTripCount = usedTrips.size();
        int multiTripVehicleCount = (int) tripCountByVehicle.values().stream().filter(c -> c > 1).count();

        CapacityVerdict verdict;
        if (unassignedCount > 0) {
            verdict = CapacityVerdict.UNSERVABLE;
        } else if (usedTripCount > vehicleCount) {
            verdict = CapacityVerdict.INSUFFICIENT;
        } else {
            verdict = CapacityVerdict.SUFFICIENT;
        }

        BigDecimal suggestedAddCbm = BigDecimal.ZERO;
        if (verdict != CapacityVerdict.SUFFICIENT) {
            BigDecimal gap = totalDemandCbm.subtract(fleetSingleRoundCbm);
            if (gap.compareTo(BigDecimal.ZERO) > 0) {
                suggestedAddCbm = gap.setScale(0, RoundingMode.CEILING);
            }
        }

        BigDecimal demandCbmDisplay = totalDemandCbm.setScale(1, RoundingMode.HALF_UP);
        BigDecimal demandKgDisplay = totalDemandKg.setScale(1, RoundingMode.HALF_UP);
        BigDecimal fleetCbmDisplay = fleetSingleRoundCbm.setScale(1, RoundingMode.HALF_UP);
        BigDecimal fleetKgDisplay = fleetSingleRoundKg.setScale(1, RoundingMode.HALF_UP);

        String message = buildMessage(verdict, vehicleCount, activeOrders.size(), demandCbmDisplay,
                fleetCbmDisplay, usedTripCount, suggestedAddCbm, unassignedCount);

        return CapacityDiagnosisDto.builder()
                .verdict(verdict)
                .totalDemandCbm(demandCbmDisplay)
                .totalDemandKg(demandKgDisplay)
                .fleetSingleRoundCbm(fleetCbmDisplay)
                .fleetSingleRoundKg(fleetKgDisplay)
                .vehicleCount(vehicleCount)
                .usedTripCount(usedTripCount)
                .multiTripVehicleCount(multiTripVehicleCount)
                .unassignedCount(unassignedCount)
                .suggestedAddCbm(suggestedAddCbm)
                .message(message)
                .build();
    }

    private static String buildMessage(CapacityVerdict verdict, int vehicleCount, int totalStores,
            BigDecimal demandCbm, BigDecimal fleetCbm, int usedTripCount, BigDecimal suggestedAddCbm,
            int unassignedCount) {
        return switch (verdict) {
            case SUFFICIENT -> String.format(
                    "运力充足 — %d 辆车一轮可送完 %d 店 / %sm³。",
                    vehicleCount, totalStores, demandCbm.toPlainString());
            case INSUFFICIENT -> String.format(
                    "车队单轮运力不足 — 本批 %sm³ 超过可服务本批区域的车队单轮 %sm³，需跑 %d 趟（有车回仓补货再出发）。"
                            + "建议增补约 %sm³ 运力可减少回仓趟次。",
                    demandCbm.toPlainString(), fleetCbm.toPlainString(), usedTripCount,
                    suggestedAddCbm.toPlainString());
            case UNSERVABLE -> String.format(
                    "%d 单暂无法派送 — 所在区域无车覆盖，或单件体积/重量超最大车。请为相应区域增派车辆或联系管理员。",
                    unassignedCount);
        };
    }

    /** 车辆 service_areas (逗号分隔) 是否服务本批任一订单区域。无区域配置的车不算服务任何区域。 */
    private static boolean servesAnyArea(String serviceAreasCsv, Set<String> orderAreas) {
        if (serviceAreasCsv == null || serviceAreasCsv.isBlank()) {
            return false;
        }
        for (String a : serviceAreasCsv.split(",")) {
            if (orderAreas.contains(a.trim())) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
