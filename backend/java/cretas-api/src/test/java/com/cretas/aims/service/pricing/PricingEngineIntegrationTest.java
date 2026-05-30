package com.cretas.aims.service.pricing;

import com.cretas.aims.entity.pricing.PricingApplicationLog;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.impl.PricingEngineImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link PricingEngineImpl} — Canvas-Pricing Phase 4b.
 *
 * <p>Exercises the JPA slice (real H2-in-PG-compat DB) end-to-end:
 * <ol>
 *   <li>Insert TIERED strategy (qty &gt;= 500 → 10% off)</li>
 *   <li>Invoke {@code PricingEngine.calculate(qty=600, unitPrice=10)}</li>
 *   <li>Verify finalPrice = 5400 (10% off 6000), totalDiscount = 600</li>
 *   <li>Verify PricingApplicationLog row persisted with correct fields</li>
 * </ol>
 *
 * <p>Uses {@link DataJpaTest} (JPA slice only, no full Spring context) to avoid
 * pulling the AI bean graph. Service under test instantiated manually with
 * real repositories.
 *
 * <p>Profile: {@code test} — H2 in PostgreSQL compatibility mode,
 * see {@code application-test.properties}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.pricing")
@DisplayName("PricingEngine — JPA slice integration")
class PricingEngineIntegrationTest {

    private static final String FACTORY_ID = "F-PRICING-IT";

    @Autowired private PricingStrategyRepository strategyRepo;
    @Autowired private PricingApplicationLogRepository logRepo;

    private PricingEngineImpl engine;

    @BeforeEach
    void setUp() {
        engine = new PricingEngineImpl(strategyRepo, logRepo);
    }

    @Test
    @DisplayName("TIERED 500件 → 10% off — 600件下单, finalPrice=5400 + log persisted")
    void tieredStrategy_appliesDiscount_andPersistsLog() {
        // 1. Insert TIERED strategy (100件 ¥10, 500件 ¥9 = 10% off)
        Map<String, Object> rules = new HashMap<>();
        rules.put("tiers", List.of(
                tier(100, 499, new BigDecimal("0")),
                tier(500, null, new BigDecimal("10"))   // 10% off at 500+
        ));

        PricingStrategy strategy = new PricingStrategy();
        strategy.setFactoryId(FACTORY_ID);
        strategy.setStrategyCode("IT_TIERED");
        strategy.setStrategyName("Integration Test Tiered");
        strategy.setStrategyType(PricingStrategyType.TIERED);
        strategy.setRulesJson(rules);
        strategy.setPriority(50);
        strategy.setEnabled(true);
        strategy.setValidFrom(LocalDate.now().minusDays(1));
        strategy.setValidTo(LocalDate.now().plusDays(30));
        strategyRepo.saveAndFlush(strategy);

        // 2. Call calculate
        PricingRequest req = PricingRequest.builder()
                .factoryId(FACTORY_ID)
                .productId("P-IT-001")
                .quantity(new BigDecimal("600"))
                .unitPriceList(new BigDecimal("10.00"))
                .businessEntityType("SO_LINE")
                .businessEntityId("SO-IT-LINE-001")
                .build();

        PricingResult result = engine.calculate(req);

        // 3. Verify result: original = 600 × 10 = 6000; 10% off = 600 discount; final = 5400
        assertEquals(new BigDecimal("6000.00"), result.getOriginalPrice(),
                "originalPrice should be qty * unitPriceList");
        assertEquals(new BigDecimal("5400.00"), result.getFinalPrice(),
                "finalPrice = 6000 - 600 (10% off)");
        assertEquals(new BigDecimal("600.00"), result.getTotalDiscount(),
                "totalDiscount = 600");
        assertNotNull(result.getAppliedStrategies(), "appliedStrategies should not be null");
        assertEquals(1, result.getAppliedStrategies().size(), "1 TIERED strategy applied");
        assertEquals(PricingStrategyType.TIERED,
                result.getAppliedStrategies().get(0).getStrategyType());

        // 4. Verify PricingApplicationLog row persisted (calculate writes log)
        List<PricingApplicationLog> logs = logRepo.findAll();
        assertEquals(1, logs.size(), "Expected 1 audit log row");
        PricingApplicationLog logged = logs.get(0);
        assertEquals(FACTORY_ID, logged.getFactoryId());
        assertEquals("SO_LINE", logged.getBusinessEntityType());
        assertEquals("SO-IT-LINE-001", logged.getBusinessEntityId());
        assertEquals(0, new BigDecimal("6000.00").compareTo(logged.getOriginalPrice()),
                "Log originalPrice should match");
        assertEquals(0, new BigDecimal("5400.00").compareTo(logged.getFinalPrice()),
                "Log finalPrice should match");
        assertEquals(0, new BigDecimal("600.00").compareTo(logged.getDiscount()),
                "Log discount should match");
        assertNotNull(logged.getAppliedAt(), "appliedAt timestamp should be set");
        assertNotNull(logged.getAppliedStrategies(), "appliedStrategies JSONB should be set");
        assertEquals(1, logged.getAppliedStrategies().size(),
                "1 strategy in JSONB array");
    }

    private static Map<String, Object> tier(Integer minQty, Integer maxQty, BigDecimal discountPct) {
        Map<String, Object> t = new HashMap<>();
        t.put("minQty", minQty);
        if (maxQty != null) t.put("maxQty", maxQty);
        t.put("discountPct", discountPct);
        return t;
    }
}
