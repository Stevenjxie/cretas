package com.cretas.aims.service.canvas;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.impl.ThresholdResolverServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ThresholdResolverServiceImpl 单元测试 — Canvas-Thresholds Phase A.
 *
 * Coverage:
 * <ol>
 *   <li>per-factory row hit → returns DB value</li>
 *   <li>per-factory miss + global hit → returns global value</li>
 *   <li>both miss → returns caller default</li>
 *   <li>integer / decimal / double parsing</li>
 *   <li>disabled row → fallback to global / caller default</li>
 *   <li>cache hit: 2nd call does not hit repository</li>
 *   <li>invalidate(factoryId, key) flushes single entry</li>
 *   <li>invalidateFactory(factoryId) flushes all factoryId entries</li>
 *   <li>parse error → fallback gracefully (does not throw)</li>
 * </ol>
 */
@DisplayName("ThresholdResolverServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class ThresholdResolverServiceImplTest {

    @Mock
    private FactoryThresholdRepository repository;

    @InjectMocks
    private ThresholdResolverServiceImpl resolver;

    @BeforeEach
    void clearCache() {
        // Each test starts with a fresh cache to isolate behavior.
        resolver.invalidateAll();
    }

    private static FactoryThreshold makeRow(String factoryId, String key,
                                            ThresholdValueType type, String value, boolean enabled) {
        FactoryThreshold t = new FactoryThreshold();
        t.setFactoryId(factoryId);
        t.setThresholdKey(key);
        t.setCategory(ThresholdCategory.INVENTORY);
        t.setValueType(type);
        t.setThresholdValue(value);
        t.setDefaultValue(value);
        t.setEnabled(enabled);
        return t;
    }

    @Test
    @DisplayName("per-factory row hit → 返回 DB 值")
    void perFactoryHit() {
        FactoryThreshold row = makeRow("F001", "inventory.turnover.red",
                ThresholdValueType.DECIMAL, "9", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "inventory.turnover.red"))
                .thenReturn(Optional.of(row));

        BigDecimal result = resolver.getBigDecimal("F001", "inventory.turnover.red", new BigDecimal("6"));

        assertEquals(new BigDecimal("9"), result);
        verify(repository).findByFactoryIdAndThresholdKey("F001", "inventory.turnover.red");
        verify(repository, never()).findByFactoryIdAndThresholdKey(eq("*"), eq("inventory.turnover.red"));
    }

    @Test
    @DisplayName("per-factory miss + global hit → 返回 global 值")
    void globalFallback() {
        FactoryThreshold globalRow = makeRow("*", "inventory.expiry.red",
                ThresholdValueType.DECIMAL, "20", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "inventory.expiry.red"))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndThresholdKey("*", "inventory.expiry.red"))
                .thenReturn(Optional.of(globalRow));

        BigDecimal result = resolver.getBigDecimal("F001", "inventory.expiry.red", new BigDecimal("15"));

        assertEquals(new BigDecimal("20"), result);
    }

    @Test
    @DisplayName("both miss → 返回 caller 默认值")
    void allMissReturnsCallerDefault() {
        when(repository.findByFactoryIdAndThresholdKey("F001", "x.y.z"))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndThresholdKey("*", "x.y.z"))
                .thenReturn(Optional.empty());

        BigDecimal result = resolver.getBigDecimal("F001", "x.y.z", new BigDecimal("42"));
        assertEquals(new BigDecimal("42"), result);
    }

    @Test
    @DisplayName("INTEGER 解析正确")
    void integerParsing() {
        FactoryThreshold row = makeRow("F001", "bom.max_depth",
                ThresholdValueType.INTEGER, "15", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "bom.max_depth"))
                .thenReturn(Optional.of(row));

        int result = resolver.getInteger("F001", "bom.max_depth", 10);
        assertEquals(15, result);
    }

    @Test
    @DisplayName("DOUBLE 解析正确")
    void doubleParsing() {
        FactoryThreshold row = makeRow("F001", "iot.cold_chain.temp_max",
                ThresholdValueType.DOUBLE, "-22.5", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "iot.cold_chain.temp_max"))
                .thenReturn(Optional.of(row));

        double result = resolver.getDouble("F001", "iot.cold_chain.temp_max", -18.0);
        assertEquals(-22.5, result);
    }

    @Test
    @DisplayName("disabled row → 回退到 global / caller 默认值")
    void disabledRowFallback() {
        FactoryThreshold perFactory = makeRow("F001", "inventory.loss.red",
                ThresholdValueType.DECIMAL, "99", false /* disabled */);
        when(repository.findByFactoryIdAndThresholdKey("F001", "inventory.loss.red"))
                .thenReturn(Optional.of(perFactory));
        when(repository.findByFactoryIdAndThresholdKey("*", "inventory.loss.red"))
                .thenReturn(Optional.empty());

        BigDecimal result = resolver.getBigDecimal("F001", "inventory.loss.red", new BigDecimal("5"));
        // Disabled per-factory row 必须 fallback 到 global; global 也 miss 则用 caller 默认值
        assertEquals(new BigDecimal("5"), result);
    }

    @Test
    @DisplayName("cache hit: 2 次调用只 hit repository 一次")
    void cacheHitAvoidsRepositoryRoundTrip() {
        FactoryThreshold row = makeRow("F001", "inventory.turnover.red",
                ThresholdValueType.DECIMAL, "8", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "inventory.turnover.red"))
                .thenReturn(Optional.of(row));

        resolver.getBigDecimal("F001", "inventory.turnover.red", new BigDecimal("6"));
        resolver.getBigDecimal("F001", "inventory.turnover.red", new BigDecimal("6"));
        resolver.getBigDecimal("F001", "inventory.turnover.red", new BigDecimal("6"));

        verify(repository, times(1)).findByFactoryIdAndThresholdKey("F001", "inventory.turnover.red");
    }

    @Test
    @DisplayName("invalidate(factoryId, key) 失效单条缓存")
    void invalidateSingleEntry() {
        FactoryThreshold row1 = makeRow("F001", "k1",
                ThresholdValueType.DECIMAL, "1", true);
        FactoryThreshold row2 = makeRow("F001", "k1",
                ThresholdValueType.DECIMAL, "2", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "k1"))
                .thenReturn(Optional.of(row1))
                .thenReturn(Optional.of(row2));

        BigDecimal first = resolver.getBigDecimal("F001", "k1", BigDecimal.ZERO);
        resolver.invalidate("F001", "k1");
        BigDecimal second = resolver.getBigDecimal("F001", "k1", BigDecimal.ZERO);

        assertEquals(new BigDecimal("1"), first);
        assertEquals(new BigDecimal("2"), second);
        verify(repository, times(2)).findByFactoryIdAndThresholdKey("F001", "k1");
    }

    @Test
    @DisplayName("invalidateFactory(factoryId) 失效整个工厂的缓存")
    void invalidateFactoryFlushesAllKeys() {
        when(repository.findByFactoryIdAndThresholdKey("F001", "k1"))
                .thenReturn(Optional.of(makeRow("F001", "k1", ThresholdValueType.DECIMAL, "1", true)));
        when(repository.findByFactoryIdAndThresholdKey("F001", "k2"))
                .thenReturn(Optional.of(makeRow("F001", "k2", ThresholdValueType.DECIMAL, "2", true)));

        resolver.getBigDecimal("F001", "k1", BigDecimal.ZERO);
        resolver.getBigDecimal("F001", "k2", BigDecimal.ZERO);
        resolver.invalidateFactory("F001");
        resolver.getBigDecimal("F001", "k1", BigDecimal.ZERO);
        resolver.getBigDecimal("F001", "k2", BigDecimal.ZERO);

        verify(repository, times(2)).findByFactoryIdAndThresholdKey("F001", "k1");
        verify(repository, times(2)).findByFactoryIdAndThresholdKey("F001", "k2");
    }

    @Test
    @DisplayName("parse 错误时 fallback 到 caller 默认值, 不抛异常")
    void parseErrorFallsBackGracefully() {
        FactoryThreshold corrupt = makeRow("F001", "bad.value",
                ThresholdValueType.INTEGER, "not-a-number", true);
        when(repository.findByFactoryIdAndThresholdKey("F001", "bad.value"))
                .thenReturn(Optional.of(corrupt));

        // Must NOT throw — service stays running with caller default.
        int result = assertDoesNotThrow(() ->
                resolver.getInteger("F001", "bad.value", 7));
        assertEquals(7, result);
    }

    @Test
    @DisplayName("null factoryId / null key → empty Optional + caller 默认")
    void nullInputs() {
        assertEquals(Optional.empty(), resolver.resolve(null, "x"));
        assertEquals(Optional.empty(), resolver.resolve("F001", null));
        // Use case from service callers: factoryId may be null in batch contexts.
        assertEquals(5, resolver.getInteger(null, "x", 5));
    }
}
