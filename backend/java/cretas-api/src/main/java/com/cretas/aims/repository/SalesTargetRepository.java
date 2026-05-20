package com.cretas.aims.repository;

import com.cretas.aims.entity.SalesTarget;
import com.cretas.aims.entity.enums.TargetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 销售目标 Repository — Sprint 7 wave 2 T5 (2026-05-20).
 */
@Repository
public interface SalesTargetRepository extends JpaRepository<SalesTarget, String> {

    /** 取单个销售员某周期的目标. */
    Optional<SalesTarget> findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
            String factoryId, Long ownerId, TargetPeriod period, Integer year, Integer periodNum);

    /** 按 factory + 周期列出所有销售员的目标 (org-wide leaderboard 用). */
    List<SalesTarget> findByFactoryIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
            String factoryId, TargetPeriod period, Integer year, Integer periodNum);

    /** 我的所有目标 (按周期排序). */
    List<SalesTarget> findByFactoryIdAndOwnerIdAndDeletedAtIsNullOrderByYearDescPeriodNumDesc(
            String factoryId, Long ownerId);

    /** id + factory scope (防租户串数据). */
    Optional<SalesTarget> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);
}
