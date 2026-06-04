package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.WastageRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 损耗记录仓库
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Repository
public interface WastageRecordRepository extends JpaRepository<WastageRecord, String> {

    // ==================== 基础查询 ====================

    Page<WastageRecord> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<WastageRecord> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, WastageRecord.Status status, Pageable pageable);

    Page<WastageRecord> findByFactoryIdAndTypeOrderByCreatedAtDesc(
            String factoryId, WastageRecord.WastageType type, Pageable pageable);

    Optional<WastageRecord> findByIdAndFactoryId(String id, String factoryId);

    Optional<WastageRecord> findByFactoryIdAndWastageNumber(String factoryId, String wastageNumber);

    /**
     * 多条件组合筛选（May 9 fix）：status / type / wastageDate 全部可选。
     *
     * 取代旧 controller "if-else 链 + early return" 模式。
     * 与 MaterialRequisitionRepository.findByFilters 同模式。
     *
     * <p>PG 兼容：parameter-side `IS NULL` 必须 CAST。见
     * .claude/rules/database-entity-sync.md。</p>
     *
     * @param factoryId 必传
     * @param status 可选
     * @param type 可选
     * @param startDate 可选（与 endDate 配对作为日期区间）
     * @param endDate 可选
     */
    @Query("SELECT w FROM WastageRecord w WHERE w.factoryId = :factoryId " +
            "AND (CAST(:status AS string) IS NULL OR w.status = :status) " +
            "AND (CAST(:type AS string) IS NULL OR w.type = :type) " +
            "AND (CAST(:startDate AS string) IS NULL OR w.wastageDate >= :startDate) " +
            "AND (CAST(:endDate AS string) IS NULL OR w.wastageDate <= :endDate) " +
            "ORDER BY w.createdAt DESC")
    Page<WastageRecord> findByFilters(
            @Param("factoryId") String factoryId,
            @Param("status") WastageRecord.Status status,
            @Param("type") WastageRecord.WastageType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    // ==================== 日期范围查询 ====================

    @Query("SELECT w FROM WastageRecord w WHERE w.factoryId = :factoryId " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "ORDER BY w.wastageDate DESC, w.createdAt DESC")
    List<WastageRecord> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ==================== 统计查询 ====================

    /**
     * 按损耗类型统计数量和金额
     */
    @Query("SELECT w.type, COUNT(w), SUM(w.quantity), SUM(w.estimatedCost) " +
            "FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "GROUP BY w.type " +
            "ORDER BY SUM(w.estimatedCost) DESC")
    List<Object[]> getStatisticsByType(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 按食材统计损耗数量和金额（Top N 高损耗食材）
     */
    @Query("SELECT w.rawMaterialTypeId, w.unit, SUM(w.quantity), SUM(w.estimatedCost) " +
            "FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "GROUP BY w.rawMaterialTypeId, w.unit " +
            "ORDER BY SUM(w.estimatedCost) DESC")
    List<Object[]> getStatisticsByMaterial(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 损耗总金额（时间范围内）
     */
    @Query("SELECT COALESCE(SUM(w.estimatedCost), 0) FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalEstimatedCost(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 按食材类别统计 APPROVED 损耗 (Sprint 12 Phase B — Shrinkage Composite wiring).
     *
     * <p>JOIN raw_material_types 取 category 字段 (e.g. 水产/肉类/蔬菜/豆制品),
     * 作为 Python {@code shrinkage_analysis} section 的 {@code department} 维度.
     *
     * @return List of [category(String), count(Long), sumCost(BigDecimal)] tuples
     *         ordered by sumCost DESC.
     */
    @Query("SELECT rmt.category, COUNT(w), COALESCE(SUM(w.estimatedCost), 0) " +
            "FROM WastageRecord w " +
            "JOIN com.cretas.aims.entity.RawMaterialType rmt " +
            "  ON rmt.id = w.rawMaterialTypeId AND rmt.factoryId = w.factoryId " +
            "WHERE w.factoryId = :factoryId " +
            "  AND w.status = 'APPROVED' " +
            "  AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "  AND rmt.category IS NOT NULL " +
            "GROUP BY rmt.category " +
            "ORDER BY SUM(w.estimatedCost) DESC")
    List<Object[]> getStatisticsByIngredientCategory(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 统计生成单号：当天该工厂的损耗单数量
     */
    @Query("SELECT COUNT(w) FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId AND w.wastageDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    // ==================== 责任制聚合 (Wave2 损耗按人/档口) ====================

    /**
     * 按责任人聚合 APPROVED 损耗（成本降序）。
     *
     * <p>operator_id 为 null 的记录归并为「未指定责任人」一行（COALESCE 在 service 层处理，
     * 此处保留原始 null operatorId，ORDER BY 用 SUM(estimatedCost) 降序）。</p>
     *
     * @return List of [operatorId(Long, 可 null), count(Long), sumQuantity(BigDecimal), sumCost(BigDecimal)]
     */
    @Query("SELECT w.operatorId, COUNT(w), COALESCE(SUM(w.quantity), 0), COALESCE(SUM(w.estimatedCost), 0) " +
            "FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "GROUP BY w.operatorId " +
            "ORDER BY COALESCE(SUM(w.estimatedCost), 0) DESC")
    List<Object[]> getStatisticsByOperator(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 按档口聚合 APPROVED 损耗（成本降序）。
     *
     * @return List of [sectionCode(String, 可 null), count(Long), sumQuantity(BigDecimal), sumCost(BigDecimal)]
     */
    @Query("SELECT w.sectionCode, COUNT(w), COALESCE(SUM(w.quantity), 0), COALESCE(SUM(w.estimatedCost), 0) " +
            "FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate " +
            "GROUP BY w.sectionCode " +
            "ORDER BY COALESCE(SUM(w.estimatedCost), 0) DESC")
    List<Object[]> getStatisticsBySection(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * APPROVED 损耗总记录数（时间范围内）— 责任制汇总 totalCount 用。
     */
    @Query("SELECT COUNT(w) FROM WastageRecord w " +
            "WHERE w.factoryId = :factoryId " +
            "AND w.status = 'APPROVED' " +
            "AND w.wastageDate BETWEEN :startDate AND :endDate")
    long countApprovedByDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
