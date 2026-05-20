package com.cretas.aims.repository;

import com.cretas.aims.entity.CommissionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 提成规则 Repository — Sprint 7 wave 2 T5 (2026-05-20).
 */
@Repository
public interface CommissionRuleRepository extends JpaRepository<CommissionRule, String> {

    Optional<CommissionRule> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    List<CommissionRule> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(String factoryId);

    /**
     * 查匹配的活跃规则候选 (含 most-specific 之外的也返). Service 层再做 most-specific 选择.
     * - active=true
     * - effective_from <= date
     * - effective_to IS NULL OR effective_to >= date
     * - sales_id IS NULL OR sales_id = :salesId
     * - customer_type IS NULL OR customer_type = :customerType
     *
     * <p>PG note: per .claude/rules/database-entity-sync.md, parameter-side IS NULL on
     * :salesId / :customerType uses CAST hint to avoid PG type inference failures.
     */
    @Query("SELECT r FROM CommissionRule r " +
            "WHERE r.factoryId = :factoryId " +
            "AND r.active = true " +
            "AND r.deletedAt IS NULL " +
            "AND r.effectiveFrom <= :date " +
            "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date) " +
            "AND (r.salesId IS NULL OR r.salesId = :salesId) " +
            "AND (r.customerType IS NULL OR " +
            "     (CAST(:customerType AS string) IS NOT NULL AND r.customerType = :customerType))")
    List<CommissionRule> findCandidates(
            @Param("factoryId") String factoryId,
            @Param("salesId") Long salesId,
            @Param("customerType") String customerType,
            @Param("date") LocalDate date);
}
