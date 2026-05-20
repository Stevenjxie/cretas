package com.cretas.aims.controller.datacenter;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.datacenter.ExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Length-validation tests for {@link ExportRuleController} — AUD-5 B-A3 sister sweep
 * batch 4a (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link ExportService}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>{@link ExportRule} is accepted as raw {@code @RequestBody} with no {@code @Size}
 * annotations, so controller-side pre-check is the safety net for {@code moduleCode}
 * (64), {@code ruleName} (200), {@code format} (10), {@code targetEntity} (200).
 * The {@code description} and {@code filterExpression} columns are TEXT (unbounded),
 * so no pre-check is needed for them.
 *
 * <p>Both create (POST) and update (PUT) share a single {@code validateExportRuleLengths}
 * helper — Rule 16 entry-point matrix.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 4a)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExportRuleController AUD-5 B-A3 length pre-check")
class ExportRuleControllerTest {

    @Mock ExportService exportService;
    @InjectMocks ExportRuleController controller;

    // ==================== AUD-5 B-A3: moduleCode > 64 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.moduleCode > 64 字符 → 400 with 4-in-1 hint")
    void testLongModuleCodeOnCreateRejected() {
        // PG column: export_rules.module_code VARCHAR(64). Pre-fix, a 65-char input let
        // the request reach PG and surfaced as DataIntegrityViolationException → 409.
        ExportRule rule = newBaseRule();
        rule.setModuleCode("M".repeat(65));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", rule));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("模块代码"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("64"));
        assertTrue(ex.getMessage().contains("65"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("moduleCode", ex.getHintTarget());
        verify(exportService, never()).createRule(any());
    }

    // ==================== AUD-5 B-A3: ruleName > 200 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.ruleName > 200 字符 → 400")
    void testLongRuleNameOnCreateRejected() {
        // PG column: export_rules.rule_name VARCHAR(200).
        ExportRule rule = newBaseRule();
        rule.setRuleName("R".repeat(201));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", rule));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("规则名称"));
        assertTrue(ex.getMessage().contains("200"));
        assertEquals("ruleName", ex.getHintTarget());
        verify(exportService, never()).createRule(any());
    }

    // ==================== AUD-5 B-A3: format > 10 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.format > 10 字符 → 400")
    void testLongFormatOnCreateRejected() {
        // PG column: export_rules.format VARCHAR(10).
        ExportRule rule = newBaseRule();
        rule.setFormat("F".repeat(11));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", rule));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("导出格式"));
        assertTrue(ex.getMessage().contains("10"));
        assertEquals("format", ex.getHintTarget());
        verify(exportService, never()).createRule(any());
    }

    // ==================== AUD-5 B-A3: targetEntity > 200 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.targetEntity > 200 字符 → 400")
    void testLongTargetEntityOnCreateRejected() {
        // PG column: export_rules.target_entity VARCHAR(200).
        ExportRule rule = newBaseRule();
        rule.setTargetEntity("com.cretas.aims.entity." + "X".repeat(180));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", rule));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("目标实体类名"));
        assertTrue(ex.getMessage().contains("200"));
        assertEquals("targetEntity", ex.getHintTarget());
        verify(exportService, never()).createRule(any());
    }

    // ==================== Rule 16: update path mirrors create ====================

    @Test
    @DisplayName("Rule 16: update.ruleName > 200 字符 → 400 (entry-point matrix)")
    void testLongRuleNameOnUpdateRejected() {
        ExportRule patch = newBaseRule();
        patch.setRuleName("R".repeat(250));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update("F001", 1L, patch));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("规则名称"));
        assertEquals("ruleName", ex.getHintTarget());
        verify(exportService, never()).updateRule(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("Rule 16: update.moduleCode > 64 字符 → 400 (entry-point matrix)")
    void testLongModuleCodeOnUpdateRejected() {
        ExportRule patch = newBaseRule();
        patch.setModuleCode("M".repeat(80));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update("F001", 1L, patch));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("模块代码"));
        assertEquals("moduleCode", ex.getHintTarget());
        verify(exportService, never()).updateRule(anyString(), anyLong(), any());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: ruleName at exactly 200 字符 accepted")
    void testMaxLengthRuleNameAccepted() {
        ExportRule rule = newBaseRule();
        rule.setRuleName("R".repeat(200));   // exactly at limit
        when(exportService.createRule(any())).thenReturn(rule);

        ResponseEntity<ApiResponse<ExportRule>> resp = controller.create("F001", rule);
        assertNotNull(resp);
        verify(exportService).createRule(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3 boundary: moduleCode at exactly 64 字符 accepted")
    void testMaxLengthModuleCodeAccepted() {
        ExportRule rule = newBaseRule();
        rule.setModuleCode("M".repeat(64));   // exactly at limit
        when(exportService.createRule(any())).thenReturn(rule);

        ResponseEntity<ApiResponse<ExportRule>> resp = controller.create("F001", rule);
        assertNotNull(resp);
        verify(exportService).createRule(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null patch tolerated on update (patch-style)")
    void testNullPatchAccepted() {
        ExportRule patch = new ExportRule();   // all-null
        when(exportService.updateRule(anyString(), anyLong(), any())).thenReturn(newBaseRule());

        ResponseEntity<ApiResponse<ExportRule>> resp = controller.update("F001", 1L, patch);
        assertNotNull(resp);
        verify(exportService).updateRule(anyString(), anyLong(), any());
    }

    // ==================== Helpers ====================

    private ExportRule newBaseRule() {
        ExportRule rule = new ExportRule();
        rule.setFactoryId("F001");
        rule.setModuleCode("customer");
        rule.setRuleName("客户导出 v1");
        rule.setFormat("XLSX");
        rule.setTargetEntity("com.cretas.aims.entity.Customer");
        rule.setIsAsync(false);
        rule.setRowThreshold(10000);
        return rule;
    }
}
