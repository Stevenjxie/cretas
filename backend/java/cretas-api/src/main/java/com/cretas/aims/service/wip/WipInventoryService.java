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
     */
    void postClerkOutput(String factoryId, String intermediateBatchNo, String productTypeId,
                         BigDecimal inQty, String unit, BigDecimal inUnitCost,
                         List<java.util.Map<String, Object>> materialBatchRefs);

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
