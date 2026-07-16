package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RawMaterialTaxTreatmentValidationTest {

    private final RawMaterialTypeServiceImpl service = new RawMaterialTypeServiceImpl(
            mock(com.cretas.aims.repository.RawMaterialTypeRepository.class),
            mock(com.cretas.aims.repository.MaterialBatchRepository.class),
            mock(com.cretas.aims.repository.ConversionRepository.class),
            mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class),
            mock(com.cretas.aims.repository.material.MaterialCodeSegmentRepository.class),
            mock(com.cretas.aims.utils.ExcelUtil.class),
            mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class));

    @Test
    void exemptRequiresReasonButNotNumericTaxRate() {
        assertThatThrownBy(() -> validate(TaxTreatment.EXEMPT, null, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("免税依据");
        assertThatCode(() -> validate(TaxTreatment.EXEMPT, null, "农产品免税备案"))
                .doesNotThrowAnyException();
    }

    @Test
    void taxableStillRequiresConfiguredBusinessTaxRate() {
        assertThatThrownBy(() -> validate(TaxTreatment.TAXABLE, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("税率不能为空");
        assertThatCode(() -> validate(TaxTreatment.TAXABLE, TaxRate.TAX_9, null))
                .doesNotThrowAnyException();
    }

    private void validate(TaxTreatment treatment, TaxRate rate, String reason) {
        ReflectionTestUtils.invokeMethod(service, "validateRequiredPricing",
                treatment, rate, new BigDecimal("100"), reason);
    }
}
