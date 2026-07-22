package com.cretas.aims.dto.inventory;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerSuppliedQuantityValidationTest {

    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void receiptQuantityRejectsDatabaseRoundingRisk() {
        CustomerSuppliedMaterialReceiptRequest request =
                new CustomerSuppliedMaterialReceiptRequest();
        request.setIdempotencyKey("receipt-precision");
        request.setReceivedQuantity(new BigDecimal("1.001"));

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("receivedQuantity"));

        request.setReceivedQuantity(new BigDecimal("1.01"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void expectedQuantityUsesTheSameInventoryBatchPrecision() {
        CreateSalesOrderRequest.SuppliedMaterialRequirementDTO request =
                new CreateSalesOrderRequest.SuppliedMaterialRequirementDTO();
        request.setMaterialTypeId("RM-1");
        request.setExpectedQuantity(new BigDecimal("3.501"));
        request.setUnit("kg");
        request.setExpectedArrivalAt(java.time.LocalDateTime.of(2026, 7, 23, 9, 0));
        request.setTargetWarehouseId("WH-RAW");

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("expectedQuantity"));

        request.setExpectedQuantity(new BigDecimal("3.50"));
        assertThat(validator.validate(request)).isEmpty();
    }
}
