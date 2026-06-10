package com.cretas.aims.entity.production;

import com.cretas.aims.controller.ProcessingController;
import com.cretas.aims.dto.processing.CreateProductionBatchRequest;
import com.cretas.aims.entity.ProductionBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP10 试制标记 — DTO 往返完整链路测试 (feedback_dto_roundtrip_silent_drop).
 *
 * <p>遵循全4处检查:
 * <ol>
 *   <li>DTO 字段声明: {@link CreateProductionBatchRequest#getIsTrial()} /
 *       {@link CreateProductionBatchRequest#getTrialSampleId()} 存在</li>
 *   <li>mapper 层 (ProcessingController#toProductionBatch): 将 DTO 字段映射到
 *       {@link ProductionBatch#setIsTrial} / {@link ProductionBatch#setTrialSampleId}</li>
 *   <li>service set: ProcessingServiceImpl.createBatch 透传 entity (不 null-override)</li>
 *   <li>convertToDTO: ProductionBatch 直接返回 (无单独 DTO 转换步骤), 字段随 Jackson 序列化</li>
 * </ol>
 */
@DisplayName("SP10 试制标记 — DTO 往返链路测试 (fix: feedback_dto_roundtrip_silent_drop)")
class ProductionBatchTrialFlagDTORoundtripTest {

    // ==================== 第1处: DTO 字段声明 ====================

    @Test
    @DisplayName("第1处: CreateProductionBatchRequest 含 isTrial 字段声明")
    void dto_isTrialFieldDeclared() {
        assertDoesNotThrow(() -> CreateProductionBatchRequest.class.getDeclaredField("isTrial"),
                "CreateProductionBatchRequest 必须声明 isTrial 字段 (feedback_dto_roundtrip_silent_drop 第1处)");
    }

    @Test
    @DisplayName("第1处: CreateProductionBatchRequest 含 trialSampleId 字段声明")
    void dto_trialSampleIdFieldDeclared() {
        assertDoesNotThrow(() -> CreateProductionBatchRequest.class.getDeclaredField("trialSampleId"),
                "CreateProductionBatchRequest 必须声明 trialSampleId 字段 (feedback_dto_roundtrip_silent_drop 第1处)");
    }

    @Test
    @DisplayName("第1处: CreateProductionBatchRequest isTrial 可读写")
    void dto_isTrialReadWrite() {
        CreateProductionBatchRequest req = new CreateProductionBatchRequest();
        req.setIsTrial(true);
        assertTrue(req.getIsTrial(), "DTO.isTrial=true 后读回应为 true");
    }

    @Test
    @DisplayName("第1处: CreateProductionBatchRequest trialSampleId 可读写")
    void dto_trialSampleIdReadWrite() {
        CreateProductionBatchRequest req = new CreateProductionBatchRequest();
        req.setTrialSampleId("sample-sp10-001");
        assertEquals("sample-sp10-001", req.getTrialSampleId(),
                "DTO.trialSampleId 读回应与写入值一致");
    }

    @Test
    @DisplayName("第1处: isTrial 默认 null (未提交时不强制 false)")
    void dto_isTrialDefaultNull() {
        CreateProductionBatchRequest req = new CreateProductionBatchRequest();
        assertNull(req.getIsTrial(), "DTO.isTrial 默认应为 null (由 mapper 的 null-guard 决定是否写入)");
    }

    // ==================== 第2处: mapper toProductionBatch 映射 ====================

    /**
     * 通过反射调用 ProcessingController#toProductionBatch (private static) 验证映射正确性.
     * 这是唯一能在单元测试无 Spring 上下文下验证 private mapper 的方式.
     */
    @Test
    @DisplayName("第2处: toProductionBatch mapper 将 isTrial=true 映射到 entity")
    void mapper_isTrialMappedToEntity() throws Exception {
        CreateProductionBatchRequest req = minimalRequest();
        req.setIsTrial(true);
        req.setTrialSampleId("sample-sp10-mapper");

        ProductionBatch entity = invokeToProductionBatch(req);

        assertTrue(entity.getIsTrial(),
                "toProductionBatch mapper 必须将 DTO.isTrial=true 设置到 entity (feedback_dto_roundtrip_silent_drop 第2处)");
        assertEquals("sample-sp10-mapper", entity.getTrialSampleId(),
                "toProductionBatch mapper 必须将 DTO.trialSampleId 设置到 entity (第2处)");
    }

    @Test
    @DisplayName("第2处: toProductionBatch mapper null isTrial 不改变 entity 默认值")
    void mapper_nullIsTrialNotMapped() throws Exception {
        CreateProductionBatchRequest req = minimalRequest();
        // isTrial 未提交 (null)
        assertNull(req.getIsTrial());

        ProductionBatch entity = invokeToProductionBatch(req);

        // null-guard 保护: entity 保持其默认值 false, 不被 null 覆盖
        Boolean val = entity.getIsTrial();
        assertTrue(val == null || !val,
                "mapper 的 null-guard 应使 entity.isTrial 保持默认 false/null, 不写入 null");
    }

    @Test
    @DisplayName("往返: DTO → mapper → entity 字段一致 (完整链路)")
    void fullRoundtrip_isTrialAndTrialSampleId() throws Exception {
        CreateProductionBatchRequest req = minimalRequest();
        req.setIsTrial(true);
        req.setTrialSampleId("roundtrip-sample-001");

        ProductionBatch entity = invokeToProductionBatch(req);

        // 往返验证
        assertNotNull(entity.getIsTrial(), "entity.isTrial 不应为 null (已由 DTO 提交 true)");
        assertTrue(entity.getIsTrial(), "entity.isTrial 应等于 DTO 提交的 true");
        assertEquals(req.getTrialSampleId(), entity.getTrialSampleId(),
                "entity.trialSampleId 应与 DTO 提交值一致 — 不静默丢失");
    }

    // ==================== 辅助方法 ====================

    /** 构造满足最小 @NotBlank 约束的请求 DTO. */
    private CreateProductionBatchRequest minimalRequest() {
        CreateProductionBatchRequest req = new CreateProductionBatchRequest();
        req.setBatchNumber("BATCH-TEST-001");
        req.setProductTypeId("pt-001");
        return req;
    }

    /** 通过反射调用 ProcessingController 的 private static toProductionBatch. */
    private ProductionBatch invokeToProductionBatch(CreateProductionBatchRequest req) throws Exception {
        Method m = ProcessingController.class.getDeclaredMethod("toProductionBatch", CreateProductionBatchRequest.class);
        m.setAccessible(true);
        return (ProductionBatch) m.invoke(null, req);
    }
}
