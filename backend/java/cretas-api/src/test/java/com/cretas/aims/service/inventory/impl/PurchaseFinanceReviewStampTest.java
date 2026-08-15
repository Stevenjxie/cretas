package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「财务已审核」这一章只有财务真的批过才能盖。
 *
 * <p>实测长相 (prod, 2026-08-15): 三条路都会把单据置成 FINANCE_APPROVED 并把
 * <b>不是财务的那个人</b>写成财务审核人 ——
 * <ul>
 *   <li>没配审批链(除 F006/LIUSHANMEN 外的所有工厂): 提交人给自己盖章</li>
 *   <li>有链但没有财务节点(LIUSHANMEN 只有一个 admin_approval): 盖成最后那个业务审批人</li>
 *   <li>F006 的 ≤¥30000 分支走 end_auto: 同样落到 case APPROVED</li>
 * </ul>
 * 于是产品里那个 {@code procurement/finance-review} 模块永远没有单据进得去。
 *
 * <p>⚠️ 状态**故意不动**: 收货门禁认的就是 FINANCE_APPROVED, 改状态会把没有财务节点的
 * 工厂(LIUSHANMEN)的采购收货整条堵死。这里只让审计痕迹说实话。
 */
class PurchaseFinanceReviewStampTest {

    private static final String FACTORY = "F006";
    private static final String INSTANCE_ID = "inst-1";

    private PurchaseServiceImpl service;
    private ApprovalHistoryRepository historyRepository;

    @BeforeEach
    void setUp() throws Exception {
        // 不走构造器: 这个 service 有十来个 final 依赖, 而被测方法只用到审批历史仓库。
        service = Mockito.mock(PurchaseServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        historyRepository = mock(ApprovalHistoryRepository.class);
        Field f = PurchaseServiceImpl.class.getDeclaredField("approvalHistoryRepository");
        f.setAccessible(true);
        f.set(service, historyRepository);
    }

    private ApprovalWorkflowInstance approvedInstance() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setId(INSTANCE_ID);
        instance.setFactoryId(FACTORY);
        instance.setStatus(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        return instance;
    }

    private ApprovalHistory history(ApprovalHistory.HistoryAction action, String role, Long actor) {
        ApprovalHistory h = new ApprovalHistory();
        h.setInstanceId(INSTANCE_ID);
        h.setFactoryId(FACTORY);
        h.setAction(action);
        h.setActorRole(role);
        h.setActorId(actor);
        h.setCreatedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        return h;
    }

    private PurchaseOrder project(ApprovalWorkflowInstance instance, Long businessActor) throws Exception {
        PurchaseOrder order = new PurchaseOrder();
        order.setFactoryId(FACTORY);
        order.setOrderNumber("PO-TEST-1");
        Method m = PurchaseServiceImpl.class.getDeclaredMethod(
                "projectWorkflowState", PurchaseOrder.class, ApprovalWorkflowInstance.class, Long.class);
        m.setAccessible(true);
        m.invoke(service, order, instance, businessActor);
        return order;
    }

    @Test
    @DisplayName("链里有财务节点且财务批了 → 盖章, 且盖的是财务那个人")
    void stampsTheActualFinanceApprover() throws Exception {
        when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(anyString(), anyString()))
                .thenReturn(List.of(
                        history(ApprovalHistory.HistoryAction.APPROVE, "workshop_supervisor", 11L),
                        history(ApprovalHistory.HistoryAction.APPROVE, "finance_manager", 77L)));

        PurchaseOrder order = project(approvedInstance(), 11L);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        // 关键: 不是 11(业务审批人), 是 77(财务)
        assertThat(order.getFinanceReviewedBy()).isEqualTo(77L);
        assertThat(order.getFinanceReviewedAt()).isNotNull();
        assertThat(order.getApprovedBy()).isEqualTo(11L);
    }

    @Test
    @DisplayName("链里没有财务节点 → 不盖章(留空), 但状态照样放行")
    void doesNotStampWhenNoFinanceNodeRan() throws Exception {
        // LIUSHANMEN 的真实长相: 只有一个 factory_super_admin 的审批
        when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(anyString(), anyString()))
                .thenReturn(List.of(
                        history(ApprovalHistory.HistoryAction.APPROVE, "factory_super_admin", 11L)));

        PurchaseOrder order = project(approvedInstance(), 11L);

        assertThat(order.getFinanceReviewedBy()).isNull();
        assertThat(order.getFinanceReviewedAt()).isNull();
        // ⚠️ 状态必须仍然放行 —— 否则 LIUSHANMEN 的采购收货会被整条堵死
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        assertThat(order.getApprovedBy()).isEqualTo(11L);
    }

    @Test
    @DisplayName("财务只是【被驳回/跳过】不算审核过")
    void financeRejectOrSkipIsNotAnApproval() throws Exception {
        when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(anyString(), anyString()))
                .thenReturn(List.of(
                        history(ApprovalHistory.HistoryAction.SKIP, "finance_manager", 77L),
                        history(ApprovalHistory.HistoryAction.APPROVE, "factory_super_admin", 11L)));

        PurchaseOrder order = project(approvedInstance(), 11L);

        assertThat(order.getFinanceReviewedBy()).isNull();
    }

    @Test
    @DisplayName("查历史抛异常 → 按【没有财务审核】处理, 宁可少盖章不要盖错章")
    void failsClosedWhenHistoryUnavailable() throws Exception {
        when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        PurchaseOrder order = project(approvedInstance(), 11L);

        assertThat(order.getFinanceReviewedBy()).isNull();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("驳回 → 状态 FINANCE_REJECTED, 一个章都不盖")
    void rejectedStampsNothing() throws Exception {
        ApprovalWorkflowInstance instance = approvedInstance();
        instance.setStatus(ApprovalWorkflowInstance.InstanceStatus.REJECTED);

        PurchaseOrder order = project(instance, 11L);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_REJECTED);
        assertThat(order.getFinanceReviewedBy()).isNull();
        assertThat(order.getApprovedBy()).isNull();
    }
}
