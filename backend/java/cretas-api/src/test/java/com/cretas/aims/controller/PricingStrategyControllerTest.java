package com.cretas.aims.controller;

import com.cretas.aims.controller.PricingStrategyController.StrategyRequest;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.PricingEngine;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boundary-validation verification for {@link PricingStrategyController} — QA Round 2026-05-19
 * findings B-P1 .. B-P5 (Pricing Tab, independent Playwright QA).
 *
 * <p>Each test asserts that a malformed write request is rejected with a specific Chinese
 * message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md} cross-cutting rule)
 * BEFORE the request reaches {@link PricingStrategyRepository#save(Object)}. This guarantees
 * neither {@code curl} nor a misbehaving FE can persist:
 * <ul>
 *   <li><b>B-P2</b> overlapping TIERED intervals (e.g. {@code [0,100]} + {@code [50,200]})</li>
 *   <li><b>B-P3</b> negative {@code discountPct} (= 涨价, breaks engine semantics)</li>
 *   <li><b>B-P4</b> {@code discountPct > 100} (= 倒贴, finalPrice would go below 0)</li>
 *   <li><b>B-P5</b> {@code validTo < validFrom} (empty effective interval, never matches)</li>
 *   <li><b>B-P1</b> {@code validFrom} format is pinned to {@code yyyy-MM-dd} so date parse
 *       errors surface through the existing
 *       {@code HttpMessageNotReadableException} handler with the LocalDate-specific hint</li>
 * </ul>
 *
 * <p>Tests are pure {@code @ExtendWith(MockitoExtension)} — no Spring context — to keep them
 * fast and unambiguous about which code path threw. The B-P1 test inspects the DTO field's
 * {@link JsonFormat} reflectively rather than spinning up Jackson, because the round-trip
 * deserialization integration is exercised in service-level integration tests.
 */
@ExtendWith(MockitoExtension.class)
class PricingStrategyControllerTest {

    @Mock PricingStrategyRepository strategyRepo;
    @Mock PricingApplicationLogRepository logRepo;
    @Mock PricingEngine pricingEngine;
    @InjectMocks PricingStrategyController controller;

    // ===== B-P2: overlapping tier intervals must be rejected =====

    @Test
    @DisplayName("B-P2: 阶梯区间重叠 [0-100] + [50-200] → 400, 4-in-1 hint")
    void testOverlappingTiersRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(50, 200, "15")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req),
                "重叠区间必须 reject");

        assertEquals(400, ex.getCode(), "code must be 400");
        assertTrue(ex.getMessage().contains("阶梯区间重叠"),
                "message must explain the failure mode: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0") && ex.getMessage().contains("100"),
                "message must surface first tier's bounds for user identification: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("50") && ex.getMessage().contains("200"),
                "message must surface second tier's bounds: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertNotNull(ex.getHintTarget(), "4-in-1 UX (d): hintTarget required");
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P2: 阶梯区间相邻 [0-100] + [101-200] → 通过 (non-overlap)")
    void testAdjacentTiersAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(101, 200, "15")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        // No exception thrown — relays through to save.
        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P3: negative discountPct must be rejected =====

    @Test
    @DisplayName("B-P3: discountPct=-5 (负数 = 涨价) → 400, 4-in-1 hint")
    void testNegativeDiscountRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tier(0, 100, "-5")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("折扣率不可为负"),
                "message must say discount cannot be negative: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-5"),
                "message must include the violating value: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].discountPct", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at the violating field path");
        verify(strategyRepo, never()).save(any());
    }

    // ===== B-P4: discountPct > 100 must be rejected =====

    @Test
    @DisplayName("B-P4: discountPct=150 (>100% = 倒贴) → 400, 4-in-1 hint")
    void testDiscountOver100Rejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tier(0, 100, "150")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("折扣率不可超过 100%"),
                "message must say discount cannot exceed 100%: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("150"),
                "message must include the violating value: " + ex.getMessage());
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].discountPct", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P3/4 boundary: discountPct=0 and 100 are both accepted (inclusive)")
    void testDiscountBoundaryAccepted() {
        // 0% (no discount) and 100% (free) are both legitimate edge cases —
        // 0% = "tier exists for metering but applies nothing", 100% = "fully promotional unit".
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 50, "0"),
                tier(51, 100, "100")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P5: validTo < validFrom must be rejected =====

    @Test
    @DisplayName("B-P5: validTo (2026-01-01) < validFrom (2026-05-01) → 400, 4-in-1 hint")
    void testReversedDateRangeRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setValidFrom(LocalDate.of(2026, 5, 1));
        req.setValidTo(LocalDate.of(2026, 1, 1));   // before validFrom

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("validTo") && ex.getMessage().contains("validFrom"),
                "message must reference both ends of the interval: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("2026-05-01") && ex.getMessage().contains("2026-01-01"),
                "message must surface the actual values: " + ex.getMessage());
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("validTo", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("B-P5 boundary: validFrom == validTo (single-day interval) accepted")
    void testSameDayDateRangeAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        LocalDate sameDay = LocalDate.of(2026, 5, 19);
        req.setValidFrom(sameDay);
        req.setValidTo(sameDay);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== B-P1: validFrom/validTo @JsonFormat pinned to yyyy-MM-dd =====

    @Test
    @DisplayName("B-P1: validFrom + validTo annotated with @JsonFormat(pattern=\"yyyy-MM-dd\")")
    void testIsoDatetimeFormatGivesHint() throws NoSuchFieldException {
        // Why reflective: we want to assert the format contract at the DTO surface
        // independently of Jackson runtime. With this annotation, a full-ISO datetime
        // input like "2026-05-01T00:00:00" is rejected by Jackson with a
        // DateTimeParseException whose message contains "java.time.LocalDate" — that
        // matches GlobalExceptionHandler.handleHttpMessageNotReadableException at
        // line ~684 and routes to "日期格式不正确（值: ...），请重新选择日期",
        // NOT the generic "请求格式不正确" fallback that B-P1 surfaced.
        Field from = StrategyRequest.class.getDeclaredField("validFrom");
        JsonFormat fromFmt = from.getAnnotation(JsonFormat.class);
        assertNotNull(fromFmt, "validFrom must carry @JsonFormat to enforce date-only parse");
        assertEquals("yyyy-MM-dd", fromFmt.pattern(),
                "validFrom @JsonFormat pattern must be yyyy-MM-dd to reject datetime strings");

        Field to = StrategyRequest.class.getDeclaredField("validTo");
        JsonFormat toFmt = to.getAnnotation(JsonFormat.class);
        assertNotNull(toFmt, "validTo must carry @JsonFormat to enforce date-only parse");
        assertEquals("yyyy-MM-dd", toFmt.pattern(),
                "validTo @JsonFormat pattern must be yyyy-MM-dd to reject datetime strings");
    }

    // ===== Update path mirrors create — sister assertion to catch entry-point divergence
    //       (per QA prompt v2.4 Rule 16: handleCreate + handleEdit are independent code paths) =====

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same B-P2 overlap validation as create")
    void testUpdateAppliesSameOverlapValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 100, "10"),
                tier(50, 200, "15")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req),
                "重叠区间在 update 路径也必须 reject (Rule 16: entry point matrix)");
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯区间重叠"));
        // Crucially: findById was NEVER called — validation fires before repo lookup.
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same B-P5 date validation as create")
    void testUpdateAppliesSameDateValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setValidFrom(LocalDate.of(2026, 5, 1));
        req.setValidTo(LocalDate.of(2026, 1, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req));
        assertEquals(400, ex.getCode());
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    // ===== Happy-path sanity check — clean TIERED create still works (no false positive) =====

    @Test
    @DisplayName("Happy path: clean TIERED create with non-overlapping tiers persists normally")
    void testCleanTieredCreatePersists() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(
                tier(0, 99, "5"),
                tier(100, 499, "10"),
                tier(500, 9999, "15")));
        req.setValidFrom(LocalDate.of(2026, 1, 1));
        req.setValidTo(LocalDate.of(2026, 12, 31));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> {
            PricingStrategy saved = inv.getArgument(0);
            saved.setId("generated-uuid");
            return saved;
        });

        var resp = controller.createStrategy("F001", req);
        assertNotNull(resp);
        assertTrue(resp.getSuccess());
        assertEquals(PricingStrategyType.TIERED, resp.getData().getStrategyType());
        assertEquals("F001", resp.getData().getFactoryId());
    }

    // ===== Edge case: non-TIERED type skips tier validation =====

    @Test
    @DisplayName("Non-TIERED types (MEMBER) skip tier validation even if rulesJson.tiers exists")
    void testNonTieredSkipsTierValidation() {
        // Even with malformed `tiers` array in a MEMBER rule, validation should not fire —
        // MEMBER's engine path reads `membershipTier` / `discountPct` / `tierDiscounts`,
        // not `tiers`. Validating it would be overreach.
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyType("MEMBER");
        Map<String, Object> rules = new HashMap<>();
        rules.put("membershipTier", "VIP");
        rules.put("discountPct", new BigDecimal("5"));
        // Stray `tiers` field with garbage — should NOT trip TIERED validation.
        rules.put("tiers", List.of(tier(0, 100, "-999")));
        req.setRulesJson(rules);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== Edge case: empty / missing rulesJson tolerated =====

    @Test
    @DisplayName("Empty rulesJson tolerated (engine returns ZERO discount at apply time)")
    void testEmptyRulesJsonTolerated() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(null);

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ==================== Edge audit 2026-05-20 — AUD-2 / AUD-3 ====================

    // ===== AUD-2: negative minQty (or maxQty) must be rejected (Long.MIN_VALUE hole) =====

    @Test
    @DisplayName("AUD-2: minQty=-1 → 400, 4-in-1 hint (sister of B-P3)")
    void testNegativeMinQtyRejected() {
        // Pre-fix: existing validator only checked `maxQty < minQty`, so any negative
        // minQty paired with a (also negative-or-zero) maxQty passed silently.
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tierLong(-1L, 100L, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req),
                "负 minQty 必须 reject");

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯数量不可为负"),
                "message must say quantity cannot be negative: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-1"),
                "message must include the violating value: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].minQty", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at the violating field path");
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-2: minQty=Long.MIN_VALUE → 400 (curl attack vector)")
    void testLongMinValueMinQtyRejected() {
        // Real attack vector surfaced by edge audit 2026-05-20:
        // `tiers[0].minQty: -9223372036854775808` was persisted on test env because
        // the relation-only `maxQty < minQty` check is bypassed when minQty is the
        // most-negative long (any reasonable maxQty satisfies the relation).
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tierLong(Long.MIN_VALUE, 100L, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯数量不可为负"));
        assertTrue(ex.getMessage().contains(String.valueOf(Long.MIN_VALUE)),
                "message must surface the offending Long.MIN_VALUE to aid debugging: " + ex.getMessage());
        assertEquals("tiers[0].minQty", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-2: maxQty=-1 → 400 (symmetric sister of minQty check)")
    void testNegativeMaxQtyRejected() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tierLong(0L, -1L, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯数量不可为负"));
        assertTrue(ex.getMessage().contains("-1"));
        assertEquals("tiers[0].maxQty", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-2 boundary: minQty=0 still accepted (inclusive lower bound)")
    void testZeroMinQtyAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    // ===== AUD-3: high-precision discountPct that rounds to boundary must be rejected =====

    @Test
    @DisplayName("AUD-3: 28-digit discountPct 99.99...99 (scale > 2, not 2-decimal-equal) → 400")
    void testHighPrecisionDiscountRejected() {
        // Edge audit 2026-05-20 finding: `99.99999999999999999999999999` (28 fractional 9s)
        // arrives as BigDecimal via test path (or, on the wire, ambiguously as Double
        // rounded to 100.0 — both routes need rejection for safety). The scale > 2 check
        // fires regardless of whether the user's intent was just-below-100 or precision
        // attack; either way `discountPct.scale() == 28` means the value can't be a
        // legitimate percentage with 2-decimal granularity.
        StrategyRequest req = newBaseTieredRequest();
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("minQty", 0);
        tier.put("maxQty", 100);
        tier.put("discountPct", new BigDecimal("99.99999999999999999999999999"));
        req.setRulesJson(tieredRulesJson(tier));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req),
                "28-digit precision discountPct 必须 reject — 不能放进去再被舍入掩盖意图");

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("折扣率精度过高")
                        || ex.getMessage().contains("精度过高"),
                "message must explain the precision overflow: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity());
        assertEquals("tiers[0].discountPct", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget pinpoints precision-overflowed field");
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-3: trailing-zero high scale (10.5000) is normalised and accepted")
    void testTrailingZeroScaleAccepted() {
        // 10.5000 has scale=4 but value-equals 10.50 setScale(2, HALF_UP). Should be
        // accepted — only TRUE precision overflow (where rounding changes the value)
        // triggers the new check. This guards against false positives on FE inputs that
        // post values like "10.5000" through extra serialization steps.
        StrategyRequest req = newBaseTieredRequest();
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("minQty", 0);
        tier.put("maxQty", 100);
        tier.put("discountPct", new BigDecimal("10.5000"));
        req.setRulesJson(tieredRulesJson(tier));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    @Test
    @DisplayName("AUD-3 boundary: 99.99 (scale=2) still accepted (existing happy path)")
    void testTwoDecimalDiscountAccepted() {
        // Regression guard for B-P4: legitimate 2-decimal-place discount must not be
        // false-flagged by the new precision check.
        StrategyRequest req = newBaseTieredRequest();
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("minQty", 0);
        tier.put("maxQty", 100);
        tier.put("discountPct", new BigDecimal("99.99"));
        req.setRulesJson(tieredRulesJson(tier));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    @Test
    @DisplayName("AUD-3 boundary: 100.0 exactly still accepted; 100.0001 rejected as > 100")
    void testHundredBoundaryStillInclusive() {
        // 100.0 (scale=1, equals setScale(2,HALF_UP)=100.00) → AUD-3 check skipped,
        //                                                       B-P4 > 100 check passes.
        StrategyRequest req = newBaseTieredRequest();
        Map<String, Object> okTier = new LinkedHashMap<>();
        okTier.put("minQty", 0);
        okTier.put("maxQty", 100);
        okTier.put("discountPct", new BigDecimal("100.0"));
        req.setRulesJson(tieredRulesJson(okTier));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));

        // 100.0001 (scale=4, value != setScale(2,HALF_UP)=100.00) → AUD-3 fires.
        StrategyRequest reqOver = newBaseTieredRequest();
        Map<String, Object> overTier = new LinkedHashMap<>();
        overTier.put("minQty", 0);
        overTier.put("maxQty", 100);
        overTier.put("discountPct", new BigDecimal("100.0001"));
        reqOver.setRulesJson(tieredRulesJson(overTier));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", reqOver));
        // Either AUD-3 (precision) or B-P4 (>100) is acceptable — both surface a 400.
        // We assert it's NOT silently accepted, and verify the next-action hint is present.
        assertEquals(400, ex.getCode());
        assertNotNull(ex.getActionHint());
        assertEquals("tiers[0].discountPct", ex.getHintTarget());
    }

    // ==================== AUD-5 B-A3 sister sweep — length pre-check ====================

    @Test
    @DisplayName("AUD-5 B-A3: strategyCode > 100 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongStrategyCodeRejected() {
        // PG column: pricing_strategies.strategy_code VARCHAR(100). Pre-fix, a 101-char
        // input let the request reach PG and surfaced as DataIntegrityViolationException →
        // generic 409 "数据处理异常". Pre-check delivers specific 400 with current vs
        // max length so user can immediately spot the issue.
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyCode("X".repeat(101));   // 101 chars, max is 100
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req),
                "101-char strategyCode 必须 reject");

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("策略代码"),
                "message must reference the field name: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("100"),
                "message must include the limit (100): " + ex.getMessage());
        assertTrue(ex.getMessage().contains("101"),
                "message must include the current length (101) to aid the user: " + ex.getMessage());
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("strategyCode", ex.getHintTarget(),
                "4-in-1 UX (d): hintTarget must point at strategyCode");
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3: strategyName > 255 字符 → 400 with 4-in-1 hint")
    void testLongStrategyNameRejected() {
        // PG column: pricing_strategies.strategy_name VARCHAR(255).
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyName("名".repeat(256));   // 256 chars, max is 255
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createStrategy("F001", req));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("策略名称"));
        assertTrue(ex.getMessage().contains("255"));
        assertTrue(ex.getMessage().contains("256"));
        assertNotNull(ex.getActionHint());
        assertEquals("warning", ex.getSeverity());
        assertEquals("strategyName", ex.getHintTarget());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("AUD-5 B-A3 boundary: strategyCode at exactly 100 字符 accepted")
    void testMaxLengthStrategyCodeAccepted() {
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyCode("X".repeat(100));   // exactly at limit
        req.setStrategyName("名".repeat(255));  // exactly at limit
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: null strategyName tolerated (nullable column)")
    void testNullStrategyNameAccepted() {
        // strategy_name has @Column(length=255) without nullable=false, so null is OK.
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyName(null);
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        when(strategyRepo.findByFactoryIdAndStrategyCode(eq("F001"), anyString()))
                .thenReturn(Optional.empty());
        when(strategyRepo.save(any(PricingStrategy.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.createStrategy("F001", req);
        verify(strategyRepo).save(any(PricingStrategy.class));
    }

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same AUD-5 length validation as create")
    void testUpdateAppliesLengthValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setStrategyCode("X".repeat(200));   // way over 100
        req.setRulesJson(tieredRulesJson(tier(0, 100, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req),
                "Rule 16: update path 也必须 reject 长 strategyCode");
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("策略代码"));
        assertEquals("strategyCode", ex.getHintTarget());
        // Crucially: findById was NEVER called — length validation fires before repo lookup.
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    // ===== AUD-2/3 mirror on update path (Rule 16 entry matrix) =====

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same AUD-2 negative-qty validation as create")
    void testUpdateAppliesNegativeQtyValidation() {
        StrategyRequest req = newBaseTieredRequest();
        req.setRulesJson(tieredRulesJson(tierLong(Long.MIN_VALUE, 100L, "10")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("阶梯数量不可为负"));
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    @Test
    @DisplayName("Rule 16: updateStrategy applies the same AUD-3 precision validation as create")
    void testUpdateAppliesPrecisionValidation() {
        StrategyRequest req = newBaseTieredRequest();
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("minQty", 0);
        tier.put("maxQty", 100);
        tier.put("discountPct", new BigDecimal("99.99999999999999999999999999"));
        req.setRulesJson(tieredRulesJson(tier));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStrategy("F001", "some-id", req));
        assertEquals(400, ex.getCode());
        verify(strategyRepo, never()).findById(anyString());
        verify(strategyRepo, never()).save(any());
    }

    // ==================== Test helpers ====================

    private static StrategyRequest newBaseTieredRequest() {
        StrategyRequest req = new StrategyRequest();
        req.setStrategyCode("TEST_TIERED_001");
        req.setStrategyName("阶梯定价测试");
        req.setStrategyType("TIERED");
        req.setPriority(100);
        req.setEnabled(true);
        return req;
    }

    /** Build {@code rulesJson} with TIERED-shaped tiers. */
    private static Map<String, Object> tieredRulesJson(Map<String, Object>... tiers) {
        Map<String, Object> rules = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>(tiers.length);
        for (Map<String, Object> t : tiers) list.add(t);
        rules.put("tiers", list);
        return rules;
    }

    private static Map<String, Object> tier(int minQty, int maxQty, String discountPct) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("minQty", minQty);
        t.put("maxQty", maxQty);
        t.put("discountPct", new BigDecimal(discountPct));
        return t;
    }

    /**
     * Long-typed variant for AUD-2 tests (Long.MIN_VALUE doesn't fit in int).
     * Mirrors {@link #tier(int, int, String)} signature, just using long for the qty fields.
     */
    private static Map<String, Object> tierLong(long minQty, long maxQty, String discountPct) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("minQty", minQty);
        t.put("maxQty", maxQty);
        t.put("discountPct", new BigDecimal(discountPct));
        return t;
    }
}
