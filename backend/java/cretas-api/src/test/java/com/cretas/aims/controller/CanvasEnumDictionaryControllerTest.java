package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.canvas.EnumDictionary;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.EnumDictionaryRepository;
import com.cretas.aims.service.canvas.EnumDictionaryResolverService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CanvasEnumDictionaryController 单元测试 — Canvas-Phase C.
 *
 * Coverage (12 tests):
 * <ol>
 *   <li>list (no category) → repository.findByFactoryIdOrderBy...</li>
 *   <li>list (with category) → repository.findByFactoryIdAndCategory...</li>
 *   <li>categories → distinct list</li>
 *   <li>resolve → resolver.getEnumValues</li>
 *   <li>resolve 缺 category → 400 hintTarget</li>
 *   <li>create → 409 on duplicate (factoryId, category, code)</li>
 *   <li>create → success path, invalidate cache</li>
 *   <li>create → 400 missing required field (code) with hintTarget</li>
 *   <li>update → 403 cross-factory blocked</li>
 *   <li>update → 409 stale version (AUD-4 P1 乐观锁)</li>
 *   <li>update → PATCH semantics: 缺失字段不被修改</li>
 *   <li>delete → softDelete + invalidate cache</li>
 *   <li>update → label 长度超限 400 hintTarget=label</li>
 * </ol>
 */
