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
     * 汇总指定批次集合上<b>真正待小结</b>的正向消耗数量, 按批次分组返回 {@code [batchId, Σquantity]}。
     *
     * <p>物料消耗采「延迟扣减」设计: 报工写 {@link MaterialConsumption} (未结, {@code interimSettledAt}
     * IS NULL, {@code batchId} = 被消耗的来源批次) 但<b>不即时扣</b> {@code usedQuantity}; 直到「小结」
     * ({@code InterimSettleServiceImpl} §①) 才在同一原子事务内逐笔 {@code usedQuantity += quantity} +
     * stamp {@code interimSettledAt}。因此某批次「货架实物」= {@code getPhysicalQuantity()}(=receipt−used)
     * − Σ(本查询待小结消耗) —— 待小结消耗对应的货已物理投入产品/在制, 只是账面 {@code usedQuantity} 尚未扣。
     *
     * <p><b>盘点必须减去本值</b>: 否则生产仓在「报工-未小结」窗口盘点, 会把待扣量误判为盘亏 (假 6602
     * 管理费用凭证), 且随后小结对已被盘点划空 (receipt 下移) 的批次 {@code usedQuantity += quantity} 会把
     * currentQuantity 扣成负 → {@code BATCH_INSUFFICIENT} 409 永久卡死 (关单亦 409, 双向死锁)。
     *
     * <p><b>🔴🔒🔒 计划族门控 (bug fix 2026-07-04, #1216↔#1217 姊妹流缺口)</b>: {@code interimSettledAt
     * IS NULL} <b>只对「存货生产 (SAFETY_STOCK)」计划</b>代表「待小结、账面 usedQuantity 尚未扣」。
     * 非 SAFETY_STOCK 的「结单族」计划 (CUSTOMER_ORDER / MANUAL / …) 走「结单」
     * ({@code ProductionPlanServiceImpl.settleProduction → postMaterialBatchConsumption}) 在结单时即时扣
     * {@code usedQuantity}, <b>永不 stamp {@code interimSettledAt}</b> → 其报工消耗行恒 {@code interimSettledAt
     * IS NULL} 但<b>早已落库扣减</b> (非待小结)。若不区分计划族一并减去 (#1216 首版缺陷), 结单族批次的
     * {@code getPhysicalQuantity()} (已扣 used) 会被<b>二次减去同一消耗</b> → 账面虚低 → 仓管数到实物 →
     * 假盘盈 + 假 借1403/贷6301 凭证 → apply 平移 receipt 上抬 → 幻库存累积 (每次盘点腐蚀)。
     *
     * <p><b>为何按 productionBatchId → process_sheet_rows.planId 反查族, 而非 mc.productionPlanId 或
     * ProductionBatch.productionPlanId</b>: (1) 逐工序非末道消耗行 {@code mc.productionPlanId} <b>故意为
     * null</b> (防成本双计, 见 {@code ClerkProcessEntryServiceImpl} writeConsumption); 按它门控会漏掉这些
     * <b>真 SAFETY_STOCK 待小结</b>行 → 重开 #1216 原盲区 (SAFETY_STOCK 报工-未结窗口假盘亏)。(2) 非末道
     * WIP {@code ProductionBatch.productionPlanId} <b>亦为 null</b> (createProductionBatch 仅 FINISHED 批挂
     * 计划) → 经 ProductionBatch 反查同样漏。唯一可靠链 = {@code mc.productionBatchId ∈
     * process_sheet_rows.batch_id} 且该 row 的 {@code plan_id} 对应计划 {@code sourceType = SAFETY_STOCK}
     * —— 这正是 {@code InterimSettleServiceImpl §①} 定位待扣减行的<b>同一 key</b> (它按本计划各道
     * {@code process_sheet_rows.batch_id} 收集 {@code productionBatchId} 再扣减), 故本 SUM 的减除集 <b>严格
     * 等于</b>小结将扣减集 —— 只减真正会被小结扣的量, 不多不少。honest-null-safe: {@code productionBatchId}
     * 为 null / 不属任何 SAFETY_STOCK 计划 process_sheet_row → 不在子查询内 → 不减 (族未知即不减, 避免错减
     * 腐蚀财务); 而小结本也只经 {@code productionBatchId IN} 扣减这些行, 故排除口径一致。
     *
     * <p>其余谓词与 count 版<b>同</b> ({@code factoryId + batchId IN + interimSettledAt IS NULL + quantity>0}):
     * {@code quantity>0} 排除退料回库负数留痕; 类级 {@code @Where(deleted_at IS NULL)} 附加软删除过滤;
     * {@code interimSettledAt IS NULL} 与 {@code usedQuantity} 扣减在小结事务内原子同步 → 无「已扣 used 但仍
     * 未结」窗口 → 与 {@code getPhysicalQuantity()=receipt−used} 组合, 待小结消耗只减一次, 无 double-subtract。
     * 领料即时扣减 / #1201/#1213 原料仓场景无待小结消耗 → 不在结果中, 调用方按 0 处理 → 快照口径不变。
     *
     * <p><b>与 count 版 ({@link #countUnsettledConsumptionByBatchIds}) 的门控差异</b>: count 版由唯一调用方
     * ({@code FactoryMaterialRequisitionServiceImpl.close} 经 {@code isInterimSettlePlan} #1217) 在<b>调用点</b>
     * 已按单一计划族门控 (仅 SAFETY_STOCK 领料单才 count), 且只查该单 WKS 批次 → 无需查内族过滤。本 SUM 版被
     * 盘点<b>跨整仓多批次</b>调用 (这些批次可能横跨多个不同族计划), 无法在调用点门控 → 必须在查询内按族过滤。
     * Factory-scoped 防跨租户。
     *
     * <p><b>🔴🔒🔒 2026-07-05 根因闭合: 移除 SAFETY_STOCK <u>族</u>门控 (结单族盘点盲区)</b> —— 上文 #1216
     * 口径「结单族 mid-production 不减」被证明是<b>漏减</b>: 结单族报工写 {@link MaterialConsumption} (未结,
     * IS NULL) 但<b>结单前不扣</b> {@code usedQuantity} (延迟扣减, 见 {@code writeConsumption} 不动 used +
     * {@code settleProduction} 的 {@code postConsumptionToInventory} 才扣)。故结单族 {@code interimSettledAt
     * IS NULL} 消耗 = <b>结单前、尚未扣减</b> (与 SAFETY_STOCK 待小结同构), 盘点<b>必须减去</b> —— 否则报工-结单
     * 窗口盘点账面 stale-high → 假盘亏 (借6602/贷1403)。结单后经 {@link #stampInterimSettledForPlan} 打戳 →
     * {@code interimSettledAt IS NOT NULL} 天然落谓词外, 不双减。故删 {@code p.sourceType = SAFETY_STOCK} 谓词,
     * IS NULL 现对<b>所有</b>计划族统一 = 「尚未扣减、待减」。
     *
     * <p><b>⚠️ 保留</b> {@code productionBatchId ∈ process_sheet_rows.batch_id} 子查询 (仅去掉 ProductionPlan
     * 族过滤): 它<b>不是</b>族门控, 而是<b>合法性/legacy 门控</b> —— 排除即时扣减 legacy 消耗
     * ({@code MaterialBatchServiceImpl.consumeBatchMaterial} / {@code ProcessingServiceImpl}: 写正数消耗 +
     * <b>同步扣</b> {@code usedQuantity} 却 IS NULL, {@code productionBatchId} 恒 null → 不在子查询内 → 不减,
     * 否则对这些"已扣 used"批次二次减 → 假盘盈)。逐工序报工消耗恒经 {@code writeConsumption} 设
     * {@code productionBatchId} → 在子查询内 → 正确纳入。honest-null-safe: 孤儿 (productionBatchId null /
     * 无匹配 process_sheet_row) 不减。
     */
    @Query("SELECT m.batchId, SUM(m.quantity) FROM MaterialConsumption m WHERE m.factoryId = :factoryId "
            + "AND m.batchId IN :batchIds AND m.interimSettledAt IS NULL AND m.quantity > 0 "
            + "AND m.productionBatchId IN ("
            + "  SELECT r.batchId FROM ProcessSheetRow r "
            + "  WHERE r.factoryId = :factoryId AND r.batchId IS NOT NULL) "
            + "GROUP BY m.batchId")
    List<Object[]> sumUnsettledConsumptionGroupedByBatch(@Param("factoryId") String factoryId,
                                                         @Param("batchIds") List<String> batchIds);

    /**
     * 🔴🔒🔒 结单族「扣减即打戳」根修 (bug fix 2026-07-04, #1216↔#1217 姊妹流的<b>根因闭合</b>):
     * 把指定计划全部道 (process_sheet_rows.batch_id) 上尚未打戳的正向报工消耗行 {@code interim_settled_at}
     * 一次性盖为 {@code settledAt} (= 结单时间戳)。返回受影响行数。
     *
     * <p><b>背景 (为何非 SAFETY_STOCK 族的消耗行需在结单时打戳)</b>: 物料消耗采「延迟扣减」设计 —— 报工
     * ({@code ClerkProcessEntryServiceImpl.writeConsumption}) 写 {@link MaterialConsumption} (未结,
     * {@code interimSettledAt IS NULL}) 但不即时扣 {@code usedQuantity}。SAFETY_STOCK (存货生产) 计划走
     * 「小结」({@code InterimSettleServiceImpl §①}) 逐笔扣 {@code usedQuantity} + 同事务盖
     * {@code interimSettledAt} —— 故其 {@code interimSettledAt IS NULL} 精确代表「待扣减」。而结单族
     * (CUSTOMER_ORDER / MANUAL / …) 走「结单」({@code ProductionPlanServiceImpl.settleProduction}) 在结单时
     * 扣减 {@code usedQuantity} 却<b>从不</b>回头盖其报工消耗行的 {@code interimSettledAt} → 这些行<b>永久</b>
     * 停留 {@code interimSettledAt IS NULL}, 使该谓词对结单族<b>失去</b>「待扣减」语义。
     *
     * <p>此前 #1216/#1219 (盘点 SUM) 与 #1215/#1217 (关单 count 守卫) 各自<b>把结单族行门控排除</b>
     * (SUM 内加 SAFETY_STOCK 子查询 / count 调用点按 {@code isInterimSettlePlan} 收窄) 来绕开这个失真 ——
     * 但那是<b>绕</b>而非<b>治</b>。本方法在结单时打戳, 使 {@code interimSettledAt IS NULL} 对<b>所有</b>计划族
     * 统一 = 「尚未扣减」→ 两处门控退化为<b>冗余而安全</b> (结单族行结单后即非 null, 天然落在谓词外)。
     * 残留 #2 (跨计划投料把结单族未结行留在他单 WKS 批次 → 关单 count 守卫误 409 永久卡死) 亦随之闭合:
     * 结单族计划一旦结单, 其消耗行即被打戳, 不再被 count 守卫统计。
     *
     * <p><b>为何按 productionBatchId ∈ process_sheet_rows.batch_id 定位 (而非 mc.production_plan_id)</b>:
     * 逐工序非末道消耗行 {@code mc.production_plan_id} <b>故意为 null</b> (仅末道/成品挂计划, 防成本双计,
     * 见 {@code ProcessSheetServiceImpl} {@code req.isFinished() ? planId : null})。按 production_plan_id 打戳
     * 会漏掉这些 null-plan 非末道行 → 它们仍 {@code interimSettledAt IS NULL} → 关单 count 守卫仍误统计。
     * 唯一可靠链 = {@code mc.production_batch_id ∈ 本计划 process_sheet_rows.batch_id} —— 与
     * {@code InterimSettleServiceImpl §①} / 撤销侧定位待扣减行的<b>同一 key</b>。
     *
     * <p><b>调用方 (仅结单族) 保证族安全</b>: 由 {@code settleProduction} 在 {@code plan.sourceType != SAFETY_STOCK}
     * 时调用 —— 绝不对 SAFETY_STOCK 计划打戳 (那族的打戳由小结原子完成, 提前打戳会让小结 §① 漏扣 → 幻库存)。
     * 即便跨计划投料把某 SAFETY_STOCK 计划的未结行落在结单族计划的道批次上, 本查询按<b>结单族计划自身</b>的
     * process_sheet_rows.batch_id 定位 (该 SAFETY_STOCK 行的 production_batch_id 属其自身道批次, 不在结单族计划
     * batchId 集内) → 不会误打戳该 SAFETY_STOCK 待扣减行。
     *
     * <p>{@code quantity > 0} 排除退料回库负数留痕 (MATERIAL_RETURN); {@code deleted_at IS NULL} 显式附加
     * (bulk UPDATE 不经类级 {@code @Where} 过滤); {@code interimSettledAt IS NULL} 使重复结单幂等 (已戳不再戳,
     * 时间戳不被覆盖)。Factory-scoped 防跨租户。
     */
    @Modifying
    @Query("UPDATE MaterialConsumption m SET m.interimSettledAt = :settledAt "
            + "WHERE m.factoryId = :factoryId AND m.interimSettledAt IS NULL "
            + "AND m.quantity > 0 AND m.deletedAt IS NULL "
            + "AND m.productionBatchId IN ("
            + "  SELECT r.batchId FROM ProcessSheetRow r "
            + "  WHERE r.factoryId = :factoryId AND r.planId = :planId AND r.batchId IS NOT NULL)")
    int stampInterimSettledForPlan(@Param("factoryId") String factoryId,
                                   @Param("planId") String planId,
                                   @Param("settledAt") LocalDateTime settledAt);

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
