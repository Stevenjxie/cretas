package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标定义数据访问接口 (Phase 1 Sprint 1 Day 3, 2026-05-20)
 *
 * <p>食品行业指标中心 — 指标定义层 ({@link Indicator}) 的查询入口。
 * 配合 IndicatorTreeNodeRepository / IndicatorComputationRepository 实现完整的指标体系。
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>唯一编码查询</b>：按 (factoryId, code) 唯一定位指标</li>
 *   <li><b>分类筛选</b>：按 category (FACTORY / RESTAURANT / QUALITY) 列出工厂启用指标</li>
 *   <li><b>工厂隔离</b>：所有查询都基于 factoryId 保证多租户数据隔离</li>
 * </ol>
 *
 * <h3>软删除语义</h3>
 * <p>Entity 层已通过 {@code @Where(clause="deleted_at IS NULL")} 自动过滤软删除记录,
 * Repository 方法签名中显式声明 {@code AndDeletedAtIsNull} 仅作 belt-and-suspenders。
 *
 * <h3>NULL factoryId 系统级模板</h3>
 * <p>未来支持 factoryId=NULL 的系统级指标模板 (memory: spec § 长期 Considerations),
 * 当前 Day 3 不实现, 在 Service 层 union 合并。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-20
 * @see Indicator 实体类
 */
@Repository
public interface IndicatorRepository extends JpaRepository<Indicator, String> {

    /**
     * 根据工厂ID和指标编码查找 (唯一约束 idx_ind_factory_code)。
     *
     * @param code       指标编码 (例如 FACTORY_YIELD_RATE)
     * @param factoryId  工厂ID
     * @return 指标定义 Optional
     */
    Optional<Indicator> findByCodeAndFactoryIdAndDeletedAtIsNull(String code, String factoryId);

    /**
     * 查找工厂指定分类的全部启用指标。
     * 用途：前端指标中心首页按分类 (工厂 / 餐饮 / 质量) 展示。
     *
     * @param factoryId  工厂ID
     * @param category   分类 (FACTORY / RESTAURANT / QUALITY)
     * @return 启用指标列表
     */
    List<Indicator> findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
            String factoryId, String category);

    /**
     * 查找工厂全部启用指标 (不限分类), 按 displayOrder 排序。
     *
     * @param factoryId  工厂ID
     * @return 启用指标列表
     */
    @Query("SELECT i FROM Indicator i WHERE i.factoryId = :factoryId " +
           "AND i.isActive = true " +
           "ORDER BY i.displayOrder ASC NULLS LAST, i.code ASC")
    List<Indicator> findActiveByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 按 ID + factoryId 查找 (多租户安全)。
     *
     * @param id         指标 ID
     * @param factoryId  工厂ID
     * @return 指标定义 Optional
     */
    Optional<Indicator> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /**
     * 检查工厂内某个 code 是否已存在 — 创建前 dup check。
     */
    boolean existsByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code);
}
