package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EncodingRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasEncodingRuleController 单元测试 — Canvas Phase BCP3.
 *
 * <p>Coverage:
 * <ol>
 *   <li>list → 工厂级 + 系统级 merge</li>
 *   <li>getById not-found → 404</li>
 *   <li>getById 跨工厂 → 403</li>
 *   <li>create 重复 entityType → 409 (idempotency)</li>
 *   <li>create 成功 → save</li>
 *   <li>update 跨工厂 → 403</li>
 *   <li>update 版本不一致 → 409 (AUD-4 P1 乐观锁)</li>
 *   <li>update 非法 resetCycle → 400</li>
 *   <li>delete → soft delete</li>
 * </ol>
 */
@DisplayName("CanvasEncodingRuleController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasEncodingRuleControllerTest {

    @Mock
    private EncodingRuleRepository repository;

    @InjectMocks
    private CanvasEncodingRuleController controller;

    private static final String FACTORY_ID = "F001";

    private static EncodingRule makeRule(String factoryId, String entityType, String id) {
        EncodingRule r = EncodingRule.builder()
                .id(id)
                .factoryId(factoryId)
                .entityType(entityType)
                .ruleName("test rule")
                .encodingPattern("X-{YYYYMMDD}-{SEQ:4}")
                .sequenceLength(4)
                .resetCycle("DAILY")
                .currentSequence(0L)
                .separator("-")
                .includeFactoryCode(true)
                .enabled(true)
                .optLockVersion(0L)
                .build();
        return r;
    }

    @Test
    @DisplayName("list → 工厂级 + 系统级 merge")
    void listMergeFactoryAndSystem() {
        when(repository.findByFactoryIdAndEnabledTrue(FACTORY_ID))
                .thenReturn(List.of(makeRule(FACTORY_ID, "MATERIAL_BATCH", "id-1")));
        when(repository.findByFactoryIdIsNullAndEnabledTrue())
                .thenReturn(List.of(makeRule(null, "PROCESSING_BATCH", "id-2")));

        ApiResponse<List<EncodingRule>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        assertEquals("MATERIAL_BATCH", resp.getData().get(0).getEntityType());
    }

    @Test
    @DisplayName("getById 不存在 → 404")
    void getByIdNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getById(FACTORY_ID, "nope"));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("getById 跨工厂 → 403")
    void getByIdCrossFactory() {
        when(repository.findById("id-x")).thenReturn(
                Optional.of(makeRule("F002", "MATERIAL_BATCH", "id-x")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getById(FACTORY_ID, "id-x"));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("create 重复 entityType → 409 + actionHint")
    void createDuplicateEntityType() {
        when(repository.existsByFactoryIdAndEntityType(FACTORY_ID, "MATERIAL_BATCH"))
                .thenReturn(true);

        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "MATERIAL_BATCH");
        body.put("ruleName", "新规则");
        body.put("encodingPattern", "M-{SEQ:4}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(409, ex.getCode());
        assertNotNull(ex.getActionHint());
        assertEquals("entityType", ex.getHintTarget());
    }

    @Test
    @DisplayName("create 成功 → save")
    void createSuccess() {
        when(repository.existsByFactoryIdAndEntityType(FACTORY_ID, "SHIPMENT"))
                .thenReturn(false);
        when(repository.save(any(EncodingRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "SHIPMENT");
        body.put("ruleName", "出货编码");
        body.put("encodingPattern", "SH-{YYYYMM}-{SEQ:6}");
        body.put("sequenceLength", 6);
        body.put("resetCycle", "MONTHLY");

        ApiResponse<EncodingRule> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals("SHIPMENT", resp.getData().getEntityType());
        assertEquals(6, resp.getData().getSequenceLength());
    }

    @Test
    @DisplayName("update 跨工厂 → 403")
    void updateCrossFactoryBlocked() {
        when(repository.findById("id-x"))
                .thenReturn(Optional.of(makeRule("F002", "MATERIAL_BATCH", "id-x")));

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "试图改");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-x", body));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("update 版本不一致 → 409 (AUD-4 P1)")
    void updateStaleVersion() {
        EncodingRule r = makeRule(FACTORY_ID, "MATERIAL_BATCH", "id-1");
        r.setOptLockVersion(5L);
        when(repository.findById("id-1")).thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 3L);
        body.put("ruleName", "更新");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-1", body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=5"));
    }

    @Test
    @DisplayName("update 非法 resetCycle → 400 + hintTarget")
    void updateBadResetCycle() {
        EncodingRule r = makeRule(FACTORY_ID, "MATERIAL_BATCH", "id-1");
        when(repository.findById("id-1")).thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("resetCycle", "HOURLY");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-1", body));
        assertEquals(400, ex.getCode());
        assertEquals("resetCycle", ex.getHintTarget());
    }

    @Test
    @DisplayName("update 非法 sequenceLength → 400")
    void updateBadSequenceLength() {
        EncodingRule r = makeRule(FACTORY_ID, "MATERIAL_BATCH", "id-1");
        when(repository.findById("id-1")).thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("sequenceLength", 99);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-1", body));
        assertEquals(400, ex.getCode());
        assertEquals("sequenceLength", ex.getHintTarget());
    }

    @Test
    @DisplayName("delete → soft delete")
    void deleteSoft() {
        EncodingRule r = makeRule(FACTORY_ID, "MATERIAL_BATCH", "id-1");
        when(repository.findById("id-1")).thenReturn(Optional.of(r));
        when(repository.save(any(EncodingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, "id-1");

        assertTrue(resp.getSuccess());
        assertTrue(r.isDeleted());
        verify(repository).save(r);
    }
}
