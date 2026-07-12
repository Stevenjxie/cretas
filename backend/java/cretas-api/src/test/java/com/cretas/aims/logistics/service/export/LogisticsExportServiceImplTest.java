package com.cretas.aims.logistics.service.export;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.service.export.impl.LogisticsExportServiceImpl;
import com.cretas.aims.repository.VehicleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 — {@link LogisticsExportService} 持久化层 @DataJpaTest (H2 PG-compat,
 * mirrors {@code LogisticsRoutingServiceTest} conventions).
 *
 * <p>核心验收 (spec §7 / handoff §16.1 "导出顺序、里程、方数、重量与计划详情一致"):
 * CSV 与 XLSX 解析回来的每一格都必须逐字段等于已落库的 plan/trip/stop 值，不重新计算；
 * NEEDS_ROUTE_DATA 车次导出 0 而不是伪造公里数；未分配门店单独列出而不是静默丢弃。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@Import(LogisticsExportServiceImpl.class)
@DisplayName("LogisticsExportService — 持久化 (@DataJpaTest, H2 PG-compat)")
class LogisticsExportServiceImplTest {

    private static final String F1 = "F-LOG-EXPORT-1";
    private static final String F2 = "F-LOG-EXPORT-2";

    @Autowired private LogisticsExportService service;
    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsPlanRepository planRepo;
    @Autowired private LogisticsTripRepository tripRepo;
    @Autowired private LogisticsStopRepository stopRepo;
    @Autowired private VehicleRepository vehicleRepo;
    @Autowired private LogisticsDriverRepository driverRepo;
    @Autowired private EntityManager em;

    // ============================================================
    // Fixture: 1 计划 — trip1(2 门店, 已解析车辆/司机/路线) + trip2(1 门店, NEEDS_ROUTE_DATA,
    // 无车无司机) + 1 未分配门店(单店超容量场景的诚实占位)
    // ============================================================

    private record Scenario(LogisticsPlan plan, LogisticsTrip trip1, LogisticsTrip trip2,
                             LogisticsDeliveryOrder orderA, LogisticsDeliveryOrder orderB,
                             LogisticsDeliveryOrder orderD, LogisticsDeliveryOrder orderUnassigned,
                             Vehicle vehicle, LogisticsDriver driver) {
    }

