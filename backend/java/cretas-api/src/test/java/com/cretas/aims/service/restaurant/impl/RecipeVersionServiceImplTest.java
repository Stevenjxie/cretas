package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.entity.restaurant.RecipeVersion;
import com.cretas.aims.entity.restaurant.RecipeVersion.VersionStatus;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.repository.restaurant.RecipeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RecipeVersionServiceImpl unit test (#60 Phase 2 配方版本化).
 *
 * <p>Covers the full state machine: createDraft → approve (with supersede), reject,
 * getCurrentApproved, idempotent re-approve (409), and the first-version no-supersede path.
 * Mirrors the {@code bom.BomVersionServiceImplApproveTest} structure (#724).
 *
 * @author Cretas Team / #60 Phase 2
 * @since 2026-06-04
 */
@DisplayName("RecipeVersionServiceImpl — #60 Phase 2 配方版本化 state machine")
@ExtendWith(MockitoExtension.class)
class RecipeVersionServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String DISH_ID = "prod-type-bailu-zhushe";
    private static final String NEW_ID = "new-version-uuid";
    private static final String PRIOR_ID = "prior-approved-uuid";
    private static final Long CREATOR = 100L;
    private static final Long APPROVER = 999L;

    @Mock
    private RecipeVersionRepository versionRepo;

    @Mock
    private RecipeRepository recipeRepo;

    @InjectMocks
    private RecipeVersionServiceImpl service;

    private Recipe recipeRow;

    @BeforeEach
    void setUp() {
        recipeRow = new Recipe();
        recipeRow.setId("recipe-row-1");
        recipeRow.setFactoryId(FACTORY);
        recipeRow.setProductTypeId(DISH_ID);
        recipeRow.setRawMaterialTypeId("rm-pig-tongue");
        recipeRow.setStandardQuantity(new BigDecimal("0.2000"));
        recipeRow.setUnit("kg");
        recipeRow.setIsMainIngredient(true);
        recipeRow.setIsActive(true);
    }

    // ── createDraft ──

    @Test
    @DisplayName("createDraft snapshots active recipe rows, assigns version max+1")
    void createDraft_snapshotsRecipesAndAssignsVersion() {
        when(recipeRepo.findActiveByFactoryIdAndProductTypeId(FACTORY, DISH_ID))
                .thenReturn(List.of(recipeRow));
        when(versionRepo.findMaxVersionNumber(FACTORY, DISH_ID)).thenReturn(2);
        when(versionRepo.save(any(RecipeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipeVersion draft = service.createDraft(FACTORY, DISH_ID, CREATOR);

        assertEquals(VersionStatus.DRAFT, draft.getStatus());
        assertEquals(3, draft.getVersionNumber(), "version = max(2)+1");
        assertEquals(CREATOR, draft.getCreatedBy());
        assertEquals(DISH_ID, draft.getProductTypeId());
        assertNotNull(draft.getSnapshotJson());
        assertEquals(DISH_ID, draft.getSnapshotJson().get("productTypeId"));
        assertEquals(1, draft.getSnapshotJson().get("itemCount"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) draft.getSnapshotJson().get("items");
        assertEquals(1, items.size(), "snapshot captures the 1 active recipe row");
    }

    @Test
    @DisplayName("createDraft throws when the dish has no active recipe rows")
    void createDraft_noRecipes_throws() {
        when(recipeRepo.findActiveByFactoryIdAndProductTypeId(FACTORY, DISH_ID))
                .thenReturn(List.of());
        assertThrows(EntityNotFoundException.class,
                () -> service.createDraft(FACTORY, DISH_ID, CREATOR));
        verify(versionRepo, never()).save(any());
    }

    // ── approve: supersede prior APPROVED ──

    @Test
    @DisplayName("approve() supersedes the prior APPROVED version in the same transaction (flush ordering)")
    void approve_supersedesPriorApproved() {
        RecipeVersion pending = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(2)
                .status(VersionStatus.PENDING_APPROVAL).build();
        RecipeVersion prior = RecipeVersion.builder()
                .id(PRIOR_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.APPROVED)
                .effectiveFrom(LocalDate.now().minusDays(30)).effectiveTo(null).build();

        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(pending));
        when(versionRepo.findCurrentInStatus(FACTORY, DISH_ID, VersionStatus.APPROVED))
                .thenReturn(Optional.of(prior));
        when(versionRepo.save(any(RecipeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipeVersion approved = service.approve(FACTORY, NEW_ID, APPROVER);

        assertEquals(VersionStatus.APPROVED, approved.getStatus());
        assertEquals(LocalDate.now(), approved.getEffectiveFrom());
        assertNull(approved.getEffectiveTo());
        assertEquals(APPROVER, approved.getApprovedBy());
        assertNotNull(approved.getApprovedAt());

        // prior OBSOLETEd with effective_to = today-1
        assertEquals(VersionStatus.OBSOLETE, prior.getStatus());
        assertEquals(LocalDate.now().minusDays(1), prior.getEffectiveTo());

        // ordering: prior saved + flushed BEFORE new row save
        ArgumentCaptor<RecipeVersion> cap = ArgumentCaptor.forClass(RecipeVersion.class);
        verify(versionRepo, times(2)).save(cap.capture());
        assertEquals(PRIOR_ID, cap.getAllValues().get(0).getId(), "FIRST save = prior (OBSOLETE)");
        assertEquals(NEW_ID, cap.getAllValues().get(1).getId(), "SECOND save = new (APPROVED)");
        verify(versionRepo, times(1)).flush();
    }

    @Test
    @DisplayName("approve() of first version (no prior APPROVED) skips supersede + flush")
    void approve_firstVersion_noSupersede() {
        RecipeVersion pending = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.DRAFT).build();   // DRAFT fast-path approve
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(pending));
        when(versionRepo.findCurrentInStatus(FACTORY, DISH_ID, VersionStatus.APPROVED))
                .thenReturn(Optional.empty());
        when(versionRepo.save(any(RecipeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipeVersion approved = service.approve(FACTORY, NEW_ID, APPROVER);

        assertEquals(VersionStatus.APPROVED, approved.getStatus());
        verify(versionRepo, never()).flush();
        verify(versionRepo, times(1)).save(any(RecipeVersion.class));
    }

    @Test
    @DisplayName("approve() of an already-APPROVED row throws 409 (Rule 4 idempotency)")
    void approve_alreadyApproved_throwsConflict() {
        RecipeVersion already = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.APPROVED).effectiveTo(null).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(already));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.approve(FACTORY, NEW_ID, APPROVER));
        assertTrue(ex.getMessage().contains("already APPROVED"));
        verify(versionRepo, never()).save(any());
        verify(versionRepo, never()).flush();
    }

    @Test
    @DisplayName("approve() from terminal status (REJECTED) throws")
    void approve_fromRejected_throws() {
        RecipeVersion rejected = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.REJECTED).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(rejected));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.approve(FACTORY, NEW_ID, APPROVER));
        assertTrue(ex.getMessage().contains("REJECTED"));
        verify(versionRepo, never()).save(any());
    }

    // ── submitForApproval ──

    @Test
    @DisplayName("submitForApproval moves DRAFT → PENDING_APPROVAL")
    void submit_draftToPending() {
        RecipeVersion draft = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.DRAFT).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(draft));
        when(versionRepo.save(any(RecipeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipeVersion submitted = service.submitForApproval(FACTORY, NEW_ID);
        assertEquals(VersionStatus.PENDING_APPROVAL, submitted.getStatus());
    }

    @Test
    @DisplayName("submitForApproval from non-DRAFT throws")
    void submit_fromApproved_throws() {
        RecipeVersion approved = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.APPROVED).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(approved));
        assertThrows(IllegalStateException.class, () -> service.submitForApproval(FACTORY, NEW_ID));
    }

    // ── reject ──

    @Test
    @DisplayName("reject() moves PENDING_APPROVAL → REJECTED with reason")
    void reject_pendingToRejected() {
        RecipeVersion pending = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.PENDING_APPROVAL).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(pending));
        when(versionRepo.save(any(RecipeVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipeVersion rejected = service.reject(FACTORY, NEW_ID, APPROVER, "成本核算有误, 退回修改");
        assertEquals(VersionStatus.REJECTED, rejected.getStatus());
        assertEquals("成本核算有误, 退回修改", rejected.getRejectionReason());
        assertEquals(APPROVER, rejected.getApprovedBy());
    }

    @Test
    @DisplayName("reject() from DRAFT (not PENDING) throws")
    void reject_fromDraft_throws() {
        RecipeVersion draft = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.DRAFT).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(draft));
        assertThrows(IllegalStateException.class,
                () -> service.reject(FACTORY, NEW_ID, APPROVER, "x"));
    }

    // ── getCurrentApproved / getById ──

    @Test
    @DisplayName("getCurrentApproved delegates to findCurrentInStatus(APPROVED)")
    void getCurrentApproved_delegates() {
        RecipeVersion approved = RecipeVersion.builder()
                .id(NEW_ID).factoryId(FACTORY).productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.APPROVED).effectiveTo(null).build();
        when(versionRepo.findCurrentInStatus(FACTORY, DISH_ID, VersionStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        Optional<RecipeVersion> got = service.getCurrentApproved(FACTORY, DISH_ID);
        assertTrue(got.isPresent());
        assertEquals(NEW_ID, got.get().getId());
    }

    @Test
    @DisplayName("getById rejects cross-factory access (wrong factory → not found)")
    void getById_wrongFactory_throws() {
        RecipeVersion other = RecipeVersion.builder()
                .id(NEW_ID).factoryId("F001").productTypeId(DISH_ID).versionNumber(1)
                .status(VersionStatus.DRAFT).build();
        when(versionRepo.findById(NEW_ID)).thenReturn(Optional.of(other));
        assertThrows(EntityNotFoundException.class, () -> service.getById(FACTORY, NEW_ID));
    }
}
