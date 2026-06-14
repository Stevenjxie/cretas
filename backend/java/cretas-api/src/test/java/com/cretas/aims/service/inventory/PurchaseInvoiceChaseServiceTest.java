package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseLevel;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseStatus;
import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoiceChaseLog;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceChaseService;
import com.cretas.aims.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseInvoiceChaseService active invoice chase")
class PurchaseInvoiceChaseServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseInvoiceChaseLogRepository chaseLogRepository;

    @Mock
    private FactorySettingsRepository factorySettingsRepository;

    @Mock
    private NotificationService notificationService;

    private PurchaseInvoiceChaseService service;

    @BeforeEach
    void setup() {
        service = new PurchaseInvoiceChaseService(
                purchaseOrderRepository,
                chaseLogRepository,
                factorySettingsRepository,
                notificationService);
    }

    @Test
    @DisplayName("overdue received PO without invoice pushes procurement and finance once")
    void scanFactory_overdueWithoutInvoice_pushesProcurementAndFinance() {
        PurchaseOrder po = purchaseOrder("PO-001", "CG-001", TODAY.minusDays(10), 3);
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                eq(FACTORY_ID), eq("PO-001"), eq(PurchaseInvoiceChaseLevel.NORMAL), any(LocalDate.class)))
                .thenReturn(false);

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(1, pushed);
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.procurement_manager.name()), any(), any());
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.finance_manager.name()), any(), any());

        ArgumentCaptor<PurchaseInvoiceChaseLog> captor =
                ArgumentCaptor.forClass(PurchaseInvoiceChaseLog.class);
        verify(chaseLogRepository).save(captor.capture());
        assertEquals(PurchaseInvoiceChaseLevel.NORMAL, captor.getValue().getChaseLevel());
        assertEquals(PurchaseInvoiceChaseStatus.SENT, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("over escalation threshold pushes factory superior")
    void scanFactory_overEscalationThreshold_pushesFactorySuperior() {
        PurchaseOrder po = purchaseOrder("PO-002", "CG-002", TODAY.minusDays(20), 3);
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                eq(FACTORY_ID), eq("PO-002"), eq(PurchaseInvoiceChaseLevel.ESCALATED), any(LocalDate.class)))
                .thenReturn(false);

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(1, pushed);
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.factory_super_admin.name()), any(), any());

        ArgumentCaptor<PurchaseInvoiceChaseLog> captor =
                ArgumentCaptor.forClass(PurchaseInvoiceChaseLog.class);
        verify(chaseLogRepository).save(captor.capture());
        assertEquals(PurchaseInvoiceChaseLevel.ESCALATED, captor.getValue().getChaseLevel());
    }

    @Test
    @DisplayName("paid PO candidate is chased even before completed receipt")
    void scanFactory_paidCandidateBeforeCompletedReceipt_pushesChase() {
        PurchaseOrder po = purchaseOrder("PO-PAID-001", "CG-PAID-001", TODAY.minusDays(10), 3);
        po.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                eq(FACTORY_ID), eq("PO-PAID-001"), eq(PurchaseInvoiceChaseLevel.NORMAL), any(LocalDate.class)))
                .thenReturn(false);

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(1, pushed);
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.procurement_manager.name()), any(), any());
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.finance_manager.name()), any(), any());
    }

    @Test
    @DisplayName("same PO and chase window is deduped")
    void scanFactory_samePoSameWindow_doesNotPushAgain() {
        PurchaseOrder po = purchaseOrder("PO-003", "CG-003", TODAY.minusDays(10), 3);
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                eq(FACTORY_ID), eq("PO-003"), eq(PurchaseInvoiceChaseLevel.NORMAL), any(LocalDate.class)))
                .thenReturn(true);

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(0, pushed);
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
        verify(chaseLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("missing PO reminder days and factory default skips honestly")
    void scanFactory_missingReminderConfig_skipsWithoutPush() {
        PurchaseOrder po = purchaseOrder("PO-004", "CG-004", TODAY.minusDays(30), null);
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(factorySettingsRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(Optional.empty());

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(0, pushed);
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
        verify(chaseLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("factory notification default reminderDays is used when PO is blank")
    void scanFactory_factoryDefaultReminderDaysUsed() {
        PurchaseOrder po = purchaseOrder("PO-005", "CG-005", TODAY.minusDays(10), null);
        FactorySettings settings = new FactorySettings();
        settings.setFactoryId(FACTORY_ID);
        settings.setNotificationSettings("{\"reminderDays\":3}");
        when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                .thenReturn(List.of(po));
        when(factorySettingsRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(Optional.of(settings));
        when(chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                eq(FACTORY_ID), eq("PO-005"), eq(PurchaseInvoiceChaseLevel.NORMAL), any(LocalDate.class)))
                .thenReturn(false);

        int pushed = service.scanFactory(FACTORY_ID, TODAY);

        assertEquals(1, pushed);
        verify(notificationService).notifyRole(eq(FACTORY_ID),
                eq(FactoryUserRole.procurement_manager.name()), any(), any());
    }

    private PurchaseOrder purchaseOrder(String id, String orderNumber, LocalDate orderDate,
                                        Integer invoiceReminderDays) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setFactoryId(FACTORY_ID);
        po.setOrderNumber(orderNumber);
        po.setSupplierId("SUP-001");
        po.setOrderDate(orderDate);
        po.setStatus(PurchaseOrderStatus.COMPLETED);
        po.setInvoiceReminderDays(invoiceReminderDays);
        po.setInvoiceStatus(PurchaseInvoiceStatus.NOT_RECEIVED);
        po.setTotalAmount(BigDecimal.valueOf(1000));
        po.setCreatedBy(1L);
        return po;
    }
}
