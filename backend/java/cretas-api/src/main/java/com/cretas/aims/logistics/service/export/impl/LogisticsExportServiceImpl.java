package com.cretas.aims.logistics.service.export.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.service.export.LogisticsExportService;
import com.cretas.aims.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 5 — {@link LogisticsExportService} 实现。直接从已落库的
 * {@link LogisticsPlan}/{@link LogisticsTrip}/{@link LogisticsStop} 读值组行，
 * CSV 与 XLSX 共用同一份行数据 (只是编码不同) — 保证两种格式互相一致，且都与
 * 计划详情逐字段一致 (不重新计算/不换口径)。
 *
 * <p>里程诚实降级 (spec §4/§6 决策6): {@code TripStatus.NEEDS_ROUTE_DATA} 车次的
 * {@code totalDistanceKm} 落库就是 0（算法从不伪造缺边里程），导出原样显示该 0 并附注
 * "待补路线数据"，不重新计算/不拼直线距离冒充公里数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsExportServiceImpl implements LogisticsExportService {

    private static final String UNASSIGNED_LABEL = "未分配门店（需人工处理，未纳入任何车次）";
    private static final String NEEDS_ROUTE_DATA_TEXT = "0（待补路线数据）";
    private static final String PENDING_VEHICLE_TEXT = "待匹配车辆";
    private static final String PENDING_DRIVER_TEXT = "待匹配司机";
    private static final String NO_STOPS_TEXT = "-";
    private static final List<String> TRIP_HEADER = List.of(
            "线路", "车辆", "司机", "门店数", "方数(m³)", "重量(kg)", "装载率", "公里数", "配送顺序");
    private static final List<String> UNASSIGNED_HEADER = List.of(
            "门店编码", "门店名称", "体积(m³)", "重量(kg)");

    private final LogisticsPlanRepository planRepository;
    private final LogisticsTripRepository tripRepository;
    private final LogisticsStopRepository stopRepository;
    private final LogisticsDeliveryOrderRepository deliveryOrderRepository;
    private final VehicleRepository vehicleRepository;
    private final LogisticsDriverRepository driverRepository;

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv(String factoryId, String planId) {
        return writeCsv(buildRows(factoryId, planId));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportXlsx(String factoryId, String planId) {
        return writeXlsx(buildRows(factoryId, planId));
    }

    // ============================================================
    // 行构建 — 直接读已落库值, 不重新计算 (handoff §16.1: 导出必须与计划详情逐字段一致)
    // ============================================================

    private List<List<Object>> buildRows(String factoryId, String planId) {
        LogisticsPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LogisticsPlan", "id", planId));

        List<LogisticsTrip> trips = tripRepository.findByPlanIdAndDeletedAtIsNull(planId).stream()
                .sorted(Comparator.comparing(LogisticsTrip::getTripNo))
                .toList();

        List<LogisticsDeliveryOrder> batchOrders =
                deliveryOrderRepository.findByFactoryIdAndBatchId(factoryId, plan.getOrderBatchId());
        Map<String, LogisticsDeliveryOrder> orderById = batchOrders.stream()
                .collect(Collectors.toMap(LogisticsDeliveryOrder::getId, o -> o, (a, b) -> a));

        Map<String, Vehicle> vehicleCache = new HashMap<>();
        Map<String, LogisticsDriver> driverCache = new HashMap<>();
        Set<String> assignedOrderIds = new HashSet<>();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(TRIP_HEADER));

        for (LogisticsTrip trip : trips) {
            List<LogisticsStop> stops =
                    stopRepository.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip.getId());

            List<String> storeNames = new ArrayList<>();
            for (LogisticsStop stop : stops) {
                assignedOrderIds.add(stop.getDeliveryOrderId());
                LogisticsDeliveryOrder order = orderById.get(stop.getDeliveryOrderId());
                storeNames.add(order != null ? order.getStoreName() : stop.getDeliveryOrderId());
            }

            rows.add(List.of(
                    plan.getPlanNumber() + "-T" + trip.getTripNo(),
                    resolveVehicleText(factoryId, trip.getVehicleId(), vehicleCache),
                    resolveDriverText(factoryId, trip.getDriverId(), driverCache),
                    stops.size(),
                    plainNumber(trip.getTotalVolumeCbm()),
                    plainNumber(trip.getTotalWeightKg()),
                    loadRateText(trip.getLoadRate()),
                    distanceText(trip),
                    storeNames.isEmpty() ? NO_STOPS_TEXT : String.join(" -> ", storeNames)
            ));
        }

        appendUnassignedSection(rows, batchOrders, assignedOrderIds);
        return rows;
    }

    /**
     * 未分配门店 (fool-proof-design Rule 5: 不静默丢弃, 明确列出需人工处理项) —
     * 已提交但没被任何车次容纳的门店 (单店超硬容量 / 缺车 / 缺可用区域候选)。
     * CANCELLED 订单不算"未分配" (它们是有意排除, 不是排线问题)。
     */
    private void appendUnassignedSection(List<List<Object>> rows,
            List<LogisticsDeliveryOrder> batchOrders, Set<String> assignedOrderIds) {
        List<LogisticsDeliveryOrder> unassigned = batchOrders.stream()
                .filter(o -> o.getStatus() != DeliveryOrderStatus.CANCELLED)
                .filter(o -> !assignedOrderIds.contains(o.getId()))
                .sorted(Comparator.comparing(LogisticsDeliveryOrder::getStoreCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (unassigned.isEmpty()) {
            return;
        }

        rows.add(List.of(UNASSIGNED_LABEL));
        rows.add(new ArrayList<>(UNASSIGNED_HEADER));
        for (LogisticsDeliveryOrder order : unassigned) {
            rows.add(List.of(
                    nvlStr(order.getStoreCode()),
                    nvlStr(order.getStoreName()),
                    plainNumber(order.getVolumeCbm()),
                    plainNumber(order.getWeightKg())
            ));
        }
    }

    private String resolveVehicleText(String factoryId, String vehicleId, Map<String, Vehicle> cache) {
        if (vehicleId == null) {
            return PENDING_VEHICLE_TEXT;
        }
        Vehicle vehicle = cache.computeIfAbsent(vehicleId,
                id -> vehicleRepository.findByIdAndFactoryId(id, factoryId).orElse(null));
        return vehicle != null && vehicle.getPlateNumber() != null ? vehicle.getPlateNumber() : vehicleId;
    }

    private String resolveDriverText(String factoryId, String driverId, Map<String, LogisticsDriver> cache) {
        if (driverId == null) {
            return PENDING_DRIVER_TEXT;
        }
        LogisticsDriver driver = cache.computeIfAbsent(driverId,
                id -> driverRepository.findByIdAndFactoryId(id, factoryId).orElse(null));
        return driver != null && driver.getName() != null ? driver.getName() : driverId;
    }

    /** loadRate 落库为 0-1 小数 (scale 4, 见 LogisticsRoutingAlgorithm) — 导出转百分比, 1 位小数。 */
    private String loadRateText(BigDecimal loadRate) {
        BigDecimal fraction = loadRate == null ? BigDecimal.ZERO : loadRate;
        BigDecimal pct = fraction.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }

    private String distanceText(LogisticsTrip trip) {
        if (trip.getStatus() == TripStatus.NEEDS_ROUTE_DATA) {
            return NEEDS_ROUTE_DATA_TEXT;
        }
        return plainNumber(trip.getTotalDistanceKm());
    }

    private String plainNumber(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private String nvlStr(String value) {
        return value == null ? "" : value;
    }

    // ============================================================
    // 编码 — CSV (UTF-8 BOM, Excel 打开中文不乱码) / XLSX (EasyExcel)
    // ============================================================

    private byte[] writeCsv(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0xFEFF); // UTF-8 BOM — Excel 打开中文不乱码 (显式 code point, 避免源文件编码歧义)
        for (List<Object> row : rows) {
            sb.append(row.stream().map(this::csvCell).collect(Collectors.joining(",")));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private byte[] writeXlsx(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return new byte[0];
        }
        List<Object> header = rows.get(0);
        List<List<Object>> dataRows = rows.subList(1, rows.size());

        List<List<String>> head = new ArrayList<>();
        for (Object h : header) {
            head.add(List.of(String.valueOf(h)));
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ExcelWriter writer = EasyExcel.write(out).head(head).build();
            WriteSheet sheet = EasyExcel.writerSheet("排线计划").build();
            writer.write(dataRows, sheet);
            writer.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("导出 XLSX 失败", e);
        }
    }
}