    private Scenario seedScenario() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-EXPORT-1").sourceFingerprint("fp-export-1")
                        .build());

        LogisticsDeliveryOrder orderA = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("S-A").storeName("门店A")
                        .volumeCbm(new BigDecimal("3.000")).weightKg(new BigDecimal("300.000"))
                        .build());
        LogisticsDeliveryOrder orderB = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("S-B").storeName("门店B")
                        .volumeCbm(new BigDecimal("2.000")).weightKg(new BigDecimal("200.000"))
                        .build());
        LogisticsDeliveryOrder orderD = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("S-D").storeName("门店D")
                        .volumeCbm(new BigDecimal("1.000")).weightKg(new BigDecimal("100.000"))
                        .build());
        LogisticsDeliveryOrder orderUnassigned = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("S-C").storeName("门店C")
                        .volumeCbm(new BigDecimal("1.500")).weightKg(new BigDecimal("150.000"))
                        .build());

        Vehicle vehicle = new Vehicle();
        vehicle.setFactoryId(F1);
        vehicle.setPlateNumber("苏A12345");
        vehicle.setCapacity(new BigDecimal("10.000"));
        vehicle = vehicleRepo.saveAndFlush(vehicle);

        LogisticsDriver driver = driverRepo.saveAndFlush(
                LogisticsDriver.builder().factoryId(F1).name("司机张三").build());

        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder().factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-EXPORT-1")
                        .build());

        LogisticsTrip trip1 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(1)
                        .vehicleId(vehicle.getId()).driverId(driver.getId())
                        .status(TripStatus.DRAFT)
                        .totalVolumeCbm(new BigDecimal("5.000")).totalWeightKg(new BigDecimal("500.000"))
                        .loadRate(new BigDecimal("0.5000")).weightLoadRate(new BigDecimal("0.5000"))
                        .totalDistanceKm(new BigDecimal("18.75"))
                        .build());
        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip1.getId())
                .deliveryOrderId(orderA.getId()).sequenceNo(1).legDistanceKm(new BigDecimal("12.50")).build());
        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip1.getId())
                .deliveryOrderId(orderB.getId()).sequenceNo(2).legDistanceKm(new BigDecimal("6.25")).build());

        LogisticsTrip trip2 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(2)
                        .status(TripStatus.NEEDS_ROUTE_DATA)
                        .totalVolumeCbm(new BigDecimal("1.000")).totalWeightKg(new BigDecimal("100.000"))
                        .loadRate(BigDecimal.ZERO).weightLoadRate(BigDecimal.ZERO)
                        .totalDistanceKm(BigDecimal.ZERO.setScale(2))
                        .build());
        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip2.getId())
                .deliveryOrderId(orderD.getId()).sequenceNo(1).build());

        em.flush();
        em.clear();
        return new Scenario(plan, trip1, trip2, orderA, orderB, orderD, orderUnassigned, vehicle, driver);
    }

    @Test
    @DisplayName("CSV — 逐字段等于落库值; NEEDS_ROUTE_DATA 显示 0 不伪造; 未分配门店单独列出")
    void exportCsv_matchesPersistedValuesFieldForField() {
        Scenario s = seedScenario();

        byte[] bytes = service.exportCsv(F1, s.plan().getId());
        List<List<String>> rows = parseCsv(bytes);

        assertEquals(List.of("线路", "车辆", "司机", "门店数", "方数(m³)", "重量(kg)", "装载率", "公里数", "配送顺序"),
                rows.get(0), "CSV 表头");

        List<String> trip1Row = rows.get(1);
        assertEquals(s.plan().getPlanNumber() + "-T1", trip1Row.get(0));
        assertEquals(s.vehicle().getPlateNumber(), trip1Row.get(1), "车辆列 = 真实车牌 (非 vehicleId)");
        assertEquals(s.driver().getName(), trip1Row.get(2), "司机列 = 真实姓名 (非 driverId)");
        assertEquals("2", trip1Row.get(3), "门店数 = 停靠点数");
        assertEquals(s.trip1().getTotalVolumeCbm().toPlainString(), trip1Row.get(4), "方数逐字段等于落库值");
        assertEquals(s.trip1().getTotalWeightKg().toPlainString(), trip1Row.get(5), "重量逐字段等于落库值");
        assertEquals("50.0%", trip1Row.get(6), "装载率 0.5000 → 50.0%");
        assertEquals(s.trip1().getTotalDistanceKm().toPlainString(), trip1Row.get(7), "公里数逐字段等于落库值");
        assertEquals("门店A -> 门店B", trip1Row.get(8), "配送顺序 = 停靠点序号顺序拼接门店名");

        List<String> trip2Row = rows.get(2);
        assertEquals(s.plan().getPlanNumber() + "-T2", trip2Row.get(0));
        assertEquals("待匹配车辆", trip2Row.get(1), "trip2 未分配车辆 → 诚实占位, 非空/非伪造");
        assertEquals("待匹配司机", trip2Row.get(2), "trip2 未分配司机 → 诚实占位");
        assertEquals("1", trip2Row.get(3));
        assertEquals("0（待补路线数据）", trip2Row.get(7),
                "NEEDS_ROUTE_DATA 显示落库的 0, 附注缺路线数据, 不伪造/不拼直线距离");
        assertEquals("门店D", trip2Row.get(8));

        // 未分配门店单独 section — 不静默丢弃 (fool-proof-design Rule 5)
        assertTrue(rows.get(3).get(0).contains("未分配门店"), "未分配 section 明确标注");
        assertEquals(List.of("订单号", "门店名称", "体积(m³)", "重量(kg)"), rows.get(4));
        List<String> unassignedRow = rows.get(5);
        assertEquals("S-C", unassignedRow.get(0));
        assertEquals("门店C", unassignedRow.get(1));
        assertEquals(s.orderUnassigned().getVolumeCbm().toPlainString(), unassignedRow.get(2));
        assertEquals(s.orderUnassigned().getWeightKg().toPlainString(), unassignedRow.get(3));
        assertEquals(6, rows.size(), "无多余/无遗漏行 (header+2 trips+未分配label+未分配header+1未分配行)");
    }

    @Test
    @DisplayName("XLSX — 与 CSV 逐格一致 (同一份行数据, 仅编码不同)")
    void exportXlsx_matchesCsvFieldForField() {
        Scenario s = seedScenario();

        List<List<String>> csvRows = parseCsv(service.exportCsv(F1, s.plan().getId()));
        List<List<String>> xlsxRows = readXlsx(service.exportXlsx(F1, s.plan().getId()));

        assertEquals(csvRows.size(), xlsxRows.size(), "CSV/XLSX 行数一致");
        for (int r = 0; r < csvRows.size(); r++) {
            List<String> csvRow = csvRows.get(r);
            List<String> xlsxRow = xlsxRows.get(r);
            for (int c = 0; c < csvRow.size(); c++) {
                assertEquals(csvRow.get(c), xlsxRow.get(c),
                        "row=" + r + " col=" + c + " CSV/XLSX 必须逐格一致");
            }
        }
    }

    @Test
    @DisplayName("计划不存在 → ResourceNotFoundException (非静默空文件)")
    void exportCsv_planNotFound_throws() {
        assertThrows(ResourceNotFoundException.class, () -> service.exportCsv(F1, "no-such-plan"));
    }

    @Test
    @DisplayName("跨租户 — F2 请求 F1 的计划 → ResourceNotFoundException (租户隔离)")
    void exportCsv_wrongFactory_throws() {
        Scenario s = seedScenario();
        assertThrows(ResourceNotFoundException.class, () -> service.exportCsv(F2, s.plan().getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.exportXlsx(F2, s.plan().getId()));
    }

    @Test
    @DisplayName("无未分配门店时不生成未分配 section")
    void exportCsv_noUnassigned_omitsSection() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder().factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-EXPORT-2").sourceFingerprint("fp-export-2").build());
        LogisticsDeliveryOrder order = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("S-X").storeName("门店X")
                        .volumeCbm(new BigDecimal("1.000")).weightKg(new BigDecimal("100.000")).build());
        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder().factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-EXPORT-2").build());
        LogisticsTrip trip = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(1)
                        .status(TripStatus.NEEDS_VEHICLE)
                        .totalVolumeCbm(new BigDecimal("1.000")).totalWeightKg(new BigDecimal("100.000"))
                        .loadRate(BigDecimal.ZERO).weightLoadRate(BigDecimal.ZERO)
                        .totalDistanceKm(new BigDecimal("5.00")).build());
        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip.getId())
                .deliveryOrderId(order.getId()).sequenceNo(1).build());
        em.flush();
        em.clear();

        List<List<String>> rows = parseCsv(service.exportCsv(F1, plan.getId()));
        assertEquals(2, rows.size(), "只有 header + 1 trip 行, 无未分配 section");
    }

    // ============================================================
    // 解析 helper — CSV (regex 反解引号字段) / XLSX (EasyExcel, mirrors VoucherExportServiceTest#readXlsx)
    // ============================================================

    private static final Pattern CSV_CELL = Pattern.compile("\"([^\"]*)\"");

    private List<List<String>> parseCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == (char) 0xFEFF) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            Matcher m = CSV_CELL.matcher(line);
            while (m.find()) {
                cells.add(m.group(1).replace("\"\"", "\""));
            }
            rows.add(cells);
        }
        return rows;
    }

    private List<List<String>> readXlsx(byte[] bytes) {
        List<List<String>> all = new ArrayList<>();
        com.alibaba.excel.EasyExcel.read(new ByteArrayInputStream(bytes),
                new com.alibaba.excel.event.AnalysisEventListener<Map<Integer, String>>() {
                    @Override
                    public void invokeHeadMap(Map<Integer, String> headMap,
                                               com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(headMap));
                    }

                    @Override
                    public void invoke(Map<Integer, String> data,
                                        com.alibaba.excel.context.AnalysisContext context) {
                        all.add(orderedValues(data));
                    }

                    @Override
                    public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext context) {
                    }

                    private List<String> orderedValues(Map<Integer, String> map) {
                        return new TreeMap<>(map).values().stream()
                                .map(v -> v == null ? "" : v)
                                .collect(Collectors.toList());
                    }
                }).sheet().doRead();
        return all;
    }
}
