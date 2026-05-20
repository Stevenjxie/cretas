package com.cretas.aims.repository;

import com.cretas.aims.entity.Customer;
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
 * 客户数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    /**
     * 根据ID和工厂ID查找客户
     */
    Optional<Customer> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 根据工厂ID查找客户（分页）
     */
    Page<Customer> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID查找所有客户（不分页）
     */
    List<Customer> findByFactoryId(String factoryId);

    /**
     * 根据工厂ID和激活状态查找客户
     */
    List<Customer> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    /**
     * 根据名称搜索客户
     * 注意：name使用双向模糊匹配（无法使用索引），customerCode使用右模糊（可使用索引）
     * keyword 必须先经 SqlLikeEscaper.escape() 处理 _ % \, 再传入. ESCAPE '\\' 子句生效.
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (c.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR c.customerCode LIKE CONCAT(:keyword, '%') ESCAPE '\\')")
    List<Customer> searchByName(@Param("factoryId") String factoryId, @Param("keyword") String keyword);

    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (c.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR c.customerCode LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR c.contactPerson LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<Customer> searchByNamePaged(@Param("factoryId") String factoryId, @Param("keyword") String keyword, Pageable pageable);

    /** R10 CRIT-2: push-down isActive filter for /reference-data/customers dropdown.
     *  Replaces post-page filter that starved on factories with concentrated inactive blocks. */
    Page<Customer> findByFactoryIdAndIsActiveTrue(String factoryId, Pageable pageable);

    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId AND c.isActive = true " +
           "AND (c.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR c.customerCode LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR c.contactPerson LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<Customer> searchActiveByNamePaged(@Param("factoryId") String factoryId,
                                            @Param("keyword") String keyword,
                                            Pageable pageable);

    /**
     * Sprint 4 W2 S-CRM-FULL-1: list 多过滤器 (keyword + customerStatus + importance + source).
     * 任一参数 null 即不过滤. 用 CAST(:param AS string) 给 PG 类型 hint, 避免
     * "could not determine data type of parameter $N" (per database-entity-sync.md).
     * keyword 已经过 SqlLikeEscaper.escape() 处理, ESCAPE '\\' 子句生效.
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (CAST(:keyword AS string) IS NULL " +
           "     OR c.name LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.customerCode LIKE CONCAT(CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.contactPerson LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\') " +
           "AND (CAST(:customerStatus AS string) IS NULL OR c.customerStatus = :customerStatus) " +
           "AND (CAST(:importance AS string) IS NULL OR c.importance = :importance) " +
           "AND (CAST(:source AS string) IS NULL OR c.source = :source)")
    Page<Customer> searchByFiltersPaged(
            @Param("factoryId") String factoryId,
            @Param("keyword") String keyword,
            @Param("customerStatus") com.cretas.aims.entity.enums.CustomerStatus customerStatus,
            @Param("importance") com.cretas.aims.entity.enums.CustomerImportance importance,
            @Param("source") com.cretas.aims.entity.enums.CustomerSource source,
            Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope sweep) — 同 {@link #searchByFiltersPaged} 加 createdBy filter.
     *
     * <p>当 {@link com.cretas.aims.security.DataScopeContext} scope == SELF, 仅查当前用户创建的客户.
     * SELF_AND_BELOW 时传入 createdByList (resolver 算下属链). DB 端 filter, 避免 SELF + keyword 复合时
     * 走 in-memory filter (Sprint 5 POC pattern, 性能差).
     *
     * <p>:createdBy null = 不过滤 (ALL scope). 非 null = 单 user filter (SELF scope).
     * SELF_AND_BELOW 用 sister method {@link #searchByFiltersPagedScopedList}.
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (CAST(:keyword AS string) IS NULL " +
           "     OR c.name LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.customerCode LIKE CONCAT(CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.contactPerson LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\') " +
           "AND (CAST(:customerStatus AS string) IS NULL OR c.customerStatus = :customerStatus) " +
           "AND (CAST(:importance AS string) IS NULL OR c.importance = :importance) " +
           "AND (CAST(:source AS string) IS NULL OR c.source = :source) " +
           "AND c.createdBy = :createdBy")
    Page<Customer> searchByFiltersPagedAndCreatedBy(
            @Param("factoryId") String factoryId,
            @Param("keyword") String keyword,
            @Param("customerStatus") com.cretas.aims.entity.enums.CustomerStatus customerStatus,
            @Param("importance") com.cretas.aims.entity.enums.CustomerImportance importance,
            @Param("source") com.cretas.aims.entity.enums.CustomerSource source,
            @Param("createdBy") Long createdBy,
            Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope DEPT_AND_BELOW / SELF_AND_BELOW) — IN list 过滤.
     * 调用方传入 resolved chain (e.g. self + 下属 userIds), DB IN 一次过.
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (CAST(:keyword AS string) IS NULL " +
           "     OR c.name LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.customerCode LIKE CONCAT(CAST(:keyword AS string), '%') ESCAPE '\\' " +
           "     OR c.contactPerson LIKE CONCAT('%', CAST(:keyword AS string), '%') ESCAPE '\\') " +
           "AND (CAST(:customerStatus AS string) IS NULL OR c.customerStatus = :customerStatus) " +
           "AND (CAST(:importance AS string) IS NULL OR c.importance = :importance) " +
           "AND (CAST(:source AS string) IS NULL OR c.source = :source) " +
           "AND c.createdBy IN :createdByList")
    Page<Customer> searchByFiltersPagedAndCreatedByIn(
            @Param("factoryId") String factoryId,
            @Param("keyword") String keyword,
            @Param("customerStatus") com.cretas.aims.entity.enums.CustomerStatus customerStatus,
            @Param("importance") com.cretas.aims.entity.enums.CustomerImportance importance,
            @Param("source") com.cretas.aims.entity.enums.CustomerSource source,
            @Param("createdByList") List<Long> createdByList,
            Pageable pageable);

    /**
     * Sprint 4 W2 S-CRM-FULL-1: 沉睡客户查询 — 最近接洽 < cutoff.
     * lastContactedAt IS NULL 视为从未接洽 (一并返回), 用 OR 涵盖.
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND (c.lastContactedAt IS NULL OR c.lastContactedAt < :cutoff) " +
           "ORDER BY c.lastContactedAt ASC NULLS FIRST")
    List<Customer> findSleepingCustomers(@Param("factoryId") String factoryId,
                                         @Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * 根据客户类型查找客户
     */
    List<Customer> findByFactoryIdAndType(String factoryId, String type);

    /**
     * 根据行业查找客户
     */
    List<Customer> findByFactoryIdAndIndustry(String factoryId, String industry);

    /**
     * 检查客户名称是否存在
     */
    boolean existsByFactoryIdAndName(String factoryId, String name);

    /**
     * 检查客户名称是否存在（排除指定ID）
     */
    boolean existsByFactoryIdAndNameAndIdNot(String factoryId, String name, String id);

    /**
     * 检查客户代码是否存在
     */
    boolean existsByCustomerCode(String customerCode);

    /**
     * 检查客户代码是否存在（在指定工厂）
     */
    boolean existsByFactoryIdAndCustomerCode(String factoryId, String customerCode);

    /**
     * 检查客户是否有关联的订单
     */
    @Query("SELECT COUNT(s) > 0 FROM ShipmentRecord s WHERE s.customerId = :customerId")
    boolean hasRelatedShipments(@Param("customerId") String customerId);

    /**
     * 获取客户评级分布
     */
    @Query("SELECT c.rating, COUNT(c) FROM Customer c WHERE c.factoryId = :factoryId " +
           "GROUP BY c.rating")
    List<Object[]> getCustomerRatingDistribution(@Param("factoryId") String factoryId);

    /**
     * 查找有欠款的客户
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND c.currentBalance > 0 ORDER BY c.currentBalance DESC")
    List<Customer> findCustomersWithOutstandingBalance(@Param("factoryId") String factoryId);

    /**
     * 获取信用额度最高的客户
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "ORDER BY c.creditLimit DESC")
    List<Customer> findTopCustomersByCreditLimit(@Param("factoryId") String factoryId, Pageable pageable);

    /**
     * 获取评级最高的客户
     */
    @Query("SELECT c FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND c.rating >= :minRating ORDER BY c.rating DESC")
    List<Customer> findTopCustomersByRating(@Param("factoryId") String factoryId,
                                           @Param("minRating") Integer minRating);

    /**
     * 计算工厂的客户总数
     */
    long countByFactoryId(String factoryId);

    /**
     * 计算工厂的活跃客户数
     */
    long countByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    /**
     * 统计客户的平均评级
     */
    @Query("SELECT AVG(c.rating) FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND c.rating IS NOT NULL")
    Double calculateAverageRating(@Param("factoryId") String factoryId);

    /**
     * 统计客户的总欠款 (仅正余额, 即客户欠工厂的钱).
     * R42 BUG-13 fix: 之前 SUM(currentBalance) 把客户预付 (negative balance) 也加进去,
     * 导致 reports/finance.accountsReceivable 显示负值 (e.g., -145K). 应付预付应分开统计.
     */
    @Query("SELECT COALESCE(SUM(c.currentBalance), 0) FROM Customer c " +
            "WHERE c.factoryId = :factoryId AND c.currentBalance > 0")
    BigDecimal calculateTotalOutstandingBalance(@Param("factoryId") String factoryId);

    /**
     * R42 BUG-13: 统计客户预付款 (negative balance 取绝对值).
     * 客户预付了钱但未消费, 在会计上是工厂的负债, 不应混在 AR 里.
     */
    @Query("SELECT COALESCE(SUM(-c.currentBalance), 0) FROM Customer c " +
            "WHERE c.factoryId = :factoryId AND c.currentBalance < 0")
    BigDecimal calculateTotalPrepayments(@Param("factoryId") String factoryId);

    /**
     * 统计客户的总信用额度
     */
    @Query("SELECT SUM(c.creditLimit) FROM Customer c WHERE c.factoryId = :factoryId " +
           "AND c.isActive = true")
    BigDecimal calculateTotalCreditLimit(@Param("factoryId") String factoryId);

    /**
     * 按客户类型统计数量
     */
    @Query("SELECT c.type, COUNT(c) FROM Customer c WHERE c.factoryId = :factoryId " +
           "GROUP BY c.type")
    List<Object[]> countByType(@Param("factoryId") String factoryId);

    /**
     * 按行业统计客户数量
     */
    @Query("SELECT c.industry, COUNT(c) FROM Customer c WHERE c.factoryId = :factoryId " +
           "GROUP BY c.industry")
    List<Object[]> countByIndustry(@Param("factoryId") String factoryId);
}
