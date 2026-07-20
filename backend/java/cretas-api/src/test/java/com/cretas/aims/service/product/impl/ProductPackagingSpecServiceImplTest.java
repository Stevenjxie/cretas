package com.cretas.aims.service.product.impl;

import com.cretas.aims.dto.producttype.ProductPackagingSpecDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.cretas.aims.service.unit.UnitContractService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPackagingSpecServiceImplTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_ID = "SKU-001";

    @Mock
    private ProductPackagingSpecRepository repository;
    @Mock
    private ProductTypeRepository productTypeRepository;
    private ProductPackagingSpecServiceImpl service;

    @BeforeEach
    void setUp() {
        UnitContractService unitContractService = TestUnitContractFactory.contract();
        service = new ProductPackagingSpecServiceImpl(repository, productTypeRepository, unitContractService);
    }

    @Test
    void requiresSelectionWhenSameOuterUnitHasMultipleActiveSpecs() {
        ProductPackagingSpec twelve = spec("spec-12", "12盒/箱", "箱", "盒", "12", 0L);
        ProductPackagingSpec twentyFour = spec("spec-24", "24盒/箱", "箱", "盒", "24", 0L);
        when(repository.findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                FACTORY_ID, PRODUCT_ID)).thenReturn(List.of(twelve, twentyFour));

        assertThatThrownBy(() -> service.resolveSelection(FACTORY_ID, PRODUCT_ID, "箱", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo("PACKAGING_SPEC_REQUIRED"));
    }

    @Test
    void selectedSpecIsReturnedAndBaseUnitNeedsNoSelection() {
        ProductPackagingSpec twelve = spec("spec-12", "12盒/箱", "箱", "盒", "12", 0L);
        ProductPackagingSpec twentyFour = spec("spec-24", "24盒/箱", "箱", "盒", "24", 0L);
        when(repository.findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                FACTORY_ID, PRODUCT_ID)).thenReturn(List.of(twelve, twentyFour));
        when(repository.findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(
                "spec-24", FACTORY_ID, PRODUCT_ID)).thenReturn(Optional.of(twentyFour));

        ProductPackagingSpecService.PackagingSelection selected =
                service.resolveSelection(FACTORY_ID, PRODUCT_ID, "箱", "spec-24");
        ProductPackagingSpecService.PackagingSelection baseUnit =
                service.resolveSelection(FACTORY_ID, PRODUCT_ID, "盒", null);

        assertThat(selected.required()).isTrue();
        assertThat(selected.spec().getConversionFactor()).isEqualByComparingTo("24");
        assertThat(baseUnit.spec()).isNull();
    }

    @Test
    void resolveSelectionUsesCanonicalAliasesWithoutRelaxingPackageIdentity() {
        ProductPackagingSpec caseToBox = spec("spec-case-box", "8盒/箱", "case", "box", "8", 0L);
        when(repository.findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                FACTORY_ID, PRODUCT_ID)).thenReturn(List.of(caseToBox));
        when(repository.findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(
                "spec-case-box", FACTORY_ID, PRODUCT_ID)).thenReturn(Optional.of(caseToBox));

        assertThat(service.resolveSelection(FACTORY_ID, PRODUCT_ID, "箱", "spec-case-box").spec())
                .isSameAs(caseToBox);
        assertThat(service.resolveSelection(FACTORY_ID, PRODUCT_ID, "case", "spec-case-box").spec())
                .isSameAs(caseToBox);
        assertThat(service.resolveSelection(FACTORY_ID, PRODUCT_ID, "盒", null).spec()).isNull();
        assertThat(service.resolveSelection(FACTORY_ID, PRODUCT_ID, "box", null).spec()).isNull();

        assertThatThrownBy(() -> service.resolveSelection(
                FACTORY_ID, PRODUCT_ID, "盒", "spec-case-box"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("PACKAGING_SPEC_UNIT_MISMATCH"));
    }

    @Test
    void replacePreservesIdsAndProjectsFirstSpecToLegacyFields() {
        ProductType product = new ProductType();
        product.setId(PRODUCT_ID);
        product.setFactoryId(FACTORY_ID);
        product.setUnit("盒");
        product.setGramsPerUnit(new BigDecimal("200"));

        ProductPackagingSpec twelve = spec("spec-12", "12盒/箱", "箱", "盒", "12", 3L);
        ProductPackagingSpec twentyFour = spec("spec-24", "24盒/箱", "箱", "盒", "24", 4L);
        when(repository.findByFactoryIdAndProductTypeIdOrderBySortOrderAscCreatedAtAsc(
                FACTORY_ID, PRODUCT_ID)).thenReturn(List.of(twelve, twentyFour));
        when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<ProductPackagingSpec> persisted = invocation.getArgument(0);
            persisted.forEach(spec -> spec.setVersion(spec.getVersion() + 1));
            return persisted;
        });

        List<ProductPackagingSpecDTO> result = service.replace(product, List.of(
                dto("spec-12", "12盒/箱", "12", 3L),
                dto("spec-24", "24盒/箱", "24", 4L)));

        assertThat(result).extracting(ProductPackagingSpecDTO::id)
                .containsExactly("spec-12", "spec-24");
        assertThat(product.getLevel1Unit()).isEqualTo("箱");
        assertThat(product.getBoxConversionCoefficient()).isEqualByComparingTo("12");
        assertThat(product.getSpecification()).isEqualTo("200g/盒 12盒/箱 2.4kg/箱 24盒/箱 4.8kg/箱");
        assertThat(twelve.getDeletedAt()).isNull();
        assertThat(twentyFour.getDeletedAt()).isNull();
        verify(productTypeRepository).save(product);
    }

    @Test
    void rejectsFractionalCountInPackagingSpec() {
        ProductType product = new ProductType();
        product.setId(PRODUCT_ID);
        product.setFactoryId(FACTORY_ID);
        product.setUnit("盒");

        assertThatThrownBy(() -> service.replace(product, List.of(
                dto(null, "半箱", "10.5", 0L))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("PACKAGING_SPEC_FACTOR_NOT_INTEGER"));
    }

    private ProductPackagingSpecDTO dto(String id, String name, String factor, long version) {
        return new ProductPackagingSpecDTO(
                id, name, "箱", "盒", new BigDecimal(factor), false, true, 0, version);
    }

    private ProductPackagingSpec spec(
            String id, String name, String packageUnit, String baseUnit, String factor, long version) {
        ProductPackagingSpec spec = new ProductPackagingSpec();
        spec.setId(id);
        spec.setFactoryId(FACTORY_ID);
        spec.setProductTypeId(PRODUCT_ID);
        spec.setName(name);
        spec.setPackageUnit(packageUnit);
        spec.setBaseUnit(baseUnit);
        spec.setConversionFactor(new BigDecimal(factor));
        spec.setDefaultSpec(false);
        spec.setActive(true);
        spec.setSortOrder(0);
        spec.setVersion(version);
        return spec;
    }
}
