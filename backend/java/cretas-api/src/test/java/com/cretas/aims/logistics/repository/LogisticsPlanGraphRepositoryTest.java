package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.*;
import com.cretas.aims.logistics.entity.enums.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 — JPA slice test for the {@link LogisticsPlan} → {@link LogisticsTrip} →
 * {@link LogisticsStop} graph (H2 PG-compat, mirrors {@code SemiFinishedInventoryRepositoryTest}
 * conventions).
 *
 * <p>Verifies:
 * <ol>
 *   <li>save + read-back of the full graph (order batch → delivery order → plan → trip → stop)</li>
 *   <li>{@code findByPlanIdAndDeletedAtIsNull} / {@code findByTripIdAndDeletedAtIsNullOrderBySequenceNo}
 *       / {@code findByDeliveryOrderIdAndDeletedAtIsNull}</li>
 *   <li>{@code @Version} optimistic-lock annotation present on all three entities + version
 *       increments on update (spec §2 决策 8 — 乐观锁, 禁 last-write-wins)</li>
 *   <li>DB unique index {@code uq_ls_order_single_trip} rejects assigning the same delivery
 *       order to two different stops (spec §3 — 一订单只属一条有效车次)</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@EnableJpaRepositories(basePackages = "com.cretas.aims")
@DisplayName("Logistics plan→trip→stop graph — JPA slice (H2 PG-compat)")
class LogisticsPlanGraphRepositoryTest {

    private static final String F1 = "F-LOG-GRAPH-1";

