package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工/均摊不在 BOM 归集：这两列必须留空，不能写 0。
 * 0 会被下游读成「这项成本是零」，null 才表示「此处不归集」。
 */
class BomRecipeCostNullCaliberTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java");

    @Test
    void newRecipe_laborAndOverheadTotalsDefaultToNull() {
        BomRecipe recipe = new BomRecipe();

        assertNull(recipe.getTotalLaborCost(), "人工总额默认必须是 null");
        assertNull(recipe.getTotalOverheadCost(), "均摊总额默认必须是 null");
    }

    /**
     * 分摊路径不得用 valueOrZero 把 null 塌成 0 —— 塌了就会把「不归集」写成「零成本」。
     * 这条断言落在源码上：该逻辑是 private 且深埋在多产出回算里，
     * 单测要跑通它需要构造整个 family + workflow revision，成本远高于收益。
     */
    @Test
    void allocationDoesNotCoerceNullCostsToZero() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("valueOrZero(target.getTotalLaborCost())"),
                "人工分摊不能走 valueOrZero —— null 会被塌成 0");
        assertFalse(source.contains("valueOrZero(target.getTotalOverheadCost())"),
                "均摊分摊不能走 valueOrZero —— null 会被塌成 0");
        assertTrue(source.contains("target.getTotalLaborCost() == null"),
                "人工分摊必须显式判 null 后短路");
        assertTrue(source.contains("target.getTotalOverheadCost() == null"),
                "均摊分摊必须显式判 null 后短路");
    }
}
