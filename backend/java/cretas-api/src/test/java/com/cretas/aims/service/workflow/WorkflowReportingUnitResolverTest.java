package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowReportingUnitResolverTest {

    private final ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
    private final UnitContractService unitContractService = mock(UnitContractService.class);
    private WorkflowReportingUnitResolver resolver;

    @BeforeEach
    void setUp() {
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(CanonicalUnit.class));
        });
        resolver = new WorkflowReportingUnitResolver(productTypeRepository, unitContractService);
    }

    @Test
    void rawAndSemiFinishedReportingAlwaysUseKgEvenForLegacyGramMasters() {
        assertEquals("kg", resolver.resolve("F006", "RAW_MATERIAL", "RM-1", "g"));
        assertEquals("kg", resolver.resolve("F006", "SEMI_FINISHED", "PT-SEMI", "g"));
    }

    @Test
    void finishedGoodsReportingInheritsTheSkuBaseUnit() {
        ProductType finished = new ProductType();
        finished.setId("PT-FG");
        finished.setFactoryId("F006");
        finished.setUnit("盒");
        when(productTypeRepository.findByIdAndFactoryId("PT-FG", "F006"))
                .thenReturn(Optional.of(finished));

        assertEquals("盒", resolver.resolve("F006", "FINISHED_GOOD", "PT-FG", "g"));
    }

    @Test
    void missingFinishedSkuFailsClosedInsteadOfGuessingFromLegacyPort() {
        when(productTypeRepository.findByIdAndFactoryId("PT-MISSING", "F006"))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolve("F006", "FINISHED_GOOD", "PT-MISSING", "g"));

        assertEquals("WORKFLOW_REPORTING_UNIT_UNRESOLVED", error.getErrorCode());
    }
}
