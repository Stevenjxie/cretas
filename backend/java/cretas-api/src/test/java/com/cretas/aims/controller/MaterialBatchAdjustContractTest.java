package com.cretas.aims.controller;

import com.cretas.aims.dto.material.AdjustMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * POST /material-batches/{id}/adjust 的入参契约。
 *
 * 背景(2026-08-13 生产实测): DTO 公布的是【增减量】契约
 * ({@code adjustmentType: INCREASE/DECREASE} + {@code quantity: 调整数量}),
 * 而实现是【绝对值】语义。照 Swagger 发 {@code DECREASE 50} 到一个剩 53 的批次,
 * 实际被执行成「设为 50」—— 方向相反; 若批次只剩 3, 同一个请求会把库存变成 50,
 * **凭空造出 47**。这些用例把「绝对值」钉死, 并要求 body 里出现增减量字段时显式报错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("批次调整入参契约 (绝对值语义)")
class MaterialBatchAdjustContractTest {

    private static final String AUTH = "Bearer t";

    @Mock MaterialBatchService materialBatchService;
    @Mock MobileService mobileService;
    @Mock PriceMaskResolver priceMaskResolver;
    @Mock OpeningInventoryService openingInventoryService;

    private MaterialBatchController controller;

    @BeforeEach
    void setUp() {
        controller = new MaterialBatchController(
                materialBatchService, mobileService, priceMaskResolver, openingInventoryService);
    }

    private AdjustMaterialBatchRequest body(String type, BigDecimal qty, String reason, String notes) {
        AdjustMaterialBatchRequest r = new AdjustMaterialBatchRequest();
        r.setAdjustmentType(type);
        r.setQuantity(qty);
        r.setReason(reason);
        r.setNotes(notes);
        return r;
    }

    @Test
    @DisplayName("body 带 adjustmentType → 400, 且一次写入都没发生")
    void deltaContractIsRejectedBeforeAnyWrite() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.adjustBatchQuantity(
                "F006", "B-1", AUTH, null, null,
                body("DECREASE", new BigDecimal("50"), "盘点调整", null)));

        assertEquals(400, e.getCode());
        assertEquals("adjustmentType", e.getHintTarget());
        // 被拒的请求不许碰 service, 也不许去解析 token —— guard 排在两者之前。
        verifyNoInteractions(materialBatchService, mobileService);
    }

    @Test
    @DisplayName("INCREASE 同样拒绝 —— 不是只挡 DECREASE 这一个字面量")
    void increaseIsRejectedToo() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.adjustBatchQuantity(
                "F006", "B-1", AUTH, null, null,
                body("INCREASE", new BigDecimal("50"), "盘点调整", null)));

        assertEquals(400, e.getCode());
        verifyNoInteractions(materialBatchService);
    }

    @Test
    @DisplayName("不带 adjustmentType 时, quantity 原样按绝对值透传给 service")
    void quantityIsPassedThroughAsAbsoluteValue() {
        when(mobileService.getUserFromToken(anyString())).thenReturn(user(7L));
        when(materialBatchService.adjustBatchQuantity(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.adjustBatchQuantity("F006", "B-1", AUTH, null, null,
                body(null, new BigDecimal("3"), "盘点调整", null));

        // 传 3 就是「调整后剩 3」, 不得被换算成任何增减量。
        verify(materialBatchService).adjustBatchQuantity(
                eq("F006"), eq("B-1"), eq(new BigDecimal("3")), anyString(), eq(7L));
    }

    @Test
    @DisplayName("notes 拼进 reason 一并落库, 不再被丢掉")
    void notesAreFoldedIntoReason() {
        when(mobileService.getUserFromToken(anyString())).thenReturn(user(7L));
        when(materialBatchService.adjustBatchQuantity(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.adjustBatchQuantity("F006", "B-1", AUTH, null, null,
                body(null, new BigDecimal("3"), "盘点调整", "误录 55kg 应为 5kg"));

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(materialBatchService).adjustBatchQuantity(
                anyString(), anyString(), any(), reasonCaptor.capture(), any());
        assertTrue(reasonCaptor.getValue().contains("盘点调整"), "原因必须保留");
        assertTrue(reasonCaptor.getValue().contains("误录 55kg 应为 5kg"), "备注必须一并落库");
    }

    @Test
    @DisplayName("缺 reason → 400 而不是等落库炸成 500")
    void missingReasonIsRejectedAtTheEntrance() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.adjustBatchQuantity(
                "F006", "B-1", AUTH, new BigDecimal("3"), null, null));

        assertEquals(400, e.getCode());
        assertEquals("reason", e.getHintTarget());
        verifyNoInteractions(materialBatchService, mobileService);
    }

    @Test
    @DisplayName("reason+notes 超过 255 字 → 400, 不静默截断")
    void overlongReasonIsRejectedNotTruncated() {
        BusinessException e = assertThrows(BusinessException.class, () -> controller.adjustBatchQuantity(
                "F006", "B-1", AUTH, new BigDecimal("3"), "x".repeat(256), null));

        assertEquals(400, e.getCode());
        assertEquals("reason", e.getHintTarget());
        verifyNoInteractions(materialBatchService);
    }

    @Test
    @DisplayName("URL 参数那条路仍然走得通(web-admin 与运维脚本都用它)")
    void urlParameterPathStillWorks() {
        when(mobileService.getUserFromToken(anyString())).thenReturn(user(7L));
        when(materialBatchService.adjustBatchQuantity(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.adjustBatchQuantity("F006", "B-1", AUTH, new BigDecimal("3"), "盘点调整", null);

        verify(materialBatchService).adjustBatchQuantity(
                eq("F006"), eq("B-1"), eq(new BigDecimal("3")), eq("盘点调整"), eq(7L));
    }

    private com.cretas.aims.dto.user.UserDTO user(Long id) {
        com.cretas.aims.dto.user.UserDTO u = new com.cretas.aims.dto.user.UserDTO();
        u.setId(id);
        return u;
    }
}
