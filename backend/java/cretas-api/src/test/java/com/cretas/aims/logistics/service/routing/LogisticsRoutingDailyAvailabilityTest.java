package com.cretas.aims.logistics.service.routing;

import com.cretas.aims.logistics.entity.LogisticsDailyAvailability;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.repository.LogisticsDailyAvailabilityRepository;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDistanceEdgeRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 按日可用性覆盖 (V20261028_57) — 排线算法喂料层集成测试。
 *
 * <p>覆盖 task brief 要求的 4 个场景: (a) 司机当天请假 → 无备用司机时诚实 NEEDS_DRIVER,
 * 有备用司机时自动切换; (b) 车辆当天维修 → 排除该车, 订单诚实 NEEDS_VEHICLE; (c) 班次覆盖
 * 改变可行性 (原班次不覆盖送达窗口 → NEEDS_DRIVER; 覆盖班次覆盖窗口 → 分配成功); (d) 无覆盖
 * 记录 → 生成结果与引入本表前完全一致 (byte-identical baseline)。
 *
 * <p>Mirrors {@link LogisticsRoutingServiceTest} 的 @DataJpaTest H2 PG-compat 夹具风格。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@Import(LogisticsRoutingService.class)
@DisplayName("LogisticsRoutingService — 按日可用性覆盖 (V20261028_57)")
class LogisticsRoutingDailyAvailabilityTest {

