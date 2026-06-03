package com.cretas.aims.repository.restock;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.inventory.ProductWarehouseDemandProjection;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 多仓备货需求聚合查询 — @DataJpaTest (H2 PG-compat).
 *
 * <p>Covers:
 * <ol>
 *   <li>multiWarehouseDemand — 同产品多仓按仓拆分, 各仓需求独立</li>
 *   <li>nullDestWarehouse → '未分仓' — 旧行归入"未分仓"桶, 不丢失</li>
 *   <li>mixedBuckets — 同产品部分行有目的仓, 部分无 → 两个桶</li>
 *   <li>cancelledExcluded — CANCELLED 订单不计入任何仓桶</li>
 * </ol>
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
@DisplayName("P3 多仓需求聚合 — MultiWarehouseDemandRepositoryTest")
class MultiWarehouseDemandRepositoryTest {

    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;

    private static final String FID  = "F-TEST";
    private static final Long   UID  = 1L;
    private static final String CID  = "CUST-TEST";
    private static final LocalDate D = LocalDate.of(2026, 9, 15);

    private static final List<SalesOrderStatus> DEMAND_STATUSES = List.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.PENDING_FINANCE_REVIEW,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED);

    // ---- helpers ----

    private SalesOrder order(String id, SalesOrderStatus status) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setFactoryId(FID);
        o.setOrderNumber("SO-P3-" + id);
        o.setCustomerId(CID);
        o.setOrderDate(D);
        o.setRequiredDeliveryDate(D);
        o.setStatus(status);
        o.setCreatedBy(UID);
        return salesOrderRepository.saveAndFlush(o);
    }

    private void item(String orderId, String productTypeId, String name, String unit, String qty,
                      String destCode, String destName) {
        SalesOrderItem i = new SalesOrderItem();
        i.setSalesOrderId(orderId);
        i.setProductTypeId(productTypeId);
        i.setProductName(name);
        i.setUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        i.setDestWarehouseCode(destCode);
        i.setDestWarehouseName(destName);
        salesOrderItemRepository.saveAndFlush(i);
    }

    // ---- tests ----

    @Test
    @DisplayName("多仓分拆: 同产品6仓 × 各自需求 → 6行独立不合并")
    void multiWarehouseDemand() {
        order("MW-1", SalesOrderStatus.CONFIRMED);
        // 6 仓 × 猪舌
        String[][] whs = {
            {"WH-DINDON-BEILUN",  "叮咚-北仑总仓",  "531"},
            {"WH-DINDON-PUDONG",  "叮咚-浦东总仓",  "200"},
            {"WH-DINDON-JINSHAN", "叮咚-金山总仓",  "150"},
            {"WH-DINDON-JIADING", "叮咚-嘉定总仓",  "180"},
            {"WH-DINDON-MINHANG", "叮咚-闵行总仓",  "120"},
            {"WH-DINDON-BAOSHAN", "叮咚-宝山总仓",  "95"},
        };
        for (String[] wh : whs) {
            item("MW-1", "PT-ZS", "猪舌120g", "盒", wh[2], wh[0], wh[1]);
        }

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        // 6 个独立桶
        assertEquals(6, rows.size(), "6 仓 → 6 行");
        Map<String, BigDecimal> byCode = rows.stream().collect(
                Collectors.toMap(ProductWarehouseDemandProjection::getDestWarehouseCode,
                                 ProductWarehouseDemandProjection::getDemand));
        assertEquals(0, new BigDecimal("531").compareTo(byCode.get("WH-DINDON-BEILUN")));
        assertEquals(0, new BigDecimal("200").compareTo(byCode.get("WH-DINDON-PUDONG")));
        assertEquals(0, new BigDecimal("95").compareTo(byCode.get("WH-DINDON-BAOSHAN")));
    }

    @Test
    @DisplayName("null destWarehouseCode → '未分仓' 桶: 旧行不丢失")
    void nullDestWarehouseCoalesced() {
        order("MW-NULL-1", SalesOrderStatus.CONFIRMED);
        // 旧行: 无目的仓
        item("MW-NULL-1", "PT-ZS", "猪舌120g", "盒", "400", null, null);

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        assertEquals(1, rows.size(), "1 行");
        ProductWarehouseDemandProjection r = rows.get(0);
        assertEquals("未分仓", r.getDestWarehouseCode(), "null → '未分仓'");
        assertEquals(0, new BigDecimal("400").compareTo(r.getDemand()));
    }

    @Test
    @DisplayName("混合桶: 同产品部分行有目的仓, 部分无 → 有仓+未分仓 两桶")
    void mixedWarehouseAndNull() {
        order("MW-MIX-1", SalesOrderStatus.CONFIRMED);
        item("MW-MIX-1", "PT-ZS", "猪舌120g", "盒", "300", "WH-DINDON-BEILUN", "叮咚-北仑总仓");
        item("MW-MIX-1", "PT-ZS", "猪舌120g", "盒", "100", null, null); // 旧行

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        assertEquals(2, rows.size(), "2 桶: 有仓 + 未分仓");
        Map<String, BigDecimal> byCode = rows.stream().collect(
                Collectors.toMap(ProductWarehouseDemandProjection::getDestWarehouseCode,
                                 ProductWarehouseDemandProjection::getDemand));
        assertTrue(byCode.containsKey("WH-DINDON-BEILUN"), "有仓桶");
        assertTrue(byCode.containsKey("未分仓"),           "未分仓桶");
        assertEquals(0, new BigDecimal("300").compareTo(byCode.get("WH-DINDON-BEILUN")));
        assertEquals(0, new BigDecimal("100").compareTo(byCode.get("未分仓")));
    }

    @Test
    @DisplayName("多仓跨订单累加: 2张 CONFIRMED 单同产品同仓 → 需求合并")
    void multiOrderSameWarehouseSum() {
        order("MW-SUM-A", SalesOrderStatus.CONFIRMED);
        order("MW-SUM-B", SalesOrderStatus.FINANCE_APPROVED);
        item("MW-SUM-A", "PT-ZS", "猪舌120g", "盒", "200", "WH-DINDON-BEILUN", "叮咚-北仑总仓");
        item("MW-SUM-B", "PT-ZS", "猪舌120g", "盒", "331", "WH-DINDON-BEILUN", "叮咚-北仑总仓");

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        assertEquals(1, rows.size(), "同产品同仓 → 1 行合并");
        assertEquals(0, new BigDecimal("531").compareTo(rows.get(0).getDemand()),
                "200 + 331 = 531");
    }

    @Test
    @DisplayName("CANCELLED 订单不计入任何仓桶")
    void cancelledExcluded() {
        order("MW-CANC-1", SalesOrderStatus.CANCELLED);
        item("MW-CANC-1", "PT-ZS", "猪舌120g", "盒", "9999", "WH-DINDON-BEILUN", "叮咚-北仑总仓");

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        assertTrue(rows.isEmpty(), "CANCELLED 订单不计入任何行");
    }

    @Test
    @DisplayName("多产品多仓: 猪舌2仓 + 猪蹄1仓 → 3行独立")
    void multiProductMultiWarehouse() {
        order("MW-MULTI-1", SalesOrderStatus.CONFIRMED);
        item("MW-MULTI-1", "PT-ZS", "猪舌120g", "盒", "531", "WH-DINDON-BEILUN", "叮咚-北仑总仓");
        item("MW-MULTI-1", "PT-ZS", "猪舌120g", "盒", "200", "WH-DINDON-PUDONG", "叮咚-浦东总仓");
        item("MW-MULTI-1", "PT-X",  "X产品",    "盒", "88",  "WH-DINDON-BEILUN", "叮咚-北仑总仓");

        List<ProductWarehouseDemandProjection> rows =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        FID, D, DEMAND_STATUSES);

        assertEquals(3, rows.size(), "3 行 (PT-ZS×2仓 + PT-X×1仓)");
    }
}
