package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.OutputOptionsResponse;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;

import java.math.BigDecimal;
import java.util.List;

/**
 * Authoritative WIP inventory operations shared by reporting entry points.
 */
public interface WipInventoryService {

    /**
     * SFI 半成品库运行余额行锚 (per-(plan,productType))。
     *
     * <p>格式 {@code CLK-SEMI-{planId[0..8]}-{productTypeId[0..8]}} (各取前 8 位, 总长 ≤64)。
     *
     * <p><b>单一真源</b>: 小结入库 ({@link #postClerkOutput} 的 {@code intermediateBatchNo}) 与
     * 逐道保存 (option F: 纯半成品喂的非成品中间道产出定位) 必须使用<b>同一锚</b>, 故提为
     * 共享静态方法, 防两处派生逻辑漂移。null 段以 {@code 00000000} 占位。
     */
    static String clerkSemiAnchor(String planId, String productTypeId) {
        return "CLK-SEMI-" + head8(planId) + "-" + head8(productTypeId);
    }

    private static String head8(String s) {
        if (s == null) {
            return "00000000";
        }
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    default SemiFinishedInventory validateSourceWip(String sourceWipNo, BigDecimal inputQuantity, String inputUnit) {
        return validateSourceWip(null, sourceWipNo, inputQuantity, inputUnit, null);
    }

    SemiFinishedInventory validateSourceWip(
            String factoryId, String sourceWipNo, BigDecimal inputQuantity, String inputUnit, Long excludeReportId);

    void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId);

    /**
     * SP1 T4 — Returns all WorkProcessTasks for {@code batchId} whose parent
     * WorkProcess has {@code semiFinishedOutputCode} configured.
     *
     * <p>Used by the RN report screen to populate the "semi output code" dropdown.
     *
     * @param factoryId factory scope
     * @param batchId   production batch ID
     * @return response object containing the list of output options (may be empty)
     */
    OutputOptionsResponse getOutputOptions(String factoryId, Long batchId);

    // ==================== SP2 新增方法 ====================

    /**
     * SP2 二次加工 — 按悲观锁扣减 WIP 余量（创建二次加工计划时调用）。
     *
     * <p>此方法必须在调用方的 {@code @Transactional} 内执行，不自行开事务。
     * 扣减后写入 SemiFinishedInventoryTransaction(OUT/SECONDARY_CONSUME) 流水。
     * 余量 = 0 → 自动将 WIP 状态置 DEPLETED。
     *
     * @param wipId      SemiFinishedInventory.id
     * @param qty        扣减数量 (必须 > 0, 且 ≤ availableQuantity)
     * @param factoryId  工厂 ID (幂等守卫 + 流水账写入)
     * @param operatorId 操作人 ID (可为 null)
     * @throws org.springframework.web.server.ResponseStatusException 409 如余量不足
     */
    void deductForSecondaryPlan(Long wipId, BigDecimal qty, String factoryId, Long operatorId);

    /**
     * 修1 (🔴) 二次加工反冲 — {@link #deductForSecondaryPlan} 的逆操作。
     *
     * <p>用于取消"已开工扣了 WIP 但还没任何报工"的 SECONDARY 二次加工计划: 把开工时扣减的
     * secondarySourceWipId 半成品余量加回 (availableQuantity += qty, consumedQuantity -= qty),
     * 若该 WIP 此前被扣至 DEPLETED 则恢复 AVAILABLE, 并写一条 REVERSE/REVERSAL 反向流水。
     *
     * <p>此方法必须在调用方的 {@code @Transactional} 内执行, 不自行开事务。findByIdForUpdate 悲观行锁防并发。
     *
     * @param wipId      SemiFinishedInventory.id (= plan.secondarySourceWipId)
     * @param qty        反冲数量 (必须 > 0, 通常 = 开工时扣减的 plannedQuantity)
     * @param factoryId  工厂 ID (越权守卫 + 流水账写入)
     * @param operatorId 操作人 ID (可为 null)
     * @throws com.cretas.aims.exception.BusinessException 404 WIP 不存在 / 403 工厂不匹配 / 400 qty 非法
     */
    void reverseSecondaryDeduct(Long wipId, BigDecimal qty, String factoryId, Long operatorId);

