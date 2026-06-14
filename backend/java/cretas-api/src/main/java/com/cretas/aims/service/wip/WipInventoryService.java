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
}
