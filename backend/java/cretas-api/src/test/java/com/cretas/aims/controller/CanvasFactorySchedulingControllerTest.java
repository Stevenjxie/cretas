package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasFactorySchedulingController 单元测试 — Canvas P3 batch 2.
 *
 * Coverage:
 * <ol>
 *   <li>list → 返工厂配置 (空 / 单条)</li>
 *   <li>get → 404 不存在</li>
 *   <li>get → 403 跨工厂</li>
 *   <li>create → 409 重复 (per-factory 唯一)</li>
 *   <li>create → 成功路径</li>
 *   <li>create → 400 权重越界 (linucbWeight > 1.0)</li>
 *   <li>update → 403 跨工厂</li>
 *   <li>update → 409 stale version (AUD-4 P1)</li>
 *   <li>update → PATCH 语义: 缺失字段不变</li>
 *   <li>delete → 软删除 setDeletedAt</li>
 * </ol>
 */
@DisplayName("CanvasFactorySchedulingController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasFactorySchedulingControllerTest {

    @Mock
    private FactorySchedulingConfigRepository repository;

    @InjectMocks
    private CanvasFactorySchedulingController controller;

    private static final String FACTORY_ID = "F001";

    private static FactorySchedulingConfig makeConfig(String factoryId, Long id) {
        FactorySchedulingConfig c = FactorySchedulingConfig.createDefault(factoryId);
        c.setId(id);
        c.setVersion(0L);
        return c;
    }

    @Test
    @DisplayName("list 工厂有配置 → 返单条")
    void listOne() {
        FactorySchedulingConfig c = makeConfig(FACTORY_ID, 1L);
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(Optional.of(c));

        ApiResponse<List<FactorySchedulingConfig>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals(1L, resp.getData().get(0).getId());
    }

    @Test
    @DisplayName("list 工厂无配置 → 返空 list")
    void listEmpty() {
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(Optional.empty());

        ApiResponse<List<FactorySchedulingConfig>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(0, resp.getData().size());
    }

    @Test
    @DisplayName("get 不存在 → 抛 404")
    void getNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.get(FACTORY_ID, 99L));
        assertEquals(404, ex.getCode());
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("get 跨工厂 → 抛 403")
    void getCrossFactoryBlocked() {
        FactorySchedulingConfig owned = makeConfig("F002", 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(owned));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.get(FACTORY_ID, 5L));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("create 重复 → 409 + actionHint")
    void createDuplicate() {
        FactorySchedulingConfig existing = makeConfig(FACTORY_ID, 1L);
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("create 成功 → save + 返回配置")
    void createSuccess() {
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(Optional.empty());
        when(repository.save(any(FactorySchedulingConfig.class)))
                .thenAnswer(inv -> {
                    FactorySchedulingConfig c = inv.getArgument(0);
                    c.setId(42L);
                    return c;
                });

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.7);
        body.put("fairnessWeight", 0.2);
        body.put("enabled", true);

        ApiResponse<FactorySchedulingConfig> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals(42L, resp.getData().getId());
        assertEquals(0.7, resp.getData().getLinucbWeight());
        assertEquals(0.2, resp.getData().getFairnessWeight());
        verify(repository).save(any(FactorySchedulingConfig.class));
    }

    @Test
    @DisplayName("create 权重越界 → 抛 400 (linucbWeight > 1.0)")
    void createWeightOutOfRange() {
        when(repository.findByFactoryId(FACTORY_ID)).thenReturn(Optional.empty());

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 1.5); // 越界

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("linucbWeight", ex.getHintTarget());
    }

    @Test
    @DisplayName("update 跨工厂 → 抛 403")
    void updateCrossFactoryBlocked() {
        FactorySchedulingConfig owned = makeConfig("F002", 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(owned));

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.6);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, 5L, body));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("update 版本不一致 → 409 (AUD-4 P1 乐观锁)")
    void updateStaleVersion() {
        FactorySchedulingConfig existing = makeConfig(FACTORY_ID, 1L);
        existing.setVersion(5L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 3); // 客户端 stale
        body.put("linucbWeight", 0.6);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, 1L, body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=5"));
        assertTrue(ex.getMessage().contains("v=3"));
    }

    @Test
    @DisplayName("update PATCH 语义 — 缺失字段不修改")
    void updatePatchSemantics() {
        FactorySchedulingConfig existing = makeConfig(FACTORY_ID, 1L);
        existing.setLinucbWeight(0.5);
        existing.setFairnessWeight(0.2);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(FactorySchedulingConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.8); // 只改 linucbWeight
        // fairnessWeight 不在 body 中

        ApiResponse<FactorySchedulingConfig> resp = controller.update(FACTORY_ID, 1L, body);

        assertEquals(0.8, resp.getData().getLinucbWeight());
        assertEquals(0.2, resp.getData().getFairnessWeight()); // 没变
    }

    @Test
    @DisplayName("delete → 软删除 setDeletedAt")
    void deleteSoft() {
        FactorySchedulingConfig existing = makeConfig(FACTORY_ID, 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        ArgumentCaptor<FactorySchedulingConfig> captor =
                ArgumentCaptor.forClass(FactorySchedulingConfig.class);

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, 1L);

        assertTrue(resp.getSuccess());
        verify(repository).save(captor.capture());
        assertNotNull(captor.getValue().getDeletedAt());
    }
}
