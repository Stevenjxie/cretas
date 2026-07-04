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
     * 🔴🔒🔒 关单-前-小结 防呆 (bug fix 2026-07-04): 统计指定批次集合上尚未小结的正向消耗笔数。
     *
     * <p>物料消耗采「延迟扣减」设计: 报工时写 {@link MaterialConsumption} (未结, {@code interimSettledAt}
     * IS NULL, {@code batchId} = 生产仓 WKS 批次) 但不扣 WKS.usedQuantity; 直到「小结」才逐笔扣减。
     * 领料单关单 {@code FactoryMaterialRequisitionServiceImpl.close} 若在小结前执行, 会把「未消耗剩余」
     * 误判为全额发出量 → 幻库存退回 + 划空 WKS → 随后小结对已划空批次扣减 409 永久卡死。关单前用本查询
     * 检测本单 WKS 批次是否尚有未结报工消耗, 有则 loud-block 引导先小结。
     *
     * <p>{@code quantity > 0} 排除退料回库写的负数留痕 (那些引用原料仓源批次, 非 WKS, 本已不在集合内,
     * 此为双保险)。类级 {@code @Where(deleted_at IS NULL)} 自动附加软删除过滤。Factory-scoped 防跨租户。
     */
    @Query("SELECT COUNT(m) FROM MaterialConsumption m WHERE m.factoryId = :factoryId "
            + "AND m.batchId IN :batchIds AND m.interimSettledAt IS NULL AND m.quantity > 0")
    long countUnsettledConsumptionByBatchIds(@Param("factoryId") String factoryId,
                                             @Param("batchIds") List<String> batchIds);

    /**
     * 🔴🔒🔒 生产仓盘点「延迟扣减」盲区修复 (bug fix 2026-07-04, mirror
     * {@link #countUnsettledConsumptionByBatchIds} 但逐批 <b>SUM 数量</b>而非计数):
     * 汇总指定批次集合上尚未小结的正向消耗数量, 按批次分组返回 {@code [batchId, Σquantity]}。
     *
     * <p>物料消耗采「延迟扣减」设计: 报工写 {@link MaterialConsumption} (未结, {@code interimSettledAt}
     * IS NULL, {@code batchId} = 被消耗的来源批次) 但<b>不即时扣</b> {@code usedQuantity}; 直到「小结」
     * ({@code InterimSettleServiceImpl} §①) 才在同一原子事务内逐笔 {@code usedQuantity += quantity} +
     * stamp {@code interimSettledAt}。因此某批次「货架实物」= {@code getPhysicalQuantity()}(=receipt−used)
     * − Σ(本查询未结消耗) —— 未结消耗对应的货已物理投入产品/在制, 只是账面 {@code usedQuantity} 尚未扣。
     *
     * <p><b>盘点必须减去本值</b>: 否则生产仓在「报工-未小结」窗口盘点, 会把待扣量误判为盘亏 (假 6602
     * 管理费用凭证), 且随后小结对已被盘点划空 (receipt 下移) 的批次 {@code usedQuantity += quantity} 会把
     * currentQuantity 扣成负 → {@code BATCH_INSUFFICIENT} 409 永久卡死 (关单亦 409, 双向死锁)。
     *
     * <p>与 count 版<b>同谓词</b> ({@code factoryId + batchId IN + interimSettledAt IS NULL + quantity>0}),
     * 继承其正确域: {@code quantity>0} 排除退料回库负数留痕; 类级 {@code @Where(deleted_at IS NULL)} 附加
     * 软删除过滤; {@code interimSettledAt IS NULL} 与 {@code usedQuantity} 扣减在小结事务内原子同步 →
     * 无「已扣 used 但仍未结」窗口 → 绝不重复减 (与 {@code getPhysicalQuantity()=receipt−used} 组合, 未结
     * 消耗只减一次, 无 double-subtract)。领料即时扣减 / #1201/#1213 原料仓场景无未结消耗 → 不在结果中,
     * 调用方按 0 处理 → 快照口径不变。Factory-scoped 防跨租户。
     */
    @Query("SELECT m.batchId, SUM(m.quantity) FROM MaterialConsumption m WHERE m.factoryId = :factoryId "
            + "AND m.batchId IN :batchIds AND m.interimSettledAt IS NULL AND m.quantity > 0 "
            + "GROUP BY m.batchId")
    List<Object[]> sumUnsettledConsumptionGroupedByBatch(@Param("factoryId") String factoryId,
                                                         @Param("batchIds") List<String> batchIds);

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
