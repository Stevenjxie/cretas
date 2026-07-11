package com.cretas.aims.logistics.service;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.plan.MoveStopRequest;
import com.cretas.aims.logistics.dto.plan.PlanSnapshotDto;
import com.cretas.aims.logistics.dto.plan.StopDto;
import com.cretas.aims.logistics.dto.plan.TripDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsDistanceEdge;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsPlan;
import com.cretas.aims.logistics.entity.LogisticsStop;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.PlanStatus;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import com.cretas.aims.logistics.mapper.LogisticsPlanMapper;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsDistanceEdgeRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsPlanRepository;
import com.cretas.aims.logistics.repository.LogisticsStopRepository;
import com.cretas.aims.logistics.repository.LogisticsTripRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import com.cretas.aims.logistics.service.impl.LogisticsPlanServiceImpl;
import com.cretas.aims.logistics.service.routing.LogisticsRoutingService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 — {@link LogisticsPlanServiceImpl} 状态机 + 并发 @DataJpaTest (H2 PG-compat,
 * mirrors {@code LogisticsRoutingServiceTest} conventions).
 *
 * <p>覆盖 handoff §10/§16.1 的调整+确认验收项：reorder 重算距离(确定性)、move 容量/重量
 * 再校验(拒绝时源不变)、一订单一车次、confirm-trip 三态阻塞、confirm-plan 未确认车次/未分配
 * 门店阻塞、乐观锁 409(不写)、CONFIRMED 后不可修改。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@Import({LogisticsRoutingService.class, LogisticsPlanMapper.class, LogisticsPlanServiceImpl.class})
@DisplayName("LogisticsPlanServiceImpl — 状态机 + 并发 (@DataJpaTest, H2 PG-compat)")
class LogisticsPlanServiceImplTest {

    private static final String F1 = "F-LOG-PLAN-1";

    @Autowired private LogisticsPlanServiceImpl service;
    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsVehicleProfileRepository vehicleProfileRepo;
    @Autowired private LogisticsDriverRepository driverRepo;
    @Autowired private LogisticsVehicleDriverRepository vehicleDriverRepo;
    @Autowired private LogisticsDistanceEdgeRepository edgeRepo;
    @Autowired private LogisticsPlanRepository planRepo;
    @Autowired private LogisticsTripRepository tripRepo;
    @Autowired private LogisticsStopRepository stopRepo;
    @Autowired private VehicleRepository vehicleRepo;
    @Autowired private EntityManager em;

    // ============================================================
    // Fixture helpers
    // ============================================================

    private LogisticsOrderBatch seedBatch(String suffix) {
        return batchRepo.saveAndFlush(LogisticsOrderBatch.builder()
                .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                .batchNumber("BATCH-PLAN-" + suffix).sourceFingerprint("fp-plan-" + suffix)
                .status(OrderBatchStatus.COMMITTED)
                .build());
    }

    private LogisticsDeliveryOrder seedOrder(String batchId, String storeCode, String areaCode,
            String volumeCbm, String weightKg) {
        return orderRepo.saveAndFlush(LogisticsDeliveryOrder.builder()
                .factoryId(F1).batchId(batchId)
                .storeCode(storeCode).storeName("门店" + storeCode).areaCode(areaCode)
                .volumeCbm(new BigDecimal(volumeCbm)).weightKg(new BigDecimal(weightKg))
                .build());
    }

    private LogisticsPlan seedPlan(String batchId, String suffix, PlanStatus status) {
        return planRepo.saveAndFlush(LogisticsPlan.builder()
                .factoryId(F1).orderBatchId(batchId).planDate(LocalDate.of(2026, 7, 11))
                .planNumber("PLAN-" + suffix).targetLoadPct(new BigDecimal("88"))
                .status(status).totalStores(0).totalTrips(0).totalDistanceKm(BigDecimal.ZERO)
                .build());
    }

    private LogisticsTrip seedTrip(String planId, int tripNo, TripStatus status, String vehicleId, String driverId) {
        return tripRepo.saveAndFlush(LogisticsTrip.builder()
                .factoryId(F1).planId(planId).tripNo(tripNo).status(status)
                .vehicleId(vehicleId).driverId(driverId)
                .build());
    }

