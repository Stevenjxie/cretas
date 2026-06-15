package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MobileDeviceServiceImplTest {

    @Test
    void recordDeviceLoginIgnoresLegacyNullDeviceIds() {
        MobileDeviceServiceImpl service = new MobileDeviceServiceImpl(null);
        Long userId = 1551L;

        service.recordDeviceLogin(userId, MobileDTO.DeviceInfo.builder()
                .deviceId(null)
                .deviceType("Web")
                .build());

        assertDoesNotThrow(() -> service.recordDeviceLogin(userId, MobileDTO.DeviceInfo.builder()
                .deviceId("rn-deep-test")
                .deviceType("Web")
                .build()));
        assertEquals(2, service.getUserDevices(userId).size());
    }

    @Test
    void removeDeviceIgnoresLegacyNullDeviceIds() {
        MobileDeviceServiceImpl service = new MobileDeviceServiceImpl(null);
        Long userId = 1551L;

        service.recordDeviceLogin(userId, MobileDTO.DeviceInfo.builder()
                .deviceId(null)
                .deviceType("Web")
                .build());

        assertDoesNotThrow(() -> service.removeDevice(userId, "rn-deep-test"));
        assertEquals(1, service.getUserDevices(userId).size());
    }
}
