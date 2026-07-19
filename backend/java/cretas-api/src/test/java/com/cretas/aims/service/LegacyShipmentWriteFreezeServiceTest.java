package com.cretas.aims.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyShipmentWriteFreezeServiceTest {

    @Test
    void legacyShipmentServiceIsReadOnly() {
        assertThat(Arrays.stream(ShipmentRecordService.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("createShipment", "updateShipment", "updateStatus", "deleteShipment");
    }
}
