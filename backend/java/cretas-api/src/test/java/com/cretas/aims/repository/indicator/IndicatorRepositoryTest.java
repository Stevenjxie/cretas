package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Sprint 1 Day 3 — JPA slice test for {@link IndicatorRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Basic CRUD + ID assignment by @PrePersist</li>
 *   <li>findByCodeAndFactoryIdAndDeletedAtIsNull returns the right indicator</li>
 *   <li>findByFactoryIdAndCategory filters by category + isActive</li>
 *   <li>Factory isolation: F001 query never sees F006 indicator</li>
 * </ol>
 *
 * <p>Uses H2 in PostgreSQL compat mode (application-test.properties). JSONB
 * {@code config} field left null per known H2 round-trip limitation
 * (see CanvasOptimisticLockingTest.alertRule_hasVersionAnnotation Javadoc).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.indicator")
@DisplayName("IndicatorRepository — JPA slice (H2 PG-compat)")
class IndicatorRepositoryTest {

    private static final String F1 = "F-IND-1";
    private static final String F2 = "F-IND-2";

    @Autowired private IndicatorRepository repo;

    @Test
    @DisplayName("save + findByCodeAndFactoryIdAndDeletedAtIsNull — basic CRUD + UUID assigned")
    void saveAndFindByCode() {
        Indicator i = newIndicator(F1, "FACTORY_YIELD_RATE", "FACTORY", "工厂良品率", true);
        Indicator saved = repo.saveAndFlush(i);

        assertNotNull(saved.getId(), "@PrePersist must assign UUID");
        assertNotNull(saved.getCreatedAt(), "BaseEntity @CreatedDate must be set");

        Optional<Indicator> found = repo.findByCodeAndFactoryIdAndDeletedAtIsNull(
                "FACTORY_YIELD_RATE", F1);
        assertTrue(found.isPresent(), "should locate indicator by (code, factoryId)");
        assertEquals("工厂良品率", found.get().getName());
        assertEquals("FACTORY", found.get().getCategory());
    }

    @Test
    @DisplayName("findByFactoryIdAndCategoryAndIsActiveTrue — filters by category + isActive=true")
    void findByCategoryAndActive() {
        // F1: 2 FACTORY active + 1 FACTORY inactive + 1 RESTAURANT active
        repo.saveAndFlush(newIndicator(F1, "FACT_A", "FACTORY", "工厂A", true));
        repo.saveAndFlush(newIndicator(F1, "FACT_B", "FACTORY", "工厂B", true));
        repo.saveAndFlush(newIndicator(F1, "FACT_C", "FACTORY", "工厂C", false));
        repo.saveAndFlush(newIndicator(F1, "REST_A", "RESTAURANT", "餐饮A", true));

        List<Indicator> factoryActive =
                repo.findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(F1, "FACTORY");

        assertEquals(2, factoryActive.size(),
                "inactive FACT_C excluded, RESTAURANT excluded; only FACT_A + FACT_B");
        List<String> codes = factoryActive.stream().map(Indicator::getCode).toList();
        assertTrue(codes.contains("FACT_A"));
        assertTrue(codes.contains("FACT_B"));
        assertFalse(codes.contains("FACT_C"), "inactive must be excluded");
        assertFalse(codes.contains("REST_A"), "different category must be excluded");
    }

    @Test
    @DisplayName("Factory isolation — F1 query does NOT see F2 indicators")
    void factoryIsolation() {
        repo.saveAndFlush(newIndicator(F1, "SAME_CODE", "FACTORY", "F1 yield", true));
        repo.saveAndFlush(newIndicator(F2, "SAME_CODE", "FACTORY", "F2 yield", true));

        Optional<Indicator> f1 = repo.findByCodeAndFactoryIdAndDeletedAtIsNull("SAME_CODE", F1);
        Optional<Indicator> f2 = repo.findByCodeAndFactoryIdAndDeletedAtIsNull("SAME_CODE", F2);

        assertTrue(f1.isPresent());
        assertTrue(f2.isPresent());
        assertEquals("F1 yield", f1.get().getName(), "F1 sees its own");
        assertEquals("F2 yield", f2.get().getName(), "F2 sees its own (separate row by uniq idx)");
        assertNotEquals(f1.get().getId(), f2.get().getId());

        // existsByFactoryIdAndCode
        assertTrue(repo.existsByFactoryIdAndCodeAndDeletedAtIsNull(F1, "SAME_CODE"));
        assertFalse(repo.existsByFactoryIdAndCodeAndDeletedAtIsNull(F1, "NON_EXISTENT"));
    }

    // ============================================================
    // Helper
    // ============================================================

    private Indicator newIndicator(String factoryId, String code, String category,
                                    String name, boolean active) {
        Indicator i = new Indicator();
        i.setFactoryId(factoryId);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setIsActive(active);
        i.setComputeStrategy("CACHED");
        i.setCacheTtlSeconds(3600);
        // Null out jsonb config — H2 PG-compat mode can't round-trip empty Map via
        // JsonBinaryType (see CanvasOptimisticLockingTest Javadoc for full rationale).
        i.setConfig(null);
        return i;
    }
}
