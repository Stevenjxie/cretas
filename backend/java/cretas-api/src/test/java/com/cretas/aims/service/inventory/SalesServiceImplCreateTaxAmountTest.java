package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
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
 * Fable 戳穿点根因修复验证 (2026-06-11): 标准下单流程产非零 {@code order.taxAmount}。
 *
 * <p><strong>背景</strong>: demo agent 实证含税三行凭证只能靠 SQL 手设 {@code sales_orders.tax_amount}
 * 才演成; 标准 API 永远产 {@code tax_amount=0} → 凭证 line3 (omitWhenZero 销项税) 永远省略 → 永远 2 行。
 *
 * <p><strong>根因 (case b)</strong>: {@code createSalesOrder}/{@code updateSalesOrder} 的税额汇总代码
 * (#737/SP4 已写) <em>逻辑正确</em>, 但 F006 全部客户 {@code default_tax_rate=NULL} 且下单未传
 * 显式 item.taxRate → 三层继承链 ({@code Customer.defaultTaxRate → SO.defaultTaxRate → item.taxRate})
 * 全部 fallback 到 0 → {@code lineAmountWithTax == lineAmount} → {@code taxAmount=0}。
 *
 * <p><strong>SP4 测试盲区</strong>: {@code SalesServiceImplTaxAmountSp4Test} 仅用 {@code makeOrderWithTax(...)}
 * 手设头部税额验证 DTO 暴露; {@code SalesServiceImplTaxRateInheritanceSp4Test} 仅在测试内
 * <em>模拟</em>继承链逻辑 (注释明言"完整继承路径通过集成测试覆盖") — 但<strong>该集成测试从未存在</strong>。
 * 本测试补上: 跑真实 {@code createSalesOrder}/{@code updateSalesOrder} 路径, 证明配了税率的客户
 * 自动产非零 {@code order.taxAmount}, 零税率客户退化 0 (向后兼容)。
 *
 * <p><strong>资金口径</strong> (#737 不变): {@code totalAmount} = 未税净额; {@code taxAmount} 单列;
 * 含税 = {@code totalAmount + taxAmount} (凭证借应收用含税)。本测试断言三者一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("createSalesOrder/updateSalesOrder — 标准 API 税额汇总 (Fable 根因修复)")
class SalesServiceImplCreateTaxAmountTest {

    @Mock SalesOrderRepository          salesOrderRepository;
    @Mock SalesOrderItemRepository      salesOrderItemRepository;
    @Mock SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock FinishedGoodsBatchRepository  finishedGoodsBatchRepository;
    @Mock CustomerRepository            customerRepository;
    @Mock ProductTypeRepository         productTypeRepository;
    @Mock ArApService                   arApService;
    @Mock ApplicationEventPublisher     eventPublisher;

    @Mock UserRepository                userRepository;
    @Mock WarehouseResolver             warehouseResolver;
    @Mock ArApTransactionRepository     arApTransactionRepository;
    @Mock EntityManager                 entityManager;
    @Mock Query                         nativeQuery;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID      = "F006";
    private static final String CUSTOMER_ID     = "CUST-DD-001";
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

        // generateSalesOrderNumber advisory-lock native query
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(1L);

        when(salesOrderRepository.findMaxOrderNumberByPrefix(anyString(), anyString()))
                .thenReturn(null);

        when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", "SO-TAX-TEST-001");
            return o;
        });
        when(salesOrderItemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        when(productTypeRepository.findById(PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubProductType("叮咚卤猪舌")));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // createSalesOrder — 三层继承链产非零税
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FABLE-01: 客户配 13% 税率 → 下单不传 item.taxRate → order.taxAmount 自动非零")
    void create_customerHasTaxRate_inheritedToOrderTaxAmount() {
        // 客户级 default_tax_rate=13 (本次根因修复 seed 的目标态)
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer(new BigDecimal("13.00"))));

        // 标准下单: item 不传 taxRate (现实中前端通常不传, 靠继承)
        CreateSalesOrderRequest req = buildRequest(new BigDecimal("10.00"), null);

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);

        // SO 级 default 从客户继承
        assertThat(created.getDefaultTaxRate()).isEqualByComparingTo(new BigDecimal("13.00"));
        // 未税净额 = 100 × 10.00 = 1000.00 (#737 口径不变)
        assertThat(created.getTotalAmount()).isEqualByComparingTo("1000.00");
        // ⭐ 关键: 标准 API 产非零税 = 1000.00 × 13% = 130.00 (无 SQL 注入)
        assertThat(created.getTaxAmount()).isEqualByComparingTo("130.00");
        // 含税 = totalAmount + taxAmount = 1130.00 (凭证应收账款金额来源)
        assertThat(created.getPayableAmount()).isEqualByComparingTo("1130.00");
    }

    @Test
    @DisplayName("FABLE-02: item 显式税率优先于客户 default — 9% > 13%")
    void create_itemExplicitTaxRate_overridesCustomerDefault() {
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer(new BigDecimal("13.00"))));

        CreateSalesOrderRequest req = buildRequest(new BigDecimal("10.00"), new BigDecimal("9.00"));

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);

        // 显式 9% 优先 → tax = 1000.00 × 9% = 90.00
        assertThat(created.getTaxAmount()).isEqualByComparingTo("90.00");
        assertThat(created.getPayableAmount()).isEqualByComparingTo("1090.00");
    }

    @Test
    @DisplayName("FABLE-03 向后兼容: 客户无税率 + item 无税率 → taxAmount=0 (凭证退化 2 行)")
    void create_noTaxRateAnywhere_taxAmountZero() {
        // 客户 default_tax_rate=null (修复前的 F006 现状, 以及永远不配税的免税客户)
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer(null)));

        CreateSalesOrderRequest req = buildRequest(new BigDecimal("10.00"), null);

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);

        assertThat(created.getDefaultTaxRate()).isNull();
        assertThat(created.getTotalAmount()).isEqualByComparingTo("1000.00");
        // 退化: 零税 → taxAmount=0 → 含税==未税 (凭证 line3 omitWhenZero 省略 → 2 行)
        assertThat(created.getTaxAmount()).isEqualByComparingTo("0");
        assertThat(created.getPayableAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("FABLE-04: 多行混合税率 → order.taxAmount = Σ 行税额 (减法兜底)")
    void create_multiLineMixedTax_sumsCorrectly() {
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(stubCustomer(new BigDecimal("13.00"))));
        when(productTypeRepository.findById("PT-002"))
                .thenReturn(Optional.of(stubProductType2("叮咚牛腱")));

        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        req.setCustomerId(CUSTOMER_ID);
        // 行1: 继承 13% — qty 100 × 10.00 = 1000.00 → tax 130.00
        CreateSalesOrderRequest.SalesOrderItemDTO i1 = new CreateSalesOrderRequest.SalesOrderItemDTO();
        i1.setProductTypeId(PRODUCT_TYPE_ID);
        i1.setProductName("叮咚卤猪舌");
        i1.setQuantity(new BigDecimal("100"));
        i1.setUnit("kg");
        i1.setUnitPrice(new BigDecimal("10.00"));
        // 行2: 显式 9% — qty 50 × 20.00 = 1000.00 → tax 90.00
        CreateSalesOrderRequest.SalesOrderItemDTO i2 = new CreateSalesOrderRequest.SalesOrderItemDTO();
        i2.setProductTypeId("PT-002");
        i2.setProductName("叮咚牛腱");
        i2.setQuantity(new BigDecimal("50"));
        i2.setUnit("kg");
        i2.setUnitPrice(new BigDecimal("20.00"));
        i2.setTaxRate(new BigDecimal("9.00"));
        req.setItems(List.of(i1, i2));

        SalesOrder created = salesService.createSalesOrder(FACTORY_ID, req, USER_ID);

        assertThat(created.getTotalAmount()).isEqualByComparingTo("2000.00");
        // Σ = 130.00 + 90.00 = 220.00
        assertThat(created.getTaxAmount()).isEqualByComparingTo("220.00");
        assertThat(created.getPayableAmount()).isEqualByComparingTo("2220.00");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // updateSalesOrder — 编辑草稿时税额对称重算
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FABLE-05: updateSalesOrder 改行项 → tax_amount 按 SO.defaultTaxRate 对称重算")
    void update_recomputesTaxAmount() {
        SalesOrder existing = new SalesOrder();
        ReflectionTestUtils.setField(existing, "id", "SO-UPD-001");
        existing.setFactoryId(FACTORY_ID);
        existing.setStatus(SalesOrderStatus.DRAFT);
        existing.setDefaultTaxRate(new BigDecimal("13.00")); // 已在 create 时从客户继承
        existing.setTotalAmount(new BigDecimal("500.00"));
        existing.setTaxAmount(new BigDecimal("65.00"));

        when(salesOrderRepository.findById("SO-UPD-001")).thenReturn(Optional.of(existing));
        when(salesOrderItemRepository.findBySalesOrderId("SO-UPD-001")).thenReturn(List.of());

        UpdateSalesOrderRequest req = new UpdateSalesOrderRequest();
        CreateSalesOrderRequest.SalesOrderItemDTO item = new CreateSalesOrderRequest.SalesOrderItemDTO();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("叮咚卤猪舌");
        item.setQuantity(new BigDecimal("200"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("10.00"));   // 200 × 10.00 = 2000.00
        req.setItems(List.of(item));

        SalesOrder updated = salesService.updateSalesOrder(FACTORY_ID, "SO-UPD-001", req);

        assertThat(updated.getTotalAmount()).isEqualByComparingTo("2000.00");
        // 继承 SO.defaultTaxRate 13% → tax = 2000.00 × 13% = 260.00 (非旧值 65.00)
        assertThat(updated.getTaxAmount()).isEqualByComparingTo("260.00");
        assertThat(updated.getPayableAmount()).isEqualByComparingTo("2260.00");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private CreateSalesOrderRequest buildRequest(BigDecimal unitPrice, BigDecimal itemTaxRate) {
        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        req.setCustomerId(CUSTOMER_ID);
        CreateSalesOrderRequest.SalesOrderItemDTO item = new CreateSalesOrderRequest.SalesOrderItemDTO();
        item.setProductTypeId(PRODUCT_TYPE_ID);
        item.setProductName("叮咚卤猪舌");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("kg");
        item.setUnitPrice(unitPrice);
        if (itemTaxRate != null) {
            item.setTaxRate(itemTaxRate);
        }
        req.setItems(List.of(item));
        return req;
    }

    private Customer stubCustomer(BigDecimal defaultTaxRate) {
        Customer c = new Customer();
        ReflectionTestUtils.setField(c, "id", CUSTOMER_ID);
        c.setFactoryId(FACTORY_ID);
        c.setName("叮咚");
        c.setDefaultTaxRate(defaultTaxRate);
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

    private ProductType stubProductType2(String name) {
        ProductType pt = new ProductType();
        pt.setId("PT-002");
        pt.setFactoryId(FACTORY_ID);
        pt.setName(name);
        pt.setUnit("kg");
        pt.setIsActive(true);
        pt.setCreatedBy(1L);
        return pt;
    }
}
