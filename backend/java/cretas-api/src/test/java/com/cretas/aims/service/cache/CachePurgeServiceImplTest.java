package com.cretas.aims.service.cache;

import com.cretas.aims.repository.calibration.ToolCallCacheRepository;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.cache.impl.CachePurgeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CachePurgeServiceImpl} (Sprint 12).
 *
 * <p>Validates scope routing, blank-factoryId safety, and event-based dispatch. Mocks
 * underlying repositories so tests run without a database.
 */
@ExtendWith(MockitoExtension.class)
class CachePurgeServiceImplTest {

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private ToolCallCacheRepository toolCallCacheRepository;

    @InjectMocks
    private CachePurgeServiceImpl service;

    @BeforeEach
    void noSetup() { /* InjectMocks handles wiring */ }

    @Test
    void purgeRouting_withIntentCode_callsInvalidateByIntentCode() {
        when(semanticCacheService.invalidateByIntentCode("F001", "RESTAURANT_ECONOMICS_ANALYSIS"))
                .thenReturn(7);

        int deleted = service.purgeRouting("F001", "RESTAURANT_ECONOMICS_ANALYSIS", "test");

        assertThat(deleted).isEqualTo(7);
        verify(semanticCacheService).invalidateByIntentCode("F001", "RESTAURANT_ECONOMICS_ANALYSIS");
        verify(semanticCacheService, never()).invalidateByFactory(anyString());
    }

    @Test
    void purgeRouting_withoutIntentCode_callsInvalidateByFactory() {
        when(semanticCacheService.invalidateByFactory("F001")).thenReturn(42);

        int deleted = service.purgeRouting("F001", null, "broad-test");

        assertThat(deleted).isEqualTo(42);
        verify(semanticCacheService).invalidateByFactory("F001");
        verify(semanticCacheService, never()).invalidateByIntentCode(anyString(), anyString());
    }

    @Test
    void purgeRouting_blankFactoryId_returnsZeroNoCallsMade() {
        int deleted = service.purgeRouting("  ", "ANY_INTENT", "blank-test");

        assertThat(deleted).isZero();
        verify(semanticCacheService, never()).invalidateByIntentCode(anyString(), anyString());
        verify(semanticCacheService, never()).invalidateByFactory(anyString());
    }

    @Test
    void purgeIndicator_callsInvalidateByFactoryAndLogsIndicatorCode() {
        when(semanticCacheService.invalidateByFactory("RES_3101_009")).thenReturn(5);

        int deleted = service.purgeIndicator("RES_3101_009", "INDICATOR_DAILY_REVENUE",
                "indicator-update");

        assertThat(deleted).isEqualTo(5);
        // INDICATOR scope still uses factory-wide invalidation (semantic cache has no indicator col)
        verify(semanticCacheService).invalidateByFactory("RES_3101_009");
    }

    @Test
    void purgeAll_combinesSemanticAndExpiredToolCleanup() {
        when(semanticCacheService.invalidateByFactory("F006")).thenReturn(10);
        when(toolCallCacheRepository.deleteExpiredCache(any(LocalDateTime.class))).thenReturn(3);

        int deleted = service.purgeAll("F006", "ops-emergency");

        assertThat(deleted).isEqualTo(13);
        verify(semanticCacheService).invalidateByFactory("F006");
        verify(toolCallCacheRepository).deleteExpiredCache(any(LocalDateTime.class));
    }

    @Test
    void purgeAll_blankFactory_returnsZeroNoCallsMade() {
        int deleted = service.purgeAll(null, "global-flush-attempt");

        assertThat(deleted).isZero();
        verify(semanticCacheService, never()).invalidateByFactory(anyString());
        verify(toolCallCacheRepository, never()).deleteExpiredCache(any());
    }

    @Test
    void purge_eventDispatchByScope_ROUTING() {
        when(semanticCacheService.invalidateByIntentCode("F001", "FOO")).thenReturn(1);

        int deleted = service.purge(CachePurgeEvent.routing("F001", "FOO", "test"));

        assertThat(deleted).isEqualTo(1);
        verify(semanticCacheService).invalidateByIntentCode("F001", "FOO");
    }

    @Test
    void purge_eventDispatchByScope_INDICATOR() {
        when(semanticCacheService.invalidateByFactory("RES_3101_009")).thenReturn(2);

        int deleted = service.purge(CachePurgeEvent.indicator("RES_3101_009", "IND_X", "test"));

        assertThat(deleted).isEqualTo(2);
        verify(semanticCacheService).invalidateByFactory("RES_3101_009");
    }

    @Test
    void purge_eventDispatchByScope_ALL() {
        when(semanticCacheService.invalidateByFactory("F006")).thenReturn(5);
        when(toolCallCacheRepository.deleteExpiredCache(any())).thenReturn(2);

        int deleted = service.purge(CachePurgeEvent.all("F006", "emergency"));

        assertThat(deleted).isEqualTo(7);
    }

    @Test
    void purge_nullEvent_returnsZeroSafely() {
        int deleted = service.purge(null);
        assertThat(deleted).isZero();
        verify(semanticCacheService, never()).invalidateByFactory(anyString());
    }

    @Test
    void purge_isIdempotent_callTwiceSameResult() {
        when(semanticCacheService.invalidateByIntentCode(eq("F001"), eq("X")))
                .thenReturn(3).thenReturn(0);

        int first = service.purge(CachePurgeEvent.routing("F001", "X", "round-1"));
        int second = service.purge(CachePurgeEvent.routing("F001", "X", "round-2"));

        assertThat(first).isEqualTo(3);
        assertThat(second).isZero(); // second call hits already-empty cache, safe
        verify(semanticCacheService, times(2)).invalidateByIntentCode("F001", "X");
    }
}
