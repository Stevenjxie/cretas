package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 8 P3 Phase A {@link AdditiveLimit}.
 *
 * <p>系统级 entity (无 factory_id). Skill `food-additive-compliance` 主要查询点.
 *
 * <p>Sprint 9 P2.F V2: 加 name-based fuzzy query 支持智能 fuzzy 匹配
 * (用户用俗名输入时自动找到 INS code).
 */
@Repository
public interface AdditiveLimitRepository extends JpaRepository<AdditiveLimit, Long> {

    /** 按 (additive_code, food_category) 查启用的限量条目 (compliance check hot path). */
    Optional<AdditiveLimit> findByAdditiveCodeAndFoodCategoryAndActiveTrue(
            String additiveCode, String foodCategory);

    /** 查某食品类目全部启用的添加剂限量 (营养标签草稿用). */
    List<AdditiveLimit> findByFoodCategoryAndActiveTrue(String foodCategory);

    /** Sprint 9 P2.F — 按 additive_code 查所有 (跨类目, 用于 smart match). */
    List<AdditiveLimit> findByAdditiveCodeAndActiveTrue(String additiveCode);

    /** Sprint 9 P2.F — 按 additive_name 精确匹配 (跨类目, 用于 smart match). */
    List<AdditiveLimit> findByAdditiveNameAndActiveTrue(String additiveName);

    /** Sprint 9 P2.F — 按 additive_name LIKE 模糊匹配 (跨类目, 用于 smart match fallback). */
    List<AdditiveLimit> findByAdditiveNameContainingAndActiveTrue(String namePart);

    /**
     * Sprint 9 P2.F — 全表查询启用 entry (用于 Levenshtein top-N 匹配).
     * 数量受限 — 当前 ~83 entries 可全表 scan; 未来超 1000 应改 ANN/索引.
     */
    @Query("SELECT a FROM AdditiveLimit a WHERE a.active = true ORDER BY a.additiveName")
    List<AdditiveLimit> findAllActiveForFuzzy();

    /**
     * Sprint 9 P2.F — 按 aliases JSONB array 模糊匹配
     * (PostgreSQL JSONB containment / `?` operator 兼容查询).
     * 用 LOWER 转换 lowercase 提高匹配率.
     */
    @Query(value = "SELECT * FROM additive_limits a " +
                   "WHERE a.active = true " +
                   "  AND a.deleted_at IS NULL " +
                   "  AND a.aliases IS NOT NULL " +
                   "  AND EXISTS (" +
                   "    SELECT 1 FROM jsonb_array_elements_text(a.aliases) AS elem " +
                   "    WHERE LOWER(elem) = LOWER(:query) " +
                   "       OR LOWER(elem) LIKE LOWER(CONCAT('%', :query, '%'))" +
                   "  )",
           nativeQuery = true)
    List<AdditiveLimit> findByAliasContaining(@Param("query") String query);
}
