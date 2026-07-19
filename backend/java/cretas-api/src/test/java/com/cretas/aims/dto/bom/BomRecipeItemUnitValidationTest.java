package com.cretas.aims.dto.bom;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BomRecipeItemUnitValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {"box", "盒", "case", "箱", "g", "kg"})
    void canonicalAndLocalizedUnitAliasesReachServiceValidation(String unit) {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setMaterialTypeId("MATERIAL-1");
        dto.setUnit(unit);
        dto.setMaterialCategory("PACKAGING");

        assertThat(validator.validate(dto))
                .noneMatch(violation -> "unit".equals(violation.getPropertyPath().toString()));
    }
}
