package com.cretas.aims.ai.tool.impl.bom;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BomAdjustToolGovernanceTest {

    private final BomAdjustTool tool = new BomAdjustTool();

    @Test
    void yieldAdjustmentIsRejectedAsSystemManaged() {
        assertThatThrownBy(() -> tool.doPreview("F006", Map.of(
                "productTypeId", "SKU-1",
                "instruction", "把黄油鸡出成率改成80"), Map.of()))
                .hasMessageContaining("正式报工历史")
                .hasMessageContaining("不允许人工修改");
    }

    @Test
    void priceAdjustmentIsRejectedInFavorOfMaterialMaster() {
        assertThatThrownBy(() -> tool.doPreview("F006", Map.of(
                "productTypeId", "SKU-1",
                "instruction", "把黄油鸡单价改成12"), Map.of()))
                .hasMessageContaining("物料档案")
                .hasMessageContaining("移动均价");
    }
}
