package com.cretas.aims.controller;

import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Length-validation tests for {@code PurchaseRequisitionController} — AUD-5 B-A3 sister sweep
 * batch 3 (edge audit 2026-05-20).
 *
 * <p><b>Coverage note</b>: this controller is intentionally exempt from the controller-side
 * {@code validateXxxLengths} pre-check pattern because:
 * <ul>
 *   <li>{@link CreatePurchaseRequisitionRequest} DTO already declares {@code @Size} on
 *       {@code requesterDeptId} (64), {@code reason} (5000), {@code remark} (5000) — these
 *       are caught by Bean Validation via {@code @Valid} + handled by
 *       {@code GlobalExceptionHandler.handleValidationException} producing 400 + hintTarget.</li>
 *   <li>The entity-level columns {@code reason} and {@code remark} are PG TEXT (unbounded),
 *       so there is no DB-level cap to enforce.</li>
 *   <li>The {@code reject} endpoint takes {@code rejectionReason} (PG TEXT, unbounded) — no cap.</li>
 *   <li>The {@code convertToPO} endpoint takes {@code supplierId} (lookup key, not user text).</li>
 * </ul>
 *
 * <p>These tests verify the DTO {@code @Size} constraints fire correctly, providing regression
 * coverage even though the controller itself adds no new validators in this PR.
 *
 * <p>Mirrors PR #48 / PR #76 / PR #78 pattern (delegating to DTO when DTO already covers it).
 *
 * @since 2026-05-20 (AUD-5 B-A3 sister sweep batch 3)
 */
@DisplayName("PurchaseRequisitionController AUD-5 B-A3 — DTO @Size coverage")
class PurchaseRequisitionControllerTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ==================== AUD-5 B-A3 (DTO-level): requesterDeptId > 64 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3 (DTO-level): requesterDeptId > 64 字符 → @Size violation")
    void testLongRequesterDeptIdViolatesSize() {
        CreatePurchaseRequisitionRequest req = newBaseRequest();
        req.setRequesterDeptId("X".repeat(65));   // exceeds @Size(max=64)

        Set<ConstraintViolation<CreatePurchaseRequisitionRequest>> violations = validator.validate(req);

        // At least one violation must reference the DTO @Size message for this field
        assertFalse(violations.isEmpty(),
                "65-char requesterDeptId must trigger @Size(max=64) violation");
        assertTrue(violations.stream().anyMatch(v ->
                "requesterDeptId".equals(v.getPropertyPath().toString())),
                "violation must target requesterDeptId, got: " + violations);
    }

    // ==================== AUD-5 B-A3 (DTO-level): reason > 5000 chars ====================

    @Test
    @DisplayName("AUD-5 B-A3 (DTO-level): reason > 5000 字符 → @Size violation (DB column is TEXT, DTO caps for sanity)")
    void testLongReasonViolatesSize() {
        CreatePurchaseRequisitionRequest req = newBaseRequest();
        req.setReason("X".repeat(5001));

        Set<ConstraintViolation<CreatePurchaseRequisitionRequest>> violations = validator.validate(req);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v ->
                "reason".equals(v.getPropertyPath().toString())),
                "violation must target reason, got: " + violations);
    }

    // ==================== Boundary: at @Size limit ====================

    @Test
    @DisplayName("AUD-5 B-A3 boundary: requesterDeptId at exactly 64 字符 accepted")
    void testMaxLengthRequesterDeptIdAccepted() {
        CreatePurchaseRequisitionRequest req = newBaseRequest();
        req.setRequesterDeptId("X".repeat(64));

        Set<ConstraintViolation<CreatePurchaseRequisitionRequest>> violations = validator.validate(req);

        // Only requesterDeptId-related violation matters; ignore other fields
        assertTrue(violations.stream().noneMatch(v ->
                "requesterDeptId".equals(v.getPropertyPath().toString())),
                "exactly 64 chars must NOT trigger @Size(max=64) violation");
    }

    // ==================== Helpers ====================

    private CreatePurchaseRequisitionRequest newBaseRequest() {
        CreatePurchaseRequisitionRequest req = new CreatePurchaseRequisitionRequest();
        // requestedItems non-empty required by @NotEmpty
        req.setRequestedItems(List.of(new HashMap<>(java.util.Map.of(
                "materialTypeId", "MAT-001",
                "materialName", "原料A",
                "quantity", 10,
                "unit", "kg"
        ))));
        req.setReason("正常请购");
        return req;
    }
}
