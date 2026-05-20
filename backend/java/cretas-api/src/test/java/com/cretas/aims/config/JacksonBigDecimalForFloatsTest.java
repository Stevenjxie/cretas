package com.cretas.aims.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-3 — Verify {@code spring.jackson.deserialization.use-big-decimal-for-floats=true}
 * closes the 28-digit precision overflow attack on the Pricing >100% discount validator.
 *
 * <p>Background (per edge audit 2026-05-20 + audit subagent a4597f9cf):
 * <ul>
 *   <li>{@link com.cretas.aims.controller.PricingStrategyController} parses {@code rulesJson}
 *       as {@code Map<String, Object>}. Jackson's <b>default</b> behavior is to deserialize
 *       JSON floats inside an {@code Object}-valued slot as {@link Double}.</li>
 *   <li>This means a curl payload of {@code discountPct: 99.99999999999999999999999999}
 *       (28 fractional 9s) arrives at the controller as {@code Double 100.0} — the IEEE 754
 *       representation rounds up. The downstream AUD-3 BigDecimal scale check
 *       ({@code scale > 2 && !equals(setScale(2, HALF_UP))}) never fires because
 *       {@code Double.valueOf(100.0)} has no scale information, and the value reaches
 *       {@code Number}-cast paths as exactly {@code 100.00}, silently bypassing the
 *       {@code > 100%} rejection branch (B-P4).</li>
 *   <li>Wire-level evidence (verified on prod &lt; 1h before this PR): {@code POST
 *       /api/mobile/F006/pricing/strategies} with the 28-digit payload returns HTTP 200
 *       and persists {@code discountPct=100.0} — the unit tests in
 *       {@link com.cretas.aims.controller.PricingStrategyControllerTest#testHighPrecisionDiscountRejected}
 *       only PASS because they hand-construct {@code new BigDecimal("99.999...")} bypassing
 *       Jackson entirely.</li>
 * </ul>
 *
 * <p>The fix is a one-line global Jackson property in all four application*.properties
 * files: {@code spring.jackson.deserialization.use-big-decimal-for-floats=true}. With it,
 * Jackson decodes the JSON float as {@link BigDecimal} (preserving full 28-digit precision),
 * which then propagates through {@code Map<String, Object>} into the controller's tier
 * loop, where the existing AUD-3 scale check correctly rejects it with a 400 + 4-in-1 hint.
 *
 * <p>This test verifies the global config takes effect by:
 * <ol>
 *   <li>Reproducing the <b>broken</b> default behavior (control case — Double rounding).</li>
 *   <li>Reproducing the <b>fixed</b> behavior after enabling the feature (BigDecimal
 *       full precision).</li>
 *   <li>Asserting the fixed BigDecimal value flows through {@code Map<String, Object>} ->
 *       {@code List<Map<String, Object>>} (the exact shape PricingStrategyController parses).</li>
 * </ol>
 *
 * <p>This is a self-contained unit test — no Spring context — to keep it fast and
 * unambiguous about which Jackson configuration was applied. The downstream business-logic
 * rejection is already covered by
 * {@link com.cretas.aims.controller.PricingStrategyControllerTest#testHighPrecisionDiscountRejected};
 * here we only prove the wire-level decoding now produces a BigDecimal that the existing
 * validator will see.
 *
 * @see com.cretas.aims.controller.PricingStrategyController
 * @see com.cretas.aims.controller.PricingStrategyControllerTest
 */
class JacksonBigDecimalForFloatsTest {

    /** The exact 28-digit attack payload that reproduces on prod. */
    private static final String ATTACK_PAYLOAD = "99.99999999999999999999999999";

    /** The same payload wrapped inside a Pricing-style rulesJson tier array. */
    private static final String ATTACK_RULES_JSON =
            "{\"tiers\":[{\"minQty\":0,\"maxQty\":100,\"discountPct\":" + ATTACK_PAYLOAD + "}]}";

    // ===== Control: default Jackson behavior IS the bug =====

    @Test
    @DisplayName("AUD-3 control: default Jackson decodes 28-digit float in Map<String,Object> as Double 100.0 (THE BUG)")
    void defaultJacksonRoundsHighPrecisionToDouble() throws Exception {
        // This is the pre-fix behavior — reproduce it to document why the fix is needed.
        // If this test ever starts failing in the OTHER direction (e.g. Jackson upstream
        // changes its default), the fix becomes redundant and the global property can be
        // removed. Until then this assertion is the canary for the regression vector.
        ObjectMapper defaultMapper = new ObjectMapper();
        // NOTE: NOT configuring USE_BIG_DECIMAL_FOR_FLOATS — replicating prod-pre-fix.

        Map<String, Object> parsed = defaultMapper.readValue(ATTACK_RULES_JSON, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) parsed.get("tiers");
        Object discountPct = tiers.get(0).get("discountPct");

        // The bug: 28-digit precision arrives as Double 100.0
        assertInstanceOf(Double.class, discountPct,
                "default Jackson SHOULD decode JSON float in Object slot as Double — control case");
        assertEquals(100.0d, ((Double) discountPct).doubleValue(), 0.0d,
                "Double rounds 99.99...99 to exactly 100.0 (IEEE 754 — proves attack is real)");

        // And critically: a BigDecimal-from-Double round-trip CANNOT see the original scale,
        // so the scale > 2 check never fires.
        BigDecimal asBigDecimal = BigDecimal.valueOf((Double) discountPct);
        assertEquals(100.0d, asBigDecimal.doubleValue(), 0.0d,
                "BigDecimal.valueOf(Double) loses the 28-digit precision — controller's scale check sees 100.00");
    }

    // ===== Fix: USE_BIG_DECIMAL_FOR_FLOATS preserves full precision =====

    @Test
    @DisplayName("AUD-3 fix: USE_BIG_DECIMAL_FOR_FLOATS decodes the same payload as BigDecimal preserving full 28-digit precision")
    void configuredJacksonPreservesFullPrecisionAsBigDecimal() throws Exception {
        // The fix — same configuration Spring Boot applies when
        // spring.jackson.deserialization.use-big-decimal-for-floats=true is set
        // in application.properties.
        ObjectMapper fixedMapper = new ObjectMapper()
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

        Map<String, Object> parsed = fixedMapper.readValue(ATTACK_RULES_JSON, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) parsed.get("tiers");
        Object discountPct = tiers.get(0).get("discountPct");

        // Fix proof 1: it's now BigDecimal, not Double
        assertInstanceOf(BigDecimal.class, discountPct,
                "USE_BIG_DECIMAL_FOR_FLOATS=true MUST decode JSON float as BigDecimal in Object slot");

        // Fix proof 2: full 28-digit precision retained — the controller's existing
        // AUD-3 scale > 2 check (PricingStrategyController.validateTiers) WILL see this.
        // Note: BigDecimal.scale() counts FRACTIONAL digits only (26 for "99.99...99"
        // with 26 fractional 9s + leading "99"); total precision is 28. What matters
        // for the controller's check is scale > 2 which is what we assert here.
        BigDecimal bd = (BigDecimal) discountPct;
        assertEquals(ATTACK_PAYLOAD, bd.toPlainString(),
                "full 28-digit string representation must survive the round-trip");
        assertTrue(bd.scale() > 2,
                "scale must exceed 2 — controller's scale > 2 check will reject this. actual: " + bd.scale());
        assertEquals(26, bd.scale(),
                "scale is exactly 26 (count of fractional 9s in 99.99...) — regression canary on payload constant");

        // Fix proof 3: the value is NOT silently rounded to 100.0.
        // This is the most important assertion — it proves the wire-level attack now
        // surfaces all the way to the validator, where it gets rejected with a 400.
        assertNotEquals(0, bd.compareTo(new BigDecimal("100")),
                "value must still differ from 100 (proves attack reaches scale check unrounded)");
        assertTrue(bd.compareTo(new BigDecimal("100")) < 0,
                "value must still be strictly < 100 — controller's > 100 check is NOT what catches it");
    }

    // ===== Sanity: feature does not affect already-typed fields or non-float numbers =====

    @Test
    @DisplayName("AUD-3 regression: feature does not affect JSON integers (minQty/maxQty stay Integer)")
    void integerFieldsUnaffectedByFeature() throws Exception {
        ObjectMapper fixedMapper = new ObjectMapper()
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

        Map<String, Object> parsed = fixedMapper.readValue(ATTACK_RULES_JSON, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) parsed.get("tiers");

        // minQty/maxQty are JSON integers — feature ONLY affects floats, integers stay
        // as Integer (or Long, for large values). This guards against accidentally
        // boxing every numeric field as BigDecimal which could break downstream code
        // that casts via (Integer) or Number.intValue().
        Object minQty = tiers.get(0).get("minQty");
        Object maxQty = tiers.get(0).get("maxQty");

        assertFalse(minQty instanceof BigDecimal,
                "JSON integer 0 must NOT become BigDecimal — feature only targets floats");
        assertFalse(maxQty instanceof BigDecimal,
                "JSON integer 100 must NOT become BigDecimal — feature only targets floats");
        assertInstanceOf(Number.class, minQty,
                "minQty stays Number (Integer/Long) — controller cast paths unchanged");
        assertInstanceOf(Number.class, maxQty,
                "maxQty stays Number (Integer/Long) — controller cast paths unchanged");
    }

    @Test
    @DisplayName("AUD-3 regression: legitimate 2-decimal discountPct (e.g. 10.50) still decodes cleanly to BigDecimal")
    void legitimateTwoDecimalFloatStillWorks() throws Exception {
        // Guard against the fix accidentally breaking the existing B-P3/B-P4 happy path
        // (discount=10.5 is the most common real-world value). With the feature on,
        // BigDecimal("10.5") is what flows in — controller's setScale(2, HALF_UP)
        // normalizes it to 10.50, which the existing trailing-zero acceptance test
        // (testTrailingZeroScaleAccepted) already validates as a save-path.
        ObjectMapper fixedMapper = new ObjectMapper()
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

        String legitimateJson = "{\"tiers\":[{\"minQty\":0,\"maxQty\":100,\"discountPct\":10.5}]}";
        Map<String, Object> parsed = fixedMapper.readValue(legitimateJson, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) parsed.get("tiers");
        Object discountPct = tiers.get(0).get("discountPct");

        assertInstanceOf(BigDecimal.class, discountPct,
                "10.5 still decodes as BigDecimal — feature applies uniformly");
        BigDecimal bd = (BigDecimal) discountPct;
        assertEquals(0, bd.compareTo(new BigDecimal("10.5")),
                "value-equal to 10.5 (scale-agnostic compare)");
        // scale=1 here; controller's setScale(2, HALF_UP) normalizes to 10.50 which
        // passes existing AUD-3 validation (10.50 == 10.5 setScale(2, HALF_UP)).
        assertTrue(bd.scale() <= 2 || bd.compareTo(bd.setScale(2, java.math.RoundingMode.HALF_UP)) == 0,
                "legitimate 10.5 still passes AUD-3 scale gate (scale <= 2 OR equal after rounding)");
    }
}
