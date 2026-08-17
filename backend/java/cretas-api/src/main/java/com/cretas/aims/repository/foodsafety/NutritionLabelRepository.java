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

    /**
     * 本工厂的标签, 按创建时间倒序分页。
     *
     * <p>🔴 2026-08-02 新增, 替换 {@code NutritionLabelLookupTool} 原来的
     * {@code findAll() + 内存过滤 factoryId + limit(50)}: 那个写法要把<b>所有工厂</b>的标签
     * 全部载入内存, 且<b>没有 ORDER BY</b> —— "前 50 条"取到哪 50 条是不确定的。
     * 租户隔离也变成"靠调用方记得那一行 filter"。
     */
    org.springframework.data.domain.Page<NutritionLabel> findByFactoryIdOrderByCreatedAtDesc(
            String factoryId, org.springframework.data.domain.Pageable pageable);
}
