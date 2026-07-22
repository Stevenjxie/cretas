package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderShortageReportRepository;
import com.cretas.aims.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesOrderShortageReportListenerCustomerSuppliedGuardTest {

    @Mock private ShortageAnalysisService shortageAnalysisService;
    @Mock private SalesOrderShortageReportRepository reportRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private NotificationService notificationService;
    @Mock private SalesOrderRepository salesOrderRepository;

    @InjectMocks
    private SalesOrderShortageReportListener listener;

    @Test
    void customerSuppliedOrderDoesNotCreateCompanyProcurementSuggestionSnapshot() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-CUSTOMER-SUPPLIED");
        order.setFactoryId("F006");
        order.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(salesOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        listener.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, "F006", order.getId(), 1309L));

        verifyNoInteractions(shortageAnalysisService, reportRepository,
                salesOrderItemRepository, notificationService);
    }
}
