package com.cretas.aims.dto.supplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSupplierRequestRequiredProfileTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private CreateSupplierRequest validRequest() {
        CreateSupplierRequest request = new CreateSupplierRequest();
        request.setName("测试供应商");
        request.setContactPerson("张三");
        request.setPhone("021-61234567-801");
        request.setAddress("上海市浦东新区测试路 1 号");
        return request;
    }

    @Test
    void allFourRequiredFieldsAreEnforced() {
        CreateSupplierRequest request = new CreateSupplierRequest();

        Set<String> fields = validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());

        assertThat(fields).contains("name", "contactPerson", "phone", "address");
    }

    @Test
    void mainlandMobileAndCompanyLandlineAreAccepted() {
        CreateSupplierRequest request = validRequest();
        assertThat(validator.validate(request)).isEmpty();

        request.setPhone("13812345678");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void invalidPhoneAndPunctuationOnlyAddressAreRejected() {
        CreateSupplierRequest request = validRequest();
        request.setPhone("12345");
        request.setAddress("---");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(request);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("phone", "address");
    }

    @Test
    void supplierImportConfirmRequiresSha256Digest() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("not-a-sha256");
        request.setIdempotencyKey("import-1");
        SupplierImportPreviewDTO.SupplierRowData row = new SupplierImportPreviewDTO.SupplierRowData();
        row.setName("测试供应商"); row.setContactPerson("张三"); row.setPhone("13812345678");
        row.setAddress("上海市1号"); request.setRows(java.util.List.of(row));

        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .contains("fileDigest");
    }
}
