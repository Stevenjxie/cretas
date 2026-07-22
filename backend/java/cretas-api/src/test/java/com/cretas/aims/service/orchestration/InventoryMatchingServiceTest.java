package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D1 双仓流转 — InventoryMatchingService 单元测试 (2026-05-10 spec, PR #309 A1=A).
 *
 * <p>验证 D5 销售从 WH-LOG 出货语义:
 * <ol>
 *   <li>checkAvailability 调用 sumAvailableQuantityByProductTypeAndWarehouse with WH-LOG id</li>
 *   <li>reserveStock 调用 findAvailableBatchesByWarehouse with WH-LOG id</li>
 *   <li>crossFactoryEnabled=true 仍仅 WH-LOG (D5 2026-05-11: 集团池但只总仓出货)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryMatchingService D1 单元测试")
class InventoryMatchingServiceTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Mock
    private FactoryWarehouseRepository factoryWarehouseRepository;

    @Mock
    private FactoryWarehouseDefaultRepository factoryWarehouseDefaultRepository;

    @Mock
    private com.cretas.aims.service.inventory.FgReservationLedgerService reservationLedgerService;

    private WarehouseResolver warehouseResolver;

    private InventoryMatchingService service;

    private static final String FACTORY_ID = "F001";
    private static final String WH_LOG_ID = "wh-log-uuid-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";

    @BeforeEach
    void setUp() {
        // Real WarehouseResolver wired to mock repos (no default-warehouse config → hardcoded fallback)
        warehouseResolver = new WarehouseResolver(factoryWarehouseRepository, factoryWarehouseDefaultRepository);

        // Use ReflectionTestUtils since Lombok @RequiredArgsConstructor builds a 4-arg ctor
        service = new InventoryMatchingService(salesOrderRepository, finishedGoodsBatchRepository,
                warehouseResolver, reservationLedgerService);
        ReflectionTestUtils.setField(service, "crossFactoryEnabled", false);

        FactoryWarehouse whLog = new FactoryWarehouse();
        whLog.setId(WH_LOG_ID);
        whLog.setFactoryId(FACTORY_ID);
        whLog.setCode(WarehouseCodes.WH_LOG);
        whLog.setType(WarehouseType.LOGISTICS);
        whLog.setIsActive(true);

        // Lenient because not all tests will trigger this lookup
        Mockito.lenient().when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.of(whLog));
    }

    @Test
    @DisplayName("checkAvailability: 默认 (非 cross-factory) 调用 WH-LOG 过滤的 sum 方法")
    void checkAvailability_singleFactory_usesWarehouseFilter() {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("产品A");
        item.setQuantity(new BigDecimal("100"));
        item.setDeliveredQuantity(BigDecimal.ZERO);

        SalesOrder so = new SalesOrder();
        so.setId("SO-001");
        so.setFactoryId(FACTORY_ID);
        so.setItems(List.of(item));

        when(salesOrderRepository.findById("SO-001")).thenReturn(Optional.of(so));
        when(finishedGoodsBatchRepository
                .sumAvailableQuantityByProductTypeAndWarehouse(FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID))
                .thenReturn(new BigDecimal("150"));

        StockCheckResult result = service.checkAvailability(FACTORY_ID, "SO-001");

        assertTrue(result.isAllSatisfied(), "150 可用 ≥ 100 待发 — 应满足");
        verify(finishedGoodsBatchRepository).sumAvailableQuantityByProductTypeAndWarehouse(
                FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID);
        // 验证 NO legacy 全 warehouse method 调用
        verify(finishedGoodsBatchRepository, never())
                .sumAvailableQuantityByProductType(anyString(), anyString());
    }

    @Test
    @DisplayName("checkAvailability: crossFactoryEnabled=true 跨工厂仍仅 WH-LOG (D5 集团池+总仓过滤)")
    void checkAvailability_crossFactoryEnabled_skipsWarehouseFilter() {
        ReflectionTestUtils.setField(service, "crossFactoryEnabled", true);

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("产品A");
        item.setQuantity(new BigDecimal("100"));
        item.setDeliveredQuantity(BigDecimal.ZERO);

        SalesOrder so = new SalesOrder();
        so.setId("SO-002");
        so.setFactoryId(FACTORY_ID);
        so.setItems(List.of(item));

        when(salesOrderRepository.findById("SO-002")).thenReturn(Optional.of(so));
        // D5 (2026-05-11): cross-factory 分支也强制 WH-LOG 过滤
        when(finishedGoodsBatchRepository
                .sumAvailableQuantityByProductTypeAllFactoriesAndWarehouseCode(
                        PRODUCT_TYPE_ID, WarehouseCodes.WH_LOG))
                .thenReturn(new BigDecimal("200"));

        StockCheckResult result = service.checkAvailability(FACTORY_ID, "SO-002");

        assertTrue(result.isAllSatisfied());
        // D5: cross-factory + WH-LOG 查询被调用 1 次, 不再调用 unfiltered all-factories
        verify(finishedGoodsBatchRepository)
                .sumAvailableQuantityByProductTypeAllFactoriesAndWarehouseCode(
                        PRODUCT_TYPE_ID, WarehouseCodes.WH_LOG);
        verify(finishedGoodsBatchRepository, never())
                .sumAvailableQuantityByProductTypeAllFactories(anyString());
        verify(finishedGoodsBatchRepository, never())
                .sumAvailableQuantityByProductTypeAndWarehouse(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("reserveStock: 默认调用 WH-LOG 过滤的 findAvailableBatchesByWarehouse")
    void reserveStock_singleFactory_usesWarehouseFilter() {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FGB-001");
        batch.setBatchNumber("FGB-2026-001");
        batch.setProductTypeId(PRODUCT_TYPE_ID);
        batch.setProducedQuantity(new BigDecimal("100"));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setWarehouseId(WH_LOG_ID);

        when(finishedGoodsBatchRepository
                .findAvailableBatchesByWarehouse(FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID))
                .thenReturn(List.of(batch));

        service.reserveStock(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("50"));

        verify(finishedGoodsBatchRepository)
                .findAvailableBatchesByWarehouse(FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID);
        // 验证 NO 全 warehouse legacy method 调用
        verify(finishedGoodsBatchRepository, never())
                .findAvailableBatches(anyString(), anyString());
    }

    @Test
    @DisplayName("普通销售预留排除客户所有成品")
    void reserveStock_standardSaleSkipsCustomerOwnedBatch() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-STANDARD");
        order.setFactoryId(FACTORY_ID);
        order.setCustomerId("C-1");
        order.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        FinishedGoodsBatch batch = customerOwnedBatch(order.getId(), "C-1");
        when(finishedGoodsBatchRepository
                .findAvailableBatchesByWarehouse(FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID))
                .thenReturn(List.of(batch));

        service.reserveStock(FACTORY_ID, order.getId(), null, PRODUCT_TYPE_ID, BigDecimal.ONE);

        verifyNoInteractions(reservationLedgerService);
    }

    @Test
    @DisplayName("代加工预留只允许同客户同销售订单成品")
    void reserveStock_tollProcessingUsesMatchingCustomerOwnedBatch() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-TOLL");
        order.setFactoryId(FACTORY_ID);
        order.setCustomerId("C-1");
        order.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        FinishedGoodsBatch wrong = customerOwnedBatch("SO-OTHER", "C-1");
        FinishedGoodsBatch matching = customerOwnedBatch(order.getId(), "C-1");
        matching.setId("FGB-MATCH");
        when(finishedGoodsBatchRepository
                .findAvailableBatchesByWarehouse(FACTORY_ID, PRODUCT_TYPE_ID, WH_LOG_ID))
                .thenReturn(List.of(wrong, matching));

        service.reserveStock(FACTORY_ID, order.getId(), null, PRODUCT_TYPE_ID, BigDecimal.ONE);

        verify(reservationLedgerService).reserve(
                FACTORY_ID, order.getId(), null, matching, BigDecimal.ONE);
    }

    private FinishedGoodsBatch customerOwnedBatch(String salesOrderId, String customerId) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FGB-CUSTOMER");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("FGB-CUSTOMER");
        batch.setProductTypeId(PRODUCT_TYPE_ID);
        batch.setProducedQuantity(BigDecimal.TEN);
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setWarehouseId(WH_LOG_ID);
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId(customerId);
        batch.setSourceSalesOrderId(salesOrderId);
        return batch;
    }
}
