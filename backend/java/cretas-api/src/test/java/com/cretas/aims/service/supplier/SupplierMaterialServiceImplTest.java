package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
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
import static org.mockito.ArgumentMatchers.any;
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
    void createsFactoryOwnedRelationWithCanonicalPurchaseUnit() {
        Supplier supplier = new Supplier(); supplier.setId("sup"); supplier.setFactoryId("F006"); supplier.setIsActive(true);
        RawMaterialType material = new RawMaterialType(); material.setId("mat"); material.setFactoryId("F006");
        material.setCode("RMT-1"); material.setName("原料A"); material.setUnit("kg");
        when(supplierRepository.findByIdAndFactoryId("sup", "F006")).thenReturn(Optional.of(supplier));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        when(repository.findByFactoryIdAndSupplierIdAndMaterialTypeId("F006", "sup", "mat"))
                .thenReturn(Optional.empty());
        CanonicalUnit kg = new CanonicalUnit("kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "kg", 3);
        when(unitContractService.normalize("F006", "KG")).thenReturn(new UnitNormalizationResult("KG", "kg", kg));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierMaterialRequest request = new SupplierMaterialRequest();
        request.setMaterialTypeId("mat"); request.setPurchaseUnit("KG"); request.setActive(true);
        var result = service.create("F006", "sup", request);

        assertThat(result.getPurchaseUnit()).isEqualTo("kg");
        assertThat(result.getMaterialName()).isEqualTo("原料A");
    }
}
