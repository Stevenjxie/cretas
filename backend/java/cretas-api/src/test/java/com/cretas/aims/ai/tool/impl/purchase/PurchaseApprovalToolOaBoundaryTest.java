package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PurchaseApprovalToolOaBoundaryTest {

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "po-1";
    private static final long USER_ID = 1309L;

    private PurchaseService purchaseService;
    private PurchaseOrderApproveTool orderApproveTool;

    @BeforeEach
    void setUp() {
        purchaseService = mock(PurchaseService.class);
        orderApproveTool = new PurchaseOrderApproveTool();
        ReflectionTestUtils.setField(orderApproveTool, "purchaseService", purchaseService);
    }

    @Test
    void legacyFinanceToolReturnsOaGuidanceWithoutBusinessWrite() {
        PurchaseFinanceApproveTool tool = new PurchaseFinanceApproveTool();

        assertThat(tool.getDescription()).contains("旧兼容入口").contains("待我审批");
        assertThat(tool.getRequiredParameters()).isEmpty();
        assertThat(tool.getAccessMode()).isEqualTo(PurchaseFinanceApproveTool.AccessMode.WRITE);
        assertOaOnly(() -> tool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "approve"), context()));
    }

    @Test
    void generalToolSchemaNoLongerOffersFinanceActions() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) orderApproveTool
                .getParametersSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) properties.get("action");

        assertThat(action.get("enum")).isEqualTo(List.of("submit", "approve", "cancel"));
        assertThat(properties).doesNotContainKey("notes");
        assertThat(orderApproveTool.getDescription()).contains("待我审批")
                .doesNotContain("finance_approve")
                .doesNotContain("finance_reject");
    }

    @Test
    void generalToolRejectsCachedFinanceActionsWithoutCallingService() {
        assertOaOnly(() -> orderApproveTool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "finance_approve"), context()));
        assertOaOnly(() -> orderApproveTool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "finance_reject", "notes", "不通过"), context()));

        verifyNoInteractions(purchaseService);
    }

    @Test
    void generalToolKeepsNonFinanceOperations() throws Exception {
        PurchaseOrder submitted = order(PurchaseOrderStatus.SUBMITTED);
        PurchaseOrder approved = order(PurchaseOrderStatus.APPROVED);
        PurchaseOrder cancelled = order(PurchaseOrderStatus.CANCELLED);
        when(purchaseService.submitOrder(FACTORY_ID, ORDER_ID)).thenReturn(submitted);
        when(purchaseService.approveOrder(FACTORY_ID, ORDER_ID, USER_ID)).thenReturn(approved);
        when(purchaseService.cancelOrder(FACTORY_ID, ORDER_ID)).thenReturn(cancelled);

        orderApproveTool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "submit"), context());
        orderApproveTool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "approve"), context());
        orderApproveTool.doExecute(FACTORY_ID,
                Map.of("orderId", ORDER_ID, "action", "cancel"), context());

        verify(purchaseService).submitOrder(FACTORY_ID, ORDER_ID);
        verify(purchaseService).approveOrder(FACTORY_ID, ORDER_ID, USER_ID);
        verify(purchaseService).cancelOrder(FACTORY_ID, ORDER_ID);
        verifyNoMoreInteractions(purchaseService);
    }

    private PurchaseOrder order(PurchaseOrderStatus status) {
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getOrderNumber()).thenReturn("PO-TEST-1");
        when(order.getStatus()).thenReturn(status);
        return order;
    }

    private Map<String, Object> context() {
        return Map.of("factoryId", FACTORY_ID, "userId", USER_ID);
    }

    private void assertOaOnly(ThrowingRunnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待我审批")
                .extracting("errorCode")
                .isEqualTo("PURCHASE_APPROVAL_OA_ONLY");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
