package com.cretas.aims.controller;

import com.cretas.aims.controller.restaurant.StocktakingRecordController;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Length-validation tests for {@link StocktakingRecordController} — AUD-5 B-A3 sister sweep
 * batch 3 (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link StocktakingRecordRepository}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Only {@code unit} (VARCHAR 20) has a bounded user-input column.
 * {@code adjustmentReason} and {@code notes} are PG TEXT (unbounded) so no length
 * pre-check is needed.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 3)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StocktakingRecordController AUD-5 B-A3 length pre-check")
class StocktakingRecordControllerTest {

    @Mock StocktakingRecordRepository stocktakingRepository;
    @InjectMocks StocktakingRecordController controller;

    // ==================== AUD-5 B-A3: create.unit > 20 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.unit > 20 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongUnitOnCreateRejected() {
        // PG column: stocktaking_records.unit VARCHAR(20). Pre-fix, a 21-char input let the
        // request reach PG and surfaced as DataIntegrityViolationException → 409.
        StocktakingRecord record = newBaseRecord();
        record.setUnit("X".repeat(21));   // 21 chars, max is 20

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, record));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("计量单位"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("20"));
        assertTrue(ex.getMessage().contains("21"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("unit", ex.getHintTarget(), "4-in-1 UX (d): hintTarget = unit");
        verify(stocktakingRepository, never()).save(any(StocktakingRecord.class));
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: create.unit at exactly 20 字符 accepted")
    void testMaxLengthUnitAccepted() {
        StocktakingRecord record = newBaseRecord();
        record.setUnit("X".repeat(20));   // exactly at limit

        // Stub out the in-progress check (returns false, allows save) + count + save.
        when(stocktakingRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                any(), any(), any())).thenReturn(false);
        when(stocktakingRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(stocktakingRepository.save(any(StocktakingRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<StocktakingRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        verify(stocktakingRepository).save(any(StocktakingRecord.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: null unit tolerated (optional field)")
    void testNullUnitAccepted() {
        StocktakingRecord record = newBaseRecord();
        record.setUnit(null);

        when(stocktakingRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                any(), any(), any())).thenReturn(false);
        when(stocktakingRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(stocktakingRepository.save(any(StocktakingRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<StocktakingRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        verify(stocktakingRepository).save(any(StocktakingRecord.class));
    }

    // ==================== Helpers ====================

    private StocktakingRecord newBaseRecord() {
        StocktakingRecord record = new StocktakingRecord();
        record.setRawMaterialTypeId("MAT-001");
        record.setStocktakingDate(LocalDate.now());
        record.setUnit("kg");
        return record;
    }
}
