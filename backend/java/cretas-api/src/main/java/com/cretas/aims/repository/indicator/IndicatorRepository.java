package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标定义 Repository — Indicator Center (Canvas Phase A subagent #3).
 *
 * <p>Phase 1 Day 3 plans a dedicated repository under the same package; this
 * Canvas-facing repo focuses on UI / aggregator scenarios and is independent
 * to avoid blocking on Day 3 PR merge.
 */
@Repository
public interface IndicatorRepository extends JpaRepository<Indicator, String> {

    /** 工厂全部启用指标 (含全局 factoryId=GLOBAL — Canvas list 视图)。 */
    List<Indicator> findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(String factoryId);

    /** 按分类筛选 (FACTORY / RESTAURANT / QUALITY / FINANCE 等)。 */
    List<Indicator> findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(
            String factoryId, String category);

    /** 按 code 唯一查找 (factory + code) — 公式编辑入口。 */
    Optional<Indicator> findByFactoryIdAndCode(String factoryId, String code);

    /** Canvas 全工厂概览 — admin 视角 (跨工厂)。 */
    List<Indicator> findAllByIsActiveTrue();

    /**
     * 列出某工厂全部分类 (DISTINCT)。
     */
    @Query("SELECT DISTINCT i.category FROM Indicator i " +
            "WHERE i.factoryId = :factoryId AND i.isActive = TRUE " +
            "ORDER BY i.category")
    List<String> findDistinctCategories(@Param("factoryId") String factoryId);
}