@DisplayName("CanvasEnumDictionaryController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasEnumDictionaryControllerTest {

    @Mock
    private EnumDictionaryRepository repository;

    @Mock
    private EnumDictionaryResolverService resolver;

    @InjectMocks
    private CanvasEnumDictionaryController controller;

    private static final String FACTORY_ID = "F001";

    private static EnumDictionary makeRow(
            String factoryId, String category, String code, String label) {
        EnumDictionary e = new EnumDictionary();
        e.setId(UUID.randomUUID());
        e.setFactoryId(factoryId);
        e.setCategory(category);
        e.setCode(code);
        e.setLabel(label);
        e.setDisplayOrder(10);
        e.setEnabled(true);
        e.setLocale(EnumDictionary.DEFAULT_LOCALE);
        e.setVersion(0L);
        return e;
    }

    @Test
    @DisplayName("list 无 category → 工厂全部枚举值")
    void listNoCategory() {
        List<EnumDictionary> rows = List.of(
                makeRow(FACTORY_ID, "CANCEL_REASON", "CUSTOMER_CANCEL", "客户撤单"),
                makeRow(FACTORY_ID, "RETURN_REASON", "QUALITY_ISSUE", "质量问题"));
        when(repository.findByFactoryIdOrderByCategoryAscDisplayOrderAscCodeAsc(FACTORY_ID))
                .thenReturn(rows);

        ApiResponse<List<EnumDictionary>> resp = controller.list(FACTORY_ID, null);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        verify(repository).findByFactoryIdOrderByCategoryAscDisplayOrderAscCodeAsc(FACTORY_ID);
    }

    @Test
    @DisplayName("list 带 category → 按 category 过滤 (auto-upper)")
    void listWithCategory() {
        when(repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                FACTORY_ID, "CANCEL_REASON"))
                .thenReturn(List.of(
                        makeRow(FACTORY_ID, "CANCEL_REASON", "CUSTOMER_CANCEL", "客户撤单")));

        // lowercase input — controller should upper-case it
        ApiResponse<List<EnumDictionary>> resp = controller.list(FACTORY_ID, "cancel_reason");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        verify(repository).findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                FACTORY_ID, "CANCEL_REASON");
    }

    @Test
    @DisplayName("categories → distinct category list")
    void categories() {
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(
                makeRow(FACTORY_ID, "CANCEL_REASON", "A", "a"),
                makeRow(FACTORY_ID, "CANCEL_REASON", "B", "b"),
                makeRow(FACTORY_ID, "RETURN_REASON", "C", "c")));

        ApiResponse<List<String>> resp = controller.categories(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        assertTrue(resp.getData().contains("CANCEL_REASON"));
        assertTrue(resp.getData().contains("RETURN_REASON"));
    }

    @Test
    @DisplayName("resolve → resolver.getEnumValues")
    void resolveDelegates() {
        when(resolver.getEnumValues(FACTORY_ID, "CANCEL_REASON")).thenReturn(List.of(
                makeRow(FACTORY_ID, "CANCEL_REASON", "CUSTOMER_CANCEL", "客户撤单")));

        ApiResponse<List<EnumDictionary>> resp = controller.resolve(FACTORY_ID, "CANCEL_REASON");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        verify(resolver).getEnumValues(FACTORY_ID, "CANCEL_REASON");
    }

    @Test
    @DisplayName("resolve 缺 category 参数 → 400 hintTarget=category")
    void resolveMissingCategory() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.resolve(FACTORY_ID, " "));
        assertEquals(400, ex.getCode());
        assertEquals("category", ex.getHintTarget());
    }

    @Test
    @DisplayName("create 重复 (factoryId, category, code) → 409 + actionHint")
    void createDuplicate() {
        when(repository.findByFactoryIdAndCategoryAndCode(
                FACTORY_ID, "CANCEL_REASON", "CUSTOMER_CANCEL"))
                .thenReturn(Optional.of(makeRow(
                        FACTORY_ID, "CANCEL_REASON", "CUSTOMER_CANCEL", "客户撤单")));

        Map<String, Object> body = new HashMap<>();
        body.put("category", "CANCEL_REASON");
        body.put("code", "CUSTOMER_CANCEL");
        body.put("label", "客户撤单");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
        assertEquals("code", ex.getHintTarget());
    }

    @Test
    @DisplayName("create 成功 → save + invalidate cache, auto upper-case category+code")
    void createSuccess() {
        when(repository.findByFactoryIdAndCategoryAndCode(
                FACTORY_ID, "CANCEL_REASON", "NEW_CODE"))
                .thenReturn(Optional.empty());
        when(repository.save(any(EnumDictionary.class)))
                .thenAnswer(inv -> {
                    EnumDictionary t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return t;
                });

        Map<String, Object> body = new HashMap<>();
        // lowercase input — controller should upper-case
        body.put("category", "cancel_reason");
        body.put("code", "new_code");
        body.put("label", "新值");
        body.put("displayOrder", 50);
        body.put("description", "说明");

        ApiResponse<EnumDictionary> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals("CANCEL_REASON", resp.getData().getCategory());
        assertEquals("NEW_CODE", resp.getData().getCode());
        assertEquals("新值", resp.getData().getLabel());
        assertEquals(50, resp.getData().getDisplayOrder());
        assertEquals(EnumDictionary.DEFAULT_LOCALE, resp.getData().getLocale());
        verify(resolver).invalidate(FACTORY_ID, "CANCEL_REASON");
    }

    @Test
    @DisplayName("create 缺 code → 400 with hintTarget=code")
    void createMissingCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("category", "CANCEL_REASON");
        body.put("label", "客户撤单");
        // code 缺失

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("code", ex.getHintTarget());
    }

    @Test
    @DisplayName("update 跨工厂 → 403")
    void updateCrossFactoryBlocked() {
        UUID id = UUID.randomUUID();
        EnumDictionary owned = makeRow("F002", "CANCEL_REASON", "X", "x");
        owned.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(owned));

        Map<String, Object> body = new HashMap<>();
        body.put("label", "新标签");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, id, body));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("update 版本不一致 → 409 (AUD-4 P1 乐观锁)")
    void updateStaleVersion() {
        UUID id = UUID.randomUUID();
        EnumDictionary existing = makeRow(FACTORY_ID, "CANCEL_REASON", "X", "x");
        existing.setId(id);
        existing.setVersion(5L);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 3); // 客户端 stale
        body.put("label", "新标签");

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
        EnumDictionary existing = makeRow(FACTORY_ID, "CANCEL_REASON", "X", "原label");
        existing.setId(id);
        existing.setDescription("原描述");
        existing.setDisplayOrder(10);
        existing.setParentCode("PARENT");
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(EnumDictionary.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("label", "新label"); // 只改 label
        // displayOrder / description / parentCode 不在 body

        ApiResponse<EnumDictionary> resp = controller.update(FACTORY_ID, id, body);

        assertEquals("新label", resp.getData().getLabel());
        assertEquals("原描述", resp.getData().getDescription()); // 没变
        assertEquals(10, resp.getData().getDisplayOrder());
        assertEquals("PARENT", resp.getData().getParentCode());
        verify(resolver).invalidate(FACTORY_ID, "CANCEL_REASON");
    }

    @Test
    @DisplayName("update label 超 200 字符 → 400 hintTarget=label")
    void updateLabelTooLong() {
        UUID id = UUID.randomUUID();
        EnumDictionary existing = makeRow(FACTORY_ID, "CANCEL_REASON", "X", "x");
        existing.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("label", "a".repeat(201)); // 201 chars

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, id, body));
        assertEquals(400, ex.getCode());
        assertEquals("label", ex.getHintTarget());
    }

    @Test
    @DisplayName("delete → softDelete + invalidate cache")
    void deleteSoft() {
        UUID id = UUID.randomUUID();
        EnumDictionary existing = makeRow(FACTORY_ID, "CANCEL_REASON", "X", "x");
        existing.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        ArgumentCaptor<EnumDictionary> captor = ArgumentCaptor.forClass(EnumDictionary.class);

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, id);

        assertTrue(resp.getSuccess());
        verify(repository).save(captor.capture());
        assertNotNull(captor.getValue().getDeletedAt());
        verify(resolver).invalidate(FACTORY_ID, "CANCEL_REASON");
    }

    @Test
    @DisplayName("delete 不存在 → 404")
    void deleteNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(FACTORY_ID, id));
        assertEquals(404, ex.getCode());
        verify(resolver, never()).invalidate(eq(FACTORY_ID), any());
    }
}
