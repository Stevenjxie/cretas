package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.NutritionLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository — Sprint 9 P2.B {@link NutritionLabel}.
 *
 * <p>Factory 隔离 entity. nutrition-label-workflow Skill 主要查询点.
 * BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 */
@Repository
public interface NutritionLabelRepository extends JpaRepository<NutritionLabel, Long> {

    /** 按 SKU 查所有 label (按 created_at desc). */
    List<NutritionLabel> findByFactoryIdAndProductCodeOrderByCreatedAtDesc(
            String factoryId, String productCode);

    /** 按 BOM 版本反查 (BOM 更新后看哪些 label 受影响). */
    List<NutritionLabel> findByBomVersionId(String bomVersionId);

    /** 按产品名模糊查 (lookup tool fallback path). */
    List<NutritionLabel> findByFactoryIdAndProductNameContainingOrderByCreatedAtDesc(
            String factoryId, String productName);

    /** Factory + status 列 (Workdesk 配置统计用). */
    List<NutritionLabel> findByFactoryIdAndStatus(String factoryId, String status);
}
