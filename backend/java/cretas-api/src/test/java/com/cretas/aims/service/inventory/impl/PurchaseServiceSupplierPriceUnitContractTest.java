package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PurchaseServiceSupplierPriceUnitContractTest {

    private static final String FACTORY = "F006";
    private final PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepository = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepository =
            mock(SupplierMaterialPurchaseSpecRepository.class);
    private PurchaseServiceImpl service;
    private RawMaterialType material;
    private SupplierMaterial relation;

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
        when(supplierRepository.findByIdAndFactoryId("supplier-1", FACTORY))
                .thenReturn(Optional.of(supplier));

        material = new RawMaterialType();
        material.setId("material-1");
        material.setFactoryId(FACTORY);
        material.setName("原料A");
        material.setUnit("kg");
        material.setUnitPrice(new BigDecimal("9"));
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialRepository.findById("material-1")).thenReturn(Optional.of(material));

        relation = new SupplierMaterial();
        relation.setId("relation-1");
        relation.setFactoryId(FACTORY);
        relation.setSupplierId("supplier-1");
        relation.setMaterialTypeId("material-1");
        relation.setPurchaseUnit("kg");
        relation.setDefaultPurchasePrice(new BigDecimal("10"));
        relation.setActive(true);
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, "supplier-1", "material-1")).thenReturn(true);
        when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(
                FACTORY, "supplier-1", "material-1")).thenReturn(Optional.of(relation));

        when(orderRepository.findRecentDuplicateOrders(any(), any(), any(), any())).thenReturn(List.of());
        when(orderRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder order = invocation.getArgument(0);
            if (order.getId() == null) order.setId("order-1");
            return order;
        });
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void relationPriceAndUnitAreTheAuthoritativePurchaseContract() {
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
                FACTORY, "relation-1")).thenReturn(List.of());

        service.createPurchaseOrder(FACTORY, request("kg", null, null), 1309L);

        PurchaseOrderItem saved = capturedItem();
        assertThat(saved.getUnit()).isEqualTo("kg");
        assertThat(saved.getPriceUnit()).isEqualTo("kg");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("10");
        assertThat(saved.getLineAmount()).isEqualByComparingTo("50");
    }

    @Test
    void missingRelationPriceFallsBackToMaterialReferenceWithoutInventingZero() {
        relation.setDefaultPurchasePrice(null);
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
                FACTORY, "relation-1")).thenReturn(List.of());

        service.createPurchaseOrder(FACTORY, request("kg", null, null), 1309L);

        assertThat(capturedItem().getUnitPrice()).isEqualByComparingTo("9");
    }

    @Test
    void explicitPriceCannotBeSubmittedWithAnotherDenominatorUnit() {
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
                FACTORY, "relation-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.createPurchaseOrder(
                FACTORY, request("kg", new BigDecimal("11"), "case"), 1309L))
                .hasMessageContaining("计价单位");
    }

    @Test
    void packagePriceUsesSpecQuoteOrConvertsBasePriceByTheExplicitFactor() {
        SupplierMaterialPurchaseSpec spec = new SupplierMaterialPurchaseSpec();
        spec.setId("spec-1");
        spec.setFactoryId(FACTORY);
        spec.setSupplierMaterialId("relation-1");
        spec.setMaterialTypeId("material-1");
        spec.setPurchasePackageUnit("case");
        spec.setInventoryBaseUnit("kg");
        spec.setConversionFactor(new BigDecimal("10"));
        spec.setQuotedPrice(null);
        spec.setActive(true);
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(
                FACTORY, "relation-1")).thenReturn(List.of(spec));

        CreatePurchaseOrderRequest request = request("case", null, null);
        request.getItems().get(0).setPurchasePackagingSpecId("spec-1");
        service.createPurchaseOrder(FACTORY, request, 1309L);

        PurchaseOrderItem saved = capturedItem();
        assertThat(saved.getUnit()).isEqualTo("case");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("100");
        assertThat(saved.getInventoryQuantitySnapshot()).isEqualByComparingTo("50");
    }

    private CreatePurchaseOrderRequest request(String unit, BigDecimal explicitPrice, String priceUnit) {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId("material-1");
        item.setMaterialName("原料A");
        item.setQuantity(new BigDecimal("5"));
        item.setUnit(unit);
        item.setQuantityUnit(unit);
        item.setUnitPrice(explicitPrice);
        item.setPriceUnit(priceUnit);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setSupplierId("supplier-1");
        request.setPurchaseType("DIRECT");
        request.setOrderDate(LocalDate.of(2026, 7, 21));
        request.setItems(List.of(item));
        return request;
    }

    @SuppressWarnings("unchecked")
    private PurchaseOrderItem capturedItem() {
        ArgumentCaptor<Iterable<PurchaseOrderItem>> captor = ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(itemRepository).saveAll(captor.capture());
        return captor.getValue().iterator().next();
    }
}
