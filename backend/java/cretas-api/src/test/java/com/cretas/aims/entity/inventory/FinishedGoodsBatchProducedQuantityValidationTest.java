package com.cretas.aims.entity.inventory;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation 单测 — FinishedGoodsBatch.producedQuantity @PositiveOrZero (R12-B2 测试补强, 2026-06-22).
 *
 * <p>B2 修复把 producedQuantity 的约束从 {@code @Positive} 改为 {@code @PositiveOrZero}:
 * 成品报损 (SCRAP) 减 producedQuantity, 全量报损会到 0; {@code @Positive} 会在
 * flush 触发 {@code ConstraintViolation} → 500。本测试锁定 0 是合法值, 防回归到 @Positive。
 *
 * <p>用 bean-validation Validator 直接校验 (而非 @DataJpaTest): 既精确隔离 @PositiveOrZero
 * 约束, 又无需为 FinishedGoodsBatch 搭建可持久化的全部 FK/NOT NULL 上下文 (脆弱)。
 * Hibernate flush 时执行的正是同一套 jakarta.validation 约束, 故等价覆盖。
 */
class FinishedGoodsBatchProducedQuantityValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    /** 全部 @NotBlank / @NotNull 必填字段填好的合法批次 (producedQuantity 由调用方设定). */
    private FinishedGoodsBatch validBatchWith(BigDecimal producedQuantity) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId("F006");
        batch.setBatchNumber("FG-20260622-0001");
        batch.setProductTypeId("pt-1");
        batch.setUnit("kg");
        batch.setWarehouseId("WH-WKS");
        batch.setProducedQuantity(producedQuantity);
        return batch;
    }

    @Test
    void producedQuantityZero_全量报损_noConstraintViolation() {
        // 全量报损后 producedQuantity = 0 → @PositiveOrZero 必须放行 (回归到 @Positive 会在此失败)。
        Set<ConstraintViolation<FinishedGoodsBatch>> violations =
                validator.validate(validBatchWith(BigDecimal.ZERO));

        assertThat(violations)
                .as("producedQuantity=0 应被 @PositiveOrZero 接受, 不抛 ConstraintViolation")
                .isEmpty();
    }

    @Test
    void producedQuantityPositive_noConstraintViolation() {
        // 正常创建路径 producedQuantity > 0 仍合法。
        Set<ConstraintViolation<FinishedGoodsBatch>> violations =
                validator.validate(validBatchWith(new BigDecimal("100.0000")));

        assertThat(violations).isEmpty();
    }

    @Test
    void producedQuantityNegative_hasConstraintViolation() {
        // 负数仍被 @PositiveOrZero 拒绝 (约束未被放宽过头)。
        Set<ConstraintViolation<FinishedGoodsBatch>> violations =
                validator.validate(validBatchWith(new BigDecimal("-1")));

        assertThat(violations)
                .as("producedQuantity<0 应被 @PositiveOrZero 拒绝")
                .anyMatch(v -> "producedQuantity".equals(v.getPropertyPath().toString()));
    }
}
