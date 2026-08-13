package com.cretas.aims.controller;

import com.cretas.aims.dto.material.ConsumeMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UseMaterialBatchRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /use 与 /consume 的入参落地契约。
 *
 * 两件被修的事:
 *
 * ① <b>/consume 的计划ID 参数名走偏</b>。/use /reserve /release 三个兄弟端点收的都是
 *    {@code productionPlanId}, 只有 /consume 收 {@code processId}; 而 RN 客户端
 *    (materialBatchApiClient) 对四个端点统一发 {@code productionPlanId}。于是
 *    /consume 那个参数恒绑不上 → 落库 MaterialConsumption.productionPlanId 永远是
 *    null, 原料消耗与生产计划的追溯关联被静默丢弃。
 *
 * ② <b>purpose / notes 收下就丢</b>。两个 DTO 都公布着这两个字段, 但 controller 从不
 *    透传、实现也从不 set —— 生产库 39 条消耗记录 notes 全为空。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("/use 与 /consume 的入参落地契约")
class MaterialBatchConsumeFieldsContractTest {

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
        when(mobileService.getUserFromToken(anyString())).thenReturn(user(7L));
    }

    private com.cretas.aims.dto.user.UserDTO user(Long id) {
        com.cretas.aims.dto.user.UserDTO u = new com.cretas.aims.dto.user.UserDTO();
        u.setId(id);
        return u;
    }

    private String capturedConsumeNotes() {
        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(materialBatchService).consumeBatchMaterial(
                anyString(), anyString(), any(), any(), any(), notes.capture());
        return notes.getValue();
    }

    private String capturedUseNotes() {
        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(materialBatchService).useBatchMaterial(
                anyString(), anyString(), any(), any(), any(), notes.capture());
        return notes.getValue();
    }

    // ---------- ① 计划ID ----------

    @Test
    @DisplayName("/consume 收 URL 参数 productionPlanId —— RN 实际发的就是这个名字")
    void consumeBindsProductionPlanIdFromUrl() {
        controller.consumeBatchMaterial("F006", "B-1", AUTH,
                new BigDecimal("150"), "PLAN-UUID", null, null);

        verify(materialBatchService).consumeBatchMaterial(
                eq("F006"), eq("B-1"), eq(new BigDecimal("150")), eq("PLAN-UUID"), eq(7L), any());
    }

    @Test
    @DisplayName("/consume 的旧名 processId 仍然认 —— 不打断既有调用方")
    void consumeStillAcceptsLegacyProcessIdParam() {
        controller.consumeBatchMaterial("F006", "B-1", AUTH,
                new BigDecimal("150"), null, "LEGACY-ID", null);

        verify(materialBatchService).consumeBatchMaterial(
                anyString(), anyString(), any(), eq("LEGACY-ID"), any(), any());
    }

    @Test
    @DisplayName("两个都给时以 productionPlanId 为准")
    void productionPlanIdWinsOverLegacyAlias() {
        controller.consumeBatchMaterial("F006", "B-1", AUTH,
                new BigDecimal("150"), "PLAN-UUID", "LEGACY-ID", null);

        verify(materialBatchService).consumeBatchMaterial(
                anyString(), anyString(), any(), eq("PLAN-UUID"), any(), any());
    }

    // ---------- ② purpose / notes ----------

    @Test
    @DisplayName("/consume 的 notes 透传下去, 不再被丢掉")
    void consumeForwardsNotes() {
        ConsumeMaterialBatchRequest req = new ConsumeMaterialBatchRequest();
        req.setQuantity(new BigDecimal("150"));
        req.setNotes("产线甲班消耗");

        controller.consumeBatchMaterial("F006", "B-1", AUTH, null, null, null, req);

        assertEquals("产线甲班消耗", capturedConsumeNotes());
    }

    @Test
    @DisplayName("/use 的 purpose 以「用途:」前缀拼进 notes —— MaterialConsumption 没有 purpose 列")
    void useFoldsPurposeIntoNotes() {
        UseMaterialBatchRequest req = new UseMaterialBatchRequest();
        req.setQuantity(new BigDecimal("10"));
        req.setPurpose("试制");
        req.setNotes("第二批");
        when(materialBatchService.useBatchMaterial(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.useBatchMaterial("F006", "B-1", AUTH, null, null, req);

        String notes = capturedUseNotes();
        assertTrue(notes.contains("试制"), "用途必须落库: " + notes);
        assertTrue(notes.contains("第二批"), "备注必须落库: " + notes);
    }

    @Test
    @DisplayName("/use 只给 purpose 时也落库(不是只在两者都有时才拼)")
    void usePersistsPurposeAlone() {
        UseMaterialBatchRequest req = new UseMaterialBatchRequest();
        req.setQuantity(new BigDecimal("10"));
        req.setPurpose("试制");
        when(materialBatchService.useBatchMaterial(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.useBatchMaterial("F006", "B-1", AUTH, null, null, req);

        assertTrue(capturedUseNotes().contains("试制"));
    }

    @Test
    @DisplayName("两者皆空 → 传 null, 不写空串进 notes 列")
    void useSendsNullWhenNothingProvided() {
        UseMaterialBatchRequest req = new UseMaterialBatchRequest();
        req.setQuantity(new BigDecimal("10"));
        when(materialBatchService.useBatchMaterial(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.useBatchMaterial("F006", "B-1", AUTH, null, null, req);

        assertNull(capturedUseNotes());
    }

    @Test
    @DisplayName("空白字符串等同于没填 —— 不把「   」当成用途写进审计")
    void blankPurposeAndNotesAreTreatedAsAbsent() {
        UseMaterialBatchRequest req = new UseMaterialBatchRequest();
        req.setQuantity(new BigDecimal("10"));
        req.setPurpose("   ");
        req.setNotes("");
        when(materialBatchService.useBatchMaterial(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new MaterialBatchDTO());

        controller.useBatchMaterial("F006", "B-1", AUTH, null, null, req);

        assertNull(capturedUseNotes());
    }
}
