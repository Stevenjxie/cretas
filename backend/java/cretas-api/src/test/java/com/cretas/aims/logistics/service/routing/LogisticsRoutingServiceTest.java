package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.DriverRole;
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
import com.cretas.aims.logistics.entity.enums.DistanceEdgeSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — {@link LogisticsRoutingService} 持久化层 @DataJpaTest (H2 PG-compat,
 * mirrors {@code LogisticsPlanGraphRepositoryTest} / {@code FgReservationLedgerServiceTest}
 * conventions)。
 *
 * <p>算法精确性本身 (确定性/硬约束穷举/targetLoad差异/TS parity) 由
 * {@code LogisticsRoutingAlgorithmTest} (纯函数, 无DB) 覆盖; 本类只验证
 * "实体加载 → 算法 → 落库" 这条链路本身的正确性 + 幂等 + 重生成行为。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@Import(LogisticsRoutingService.class)
@DisplayName("LogisticsRoutingService — 持久化 (@DataJpaTest, H2 PG-compat)")
class LogisticsRoutingServiceTest {

    private static final String F1 = "F-LOG-ROUTE-1";

    @Autowired private LogisticsRoutingService service;
    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsVehicleProfileRepository vehicleProfileRepo;
    @Autowired private LogisticsDriverRepository driverRepo;
    @Autowired private LogisticsVehicleDriverRepository vehicleDriverRepo;
    @Autowired private LogisticsDistanceEdgeRepository edgeRepo;
    @Autowired private LogisticsPlanRepository planRepo;
    @Autowired private LogisticsTripRepository tripRepo;
    @Autowired private LogisticsStopRepository stopRepo;
    @Autowired private EntityManager em;

    // Mocked — never hit the real Amap API in tests. Default (unstubbed) Mockito boolean answer
    // is false, so amapClient.isEnabled() == false unless a test explicitly stubs it: existing
    // tests below therefore keep their pre-Amap-integration behavior unchanged (fast no-op path).
    @MockBean
    private AmapClient amapClient;

    // Mocked (档1-B) — 道路路线多提供商链。unstubbed anyEnabled()==false → generate 路径
    // 完全跳过路线规划 (零地图调用), 既有断言全部不受影响。
    @MockBean
    private RouteProviderChain routeProviderChain;

    // ============================================================
    // Fixture builder — 2 门店同区域, 1 车 (容量刚好容纳2单), 1 主司机, 2 条完整距离边
    // ============================================================

