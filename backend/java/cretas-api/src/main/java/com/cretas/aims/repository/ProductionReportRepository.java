package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface ProductionReportRepository extends JpaRepository<ProductionReport, Long> {

    // ==================== AUDIT-004 辅料按锅平摊 ====================

    /** 某共享锅的总产出量 (Σ output_quantity), 用于辅料按产出量分摊。 */
    @Query("SELECT COALESCE(SUM(r.outputQuantity), 0) FROM ProductionReport r " +
           "WHERE r.factoryId = :factoryId AND r.auxPotNo = :auxPotNo AND r.deletedAt IS NULL")
    BigDecimal sumOutputByAuxPotNo(@Param("factoryId") String factoryId, @Param("auxPotNo") String auxPotNo);

    // ==================== 列表查询 ====================

    Page<ProductionReport> findByFactoryIdAndDeletedAtIsNull(
            String factoryId, Pageable pageable);

    Page<ProductionReport> findByFactoryIdAndReportTypeAndDeletedAtIsNull(
            String factoryId, String reportType, Pageable pageable);

    Page<ProductionReport> findByFactoryIdAndReportDateBetweenAndDeletedAtIsNull(
            String factoryId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    Page<ProductionReport> findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
            String factoryId, String reportType, LocalDate startDate, LocalDate endDate, Pageable pageable);

    // ==================== 按状态查询 ====================

    long countByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, ProductionReport.Status status);

    Page<ProductionReport> findByFactoryIdAndStatusAndDeletedAtIsNull(
            String factoryId, ProductionReport.Status status, Pageable pageable);

    // ==================== SmartBI同步 ====================

    List<ProductionReport> findByFactoryIdAndSyncedToSmartbiFalseAndDeletedAtIsNull(String factoryId);

    @Query("SELECT DISTINCT r.factoryId FROM ProductionReport r WHERE r.syncedToSmartbi = false AND r.deletedAt IS NULL")
    List<String> findDistinctFactoryIdsWithUnsyncedReports();

    // ==================== 汇总统计 ====================

    @Query(value = """
        SELECT
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as total_output,
            COALESCE(SUM(CAST(good_quantity AS DECIMAL(12,2))), 0) as total_good,
            COALESCE(SUM(CAST(defect_quantity AS DECIMAL(12,2))), 0) as total_defect,
            COUNT(*) as report_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND report_type = 'PROGRESS'
          AND report_date BETWEEN :startDate AND :endDate
          AND deleted_at IS NULL
        """, nativeQuery = true)
    Map<String, Object> getProgressSummary(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            COALESCE(SUM(total_work_minutes), 0) as total_minutes,
            COALESCE(SUM(total_workers), 0) as total_workers,
            COALESCE(SUM(CAST(operation_volume AS DECIMAL(10,2))), 0) as total_volume,
            COUNT(*) as report_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND report_type = 'HOURS'
          AND report_date BETWEEN :startDate AND :endDate
          AND deleted_at IS NULL
        """, nativeQuery = true)
    Map<String, Object> getHoursSummary(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT COUNT(*) FROM production_reports
        WHERE factory_id = :factoryId
          AND report_date = :date
          AND deleted_at IS NULL
        """, nativeQuery = true)
    long countByFactoryIdAndDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDate date);

    // ==================== 生产分析 ====================

    @Query(value = """
        SELECT
            report_date as date,
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as output,
            COALESCE(SUM(CAST(good_quantity AS DECIMAL(12,2))), 0) as good,
            COALESCE(SUM(CAST(defect_quantity AS DECIMAL(12,2))), 0) as defect,
            COUNT(*) as report_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND report_type = 'PROGRESS'
          AND report_date BETWEEN :startDate AND :endDate
          AND deleted_at IS NULL
        GROUP BY report_date
        ORDER BY report_date
        """, nativeQuery = true)
    List<Map<String, Object>> getDailyProductionTrend(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            COALESCE(product_name, '未分类') as product_name,
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as output,
            COALESCE(SUM(CAST(good_quantity AS DECIMAL(12,2))), 0) as good,
            COALESCE(SUM(CAST(defect_quantity AS DECIMAL(12,2))), 0) as defect,
            COUNT(*) as report_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND report_type = 'PROGRESS'
          AND report_date BETWEEN :startDate AND :endDate
          AND deleted_at IS NULL
        GROUP BY product_name
        ORDER BY output DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getProductBreakdown(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            COALESCE(process_category, '未分类') as process_category,
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as output,
            COALESCE(SUM(CAST(good_quantity AS DECIMAL(12,2))), 0) as good,
            COALESCE(SUM(CAST(defect_quantity AS DECIMAL(12,2))), 0) as defect,
            COUNT(*) as report_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND report_type = 'PROGRESS'
          AND report_date BETWEEN :startDate AND :endDate
          AND deleted_at IS NULL
        GROUP BY process_category
        ORDER BY output DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getProcessBreakdown(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ==================== 人效分析 ====================

    @Query(value = """
        SELECT
            p.worker_id,
            p.reporter_name as worker_name,
            COALESCE(SUM(CAST(p.output_quantity AS DECIMAL(12,2))), 0) as total_output,
            COALESCE(SUM(CAST(p.good_quantity AS DECIMAL(12,2))), 0) as total_good,
            COALESCE(SUM(CAST(p.defect_quantity AS DECIMAL(12,2))), 0) as total_defect,
            COALESCE(SUM(h.total_work_minutes), 0) as total_minutes,
            COUNT(DISTINCT p.report_date) as work_days
        FROM production_reports p
        LEFT JOIN production_reports h ON h.worker_id = p.worker_id
            AND h.factory_id = p.factory_id
            AND h.report_type = 'HOURS'
            AND h.report_date BETWEEN :startDate AND :endDate
            AND h.deleted_at IS NULL
        WHERE p.factory_id = :factoryId
          AND p.report_type = 'PROGRESS'
          AND p.report_date BETWEEN :startDate AND :endDate
          AND p.deleted_at IS NULL
        GROUP BY p.worker_id, p.reporter_name
        ORDER BY total_output DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getWorkerEfficiencyRanking(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            p.report_date as date,
            COALESCE(SUM(CAST(p.output_quantity AS DECIMAL(12,2))), 0) as total_output,
            COALESCE(SUM(h.total_work_minutes), 0) as total_minutes,
            COUNT(DISTINCT p.worker_id) as worker_count
        FROM production_reports p
        LEFT JOIN production_reports h ON h.worker_id = p.worker_id
            AND h.factory_id = p.factory_id
            AND h.report_type = 'HOURS'
            AND h.report_date = p.report_date
            AND h.deleted_at IS NULL
        WHERE p.factory_id = :factoryId
          AND p.report_type = 'PROGRESS'
          AND p.report_date BETWEEN :startDate AND :endDate
          AND p.deleted_at IS NULL
        GROUP BY p.report_date
        ORDER BY p.report_date
        """, nativeQuery = true)
    List<Map<String, Object>> getDailyEfficiencyTrend(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            COALESCE(p.product_name, '未分类') as product_name,
            COALESCE(SUM(h.total_work_minutes), 0) as total_minutes,
            COALESCE(SUM(CAST(p.output_quantity AS DECIMAL(12,2))), 0) as total_output,
            COUNT(DISTINCT p.worker_id) as worker_count
        FROM production_reports p
        LEFT JOIN production_reports h ON h.worker_id = p.worker_id
            AND h.factory_id = p.factory_id
            AND h.report_type = 'HOURS'
            AND h.report_date = p.report_date
            AND h.deleted_at IS NULL
        WHERE p.factory_id = :factoryId
          AND p.report_type = 'PROGRESS'
          AND p.report_date BETWEEN :startDate AND :endDate
          AND p.deleted_at IS NULL
        GROUP BY p.product_name
        ORDER BY total_minutes DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getHoursBreakdownByProduct(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(value = """
        SELECT
            p.reporter_name as worker_name,
            COALESCE(p.process_category, '未分类') as process_category,
            COALESCE(SUM(CAST(p.output_quantity AS DECIMAL(12,2))), 0) as output
        FROM production_reports p
        WHERE p.factory_id = :factoryId
          AND p.report_type = 'PROGRESS'
          AND p.report_date BETWEEN :startDate AND :endDate
          AND p.deleted_at IS NULL
        GROUP BY p.reporter_name, p.process_category
        ORDER BY p.reporter_name, output DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getWorkerProcessCross(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ==================== PROCESS 模式 ====================

    List<ProductionReport> findByProcessTaskIdAndDeletedAtIsNull(String processTaskId);

    List<ProductionReport> findByProcessTaskIdAndApprovalStatusAndDeletedAtIsNull(
            String processTaskId, String approvalStatus);

    Page<ProductionReport> findByFactoryIdAndApprovalStatusAndProcessTaskIdIsNotNullAndDeletedAtIsNull(
            String factoryId, String approvalStatus, Pageable pageable);

    @Query("""
            SELECT r FROM ProductionReport r
            WHERE r.factoryId = :factoryId
              AND r.approvalStatus = :approvalStatus
              AND r.deletedAt IS NULL
              AND (r.processTaskId IS NOT NULL OR r.workProcessTaskId IS NOT NULL)
            """)
    Page<ProductionReport> findPendingApprovalsForFactory(
            @Param("factoryId") String factoryId,
            @Param("approvalStatus") String approvalStatus,
            Pageable pageable);

    @Query(value = """
        SELECT
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as total
        FROM production_reports
        WHERE process_task_id = :taskId
          AND approval_status = 'APPROVED'
          AND deleted_at IS NULL
        """, nativeQuery = true)
    Map<String, Object> sumApprovedQuantityByTaskId(@Param("taskId") String taskId);

    @Query(value = """
        SELECT
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as total
        FROM production_reports
        WHERE process_task_id = :taskId
          AND approval_status = 'PENDING'
          AND deleted_at IS NULL
        """, nativeQuery = true)
    Map<String, Object> sumPendingQuantityByTaskId(@Param("taskId") String taskId);

    @Query(value = """
        SELECT COALESCE(SUM(CAST(COALESCE(custom_fields->>'sourceWipQuantity', input_quantity::text) AS DECIMAL(12,2))), 0)
        FROM production_reports
        WHERE factory_id = :factoryId
          AND source_wip_no = :sourceWipNo
          AND approval_status = 'PENDING'
          AND input_quantity IS NOT NULL
          AND (:excludeReportId IS NULL OR id <> :excludeReportId)
          AND deleted_at IS NULL
        """, nativeQuery = true)
    BigDecimal sumPendingInputBySourceWipNo(
            @Param("factoryId") String factoryId,
            @Param("sourceWipNo") String sourceWipNo,
            @Param("excludeReportId") Long excludeReportId);

    @Query(value = """
        SELECT
            worker_id,
            reporter_name as worker_name,
            COALESCE(SUM(CAST(output_quantity AS DECIMAL(12,2))), 0) as total_quantity,
            COUNT(*) as report_count
        FROM production_reports
        WHERE process_task_id = :taskId
          AND approval_status = 'APPROVED'
          AND deleted_at IS NULL
        GROUP BY worker_id, reporter_name
        ORDER BY total_quantity DESC
        """, nativeQuery = true)
    List<Map<String, Object>> getWorkerSummaryByTaskId(@Param("taskId") String taskId);

    // ==================== 报工防重 (30s 时间窗口) ====================

    // #566 T4-B6: 替代 in-memory filter on findByProcessTaskIdAndDeletedAtIsNull —
    // F006 prod 单任务可累计 ~6700 行,JVM 端 stream filter 单次 3-15s。
    // 配套 partial index idx_pr_dedup (V20260514_01) → 索引扫描 <5ms。
    @Query(value = """
        SELECT * FROM production_reports
        WHERE process_task_id = :taskId
          AND worker_id = :workerId
          AND output_quantity = :qty
          AND created_at > :since
          AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<ProductionReport> findRecentDuplicate(
            @Param("taskId") String taskId,
            @Param("workerId") Long workerId,
            @Param("qty") BigDecimal qty,
            @Param("since") LocalDateTime since);

    // ==================== 冲销防重 ====================

    boolean existsByReversalOfIdAndDeletedAtIsNull(Long reversalOfId);

    // ==================== 报工防重提交 ====================

    boolean existsByFactoryIdAndWorkerIdAndBatchIdAndReportDateAndDeletedAtIsNull(
            String factoryId, Long workerId, Long batchId, LocalDate reportDate);

    boolean existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndReportDateAndDeletedAtIsNull(
            String factoryId, Long workerId, Long batchId, String reportType, LocalDate reportDate);

    boolean existsByFactoryIdAndWorkerIdAndReportTypeAndReportDateAndBatchIdIsNullAndDeletedAtIsNull(
            String factoryId, Long workerId, String reportType, LocalDate reportDate);

    // 单元B (F006 REQ-17): 5 分钟窗口去重 (createdAt > cutoff), 替代全天去重以解封累加式分时段报工。
    // 仅拦 5 分钟内的 double-click, 不再封锁同日累加。createdAt 为实体字段 → CreatedAtAfter 派生 createdAt > cutoff。

    boolean existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(
            String factoryId, Long workerId, Long batchId, String reportType, LocalDateTime cutoff);

    boolean existsByFactoryIdAndWorkerIdAndReportTypeAndCreatedAtAfterAndBatchIdIsNullAndDeletedAtIsNull(
            String factoryId, Long workerId, String reportType, LocalDateTime cutoff);

    // ==================== 报工傻瓜化: 上次报工 + 历史均值 ====================

    ProductionReport findTopByFactoryIdAndWorkerIdAndReportTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long workerId, String reportType);

    @Query(value = """
        SELECT
            COALESCE(AVG(CAST(output_quantity AS DOUBLE PRECISION)), 0) as avg_output,
            COALESCE(STDDEV(CAST(output_quantity AS DOUBLE PRECISION)), 0) as stddev_output,
            COALESCE(AVG(CAST(defect_quantity AS DOUBLE PRECISION)), 0) as avg_defect,
            COUNT(*) as sample_count
        FROM production_reports
        WHERE factory_id = :factoryId
          AND process_category = :processCategory
          AND report_type = 'PROGRESS'
          AND report_date >= :sinceDate
          AND deleted_at IS NULL
          AND output_quantity IS NOT NULL
        """, nativeQuery = true)
    Map<String, Object> getHistoricalAverageByProcess(
            @Param("factoryId") String factoryId,
            @Param("processCategory") String processCategory,
            @Param("sinceDate") LocalDate sinceDate);

    // ==================== Phase A 出成率 (report_type='YIELD') ====================

    /** 某批次全部 YIELD 报工 (按工序序), 出成率派生用 */
    @Query("SELECT r FROM ProductionReport r WHERE r.factoryId = :factoryId "
            + "AND r.batchId = :batchId AND r.reportType = 'YIELD' AND r.deletedAt IS NULL "
            + "ORDER BY r.processOrder ASC, r.createdAt ASC")
    List<ProductionReport> findYieldReportsByBatch(@Param("factoryId") String factoryId,
                                                   @Param("batchId") Long batchId);

    /** 某工序任务的全部 YIELD 报工 (一道工序可 N 条分次报) */
    @Query("SELECT r FROM ProductionReport r WHERE r.factoryId = :factoryId "
            + "AND r.workProcessTaskId = :taskId AND r.reportType = 'YIELD' AND r.deletedAt IS NULL "
            + "ORDER BY r.createdAt ASC")
    List<ProductionReport> findYieldReportsByTask(@Param("factoryId") String factoryId,
                                                  @Param("taskId") Long taskId);

    /**
     * 权威规则: 该批次是否已有 YIELD 报工 (有则 YIELD 是产出权威源, 老报工端点应拒绝).
     * <p>暂未 wire — 留给 Phase D: RN 从 /process-work-reporting 切到 YIELD API 前,
     * 用它做老/新端点同批次互斥校验 (spec §9.6/§9.7). Phase A report_type 物理隔离已防派生污染,
     * RN 未接, 故互斥护栏 deferred 不阻塞。
     */
    boolean existsByFactoryIdAndBatchIdAndReportTypeAndDeletedAtIsNull(
            String factoryId, Long batchId, String reportType);

    /** 某批次某日未结清的 YIELD 报工 (每日结清打标用) */
    @Query("SELECT r FROM ProductionReport r WHERE r.factoryId = :factoryId "
            + "AND r.batchId = :batchId AND r.reportType = 'YIELD' "
            + "AND r.reportDate = :date AND r.settled = false AND r.deletedAt IS NULL")
    List<ProductionReport> findUnsettledYieldReports(@Param("factoryId") String factoryId,
                                                     @Param("batchId") Long batchId,
                                                     @Param("date") LocalDate date);

    // ==================== A2b: 自动结清 — @> jsonb 包含查询 ====================

    /**
     * A2b: 查找 material_batch_refs 包含特定 materialBatchId 的未结清 YIELD 报工。
     * 使用 GIN idx_pr_material_batch_refs (jsonb_path_ops) 支持 @> 包含查询。
     * 调用方传 refJson = "[{\"materialBatchId\":" + materialBatchId + "}]"
     */
    @Query(value = "SELECT * FROM production_reports r " +
                   "WHERE r.factory_id = :factoryId " +
                   "AND r.batch_id = :batchId " +
                   "AND r.report_type = 'YIELD' " +
                   "AND (r.settled IS NULL OR r.settled = false) " +
                   "AND r.deleted_at IS NULL " +
                   "AND r.material_batch_refs @> CAST(:refJson AS jsonb)",
           nativeQuery = true)
    List<ProductionReport> findUnsettledYieldContainingMaterialBatch(
            @Param("factoryId") String factoryId,
            @Param("batchId") Long batchId,
            @Param("refJson") String refJson);

    /**
     * 按正式半成品 SKU 聚合全部已小结的逐道录入。
     *
     * <p>ProductionReport.productTypeId 在文员逐道路径不是稳定的半成品 SKU 身份，
     * semiCode 也是历史业务编码，因此不做猜测。process_sheet_rows.row_payload
     * 同时保存了产出 ProductType ID、投入/产出量和单位，interim_settled_at
     * 是已小结事实；撤销小结会清除该标记，因而自动退出统计。
     */
    @Query(value = """
        WITH raw_rows AS (
            SELECT
                CAST(NULLIF(row_payload ->> 'inputQuantity', '') AS DECIMAL(20,6)) AS input_quantity,
                CAST(NULLIF(row_payload ->> 'outputQuantity', '') AS DECIMAL(20,6)) AS output_quantity,
                LOWER(COALESCE(NULLIF(row_payload ->> 'inputUnit', ''),
                               NULLIF(row_payload ->> 'unit', ''), 'kg')) AS input_unit,
                LOWER(COALESCE(NULLIF(row_payload ->> 'outputUnit', ''),
                               NULLIF(row_payload ->> 'unit', ''), 'kg')) AS output_unit,
                COALESCE(CAST(batch_id AS TEXT), NULLIF(batch_number, ''), CAST(id AS TEXT)) AS batch_key
            FROM process_sheet_rows
            WHERE factory_id = :factoryId
              AND interim_settled_at IS NOT NULL
              AND deleted_at IS NULL
              AND row_status <> 'DRAFT'
              AND row_payload ->> 'productTypeId' = :semiFinishedSkuId
              AND row_payload ->> 'finished' = 'false'
        ), valid_rows AS (
            SELECT
                CASE WHEN input_unit IN ('g', '克') THEN input_quantity / 1000
                     ELSE input_quantity END AS input_kg,
                CASE WHEN output_unit IN ('g', '克') THEN output_quantity / 1000
                     ELSE output_quantity END AS output_kg,
                batch_key
            FROM raw_rows
            WHERE input_quantity > 0
              AND output_quantity > 0
              AND input_unit IN ('kg', '千克', '公斤', 'g', '克')
              AND output_unit IN ('kg', '千克', '公斤', 'g', '克')
        )
        SELECT COALESCE(SUM(input_kg), 0) AS "totalInputKg",
               COALESCE(SUM(output_kg), 0) AS "totalOutputKg",
               COUNT(DISTINCT batch_key) AS "batchCount"
        FROM valid_rows
        """, nativeQuery = true)
    SemiFinishedYieldAggregate aggregateSettledSemiFinishedYield(
            @Param("factoryId") String factoryId,
            @Param("semiFinishedSkuId") String semiFinishedSkuId);

    interface SemiFinishedYieldAggregate {
        BigDecimal getTotalInputKg();
        BigDecimal getTotalOutputKg();
        Long getBatchCount();
    }

    // ==================== 单元2: 厂级工序聚合 ====================

    /**
     * audit 单元2: 厂级按工序聚合 YIELD 报工投入/产出. native (沿用 analytics 投影惯例).
     * 单位取工序标准 wp.unit/wp.output_unit (audit SQL-2). 可空参数由 service 转 sentinel
     * (audit SQL-1: 永不传 null, 避 PG 类型推断失败).
     */
    @Query(value = """
        SELECT
            wp.process_name AS process_name,
            COALESCE(SUM(CAST(pr.input_quantity AS DECIMAL(14,2))), 0) AS total_input,
            COALESCE(SUM(CAST(pr.output_quantity AS DECIMAL(14,2))), 0) AS total_output,
            wp.unit AS input_unit,
            wp.output_unit AS output_unit,
            COUNT(DISTINCT pr.batch_id) AS batch_count
        FROM production_reports pr
        JOIN work_process_tasks wpt ON pr.work_process_task_id = wpt.id AND wpt.deleted_at IS NULL
        JOIN work_processes wp ON wpt.work_process_id = wp.id AND wp.deleted_at IS NULL
        WHERE pr.factory_id = :factoryId
          AND wpt.factory_id = :factoryId
          AND pr.report_type = 'YIELD'
          AND pr.deleted_at IS NULL
          AND pr.report_date BETWEEN :startDate AND :endDate
          AND (:productTypeId = '' OR wpt.product_type_id = :productTypeId)
        GROUP BY wp.id, wp.process_name, wp.unit, wp.output_unit
        ORDER BY MIN(wpt.process_order)
        """, nativeQuery = true)
    List<Map<String, Object>> aggregateYieldByProcess(
            @Param("factoryId") String factoryId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("productTypeId") String productTypeId);

    /**
     * D4: 每个工序按批次聚合的自动标准计算样本.
     * <p>每行代表同一 workProcessId 在一个批次/工序任务上的投入、产出、工时、人数、人工成本汇总.
     * service 端负责过滤 0/null 样本、做 P20/P80 分位和手填优先写回.
     */
    @Query(value = """
        SELECT
            wpt.work_process_id AS work_process_id,
            pr.batch_id AS batch_id,
            COALESCE(SUM(CAST(pr.input_quantity AS DECIMAL(14,4))), 0) AS input_quantity,
            COALESCE(SUM(CAST(pr.output_quantity AS DECIMAL(14,4))), 0) AS output_quantity,
            COALESCE(SUM(pr.total_work_minutes), 0) AS total_work_minutes,
            COALESCE(SUM(pr.total_workers), 0) AS total_workers,
            COALESCE(SUM(CAST(pr.labor_cost AS DECIMAL(14,2))), 0) AS labor_cost
        FROM production_reports pr
        JOIN work_process_tasks wpt ON pr.work_process_task_id = wpt.id AND wpt.deleted_at IS NULL
        WHERE pr.factory_id = :factoryId
          AND wpt.factory_id = :factoryId
          AND pr.report_type = 'YIELD'
          AND pr.deleted_at IS NULL
          AND pr.work_process_task_id IS NOT NULL
          AND pr.batch_id IS NOT NULL
        GROUP BY wpt.work_process_id, pr.batch_id, pr.work_process_task_id
        ORDER BY wpt.work_process_id, pr.batch_id
        """, nativeQuery = true)
    List<Map<String, Object>> findYieldStandardSamples(@Param("factoryId") String factoryId);

    /**
     * SP-F: 软删除某生产批次(batchId)的全部报工记录。
     * 用于 re-save/delete 时逆向清除已物化的报工行，factory-scoped 防跨租户。
     * ProductionReport.batchId 是 Long (production_batch.id)。
     */
    @Modifying
    @Query("UPDATE ProductionReport r SET r.deletedAt = CURRENT_TIMESTAMP " +
           "WHERE r.factoryId = :f AND r.batchId = :bId AND r.deletedAt IS NULL")
    int softDeleteByFactoryIdAndBatchId(@Param("f") String factoryId,
                                        @Param("bId") Long batchId);
}
