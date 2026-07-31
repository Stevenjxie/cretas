package com.cretas.aims.dto.bom;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOM 明细的 materialCategory 白名单 —— 加第四类时**一共有三个承载点**，这里盯请求校验那一个。
 *
 * <p>🔴 2026-07-31/08-01 走前端验收时踩到：DB 的 {@code chk_bri_category} 已经用
 * V20261029_37 放开到四个值，前端页签也上线了，但**请求 DTO 上的 {@code @Pattern} 没跟上** ——
 * 保存副产行时后端返 400「物料分类必须是 RAW/AUXILIARY/PACKAGING 之一」。
 * 也就是说前两处改完仍然存不进去，这是同一次「加新的一类」的第三个承载点。</p>
 *
 * <p>三个承载点，别再漏：
 * ① DB {@code chk_bri_category}（V20261029_37）
 * ② 前端页签与过滤（bomCategoryTabs.ts）
 * ③ <b>请求 DTO 的 @Pattern（本测试）</b></p>
 */
class BomItemCategoryWhitelistTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private CreateBomRecipeRequest.BomRecipeItemDTO itemWithCategory(String category) {
        CreateBomRecipeRequest.BomRecipeItemDTO item = new CreateBomRecipeRequest.BomRecipeItemDTO();
        item.setMaterialCategory(category);
        return item;
    }

    private Set<String> categoryViolations(String category) {
        return VALIDATOR.validateProperty(itemWithCategory(category), "materialCategory").stream()
                .map(v -> v.getMessage())
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 🔴 修复前这条是红的：副产行被请求校验挡在门外，DB 放开了也存不进去。 */
    @Test
    void byproductCategoryIsAccepted() {
        assertThat(categoryViolations("BYPRODUCT"))
                .as("BYPRODUCT 必须能过请求校验, 否则 BOM 第四类的保存永远 400")
                .isEmpty();
    }

    /** 阳性对照：既有三类照旧放行 —— 证明不是我把校验整个删了。 */
    @Test
    void existingThreeCategoriesStillAccepted() {
        for (String category : new String[]{"RAW", "AUXILIARY", "PACKAGING"}) {
            assertThat(categoryViolations(category)).as("既有类别 %s 被误拦", category).isEmpty();
        }
    }

    /** 阴性对照：白名单仍然是白名单，乱填照旧拒绝（不是放开成任意字符串）。 */
    @Test
    void unknownCategoryStillRejected() {
        assertThat(categoryViolations("SOMETHING_ELSE")).isNotEmpty();
        assertThat(categoryViolations("副产"))
                .as("中文写法不是 BOM 行的类别取值, 仍应拒绝")
                .isNotEmpty();
    }
}
