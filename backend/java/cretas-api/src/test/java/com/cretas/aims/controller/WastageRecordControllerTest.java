package com.cretas.aims.controller;

import com.cretas.aims.controller.restaurant.WastageRecordController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
 * Length-validation tests for {@link WastageRecordController} — AUD-5 B-A3 sister sweep
 * batch 4b (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link WastageRecordRepository}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Only {@code unit} (VARCHAR 20) has a bounded user-input column.
 * {@code reason} and {@code notes} are PG TEXT (unbounded) so no length pre-check
 * is needed.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 4b)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WastageRecordController AUD-5 B-A3 length pre-check")
class WastageRecordControllerTest {

    @Mock WastageRecordRepository wastageRepository;
    @Mock com.cretas.aims.service.restaurant.WastageRecordService wastageRecordService;
    @InjectMocks WastageRecordController controller;

    // ==================== AUD-5 B-A3: create.unit > 20 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.unit > 20 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongUnitOnCreateRejected() {
        // PG column: wastage_records.unit VARCHAR(20). Pre-fix, a 21-char input let the
        // request reach PG and surfaced as DataIntegrityViolationException → 409.
        WastageRecord record = newBaseRecord();
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
        verify(wastageRepository, never()).save(any(WastageRecord.class));
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: create.unit at exactly 20 字符 accepted")
    void testMaxLengthUnitAccepted() {
        WastageRecord record = newBaseRecord();
        record.setUnit("X".repeat(20));   // exactly at limit

        when(wastageRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(wastageRepository.save(any(WastageRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WastageRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        verify(wastageRepository).save(any(WastageRecord.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: null unit tolerated (optional field)")
    void testNullUnitAccepted() {
        WastageRecord record = newBaseRecord();
        record.setUnit(null);

        when(wastageRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(wastageRepository.save(any(WastageRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WastageRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        verify(wastageRepository).save(any(WastageRecord.class));
    }

    // ==================== Wave2: section_code 校验 (防呆 Rule 3) ====================

    @Test
    @DisplayName("Wave2: create.sectionCode 非法值 → 400 with hintTarget=sectionCode (非脏数据落库)")
    void testInvalidSectionCodeRejected() {
        WastageRecord record = newBaseRecord();
        record.setSectionCode("KITCHEN");   // not in enum

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, record));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("档口"), "message must reference 档口: " + ex.getMessage());
        assertEquals("sectionCode", ex.getHintTarget(), "4-in-1 UX (d): hintTarget = sectionCode");
        verify(wastageRepository, never()).save(any(WastageRecord.class));
    }

    @Test
    @DisplayName("Wave2: create.sectionCode 合法值 (SEAFOOD) + operatorId 接受并落库")
    void testValidSectionCodeAndOperatorAccepted() {
        WastageRecord record = newBaseRecord();
        record.setSectionCode("SEAFOOD");
        record.setOperatorId(42L);

        when(wastageRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(wastageRepository.save(any(WastageRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WastageRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        assertEquals("SEAFOOD", resp.getData().getSectionCode());
        assertEquals(42L, resp.getData().getOperatorId());
        verify(wastageRepository).save(any(WastageRecord.class));
    }

    @Test
    @DisplayName("Wave2: create.sectionCode null tolerated (档口可选)")
    void testNullSectionCodeAccepted() {
        WastageRecord record = newBaseRecord();
        record.setSectionCode(null);

        when(wastageRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(wastageRepository.save(any(WastageRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WastageRecord> resp = controller.create("F001", 1L, record);
        assertNotNull(resp);
        verify(wastageRepository).save(any(WastageRecord.class));
    }

    // ==================== Helpers ====================

    private WastageRecord newBaseRecord() {
        WastageRecord record = new WastageRecord();
        record.setRawMaterialTypeId("MAT-001");
        record.setType(WastageRecord.WastageType.EXPIRED);
        record.setQuantity(new BigDecimal("1.5"));
        record.setWastageDate(LocalDate.now());
        record.setUnit("kg");
        return record;
    }
}
