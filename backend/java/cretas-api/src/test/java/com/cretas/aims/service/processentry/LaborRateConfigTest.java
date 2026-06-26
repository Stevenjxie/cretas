package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.config.FactoryCostSettings;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.config.FactoryCostSettingsRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SP-C Task 2 — 工时单价从 factory_cost_settings 读取 (resolveLaborRate).
 *
 * <p>用例1: 配置 ¥30/工时 → rate=30, 无 warning.
 * <p>用例2: 未配置 → 回退 ¥0 + warnings 含"工时单价未配置".
 * <p>用例3: laborHourlyRate 为 null → 也触发 fallback(¥0) + warning.
 * <p>用例4: costSettingsRepository null (老测试 @InjectMocks 场景) → LABOR_RATE_DEFAULT(¥26) 不 NPE.
 * <p>用例5: 配置 ¥30, 60min×2人 → laborCost = 60 (集成回归: SP-B1 ¥26×2×1h = 52 不变).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborRateConfigTest — SP-C Task 2 工时单价配置")
class LaborRateConfigTest {

    private static final String FACTORY = "DEMO_FACTORY";

    // All deps mocked so @InjectMocks can construct — mirrors ClerkProcessEntryServiceImplTest pattern
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private ProductRecipeRepository recipeRepo;
    @Mock private RecipeIngredientRepository ingredientRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private FactoryCostSettingsRepository costSettingsRepo;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    // ObjectMapper injection — same trick as ClerkProcessEntryServiceImplTest
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────
    // resolveLaborRate
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("用例1: 配置 ¥30/工时 → resolveLaborRate 返回 30, 无 warning")
    void configuredRate_returnsConfiguredValue() throws Exception {
        injectObjectMapper();
        FactoryCostSettings cfg = new FactoryCostSettings();
        cfg.setFactoryId(FACTORY);
        cfg.setLaborHourlyRate(new BigDecimal("30"));
        when(costSettingsRepo.findByFactoryId(FACTORY)).thenReturn(Optional.of(cfg));

        List<String> warnings = new ArrayList<>();
        BigDecimal rate = service.resolveLaborRate(FACTORY, warnings);

        assertThat(rate).isEqualByComparingTo("30");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("用例2: 未配置 → 回退 ¥0, warnings 含'工时单价未配置'")
    void unconfigured_fallsBackToDefault_withWarning() throws Exception {
        injectObjectMapper();
        when(costSettingsRepo.findByFactoryId(FACTORY)).thenReturn(Optional.empty());

        List<String> warnings = new ArrayList<>();
        BigDecimal rate = service.resolveLaborRate(FACTORY, warnings);

        assertThat(rate).isEqualByComparingTo("26");
        assertThat(warnings).anyMatch(w -> w.contains("工时单价未配置"));
    }

    @Test
    @DisplayName("用例2b: laborHourlyRate 为 null → 也触发 fallback(¥0) + warning")
    void nullRate_fallsBackToDefault_withWarning() throws Exception {
        injectObjectMapper();
        FactoryCostSettings cfg = new FactoryCostSettings();
        cfg.setFactoryId(FACTORY);
        cfg.setLaborHourlyRate(null);
        when(costSettingsRepo.findByFactoryId(FACTORY)).thenReturn(Optional.of(cfg));

        List<String> warnings = new ArrayList<>();
        BigDecimal rate = service.resolveLaborRate(FACTORY, warnings);

        assertThat(rate).isEqualByComparingTo("26");
        assertThat(warnings).anyMatch(w -> w.contains("工时单价未配置"));
    }

    @Test
    @DisplayName("用例3: costSettingsRepository 为 null (@InjectMocks 未注入场景) → ¥26 不 NPE")
    void nullRepo_fallsBackToDefault_noNPE() throws Exception {
        injectObjectMapper();
        // Forcibly set repo to null to simulate old-test scenario
        ReflectionTestUtils.setField(service, "costSettingsRepository", null);

        List<String> warnings = new ArrayList<>();
        BigDecimal rate = service.resolveLaborRate(FACTORY, warnings);

        assertThat(rate).isEqualByComparingTo("26");
    }

    @Test
    @DisplayName("用例5: 配置 ¥30, 60min×2人 → laborCost = 60 (SP-B1回归: ¥26×2=52不变)")
    void computeLaborCost_withConfiguredRate_60() throws Exception {
        injectObjectMapper();
        FactoryCostSettings cfg = new FactoryCostSettings();
        cfg.setFactoryId(FACTORY);
        cfg.setLaborHourlyRate(new BigDecimal("30"));
        when(costSettingsRepo.findByFactoryId(FACTORY)).thenReturn(Optional.of(cfg));

        List<String> warnings = new ArrayList<>();
        BigDecimal rate = service.resolveLaborRate(FACTORY, warnings);

        // 60 min × 2 workers = 2 工时 × ¥30 = ¥60
        int minutes = service.minutesBetween("08:00", "09:00");
        assertThat(minutes).isEqualTo(60);
        BigDecimal workerHours = new BigDecimal(minutes)
                .divide(new BigDecimal("60"), 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal(2));
        BigDecimal laborCost = workerHours.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);

        assertThat(laborCost).isEqualByComparingTo("60.00");
    }

    private void injectObjectMapper() throws Exception {
        var f = ClerkProcessEntryServiceImpl.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }
}
