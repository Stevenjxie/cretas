package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUD-7 / B-P1 follow-up (QA 2026-05-19): PR #49 fixed only the {@code "2026"} (incomplete)
 * input via {@code @JsonFormat(pattern="yyyy-MM-dd")}. Independent QA caught 3 still-broken
 * shapes returning generic {@code "请求格式不正确"} instead of a specific hint:
 * <ul>
 *   <li>{@code "2026-05-01T00:00:00"} (full ISO with T suffix)</li>
 *   <li>{@code "2026-05-01+08:00"} (timezone offset)</li>
 *   <li>{@code "2023-02-29"} (proper format but invalid leap day)</li>
 * </ul>
 *
 * <p>Root cause: {@code e.getMessage()}-based regex misses these because Spring's wrapper does
 * NOT inline {@code DateTimeParseException} class name and the regex needs
 * {@code "could not be parsed at index"} which JSR310 only emits for {@code at index 0} truly
 * unparseable text. The fix walks {@link Throwable#getCause()} chain to find the inner
 * {@link DateTimeParseException} / {@link InvalidFormatException} and classifies by value shape.
 *
 * <p>Direct handler instantiation, no Spring context — ~2s suite.
 */
@DisplayName("AUD-7: GlobalExceptionHandler date-parse cause-chain routing")
class GlobalExceptionHandlerDateParseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Build an HttpMessageNotReadableException with the given cause (no message override). */
    private HttpMessageNotReadableException wrap(Throwable cause) {
        // 1-arg ctor with msg-only is deprecated; 2-arg requires HttpInputMessage. Use 3-arg
        // (msg, cause, httpInputMessage) which is the non-deprecated public constructor.
        return new HttpMessageNotReadableException(
                "JSON parse error: " + cause.getMessage(),
                cause,
                new MockHttpInputMessage(new byte[0]));
    }

    // ===== T-suffix (full ISO datetime) =====

    @Test
    @DisplayName("T-suffix '2026-05-01T00:00:00' → specific hint to drop time part")
    void testTSuffixDateGivesSpecificHint() {
        String bad = "2026-05-01T00:00:00";
        DateTimeParseException dtpe;
        try {
            LocalDate.parse(bad);  // ISO_LOCAL_DATE — throws on T-suffix
            fail("LocalDate.parse should throw on T-suffix input");
            return;
        } catch (DateTimeParseException ex) {
            dtpe = ex;
        }

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(dtpe));

        assertEquals((Integer) 400, resp.getCode());
        assertEquals(Boolean.FALSE, resp.getSuccess());
        assertEquals("VALIDATION", resp.getErrorCode(), "errorCode must be VALIDATION");
        assertTrue(resp.getMessage() != null && resp.getMessage().contains("日期格式不正确"),
                "message should signal date format error: " + resp.getMessage());
        assertTrue(resp.getMessage().contains(bad),
                "message should surface the offending value: " + resp.getMessage());
        assertNotNull(resp.getActionHint(), "actionHint required for 4-in-1 UX");
        assertTrue(resp.getActionHint().contains("去掉时间")
                        || resp.getActionHint().contains("T 时间后缀"),
                "actionHint should explain T-suffix removal, got: " + resp.getActionHint());
        assertEquals("warning", resp.getSeverity());
    }

    // ===== Timezone offset =====

    @Test
    @DisplayName("TZ offset '2026-05-01+08:00' → specific hint to drop offset")
    void testTimezoneOffsetGivesSpecificHint() {
        String bad = "2026-05-01+08:00";
        DateTimeParseException dtpe;
        try {
            LocalDate.parse(bad);
            fail("LocalDate.parse should throw on TZ-offset input");
            return;
        } catch (DateTimeParseException ex) {
            dtpe = ex;
        }

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(dtpe));

        assertEquals((Integer) 400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("日期格式不正确"));
        assertTrue(resp.getMessage().contains(bad),
                "message should surface the offending value: " + resp.getMessage());
        assertNotNull(resp.getActionHint());
        assertTrue(resp.getActionHint().contains("去掉时区")
                        || resp.getActionHint().contains("时区偏移"),
                "actionHint should explain TZ-offset removal, got: " + resp.getActionHint());
        assertEquals("warning", resp.getSeverity());
    }

    // ===== Invalid leap day =====

    @Test
    @DisplayName("Invalid leap '2023-02-29' → specific hint 'invalid day/month'")
    void testInvalidLeapDayGivesSpecificHint() {
        String bad = "2023-02-29";
        DateTimeParseException dtpe;
        try {
            LocalDate.parse(bad);  // 2023 non-leap year, 2-29 invalid
            fail("LocalDate.parse should throw on non-leap-year Feb 29");
            return;
        } catch (DateTimeParseException ex) {
            dtpe = ex;
        }

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(dtpe));

        assertEquals((Integer) 400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("日期格式不正确"));
        assertTrue(resp.getMessage().contains(bad),
                "message should surface the offending value: " + resp.getMessage());
        assertNotNull(resp.getActionHint());
        assertTrue(resp.getActionHint().contains("无效")
                        || resp.getActionHint().contains("月份/日期"),
                "actionHint should explain invalid date value, got: " + resp.getActionHint());
        assertEquals("warning", resp.getSeverity());
    }

    // ===== Incomplete year-only (PR #49 originally fixed case, must still work) =====

    @Test
    @DisplayName("Incomplete '2026' → specific hint (fallback message format)")
    void testIncompleteYearOnlyGivesSpecificHint() {
        // PR #49 routed this via @JsonFormat(pattern="yyyy-MM-dd") → InvalidFormatException
        // with targetType=LocalDate. The new cause-chain walk should ALSO catch it.
        InvalidFormatException ife = InvalidFormatException.from(
                null,  // JsonParser — not needed for our extraction
                "Cannot deserialize value of type `java.time.LocalDate` from String \"2026\"",
                "2026",
                LocalDate.class);

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(ife));

        assertEquals((Integer) 400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("日期格式不正确"));
        assertTrue(resp.getMessage().contains("2026"));
        assertNotNull(resp.getActionHint());
        // "2026" doesn't match the matches() check for yyyy-MM-dd, no T, no +/Z → fallback hint
        assertTrue(resp.getActionHint().contains("yyyy-MM-dd"),
                "incomplete year should get the generic format hint, got: " + resp.getActionHint());
    }

    // ===== Valid date control — must NOT be intercepted as date-error =====

    @Test
    @DisplayName("Non-date HttpMessageNotReadable (e.g. malformed JSON) → generic message")
    void testNonDateErrorStillGenericMessage() {
        // Pure JSON-shape error with no inner DateTimeParseException — must fall through to the
        // legacy "请求格式不正确" generic message, NOT date-specific.
        // Use a plain RuntimeException as cause to avoid Jackson constructor version churn
        // (JsonParseException constructors differ across Jackson 2.13/2.14/2.15+).
        RuntimeException jpe = new RuntimeException(
                "Unexpected character ('}' (code 125))");

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(jpe));

        assertEquals((Integer) 400, resp.getCode());
        // Should NOT be routed to date error
        assertTrue(resp.getMessage() != null && !resp.getMessage().contains("日期格式不正确"),
                "non-date JSON error must not be classified as date error: " + resp.getMessage());
    }

    // ===== Cause chain depth — DateTimeParseException nested 2 levels =====

    @Test
    @DisplayName("Nested DateTimeParseException (depth=2) still detected via walk")
    void testNestedCauseChainStillDetected() {
        DateTimeParseException dtpe = new DateTimeParseException(
                "Text '2026-05-01T00:00:00' could not be parsed, unparsed text found at index 10",
                "2026-05-01T00:00:00",
                10);
        // Wrap once (JsonMappingException via static factory) then again (HttpMessageNotReadableException).
        // JsonMappingException.from(JsonParser, String, Throwable) is the non-deprecated factory.
        JsonMappingException jme = JsonMappingException.from(
                (com.fasterxml.jackson.core.JsonParser) null,
                "Failed to deserialize LocalDate",
                dtpe);

        ApiResponse<?> resp = handler.handleHttpMessageNotReadableException(wrap(jme));

        assertEquals((Integer) 400, resp.getCode());
        assertEquals("VALIDATION", resp.getErrorCode(),
                "nested DateTimeParseException must still be detected via cause walk");
        assertNotNull(resp.getActionHint());
        assertTrue(resp.getActionHint().contains("去掉时间")
                        || resp.getActionHint().contains("T 时间后缀"),
                "nested case must still route to T-suffix hint, got: " + resp.getActionHint());
    }
}
