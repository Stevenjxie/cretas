package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 六扇门 Tier0 #16 (requirements-catalog 行2399-2416): 退货财务审批门。
 *
 * <p>客户原话 (contradiction 现场纠正): "退货跟钱有关要先财务审批确认；跟钱有关的东西都要审批"
 * / "退货单发财务审批，审批后给仓管把实物拿走(出货管理)"。
 *
 * <p>验证退货状态机财务审批门: APPROVED → FINANCE_APPROVED → COMPLETED。
 * <ol>
 *   <li>未财务审批 (APPROVED) 直接 complete → 409 拒绝 + hint (防呆)。</li>
 *   <li>APPROVED → financeApprove → FINANCE_APPROVED + 记录审批人/时间。</li>
 *   <li>FINANCE_APPROVED → complete 成功 → COMPLETED。</li>
 *   <li>非 APPROVED 状态调 financeApprove (如 SUBMITTED) → 409 拒绝。</li>
 *   <li>SALES_RETURN 同样受财务门约束 (跟钱有关，业态无关)。</li>
 * </ol>
 *
 * <p>注: Controller 层 @RequirePermission({"finance:read_write"}) 把守"非财务角色审 → 403"
 * (RBAC 拦截器测试覆盖)，本测试聚焦 service 状态机语义。
 */
@DisplayName("六扇门#16: 退货财务审批门 (APPROVED → FINANCE_APPROVED → COMPLETED)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderFinanceApprovalGateTest {

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private ReturnOrderServiceImpl service;

    private static final String FACTORY = "F001";
    private static final Long FINANCE_USER = 88L;
    private static final Long BIZ_APPROVER = 22L;

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository,
                returnOrderItemRepository,
                arApService,
                applicationEventPublisher);
    }

    private ReturnOrder buildOrder(ReturnType type, ReturnOrderStatus status) {
        ReturnOrder o = new ReturnOrder();
        o.setId("RO-" + System.nanoTime());
        o.setFactoryId(FACTORY);
        o.setReturnNumber("RT-PUR-20261016-0001");
        o.setReturnType(type);
        o.setWithGoods(Boolean.FALSE); // 无货退款 path: AR/AP 已在 approve 触发，complete 仅翻状态
        o.setStatus(status);
        o.setCounterpartyId("SUP-001");
        o.setTotalAmount(new BigDecimal("500.00"));
        o.setApprovedBy(BIZ_APPROVER);
        o.setItems(new ArrayList<>());
        return o;
    }

    private void stub(ReturnOrder order) {
        when(returnOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(returnOrderRepository.save(order)).thenReturn(order);
    }

    @Test
    @DisplayName("未财务审批 (APPROVED) 直接 complete → 409 拒绝 + hint (防呆门)")
    void approvedButNotFinanceApproved_completeRejected() {
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, ReturnOrderStatus.APPROVED);
        stub(order);

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("财务审批");

        // 状态未被推进，AR/AP 不被触发
        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.APPROVED);
    }

    @Test
    @DisplayName("APPROVED → financeApprove → FINANCE_APPROVED + 记录审批人/时间")
    void financeApprove_flipsToFinanceApproved() {
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, ReturnOrderStatus.APPROVED);
        stub(order);

        ReturnOrder result = service.financeApproveReturnOrder(FACTORY, order.getId(), FINANCE_USER);

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.FINANCE_APPROVED);
        assertThat(result.getFinanceApprovedBy()).isEqualTo(FINANCE_USER);
        assertThat(result.getFinanceApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("FINANCE_APPROVED → complete 成功 → COMPLETED")
    void financeApproved_completeSucceeds() {
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, ReturnOrderStatus.FINANCE_APPROVED);
        order.setFinanceApprovedBy(FINANCE_USER);
        stub(order);

        ReturnOrder result = service.completeReturnOrder(FACTORY, order.getId());

        assertThat(result.getStatus()).isEqualTo(ReturnOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("非 APPROVED 状态 (SUBMITTED) 调 financeApprove → 409 拒绝")
    void nonApproved_financeApproveRejected() {
        ReturnOrder order = buildOrder(ReturnType.PURCHASE_RETURN, ReturnOrderStatus.SUBMITTED);
        stub(order);

        assertThatThrownBy(() -> service.financeApproveReturnOrder(FACTORY, order.getId(), FINANCE_USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务审批");

        assertThat(order.getStatus()).isEqualTo(ReturnOrderStatus.SUBMITTED);
        verify(returnOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("SALES_RETURN 同样受财务门约束 (未财务审批 complete 拒绝)")
    void salesReturn_alsoGatedByFinance() {
        ReturnOrder order = buildOrder(ReturnType.SALES_RETURN, ReturnOrderStatus.APPROVED);
        stub(order);

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("财务审批");

        // 财务门拦截发生在 AR/AP 冲减之前
        verify(arApService, never()).recordAdjustment(
                anyString(), any(), anyString(), any(), any(), anyString());
    }
}