    private static final String F1 = "F-LOG-AVAIL-1";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 7, 12);

    @Autowired private LogisticsRoutingService service;
    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsVehicleProfileRepository vehicleProfileRepo;
    @Autowired private LogisticsDriverRepository driverRepo;
    @Autowired private LogisticsVehicleDriverRepository vehicleDriverRepo;
    @Autowired private LogisticsDistanceEdgeRepository edgeRepo;
    @Autowired private LogisticsDailyAvailabilityRepository availabilityRepo;
    @Autowired private LogisticsPlanRepository planRepo;
    @Autowired private LogisticsTripRepository tripRepo;
    @Autowired private LogisticsStopRepository stopRepo;
    @Autowired private EntityManager em;

    // Never hit the real Amap API / road-route providers — same unstubbed-false convention as
    // LogisticsRoutingServiceTest (keeps this suite fast + fully deterministic on edge-distance data).
    @MockBean private AmapClient amapClient;
    @MockBean private RouteProviderChain routeProviderChain;

    // ============================================================
    // (a) 司机请假
    // ============================================================

    @Test
    @DisplayName("司机当天请假(available=false) + 无备用司机 → 车次诚实 NEEDS_DRIVER (不误配)")
    void driverUnavailableWithNoBackupFallsToNeedsDriver() {
        LogisticsOrderBatch batch = seedSingleStoreScenario("A1", null, null);
        LogisticsDriver driver = driverRepo.findByFactoryIdOrderByNameAsc(F1).get(0);

        availabilityRepo.saveAndFlush(LogisticsDailyAvailability.builder()
                .factoryId(F1).resourceType(AvailabilityResourceType.DRIVER)
                .resourceId(driver.getId()).availDate(PLAN_DATE).available(false)
                .build());
        em.flush();
        em.clear();

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.NEEDS_DRIVER, trips.get(0).getStatus(),
                "driver on leave with no backup binding must honestly fall to NEEDS_DRIVER, never silently assign an unavailable driver");
        assertNull(trips.get(0).getDriverId());
        assertEquals("VEH-A1", trips.get(0).getVehicleId(), "vehicle itself is still available, only the driver is excluded");
        assertEquals(PlanStatus.NEEDS_ACTION, plan.getStatus());
    }

    @Test
    @DisplayName("司机当天请假(available=false) + 有备用司机 → 自动切换到 BACKUP 司机")
    void driverUnavailableFallsBackToBackupDriver() {
        LogisticsOrderBatch batch = seedSingleStoreScenario("A2", null, null);
        LogisticsDriver primary = driverRepo.findByFactoryIdOrderByNameAsc(F1).get(0);

        LogisticsDriver backup = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("备用司机A2").active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-A2").driverId(backup.getId())
                .role(DriverRole.BACKUP).priority(1).active(true)
                .build());

        availabilityRepo.saveAndFlush(LogisticsDailyAvailability.builder()
                .factoryId(F1).resourceType(AvailabilityResourceType.DRIVER)
                .resourceId(primary.getId()).availDate(PLAN_DATE).available(false)
                .build());
        em.flush();
        em.clear();

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.DRAFT, trips.get(0).getStatus());
        assertEquals(backup.getId(), trips.get(0).getDriverId(),
                "primary driver on leave must fall back to the bound BACKUP driver, not stay unassigned");
    }

    // ============================================================
    // (b) 车辆维修
    // ============================================================

    @Test
    @DisplayName("车辆当天维修(available=false) + 同区域有替代车 → 排除该车, 改派替代车")
    void vehicleUnavailableFallsBackToAlternateVehicleInArea() {
        LogisticsOrderBatch batch = seedSingleStoreScenario("B1", null, null);

        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-B1-ALT")
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-B1").active(true)
                .build());
        LogisticsDriver altDriver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("替代司机B1").active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-B1-ALT").driverId(altDriver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());

        availabilityRepo.saveAndFlush(LogisticsDailyAvailability.builder()
                .factoryId(F1).resourceType(AvailabilityResourceType.VEHICLE)
                .resourceId("VEH-B1").availDate(PLAN_DATE).available(false)
                .build());
        em.flush();
        em.clear();

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.DRAFT, trips.get(0).getStatus());
        assertEquals("VEH-B1-ALT", trips.get(0).getVehicleId(),
                "vehicle under maintenance must be excluded from the algorithm input; order re-routes to the other vehicle covering the same area");
        assertEquals(altDriver.getId(), trips.get(0).getDriverId());
        assertEquals(PlanStatus.DRAFT, plan.getStatus());
    }

    @Test
    @DisplayName("车辆当天维修(available=false) 且无替代车 → 排除该车后订单诚实无法分配, 不产生任何车次")
    void vehicleUnavailableWithNoAlternateLeavesOrderUnassigned() {
        LogisticsOrderBatch batch = seedSingleStoreScenario("B2", null, null);

        availabilityRepo.saveAndFlush(LogisticsDailyAvailability.builder()
                .factoryId(F1).resourceType(AvailabilityResourceType.VEHICLE)
                .resourceId("VEH-B2").availDate(PLAN_DATE).available(false)
                .build());
        em.flush();
        em.clear();

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertEquals(PlanStatus.NEEDS_ACTION, plan.getStatus());
        assertEquals(0, plan.getTotalTrips(), "the only vehicle able to serve this area was excluded → no trip can be built at all");
        assertEquals(0, plan.getTotalStores());
        assertEquals(0, tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId()).size());
    }

    // ============================================================
    // (c) 班次覆盖改变可行性
    // ============================================================

    @Test
    @DisplayName("司机班次覆盖: 原绑定班次不覆盖送达窗口(NEEDS_DRIVER) → 覆盖班次覆盖窗口后分配成功")
    void driverShiftOverrideChangesFeasibility() {
        // 送达窗口 08:00-09:00, 但绑定班次是 10:00-18:00 (不重叠) → 无覆盖时诚实 NEEDS_DRIVER
        LogisticsOrderBatch batch = seedSingleStoreScenario("C1", "08:00", "09:00");
        LogisticsVehicleDriver binding = vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull("VEH-C1").get(0);
        binding.setShiftStart("10:00");
        binding.setShiftEnd("18:00");
        vehicleDriverRepo.saveAndFlush(binding);
        em.flush();
        em.clear();

        LogisticsPlan baseline = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));
        List<LogisticsTrip> baselineTrips = tripRepo.findByPlanIdAndDeletedAtIsNull(baseline.getId());
        assertEquals(TripStatus.NEEDS_DRIVER, baselineTrips.get(0).getStatus(),
                "sanity check: shift 10:00-18:00 does not overlap delivery window 08:00-09:00");

        // 加班次覆盖 07:00-10:00 (与送达窗口重叠) → 重新生成后应可分配
        LogisticsDriver driver = driverRepo.findByFactoryIdOrderByNameAsc(F1).get(0);
        availabilityRepo.saveAndFlush(LogisticsDailyAvailability.builder()
                .factoryId(F1).resourceType(AvailabilityResourceType.DRIVER)
                .resourceId(driver.getId()).availDate(PLAN_DATE).available(true)
                .shiftStart("07:00").shiftEnd("10:00")
                .build());
        em.flush();
        em.clear();

        LogisticsPlan regenerated = service.regeneratePlan(F1, baseline.getId());

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(regenerated.getId());
        assertEquals(1, trips.size());
        assertEquals(TripStatus.DRAFT, trips.get(0).getStatus(),
                "shift override 07:00-10:00 overlaps the 08:00-09:00 delivery window → driver becomes assignable");
        assertNotNull(trips.get(0).getDriverId());
    }

    // ============================================================
    // (d) 无覆盖记录 → 行为不变 (回归基线)
    // ============================================================

    @Test
    @DisplayName("无任何按日可用性覆盖记录 → 生成结果与引入本表前完全一致 (2 门店同车同司机)")
    void noAvailabilityRecordsLeavesGenerationUnchanged() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(PLAN_DATE)
                        .batchNumber("BATCH-AVAIL-D1").sourceFingerprint("fp-avail-d1")
                        .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-D1A").storeName("门店D1A").areaCode("AREA-D1")
                .volumeCbm(new BigDecimal("3.000")).weightKg(new BigDecimal("300.000"))
                .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-D1B").storeName("门店D1B").areaCode("AREA-D1")
                .volumeCbm(new BigDecimal("3.000")).weightKg(new BigDecimal("300.000"))
                .build());
        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-D1")
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-D1").active(true)
                .build());
        LogisticsDriver driver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机D1").active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-D1").driverId(driver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());
        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId("DEPOT").toPointId("S-D1A")
                .distanceKm(new BigDecimal("12.50")).build());
        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId("S-D1A").toPointId("S-D1B")
                .distanceKm(new BigDecimal("6.25")).build());
        em.flush();
        em.clear();

        assertEquals(0, availabilityRepo.findByFactoryIdAndAvailDateAndDeletedAtIsNull(F1, PLAN_DATE).size(),
                "sanity check: zero override records exist for this date");

        LogisticsPlan plan = service.generatePlan(F1, batch.getId(), new BigDecimal("88"));

        assertEquals(PlanStatus.DRAFT, plan.getStatus());
        assertEquals(2, plan.getTotalStores());
        assertEquals(1, plan.getTotalTrips());
        assertEquals(0, plan.getTotalDistanceKm().compareTo(new BigDecimal("18.75")));
        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(TripStatus.DRAFT, trips.get(0).getStatus());
        assertEquals("VEH-D1", trips.get(0).getVehicleId());
        assertEquals(driver.getId(), trips.get(0).getDriverId());
    }

    // ============================================================
    // Fixture — 1 门店, 1 车 (容量充足), 1 主司机, 1 条完整距离边; 可选送达窗口
    // ============================================================

    private LogisticsOrderBatch seedSingleStoreScenario(String suffix, String windowStart, String windowEnd) {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(PLAN_DATE)
                        .batchNumber("BATCH-AVAIL-" + suffix).sourceFingerprint("fp-avail-" + suffix)
                        .build());
        orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batch.getId())
                .storeCode("S-" + suffix).storeName("门店" + suffix).areaCode("AREA-" + suffix)
                .volumeCbm(new BigDecimal("1.000")).weightKg(new BigDecimal("100.000"))
                .deliveryWindowStart(windowStart).deliveryWindowEnd(windowEnd)
                .build());
        vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId("VEH-" + suffix)
                .capacityCbm(new BigDecimal("10.000")).maxWeightKg(new BigDecimal("5000.000"))
                .serviceAreas("AREA-" + suffix).active(true)
                .build());
        LogisticsDriver driver = driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机" + suffix).active(true).build());
        vehicleDriverRepo.saveAndFlush(LogisticsVehicleDriver.builder()
                .factoryId(F1).vehicleId("VEH-" + suffix).driverId(driver.getId())
                .role(DriverRole.PRIMARY).priority(0).active(true)
                .build());
        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId("DEPOT").toPointId("S-" + suffix)
                .distanceKm(new BigDecimal("10.00")).build());
        em.flush();
        em.clear();
        return batch;
    }
}
