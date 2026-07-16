package com.cretas.aims.dto.bom;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BomItemRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void createRawLinkAllowsMissingStandardQuantity() {
        CreateBomItemRequest request = validCreateRequest("RAW", null);

        assertNoStandardQuantityViolation(validator.validate(request));
    }

    @Test
    void createRequestDefaultsToRawAndAllowsMissingStandardQuantity() {
        CreateBomItemRequest request = validCreateRequest(null, null);

        assertNoStandardQuantityViolation(validator.validate(request));
    }

    @Test
    void createPackagingRequiresPositiveStandardQuantity() {
        CreateBomItemRequest request = validCreateRequest("PACKAGING", null);

        assertHasStandardQuantityViolation(validator.validate(request));
    }

    @Test
    void updateAuxiliaryRequiresPositiveStandardQuantity() {
        UpdateBomItemRequest request = new UpdateBomItemRequest();
        request.setProductTypeId("PT-001");
        request.setMaterialTypeId("MT-001");
        request.setMaterialCategory("AUXILIARY");
        request.setStandardQuantity(null);

        assertHasStandardQuantityViolation(validator.validate(request));
    }

    @Test
    void rawLinkStillRejectsZeroStandardQuantityWhenProvided() {
        CreateBomItemRequest request = validCreateRequest("RAW", BigDecimal.ZERO);

        assertHasStandardQuantityViolation(validator.validate(request));
    }

    private static CreateBomItemRequest validCreateRequest(String category, BigDecimal quantity) {
        CreateBomItemRequest request = new CreateBomItemRequest();
        request.setProductTypeId("PT-001");
        request.setMaterialTypeId("MT-001");
        request.setMaterialCategory(category);
        request.setStandardQuantity(quantity);
        return request;
    }

    private static void assertNoStandardQuantityViolation(Set<? extends ConstraintViolation<?>> violations) {
        assertFalse(hasStandardQuantityViolation(violations),
                () -> "Unexpected standardQuantity violation: " + violations);
    }

    private static void assertHasStandardQuantityViolation(Set<? extends ConstraintViolation<?>> violations) {
        assertTrue(hasStandardQuantityViolation(violations),
                () -> "Expected standardQuantity violation but got: " + violations);
    }

    private static boolean hasStandardQuantityViolation(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().anyMatch(violation ->
                violation.getPropertyPath().toString().startsWith("standardQuantity"));
    }
}
