package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防呆 R4 幂等闸的**内容维度**（Steve 2026-08-15 拍板）。
 *
 * <p>原先的键是 (工厂 + 供应商 + 买手 + DRAFT + 60s)，**不含任何内容维度** ——
 * 同一买手 60s 内给同一供应商下两张**内容不同**的单会被误拦。
 * `status = DRAFT` 救不了场：web-admin 里「创建」与「提交」是两个动作，
 * 单据创建后就停在 DRAFT，误拦窗口是活的。
 *
 * <p>同族兄弟实现都带内容维度；唯一不带的 InternalTransfer 已于 2026-06-18
 * 因「备料被彻底卡住」整道移除。采购是第二个异类。
 *
 * <p>⚠️ 本类里「放行」那几条**必须配阳性对照**：如果 `findRecentDuplicateOrders`
 * 返回空，闸根本不会运行，那几条断言就会**恒真**地通过 —— 那时它们什么都没守。
 * 所以每条放行用例都额外 verify「候选单被查出来了、且行项目被加载做了比对」，
 * 断言的是「闸跑了并且判定放行」，不是「闸没跑」。
 */
class PurchaseOrderIdempotencyContentDimensionContractTest {

    private static final String FACTORY = "F006";
    private static final Long BUYER = 1309L;

    private final PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepository = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepository =
            mock(SupplierMaterialPurchaseSpecRepository.class);

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, null,
                supplierRepository, materialRepository, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relationRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", specRepository);

        Supplier supplier = new Supplier();
        supplier.setId("supplier-1");
        supplier.setFactoryId(FACTORY);
        supplier.setIsActive(true);
        when(supplierRepository.findByIdAndFactoryId("supplier-1", FACTORY)).thenReturn(Optional.of(supplier));

