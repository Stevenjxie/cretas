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
import static org.mockito.Mockito.when;

/**
 * Demonstration test — Canvas-Thresholds Phase A.
 *
 * <p>Shows the "before fix" (hard-coded constant) vs "after fix" (DB override) behavior for
 * the inventory-health turnover-red threshold. With no DB row, the resolver returns the
 * caller's fallback (mirroring the original hard-coded 6). With a DB row set to 8, the
 * resolver returns 8 — which is what callers like
 * {@code InventoryHealthAnalysisServiceImpl.determineTurnoverAlertLevel} now use.
 *
 * <p>This test does NOT spin up Spring context. It exercises the resolver directly to prove
 * the override mechanism — a sister integration test (out of Phase A scope) would wire
 * the full service + repository for end-to-end verification.
 */
@DisplayName("Inventory threshold overlay — before/after fix demonstration")
@ExtendWith(MockitoExtension.class)
class InventoryHealthThresholdOverlayTest {

    @Mock
    private FactoryThresholdRepository repository;

    @InjectMocks
    private ThresholdResolverServiceImpl resolver;

    @BeforeEach
    void clearCache() {
        resolver.invalidateAll();
    }

    @Test
    @DisplayName("Before fix: 没有 DB row → 返回 hard-coded 6 (mirror 老行为)")
    void beforeFix_HardCodedConstant() {
        when(repository.findByFactoryIdAndThresholdKey("F001", ThresholdKeys.INVENTORY_TURNOVER_RED))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndThresholdKey("*", ThresholdKeys.INVENTORY_TURNOVER_RED))
                .thenReturn(Optional.empty());

        BigDecimal threshold = resolver.getBigDecimal("F001",
                ThresholdKeys.INVENTORY_TURNOVER_RED, new BigDecimal("6"));

        // 老行为: 调用方传入 6 作为 fallback, 没有 DB row → 仍然返回 6.
        assertEquals(new BigDecimal("6"), threshold);

        // 决策: rate=5 < 6 → RED alert (跟 fix 前行为一致)
        BigDecimal rate = new BigDecimal("5");
        assertTrue(rate.compareTo(threshold) < 0, "rate < threshold should fire RED");
    }

    @Test
    @DisplayName("After fix: F001 配置 turnover.red=8 → 阈值变 8, 影响 RED/YELLOW 边界")
    void afterFix_PerFactoryDbOverride() {
        FactoryThreshold customRow = new FactoryThreshold();
        customRow.setFactoryId("F001");
        customRow.setThresholdKey(ThresholdKeys.INVENTORY_TURNOVER_RED);
        customRow.setCategory(ThresholdCategory.INVENTORY);
        customRow.setValueType(ThresholdValueType.DECIMAL);
        customRow.setThresholdValue("8");
        customRow.setDefaultValue("6");
        customRow.setEnabled(true);
        when(repository.findByFactoryIdAndThresholdKey("F001", ThresholdKeys.INVENTORY_TURNOVER_RED))
                .thenReturn(Optional.of(customRow));

        BigDecimal threshold = resolver.getBigDecimal("F001",
                ThresholdKeys.INVENTORY_TURNOVER_RED, new BigDecimal("6"));

        // 新行为: F001 配置 8 → 返回 8 (覆盖 fallback 6)
        assertEquals(new BigDecimal("8"), threshold);

        // 业务影响: 周转率 7 在 fix 前是 GREEN (7 > 6), fix 后 F001 是 RED (7 < 8) — 工厂可自定义敏感度.
        BigDecimal rate = new BigDecimal("7");
        assertTrue(rate.compareTo(threshold) < 0,
                "Per-factory threshold 8 makes rate=7 fire RED, which the global default 6 would not");
    }

    @Test
    @DisplayName("Global default 行 → 所有未配置工厂统一 fallback (而非 caller hard-coded)")
    void globalDefault_AppliesToAllFactoriesNotCustomized() {
        FactoryThreshold globalRow = new FactoryThreshold();
        globalRow.setFactoryId("*");
        globalRow.setThresholdKey(ThresholdKeys.INVENTORY_TURNOVER_RED);
        globalRow.setCategory(ThresholdCategory.INVENTORY);
        globalRow.setValueType(ThresholdValueType.DECIMAL);
        globalRow.setThresholdValue("7"); // global override default
        globalRow.setDefaultValue("6");
        globalRow.setEnabled(true);

        // F999 没有 per-factory 行, 但 global '*' 配置了 7.
        when(repository.findByFactoryIdAndThresholdKey("F999", ThresholdKeys.INVENTORY_TURNOVER_RED))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndThresholdKey("*", ThresholdKeys.INVENTORY_TURNOVER_RED))
                .thenReturn(Optional.of(globalRow));

        BigDecimal threshold = resolver.getBigDecimal("F999",
                ThresholdKeys.INVENTORY_TURNOVER_RED, new BigDecimal("6"));

        // F999 拿到 global 7, 不是 caller 默认 6 — 管理员通过 global row 全局调整.
        assertEquals(new BigDecimal("7"), threshold);
    }
}
