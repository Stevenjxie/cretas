package com.cretas.aims.scheduler;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * Phase 1 Day 9 — branch coverage tests for {@link IndicatorRecomputeScheduler}.
 *
 * <p>Spec demands 100% branch coverage on dispatcher logic. Each test exercises
 * one branch of the recompute loop: enabled flag / strategy filter / per-indicator
 * exception handling / empty indicator list / factory load failure / time window.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndicatorRecomputeScheduler — Phase 1 Day 9 branch coverage")
class IndicatorRecomputeSchedulerTest {

    @Mock FactoryRepository factoryRepository;
    @Mock IndicatorRepository indicatorRepository;
    @Mock IndicatorQueryService indicatorQueryService;
    @InjectMocks IndicatorRecomputeScheduler scheduler;

    @BeforeEach
    void enableByDefault() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
    }

    private Indicator stubIndicator(String code, String strategy) {
        Indicator i = new Indicator();
        i.setCode(code);
        i.setComputeStrategy(strategy);
        return i;
    }

    private IndicatorValueResult stubResult(String source) {
        return new IndicatorValueResult(BigDecimal.ONE, LocalDateTime.now(), false, source);
    }

    @Test
    @DisplayName("Happy path: 1 factory × 2 active indicators → both recomputed")
    void happyPath() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F006"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("RESTAURANT_TABLE_TURNOVER", "REALTIME"),
                stubIndicator("FACTORY_YIELD_RATE", "CACHED")
        ));
        when(indicatorQueryService.computeForCode(anyString(), eq("F006"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(stubResult("python"));

        scheduler.recomputeAll();

        verify(indicatorQueryService, times(2))
                .computeForCode(anyString(), eq("F006"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @DisplayName("PRECOMPUTED strategy is skipped (Phase 2B Python cron owns these)")
    void precomputedSkipped() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F006"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("FACTORY_OEE", "PRECOMPUTED"),
                stubIndicator("FACTORY_YIELD_RATE", "CACHED")
        ));
        when(indicatorQueryService.computeForCode(eq("FACTORY_YIELD_RATE"), eq("F006"),
                any(LocalDate.class), any(LocalDate.class))).thenReturn(stubResult("cache"));

        scheduler.recomputeAll();

        // PRECOMPUTED never reaches dispatcher
        verify(indicatorQueryService, never())
                .computeForCode(eq("FACTORY_OEE"), anyString(), any(), any());
        // CACHED does
        verify(indicatorQueryService, times(1))
                .computeForCode(eq("FACTORY_YIELD_RATE"), eq("F006"), any(), any());
    }

    @Test
    @DisplayName("Per-indicator failure does not abort batch (degraded mode)")
    void perIndicatorFailureIsolated() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F006"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("BAD_INDICATOR", "REALTIME"),
                stubIndicator("GOOD_INDICATOR", "REALTIME")
        ));
        when(indicatorQueryService.computeForCode(eq("BAD_INDICATOR"), eq("F006"), any(), any()))
                .thenThrow(new RuntimeException("simulated python timeout"));
        when(indicatorQueryService.computeForCode(eq("GOOD_INDICATOR"), eq("F006"), any(), any()))
                .thenReturn(stubResult("python"));

        scheduler.recomputeAll();

        // Both called — failure in first did not skip second
        verify(indicatorQueryService).computeForCode(eq("BAD_INDICATOR"), eq("F006"), any(), any());
        verify(indicatorQueryService).computeForCode(eq("GOOD_INDICATOR"), eq("F006"), any(), any());
    }

    @Test
    @DisplayName("Empty active factories list → graceful no-op (no NPE)")
    void emptyFactories() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(Collections.emptyList());

        scheduler.recomputeAll();

        verify(indicatorRepository, never()).findActiveByFactoryId(anyString());
        verify(indicatorQueryService, never()).computeForCode(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Disabled (enabled=false) → no factory load, no dispatch")
    void disabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.recomputeAll();

        verify(factoryRepository, never()).findAllActiveFactoryIds();
        verify(indicatorQueryService, never()).computeForCode(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Factory list load failure → return without dispatch (logged)")
    void factoryLoadFailure() {
        when(factoryRepository.findAllActiveFactoryIds())
                .thenThrow(new RuntimeException("simulated DB outage"));

        scheduler.recomputeAll();

        verify(indicatorRepository, never()).findActiveByFactoryId(anyString());
        verify(indicatorQueryService, never()).computeForCode(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Per-factory indicator load failure → continue to next factory")
    void perFactoryLoadFailureIsolated() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F001", "F006"));
        when(indicatorRepository.findActiveByFactoryId("F001"))
                .thenThrow(new RuntimeException("simulated bad factory data"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("OK", "REALTIME")
        ));
        lenient().when(indicatorQueryService.computeForCode(eq("OK"), eq("F006"), any(), any()))
                .thenReturn(stubResult("python"));

        scheduler.recomputeAll();

        // F001 failed → no dispatch for F001
        verify(indicatorQueryService, never()).computeForCode(anyString(), eq("F001"), any(), any());
        // F006 still ran
        verify(indicatorQueryService).computeForCode(eq("OK"), eq("F006"), any(), any());
    }

    @Test
    @DisplayName("Period window = (today-30days, today)")
    void periodWindowCorrect() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F006"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("CHECK", "REALTIME")
        ));
        when(indicatorQueryService.computeForCode(eq("CHECK"), eq("F006"), any(), any()))
                .thenReturn(stubResult("python"));

        LocalDate beforeRun = LocalDate.now();
        scheduler.recomputeAll();
        LocalDate afterRun = LocalDate.now();

        verify(indicatorQueryService).computeForCode(
                eq("CHECK"),
                eq("F006"),
                org.mockito.ArgumentMatchers.argThat(d ->
                        !d.isBefore(beforeRun.minusDays(30)) && !d.isAfter(afterRun.minusDays(30))),
                org.mockito.ArgumentMatchers.argThat(d ->
                        !d.isBefore(beforeRun) && !d.isAfter(afterRun))
        );
    }

    @Test
    @DisplayName("Null IndicatorValueResult is counted as skipped (not failed)")
    void nullResultSkipped() {
        when(factoryRepository.findAllActiveFactoryIds()).thenReturn(List.of("F006"));
        when(indicatorRepository.findActiveByFactoryId("F006")).thenReturn(List.of(
                stubIndicator("UNCERTAIN", "CACHED")
        ));
        when(indicatorQueryService.computeForCode(eq("UNCERTAIN"), eq("F006"), any(), any()))
                .thenReturn(null);

        scheduler.recomputeAll();

        verify(indicatorQueryService).computeForCode(eq("UNCERTAIN"), eq("F006"), any(), any());
        // No exception thrown — null path handled
    }
}
