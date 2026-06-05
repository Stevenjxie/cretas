package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.StocktakingRecord;
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
 * 盘点记录仓库
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Repository
public interface StocktakingRecordRepository extends JpaRepository<StocktakingRecord, String> {

    // ==================== 基础查询 ====================

    Page<StocktakingRecord> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<StocktakingRecord> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, StocktakingRecord.Status status, Pageable pageable);

    Optional<StocktakingRecord> findByIdAndFactoryId(String id, String factoryId);

    Optional<StocktakingRecord> findByFactoryIdAndStocktakingNumber(String factoryId, String stocktakingNumber);

    // ==================== 日期范围查询 ====================

    @Query("SELECT s FROM StocktakingRecord s WHERE s.factoryId = :factoryId " +
            "AND s.stocktakingDate BETWEEN :startDate AND :endDate " +
            "ORDER BY s.stocktakingDate DESC, s.createdAt DESC")
    List<StocktakingRecord> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ==================== 最新盘点汇总 ====================

    /**
     * 查询某食材最近一次完成的盘点记录
     */
    Optional<StocktakingRecord> findTopByFactoryIdAndRawMaterialTypeIdAndStatusOrderByStocktakingDateDesc(
            String factoryId, String rawMaterialTypeId, StocktakingRecord.Status status);

    /**
     * 最近一次完成的盘点日期
     */
    @Query("SELECT MAX(s.stocktakingDate) FROM StocktakingRecord s " +
            "WHERE s.factoryId = :factoryId AND s.status = 'COMPLETED'")
    Optional<LocalDate> findLatestCompletedDate(@Param("factoryId") String factoryId);

    /**
     * 最近一次盘点的差异汇总（用于首页 dashboard）
     */
    @Query("SELECT s.differenceType, COUNT(s), COALESCE(SUM(ABS(s.differenceAmount)), 0) " +
            "FROM StocktakingRecord s " +
            "WHERE s.factoryId = :factoryId " +
            "AND s.stocktakingDate = :date " +
            "AND s.status = 'COMPLETED' " +
            "GROUP BY s.differenceType")
    List<Object[]> getSummaryByDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDate date);

    /**
     * 查询在盘点中状态的记录（防止重复盘点同一食材）
     */
    boolean existsByFactoryIdAndRawMaterialTypeIdAndStatus(
            String factoryId, String rawMaterialTypeId, StocktakingRecord.Status status);

    /**
     * 统计生成单号：当天该工厂的盘点单数量
     */
    @Query("SELECT COUNT(s) FROM StocktakingRecord s " +
            "WHERE s.factoryId = :factoryId AND s.stocktakingDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    /**
     * 查询最近 N 次盘点汇总（用于 latest-summary 端点）
     */
    @Query("SELECT s FROM StocktakingRecord s " +
            "WHERE s.factoryId = :factoryId AND s.status = 'COMPLETED' " +
            "ORDER BY s.stocktakingDate DESC, s.completedAt DESC")
    List<StocktakingRecord> findLatestCompleted(@Param("factoryId") String factoryId, Pageable pageable);

    @Query("SELECT s.sectionCode, s.stallCode, s.countedBy, " +
            "COUNT(s), COALESCE(SUM(ABS(s.differenceQuantity)), 0), COALESCE(SUM(s.differenceAmount), 0) " +
            "FROM StocktakingRecord s " +
            "WHERE s.factoryId = :factoryId " +
            "AND s.status = 'COMPLETED' " +
            "AND s.inventoryPostedAt IS NOT NULL " +
            "AND s.differenceType = 'SHORTAGE' " +
            "AND s.stocktakingDate BETWEEN :startDate AND :endDate " +
            "GROUP BY s.sectionCode, s.stallCode, s.countedBy")
    List<Object[]> getShortageCostAttributionRows(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