    @Autowired private LogisticsOrderBatchRepository batchRepo;
    @Autowired private LogisticsDeliveryOrderRepository orderRepo;
    @Autowired private LogisticsPlanRepository planRepo;
    @Autowired private LogisticsTripRepository tripRepo;
    @Autowired private LogisticsStopRepository stopRepo;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("save graph + read back — batch→order→plan→trip→stop")
    void saveAndReadBackGraph() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder()
                        .factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-G1").sourceFingerprint("fp-g1")
                        .build());

        LogisticsDeliveryOrder order = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder()
                        .factoryId(F1).batchId(batch.getId())
                        .storeCode("STORE-001").storeName("示范门店01")
                        .volumeCbm(new BigDecimal("1.500")).weightKg(new BigDecimal("300.000"))
                        .build());

        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder()
                        .factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-G1")
                        .build());

        LogisticsTrip trip = tripRepo.saveAndFlush(
                LogisticsTrip.builder()
                        .factoryId(F1).planId(plan.getId()).tripNo(1)
                        .build());

        LogisticsStop stop = stopRepo.saveAndFlush(
                LogisticsStop.builder()
                        .factoryId(F1).tripId(trip.getId()).deliveryOrderId(order.getId())
                        .sequenceNo(1)
                        .build());

        assertNotNull(plan.getId());
        assertEquals(PlanStatus.DRAFT, planRepo.findById(plan.getId()).orElseThrow().getStatus());
        assertEquals(TripStatus.DRAFT, tripRepo.findById(trip.getId()).orElseThrow().getStatus());
        assertEquals(order.getId(), stopRepo.findById(stop.getId()).orElseThrow().getDeliveryOrderId());
    }

    @Test
    @DisplayName("findByPlanIdAndDeletedAtIsNull / findByTripIdAndDeletedAtIsNullOrderBySequenceNo / findByDeliveryOrderIdAndDeletedAtIsNull")
    void finderQueries() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder().factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-G2").sourceFingerprint("fp-g2").build());
        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder().factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-G2").build());
        LogisticsTrip trip1 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(1).build());
        LogisticsTrip trip2 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(2).build());

        LogisticsDeliveryOrder order1 = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("STORE-101").storeName("门店101").build());
        LogisticsDeliveryOrder order2 = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("STORE-102").storeName("门店102").build());

        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip1.getId())
                .deliveryOrderId(order1.getId()).sequenceNo(2).build());
        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip1.getId())
                .deliveryOrderId(order2.getId()).sequenceNo(1).build());

        List<LogisticsTrip> trips = tripRepo.findByPlanIdAndDeletedAtIsNull(plan.getId());
        assertEquals(2, trips.size());

        List<LogisticsStop> stops = stopRepo.findByTripIdAndDeletedAtIsNullOrderBySequenceNo(trip1.getId());
        assertEquals(2, stops.size());
        assertEquals(order2.getId(), stops.get(0).getDeliveryOrderId(), "sequence_no=1 (order2) must sort first");
        assertEquals(1, stops.get(0).getSequenceNo());
        assertEquals(2, stops.get(1).getSequenceNo());

        Optional<LogisticsStop> byOrder = stopRepo.findByDeliveryOrderIdAndDeletedAtIsNull(order1.getId());
        assertTrue(byOrder.isPresent());
        assertEquals(trip1.getId(), byOrder.get().getTripId());

        assertTrue(tripRepo.findByPlanIdAndDeletedAtIsNull("no-such-plan").isEmpty());
    }

    @Test
    @DisplayName("@Version — optimistic lock present on plan/trip/stop + increments on update")
    void versionIncrementsOnUpdate() throws NoSuchFieldException {
        assertNotNull(LogisticsPlan.class.getDeclaredField("version").getAnnotation(jakarta.persistence.Version.class));
        assertNotNull(LogisticsTrip.class.getDeclaredField("version").getAnnotation(jakarta.persistence.Version.class));
        assertNotNull(LogisticsStop.class.getDeclaredField("version").getAnnotation(jakarta.persistence.Version.class));

        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder().factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-G3").sourceFingerprint("fp-g3").build());
        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder().factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-G3").build());
        assertEquals(0L, plan.getVersion());

        plan.setStatus(PlanStatus.NEEDS_ACTION);
        LogisticsPlan updated = planRepo.saveAndFlush(plan);
        em.flush();
        assertEquals(1L, updated.getVersion(), "version increments on update");

        LogisticsTrip trip = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(1).build());
        assertEquals(0L, trip.getVersion());
        trip.setStatus(TripStatus.NEEDS_VEHICLE);
        LogisticsTrip updatedTrip = tripRepo.saveAndFlush(trip);
        em.flush();
        assertEquals(1L, updatedTrip.getVersion());

        LogisticsDeliveryOrder order = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("STORE-201").storeName("门店201").build());
        LogisticsStop stop = stopRepo.saveAndFlush(
                LogisticsStop.builder().factoryId(F1).tripId(trip.getId())
                        .deliveryOrderId(order.getId()).sequenceNo(1).build());
        assertEquals(0L, stop.getVersion());
        stop.setLegDistanceKm(new BigDecimal("12.50"));
        LogisticsStop updatedStop = stopRepo.saveAndFlush(stop);
        em.flush();
        assertEquals(1L, updatedStop.getVersion());
    }

    @Test
    @DisplayName("DB unique index uq_ls_order_single_trip rejects assigning one order to two stops")
    void deliveryOrderCanOnlyBelongToOneActiveTrip() {
        LogisticsOrderBatch batch = batchRepo.saveAndFlush(
                LogisticsOrderBatch.builder().factoryId(F1).businessDate(LocalDate.of(2026, 7, 11))
                        .batchNumber("BATCH-G4").sourceFingerprint("fp-g4").build());
        LogisticsPlan plan = planRepo.saveAndFlush(
                LogisticsPlan.builder().factoryId(F1).orderBatchId(batch.getId())
                        .planDate(LocalDate.of(2026, 7, 11)).planNumber("PLAN-G4").build());
        LogisticsTrip trip1 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(1).build());
        LogisticsTrip trip2 = tripRepo.saveAndFlush(
                LogisticsTrip.builder().factoryId(F1).planId(plan.getId()).tripNo(2).build());
        LogisticsDeliveryOrder order = orderRepo.saveAndFlush(
                LogisticsDeliveryOrder.builder().factoryId(F1).batchId(batch.getId())
                        .storeCode("STORE-301").storeName("门店301").build());

        stopRepo.saveAndFlush(LogisticsStop.builder().factoryId(F1).tripId(trip1.getId())
                .deliveryOrderId(order.getId()).sequenceNo(1).build());

        LogisticsStop conflicting = LogisticsStop.builder().factoryId(F1).tripId(trip2.getId())
                .deliveryOrderId(order.getId()).sequenceNo(1).build();
        assertThrows(DataIntegrityViolationException.class, () -> stopRepo.saveAndFlush(conflicting),
                "same delivery_order_id assigned to a second active stop must violate uq_ls_order_single_trip");
    }
}
