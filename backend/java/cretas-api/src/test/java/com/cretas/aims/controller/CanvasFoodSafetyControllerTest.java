package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CanvasFoodSafetyController} — Canvas Phase A Tab 2 Food Safety Hub.
 *
 * <p>Coverage:
 * <ul>
 *   <li>HACCP checkpoint create: required-field validation + duplicate detection (fool-proof Rule 4)</li>
 *   <li>HACCP checkpoint create: min/max bound sanity check</li>
 *   <li>HACCP checkpoint update: AUD-4 stale version → 409</li>
 *   <li>Recall create: status defaults to INVESTIGATING</li>
 *   <li>Recall update: invalid status enum → 400</li>
 *   <li>Recall list: factory-scoped query</li>
 *   <li>Summary endpoint: aggregates from all repos</li>
 * </ul>
 *
 * <p>Pure Mockito — no Spring context. AUD-4 version check + safe-commit pattern verified.
 *
 * @since 2026-05-21 (Canvas Phase A subagent #2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanvasFoodSafetyController — Phase A Tab 2 Food Safety Hub")
class CanvasFoodSafetyControllerTest {

    @Mock HaccpCheckpointRepository haccpCheckpointRepo;
    @Mock HaccpMonitoringRecordRepository haccpMonitoringRepo;
    @Mock AdditiveLimitRepository additiveLimitRepo;
    @Mock RecallEventRepository recallEventRepo;
    @Mock RecallActionRepository recallActionRepo;
    @InjectMocks CanvasFoodSafetyController controller;

    // ==================== HACCP Checkpoint — CREATE ====================

    @Test
    @DisplayName("HACCP create: 缺 checkpointCode → 400 VALIDATION with actionHint")
    void testCreateCheckpointMissingCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "中心温度");

        ApiResponse<Map<String, Object>> resp = controller.createCheckpoint("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("checkpointCode"));
        assertNotNull(resp.getActionHint(), "4位一体: actionHint required");
        assertEquals("warning", resp.getSeverity(), "4位一体: severity=warning");
        verify(haccpCheckpointRepo, never()).save(any());
    }

    @Test
    @DisplayName("HACCP create: criticalLimitMin > Max → 400 VALIDATION (sanity check)")
    void testCreateCheckpointBadLimits() {
        Map<String, Object> body = new HashMap<>();
        body.put("checkpointCode", "CCP-01");
        body.put("name", "中心温度");
        body.put("hazardType", "BIOLOGICAL");
        body.put("criticalLimitMin", new BigDecimal("90"));   // higher than max
        body.put("criticalLimitMax", new BigDecimal("75"));
        body.put("unit", "℃");

        // No findByFactoryIdAndCheckpointCode stub needed: bounds check fires BEFORE
        // the duplicate-code check, so repo lookup is never reached.
        ApiResponse<Map<String, Object>> resp = controller.createCheckpoint("F006", body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("下限"),
                "message should mention bounds order: " + resp.getMessage());
        verify(haccpCheckpointRepo, never()).save(any());
    }

    @Test
    @DisplayName("HACCP create: 重复 checkpointCode → 409 DUPLICATE with actionHint to edit")
    void testCreateCheckpointDuplicate() {
        Map<String, Object> body = new HashMap<>();
        body.put("checkpointCode", "CCP-01");
        body.put("name", "中心温度");
        body.put("hazardType", "BIOLOGICAL");
        body.put("criticalLimitMin", new BigDecimal("75"));
        body.put("criticalLimitMax", new BigDecimal("90"));
        body.put("unit", "℃");

        HaccpCheckpoint existing = HaccpCheckpoint.builder()
                .id(99L)
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .build();
        when(haccpCheckpointRepo.findByFactoryIdAndCheckpointCode("F006", "CCP-01"))
                .thenReturn(Optional.of(existing));

        ApiResponse<Map<String, Object>> resp = controller.createCheckpoint("F006", body);

        assertEquals(409, resp.getCode());
        assertEquals("DUPLICATE", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("CCP-01"),
                "message must include the duplicate code: " + resp.getMessage());
        assertTrue(resp.getActionHint().contains("编辑"),
                "actionHint must offer 'edit' next action (fool-proof Rule 5): " + resp.getActionHint());
        verify(haccpCheckpointRepo, never()).save(any());
    }

    // ==================== HACCP Checkpoint — UPDATE (AUD-4) ====================

    @Test
    @DisplayName("HACCP update: AUD-4 client version=0 stale, server v=5 → 409 CONFLICT with refresh hint")
    void testUpdateCheckpointStaleVersion() {
        HaccpCheckpoint cp = HaccpCheckpoint.builder()
                .id(42L)
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .name("中心温度")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75"))
                .criticalLimitMax(new BigDecimal("90"))
                .unit("℃")
                .active(true)
                .version(5L)
                .build();
        when(haccpCheckpointRepo.findById(42L)).thenReturn(Optional.of(cp));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "更新后名");
        body.put("version", 0);  // stale snapshot

        ApiResponse<Map<String, Object>> resp = controller.updateCheckpoint("F006", 42L, body);

        assertEquals(409, resp.getCode());
        assertEquals("CONFLICT", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("v=5"),
                "message should show server version: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("v=0"),
                "message should show client version: " + resp.getMessage());
        assertTrue(resp.getActionHint().contains("刷新"),
                "actionHint should suggest refresh: " + resp.getActionHint());
        // CRITICAL: save must NOT be called when version is stale
        verify(haccpCheckpointRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("HACCP update: null version (legacy client) → lenient passthrough")
    void testUpdateCheckpointNullVersionLenient() {
        HaccpCheckpoint cp = HaccpCheckpoint.builder()
                .id(42L)
                .factoryId("F006")
                .checkpointCode("CCP-01")
                .name("中心温度")
                .hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75"))
                .criticalLimitMax(new BigDecimal("90"))
                .unit("℃")
                .active(true)
                .version(5L)
                .build();
        when(haccpCheckpointRepo.findById(42L)).thenReturn(Optional.of(cp));
        when(haccpCheckpointRepo.saveAndFlush(any())).thenReturn(cp);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "更新后名");
        // version field absent (legacy client) → lenient

        ApiResponse<Map<String, Object>> resp = controller.updateCheckpoint("F006", 42L, body);

        assertEquals(200, resp.getCode());
        assertEquals(true, resp.getSuccess());
        verify(haccpCheckpointRepo).saveAndFlush(any(HaccpCheckpoint.class));
    }

    @Test
    @DisplayName("HACCP update: cross-factory access → 403 BusinessException")
    void testUpdateCheckpointCrossFactory() {
        HaccpCheckpoint cp = HaccpCheckpoint.builder()
                .id(42L)
                .factoryId("F999")   // belongs to F999
                .build();
        when(haccpCheckpointRepo.findById(42L)).thenReturn(Optional.of(cp));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "尝试越权");

        try {
            controller.updateCheckpoint("F006", 42L, body);
            org.junit.jupiter.api.Assertions.fail("expected BusinessException 403");
        } catch (com.cretas.aims.exception.BusinessException ex) {
            assertEquals(403, ex.getCode());
            assertTrue(ex.getMessage().contains("无权"),
                    "403 message should clearly indicate authorization: " + ex.getMessage());
        }
        verify(haccpCheckpointRepo, never()).saveAndFlush(any());
    }

    // ==================== Recall Event — CREATE ====================

    @Test
    @DisplayName("Recall create: default status=INVESTIGATING, version=null OK on create")
    void testCreateRecallDefaults() {
        Map<String, Object> body = new HashMap<>();
        body.put("eventCode", "RECALL-20260801-001");
        body.put("triggerReason", "客户投诉发现卤猪蹄变质");
        body.put("affectedProductCategory", "卤猪蹄");
        body.put("triggeredByUserId", 7L);

        when(recallEventRepo.findByEventCode("RECALL-20260801-001")).thenReturn(Optional.empty());
        when(recallEventRepo.saveAndFlush(any(RecallEvent.class))).thenAnswer(inv -> {
            RecallEvent r = inv.getArgument(0);
            r.setId(123L);
            r.setVersion(0L);
            return r;
        });

        ApiResponse<Map<String, Object>> resp = controller.createRecall("F006", body);

        assertEquals(200, resp.getCode());
        Map<String, Object> data = resp.getData();
        assertEquals("INVESTIGATING", data.get("status"),
                "new recall must default to INVESTIGATING");
        assertNotNull(data.get("triggerTime"), "triggerTime auto-fill from server NOW()");
    }

    /**
     * 2026-08-02 owner 拍板: 已结案(COMPLETED)的召回不允许改回别的状态。
     *
     * <p>此前控制器允许人工把 status 改到词表里<b>任意</b>一个值 —— 包括把已结案的召回
     * 改回"调查中"。召回是合规留痕场景, 改回去会让台账与监管上报对不上,
     * 而且没有任何审计流水能还原它曾经结过案。
     *
     * <p>⚠️ 只禁这一条, <b>不是</b>完整流转矩阵 —— 见下一条配对用例。
     */
    @Test
    @DisplayName("Recall update: COMPLETED → INVESTIGATING 回退 → 409 CONFLICT")
    void testUpdateRecallForbidsCompletedRollback() {
        RecallEvent ev = RecallEvent.builder()
                .id(123L)
                .factoryId("F006")
                .eventCode("RECALL-20260801-001")
                .status("COMPLETED")
                .version(0L)
                .build();
        when(recallEventRepo.findById(123L)).thenReturn(Optional.of(ev));

        Map<String, Object> body = new HashMap<>();
        body.put("status", "INVESTIGATING");

        ApiResponse<Map<String, Object>> resp = controller.updateRecall("F006", 123L, body);

        assertEquals(409, resp.getCode());
        assertEquals("CONFLICT", resp.getErrorCode());
        assertEquals("COMPLETED", ev.getStatus(), "被拒绝时不该改动实体");
        verify(recallEventRepo, never()).saveAndFlush(any());
    }

    /**
     * 配对用例: <b>其余方向仍然放行</b>。
     *
     * <p>钉住"只禁一条"这个取舍本身 —— 哪天有人把它补成完整流转矩阵, 这条会红,
     * 强迫那次改动是有意识的(补全矩阵会开始拒绝现在能做的其它人工操作)。
     */
    @Test
    @DisplayName("Recall update: REPORTED → FROZEN 仍放行(只禁 COMPLETED 回退, 非完整矩阵)")
    void testUpdateRecallStillAllowsOtherTransitions() {
        RecallEvent ev = RecallEvent.builder()
                .id(123L)
                .factoryId("F006")
                .eventCode("RECALL-20260801-001")
                .status("REPORTED")
                .version(0L)
                .build();
        when(recallEventRepo.findById(123L)).thenReturn(Optional.of(ev));
        when(recallEventRepo.saveAndFlush(any(RecallEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("status", "FROZEN");

        ApiResponse<Map<String, Object>> resp = controller.updateRecall("F006", 123L, body);

        assertEquals(200, resp.getCode());
        assertEquals("FROZEN", ev.getStatus());
    }

    @Test
    @DisplayName("Recall update: invalid status enum → 400 VALIDATION list allowed values")
    void testUpdateRecallInvalidStatus() {
        RecallEvent ev = RecallEvent.builder()
                .id(123L)
                .factoryId("F006")
                .eventCode("RECALL-20260801-001")
                .status("INVESTIGATING")
                .version(0L)
                .build();
        when(recallEventRepo.findById(123L)).thenReturn(Optional.of(ev));

        Map<String, Object> body = new HashMap<>();
        body.put("status", "DELETED_BY_LLM");   // not in enum

        ApiResponse<Map<String, Object>> resp = controller.updateRecall("F006", 123L, body);

        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("DELETED_BY_LLM"),
                "error should echo invalid value: " + resp.getMessage());
        assertTrue(resp.getActionHint().contains("INVESTIGATING"),
                "actionHint should list valid enum values (fool-proof Rule 3): " + resp.getActionHint());
        verify(recallEventRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Recall create: 重复 eventCode → 409 DUPLICATE")
    void testCreateRecallDuplicateCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("eventCode", "RECALL-20260801-001");
        body.put("triggerReason", "客户投诉");
        body.put("affectedProductCategory", "卤猪蹄");
        body.put("triggeredByUserId", 7L);

        RecallEvent existing = RecallEvent.builder()
                .id(99L)
                .eventCode("RECALL-20260801-001")
                .build();
        when(recallEventRepo.findByEventCode("RECALL-20260801-001"))
                .thenReturn(Optional.of(existing));

        ApiResponse<Map<String, Object>> resp = controller.createRecall("F006", body);

        assertEquals(409, resp.getCode());
        assertEquals("DUPLICATE", resp.getErrorCode());
        verify(recallEventRepo, never()).saveAndFlush(any());
    }

    // ==================== Summary endpoint ====================

    @Test
    @DisplayName("Summary: aggregates counts from all 4 mockable repos")
    void testSummaryAggregates() {
        when(haccpCheckpointRepo.findByFactoryIdAndActiveTrue("F006"))
                .thenReturn(List.of(
                        HaccpCheckpoint.builder().id(1L).build(),
                        HaccpCheckpoint.builder().id(2L).build()));
        when(haccpMonitoringRepo.findByFactoryIdAndIsDeviationTrue("F006"))
                .thenReturn(List.of());
        when(recallEventRepo.findByFactoryIdOrderByTriggerTimeDesc(anyString(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(additiveLimitRepo.count()).thenReturn(30L);

        ApiResponse<Map<String, Object>> resp = controller.summary("F006");

        assertEquals(200, resp.getCode());
        Map<String, Object> data = resp.getData();
        assertEquals(2L, data.get("haccpCheckpointsActive"));
        assertEquals(0L, data.get("haccpDeviations"));
        assertEquals(0L, data.get("recallsOpen"));
        assertEquals(30L, data.get("additiveLimitsTotal"));
        // Placeholders for Sprint 9 entities not yet merged
        assertEquals(0, data.get("foodSamplePending"));
        assertEquals(0, data.get("nutritionLabelPending"));
        assertEquals(0, data.get("supplierQualPending"));
    }
}
