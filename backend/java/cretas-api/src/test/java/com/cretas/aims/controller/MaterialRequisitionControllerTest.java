package com.cretas.aims.controller;

import com.cretas.aims.controller.restaurant.MaterialRequisitionController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
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
 * Length-validation tests for {@link MaterialRequisitionController} — AUD-5 B-A3 sister sweep
 * batch 4b (edge audit 2026-05-20).
 *
 * <p>Each test asserts that an over-length string field is rejected with a specific
 * Chinese message + 4-in-1 UX hint (per {@code .claude/rules/fool-proof-design.md}
 * cross-cutting rule) BEFORE the request reaches {@link MaterialRequisitionRepository}
 * — guaranteeing PG VARCHAR overflow can't surface as generic 409 "数据处理异常".
 *
 * <p>Only {@code unit} (VARCHAR 20) has a bounded user-input column.
 * {@code notes} is PG TEXT (unbounded, used as reject reason) so no length pre-check
 * is needed.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 4b)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialRequisitionController AUD-5 B-A3 length pre-check")
class MaterialRequisitionControllerTest {

    @Mock MaterialRequisitionRepository requisitionRepository;
    @InjectMocks MaterialRequisitionController controller;

    // ==================== AUD-5 B-A3: create.unit > 20 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3: create.unit > 20 字符 → 400 with 4-in-1 hint (非 PG 409)")
    void testLongUnitOnCreateRejected() {
        // PG column: material_requisitions.unit VARCHAR(20). Pre-fix, a 21-char input let the
        // request reach PG and surfaced as DataIntegrityViolationException → 409.
        MaterialRequisition req = newBaseRequisition();
        req.setUnit("X".repeat(21));   // 21 chars, max is 20

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create("F001", 1L, req));

        assertEquals(400, ex.getCode(), "must be 400, not 409 PG overflow");
        assertTrue(ex.getMessage().contains("计量单位"),
                "message must reference field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("20"));
        assertTrue(ex.getMessage().contains("21"));
        assertNotNull(ex.getActionHint(), "4-in-1 UX (a): actionHint required");
        assertEquals("warning", ex.getSeverity(), "4-in-1 UX (c): severity warning");
        assertEquals("unit", ex.getHintTarget(), "4-in-1 UX (d): hintTarget = unit");
        verify(requisitionRepository, never()).save(any(MaterialRequisition.class));
    }

    // ==================== Boundary: exactly at limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: create.unit at exactly 20 字符 accepted")
    void testMaxLengthUnitAccepted() {
        MaterialRequisition req = newBaseRequisition();
        req.setUnit("X".repeat(20));   // exactly at limit

        when(requisitionRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(requisitionRepository.save(any(MaterialRequisition.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<MaterialRequisition> resp = controller.create("F001", 1L, req);
        assertNotNull(resp);
        verify(requisitionRepository).save(any(MaterialRequisition.class));
    }

    @Test
    @DisplayName("AUD-5 B-A3: null unit tolerated (optional field)")
    void testNullUnitAccepted() {
        MaterialRequisition req = newBaseRequisition();
        req.setUnit(null);

        when(requisitionRepository.countByFactoryIdAndDate(any(), any())).thenReturn(0L);
        when(requisitionRepository.save(any(MaterialRequisition.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<MaterialRequisition> resp = controller.create("F001", 1L, req);
        assertNotNull(resp);
        verify(requisitionRepository).save(any(MaterialRequisition.class));
    }

    // ==================== Helpers ====================

    private MaterialRequisition newBaseRequisition() {
        MaterialRequisition req = new MaterialRequisition();
        req.setType(MaterialRequisition.RequisitionType.MANUAL);
        req.setRawMaterialTypeId("MAT-001");
        req.setRequestedQuantity(new BigDecimal("5.0"));
        req.setRequisitionDate(LocalDate.now());
        req.setUnit("kg");
        return req;
    }
}
