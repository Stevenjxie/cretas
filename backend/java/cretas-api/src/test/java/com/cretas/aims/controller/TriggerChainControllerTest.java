package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Length-validation tests for {@link TriggerChainController} — AUD-5 B-A3 sister sweep
 * batch 4a (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches the repository
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>{@link FactoryTriggerChain} is accepted as raw {@code @RequestBody} with no
 * {@code @Size} annotations, so controller-side pre-check is the safety net for
 * {@code eventType} (100) and {@code errorStrategy} (20). The {@code description} column
 * is {@code TEXT} (unbounded), so no pre-check is needed for it.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 4a)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerChainController AUD-5 B-A3 length pre-check")
class TriggerChainControllerTest {

    @Mock FactoryToolConfigRepository toolConfigRepo;
    @Mock FactorySkillConfigRepository skillConfigRepo;
    @Mock FactoryTriggerChainRepository triggerChainRepo;
    @InjectMocks TriggerChainController controller;

    // ==================== AUD-5 B-A3: eventType > 100 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: setTriggerChain.eventType > 100 字符 → 400 with 4-in-1 hint")
    void testLongEventTypeRejected() {
        // PG column: factory_trigger_chains.event_type VARCHAR(100). Pre-fix, a 101-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException → 409.
        FactoryTriggerChain body = newBaseChain();
        body.setEventType("E".repeat(101));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.setTriggerChain("F001", "ORDER_CREATED", body));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("事件类型"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("101"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("eventType", ex.getHintTarget());
        verify(triggerChainRepo, never()).save(any());
    }

    // ==================== AUD-5 B-A3: errorStrategy > 20 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: setTriggerChain.errorStrategy > 20 字符 → 400")
    void testLongErrorStrategyRejected() {
        // PG column: factory_trigger_chains.error_strategy VARCHAR(20).
        FactoryTriggerChain body = newBaseChain();
        body.setErrorStrategy("S".repeat(21));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.setTriggerChain("F001", "ORDER_CREATED", body));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("错误处理策略"));
        assertTrue(ex.getMessage().contains("20"));
        assertEquals("errorStrategy", ex.getHintTarget());
        verify(triggerChainRepo, never()).save(any());
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: eventType at exactly 100 字符 accepted")
    void testMaxLengthEventTypeAccepted() {
        FactoryTriggerChain body = newBaseChain();
        body.setEventType("E".repeat(100));   // exactly at limit
        when(triggerChainRepo.findByFactoryIdAndChainCode(eq("F001"), anyString()))
                .thenReturn(Optional.of(newBaseChain()));
        when(triggerChainRepo.save(any())).thenReturn(body);

        ApiResponse<FactoryTriggerChain> resp =
                controller.setTriggerChain("F001", "ORDER_CREATED", body);
        assertNotNull(resp);
        verify(triggerChainRepo).save(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3 boundary: errorStrategy at exactly 20 字符 accepted")
    void testMaxLengthErrorStrategyAccepted() {
        FactoryTriggerChain body = newBaseChain();
        body.setErrorStrategy("S".repeat(20));  // exactly at limit
        when(triggerChainRepo.findByFactoryIdAndChainCode(eq("F001"), anyString()))
                .thenReturn(Optional.of(newBaseChain()));
        when(triggerChainRepo.save(any())).thenReturn(body);

        ApiResponse<FactoryTriggerChain> resp =
                controller.setTriggerChain("F001", "ORDER_CREATED", body);
        assertNotNull(resp);
        verify(triggerChainRepo).save(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: null eventType + null errorStrategy tolerated (patch-style)")
    void testNullOptionalFieldsAccepted() {
        FactoryTriggerChain body = new FactoryTriggerChain();  // All null
        when(triggerChainRepo.findByFactoryIdAndChainCode(eq("F001"), anyString()))
                .thenReturn(Optional.of(newBaseChain()));
        when(triggerChainRepo.save(any())).thenReturn(newBaseChain());

        ApiResponse<FactoryTriggerChain> resp =
                controller.setTriggerChain("F001", "ORDER_CREATED", body);
        assertNotNull(resp);
    }

    // ==================== Helpers ====================

    private FactoryTriggerChain newBaseChain() {
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId("F001");
        chain.setChainCode("ORDER_CREATED");
        chain.setEventType("ORDER_CREATED");
        chain.setErrorStrategy("CONTINUE");
        chain.setEnabled(true);
        return chain;
    }
}
