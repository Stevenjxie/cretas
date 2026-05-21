package com.cretas.aims.service.cron.handler;

import com.cretas.aims.scheduler.SupplierQualificationExpiryScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SupplierQualificationExpiryTaskHandler}.
 *
 * <p>The handler delegates to the legacy
 * {@link SupplierQualificationExpiryScheduler#dailyExpiryCheck()}, so the unit
 * tests verify delegation + error wrapping. The legacy scheduler's own behavior
 * has its own integration tests elsewhere.
 *
 * @since 2026-05-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupplierQualificationExpiryTaskHandler 单元测试")
class SupplierQualificationExpiryTaskHandlerTest {

    @Mock
    private SupplierQualificationExpiryScheduler legacyScheduler;

    @InjectMocks
    private SupplierQualificationExpiryTaskHandler handler;

    @Test
    @DisplayName("execute: 调用 legacy dailyExpiryCheck + context 写 DELEGATED_SUCCESS")
    void delegatesToLegacyAndReportsSuccess() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("taskCode", "supplier_qualification_expiry_scan");

        handler.execute(ctx);

        verify(legacyScheduler, times(1)).dailyExpiryCheck();
        assertEquals("DELEGATED_SUCCESS", ctx.get("status"));
    }

    @Test
    @DisplayName("execute: legacy 抛异常时包装为 RuntimeException 给 DynamicScheduler 写 FAILED")
    void wrapsLegacyFailure() {
        doThrow(new RuntimeException("notification API down"))
                .when(legacyScheduler).dailyExpiryCheck();

        Map<String, Object> ctx = new HashMap<>();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(ctx));
        assertTrue(ex.getMessage().contains("supplier qualification expiry scan failed"));
        assertTrue(ex.getMessage().contains("notification API down"));
    }
}
