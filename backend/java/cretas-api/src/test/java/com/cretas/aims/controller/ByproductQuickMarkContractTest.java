package com.cretas.aims.controller;

import com.cretas.aims.dto.material.ByproductMarkRequest;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画布「标记为副产」对话框点「标记并选入」必须真的能标上 —— 产品负责人 2026-08-17 报障。
 *
 * <h3>缺陷</h3>
 * 用户在对话框里搜「鸡油」, 候选 {@code YL114 — 鸡油 · kg} 已正确列出并选中,
 * 点「标记并选入」→ 右上角连弹两条红色 <b>「原材料名称不能为空」</b>。
 * 用户明明选了物料, 却被告知名称为空。
 *
 * <h3>根因</h3>
 * 前端发的是 {@code PUT /raw-material-types/{id}} + body {@code {"isByproduct": true}},
 * 代码注释写着「null-tolerant 更新: 后端其余字段判空不动」。service 层**确实**是
 * null-tolerant ({@code if (dto.getIsByproduct() != null) ...}), 但控制器参数上的
 * {@code @Valid} <b>在进 service 之前</b>就跑完了, 而 {@code RawMaterialTypeDTO.name}
 * 带 {@code @NotBlank(message = "原材料名称不能为空")}。
 *
 * <p>⇒ 那套 null-tolerant 分支是**到不了的代码**。这是本仓的形态 B 的一个变体:
 * 机制写好了, 但它前面还有一道闸, 外面那道说了算。
 *
 * <h3>为什么不去掉 {@code @NotBlank}</h3>
 * 同一个 DTO 也用于 {@code POST} 新建物料, 那里「名称不能为空」是**真的在守东西**。
 * 放宽它等于为了修一个入口去拆掉另一个入口的保护。所以把「标记副产」做成一个
 * 自带窄契约的动作。
 */
class ByproductQuickMarkContractTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private static Set<String> messagesFor(Object dto) {
        return VALIDATOR.validate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // 阳性对照: 先证明那个 400 是真的 —— 否则下面的断言在守空气
    // ------------------------------------------------------------------

    @Test
    @DisplayName("阳性对照: 只带 isByproduct 的 RawMaterialTypeDTO 【确实】被判「原材料名称不能为空」")
    void legacyDtoStillRejectsPartialBody() {
        RawMaterialTypeDTO partial = new RawMaterialTypeDTO();
        partial.setIsByproduct(Boolean.TRUE);

        assertThat(messagesFor(partial))
                .as("这条是用户实际撞到的那句错误。它如果【不】出现, 说明本测试没有在复现真实缺陷, "
                        + "下面那条「新契约放行」的断言也就失去了对照")
                .contains("原材料名称不能为空");
    }

    @Test
    @DisplayName("@NotBlank 仍然守着新建物料这条路 —— 修副产不许把它拆掉")
    void notBlankStillProtectsCreate() {
        RawMaterialTypeDTO blankName = new RawMaterialTypeDTO();
        blankName.setName("   ");
        blankName.setCategory("油脂");

        assertThat(messagesFor(blankName))
                .as("空白名称在新建路径上必须仍然被拒 —— 这条约束不是这次要放宽的东西")
                .contains("原材料名称不能为空");
    }

    // ------------------------------------------------------------------
    // 修复后的窄契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("🔴 回归: 只带 isByproduct 的窄请求体通过校验 —— 「标记并选入」不再被名称卡住")
    void narrowRequestPassesValidation() {
        ByproductMarkRequest request = new ByproductMarkRequest(Boolean.TRUE);

        assertThat(messagesFor(request))
                .as("窄契约上不该有任何与物料名称相关的约束; 有的话说明又把整个物料 DTO 拖进来了")
                .isEmpty();
    }

    @Test
    @DisplayName("窄请求体自己也不许空 —— 「没传」和「传了 false」是两件事, 不猜")
    void narrowRequestRejectsMissingFlag() {
        assertThat(messagesFor(new ByproductMarkRequest(null)))
                .as("兜底成 false 会把「我没说」翻译成「不是副产」")
                .contains("副产标记不能为空");
    }

    @Test
    @DisplayName("取消标记 (false) 是合法请求, 不能被当成缺失挡掉")
    void narrowRequestAcceptsFalse() {
        assertThat(messagesFor(new ByproductMarkRequest(Boolean.FALSE))).isEmpty();
    }

    // ------------------------------------------------------------------
    // 接线: 光有 DTO 不算, 得真有一个端点收它
    // ------------------------------------------------------------------

    @Test
    @DisplayName("接线: 控制器上真有一个 PATCH {id}/byproduct 收这个窄请求体")
    void controllerExposesNarrowEndpoint() {
        Method endpoint = Arrays.stream(RawMaterialTypeController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PatchMapping.class))
                .filter(m -> Arrays.asList(m.getParameterTypes()).contains(ByproductMarkRequest.class))
                .findFirst()
                .orElse(null);

        assertThat(endpoint)
                .as("DTO 建好了但没有端点收 = 机制在、没接上; 前端照样 404")
                .isNotNull();
        assertThat(endpoint.getAnnotation(PatchMapping.class).value())
                .as("路径必须是前端调的那个")
                .containsExactly("/{id}/byproduct");
    }
}