    /**
     * SP2 二次加工 — 列出工厂所有 AVAILABLE 且 availableQuantity > 0 的 WIP 行。
     *
     * <p>用于 RN/web-admin "选择源 WIP" 下拉列表。
     *
     * @param factoryId 工厂 ID
     * @return 可用 WIP 列表 (按 processOrder ASC, createdAt ASC 排序)
     */
    List<SemiFinishedInventory> listAvailableWip(String factoryId);

    /**
     * C3: 工厂级半成品重量库存视图 — 全状态快照 (AVAILABLE/DEPLETED/RETURNED)。
     *
     * <p>仅返回重量字段 (producedQuantity / consumedQuantity / availableQuantity / unit)，
     * 不暴露成本字段 (accumulatedCost / unitCost)。流水账 SemiFinishedInventoryTransaction
     * 内部保留，不通过本端点暴露。
     *
     * @param factoryId 工厂 ID
     * @return WIP 重量视图 DTO 列表，含 productTypeName 回填 (批次级视图留 null)
     */
    List<WipRowDTO> listWipByFactory(String factoryId);

    /**
     * C3 + 防呆过滤 — 工厂级半成品重量库存视图 (可选 同族 + 阶段 过滤)。
     *
     * <p>逐道录入 SFI 投料下拉据此收窄可选半成品, 防低素养操作员误选 (07-01 客户会议防呆需求):
     * <ul>
     *   <li><b>同族</b> ({@code productTypeId} 非空): 把当前计划产品解析成<b>产品族</b>
     *       (以原料为主自动识别, 见 {@link ProductFamilyResolver}), 仅返回<b>同族</b>半成品。
     *       <b>不是</b>按 productTypeId 精确匹配 —— 07-01 客户澄清: 熟制前半成品在同产品族内通用
     *       (卤猪蹄/椒麻猪蹄/猪蹄冠 共用同一"猪蹄"半成品, 只在最后熟制道分化成成品), 按 productTypeId
     *       精确过滤会把通用"猪蹄"半成品对兄弟"猪蹄"成品计划隐藏 → 破坏客户想要的复用; 故按族过滤,
     *       猪蹄计划显示所有猪蹄族半成品 (跨兄弟成品), 但不显牛肉。
     *       <b>宁缺勿藏</b>: 仅排除"族已知且与计划族不同"的行; 族识别不出的候选一律放行 (不隐藏可能合法
     *       复用的项); 计划族本身识别不出 → 不按族过滤 (全放行, 只按阶段)。</li>
     *   <li><b>阶段</b> ({@code maxProcessOrder} 非空): 仅返回更早阶段 ({@code processOrder < maxProcessOrder})
     *       的半成品 —— 半成品只能前向投喂 (阶段产出), 不能把后道半成品回锅进前道。
     *       <b>严格排除</b> {@code processOrder} 为 null 的行 (无法确认阶段 → 防回锅)。此部分不变。</li>
     * </ul>
     * 两参数均为 null → 等价 {@link #listWipByFactory(String)} (全量, 向后兼容)。
     *
     * @param factoryId       工厂 ID
     * @param productTypeId   同族过滤: 计划产品类型 id → 内部解析成族键 (null/空白 = 不按族过滤)
     * @param maxProcessOrder 阶段过滤: 当前道工序序 (null = 不按阶段过滤; 返回 processOrder 严格小于此值的行)
     * @return 过滤后的 WIP 重量视图 DTO 列表
     */
    List<WipRowDTO> listWipByFactory(String factoryId, String productTypeId, Integer maxProcessOrder);

    // ==================== G3 小结 (interim-settle) — task-free SFI in/out ====================

