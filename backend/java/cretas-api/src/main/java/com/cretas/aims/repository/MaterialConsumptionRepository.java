package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
/**
 * 原材料消耗记录数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Integer> {
    /**
     * 根据生产计划查找消耗记录
     */
    List<MaterialConsumption> findByProductionPlanId(String productionPlanId);

    /**
     * 根据批次查找消耗记录
     */
    List<MaterialConsumption> findByBatchId(String batchId);

    /**
     * 查找工厂的消耗记录
     */
    List<MaterialConsumption> findByFactoryId(String factoryId);

    /**
     * 根据时间范围查找消耗记录
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId " +
           "AND m.consumptionTime BETWEEN :startTime AND :endTime")
    List<MaterialConsumption> findByTimeRange(@Param("factoryId") String factoryId,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    /**
     * 计算生产计划的总消耗成本
     */
    @Query("SELECT SUM(m.totalCost) FROM MaterialConsumption m WHERE m.productionPlanId = :planId")
    BigDecimal calculateTotalCostByPlan(@Param("planId") String planId);

    /**
     * 计算批次的总消耗量
     */
    @Query("SELECT SUM(m.quantity) FROM MaterialConsumption m WHERE m.batchId = :batchId")
    BigDecimal calculateTotalQuantityByBatch(@Param("batchId") String batchId);

    /**
     * 获取生产计划的消耗统计
     */
    @Query("SELECT m.batch.materialType.name, SUM(m.quantity), SUM(m.totalCost) " +
           "FROM MaterialConsumption m WHERE m.productionPlanId = :planId " +
           "GROUP BY m.batch.materialType.name")
    List<Object[]> getConsumptionStatsByPlan(@Param("planId") String planId);

    /**
     * 删除生产计划的所有消耗记录
     */
    void deleteByProductionPlanId(String productionPlanId);

    /**
     * 根据生产批次ID查找消耗记录
     */
    List<MaterialConsumption> findByProductionBatchId(Long productionBatchId);

    /**
     * 按工厂ID分页查询消耗记录（排序由Pageable控制）
     */
    Page<MaterialConsumption> findByFactoryIdOrderByConsumptionTimeDesc(
            String factoryId, Pageable pageable);

    /**
     * 按工厂ID + 生产批次ID分页查询
     */
    Page<MaterialConsumption> findByFactoryIdAndProductionBatchIdOrderByConsumptionTimeDesc(
            String factoryId, Long productionBatchId, Pageable pageable);

    /**
     * 按工厂ID + 原材料批次ID分页查询
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId AND m.batchId = :batchId ORDER BY m.consumptionTime DESC")
    Page<MaterialConsumption> findByFactoryIdAndBatchIdPaged(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId,
            Pageable pageable);

    /**
     * 按工厂ID + 生产批次ID + 原材料批次ID分页查询
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId " +
           "AND m.productionBatchId = :productionBatchId AND m.batchId = :batchId " +
           "ORDER BY m.consumptionTime DESC")
    Page<MaterialConsumption> findByFactoryIdAndProductionBatchIdAndBatchIdPaged(
            @Param("factoryId") String factoryId,
            @Param("productionBatchId") Long productionBatchId,
            @Param("batchId") String batchId,
            Pageable pageable);

    /**
     * 按工厂ID和原材料批次ID查询消耗记录
     */
    @Query("SELECT m FROM MaterialConsumption m WHERE m.factoryId = :factoryId AND m.batchId = :batchId")
    List<MaterialConsumption> findByFactoryIdAndBatchId(
            @Param("factoryId") String factoryId,
            @Param("batchId") String batchId);

    /**
     * 按生产批次ID和物料类型ID查询消耗记录
     */
    List<MaterialConsumption> findByProductionBatchIdAndMaterialTypeId(Long productionBatchId, String materialTypeId);

    /**
     * 按生产批次ID和工厂ID查询消耗记录
     */
    List<MaterialConsumption> findByProductionBatchIdAndFactoryId(Long productionBatchId, String factoryId);

    /**
     * 计算生产批次的总消耗成本
     */
    @Query("SELECT COALESCE(SUM(m.totalCost), 0) FROM MaterialConsumption m WHERE m.productionBatchId = :batchId")
    BigDecimal calculateTotalCostByProductionBatch(@Param("batchId") Long batchId);

    /**
     * 按生产批次ID和来源类型查询
     */
    List<MaterialConsumption> findByProductionBatchIdAndSourceType(Long productionBatchId, String sourceType);

    /**
     * Task 3 (小结 Service): 查询某生产计划下尚未被小结的消耗行。
     * {@code interimSettledAt IS NULL} = 待处理; 已有值的行已被之前某次小结计入，跳过。
     * Factory-scoped 防跨租户。
     */
    List<MaterialConsumption> findByProductionPlanIdAndFactoryIdAndInterimSettledAtIsNull(
            String productionPlanId, String factoryId);

    /**
     * 小结原料扣减定位 (bug fix 2026-07-02): 按 (factoryId, production_batch_id ∈ 本计划各道 WIP 批次)
     * + 未小结 定位待扣减消耗行。
     *
     * <p><b>为什么不按 production_plan_id 查</b>: 逐工序首/中间道 (finished=false) 写的 raw
     * {@link MaterialConsumption} 其 {@code production_plan_id} <b>故意为 null</b>
     * (见 {@code ClerkProcessEntryServiceImpl} createProductionBatch 注释 — 防 OrderCostBreakdownService
     * 按 plan-scoped SUM 双计在制 WIP 原料成本), 但 {@code production_batch_id} (per-道 WIP ProductionBatch id)
     * <b>恒有值</b>。原 {@code findBy...ProductionPlanId...} 查询会漏掉这些 null-plan 在制道消耗 →
     * 小结扣减循环从不执行 → 原料零扣减 (production creates stock from nothing, 幻库存)。
     *
     * <p>改用 production_batch_id ∈ 本计划各道 process_sheet_rows.batch_id 定位, 命中所有在制/成品道的
     * raw+WIP 消耗边, 与既有成品道扣减行为对称 (成品道 production_plan_id 非 null, 亦被此查询命中)。
     * production_batch_id 上已有 {@code idx_consumption_production_batch} 索引, 无需新增迁移。
     * Factory-scoped 防跨租户。
     */
    List<MaterialConsumption> findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull(
            String factoryId, List<Long> productionBatchIds);

    /**
     * 撤销小结: 查某次小结 (postedAt 时间戳) 扣减的全部消耗行 (interim_settled_at == postedAt)。
     * 逆转时逐行还回来源 MaterialBatch.usedQuantity + 清 interim_settled_at。Factory-scoped 防跨租户。
     */
    List<MaterialConsumption> findByProductionPlanIdAndFactoryIdAndInterimSettledAt(
            String productionPlanId, String factoryId, LocalDateTime interimSettledAt);

    /**
     * 撤销小结 原料还回定位 (bug fix 2026-07-03, mirror
     * {@link #findByFactoryIdAndProductionBatchIdInAndInterimSettledAtIsNull}): 按
     * (factoryId, production_batch_id ∈ 本计划各道 WIP 批次) + interim_settled_at == postedAt 定位
     * 某次小结扣减的全部消耗行, 供撤销逐行还回来源 MaterialBatch.usedQuantity + 清 interim_settled_at。
     *
     * <p><b>为什么不按 production_plan_id 查</b> (与扣减侧同因): 扣减侧
     * {@code InterimSettleServiceImpl} 按 production_batch_id 定位并 stamp 消耗 (含逐工序首/中间道
     * production_plan_id <b>故意为 null</b> 防成本双计的在制道消耗)。撤销侧若按 production_plan_id 反查
     * 会漏掉这些 null-plan 消耗行 → 其 usedQuantity 不还回 + interim_settled_at 戳清不掉 → 撤销后重新
     * 小结时这些行已非未结 → 永久幻扣减 (库存永久短缺, 保证盘点差异)。与扣减侧同 key 反查 → 撤销集 ==
     * 扣减集, 无跨小结/跨计划渗漏。production_batch_id 上已有索引, 无需新增迁移。Factory-scoped 防跨租户。
     */
    List<MaterialConsumption> findByFactoryIdAndProductionBatchIdInAndInterimSettledAt(
            String factoryId, List<Long> productionBatchIds, LocalDateTime interimSettledAt);

    /**
     * SP-F: 软删除某消耗批次(productionBatchId)的全部消耗边记录。
     * 用于 re-save/delete 时逆向清除已物化的消耗 edges，factory-scoped 防跨租户。
     */
    @Modifying
    @Query("UPDATE MaterialConsumption c SET c.deletedAt = CURRENT_TIMESTAMP " +
           "WHERE c.factoryId = :f AND c.productionBatchId = :pbId AND c.deletedAt IS NULL")
    int softDeleteByFactoryIdAndProductionBatchId(@Param("f") String factoryId,
                                                  @Param("pbId") Long productionBatchId);
}
