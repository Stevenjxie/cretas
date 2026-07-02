package com.cretas.aims.dto.supplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户张权原话 (2026-07-02): "这个不要强制 一般不会放系统里面的 都是一脉单传的" —
 * 供应商联系人/联系电话/地址 改为选填, 只有供应商名称必填。
 *
 * <p>Entity 层 contact_person/phone/address 列本就 nullable (Supplier.java 无
 * nullable=false), 不需要迁移 — 只是 DTO 上的 Bean Validation 太严。
 *
 * @see CreateSupplierRequest
 */
@DisplayName("CreateSupplierRequest: 联系人/电话/地址选填, 手机号格式仍校验")
class CreateSupplierRequestOptionalContactTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    private CreateSupplierRequest onlyName(String name) {
        CreateSupplierRequest req = new CreateSupplierRequest();
        req.setName(name);
        return req;
    }

    @Test
    @DisplayName("只填供应商名称, 联系人/电话/地址全空 → 无 ConstraintViolation")
    void onlyName_passes() {
        CreateSupplierRequest req = onlyName("领鲜六膳");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("供应商名称为空 → 仍触发 @NotBlank (name 保持必填)")
    void blankName_stillViolates() {
        CreateSupplierRequest req = onlyName("");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(req);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")
                        && v.getMessage().contains("供应商名称不能为空"));
    }

    @Test
    @DisplayName("手机号格式错误 (非11位/非1开头) → 仍触发 @Pattern 校验")
    void invalidPhoneFormat_violates() {
        CreateSupplierRequest req = onlyName("测试供应商");
        req.setPhone("12345");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(req);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("phone")
                        && v.getMessage().contains("手机号格式不正确"));
    }

    @Test
    @DisplayName("手机号合法格式 → 通过")
    void validPhoneFormat_passes() {
        CreateSupplierRequest req = onlyName("测试供应商");
        req.setPhone("13812345678");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("填了全部字段(name+contactPerson+phone+address) → 通过 (向后兼容)")
    void allFieldsFilled_passes() {
        CreateSupplierRequest req = onlyName("测试供应商");
        req.setContactPerson("张三");
        req.setPhone("13812345678");
        req.setAddress("上海市浦东新区");

        Set<ConstraintViolation<CreateSupplierRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }
}
