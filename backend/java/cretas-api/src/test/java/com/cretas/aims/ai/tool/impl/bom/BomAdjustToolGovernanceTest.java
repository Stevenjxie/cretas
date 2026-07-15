package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.service.BomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BomAdjustToolGovernanceTest {

    private BomAdjustTool tool;

    @BeforeEach
    void setUp() {
        tool = new BomAdjustTool();
        ReflectionTestUtils.setField(tool, "bomService", mock(BomService.class));
    }

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
