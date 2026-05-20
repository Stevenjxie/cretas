package com.cretas.aims.service;

import com.cretas.aims.entity.OpportunityStageHistory;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.InvalidStageTransitionException;
import com.cretas.aims.repository.OpportunityStageHistoryRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 7 wave 2 T4 — SalesOpportunityService 单元测试.
 *
 * <p>覆盖:
 * <ul>
 *   <li>create 默认 LEAD + default probability 10 + history initial</li>
 *   <li>transitionStage forward by 1 (LEAD → QUALIFIED) 成功, probability 自动 30</li>
 *   <li>transitionStage backward (PROPOSAL → QUALIFIED) reason 必填</li>
 *   <li>transitionStage skip-forward (LEAD → PROPOSAL) 不带 confirmSkip → 抛异常</li>
 *   <li>transitionStage skip-forward 带 confirmSkip + reason → 成功</li>
 *   <li>transitionStage same-stage (LEAD → LEAD) → 抛 InvalidStageTransitionException</li>
 *   <li>transitionStage CLOSED_WON → LEAD 重激活 reason 必填</li>
 *   <li>transitionStage forward to CLOSED_WON → 自动设 closedAt + probability 100</li>
 *   <li>transitionStage probabilityOverride 手动覆盖</li>
 *   <li>history audit row 写入</li>
 * </ul>
 */
@DisplayName("SalesOpportunityService unit tests (Sprint 7 wave 2 T4)")
@ExtendWith(MockitoExtension.class)
class SalesOpportunityServiceTest {

    @Mock
    private SalesOpportunityRepository repository;

    @Mock
    private OpportunityStageHistoryRepository historyRepository;

    private SalesOpportunityService service;

    private static final String FACTORY = "F006";
    private static final String CUSTOMER = "CUST-001";
    private static final Long OWNER_ID = 100L;
    private static final Long CHANGED_BY = 200L;

    @BeforeEach
    void setUp() {
        service = new SalesOpportunityService(repository, historyRepository);
    }

    /** Helper: build an existing opportunity in a given stage. */
    private SalesOpportunity existingOpp(String id, OpportunityStage stage, int probability) {
        return SalesOpportunity.builder()
                .id(id)
                .factoryId(FACTORY)
                .customerId(CUSTOMER)
                .title("叮咚好食光卤猪蹄合作")
                .stage(stage)
                .probability(probability)
                .valueAmount(new BigDecimal("100000.00"))
                .ownerId(OWNER_ID)
                .createdBy(OWNER_ID)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("create with no initialStage defaults to LEAD with probability=10 + writes initial history")
    void create_defaultsToLeadStage() {
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> {
            SalesOpportunity arg = inv.getArgument(0);
            arg.setId("opp-1");
            return arg;
        });

        SalesOpportunity saved = service.create(FACTORY, CUSTOMER, "新合作机会",
                null, new BigDecimal("50000.00"), null, LocalDate.now().plusMonths(3),
                OWNER_ID, "描述", OWNER_ID);

        assertNotNull(saved);
        assertEquals(OpportunityStage.LEAD, saved.getStage());
        assertEquals(10, saved.getProbability());
        assertNull(saved.getClosedAt());

        // history row written
        ArgumentCaptor<OpportunityStageHistory> historyCaptor =
                ArgumentCaptor.forClass(OpportunityStageHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        OpportunityStageHistory h = historyCaptor.getValue();
        assertNull(h.getFromStage(), "Initial creation has null fromStage");
        assertEquals(OpportunityStage.LEAD, h.getToStage());
        assertEquals(OWNER_ID, h.getChangedBy());
    }

    @Test
    @DisplayName("transitionStage LEAD → QUALIFIED auto-sets probability to 30, writes history")
    void transitionForwardOneStep_ok() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.QUALIFIED, null, false, null, CHANGED_BY);

        assertEquals(OpportunityStage.QUALIFIED, saved.getStage());
        assertEquals(30, saved.getProbability(), "Auto-bumped to QUALIFIED default");
        assertNull(saved.getClosedAt());

        ArgumentCaptor<OpportunityStageHistory> historyCaptor =
                ArgumentCaptor.forClass(OpportunityStageHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        OpportunityStageHistory h = historyCaptor.getValue();
        assertEquals(OpportunityStage.LEAD, h.getFromStage());
        assertEquals(OpportunityStage.QUALIFIED, h.getToStage());
        assertEquals(CHANGED_BY, h.getChangedBy());
    }

