package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityInspection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
/**
 * 质量检验数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface QualityInspectionRepository extends JpaRepository<QualityInspection, String> {
    /**
     * 根据工厂ID分页查找
     */
    Page<QualityInspection> findByFactoryId(String factoryId, Pageable pageable);
     /**
     * 根据工厂ID和生产批次ID分页查找
      */
    Page<QualityInspection> findByFactoryIdAndProductionBatchId(String factoryId, Long productionBatchId, Pageable pageable);
     /**
     * 根据工厂ID和日期范围查找
      */
    @Query("SELECT q FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate >= :startDate AND q.inspectionDate <= :endDate")
    List<QualityInspection> findByFactoryIdAndDateRange(@Param("factoryId") String factoryId,
                                                        @Param("startDate") LocalDate startDate,
                                                        @Param("endDate") LocalDate endDate);

    /**
     * 查找指定日期之后的所有质检记录（跨所有工厂，用于趋势分析）
     * @param startDate 起始日期
     * @return 质检记录列表
     */
    @Query("SELECT q FROM QualityInspection q WHERE q.inspectionDate >= :startDate ORDER BY q.inspectionDate")
    List<QualityInspection> findByInspectionDateAfter(@Param("startDate") LocalDate startDate);

    /**
     * 根据生产批次ID查找质检记录
     * @param productionBatchId 生产批次ID
     * @return 质检记录列表
     */
    List<QualityInspection> findByProductionBatchId(Long productionBatchId);

    /**
     * 获取生产批次最新的质检记录
     * 用于质检处置门禁判断
     *
     * @param productionBatchId 生产批次ID
     * @return 最新的质检记录
     */
    Optional<QualityInspection> findFirstByProductionBatchIdOrderByInspectionDateDesc(Long productionBatchId);

    /**
     * 获取工厂内生产批次最新的质检记录（工厂隔离）
     * 用于AI意图执行时的工厂隔离查询
     *
     * @param factoryId 工厂ID
     * @param productionBatchId 生产批次ID
     * @return 最新的质检记录
     */
    Optional<QualityInspection> findFirstByFactoryIdAndProductionBatchIdOrderByInspectionDateDesc(
            String factoryId, Long productionBatchId);

    /**
     * 统计指定日期之后的质检记录数量
     * @param factoryId 工厂ID
     * @param date 起始日期
     * @return 质检记录数量
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.factoryId = :factoryId AND q.inspectionDate >= :date")
    long countByFactoryIdAndInspectionDateAfter(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    // ========== 员工AI分析相关查询 ==========

    /**
     * 根据质检员ID和时间范围统计质检数量
     * @param inspectorId 质检员ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 质检数量
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.inspectorId = :inspectorId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    long countByInspectorIdAndDateRange(
            @Param("inspectorId") Long inspectorId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据工厂、质检员和时间范围统计质检记录数。
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectorId = :inspectorId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    long countByFactoryIdAndInspectorIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("inspectorId") Long inspectorId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据质检员ID和时间范围统计合格数量
     * @param inspectorId 质检员ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 合格数量
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.inspectorId = :inspectorId " +
           "AND q.result = 'passed' AND q.inspectionDate BETWEEN :startDate AND :endDate")
    long countPassedByInspectorIdAndDateRange(
            @Param("inspectorId") Long inspectorId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据工厂、质检员和时间范围统计通过记录数。
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectorId = :inspectorId AND UPPER(q.result) IN ('PASS', 'PASSED') " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    long countPassedByFactoryIdAndInspectorIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("inspectorId") Long inspectorId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据质检员ID和时间范围查询质检记录
     * @param inspectorId 质检员ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 质检记录列表
     */
    @Query("SELECT q FROM QualityInspection q WHERE q.inspectorId = :inspectorId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate ORDER BY q.inspectionDate DESC")
    List<QualityInspection> findByInspectorIdAndDateRange(
            @Param("inspectorId") Long inspectorId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据工厂ID和时间范围按质检员统计
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return [inspectorId, totalCount, passedCount]
     */
    @Query("SELECT q.inspectorId, COUNT(q), SUM(CASE WHEN q.result = 'passed' THEN 1 ELSE 0 END) " +
           "FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate " +
           "GROUP BY q.inspectorId")
    List<Object[]> countByFactoryIdAndDateRangeGroupByInspector(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据工厂ID和生产批次ID查找所有质检记录
     * 用于处置历史查询
     */
    List<QualityInspection> findByFactoryIdAndProductionBatchId(String factoryId, Long productionBatchId);

    // ========== Sprint 9 P1.1 Fix 2: MaterialBatch 直查路径 ==========

    /**
     * Sprint 9 P1.1: 根据工厂ID和 MaterialBatch ID (UUID String) 查所有质检记录.
     * 用于 QualityCheckSummaryTool 按 batch_number → MaterialBatch.id 直查路径.
     *
     * @param factoryId 工厂ID
     * @param materialBatchId MaterialBatch.id (UUID String)
     * @return 关联此 MaterialBatch 的质检记录列表
     */
    List<QualityInspection> findByFactoryIdAndMaterialBatchId(String factoryId, String materialBatchId);

    // ========== 统计查询方法 ==========

    /**
     * 计算指定时间范围内的总样本数
     */
    @Query("SELECT COALESCE(SUM(q.sampleSize), 0) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal calculateTotalSampleSize(@Param("factoryId") String factoryId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    /**
     * 计算指定时间范围内的合格数量
     */
    @Query("SELECT COALESCE(SUM(q.passCount), 0) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal calculateTotalPassCount(@Param("factoryId") String factoryId,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    /**
     * 计算指定时间范围内的不合格数量
     */
    @Query("SELECT COALESCE(SUM(q.failCount), 0) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal calculateTotalFailCount(@Param("factoryId") String factoryId,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    /**
     * 计算平均合格率
     */
    @Query("SELECT COALESCE(AVG(q.passRate), 0) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate AND q.passRate IS NOT NULL")
    java.math.BigDecimal calculateAveragePassRate(@Param("factoryId") String factoryId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    /**
     * 统计质量问题数量（不合格记录数）
     */
    @Query("SELECT COUNT(q) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate AND q.result = 'FAIL'")
    long countQualityIssues(@Param("factoryId") String factoryId,
                            @Param("startDate") LocalDate startDate,
                            @Param("endDate") LocalDate endDate);

    /**
     * 统计已解决的质量问题数量（假设通过复检的记录视为已解决）
     */
    @Query("SELECT COUNT(DISTINCT q.productionBatchId) FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate AND q.result = 'PASS' " +
           "AND q.productionBatchId IN (" +
           "    SELECT q2.productionBatchId FROM QualityInspection q2 WHERE q2.factoryId = :factoryId " +
           "    AND q2.inspectionDate BETWEEN :startDate AND :endDate AND q2.result = 'FAIL')")
    long countResolvedIssues(@Param("factoryId") String factoryId,
                             @Param("startDate") LocalDate startDate,
                             @Param("endDate") LocalDate endDate);

    /**
     * 统计一次通过率（第一次检验即合格的比例）
     */
    @Query("SELECT COALESCE(AVG(CASE WHEN q.result = 'PASS' THEN 100.0 ELSE 0.0 END), 0) " +
           "FROM QualityInspection q WHERE q.factoryId = :factoryId " +
           "AND q.inspectionDate BETWEEN :startDate AND :endDate")
    Double calculateFirstPassRate(@Param("factoryId") String factoryId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    /**
     * 统计指定工厂的质检记录总数
     */
    long countByFactoryId(String factoryId);

    /**
     * 统计指定工厂和结果的质检记录数
     */
    long countByFactoryIdAndResult(String factoryId, String result);

    /**
     * 批量查询多个质检记录 - 解决 N+1 查询问题
     * @param ids 质检记录ID集合
     * @return 质检记录列表
     */
    @Query("SELECT q FROM QualityInspection q WHERE q.id IN :ids")
    List<QualityInspection> findByIdIn(@Param("ids") Collection<String> ids);
}
