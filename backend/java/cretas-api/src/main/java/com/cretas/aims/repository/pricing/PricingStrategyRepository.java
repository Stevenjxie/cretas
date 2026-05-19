package com.cretas.aims.repository.pricing;

import com.cretas.aims.entity.pricing.PricingStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 价格策略 Repo — Canvas-Pricing Phase 4b.
 *
 * <p>核心查询是 {@link #findActiveByFactoryIdOrderByPriorityAsc} — PricingEngine 热路径.
 * 用 @Query + CAST(:validFrom AS date) 防御 PG 严格类型推断
 * (per .claude/rules/database-entity-sync.md — column-side IS NULL 不需要 CAST,
 * 但 valid_from/valid_to 双向 null-safe 需要 CAST hint).
 */
@Repository
public interface PricingStrategyRepository extends JpaRepository<PricingStrategy, String> {

    Optional<PricingStrategy> findByFactoryIdAndStrategyCode(String factoryId, String strategyCode);

    List<PricingStrategy> findByFactoryIdOrderByPriorityAscCreatedAtDesc(String factoryId);

    /**
     * Active strategies for hot-path: enabled + valid_from <= now + valid_to >= now,
     * ordered by priority asc (lower number = higher priority).
     *
     * <p>NULL valid_from / valid_to 视为无边界 (always valid that side).
     * Use {@code CAST(:asOfDate AS date)} per PG strict type inference rule.
     */
    @Query("""
            SELECT p FROM PricingStrategy p
            WHERE p.factoryId = :factoryId
              AND p.enabled = true
              AND (p.validFrom IS NULL OR p.validFrom <= :asOfDate)
              AND (p.validTo IS NULL OR p.validTo >= :asOfDate)
            ORDER BY p.priority ASC, p.createdAt DESC
            """)
    List<PricingStrategy> findActiveByFactoryIdOrderByPriorityAsc(
            @Param("factoryId") String factoryId,
            @Param("asOfDate") LocalDate asOfDate
    );
}
