package com.cretas.aims.service;

import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CommissionRepository;
import com.cretas.aims.repository.CommissionRuleRepository;
import com.cretas.aims.repository.CustomerRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sprint 7 wave 2 T5 — CommissionService 单元测试.
 *
 * <p>覆盖:
 * <ul>
 *   <li>findApplicableRule: most-specific wins (sales+type > sales > type > generic)</li>
 *   <li>calculate: opportunity CLOSED_WON → commission(PENDING) 创建</li>
 *   <li>calculate: idempotent (重复调用同 oppId)</li>
 *   <li>calculate: no rule → returns empty</li>
 *   <li>calculate: zero valueAmount → returns empty</li>
 *   <li>markPaid 状态切换 + paidAt 设置</li>
 *   <li>cancel: PAID 不可取消</li>
 * </ul>
 */
@DisplayName("CommissionService unit tests (Sprint 7 wave 2 T5)")
@ExtendWith(MockitoExtension.class)
class CommissionServiceTest {

    @Mock private CommissionRepository commissionRepository;
    @Mock private CommissionRuleRepository ruleRepository;
    @Mock private SalesOpportunityRepository opportunityRepository;
    @Mock private CustomerRepository customerRepository;

    private CommissionService service;

    private static final String FACTORY = "F006";
    private static final Long SALES_ID = 100L;
    private static final String CUSTOMER_ID = "C-001";
    private static final String CUSTOMER_TYPE = "大客户";
    private static final LocalDate DATE = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        service = new CommissionService(commissionRepository, ruleRepository,
                opportunityRepository, customerRepository);
    }

    // ==================== findApplicableRule — most-specific wins ====================

    @Test
    @DisplayName("findApplicableRule picks sales+customerType match over sales-only")
    void findApplicableRule_mostSpecificWins() {
        CommissionRule generic = ruleOf("r-generic", null, null, "3.00");
        CommissionRule salesOnly = ruleOf("r-sales", SALES_ID, null, "5.00");
        CommissionRule typeOnly = ruleOf("r-type", null, CUSTOMER_TYPE, "4.00");
        CommissionRule both = ruleOf("r-both", SALES_ID, CUSTOMER_TYPE, "8.00");
        when(ruleRepository.findCandidates(FACTORY, SALES_ID, CUSTOMER_TYPE, DATE))
                .thenReturn(List.of(generic, salesOnly, typeOnly, both));

        Optional<CommissionRule> result = service.findApplicableRule(FACTORY, SALES_ID, CUSTOMER_TYPE, DATE);
        assertTrue(result.isPresent());
        assertEquals("r-both", result.get().getId());
        assertEquals(new BigDecimal("8.00"), result.get().getPercentage());
    }

    @Test
    @DisplayName("findApplicableRule falls back to generic when no specific match")
    void findApplicableRule_fallback() {
        CommissionRule generic = ruleOf("r-generic", null, null, "3.00");
        when(ruleRepository.findCandidates(FACTORY, SALES_ID, CUSTOMER_TYPE, DATE))
                .thenReturn(List.of(generic));

        Optional<CommissionRule> result = service.findApplicableRule(FACTORY, SALES_ID, CUSTOMER_TYPE, DATE);
        assertTrue(result.isPresent());
        assertEquals("r-generic", result.get().getId());
    }

    @Test
    @DisplayName("findApplicableRule returns empty if no candidates")
    void findApplicableRule_noCandidates() {
        when(ruleRepository.findCandidates(any(), any(), any(), any())).thenReturn(List.of());
        Optional<CommissionRule> result = service.findApplicableRule(FACTORY, SALES_ID, CUSTOMER_TYPE, DATE);
        assertTrue(result.isEmpty());
    }

    // ==================== calculate — auto on CLOSED_WON ====================

    @Test
    @DisplayName("calculate creates Commission(PENDING) with correct amount on CLOSED_WON")
    void calculate_createsCommissionOnClosedWon() {
        SalesOpportunity opp = oppOf("opp-1", new BigDecimal("100000"));
        Customer cust = customerOf(CUSTOMER_ID, CUSTOMER_TYPE);
        CommissionRule rule = ruleOf("r-1", SALES_ID, CUSTOMER_TYPE, "5.50");

        when(commissionRepository.findBySalesOpportunityIdAndDeletedAtIsNull("opp-1"))
                .thenReturn(Optional.empty());
        when(opportunityRepository.findById("opp-1")).thenReturn(Optional.of(opp));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(cust));
        when(ruleRepository.findCandidates(eq(FACTORY), eq(SALES_ID), eq(CUSTOMER_TYPE), any(LocalDate.class)))
                .thenReturn(List.of(rule));
        when(commissionRepository.save(any(Commission.class))).thenAnswer(inv -> {
            Commission c = inv.getArgument(0);
            c.setId("comm-1");
            return c;
        });

        Optional<Commission> result = service.calculate("opp-1");

        assertTrue(result.isPresent());
        // amount = 100000 * 5.50 / 100 = 5500.00
        ArgumentCaptor<Commission> captor = ArgumentCaptor.forClass(Commission.class);
        verify(commissionRepository).save(captor.capture());
        Commission saved = captor.getValue();
        assertEquals(new BigDecimal("5500.00"), saved.getAmount());
        assertEquals(new BigDecimal("5.50"), saved.getPercentage());
        assertEquals(CommissionStatus.PENDING, saved.getStatus());
        assertEquals(SALES_ID, saved.getSalesId());
        assertEquals("r-1", saved.getRuleId());
    }

    @Test
    @DisplayName("calculate is idempotent — second call returns existing commission")
    void calculate_idempotent() {
        Commission existing = Commission.builder()
                .id("comm-1")
                .factoryId(FACTORY)
                .salesOpportunityId("opp-1")
                .ruleId("r-1")
                .salesId(SALES_ID)
                .percentage(new BigDecimal("5.50"))
                .amount(new BigDecimal("5500.00"))
                .status(CommissionStatus.PENDING)
                .build();
        when(commissionRepository.findBySalesOpportunityIdAndDeletedAtIsNull("opp-1"))
                .thenReturn(Optional.of(existing));

        Optional<Commission> result = service.calculate("opp-1");
        assertTrue(result.isPresent());
        assertEquals("comm-1", result.get().getId());
        // 必须 NOT 调用 opportunityRepository, NOT save
        verify(opportunityRepository, never()).findById(anyString());
        verify(commissionRepository, never()).save(any(Commission.class));
    }

    @Test
    @DisplayName("calculate with no applicable rule returns empty")
    void calculate_noRule() {
        SalesOpportunity opp = oppOf("opp-1", new BigDecimal("100000"));
        Customer cust = customerOf(CUSTOMER_ID, CUSTOMER_TYPE);

        when(commissionRepository.findBySalesOpportunityIdAndDeletedAtIsNull("opp-1"))
                .thenReturn(Optional.empty());
        when(opportunityRepository.findById("opp-1")).thenReturn(Optional.of(opp));
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(cust));
        when(ruleRepository.findCandidates(any(), any(), any(), any())).thenReturn(List.of());

        Optional<Commission> result = service.calculate("opp-1");
        assertTrue(result.isEmpty());
        verify(commissionRepository, never()).save(any(Commission.class));
    }

    @Test
    @DisplayName("calculate skips opportunity with zero/null valueAmount")
    void calculate_zeroAmount() {
        SalesOpportunity opp = oppOf("opp-1", BigDecimal.ZERO);
        when(commissionRepository.findBySalesOpportunityIdAndDeletedAtIsNull("opp-1"))
                .thenReturn(Optional.empty());
        when(opportunityRepository.findById("opp-1")).thenReturn(Optional.of(opp));

        Optional<Commission> result = service.calculate("opp-1");
        assertTrue(result.isEmpty());
        verify(commissionRepository, never()).save(any(Commission.class));
    }

    // ==================== markPaid / cancel ====================

    @Test
    @DisplayName("markPaid sets status=PAID and paidAt")
    void markPaid_setsStatusAndPaidAt() {
        Commission c = Commission.builder()
                .id("c-1").factoryId(FACTORY).salesOpportunityId("opp-1")
                .ruleId("r-1").salesId(SALES_ID)
                .percentage(new BigDecimal("5.50"))
                .amount(new BigDecimal("5500.00"))
                .status(CommissionStatus.PENDING).build();
        when(commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull("c-1", FACTORY))
                .thenReturn(Optional.of(c));
        when(commissionRepository.save(any(Commission.class))).thenAnswer(inv -> inv.getArgument(0));

        Commission result = service.markPaid(FACTORY, "c-1");
        assertEquals(CommissionStatus.PAID, result.getStatus());
        assertNotNull(result.getPaidAt());
    }

    @Test
    @DisplayName("cancel on PAID commission throws BusinessException")
    void cancel_rejectsPaid() {
        Commission c = Commission.builder()
                .id("c-1").factoryId(FACTORY).salesOpportunityId("opp-1")
                .ruleId("r-1").salesId(SALES_ID)
                .percentage(new BigDecimal("5.50"))
                .amount(new BigDecimal("5500.00"))
                .status(CommissionStatus.PAID).paidAt(LocalDateTime.now()).build();
        when(commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull("c-1", FACTORY))
                .thenReturn(Optional.of(c));

        assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.cancel(FACTORY, "c-1"));
    }

    // ==================== Helpers ====================

    private SalesOpportunity oppOf(String id, BigDecimal valueAmount) {
        return SalesOpportunity.builder()
                .id(id)
                .factoryId(FACTORY)
                .customerId(CUSTOMER_ID)
                .title("叮咚好食光卤猪蹄合作")
                .stage(OpportunityStage.CLOSED_WON)
                .valueAmount(valueAmount)
                .probability(100)
                .ownerId(SALES_ID)
                .createdBy(SALES_ID)
                .closedAt(DATE.atStartOfDay())
                .version(0L)
                .build();
    }

    private CommissionRule ruleOf(String id, Long salesId, String customerType, String pct) {
        return CommissionRule.builder()
                .id(id)
                .factoryId(FACTORY)
                .salesId(salesId)
                .customerType(customerType)
                .percentage(new BigDecimal(pct))
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(null)
                .active(Boolean.TRUE)
                .createdBy(1L)
                .build();
    }

    private Customer customerOf(String id, String customerType) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(FACTORY);
        c.setCustomerType(customerType);
        return c;
    }
}