        for (String id : List.of("material-1", "material-2")) {
            RawMaterialType material = new RawMaterialType();
            material.setId(id);
            material.setFactoryId(FACTORY);
            material.setName("原料-" + id);
            material.setUnit("kg");
            material.setUnitPrice(new BigDecimal("9"));
            material.setTaxTreatment(TaxTreatment.TAXABLE);
            material.setTaxRate(TaxRate.TAX_13);
            when(materialRepository.findById(id)).thenReturn(Optional.of(material));

            SupplierMaterial relation = new SupplierMaterial();
            relation.setId("relation-" + id);
            relation.setFactoryId(FACTORY);
            relation.setSupplierId("supplier-1");
            relation.setMaterialTypeId(id);
            relation.setPurchaseUnit("kg");
            relation.setDefaultPurchasePrice(new BigDecimal("10"));
            relation.setActive(true);
            when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                    FACTORY, "supplier-1", id)).thenReturn(true);
            when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(
                    FACTORY, "supplier-1", id)).thenReturn(Optional.of(relation));
            when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
                    FACTORY, "relation-" + id)).thenReturn(List.of());
        }

        when(orderRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            if (order.getId() == null) order.setId("order-new");
            return order;
        });
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ─── 保护没有减弱 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("双击: 60s 内同买手同供应商、内容完全相同 → 仍然 409")
    void identicalContentWithinWindowIsStillRejected() {
        existingDraftWith(item("material-1", "5"));

        assertThatThrownBy(() -> service.createPurchaseOrder(FACTORY, request(item("material-1", "5")), BUYER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容相同")
                .hasMessageContaining("PO-EXISTING");
    }

    @Test
    @DisplayName("双击: 数量 scale 不同 (5 vs 5.00) 仍算相同 —— 不能用 BigDecimal.equals")
    void quantityScaleDifferenceStillCountsAsDuplicate() {
        existingDraftWith(item("material-1", "5.00"));

        assertThatThrownBy(() -> service.createPurchaseOrder(FACTORY, request(item("material-1", "5")), BUYER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容相同");
    }

    @Test
    @DisplayName("双击: 行序不同但内容相同 → 409 (多重集语义, 顺序无关)")
    void rowOrderDoesNotAffectDuplicateDetection() {
        existingDraftWith(item("material-2", "3"), item("material-1", "5"));

        assertThatThrownBy(() -> service.createPurchaseOrder(
                FACTORY, request(item("material-1", "5"), item("material-2", "3")), BUYER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容相同");
    }

    // ─── 误拦消除了 (每条都带阳性对照) ────────────────────────────────────────

    @Test
    @DisplayName("放行: 同供应商连下两张【数量不同】的单")
    void differentQuantityIsNotADuplicate() {
        existingDraftWith(item("material-1", "5"));

        PurchaseOrder created = service.createPurchaseOrder(
                FACTORY, request(item("material-1", "8")), BUYER);

        assertThat(created).isNotNull();
        assertGateActuallyEvaluatedContent();
    }

    @Test
    @DisplayName("放行: 同供应商连下两张【物料不同】的单")
    void differentMaterialIsNotADuplicate() {
        existingDraftWith(item("material-1", "5"));

        PurchaseOrder created = service.createPurchaseOrder(
                FACTORY, request(item("material-2", "5")), BUYER);

        assertThat(created).isNotNull();
        assertGateActuallyEvaluatedContent();
    }

    @Test
    @DisplayName("放行: 已有单是子集 (多一行) 也不算重复")
    void extraLineIsNotADuplicate() {
        existingDraftWith(item("material-1", "5"));

        PurchaseOrder created = service.createPurchaseOrder(
                FACTORY, request(item("material-1", "5"), item("material-2", "3")), BUYER);

        assertThat(created).isNotNull();
        assertGateActuallyEvaluatedContent();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * 阳性对照：证明闸**确实运行了**并且是「比对完内容后放行」。
     * 少了这一条，上面那几条放行用例在 `findRecentDuplicateOrders` 返回空时会恒真通过。
     */
    private void assertGateActuallyEvaluatedContent() {
        verify(orderRepository, atLeastOnce())
                .findRecentDuplicateOrders(any(), any(), any(), any());
        verify(itemRepository, atLeastOnce()).findByPurchaseOrderIdIn(List.of("order-existing"));
    }

    /** 让 60s 窗口内存在一张内容为 items 的 DRAFT 候选单。 */
    private void existingDraftWith(PurchaseOrderItem... items) {
        PurchaseOrder existing = new PurchaseOrder();
        existing.setId("order-existing");
        existing.setFactoryId(FACTORY);
        existing.setOrderNumber("PO-EXISTING");
        existing.setSupplierId("supplier-1");
        existing.setStatus(PurchaseOrderStatus.DRAFT);
        existing.setCreatedBy(BUYER);

        when(orderRepository.findRecentDuplicateOrders(any(), any(), any(), any()))
                .thenReturn(List.of(existing));
        for (PurchaseOrderItem i : items) {
            i.setPurchaseOrderId("order-existing");
        }
        when(itemRepository.findByPurchaseOrderIdIn(List.of("order-existing")))
                .thenReturn(List.of(items));
    }

    private PurchaseOrderItem item(String materialTypeId, String quantity) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setMaterialTypeId(materialTypeId);
        i.setQuantity(new BigDecimal(quantity));
        return i;
    }

    private CreatePurchaseOrderRequest request(PurchaseOrderItem... items) {
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId("supplier-1");
        request.setPurchaseType("DIRECT");
        request.setOrderDate(LocalDate.of(2026, 8, 15));
        request.setItems(java.util.Arrays.stream(items).map(src -> {
            CreatePurchaseOrderRequest.PurchaseOrderItemDTO dto =
                    new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
            dto.setMaterialTypeId(src.getMaterialTypeId());
            dto.setMaterialName("原料-" + src.getMaterialTypeId());
            dto.setQuantity(src.getQuantity());
            dto.setUnit("kg");
            dto.setQuantityUnit("kg");
            return dto;
        }).toList());
        return request;
    }
}