    private LogisticsOrderBatch seedTwoStoreSingleTripScenario(String suffix) {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-RT-" + suffix).sourceFingerprint("fp-rt-" + suffix)
                        .status(com.cretas.aims.logistics.entity.enums.OrderBatchStatus.COMMITTED)
                        .build());

        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-A" + suffix).storeName("门店A" + suffix).areaCode("AREA-X")
                .volumeCbm(new BigDecimal("3.000")).weightKg(new BigDecimal("300.000"))
                .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-B" + suffix).storeName("门店B" + suffix).areaCode("AREA-X")
                .volumeCbm(new BigDecimal("3.000")).weightKg(new BigDecimal("300.000"))
                .build());

        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-RT" + suffix)
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-X").active(true)
                .build());

        LogisticsDriver driver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机RT" + suffix).active(true)
                .build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-RT" + suffix).driverId(driver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());

        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId("DEPOT").toPointId("S-A" + suffix)
                .distanceKm(new BigDecimal("12.50")).build());
        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId("S-A" + suffix).toPointId("S-B" + suffix)
                .distanceKm(new BigDecimal("6.25")).build());

        em.flush();
        em.clear();
        return batch;
    }

    @Test
    @DisplayName("generatePlan — 落库 Plan+Trip+Stop, 状态DRAFT (体积/距离全齐)")
    void generatePlanPersistsFullGraph() {
        LogisticsOrderBatch batch = seedTwoStoreSingleTripScenario("1");

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertNotNull(plan.getId());
        assertEquals(PlanStatus.DRAFT, plan.getStatus(), "both stores fit one trip with vehicle+driver+route all resolved");
        assertEquals(2, plan.getTotalStores());
        assertEquals(1, plan.getTotalTrips());
        assertEquals(0, plan.getTotalDistanceKm().compareTo(new BigDecimal("18.75")));

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        LogisticsTrip trip = trips.get(0);
        assertEquals(TripStatus.DRAFT, trip.getStatus());
        assertEquals("VEH-RT1", trip.getVehicleId());
        assertNotNull(trip.getDriverId());
        assertEquals(0, trip.getTotalVolumeCbm().compareTo(new BigDecimal("6.000")));

        List<LogisticsStop> stops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip.getId());
        assertEquals(2, stops.size());
        assertEquals(1, stops.get(0).getSequenceNo());
        assertEquals(0, stops.get(0).getLegDistanceKm().compareTo(new BigDecimal("12.50")));
        assertEquals(2, stops.get(1).getSequenceNo());
        assertEquals(0, stops.get(1).getLegDistanceKm().compareTo(new BigDecimal("6.25")));
    }

    @Test
    @DisplayName("generatePlan — 幂等: 同批次二次调用返回既有(非CANCELLED)计划, 不重复创建")
    void generatePlanIsIdempotentPerBatch() {
        LogisticsOrderBatch batch = seedTwoStoreSingleTripScenario("2");

        LogisticsPlan first = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));
        LogisticsPlan second = service.generatePlan(F1, batch.getId(), new BigDecimal("70")); // different pct — still short-circuits

        assertEquals(first.getId(), second.getId(), "second call must return the existing plan, not create a new one");
        assertEquals(1, planRepo.findByFactoryIdOrderByPlanDateDesc(F1).stream()
                        .filter(p -> batch.getId().equals(p.getOrderBatchId())).count(),
                "exactly one plan must exist for this batch after 2 generatePlan calls");
    }

    @Test
    @DisplayName("regeneratePlan — 清空旧Trip/Stop并重建, 旧stop被软删且原order可重新排入新stop")
    void regeneratePlanClearsAndRebuildsTrips() {
        LogisticsOrderBatch batch = seedTwoStoreSingleTripScenario("3");
        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));
        List<LogisticsStop> oldStops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(
                tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId()).get(0).getId());
        assertEquals(2, oldStops.size());

        LogisticsPlan regenerated = service.regeneratePlan(F1, plan.getId());

        assertEquals(plan.getId(), regenerated.getId(), "regenerate keeps the same plan id/number");
        List<LogisticsTrip> newTrips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, newTrips.size(), "rebuilt from the same batch data yields the same trip shape");
        List<LogisticsStop> newStops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(newTrips.get(0).getId());
        assertEquals(2, newStops.size());

        // old stop rows are soft-deleted, not still "active" under the old trip id (which is also gone)
        for (LogisticsStop old : oldStops) {
            assertTrue(stopRepo.findById(old.getId()).map(s -> s.getDeletedAt() != null).orElse(true),
                    "old stop row must be soft-deleted after regenerate");
        }
    }

    @Test
    @DisplayName("generatePlan — 缺距离边 → 车次NEEDS_ROUTE_DATA, totalDistanceKm=0 (落库层诚实降级)")
    void generatePlanHonestlyDegradesOnMissingEdge() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-RT-NOEDGE").sourceFingerprint("fp-rt-noedge")
                        .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-NOEDGE").storeName("无边门店").areaCode("AREA-Y")
                .volumeCbm(new BigDecimal("1.000")).weightKg(new BigDecimal("100.000"))
                .build());
        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-NOEDGE")
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-Y").active(true)
                .build());
        LogisticsDriver driver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机无边").active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-NOEDGE").driverId(driver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());
        // 故意不建 DEPOT->S-NOEDGE 边

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertEquals(PlanStatus.NEEDS_ACTION, plan.getStatus());
        assertEquals(0, plan.getTotalDistanceKm().compareTo(BigDecimal.ZERO));
        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.NEEDS_ROUTE_DATA, trips.get(0).getStatus());
        assertEquals(0, trips.get(0).getTotalDistanceKm().compareTo(BigDecimal.ZERO),
                "must be exactly zero, never a fabricated distance");
        List<LogisticsStop> stops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trips.get(0).getId());
        assertEquals(1, stops.size());
        assertEquals(0, stops.get(0).getLegDistanceKm().compareTo(BigDecimal.ZERO));
    }

    // ============================================================
    // Phase 4 — 高德地图补齐缺边 (mocked AmapClient, 从不打真实 API)
    // ============================================================

    /** 单门店 (有坐标)、单车单司机、故意不建 DEPOT->门店 距离边 的最小夹具, 供高德补齐测试复用。 */
    private LogisticsOrderBatch seedSingleStoreWithCoordsNoEdgeScenario(String suffix) {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-RT-AMAP" + suffix).sourceFingerprint("fp-rt-amap" + suffix)
                        .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-AMAP" + suffix).storeName("高德门店" + suffix).areaCode("AREA-Z" + suffix)
                .volumeCbm(new BigDecimal("1.000")).weightKg(new BigDecimal("100.000"))
                .longitude(new BigDecimal("120.70")).latitude(new BigDecimal("31.40"))
                .build());
        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-AMAP" + suffix)
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-Z" + suffix).active(true)
                .build());
        LogisticsDriver driver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机高德" + suffix).active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-AMAP" + suffix).driverId(driver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());
        // 故意不建 DEPOT->S-AMAP<suffix> 边
        return batch;
    }

    @Test
    @DisplayName("generatePlan — mock高德补齐缺边成功 → 车次转DRAFT + 边缓存为MAP_PROVIDER")
    void generatePlanFillsMissingEdgeViaMockedAmap() {
        LogisticsOrderBatch batch = seedSingleStoreWithCoordsNoEdgeScenario("1");

        when(amapClient.isEnabled()).thenReturn(true);
        when(amapClient.drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new BigDecimal("15.30")));

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertEquals(PlanStatus.DRAFT, plan.getStatus(),
                "amap 成功补齐距离后车次应转 DRAFT, 计划应 DRAFT 而非 NEEDS_ACTION");
        assertEquals(0, plan.getTotalDistanceKm().compareTo(new BigDecimal("15.30")));

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.DRAFT, trips.get(0).getStatus());
        assertEquals(0, trips.get(0).getTotalDistanceKm().compareTo(new BigDecimal("15.30")));

        verify(amapClient, times(1)).drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        var cachedEdge = edgeRepo.findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(F1, "DEPOT", "S-AMAP1");
        assertTrue(cachedEdge.isPresent(), "成功查到的边必须缓存进 logistics_distance_edges 供下次复用");
        assertEquals(DistanceEdgeSource.MAP_PROVIDER, cachedEdge.get().getSource());
    }

    @Test
    @DisplayName("regeneratePlan — 已缓存的MAP_PROVIDER边命中缓存, 不重复调用高德")
    void regeneratePlanReusesCachedAmapEdgeWithoutRecalling() {
        LogisticsOrderBatch batch = seedSingleStoreWithCoordsNoEdgeScenario("2");
        when(amapClient.isEnabled()).thenReturn(true);
        when(amapClient.drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new BigDecimal("9.90")));

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));
        assertEquals(PlanStatus.DRAFT, plan.getStatus());
        verify(amapClient, times(1)).drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        LogisticsPlan regenerated = service.regeneratePlan(F1, plan.getId());

        assertEquals(PlanStatus.DRAFT, regenerated.getStatus());
        assertEquals(0, regenerated.getTotalDistanceKm().compareTo(new BigDecimal("9.90")));
        // 边已缓存 — regenerate 不应再次调用高德 (仍只 1 次总调用)
        verify(amapClient, times(1)).drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("generatePlan — 高德未启用(key为空/blank) → 即便订单有坐标仍诚实保持NEEDS_ROUTE_DATA, 从不调用高德, 从不伪造km")
    void generatePlanStaysHonestWhenAmapDisabled() {
        LogisticsOrderBatch batch = seedSingleStoreWithCoordsNoEdgeScenario("3");
        // amapClient.isEnabled() 未 stub → Mockito 默认 boolean 返回 false (等价 blank key 场景)

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertEquals(PlanStatus.NEEDS_ACTION, plan.getStatus());
        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.NEEDS_ROUTE_DATA, trips.get(0).getStatus());
        assertEquals(0, trips.get(0).getTotalDistanceKm().compareTo(BigDecimal.ZERO),
                "高德未启用时必须保持诚实 0, 绝不伪造/直线降级公里数");
        verify(amapClient, never()).drivingDistanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble());

        var missingEdge = edgeRepo.findByFactoryIdAndFromPointIdAndToPointIdAndDeletedAtIsNull(F1, "DEPOT", "S-AMAP3");
        assertTrue(missingEdge.isEmpty(), "高德未启用时不应凭空产生任何边");
    }
}
