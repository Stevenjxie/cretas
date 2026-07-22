package com.cretas.aims.controller.inventory;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SalesControllerCustomerMaterialReceiptDisabledTest {

    @Test
    void legacySalesReceiptRouteReturnsGoneAndHasNoInventoryWriteDependency() {
        SalesController controller = new SalesController(
                mock(SalesService.class),
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

        SalesController.CustomerMaterialReceiptRequest request =
                new SalesController.CustomerMaterialReceiptRequest(
                        "M-1", LocalDate.of(2026, 7, 22), BigDecimal.ONE, "kg",
                        BigDecimal.ONE, BigDecimal.ZERO, "W-1", null,
                        null, null, null);

        assertThatThrownBy(() -> controller.createCustomerMaterialReceipt(
                "F006", "SO-1", "Bearer ignored", request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(410);
                    assertThat(exception.getErrorCode())
                            .isEqualTo("CUSTOMER_SUPPLIED_RECEIPT_WAREHOUSE_ONLY");
                });

        assertThat(Arrays.stream(SalesController.class.getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(MaterialBatchService.class);
    }
}
