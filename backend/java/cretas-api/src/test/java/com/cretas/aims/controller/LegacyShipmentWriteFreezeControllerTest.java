package com.cretas.aims.controller;

import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ShipmentRecordService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegacyShipmentWriteFreezeControllerTest {

    private final ShipmentRecordService service = mock(ShipmentRecordService.class);
    private final ShipmentController controller = new ShipmentController(service);

    @Test
    void everyLegacyMutationReturnsGoneWithoutTouchingTheRepositoryService() {
        assertGone(() -> controller.createShipment("F001", 7L, new ShipmentRecord()));
        assertGone(() -> controller.updateShipment("F001", "legacy-1", new ShipmentRecord()));
        assertGone(() -> controller.updateStatus("F001", "legacy-1", Map.of("status", "shipped")));
        assertGone(() -> controller.deleteShipment("F001", "legacy-1"));

        verifyNoInteractions(service);
    }

    private void assertGone(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(410);
                    assertThat(exception.getErrorCode()).isEqualTo("LEGACY_SHIPMENT_WRITE_GONE");
                    assertThat(exception.getMessage()).contains("销售订单").contains("扣减库存");
                    assertThat(exception.getActionHint()).contains("仓库待确认发货单");
                });
    }
}