    private LogisticsStop seedStop(String tripId, String orderId, int sequenceNo) {
        return stopRepo.saveAndFlush(LogisticsStop.builder()
                .factoryId(F1).tripId(tripId).deliveryOrderId(orderId)
                .sequenceNo(sequenceNo).legDistanceKm(BigDecimal.ZERO)
                .build());
    }

    private void seedEdge(String from, String to, String km) {
        edgeRepo.saveAndFlush(LogisticsDistanceEdge.builder()
                .factoryId(F1).fromPointId(from).toPointId(to)
                .distanceKm(new BigDecimal(km)).build());
    }

    private Vehicle seedVehicle(String id, String plate) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setFactoryId(F1);
        v.setPlateNumber(plate);
        return vehicleRepo.saveAndFlush(v);
    }

    private LogisticsVehicleProfile seedVehicleProfile(String vehicleId, String capacityCbm, String maxWeightKg, String areas) {
        return vehicleProfileRepo.saveAndFlush(LogisticsVehicleProfile.builder()
                .factoryId(F1).vehicleId(vehicleId)
                .capacityCbm(new BigDecimal(capacityCbm)).maxWeightKg(new BigDecimal(maxWeightKg))
                .source(OwnershipType.OWNED).serviceAreas(areas).active(true)
                .build());
    }

    private LogisticsDriver seedDriver(String suffix, String areas) {
        return driverRepo.saveAndFlush(LogisticsDriver.builder()
                .factoryId(F1).name("司机" + suffix).serviceAreas(areas).active(true)
                .build());
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    // ============================================================
    // 1) reorder — 重算距离 + 确定性
    // ============================================================

    @Test
    @DisplayName("reorderStops — 重算分段公里数, 相同目标顺序两次调用得到相同距离(确定性)")
    void reorderRecomputesDistanceDeterministically() {
        LogisticsOrderBatch batch = seedBatch("RO1");
        LogisticsDeliveryOrder a = seedOrder(batch.getId(), "S-RO-A", "AREA-RO", "1.000", "100.000");
        LogisticsDeliveryOrder b = seedOrder(batch.getId(), "S-RO-B", "AREA-RO", "1.000", "100.000");
        seedEdge("DEPOT", "S-RO-A", "12.50");
        seedEdge("S-RO-A", "S-RO-B", "6.25");
        seedEdge("DEPOT", "S-RO-B", "9.00");
        seedEdge("S-RO-B", "S-RO-A", "4.00");

        LogisticsPlan plan = seedPlan(batch.getId(), "RO1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(trip.getId(), a.getId(), 1);
        seedStop(trip.getId(), b.getId(), 2);
        flushClear();

        PlanSnapshotDto snap1 = service.reorderStops(F1, plan.getId(), trip.getId(),
                List.of(b.getId(), a.getId()), null);
        TripDto tripDto1 = snap1.getTrips().get(0);
        assertEquals(0, tripDto1.getTotalDistanceKm().compareTo(new BigDecimal("13.00")),
                "DEPOT->B(9.00) + B->A(4.00) = 13.00");
        assertEquals(b.getId(), tripDto1.getStops().get(0).getDeliveryOrderId());
        assertEquals(a.getId(), tripDto1.getStops().get(1).getDeliveryOrderId());
        assertEquals(1, tripDto1.getStops().get(0).getSequenceNo());
        assertEquals(2, tripDto1.getStops().get(1).getSequenceNo());
        assertEquals(0, tripDto1.getStops().get(0).getLegDistanceKm().compareTo(new BigDecimal("9.00")));
        assertEquals(0, tripDto1.getStops().get(1).getLegDistanceKm().compareTo(new BigDecimal("4.00")));

        // determinism: reorder back to [a, b] then back to [b, a] again — same numeric result
        service.reorderStops(F1, plan.getId(), trip.getId(), List.of(a.getId(), b.getId()), null);
        PlanSnapshotDto snap2 = service.reorderStops(F1, plan.getId(), trip.getId(),
                List.of(b.getId(), a.getId()), null);
        assertEquals(0, snap2.getTrips().get(0).getTotalDistanceKm().compareTo(new BigDecimal("13.00")),
                "same target order must recompute to the exact same distance every time");
    }

    @Test
    @DisplayName("reorderStops — storeIds 不是完整排列 → 400, 不写库")
    void reorderRejectsNonPermutation() {
        LogisticsOrderBatch batch = seedBatch("RO2");
        LogisticsDeliveryOrder a = seedOrder(batch.getId(), "S-RO2-A", "AREA-RO2", "1.000", "100.000");
        LogisticsDeliveryOrder b = seedOrder(batch.getId(), "S-RO2-B", "AREA-RO2", "1.000", "100.000");
        seedEdge("DEPOT", "S-RO2-A", "1.00");
        seedEdge("S-RO2-A", "S-RO2-B", "1.00");
        LogisticsPlan plan = seedPlan(batch.getId(), "RO2", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(trip.getId(), a.getId(), 1);
        seedStop(trip.getId(), b.getId(), 2);
        flushClear();

        assertThrows(BusinessException.class,
                () -> service.reorderStops(F1, plan.getId(), trip.getId(), List.of(a.getId()), null));

        List<LogisticsStop> stops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip.getId());
        assertEquals(1, stops.get(0).getSequenceNo());
        assertEquals(2, stops.get(1).getSequenceNo());
    }

    // ============================================================
    // 2) move — 容量/重量再校验, 拒绝时源不变
    // ============================================================

    @Test
    @DisplayName("moveStop — 超出目标车次容量 → 409 CAPACITY_EXCEEDED, 源车次不变")
    void moveRejectsOverCapacitySourceUnchanged() {
        LogisticsOrderBatch batch = seedBatch("MV1");
        LogisticsDeliveryOrder sourceOrder1 = seedOrder(batch.getId(), "S-MV1-S1", "AREA-MV1", "3.000", "100.000");
        LogisticsDeliveryOrder sourceOrder2 = seedOrder(batch.getId(), "S-MV1-S2", "AREA-MV1", "3.000", "100.000");
        LogisticsDeliveryOrder targetOrder1 = seedOrder(batch.getId(), "S-MV1-T1", "AREA-MV1", "5.000", "100.000");
        Vehicle targetVehicle = seedVehicle("VEH-MV1-T", "沪MV1T");
        seedVehicleProfile(targetVehicle.getId(), "6.000", "5000.000", "AREA-MV1");

        LogisticsPlan plan = seedPlan(batch.getId(), "MV1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip sourceTrip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(sourceTrip.getId(), sourceOrder1.getId(), 1);
        seedStop(sourceTrip.getId(), sourceOrder2.getId(), 2);
        LogisticsTrip targetTrip = seedTrip(plan.getId(), 2, TripStatus.NEEDS_DRIVER, targetVehicle.getId(), null);
        seedStop(targetTrip.getId(), targetOrder1.getId(), 1);
        flushClear();

        MoveStopRequest req = new MoveStopRequest();
        req.setDeliveryOrderId(sourceOrder1.getId());
        req.setTargetTripId(targetTrip.getId());
        req.setTargetIndex(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.moveStop(F1, plan.getId(), sourceTrip.getId(), req));
        assertEquals("CAPACITY_EXCEEDED", ex.getErrorCode());

        List<LogisticsStop> sourceStops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(sourceTrip.getId());
        assertEquals(2, sourceStops.size(), "rejected move must leave the source trip untouched");
        List<LogisticsStop> targetStops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(targetTrip.getId());
        assertEquals(1, targetStops.size(), "rejected move must leave the target trip untouched");
    }

    // ============================================================
    // 3) move — 一订单一车次(成功路径), 源清空后软删除
    // ============================================================

    @Test
    @DisplayName("moveStop — 成功移动到新建车次: 一订单只出现在一条有效车次; 源车次清空后软删除")
    void moveEnforcesOneOrderOneTripAndSoftDeletesEmptySource() {
        LogisticsOrderBatch batch = seedBatch("MV2");
        LogisticsDeliveryOrder onlyOrder = seedOrder(batch.getId(), "S-MV2-A", "AREA-MV2", "1.000", "100.000");
        seedEdge("DEPOT", "S-MV2-A", "5.00");

        LogisticsPlan plan = seedPlan(batch.getId(), "MV2", PlanStatus.NEEDS_ACTION);
        LogisticsTrip sourceTrip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(sourceTrip.getId(), onlyOrder.getId(), 1);
        flushClear();

        MoveStopRequest req = new MoveStopRequest();
        req.setDeliveryOrderId(onlyOrder.getId());
        req.setTargetTripId(null); // 新建待匹配车次
        req.setTargetIndex(0);

        PlanSnapshotDto snap = service.moveStop(F1, plan.getId(), sourceTrip.getId(), req);

        // 一订单一车次: DB 只有一条活跃 stop 行
        Optional<LogisticsStop> liveStop = stopRepo.findByDeliveryOrderIdAndDeletedAtIsNull(onlyOrder.getId());
        assertTrue(liveStop.isPresent());
        assertFalse(sourceTrip.getId().equals(liveStop.get().getTripId()), "must have moved off the source trip");

        // 源车次已软删除 (清空)
        assertTrue(tripRepo.findById(sourceTrip.getId()).map(t -> t.getDeletedAt() != null).orElse(true));
        assertFalse(tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId()).stream()
                .anyMatch(t -> t.getId().equals(sourceTrip.getId())));

        // 新车次出现在快照里, 恰好 1 个 stop, 状态 NEEDS_VEHICLE (无车, 边已配)
        TripDto newTripDto = snap.getTrips().stream()
                .filter(t -> !t.getId().equals(sourceTrip.getId())).findFirst().orElseThrow();
        assertEquals(1, newTripDto.getStops().size());
        assertEquals("needs_vehicle", newTripDto.getStatus());
        assertEquals(onlyOrder.getId(), newTripDto.getStoreIds().get(0));
    }

    // ============================================================
    // 4) confirmTrip — NEEDS_VEHICLE/DRIVER/ROUTE_DATA 阻塞, 解决后放行
    // ============================================================

    @Test
    @DisplayName("confirmTrip — NEEDS_VEHICLE/NEEDS_DRIVER/NEEDS_ROUTE_DATA 依次阻塞, 全部解决后可确认")
    void confirmTripBlockedUntilAllResolved() {
        LogisticsOrderBatch batch = seedBatch("CT1");
        LogisticsDeliveryOrder order = seedOrder(batch.getId(), "S-CT1-A", "AREA-CT1", "1.000", "100.000");
        Vehicle vehicle = seedVehicle("VEH-CT1", "沪CT1");
        seedVehicleProfile(vehicle.getId(), "10.000", "5000.000", "AREA-CT1");
        LogisticsDriver driver = seedDriver("CT1", "AREA-CT1");

        LogisticsPlan plan = seedPlan(batch.getId(), "CT1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(trip.getId(), order.getId(), 1);
        flushClear();

        // (a) NEEDS_VEHICLE 阻塞
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L));
        assertEquals("NEEDS_VEHICLE", ex1.getErrorCode());

        // (b) 缺距离边 → 分配车辆后仍是 NEEDS_ROUTE_DATA (还没建 DEPOT->S-CT1-A 边)
        service.setTripVehicle(F1, plan.getId(), trip.getId(), vehicle.getId(), null);
        LogisticsTrip afterVehicle = tripRepo.findById(trip.getId()).orElseThrow();
        assertEquals(TripStatus.NEEDS_ROUTE_DATA, afterVehicle.getStatus());
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L));
        assertEquals("NEEDS_ROUTE_DATA", ex2.getErrorCode());

        // (c) 补边后仍缺司机 → NEEDS_DRIVER
        seedEdge("DEPOT", "S-CT1-A", "3.00");
        // 触发重算 (通过一次 reorder no-op 排列来强制 recompute, 因为 setTripVehicle 已在补边前跑过)
        service.reorderStops(F1, plan.getId(), trip.getId(), List.of(order.getId()), null);
        LogisticsTrip afterEdge = tripRepo.findById(trip.getId()).orElseThrow();
        assertEquals(TripStatus.NEEDS_DRIVER, afterEdge.getStatus());
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L));
        assertEquals("NEEDS_DRIVER", ex3.getErrorCode());

        // (d) 分配司机后 DRAFT → 可确认
        service.setTripDriver(F1, plan.getId(), trip.getId(), driver.getId(), null);
        PlanSnapshotDto confirmed = service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L);
        TripDto confirmedTrip = confirmed.getTrips().get(0);
        assertEquals("confirmed", confirmedTrip.getStatus());

        // 幂等: 再次确认同一车次不报错
        PlanSnapshotDto again = service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L);
        assertEquals("confirmed", again.getTrips().get(0).getStatus());
    }

    // ============================================================
    // 5) confirmPlan — 未确认车次 / 未分配门店 阻塞; 全部清空后放行
    // ============================================================

    @Test
    @DisplayName("confirmPlan — 车次未全确认 或 有未分配门店 → 409 PLAN_NOT_READY; 全部清空后可确认")
    void confirmPlanBlockedUntilAllTripsConfirmedAndNoneUnassigned() {
        LogisticsOrderBatch batch = seedBatch("CP1");
        LogisticsDeliveryOrder assignedOrder = seedOrder(batch.getId(), "S-CP1-A", "AREA-CP1", "1.000", "100.000");
        LogisticsDeliveryOrder unassignedOrder = seedOrder(batch.getId(), "S-CP1-B", "AREA-CP1", "1.000", "100.000");
        seedEdge("DEPOT", "S-CP1-A", "2.00");
        Vehicle vehicle = seedVehicle("VEH-CP1", "沪CP1");
        seedVehicleProfile(vehicle.getId(), "10.000", "5000.000", "AREA-CP1");
        LogisticsDriver driver = seedDriver("CP1", "AREA-CP1");

        LogisticsPlan plan = seedPlan(batch.getId(), "CP1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.DRAFT, vehicle.getId(), driver.getId());
        seedStop(trip.getId(), assignedOrder.getId(), 1);
        // unassignedOrder 故意不建 stop —— 模拟"未分配"
        flushClear();

        // (a) 车次未确认 + 有未分配门店 → 阻塞
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.confirmPlan(F1, plan.getId(), null, 1L));
        assertEquals("PLAN_NOT_READY", ex1.getErrorCode());
        assertTrue(ex1.getMessage().contains("未确认") || ex1.getMessage().contains("未分配"));

        // (b) 确认车次后仍有未分配门店 → 仍阻塞
        service.confirmTrip(F1, plan.getId(), trip.getId(), null, 1L);
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.confirmPlan(F1, plan.getId(), null, 1L));
        assertEquals("PLAN_NOT_READY", ex2.getErrorCode());
        assertTrue(ex2.getMessage().contains("未分配"));

        // (c) 把剩余门店也分配进同一车次并重排(recompute) 后, 车次仍是 CONFIRMED (不因新增门店回退状态: 但本
        // 实现的 reorder/move 会 assertTripNotConfirmed —— 已确认车次不可再调整; 因此改用把该门店直接标记
        // CANCELLED 来清空"未分配"集合, 而不经过已确认车次的调整接口(更贴近真实业务: 该门店本次不送)
        unassignedOrder.setStatus(com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus.CANCELLED);
        orderRepo.saveAndFlush(unassignedOrder);

        PlanSnapshotDto confirmed = service.confirmPlan(F1, plan.getId(), null, 1L);
        assertEquals(PlanStatus.CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        assertEquals("1", confirmed.getConfirmedBy());

        // 幂等: 再次确认同一计划不报错
        PlanSnapshotDto again = service.confirmPlan(F1, plan.getId(), null, 1L);
        assertEquals(PlanStatus.CONFIRMED, again.getStatus());
    }

    // ============================================================
    // 6) 乐观锁 — 旧 version 写入 → 409, 不写库
    // ============================================================

    @Test
    @DisplayName("乐观锁 — 用过期 version 调用 setTripVehicle → 409 VERSION_CONFLICT, 不写库")
    void staleVersionRejectedWithoutWrite() {
        LogisticsOrderBatch batch = seedBatch("VC1");
        LogisticsDeliveryOrder order = seedOrder(batch.getId(), "S-VC1-A", "AREA-VC1", "1.000", "100.000");
        Vehicle vehicle = seedVehicle("VEH-VC1", "沪VC1");
        seedVehicleProfile(vehicle.getId(), "10.000", "5000.000", "AREA-VC1");

        LogisticsPlan plan = seedPlan(batch.getId(), "VC1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.NEEDS_VEHICLE, null, null);
        seedStop(trip.getId(), order.getId(), 1);
        flushClear();

        Long staleVersion = trip.getVersion() - 1; // definitely stale (fresh row starts at 0)
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setTripVehicle(F1, plan.getId(), trip.getId(), vehicle.getId(), staleVersion));
        assertEquals("VERSION_CONFLICT", ex.getErrorCode());

        LogisticsTrip reloaded = tripRepo.findById(trip.getId()).orElseThrow();
        assertNull(reloaded.getVehicleId(), "rejected stale-version write must not touch the row");
    }

    // ============================================================
    // 7) CONFIRMED 计划/车次 — 拒绝进一步修改
    // ============================================================

    @Test
    @DisplayName("已确认计划 — reorder/move/setVehicle/setDriver/regenerate 全部 409, 不写库")
    void mutationsRejectedAfterPlanConfirmed() {
        LogisticsOrderBatch batch = seedBatch("PC1");
        LogisticsDeliveryOrder a = seedOrder(batch.getId(), "S-PC1-A", "AREA-PC1", "1.000", "100.000");
        LogisticsDeliveryOrder b = seedOrder(batch.getId(), "S-PC1-B", "AREA-PC1", "1.000", "100.000");
        seedEdge("DEPOT", "S-PC1-A", "1.00");
        seedEdge("S-PC1-A", "S-PC1-B", "1.00");
        Vehicle vehicle = seedVehicle("VEH-PC1", "沪PC1");
        seedVehicleProfile(vehicle.getId(), "10.000", "5000.000", "AREA-PC1");
        LogisticsDriver driver = seedDriver("PC1", "AREA-PC1");

        LogisticsPlan plan = seedPlan(batch.getId(), "PC1", PlanStatus.CONFIRMED);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.CONFIRMED, vehicle.getId(), driver.getId());
        seedStop(trip.getId(), a.getId(), 1);
        seedStop(trip.getId(), b.getId(), 2);
        flushClear();

        assertThrows(BusinessException.class,
                () -> service.reorderStops(F1, plan.getId(), trip.getId(), List.of(b.getId(), a.getId()), null));

        MoveStopRequest moveReq = new MoveStopRequest();
        moveReq.setDeliveryOrderId(a.getId());
        moveReq.setTargetTripId(null);
        assertThrows(BusinessException.class, () -> service.moveStop(F1, plan.getId(), trip.getId(), moveReq));

        assertThrows(BusinessException.class,
                () -> service.setTripVehicle(F1, plan.getId(), trip.getId(), null, null));
        assertThrows(BusinessException.class,
                () -> service.setTripDriver(F1, plan.getId(), trip.getId(), null, null));
        assertThrows(BusinessException.class,
                () -> service.regeneratePlan(F1, plan.getId()));

        List<LogisticsStop> stops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip.getId());
        assertEquals(2, stops.size(), "no mutation must have gone through");
        assertEquals(a.getId(), stops.get(0).getDeliveryOrderId());
        assertEquals(b.getId(), stops.get(1).getDeliveryOrderId());
    }

    @Test
    @DisplayName("已确认车次(计划本身未确认) — 对该车次的 reorder/setVehicle 仍 409")
    void mutationsRejectedOnConfirmedTripEvenIfPlanNotConfirmed() {
        LogisticsOrderBatch batch = seedBatch("TC1");
        LogisticsDeliveryOrder a = seedOrder(batch.getId(), "S-TC1-A", "AREA-TC1", "1.000", "100.000");
        LogisticsDeliveryOrder b = seedOrder(batch.getId(), "S-TC1-B", "AREA-TC1", "1.000", "100.000");
        seedEdge("DEPOT", "S-TC1-A", "1.00");
        seedEdge("S-TC1-A", "S-TC1-B", "1.00");
        Vehicle vehicle = seedVehicle("VEH-TC1", "沪TC1");
        seedVehicleProfile(vehicle.getId(), "10.000", "5000.000", "AREA-TC1");

        LogisticsPlan plan = seedPlan(batch.getId(), "TC1", PlanStatus.NEEDS_ACTION);
        LogisticsTrip trip = seedTrip(plan.getId(), 1, TripStatus.CONFIRMED, vehicle.getId(), "SOME-DRIVER");
        seedStop(trip.getId(), a.getId(), 1);
        seedStop(trip.getId(), b.getId(), 2);
        flushClear();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reorderStops(F1, plan.getId(), trip.getId(), List.of(b.getId(), a.getId()), null));
        assertEquals("TRIP_CONFIRMED", ex.getErrorCode());
    }
}
