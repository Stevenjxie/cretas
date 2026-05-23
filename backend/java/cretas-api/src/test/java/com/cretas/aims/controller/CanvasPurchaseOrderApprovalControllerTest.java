package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.inventory.PurchaseOrderApprovalRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderApprovalRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasPurchaseOrderApprovalController 单元测试 — Canvas P3 batch 2.
 *
 * Coverage:
 * <ol>
 *   <li>list → 仅返本工厂规则</li>
 *   <li>get → 404 不存在</li>
 *   <li>get → 403 跨工厂</li>
 *   <li>create → 409 重复 ruleName</li>
 *   <li>create → 成功路径</li>
 *   <li>create → 400 priceVarianceThreshold 越界 (>100)</li>
 *   <li>create → 400 amountThreshold 负数</li>
 *   <li>create → 400 ruleName 过长</li>
 *   <li>update → 403 跨工厂</li>
 *   <li>update → 409 stale version (AUD-4 P1)</li>
 *   <li>update → PATCH 语义</li>
 *   <li>delete → softDelete 走 @SQLDelete</li>
 * </ol>
 */
@DisplayName("CanvasPurchaseOrderApprovalController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasPurchaseOrderApprovalControllerTest {

    @Mock
    private PurchaseOrderApprovalRuleRepository repository;

    @InjectMocks
    private CanvasPurchaseOrderApprovalController controller;

    private static final String FACTORY_ID = "F001";

    private static PurchaseOrderApprovalRule makeRule(String factoryId, String ruleName) {
        PurchaseOrderApprovalRule r = new PurchaseOrderApprovalRule();
        r.setId(UUID.randomUUID().toString());
        r.setFactoryId(factoryId);
        r.setRuleName(ruleName);
        r.setPriceVarianceThreshold(new BigDecimal("10.00"));
        r.setAmountThreshold(new BigDecimal("100000.00"));
        r.setEnabled(true);
        r.setVersion(0L);
        return r;
    }

    @Test
    @DisplayName("list → 仅返本工厂规则")
    void listFilterByFactory() {
        PurchaseOrderApprovalRule r1 = makeRule(FACTORY_ID, "默认审核规则");
        PurchaseOrderApprovalRule r2 = makeRule("F002", "其他工厂规则");
        when(repository.findAll()).thenReturn(List.of(r1, r2));

        ApiResponse<List<PurchaseOrderApprovalRule>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals(FACTORY_ID, resp.getData().get(0).getFactoryId());
    }

    @Test
    @DisplayName("get 不存在 → 抛 404")
    void getNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.get(FACTORY_ID, "nope"));
        assertEquals(404, ex.getCode());
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("get 跨工厂 → 抛 403")
    void getCrossFactoryBlocked() {
        PurchaseOrderApprovalRule owned = makeRule("F002", "其他规则");
        when(repository.findById(owned.getId())).thenReturn(Optional.of(owned));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.get(FACTORY_ID, owned.getId()));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("create 重复 ruleName → 409 + actionHint")
    void createDuplicateName() {
        PurchaseOrderApprovalRule existing = makeRule(FACTORY_ID, "重复规则");
        when(repository.findByFactoryIdAndRuleName(FACTORY_ID, "重复规则"))
                .thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "重复规则");
        body.put("priceVarianceThreshold", "15.00");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(409, ex.getCode());
        assertEquals("ruleName", ex.getHintTarget());
    }

    @Test
    @DisplayName("create 成功 → save + 返回规则")
    void createSuccess() {
        when(repository.findByFactoryIdAndRuleName(FACTORY_ID, "高价单审核"))
                .thenReturn(Optional.empty());
        when(repository.save(any(PurchaseOrderApprovalRule.class)))
                .thenAnswer(inv -> {
                    PurchaseOrderApprovalRule r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID().toString());
                    return r;
                });

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "高价单审核");
        body.put("priceVarianceThreshold", "8.50");
        body.put("amountThreshold", "200000");

        ApiResponse<PurchaseOrderApprovalRule> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals("高价单审核", resp.getData().getRuleName());
        assertEquals(new BigDecimal("8.50"), resp.getData().getPriceVarianceThreshold());
        assertEquals(new BigDecimal("200000"), resp.getData().getAmountThreshold());
        assertEquals(FACTORY_ID, resp.getData().getFactoryId());
    }

    @Test
    @DisplayName("create priceVarianceThreshold > 100 → 抛 400")
    void createBadThreshold() {
        when(repository.findByFactoryIdAndRuleName(FACTORY_ID, "测试"))
                .thenReturn(Optional.empty());

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "测试");
        body.put("priceVarianceThreshold", "120"); // 越界

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("priceVarianceThreshold", ex.getHintTarget());
    }

    @Test
    @DisplayName("create amountThreshold 负数 → 抛 400")
    void createNegativeAmount() {
        when(repository.findByFactoryIdAndRuleName(FACTORY_ID, "测试"))
                .thenReturn(Optional.empty());

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", "测试");
        body.put("amountThreshold", "-100");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("amountThreshold", ex.getHintTarget());
    }

    @Test
    @DisplayName("create ruleName 过长 → 抛 400")
    void createNameTooLong() {
        String longName = "x".repeat(101); // > 100

        Map<String, Object> body = new HashMap<>();
        body.put("ruleName", longName);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("ruleName", ex.getHintTarget());
    }

    @Test
    @DisplayName("update 跨工厂 → 抛 403")
    void updateCrossFactoryBlocked() {
        PurchaseOrderApprovalRule owned = makeRule("F002", "其他规则");
        when(repository.findById(owned.getId())).thenReturn(Optional.of(owned));

        Map<String, Object> body = new HashMap<>();
        body.put("priceVarianceThreshold", "12.5");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, owned.getId(), body));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("update 版本不一致 → 409 (AUD-4 P1 乐观锁)")
    void updateStaleVersion() {
        PurchaseOrderApprovalRule existing = makeRule(FACTORY_ID, "规则A");
        existing.setVersion(7L);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 5); // 客户端 stale
        body.put("priceVarianceThreshold", "12.0");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, existing.getId(), body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=7"));
        assertTrue(ex.getMessage().contains("v=5"));
    }

    @Test
    @DisplayName("update PATCH 语义 — 仅修改 body 中字段")
    void updatePatchSemantics() {
        PurchaseOrderApprovalRule existing = makeRule(FACTORY_ID, "规则A");
        existing.setAmountThreshold(new BigDecimal("100000.00"));
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(PurchaseOrderApprovalRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("priceVarianceThreshold", "15.50"); // 只改这个
        // amountThreshold / enabled / ruleName 不在 body 中

        ApiResponse<PurchaseOrderApprovalRule> resp =
                controller.update(FACTORY_ID, existing.getId(), body);

        assertEquals(new BigDecimal("15.50"), resp.getData().getPriceVarianceThreshold());
        assertEquals(new BigDecimal("100000.00"), resp.getData().getAmountThreshold()); // 没变
        assertEquals("规则A", resp.getData().getRuleName()); // 没变
        assertTrue(resp.getData().getEnabled()); // 没变
    }

    @Test
    @DisplayName("delete → @SQLDelete 触发 (走 repository.delete)")
    void deleteCallsRepositoryDelete() {
        PurchaseOrderApprovalRule existing = makeRule(FACTORY_ID, "规则A");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, existing.getId());

        assertTrue(resp.getSuccess());
        verify(repository).delete(existing);
    }
}