    /**
     * G3 小结半成品入库 (SFI IN) — task-free，逐道录入路径专用。
     *
     * <p>与 {@link #postApprovedOutput} 不同: 不依赖 {@link WorkProcessTask} / WorkProcess 基础设施。
     * 直接 upsert {@link SemiFinishedInventory} 运行余额行 (锚 = {@code intermediateBatchNo}),
     * 多次小结对同一半成品 moving-average 累加 (不每次新建行)。
     *
     * <p>必须在调用方 {@code @Transactional} 内执行。{@code findForUpdate} 悲观行锁串行化累加。
     *
     * @param factoryId           工厂 ID (factory-scoped 🔒)
     * @param intermediateBatchNo SFI 运行余额行锚 (per-(plan,productType): {@code CLK-SEMI-{planId8}-{productTypeId8}})
     * @param productTypeId       产品类型 (冗余溯源, 占位行字段)
     * @param inQty               本次入库量 (≤0 → no-op)
     * @param unit                单位
     * @param inUnitCost          本次入库单位成本 (诚实 null: 无成本数据时传 null, 不假成 0)
     * @param materialBatchRefs   溯源 (可空)
     * @param processOrder        产出此 SFI 的工序序 (picker 阶段可见性锚)。锚跨多次小结可被不同道喂 →
     *                            取 MIN (最早道), 让新产 SFI 在尽可能靠后的复用道 (maxProcessOrder 大) 仍可见 (宁缺勿藏)。
     *                            null 允许 (旧路径/历史无序), 但 {@code listWipByFactory} picker 阶段过滤
     *                            (processOrder != null && < maxProcessOrder) 严格排除 null → 逐道录入必传。
     */
    void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
                         BigDecimal inQty, String unit, BigDecimal inUnitCost,
                         List<java.util.Map<String, Object>> materialBatchRefs, Integer processOrder);

    /**
     * G3 小结半成品出库 (SFI OUT) — task-free，{@link #postClerkOutput} 的逆向。
     *
     * <p>当某次小结的某道消耗了 <b>前序小结已入库的</b>半成品 (跨小结 in/out 闭环) 时调用:
     * {@code consumedQuantity += qty; availableQuantity = produced - consumed}。
     * 防止 SFI 半成品余额只增不减虚高。
     *
     * <p>守卫 not-below-zero: SFI 行不存在 → no-op (无库存可扣, 不报错不建负行);
     * 扣减量超过 produced → clamp 到 produced (available 不为负)。
     * available≤0 → 状态置 DEPLETED。必须在调用方 {@code @Transactional} 内执行。
     *
     * @param factoryId           工厂 ID (factory-scoped 🔒)
     * @param intermediateBatchNo SFI 运行余额行锚 (= 入库时同一锚)
     * @param qty                 本次出库量 (≤0 → no-op)
     */
    void consumeClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty);

    /**
     * G3 小结半成品出库 (SFI OUT) — <b>严格版</b>，用于 SFI 投料 (半成品直接产成品)。
     *
     * <p>与 {@link #consumeClerkSemi} 的容忍 (行缺失 no-op / 超扣 clamp) 不同, 本方法对
     * <b>常驻半成品库存</b> 的出库 <b>失败即抛 (禁止降级)</b>:
     * <ul>
     *   <li>SFI 行不存在 → 抛 {@code BusinessException(409, SFI_NOT_FOUND)} (拒绝 phantom 出库)。</li>
     *   <li>出库量超过可用 (qty &gt; available) → 抛 {@code BusinessException(409, SFI_INSUFFICIENT)}
     *       (拒绝超扣 → 防 phantom/不足库存生产成品)。</li>
     * </ul>
     * 成功时按 {@code consumedQuantity += qty; availableQuantity = produced - consumed} 扣减,
     * available≤0 → DEPLETED, 并返回 <b>实际出库量</b> (= {@code qty}, 因不足即抛, 永不少扣)。
     *
     * <p>容忍版 {@link #consumeClerkSemi} 仍保留给 <b>计划内前序小结半成品</b> 的 anchor 出库
     * (那里的 not-below-zero 是有意双保险, 与 SFI IN 净结余会计互为校验)。
     *
     * @param factoryId           工厂 ID (factory-scoped 🔒)
     * @param intermediateBatchNo 常驻 SFI 批次号 (= {@link SemiFinishedInventory#getIntermediateBatchNo()})
     * @param qty                 本次出库量 (≤0 → 返回 0, 不扣)
     * @return 实际出库量 (= qty; 不足/缺失即抛, 故永不少于请求量)
     */
    BigDecimal consumeClerkSemiStrict(String factoryId, String intermediateBatchNo, BigDecimal qty);

    /**
     * 撤销小结 (interim-settle reversal) — {@link #postClerkOutput} 的逆 (SFI IN un-stock)。
     *
     * <p>把某次小结入库的净结余从运行余额行冲销:
     * {@code producedQuantity -= qty; accumulatedCost -= totalCost; unitCost 重算; availableQuantity 重算}。
     *
     * <p>🔴 <b>下游已消耗守卫 (禁止降级, 不产 phantom 负库存)</b>: 若该半成品在入库后已被下游道/其它计划
     * 消耗 (available_after = produced − qty − consumed + adjustment &lt; 0), 冲销会致负库存 →
     * <b>抛 {@code BusinessException(409, SFI_DOWNSTREAM_CONSUMED)}</b> (整事务回滚)。绝不静默 clamp/部分冲销。
     *
     * <p><b>成本冲销 (诚实)</b>: {@code totalCost} = 入库时加进 accumulatedCost 的确切金额 (小结记录在
     * {@code summary.reversalDetail}), null = 当时未知成本 (accumulatedCost 未加, 此处不减)。冲销后
     * {@code unitCost = accumulatedCost / producedQuantity} (scale-4 HALF_UP; produced≤0 或 accumulatedCost null → null)。
     * 移动均价对单一 clean IN 精确可逆; 若同锚残留其它 null-成本 IN (poison) 则重算值可能非精确 null (罕见 edge, 见 impl 注)。
     *
     * @param factoryId           工厂 ID (factory-scoped 🔒)
     * @param intermediateBatchNo SFI 运行余额行锚 (= 入库时同一锚)
     * @param qty                 本次冲销量 (= 入库时的净结余; ≤0 → no-op)
     * @param totalCost           入库时加进 accumulatedCost 的确切金额 (可空 = 当时成本未知, 不减)
     * @param operatorId          操作人 (REVERSE 流水 audit, 可空)
     * @throws com.cretas.aims.exception.BusinessException 409 SFI_NOT_FOUND (行缺失) / SFI_DOWNSTREAM_CONSUMED (下游已消耗)
     */
    void reverseClerkOutput(String factoryId, String intermediateBatchNo, BigDecimal qty,
                            BigDecimal totalCost, Long operatorId);

    /**
     * 撤销小结 — {@link #consumeClerkSemi} / {@link #consumeClerkSemiStrict} 的逆 (SFI OUT restore, 还回消耗)。
     *
     * <p>把某次小结出库的量还回运行余额行: {@code consumedQuantity -= qty; availableQuantity += qty}。
     * available&gt;0 → 从 DEPLETED 恢复 AVAILABLE (RETURNED 终态不动)。写 REVERSE/REVERSAL 反向流水。
     *
     * <p>还量是<b>增加</b>库存 (无 phantom 风险), 故对<b>行缺失容忍</b> (log warn + no-op —— 被撤销的出库其源库存行
     * 已不存在则无处可还, 跳过不产负行); 但 {@code consumedQuantity} 不得反冲到负 (超过历史消耗量 → 抛
     * {@code SFI_REVERSE_OVER_CONSUMED}, 诚实报错)。必须在调用方 {@code @Transactional} 内。
     *
     * @param factoryId           工厂 ID (factory-scoped 🔒)
     * @param intermediateBatchNo SFI 运行余额行锚 / 常驻 SFI 批次号 (= 出库时同一键)
     * @param qty                 本次还回量 (≤0 → no-op)
     * @param operatorId          操作人 (REVERSE 流水 audit, 可空)
     * @throws com.cretas.aims.exception.BusinessException 409 SFI_REVERSE_OVER_CONSUMED (还量超过已消耗)
     */
    void restoreClerkSemi(String factoryId, String intermediateBatchNo, BigDecimal qty, Long operatorId);

    /**
     * 读取常驻半成品库存(SFI)的移动均价 {@code unitCost} —— 成本传导基准 (SFI 投料下游道算本道产出成本时用)。
     *
     * <p>🔴 诚实 null: 行缺失 或 {@code unitCost} 为 null (旧库存/未接通成本的半成品) → 返 <b>null</b>,
     * 绝不伪造 ¥0。调用方 (小结成本计算) 见 null 即把整道产出成本判为未知 (诚实 null 传播), 不当零成本摊薄。
     *
     * <p>只读 (不加锁): OUT 扣减 ({@link #consumeClerkSemiStrict}) 不改 unitCost, 故本方法与 OUT 同事务读到的
     * 值稳定 (moving-average unitCost 仅 IN 时变)。
     *
     * @return 该半成品的移动均价 unitCost; 行缺失或成本未知 → null
     */
    BigDecimal getSemiUnitCost(String factoryId, String intermediateBatchNo);
}
