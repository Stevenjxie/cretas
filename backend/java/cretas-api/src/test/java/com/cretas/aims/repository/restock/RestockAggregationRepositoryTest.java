package com.cretas.aims.repository.restock;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 备货看板聚合查询 — JPA slice test (H2 PG-compat).
 *
 * <p>Covers:
 * <ol>
 *   <li>demandAggregation — CONFIRMED+FINANCE_APPROVED 累加, CANCELLED 排除</li>
 *   <li>demandUnitInconsistent — 同产品不同 unit → minUnit≠maxUnit (F2 flag)</li>
 *   <li>wipAggregation — sumAvailableByProduct(availableQuantity>0 求和)</li>
 *   <li>scheduledAggregation — 仅 PLANNED+PENDING 计入; 其他状态排除</li>
 * </ol>
 *
 * <p>FK dependency chain handled via {@code restock-fixtures.sql} which uses
 * {@code SET REFERENTIAL_INTEGRITY FALSE} to insert parent rows (factories, users,
 * customers, product_types) in one shot. This avoids expanding JPA-based FK setup
 * code across ever-deeper entity hierarchies.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Sql(
    scripts = "/test-data/restock-fixtures.sql",
    config = @SqlConfig(errorMode = SqlConfig.ErrorMode.FAIL_ON_ERROR),
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@DisplayName("备货看板聚合查询 — RestockAggregationRepositoryTest")
class RestockAggregationRepositoryTest {

    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Autowired private ProductionPlanRepository productionPlanRepository;

    private static final String FID = "F-TEST";       // matches fixtures
    private static final Long UID = 1L;               // matches fixtures
    private static final String CID = "CUST-TEST";   // matches fixtures
    private static final LocalDate D = LocalDate.of(2026, 6, 3);
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    // ============================================================
    // Helpers
    // ============================================================

    private SalesOrder order(String id, SalesOrderStatus status) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setFactoryId(FID);
        o.setOrderNumber("SO-" + id);
        o.setCustomerId(CID);
        o.setOrderDate(D);
        o.setRequiredDeliveryDate(D);
        o.setStatus(status);
        o.setCreatedBy(UID);
        return salesOrderRepository.saveAndFlush(o);
    }

    private void item(String orderId, String productTypeId, String name, String unit, String qty) {
        SalesOrderItem i = new SalesOrderItem();
        i.setSalesOrderId(orderId);
        i.setProductTypeId(productTypeId);
        i.setProductName(name);
        i.setUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        salesOrderItemRepository.saveAndFlush(i);
    }

    private void plan(String productTypeId, ProductionPlanStatus status, String qty) {
        ProductionPlan p = new ProductionPlan();
        p.setId(UUID.randomUUID().toString()); // manual id required — no @GeneratedValue
        p.setFactoryId(FID);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(qty));
        p.setStatus(status);
        p.setPlanNumber("PLAN-" + SEQ.getAndIncrement()); // NOT NULL + unique
        p.setCreatedBy(UID);
        productionPlanRepository.saveAndFlush(p);
    }

    // ============================================================
    // Tests
    // ============================================================

    @Test
    @DisplayName("需求聚合: 多有效订单同产品累加; 已取消不计; 单位一致")
    void demandAggregation() {
        order("O-DA-1", SalesOrderStatus.CONFIRMED);
        order("O-DA-2", SalesOrderStatus.FINANCE_APPROVED);
        order("O-DA-3", SalesOrderStatus.CANCELLED); // 不计入
        item("O-DA-1", "PT-ZS", "猪舌120g", "盒", "531");
        item("O-DA-2", "PT-ZS", "猪舌120g", "盒", "94");
        item("O-DA-3", "PT-ZS", "猪舌120g", "盒", "999"); // cancelled

        List<ProductDemandProjection> rows = salesOrderItemRepository
                .sumDemandByProductForDeliveryDate(FID, D,
                        List.of(SalesOrderStatus.CONFIRMED,
                                SalesOrderStatus.PENDING_FINANCE_REVIEW,
                                SalesOrderStatus.FINANCE_APPROVED,
                                SalesOrderStatus.PROCESSING,
                                SalesOrderStatus.PARTIAL_DELIVERED));

        assertEquals(1, rows.size(), "exactly 1 product group");
        ProductDemandProjection r = rows.get(0);
        assertEquals("PT-ZS", r.getProductTypeId());
        assertEquals(0, new BigDecimal("625").compareTo(r.getDemand()),
                "531 + 94 = 625; CANCELLED excluded");
        assertEquals("盒", r.getMinUnit(), "minUnit should be 盒");
        assertEquals("盒", r.getMaxUnit(), "maxUnit should be 盒 (unit consistent)");
    }

    @Test
    @DisplayName("需求聚合: 同产品订单行单位不一致 → minUnit≠maxUnit (F2 flag)")
    void demandUnitInconsistent() {
        order("O-DU-1", SalesOrderStatus.CONFIRMED);
        item("O-DU-1", "PT-X", "X产品", "盒", "10");
        item("O-DU-1", "PT-X", "X产品", "箱", "2");

        List<ProductDemandProjection> rows = salesOrderItemRepository
                .sumDemandByProductForDeliveryDate(FID, D, List.of(SalesOrderStatus.CONFIRMED));

        assertEquals(1, rows.size(), "one product group");
        assertNotEquals(rows.get(0).getMinUnit(), rows.get(0).getMaxUnit(),
                "unit inconsistency: min=盒, max=箱 (or vice versa)");
    }

    @Test
    @DisplayName("WIP 可用聚合: availableQuantity>0 的行求和=150")
    void wipAggregation() {
        SemiFinishedInventory w1 = new SemiFinishedInventory();
        w1.setFactoryId(FID);
        w1.setProductTypeId("PT-ZS");
        w1.setIntermediateBatchNo("WIP-AGG-1");
        w1.setAvailableQuantity(new BigDecimal("100"));
        semiFinishedInventoryRepository.saveAndFlush(w1);

        SemiFinishedInventory w2 = new SemiFinishedInventory();
        w2.setFactoryId(FID);
        w2.setProductTypeId("PT-ZS");
        w2.setIntermediateBatchNo("WIP-AGG-2");
        w2.setAvailableQuantity(new BigDecimal("50"));
        semiFinishedInventoryRepository.saveAndFlush(w2);

        BigDecimal sum = semiFinishedInventoryRepository.sumAvailableByProduct(FID, "PT-ZS");
        assertEquals(0, new BigDecimal("150").compareTo(sum),
                "100 + 50 = 150");
    }

    @Test
    @DisplayName("已排产聚合: 仅 PLANNED+PENDING 计入; IN_PROGRESS/COMPLETED/CANCELLED 排除")
    void scheduledAggregation() {
        plan("PT-ZS", ProductionPlanStatus.PLANNED,      "200");
        plan("PT-ZS", ProductionPlanStatus.PENDING,      "100");
        plan("PT-ZS", ProductionPlanStatus.IN_PROGRESS,  "999"); // 排除 (产出在WIP/FG)
        plan("PT-ZS", ProductionPlanStatus.COMPLETED,    "888"); // 排除
        plan("PT-ZS", ProductionPlanStatus.CANCELLED,    "777"); // 排除

        BigDecimal sum = productionPlanRepository.sumPlannedQuantityByProductAndStatuses(
                FID, "PT-ZS",
                List.of(ProductionPlanStatus.PLANNED, ProductionPlanStatus.PENDING));

        assertEquals(0, new BigDecimal("300").compareTo(sum),
                "200 + 100 = 300; IN_PROGRESS/COMPLETED/CANCELLED excluded");
    }
}
