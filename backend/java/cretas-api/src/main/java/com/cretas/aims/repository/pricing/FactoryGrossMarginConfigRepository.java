package com.cretas.aims.repository.pricing;

import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SP5: 工厂毛利红线配置 Repository.
 */
@Repository
public interface FactoryGrossMarginConfigRepository extends JpaRepository<FactoryGrossMarginConfig, String> {

    /**
     * 查找指定产品类型的配置 (精确匹配).
     */
    Optional<FactoryGrossMarginConfig> findByFactoryIdAndProductTypeIdAndIsActiveTrue(
            String factoryId, String productTypeId);

    /**
     * 查找工厂全局默认配置 (productTypeId = null).
     */
    @Query("SELECT c FROM FactoryGrossMarginConfig c " +
           "WHERE c.factoryId = :factoryId " +
           "AND c.productTypeId IS NULL " +
           "AND c.isActive = true")
    Optional<FactoryGrossMarginConfig> findFactoryDefault(@Param("factoryId") String factoryId);

    /**
     * 查询工厂全部激活配置.
     */
    List<FactoryGrossMarginConfig> findByFactoryIdAndIsActiveTrueOrderByProductTypeIdAsc(String factoryId);

    /**
     * 查询工厂全部配置 (含已禁用，自动过滤软删除 via @Where).
     *
     * <p>用于管理界面列表 — 既要看启用的也要看禁用的，方便恢复/编辑。
     * factory-global (productTypeId IS NULL) 排在最前 (NULLS FIRST)。
     */
    @Query("SELECT c FROM FactoryGrossMarginConfig c " +
           "WHERE c.factoryId = :factoryId " +
           "ORDER BY c.productTypeId ASC NULLS FIRST")
    List<FactoryGrossMarginConfig> findAllByFactory(@Param("factoryId") String factoryId);

    /**
     * 查找指定产品类型的配置 (精确匹配，含已禁用) — 用于创建前去重 (唯一约束 factory+product).
     */
    Optional<FactoryGrossMarginConfig> findByFactoryIdAndProductTypeId(
            String factoryId, String productTypeId);

    /**
     * 查找工厂全局默认配置 (productTypeId = null，含已禁用) — 用于创建前去重.
     */
    @Query("SELECT c FROM FactoryGrossMarginConfig c " +
           "WHERE c.factoryId = :factoryId AND c.productTypeId IS NULL")
    Optional<FactoryGrossMarginConfig> findFactoryDefaultIncludingInactive(@Param("factoryId") String factoryId);
}