    @Test
    @DisplayName("transitionStage backward PROPOSAL → QUALIFIED requires reason")
    void transitionBackward_requiresReason() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.PROPOSAL, 70);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));

        // 不带 reason → throw
        InvalidStageTransitionException ex = assertThrows(InvalidStageTransitionException.class,
                () -> service.transitionStage(FACTORY, "opp-1",
                        OpportunityStage.QUALIFIED, null, false, null, CHANGED_BY));
        assertTrue(ex.getMessage().contains("回退"), "Error msg mentions 回退");
        verify(repository, never()).save(any(SalesOpportunity.class));
    }

    @Test
    @DisplayName("transitionStage backward PROPOSAL → QUALIFIED with reason succeeds")
    void transitionBackward_withReason_ok() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.PROPOSAL, 70);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.QUALIFIED, "客户预算待重新评估", false, null, CHANGED_BY);

        assertEquals(OpportunityStage.QUALIFIED, saved.getStage());
        assertEquals(30, saved.getProbability());
    }

    @Test
    @DisplayName("transitionStage skip-forward LEAD → PROPOSAL without confirmSkip throws")
    void transitionSkipForward_noConfirm_throws() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));

        InvalidStageTransitionException ex = assertThrows(InvalidStageTransitionException.class,
                () -> service.transitionStage(FACTORY, "opp-1",
                        OpportunityStage.PROPOSAL, "客户直接接受方案", false, null, CHANGED_BY));
        assertTrue(ex.getMessage().contains("跳级"));
    }

    @Test
    @DisplayName("transitionStage skip-forward LEAD → PROPOSAL with confirmSkip + reason succeeds")
    void transitionSkipForward_withConfirm_ok() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.PROPOSAL, "客户带着 RFQ 直接来了", true, null, CHANGED_BY);

        assertEquals(OpportunityStage.PROPOSAL, saved.getStage());
        assertEquals(70, saved.getProbability());
    }

    @Test
    @DisplayName("transitionStage same-stage LEAD → LEAD throws InvalidStageTransitionException")
    void transitionSameStage_throws() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));

        InvalidStageTransitionException ex = assertThrows(InvalidStageTransitionException.class,
                () -> service.transitionStage(FACTORY, "opp-1",
                        OpportunityStage.LEAD, null, false, null, CHANGED_BY));
        assertTrue(ex.getMessage().contains("相同"));
    }

    @Test
    @DisplayName("transitionStage VERBAL → CLOSED_WON sets closedAt + probability=100, no reason required")
    void transitionToCloseWon_ok() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.VERBAL, 95);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.CLOSED_WON, null, false, null, CHANGED_BY);

        assertEquals(OpportunityStage.CLOSED_WON, saved.getStage());
        assertEquals(100, saved.getProbability());
        assertNotNull(saved.getClosedAt(), "closedAt should be set on close");
    }

    @Test
    @DisplayName("transitionStage CLOSED_WON → LEAD reactivation requires reason")
    void transitionReactivate_requiresReason() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.CLOSED_WON, 100);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));

        InvalidStageTransitionException ex = assertThrows(InvalidStageTransitionException.class,
                () -> service.transitionStage(FACTORY, "opp-1",
                        OpportunityStage.LEAD, null, false, null, CHANGED_BY));
        assertTrue(ex.getMessage().contains("重新激活"));
    }

    @Test
    @DisplayName("transitionStage CLOSED_WON → LEAD with reason clears closedAt + probability=10")
    void transitionReactivate_withReason_clearsClosedAt() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.CLOSED_WON, 100);
        opp.setClosedAt(java.time.LocalDateTime.now().minusDays(30));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.LEAD, "客户合同未签, 重启商机", false, null, CHANGED_BY);

        assertEquals(OpportunityStage.LEAD, saved.getStage());
        assertEquals(10, saved.getProbability());
        assertNull(saved.getClosedAt(), "closedAt cleared on reactivation");
    }

    @Test
    @DisplayName("transitionStage with probabilityOverride respects custom value")
    void transitionWithProbabilityOverride() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesOpportunity saved = service.transitionStage(FACTORY, "opp-1",
                OpportunityStage.QUALIFIED, null, false, 50, CHANGED_BY);

        assertEquals(OpportunityStage.QUALIFIED, saved.getStage());
        assertEquals(50, saved.getProbability(), "Override beats default 30");
    }

    @Test
    @DisplayName("transitionStage probabilityOverride out of range (>100) throws")
    void transitionInvalidProbability_throws() {
        SalesOpportunity opp = existingOpp("opp-1", OpportunityStage.LEAD, 10);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("opp-1", FACTORY))
                .thenReturn(Optional.of(opp));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transitionStage(FACTORY, "opp-1",
                        OpportunityStage.QUALIFIED, null, false, 150, CHANGED_BY));
        assertTrue(ex.getMessage().contains("0-100"));
    }

    @Test
    @DisplayName("create with stage=CLOSED_WON auto-sets closedAt")
    void create_withClosedWonStage_setsClosedAt() {
        when(repository.save(any(SalesOpportunity.class))).thenAnswer(inv -> {
            SalesOpportunity arg = inv.getArgument(0);
            arg.setId("opp-1");
            return arg;
        });

        SalesOpportunity saved = service.create(FACTORY, CUSTOMER, "已成交合作",
                OpportunityStage.CLOSED_WON, new BigDecimal("80000.00"), null, null,
                OWNER_ID, "已签合同", OWNER_ID);

        assertEquals(OpportunityStage.CLOSED_WON, saved.getStage());
        assertEquals(100, saved.getProbability());
        assertNotNull(saved.getClosedAt());
    }

    @Test
    @DisplayName("get with non-existent id throws 404")
    void get_notFound_throws() {
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("missing", FACTORY))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(FACTORY, "missing"));
        assertEquals(404, ex.getCode());
    }
}
