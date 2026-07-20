package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialPurchaseSpecServiceImplTest {
    @Mock SupplierMaterialPurchaseSpecRepository repository;
    @Mock SupplierMaterialRepository relationRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    SupplierMaterialPurchaseSpecServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMaterialPurchaseSpecServiceImpl(repository, relationRepository,
                materialRepository, unitContractService);
    }

    @Test
    void createsCanonicalPackageSpecAndKeepsOnlyOneDefault() {
        SupplierMaterial relation = relation(true);
        RawMaterialType material = material("kg");
        SupplierMaterialPurchaseSpec previous = new SupplierMaterialPurchaseSpec();
        previous.setId("old"); previous.setDefaultSpec(true); previous.setActive(true);
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = "箱".equals(raw) ? "case" : raw;
            return new UnitNormalizationResult(raw, code, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(repository.findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc("F006", "rel"))
                .thenReturn(List.of(previous));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierMaterialPurchaseSpecRequest request = request("箱", "kg", true);
        var result = service.create("F006", "sup", "rel", request);

        assertThat(result.getPurchasePackageUnit()).isEqualTo("case");
        assertThat(result.getInventoryBaseUnit()).isEqualTo("kg");
        assertThat(result.getFactor()).isEqualByComparingTo("10");
        assertThat(previous.getDefaultSpec()).isFalse();
    }

    @Test
    void rejectsBaseUnitThatDiffersFromMaterialMasterData() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "g", false)))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void inactiveSupplierMaterialRelationCannotAddSpecs() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(false)));
        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "kg", false)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(materialRepository);
    }

    private SupplierMaterial relation(boolean active) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("rel"); relation.setFactoryId("F006"); relation.setSupplierId("sup");
        relation.setMaterialTypeId("mat"); relation.setActive(active);
        return relation;
    }

    private RawMaterialType material(String unit) {
        RawMaterialType material = new RawMaterialType();
        material.setId("mat"); material.setFactoryId("F006"); material.setUnit(unit);
        return material;
    }

    private SupplierMaterialPurchaseSpecRequest request(String packageUnit, String baseUnit, boolean defaultSpec) {
        SupplierMaterialPurchaseSpecRequest request = new SupplierMaterialPurchaseSpecRequest();
        request.setName("10kg/箱"); request.setPurchasePackageUnit(packageUnit);
        request.setInventoryBaseUnit(baseUnit); request.setFactor(new BigDecimal("10"));
        request.setQuotedPrice(new BigDecimal("120")); request.setDefaultSpec(defaultSpec);
        request.setActive(true);
        return request;
    }
}
