package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasThresholdsController 单元测试 — Canvas-Thresholds Phase A.
 *
 * Coverage:
 * <ol>
 *   <li>list (no category) → repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc</li>
 *   <li>list (with category) → repository.findByFactoryIdAndCategoryOrderByThresholdKey</li>
 *   <li>getByKey → 404 when not found</li>
 *   <li>create → 409 on duplicate key (idempotency)</li>
 *   <li>create → success path, invalidate cache</li>
 *   <li>create → 400 on bad valueType / category</li>
 *   <li>create → 400 on value not parseable as declared type</li>
 *   <li>update → 403 cross-factory access blocked</li>
 *   <li>update → 409 stale version (AUD-4 P1 optimistic lock)</li>
 *   <li>update → PATCH semantics: missing fields not touched</li>
 *   <li>delete → soft delete + invalidate cache</li>
 *   <li>reset-to-default → 仅修改 threshold != default 的行</li>
 *   <li>invalid category param → 400 with hintTarget</li>
 * </ol>
 */
@DisplayName("CanvasThresholdsController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasThresholdsControllerTest {

    @Mock
    private FactoryThresholdRepository repository;

    @Mock
    private ThresholdResolverService resolver;

    @InjectMocks
    private CanvasThresholdsController controller;

    private static final String FACTORY_ID = "F001";

    private static FactoryThreshold makeRow(String factoryId, String key, String value, String defaultValue) {
        FactoryThreshold t = new FactoryThreshold();
        t.setId(UUID.randomUUID());
        t.setFactoryId(factoryId);
        t.setThresholdKey(key);
        t.setCategory(ThresholdCategory.INVENTORY);
        t.setValueType(ThresholdValueType.DECIMAL);
        t.setThresholdValue(value);
        t.setDefaultValue(defaultValue);
        t.setEnabled(true);
        t.setVersion(0L);
        return t;
    }

    @Test
    @DisplayName("list 无 category → 工厂全部阈值")
    void listNoCategory() {
        List<FactoryThreshold> rows = List.of(
                makeRow(FACTORY_ID, "k1", "1", "1"),
                makeRow(FACTORY_ID, "k2", "2", "2"));
        when(repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(FACTORY_ID)).thenReturn(rows);

        ApiResponse<List<FactoryThreshold>> resp = controller.list(FACTORY_ID, null);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        verify(repository).findByFactoryIdOrderByCategoryAscThresholdKeyAsc(FACTORY_ID);
    }

    @Test
    @DisplayName("list 带 category → 按 category 过滤")
    void listWithCategory() {
        when(repository.findByFactoryIdAndCategoryOrderByThresholdKey(FACTORY_ID, ThresholdCategory.IOT))
                .thenReturn(List.of(makeRow(FACTORY_ID, "iot.cold_chain.temp_max", "-20", "-18")));

        ApiResponse<List<FactoryThreshold>> resp = controller.list(FACTORY_ID, "IOT");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
    }

    @Test
    @DisplayName("getByKey 不存在 → 抛 404")
    void getByKeyNotFound() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY_ID, "nope"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getByKey(FACTORY_ID, "nope"));
        assertEquals(404, ex.getCode());
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("create 重复 key → 409 + actionHint")
    void createDuplicateKey() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY_ID, "k1"))
                .thenReturn(Optional.of(makeRow(FACTORY_ID, "k1", "1", "1")));

        Map<String, Object> body = new HashMap<>();
        body.put("thresholdKey", "k1");
        body.put("category", "INVENTORY");
        body.put("valueType", "DECIMAL");
        body.put("thresholdValue", "2");
        body.put("defaultValue", "1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("create 成功 → save + invalidate cache")
    void createSuccess() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY_ID, "k1"))
                .thenReturn(Optional.empty());
        when(repository.save(any(FactoryThreshold.class)))
                .thenAnswer(inv -> {
                    FactoryThreshold t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return t;
                });

        Map<String, Object> body = new HashMap<>();
        body.put("thresholdKey", "k1");
        body.put("category", "INVENTORY");
        body.put("valueType", "DECIMAL");
        body.put("thresholdValue", "9");
        body.put("defaultValue", "6");
        body.put("description", "test");
        body.put("unit", "次/年");

        ApiResponse<FactoryThreshold> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals("9", resp.getData().getThresholdValue());
        verify(resolver).invalidate(FACTORY_ID, "k1");
    }

    @Test
    @DisplayName("create valueType 非法 → 400")
    void createBadValueType() {
        Map<String, Object> body = new HashMap<>();
        body.put("thresholdKey", "k1");
        body.put("category", "INVENTORY");
        body.put("valueType", "FLOATY");
        body.put("thresholdValue", "9");
        body.put("defaultValue", "6");

        // dup check 之后才到 parse, 所以先 mock 一下
        when(repository.findByFactoryIdAndThresholdKey(FACTORY_ID, "k1"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("valueType", ex.getHintTarget());
    }

    @Test
    @DisplayName("create thresholdValue 不可解析 → 400")
    void createUnparseableValue() {
        when(repository.findByFactoryIdAndThresholdKey(FACTORY_ID, "k1"))
                .thenReturn(Optional.empty());

        Map<String, Object> body = new HashMap<>();
        body.put("thresholdKey", "k1");
        body.put("category", "INVENTORY");
        body.put("valueType", "INTEGER");
        body.put("thresholdValue", "abc");
        body.put("defaultValue", "10");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("abc"));
    }

    @Test
    @DisplayName("update 跨工厂 → 403")
    void updateCrossFactoryBlocked() {
        UUID id = UUID.randomUUID();
        FactoryThreshold owned = makeRow("F002", "k1", "9", "6");
        owned.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(owned));

        Map<String, Object> body = new HashMap<>();
        body.put("thresholdValue", "10");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, id, body));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("update 版本不一致 → 409 (AUD-4 P1 乐观锁)")
    void updateStaleVersion() {
        UUID id = UUID.randomUUID();
        FactoryThreshold existing = makeRow(FACTORY_ID, "k1", "9", "6");
        existing.setId(id);
        existing.setVersion(5L);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 3); // 客户端 stale
        body.put("thresholdValue", "10");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, id, body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=5"));
        assertTrue(ex.getMessage().contains("v=3"));
    }

    @Test
    @DisplayName("update PATCH 语义 — 缺失字段不被修改")
    void updatePatchSemantics() {
        UUID id = UUID.randomUUID();
        FactoryThreshold existing = makeRow(FACTORY_ID, "k1", "9", "6");
        existing.setId(id);
        existing.setDescription("原描述");
        existing.setUnit("原单位");
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(FactoryThreshold.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("thresholdValue", "10"); // 只改 value
        // description / unit 不在 body 中

        ApiResponse<FactoryThreshold> resp = controller.update(FACTORY_ID, id, body);

        assertEquals("10", resp.getData().getThresholdValue());
        assertEquals("原描述", resp.getData().getDescription()); // 没变
        assertEquals("原单位", resp.getData().getUnit());
        verify(resolver).invalidate(FACTORY_ID, "k1");
    }

    @Test
    @DisplayName("delete → softDelete + invalidate cache")
    void deleteSoft() {
        UUID id = UUID.randomUUID();
        FactoryThreshold existing = makeRow(FACTORY_ID, "k1", "9", "6");
        existing.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        ArgumentCaptor<FactoryThreshold> captor = ArgumentCaptor.forClass(FactoryThreshold.class);

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, id);

        assertTrue(resp.getSuccess());
        verify(repository).save(captor.capture());
        assertNotNull(captor.getValue().getDeletedAt());
        verify(resolver).invalidate(FACTORY_ID, "k1");
    }

    @Test
    @DisplayName("reset-to-default → 仅修改与 default 不同的行")
    void resetToDefaultSkipsAlreadyDefault() {
        FactoryThreshold modified = makeRow(FACTORY_ID, "k1", "9", "6"); // diff from default
        FactoryThreshold unchanged = makeRow(FACTORY_ID, "k2", "10", "10"); // same as default
        when(repository.findByFactoryIdAndCategoryOrderByThresholdKey(FACTORY_ID, ThresholdCategory.INVENTORY))
                .thenReturn(List.of(modified, unchanged));

        ApiResponse<Integer> resp = controller.resetCategoryToDefault(FACTORY_ID, "INVENTORY");

        assertEquals(1, resp.getData());
        verify(repository, times(1)).save(any(FactoryThreshold.class));
        assertEquals("6", modified.getThresholdValue()); // reset
        assertEquals("10", unchanged.getThresholdValue()); // unchanged
        verify(resolver).invalidate(FACTORY_ID, "k1");
        verify(resolver, never()).invalidate(FACTORY_ID, "k2");
    }

    @Test
    @DisplayName("非法 category 参数 → 400")
    void invalidCategoryParam() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.list(FACTORY_ID, "NOT_REAL"));
        assertEquals(400, ex.getCode());
        assertEquals("category", ex.getHintTarget());
    }
}
