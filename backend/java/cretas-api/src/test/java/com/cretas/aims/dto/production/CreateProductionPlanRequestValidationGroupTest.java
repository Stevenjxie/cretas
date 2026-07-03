package com.cretas.aims.dto.production;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产计划「编辑」dead-stub 修复 (2026-07) — {@link CreateProductionPlanRequest#plannedDate}
 * 的 {@code @FutureOrPresent} 约束改用 {@link CreateProductionPlanRequest.OnCreate} 分组,
 * 使得:
 * <ul>
 *   <li>创建端点 (POST /production-plans, POST /production-plans/draft) 用
 *       {@code @Validated(OnCreate.class)} — 计划日期不能是过去 (行为不变)。</li>
 *   <li>编辑端点 (PUT /production-plans/{planId}) 仍用 {@code @Valid} (隐式 Default 组) —
 *       计划日期可以改到今天/过去几天(六扇门"系统不强制按计划日" 软约束; 未开始的计划改期
 *       是常规需求, 不该被创建时的"不能是过去"规则挡住)。</li>
 * </ul>
 *
 * <p>本测试直接用 {@link Validator} 分别对 Default 组 / OnCreate 组校验同一个"计划日期=昨天"
 * 的请求, 验证分组切分符合预期; 同时验证其它字段的 {@code @NotNull} (无显式 groups, 落
 * {@link jakarta.validation.groups.Default}) 在两个组下都照常校验 — 不因为这次改动被误伤。
 */
@DisplayName("CreateProductionPlanRequest — plannedDate OnCreate 校验分组")
class CreateProductionPlanRequestValidationGroupTest {

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

    private static CreateProductionPlanRequest requestWithPlannedDate(LocalDate plannedDate) {
        CreateProductionPlanRequest req = new CreateProductionPlanRequest();
        req.setProductTypeId("PT-001");
        req.setPlannedQuantity(new BigDecimal("100"));
        req.setPlannedDate(plannedDate);
        return req;
    }

    @Test
    @DisplayName("编辑路径 (Default 组): 计划日期=昨天 → 不报违规 (改期到过去几天允许)")
    void update_pastPlannedDate_defaultGroup_passes() {
        CreateProductionPlanRequest req = requestWithPlannedDate(LocalDate.now().minusDays(1));

        Set<ConstraintViolation<CreateProductionPlanRequest>> violations = validator.validate(req);

        assertThat(violations)
                .as("PUT /production-plans/{planId} 用 @Valid = 只校验 Default 组, "
                        + "plannedDate 的 @FutureOrPresent 已挂到 OnCreate 组, 不应触发")
                .noneMatch(v -> v.getPropertyPath().toString().equals("plannedDate"));
    }

    @Test
    @DisplayName("创建路径 (OnCreate 组): 计划日期=昨天 → 报违规 (不能是过去, 行为不变)")
    void create_pastPlannedDate_onCreateGroup_violates() {
        CreateProductionPlanRequest req = requestWithPlannedDate(LocalDate.now().minusDays(1));

        Set<ConstraintViolation<CreateProductionPlanRequest>> violations =
                validator.validate(req, CreateProductionPlanRequest.OnCreate.class);

        assertThat(violations)
                .as("POST /production-plans 用 @Validated(OnCreate.class), OnCreate extends Default "
                        + "→ 过去日期仍应报 @FutureOrPresent 违规, 跟改动前行为一致")
                .anyMatch(v -> v.getPropertyPath().toString().equals("plannedDate")
                        && v.getMessage().contains("不能是过去"));
    }

    @Test
    @DisplayName("创建路径: 计划日期=今天 → 通过 (边界, FutureOrPresent 含今天)")
    void create_todayPlannedDate_onCreateGroup_passes() {
        CreateProductionPlanRequest req = requestWithPlannedDate(LocalDate.now());

        Set<ConstraintViolation<CreateProductionPlanRequest>> violations =
                validator.validate(req, CreateProductionPlanRequest.OnCreate.class);

        assertThat(violations)
                .as("今天是 FutureOrPresent 的合法边界值")
                .noneMatch(v -> v.getPropertyPath().toString().equals("plannedDate"));
    }

    @Test
    @DisplayName("创建路径: 计划日期=明天 → 通过 (happy path)")
    void create_futurePlannedDate_onCreateGroup_passes() {
        CreateProductionPlanRequest req = requestWithPlannedDate(LocalDate.now().plusDays(1));

        Set<ConstraintViolation<CreateProductionPlanRequest>> violations =
                validator.validate(req, CreateProductionPlanRequest.OnCreate.class);

        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("plannedDate"));
    }

    @Test
    @DisplayName("plannedDate=null → Default 组和 OnCreate 组都报 @NotNull (未被这次改动放宽)")
    void nullPlannedDate_violatesBothGroups() {
        CreateProductionPlanRequest req = requestWithPlannedDate(null);

        Set<ConstraintViolation<CreateProductionPlanRequest>> defaultViolations = validator.validate(req);
        Set<ConstraintViolation<CreateProductionPlanRequest>> onCreateViolations =
                validator.validate(req, CreateProductionPlanRequest.OnCreate.class);

        assertThat(defaultViolations)
                .as("编辑端点仍要求 plannedDate 非空 (只放宽'不能是过去', 不放宽'不能为空')")
                .anyMatch(v -> v.getPropertyPath().toString().equals("plannedDate")
                        && v.getMessage().contains("不能为空"));
        assertThat(onCreateViolations)
                .as("创建端点同理仍要求非空")
                .anyMatch(v -> v.getPropertyPath().toString().equals("plannedDate")
                        && v.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("productTypeId=null → Default 组和 OnCreate 组都报违规 (其它字段校验行为不受影响)")
    void nullProductTypeId_violatesBothGroups() {
        CreateProductionPlanRequest req = requestWithPlannedDate(LocalDate.now());
        req.setProductTypeId(null);

        Set<ConstraintViolation<CreateProductionPlanRequest>> defaultViolations = validator.validate(req);
        Set<ConstraintViolation<CreateProductionPlanRequest>> onCreateViolations =
                validator.validate(req, CreateProductionPlanRequest.OnCreate.class);

        assertThat(defaultViolations).anyMatch(v -> v.getPropertyPath().toString().equals("productTypeId"));
        assertThat(onCreateViolations).anyMatch(v -> v.getPropertyPath().toString().equals("productTypeId"));
    }
}
