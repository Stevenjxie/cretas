package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialServiceImplTest {
    @Mock SupplierMaterialRepository repository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    SupplierMaterialServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMaterialServiceImpl(repository, supplierRepository, materialRepository, unitContractService);
    }

    @Test
    void createsFactoryOwnedRelationWithCanonicalPurchaseUnitAndRelationPrice() {
        stubOwnedMasterData(material("kg", new BigDecimal("8.00")));
        stubUnits();

        SupplierMaterialRequest request = request("KG");
        request.setDefaultPurchasePrice(new BigDecimal("10.00"));
        var result = service.create("F006", "sup", request);

        assertThat(result.getPurchaseUnit()).isEqualTo("kg");
        assertThat(result.getMaterialName()).isEqualTo("原料A");
        assertThat(result.getMaterialReferencePrice()).isEqualByComparingTo("8.00");
        assertThat(result.getMaterialReferencePriceUnit()).isEqualTo("kg");
        assertThat(result.getEffectivePurchasePrice()).isEqualByComparingTo("10.00");
        assertThat(result.getEffectivePriceUnit()).isEqualTo("kg");
        assertThat(result.getPriceSource()).isEqualTo("SUPPLIER_RELATION");
    }

    @Test
    void fallsBackToMaterialReferencePriceWithSafeMassConversion() {
        stubOwnedMasterData(material("kg", new BigDecimal("10.00")));
        stubUnits();

        var result = service.create("F006", "sup", request("g"));

        assertThat(result.getEffectivePurchasePrice()).isEqualByComparingTo("0.01");
        assertThat(result.getEffectivePriceUnit()).isEqualTo("g");
        assertThat(result.getPriceSource()).isEqualTo("MATERIAL_REFERENCE");
    }

    @Test
    void leavesEffectivePriceUnconfiguredWhenMaterialReferenceIsMissing() {
        stubOwnedMasterData(material("kg", null));
        stubUnits();

        var result = service.create("F006", "sup", request("kg"));

        assertThat(result.getEffectivePurchasePrice()).isNull();
        assertThat(result.getPriceSource()).isEqualTo("UNCONFIGURED");
    }

    @Test
    void rejectsPackagingUnitWithoutExplicitPurchaseSpec() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();

        assertThatThrownBy(() -> service.create("F006", "sup", request("case")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("采购包装规格");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnitOutsidePurchaseUsageScope() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();
        when(unitContractService.supportsUsage(eq("F006"), eq("minute"), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create("F006", "sup", request("minute")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许用于采购数量");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsZeroDefaultPurchasePriceInServiceBoundary() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();
        SupplierMaterialRequest request = request("kg");
        request.setDefaultPurchasePrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.create("F006", "sup", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于0");
        verify(repository, never()).saveAndFlush(any());
    }

    private void stubOwnedMasterData(RawMaterialType material) {
        Supplier supplier = new Supplier();
        supplier.setId("sup"); supplier.setFactoryId("F006"); supplier.setIsActive(true); supplier.setName("供应商A");
        when(supplierRepository.findByIdAndFactoryId("sup", "F006")).thenReturn(Optional.of(supplier));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        when(repository.findByFactoryIdAndSupplierIdAndMaterialTypeId("F006", "sup", "mat"))
                .thenReturn(Optional.empty());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubUnits() {
        lenient().when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = raw.equalsIgnoreCase("KG") ? "kg" : raw;
            CanonicalUnit unit = switch (code) {
                case "kg" -> unit("kg", UnitDimension.MASS, "g", "1000");
                case "g" -> unit("g", UnitDimension.MASS, "g", "1");
                case "case" -> unit("case", UnitDimension.PACKAGE, "case", "1");
                case "minute" -> unit("minute", UnitDimension.TIME, "minute", "1");
                default -> null;
            };
            return unit == null
                    ? new UnitNormalizationResult(raw, raw, null)
                    : new UnitNormalizationResult(raw, code, unit);
        });
        lenient().when(unitContractService.supportsUsage(eq("F006"), anyString(), any())).thenReturn(true);
    }

    private CanonicalUnit unit(String code, UnitDimension dimension, String base, String factor) {
        return new CanonicalUnit(code, dimension, base, new BigDecimal(factor), code, 3);
    }

    private SupplierMaterialRequest request(String unit) {
        SupplierMaterialRequest request = new SupplierMaterialRequest();
        request.setMaterialTypeId("mat");
        request.setPurchaseUnit(unit);
        request.setActive(true);
        return request;
    }

    private RawMaterialType material(String unit, BigDecimal referencePrice) {
        RawMaterialType material = new RawMaterialType();
        material.setId("mat"); material.setFactoryId("F006");
        material.setCode("RMT-1"); material.setName("原料A"); material.setUnit(unit);
        material.setUnitPrice(referencePrice);
        return material;
    }
}
