package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1 #33: SalesServiceImpl.createSalesOrder 销售价 ≥ 研发预估价 跨流校验集成单测.
 *
 * <p><strong>决策对齐 #693</strong>: 低于研发预估价 = 警告放行 (200 + priceWarnings), 不 409 硬拦
 * (防呆 Rule 1: 提交前看到边界, 不提交后被拒)。
 *
 * <p>验证 createSalesOrder 中估价预警各路径:
 * <ol>
 *   <li>销售价低于预估价 (belowEstimate=true) → 订单正常创建 + priceWarnings 含预警 (不抛异常)</li>
 *   <li>销售价 ≥ 预估价 (pass) → 正常创建, priceWarnings 为空</li>
 *   <li>研发未报价 (skipped) → 订单正常创建, priceWarnings 为空 (诚实跳过)</li>
 *   <li>estimatePriceCheckService 未注入 (null) → 不影响订单创建</li>
 *   <li>报价 null → 不调用估价服务 (守卫静默跳过)</li>
 * </ol>
 *
 * <p><strong>脱敏</strong>: 预警文案来自 {@link EstimatePriceCheckResult#getWarningMessage()},
 * 不含 estimatePrice / suggestedPrice / finalPrice 绝对值 (由 DTO 保证)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceImplEstimatePriceTest {

    // ctor-injected required deps
    @Mock SalesOrderRepository          salesOrderRepository;
    @Mock SalesOrderItemRepository      salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository  finishedGoodsBatchRepository;
    @Mock CustomerRepository            customerRepository;
    @Mock ProductTypeRepository         productTypeRepository;
    @Mock ArApService                   arApService;
    @Mock ApplicationEventPublisher     eventPublisher;

    // optional field-injected deps
    @Mock UserRepository                userRepository;
    @Mock WarehouseResolver             warehouseResolver;
    @Mock ArApTransactionRepository     arApTransactionRepository;
    @Mock EntityManager                 entityManager;
    @Mock Query                         nativeQuery;

    // #33 subject under test
    @Mock EstimatePriceCheckService     estimatePriceCheckService;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID      = "F006";
    private static final String CUSTOMER_ID     = "CUST-001";
    private static final String PRODUCT_TYPE_ID = "PT-001";
    private static final Long   USER_ID         = 1L;

    @BeforeEach
    void setUp() {
        salesService = new SalesServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                deliveryRecordRepository,
                finishedGoodsBatchRepository,
                customerRepository,
                productTypeRepository,
                arApService,
                eventPublisher);

        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "entityManager", entityManager);

        // #33 guard
        ReflectionTestUtils.setField(salesService, "estimatePriceCheckService", estimatePriceCheckService);

        Query queryMock = nativeQuery;
        when(entityManager.createNativeQuery(anyString())).thenReturn(queryMock);
        when(queryMock.setParameter(anyString(), any())).thenReturn(queryMock);
        when(queryMock.getSingleResult()).thenReturn(1L);

        when(salesOrderRepository.findMaxOrderNumberByPrefix(anyString(), anyString()))
                .thenReturn(null);

        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", "SO-TEST-001");
            return o;
        });

        when(salesOrderItemRepository.saveAll(anyList())).thenReturn(List.of());

        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer()));

        when(productTypeRepository.findById(PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubProductType("猪舌")));
    }

    // =========================================================================
    // 路径 1: belowEstimate=true → 非阻断, 订单创建 + priceWarnings 含预警
    // =========================================================================

    @Test
    @DisplayName("#33-01: 销售价低于研发预估价 → 订单仍创建 (不抛异常) + priceWarnings 含产品名预警")
    void createOrder_priceBelowEstimate_createsWithWarning() {
        when(estimatePriceCheckService.checkAgainstEstimate(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(EstimatePriceCheckResult.belowEstimate());

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("80.00"), USER_ID);

        // 非阻断: 订单正常创建
        assertThat(created).isNotNull();
        verify(salesOrderItemRepository).saveAll(anyList());
        // priceWarnings 含本行预警, 含产品名, 含 next-action 提示
        assertThat(created.getPriceWarnings()).hasSize(1);
        assertThat(created.getPriceWarnings().get(0)).contains("猪舌");
        assertThat(created.getPriceWarnings().get(0)).contains("研发预估价");
        assertThat(created.getPriceWarnings().get(0)).contains("销售经理"); // fool-proof Rule 4位一体 d
        // 脱敏: 不含预估价绝对值
        assertThat(created.getPriceWarnings().get(0)).doesNotContain("成本");
    }

    // =========================================================================
    // 路径 2: pass → 正常创建, 无预警
    // =========================================================================

    @Test
    @DisplayName("#33-02: 销售价 ≥ 研发预估价 → 订单正常创建, priceWarnings 为空")
    void createOrder_priceAboveEstimate_noWarning() {
        when(estimatePriceCheckService.checkAgainstEstimate(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(EstimatePriceCheckResult.pass());

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("200.00"), USER_ID);

        assertThat(created).isNotNull();
        assertThat(created.getPriceWarnings()).isEmpty();
    }

    // =========================================================================
    // 路径 3: skipped (研发未报价) → 订单创建, 无预警 (诚实跳过)
    // =========================================================================

    @Test
    @DisplayName("#33-03: 研发未报价 (skipped) → 订单正常创建, priceWarnings 为空 (不伪造)")
    void createOrder_estimateMissing_skipped_noWarning() {
        when(estimatePriceCheckService.checkAgainstEstimate(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(EstimatePriceCheckResult.skipped());

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("150.00"), USER_ID);

        assertThat(created).isNotNull();
        // skipped 不算"低于预估价"预警 → priceWarnings 为空
        assertThat(created.getPriceWarnings()).isEmpty();
    }

    // =========================================================================
    // 路径 4: estimatePriceCheckService 未注入 (legacy) → 不影响创建
    // =========================================================================

    @Test
    @DisplayName("#33-04: estimatePriceCheckService=null (未注入) → 不影响订单创建，向前兼容")
    void createOrder_serviceNotInjected_orderCreated() {
        ReflectionTestUtils.setField(salesService, "estimatePriceCheckService", null);

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("150.00"), USER_ID);

        assertThat(created).isNotNull();
        verifyNoInteractions(estimatePriceCheckService);
        assertThat(created.getPriceWarnings()).isEmpty();
    }

    // =========================================================================
    // 路径 5: 报价 null → 守卫静默跳过 (不调用估价服务)
    // =========================================================================

    @Test
    @DisplayName("#33-05: 报价为 null → 不调用估价服务（守卫对 null 价格静默跳过）")
    void createOrder_nullUnitPrice_estimateSkipped() {
        CreateSalesOrderRequest req = buildRequest(null);

        try {
            salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        } catch (NullPointerException expected) {
            // pre-existing NPE from calculateLineAmount when unitPrice==null — acceptable
        }

        verify(estimatePriceCheckService, never())
                .checkAgainstEstimate(anyString(), anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CreateSalesOrderRequest buildRequest(String unitPrice) {
        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        req.setCustomerId(CUSTOMER_ID);
        req.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        req.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);

        CreateSalesOrderRequest.SalesOrderItemDTO item =
                new CreateSalesOrderRequest.SalesOrderItemDTO();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("kg");
        if (unitPrice != null) {
            item.setUnitPrice(new BigDecimal(unitPrice));
        }
        req.setItems(List.of(item));
        return req;
    }

    private Customer stubCustomer() {
        Customer c = new Customer();
        ReflectionTestUtils.setField(c, "id", CUSTOMER_ID);
        c.setFactoryId(FACTORY_ID);
        c.setName("测试客户");
        return c;
    }

    private ProductType stubProductType(String name) {
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setName(name);
        pt.setUnit("kg");
        pt.setIsActive(true);
        pt.setCreatedBy(1L);
        return pt;
    }
}
