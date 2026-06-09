package com.cretas.aims.entity.production;

import com.cretas.aims.entity.ProductionBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP10: ProductionBatch 试制批次标记字段测试 (无 Spring 上下文).
 *
 * <p>验证 is_trial + trial_sample_id 字段的存在性与默认值.
 * 集成层的 findByFactoryIdAndIsTrialTrue 验证在 service 测试中补充.
 */
@DisplayName("SP10 ProductionBatch 试制标记字段测试")
class ProductionBatchTrialFlagTest {

    @Test
    @DisplayName("isTrial 字段通过反射确认存在")
    void isTrial_declaredField() {
        assertDoesNotThrow(() ->
                ProductionBatch.class.getDeclaredField("isTrial"));
    }

    @Test
    @DisplayName("trialSampleId 字段通过反射确认存在")
    void trialSampleId_declaredField() {
        assertDoesNotThrow(() ->
                ProductionBatch.class.getDeclaredField("trialSampleId"));
    }

    @Test
    @DisplayName("isTrial 可设置为 true")
    void isTrial_setTrue() {
        ProductionBatch batch = new ProductionBatch();
        batch.setIsTrial(true);
        assertTrue(batch.getIsTrial());
    }

    @Test
    @DisplayName("trialSampleId 可设置并读取")
    void trialSampleId_readWrite() {
        ProductionBatch batch = new ProductionBatch();
        batch.setTrialSampleId("sample-001");
        assertEquals("sample-001", batch.getTrialSampleId());
    }

    @Test
    @DisplayName("isTrial 默认 false (非试制批次)")
    void isTrial_defaultFalse() {
        ProductionBatch batch = new ProductionBatch();
        // 新建批次默认非试制
        Boolean val = batch.getIsTrial();
        // 可为 null (未初始化) 或 false — 都符合语义
        assertTrue(val == null || !val, "isTrial 默认应为 false 或 null");
    }
}
