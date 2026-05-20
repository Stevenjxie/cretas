package com.cretas.aims.repository;

import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 销售商机 Repository — Sprint 7 wave 2 T4 (2026-05-20).
 */
@Repository
public interface SalesOpportunityRepository extends JpaRepository<SalesOpportunity, String> {

    /** factoryId 单独 scope (列表页). */
    Page<SalesOpportunity> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    /** factoryId + stage 过滤. */
    Page<SalesOpportunity> findByFactoryIdAndStageAndDeletedAtIsNull(
            String factoryId, OpportunityStage stage, Pageable pageable);

    /** factoryId + ownerId 过滤 (我的商机). */
    Page<SalesOpportunity> findByFactoryIdAndOwnerIdAndDeletedAtIsNull(
            String factoryId, Long ownerId, Pageable pageable);

    /** factoryId + stage + ownerId 过滤. */
    Page<SalesOpportunity> findByFactoryIdAndStageAndOwnerIdAndDeletedAtIsNull(
            String factoryId, OpportunityStage stage, Long ownerId, Pageable pageable);

    /** Get by id + factory scope (防租户串数据). */
    Optional<SalesOpportunity> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** factoryId + customerId scope (客户详情 tab). */
    List<SalesOpportunity> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String customerId);

    /**
     * Funnel 统计 — group by stage, count + sum(value_amount * probability / 100).
     * 用于 funnel chart 渲染 + kanban column header.
     */
    @Query("SELECT o.stage AS stage, COUNT(o) AS cnt, " +
            "COALESCE(SUM(o.valueAmount), 0) AS totalValue, " +
            "COALESCE(SUM(o.valueAmount * CAST(o.probability AS BigDecimal) / 100), 0) AS expectedValue " +
            "FROM SalesOpportunity o " +
            "WHERE o.factoryId = :factoryId " +
            "AND (:ownerId IS NULL OR o.ownerId = :ownerId) " +
            "AND o.deletedAt IS NULL " +
            "GROUP BY o.stage")
    List<FunnelStat> getFunnelStats(@Param("factoryId") String factoryId,
                                     @Param("ownerId") Long ownerId);

    /**
     * 投影接口 (per Spring Data JPA pattern) for funnel chart.
     */
    interface FunnelStat {
        OpportunityStage getStage();
        Long getCnt();
        BigDecimal getTotalValue();
        BigDecimal getExpectedValue();
    }
}
