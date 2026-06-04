package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.RecipeVersion;

import java.util.List;
import java.util.Optional;

/**
 * RecipeVersionService (#60 Phase 2 配方版本化).
 *
 * <p>Independent row-per-approval companion to {@link RecipeService}. A dish's complete
 * recipe (multiple flat {@link com.cretas.aims.entity.restaurant.Recipe} rows) is frozen
 * into a {@code snapshot_json} at approval time. The flat rows have no versioning today.
 *
 * <p>State machine: {@code DRAFT → PENDING_APPROVAL → APPROVED → OBSOLETE}. Reject sets
 * {@code REJECTED} (terminal). DB-level partial unique {@code uq_rv_one_approved_per_dish}
 * provides defense-in-depth; service layer also performs explicit supersede in approve()
 * (mirrors bom.BomVersion #724 fix — supersede prior APPROVED + flush BEFORE writing the
 * new APPROVED row so the partial-unique index never conflicts).
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
public interface RecipeVersionService {

    /**
     * Create a DRAFT RecipeVersion snapshotting the dish's current active {@link
     * com.cretas.aims.entity.restaurant.Recipe} rows. versionNumber = max+1 for that dish.
     */
    RecipeVersion createDraft(String factoryId, String productTypeId, Long createdBy);

    /** DRAFT → PENDING_APPROVAL. */
    RecipeVersion submitForApproval(String factoryId, String versionId);

    /**
     * PENDING_APPROVAL (or DRAFT fast-path) → APPROVED. Sets effective_from=today, approvedBy,
     * approvedAt. Explicitly supersedes the prior APPROVED+effective version (effective_to=today-1,
     * status=OBSOLETE) BEFORE promoting this row, then flushes, to satisfy the partial-unique
     * index. Idempotent re-approve of the already-current row does NOT self-supersede.
     *
     * <p>Rule 4 防呆 (idempotency): re-approving a row already in APPROVED throws {@code
     * IllegalStateException} (controller maps to 409) — caller should query getCurrentApproved.
     */
    RecipeVersion approve(String factoryId, String versionId, Long approverId);

    /** PENDING_APPROVAL → REJECTED. Sets rejectionReason. Terminal — must createDraft anew. */
    RecipeVersion reject(String factoryId, String versionId, Long approverId, String reason);

    /** Current effective version (status=APPROVED + effective_to IS NULL). */
    Optional<RecipeVersion> getCurrentApproved(String factoryId, String productTypeId);

    /** All versions for a dish, newest first. */
    List<RecipeVersion> getHistory(String factoryId, String productTypeId);

    /** Lookup by id. Throws {@code EntityNotFoundException} if absent / wrong factory. */
    RecipeVersion getById(String factoryId, String versionId);
}
