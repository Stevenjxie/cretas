package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 8 P3 Phase A {@link AdditiveLimit}.
 *
 * <p>系统级 entity (无 factory_id). Skill `food-additive-compliance` 主要查询点.
 */
@Repository
public interface AdditiveLimitRepository extends JpaRepository<AdditiveLimit, Long> {

    /** 按 (additive_code, food_category) 查启用的限量条目 (compliance check hot path). */
    Optional<AdditiveLimit> findByAdditiveCodeAndFoodCategoryAndActiveTrue(
            String additiveCode, String foodCategory);

    /** 查某食品类目全部启用的添加剂限量 (营养标签草稿用). */
    List<AdditiveLimit> findByFoodCategoryAndActiveTrue(String foodCategory);
}
