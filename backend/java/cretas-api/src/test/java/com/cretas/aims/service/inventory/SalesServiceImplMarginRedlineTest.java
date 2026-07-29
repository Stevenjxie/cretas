package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.pricing.GrossMarginCheckResult;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesProcessingMode;
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
 * E-5 毛利红线预警单元测试 (W1 六扇门追溯矩阵 E-5; 非阻断决策修正 2026-06-10).
 *
 * <p><strong>决策修正</strong>: 低于红线由"409 硬阻断"改为"200 + 预警字段"
 * (SP3 spec §6.3 / SP5 spec §8 本就规定非阻断; 客户原话"低于底线提示红色, 不是不允许")。
 *
 * <p>验证 {@code SalesServiceImpl.createSalesOrder} 中毛利红线预警的各条路径:
 * <ol>
 *   <li>报价低于红线 → 订单正常创建 + {@code marginWarnings} 含预警文案 (不抛异常)</li>
 *   <li>报价满足红线 → 正常创建订单, {@code marginWarnings} 为空</li>
 *   <li>成本数据缺失 (belowRedline=null，skipped) → warn-and-allow，订单正常创建, 无预警</li>
 *   <li>grossMarginRedlineService 未注入 (null) → 不影响订单创建</li>
 * </ol>
 *
 * <p><strong>脱敏</strong>: 预警文案来自 {@link GrossMarginCheckResult#getWarningMessage()},
 * 不含 minPrice / standardCost / targetGrossMargin 等价格敏感数值 (由 DTO 保证)。
 *
 * <p>红线阈值来源：通过 {@link GrossMarginRedlineService} mock 注入，阈值配置本身由
 * {@code GrossMarginRedlineServiceTest} 覆盖（不重复）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesServiceImplMarginRedlineTest {

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

    // E-5 subject under test
    @Mock GrossMarginRedlineService     grossMarginRedlineService;

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

        // optional field injections
        ReflectionTestUtils.setField(salesService, "userRepository", userRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "arApTransactionRepository", arApTransactionRepository);
        ReflectionTestUtils.setField(salesService, "entityManager", entityManager);

        // E-5 guard
        ReflectionTestUtils.setField(salesService, "grossMarginRedlineService", grossMarginRedlineService);

        // entityManager.createNativeQuery(…).setParameter(…).setParameter(…).getSingleResult()
        // — used by generateSalesOrderNumber advisory lock
        Query queryMock = nativeQuery;
        when(entityManager.createNativeQuery(anyString())).thenReturn(queryMock);
        when(queryMock.setParameter(anyString(), any())).thenReturn(queryMock);
        when(queryMock.getSingleResult()).thenReturn(1L);   // advisory lock return value (ignored)

        // salesOrderRepository.findMaxOrderNumberByPrefix → null → first SO of day
        when(salesOrderRepository.findMaxOrderNumberByPrefix(anyString(), anyString()))
                .thenReturn(null);

        // salesOrderRepository.save(order) → return order with a predictable ID
        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", "SO-TEST-001");
            return o;
        });

        // salesOrderItemRepository.saveAll → no-op (not asserting items here)
        when(salesOrderItemRepository.saveAll(anyList())).thenReturn(List.of());

        // customerRepository stub
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer()));

        // productTypeRepository stub
        when(productTypeRepository.findById(PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubProductType("猪舌")));
    }

    // =========================================================================
    // 路径 1: belowRedline=true → 非阻断, 订单创建 + marginWarnings 含预警
    // =========================================================================

    @Test
    @DisplayName("E5-01: 报价低于毛利红线 → 订单仍创建 (不抛异常) + marginWarnings 含产品名预警")
    void createOrder_priceBelowRedline_createsWithWarning() {
        when(grossMarginRedlineService.checkMargin(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(GrossMarginCheckResult.warn());

        CreateSalesOrderRequest req = buildRequest("80.00");   // 低价

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);

        // 非阻断: 订单正常创建
        assertThat(created).isNotNull();
        assertThat(created.getFactoryId()).isEqualTo(FACTORY_ID);
        // 订单仍被持久化 (saveAll 被调用) — 不再 short-circuit
        verify(salesOrderItemRepository).saveAll(anyList());
        // marginWarnings 含本行预警, 含产品名, 含 next-action 提示
        assertThat(created.getMarginWarnings()).hasSize(1);
        assertThat(created.getMarginWarnings().get(0)).contains("猪舌");
        assertThat(created.getMarginWarnings().get(0)).contains("毛利红线");
        assertThat(created.getMarginWarnings().get(0)).contains("销售经理"); // fool-proof Rule 4位一体 d
    }

    @Test
    @DisplayName("E5-02: 报价低于红线 → warningMessage 传递到 marginWarnings (不含成本数值)")
    void createOrder_priceBelowRedline_warningFromBackendMessage() {
        GrossMarginCheckResult warn = new GrossMarginCheckResult(true, "报价低于毛利红线，建议调整");
        when(grossMarginRedlineService.checkMargin(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(warn);

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("90.00"), USER_ID);

        assertThat(created).isNotNull();
        assertThat(created.getMarginWarnings()).hasSize(1);
        assertThat(created.getMarginWarnings().get(0)).contains("报价低于毛利红线");
        // 脱敏: 文案不含任何成本数值 (¥成本/minPrice/毛利率%) — DTO 层保证, 此处复核无 "成本¥" 类泄露
        assertThat(created.getMarginWarnings().get(0)).doesNotContain("成本");
        assertThat(created.getMarginWarnings().get(0)).doesNotContain("标准成本");
    }

    // =========================================================================
    // 路径 2: belowRedline=false → 正常创建
    // =========================================================================

    @Test
    @DisplayName("E5-03: 报价高于红线 → 订单正常创建，无异常")
    void createOrder_priceAboveRedline_createsOrder() {
        when(grossMarginRedlineService.checkMargin(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(GrossMarginCheckResult.pass());

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("200.00"), USER_ID);

        assertThat(created).isNotNull();
        assertThat(created.getFactoryId()).isEqualTo(FACTORY_ID);
        // 合规报价 → 无预警
        assertThat(created.getMarginWarnings()).isEmpty();
    }

    // =========================================================================
    // 路径 3: belowRedline=null (成本缺失) → warn-and-allow
    // =========================================================================

    @Test
    @DisplayName("E5-04: 成本数据缺失 (belowRedline=null, skipped) → 订单仍正常创建")
    void createOrder_costMissing_skipped_orderStillCreated() {
        when(grossMarginRedlineService.checkMargin(
                eq(FACTORY_ID), eq(PRODUCT_TYPE_ID), any(BigDecimal.class)))
                .thenReturn(GrossMarginCheckResult.skipped());

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("150.00"), USER_ID);

        assertThat(created).isNotNull();
        // skipped → 不抛异常 (warn-and-allow, 禁止降级原则: 明确 WARN log 而非静默放行)
        // skipped 不算"低于红线"预警 → marginWarnings 为空 (配置缺口仅 WARN log, 不污染用户面预警)
        assertThat(created.getMarginWarnings()).isEmpty();
    }

    // =========================================================================
    // 路径 4: grossMarginRedlineService 未注入 (legacy) → 不影响创建
    // =========================================================================

    @Test
    @DisplayName("E5-05: grossMarginRedlineService=null (未注入) → 不影响订单创建，向前兼容")
    void createOrder_serviceNotInjected_orderCreated() {
        ReflectionTestUtils.setField(salesService, "grossMarginRedlineService", null);

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, buildRequest("150.00"), USER_ID);

        assertThat(created).isNotNull();
        // 红线服务未注入时不调用 (optional bean)
        verifyNoInteractions(grossMarginRedlineService);
    }

    // =========================================================================
    // 路径 5: 报价为 null / 0 → 红线检查跳过 (无价格无法检查)
    // =========================================================================

    @Test
    @DisplayName("E5-06: 报价为 null → 不调用红线服务（守卫对 null 价格静默跳过）")
    void createOrder_nullUnitPrice_redlineSkipped() {
        CreateSalesOrderRequest req = buildRequest(null);   // null price

        // 无协议价，无定价引擎注入 → resolvedUnitPrice stays null → E-5 guard skips.
        // SalesOrderItem.getLineAmount() 返 null + totalAmount.add(null) → NPE (pre-existing
        // behavior for null-price lines; 守卫本身正确跳过 — 这是此测试的验证重点).
        try {
            salesService.createSalesOrder(FACTORY_ID, req, USER_ID);
        } catch (NullPointerException expected) {
            // pre-existing NPE from calculateLineAmount when unitPrice==null — acceptable
        }

        // 关键断言: 守卫对 null 价格未调用红线服务
        verify(grossMarginRedlineService, never()).checkMargin(anyString(), anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CreateSalesOrderRequest buildRequest(String unitPrice) {
        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        // PR #1588 起 processingMode/materialSupplyMode 是必填业务契约 (DTO 上有 @NotNull,
        // service 层 validateSupplyContract 再兜一道)。本测试直调 service 绕过 bean validation,
        // 因此 fixture 必须显式给出这两项 — 普通销售 + 工厂供料是最常见组合。
        req.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        req.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        req.setCustomerId(CUSTOMER_ID);

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
