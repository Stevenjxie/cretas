package com.cretas.aims.repository;

import com.cretas.aims.entity.Supplier;
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
 * 供应商数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, String> {

    /**
     * 根据ID和工厂ID查找供应商
     */
    Optional<Supplier> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 根据工厂ID查找供应商（分页）
     */
    Page<Supplier> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID查找所有供应商（不分页）
     */
    List<Supplier> findByFactoryId(String factoryId);

    /**
     * 根据工厂ID和激活状态查找供应商
     */
    List<Supplier> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    /**
     * 根据名称搜索供应商
     * 注意：name使用双向模糊匹配（无法使用索引），supplierCode使用右模糊（可使用索引）
     * keyword 必须先经 SqlLikeEscaper.escape() 处理 _ % \, ESCAPE '\\' 子句生效.
     */
    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId " +
           "AND (s.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.shortName LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.supplierCode LIKE CONCAT(:keyword, '%') ESCAPE '\\')")
    List<Supplier> searchByName(@Param("factoryId") String factoryId, @Param("keyword") String keyword);

    /**
     * ⚠️ shortName 必须在这里参与匹配 —— 客户要简称就是为了在下拉里打简称找供应商,
     * 只加列不加搜索条件等于功能没上线。
     */
    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId " +
           "AND (s.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.shortName LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.supplierCode LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR s.contactPerson LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<Supplier> searchByNamePaged(@Param("factoryId") String factoryId,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);

    /** R10 CRIT-2: push-down isActive filter (see CustomerRepository). */
    Page<Supplier> findByFactoryIdAndIsActiveTrue(String factoryId, Pageable pageable);

    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId AND s.isActive = true " +
           "AND (s.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.shortName LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR s.supplierCode LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR s.contactPerson LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<Supplier> searchActiveByNamePaged(@Param("factoryId") String factoryId,
                                            @Param("keyword") String keyword,
                                            Pageable pageable);

    /**
     * 根据供应材料类型查找供应商
     */
    List<Supplier> findByFactoryIdAndSuppliedMaterialsContaining(String factoryId, String materialType);

    /**
     * Issue #788 follow-up to PR #782 / #779: reverse direction — find suppliers
     * who actually supplied a given raw_material_type (history-based M:N).
     *
     * <p>Differs from {@link #findByFactoryIdAndSuppliedMaterialsContaining}: that one queries
     * the declared {@code supplied_materials} array field on Supplier (lossy, by name).
     * This one walks PurchaseOrderItem -> PurchaseOrder.supplier_id history (true M:N relation).
     *
     * <p>Returns distinct suppliers ordered by name. Factory-isolated through PurchaseOrder.factoryId.
     */
    @Query("SELECT DISTINCT s FROM Supplier s, com.cretas.aims.entity.inventory.PurchaseOrder po, "
            + "com.cretas.aims.entity.inventory.PurchaseOrderItem poi "
            + "WHERE s.id = po.supplierId "
            + "AND po.id = poi.purchaseOrderId "
            + "AND poi.materialTypeId = :materialTypeId "
            + "AND po.factoryId = :factoryId "
            + "AND s.factoryId = :factoryId "
            + "ORDER BY s.name ASC")
    List<Supplier> findDistinctSuppliersByMaterialTypeId(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId);

    /**
     * 检查供应商名称是否存在
     */
    boolean existsByFactoryIdAndName(String factoryId, String name);

    /**
     * 检查供应商名称是否存在（排除指定ID）
     */
    boolean existsByFactoryIdAndNameAndIdNot(String factoryId, String name, String id);

    /**
     * 检查供应商代码是否存在
     */
    boolean existsBySupplierCode(String supplierCode);

    /**
     * 检查供应商代码是否存在（在指定工厂）
     */
    boolean existsByFactoryIdAndSupplierCode(String factoryId, String supplierCode);

    /**
     * 检查供应商是否有关联的原材料批次
     */
    @Query("SELECT COUNT(m) > 0 FROM MaterialBatch m WHERE m.supplierId = :supplierId")
    boolean hasRelatedMaterialBatches(@Param("supplierId") String supplierId);

    /**
     * 获取供应商评级分布
     */
    @Query("SELECT s.rating, COUNT(s) FROM Supplier s WHERE s.factoryId = :factoryId " +
           "GROUP BY s.rating")
    List<Object[]> getSupplierRatingDistribution(@Param("factoryId") String factoryId);

    /**
     * 查找有欠款的供应商
     */
    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId " +
           "AND s.currentBalance > 0 ORDER BY s.currentBalance DESC")
    List<Supplier> findSuppliersWithOutstandingBalance(@Param("factoryId") String factoryId);

    /**
     * 计算工厂的供应商总数
     */
    long countByFactoryId(String factoryId);

    /**
     * 计算工厂的活跃供应商数
     */
    long countByFactoryIdAndIsActive(String factoryId, Boolean isActive);

    /**
     * 获取信用额度最高的供应商
     */
    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId " +
           "ORDER BY s.creditLimit DESC")
    List<Supplier> findTopSuppliersByCreditLimit(@Param("factoryId") String factoryId, Pageable pageable);

    /**
     * 获取评级最高的供应商
     */
    @Query("SELECT s FROM Supplier s WHERE s.factoryId = :factoryId " +
           "AND s.rating >= :minRating ORDER BY s.rating DESC")
    List<Supplier> findTopSuppliersByRating(@Param("factoryId") String factoryId,
                                           @Param("minRating") Integer minRating);

    /**
     * 统计供应商的平均评级
     */
    @Query("SELECT AVG(s.rating) FROM Supplier s WHERE s.factoryId = :factoryId " +
           "AND s.rating IS NOT NULL")
    Double calculateAverageRating(@Param("factoryId") String factoryId);

    /**
     * 统计供应商的总欠款 (仅正余额, 即工厂欠供应商的钱).
     * R42 BUG-13 fix: 之前 SUM(currentBalance) 会把工厂预付供应商的钱(negative)抵消, 导致 AP 偏低/为负.
     */
    @Query("SELECT COALESCE(SUM(s.currentBalance), 0) FROM Supplier s " +
            "WHERE s.factoryId = :factoryId AND s.currentBalance > 0")
    BigDecimal calculateTotalOutstandingBalance(@Param("factoryId") String factoryId);

    /**
     * R42 BUG-13: 工厂预付供应商款 (negative balance 取绝对值).
     */
    @Query("SELECT COALESCE(SUM(-s.currentBalance), 0) FROM Supplier s " +
            "WHERE s.factoryId = :factoryId AND s.currentBalance < 0")
    BigDecimal calculateTotalSupplierPrepayments(@Param("factoryId") String factoryId);

    /**
     * 根据ID列表和工厂ID批量查找供应商
     */
    List<Supplier> findByIdInAndFactoryId(List<String> ids, String factoryId);
}
