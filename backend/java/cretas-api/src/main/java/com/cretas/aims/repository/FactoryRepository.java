package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
/**
 * 工厂数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface FactoryRepository extends JpaRepository<Factory, String> {
    /** Serialize code-owned tenant provisioning across application processes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Factory f WHERE f.id = :factoryId")
    Optional<Factory> findByIdForUpdate(@Param("factoryId") String factoryId);

    /**
     * 根据工厂名称查找
     */
    Optional<Factory> findByName(String name);
     /**
     * 查找所有激活的工厂
      */
    List<Factory> findByIsActiveTrue();
     /**
     * 根据行业代码查找工厂
      */
    List<Factory> findByIndustryCode(String industryCode);
     /**
     * 根据地区代码查找工厂
      */
    List<Factory> findByRegionCode(String regionCode);
     /**
     * 模糊搜索工厂
     * 注意：name/address/contactName都是用户友好名称字段，使用双向模糊（无法使用索引）
     * 如果性能成为问题，考虑添加 factory_code 字段并使用右模糊
      */
    @Query("SELECT f FROM Factory f WHERE " +
           "(LOWER(f.name) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(f.address) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(f.contactName) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\') " +
           "AND f.isActive = :isActive")
    Page<Factory> searchFactories(@Param("keyword") String keyword,
                                  @Param("isActive") Boolean isActive,
                                  Pageable pageable);
     /**
     * 统计工厂数量
      */
    @Query("SELECT COUNT(f) FROM Factory f WHERE f.isActive = true")
    Long countActiveFactories();
     /**
     * 根据订阅计划查找工厂
      */
    List<Factory> findBySubscriptionPlan(String subscriptionPlan);
     /**
     * 检查工厂ID是否存在
      */
    boolean existsById(String id);

    /**
     * 根据ID前缀统计工厂数量（用于生成序号）
     * @param prefix ID前缀，如 "FISH_2025_"
     * @return 匹配的工厂数量
     */
    @Query("SELECT COUNT(f) FROM Factory f WHERE f.id LIKE CONCAT(:prefix, '%')")
    long countByIdPrefix(@Param("prefix") String prefix);

    /**
     * 分页获取所有工厂
     */
    Page<Factory> findAll(Pageable pageable);

    /**
     * 分页获取激活的工厂
     */
    Page<Factory> findByIsActive(boolean isActive, Pageable pageable);
     /**
     * 获取工厂的AI使用配额信息
      */
    @Query("SELECT f.aiWeeklyQuota FROM Factory f WHERE f.id = :factoryId")
    Integer getAiWeeklyQuota(@Param("factoryId") String factoryId);

    /**
     * 计算所有激活工厂的AI周配额总和
     * @return 配额总和（null视为默认值50）
     */
    @Query("SELECT COALESCE(SUM(COALESCE(f.aiWeeklyQuota, 50)), 0) FROM Factory f WHERE f.isActive = true")
    Integer sumActiveFactoriesAIQuota();

    /**
     * 获取所有激活工厂的ID列表
     * @return 工厂ID列表
     */
    @Query("SELECT f.id FROM Factory f WHERE f.isActive = true")
    List<String> findAllActiveFactoryIds();

    // ==================== 组织层级查询 (进销存通用化) ====================

    /**
     * 根据组织类型查找
     */
    List<Factory> findByTypeAndIsActiveTrue(FactoryType type);

    /**
     * 根据上级组织ID查找直属下级
     */
    List<Factory> findByParentIdAndIsActiveTrue(String parentId);

    /**
     * 查找所有下属组织ID（含自身）
     * 用于总部聚合查询: SELECT ... WHERE factory_id IN (findAllDescendantIds)
     */
    @Query("SELECT f.id FROM Factory f WHERE f.parentId = :parentId AND f.isActive = true")
    List<String> findChildFactoryIds(@Param("parentId") String parentId);

    /**
     * 根据多个factoryId查找（支持总部聚合视图）
     */
    List<Factory> findByIdInAndIsActiveTrue(List<String> factoryIds);

    /**
     * 查找所有总部级组织
     */
    @Query("SELECT f FROM Factory f WHERE f.type = 'HEADQUARTERS' AND f.isActive = true")
    List<Factory> findAllHeadquarters();

    /**
     * 查找独立组织（无层级关系）
     */
    @Query("SELECT f FROM Factory f WHERE f.parentId IS NULL AND f.type IN ('FACTORY', 'RESTAURANT') AND f.isActive = true")
    List<Factory> findAllStandalone();
}
