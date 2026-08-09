package com.cretas.aims.controller.inventory;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SalesControllerDirectShipmentDisabledTest {

    @Test
    void directSalesShipmentRouteIsGoneAndNeverCallsInventoryMutationService() {
        SalesService salesService = mock(SalesService.class);
        SalesController controller = new SalesController(
                salesService,
                mock(MobileService.class),
                mock(PermissionService.class),
                mock(UserRepository.class),
                mock(SalesOrderRepository.class),
                mock(BusinessLinkRepository.class),
                mock(ProductTypeRepository.class),
                mock(FinishedGoodsBatchRepository.class),
                mock(GrossMarginRedlineService.class),
                mock(EstimatePriceCheckService.class),
                mock(SalesPriceAdjustmentService.class),
                mock(ProductPackagingSpecService.class));

        assertThatThrownBy(() -> controller.shipDelivery("F006", "DLV-1", "Bearer ignored"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(410);
                    assertThat(error.getErrorCode()).isEqualTo("SALES_DELIVERY_WAREHOUSE_ONLY");
                });
        verify(salesService, never()).shipDelivery(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
