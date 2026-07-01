package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.CreateSemiFinishedStocktakeRequest;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeDTO;
import com.cretas.aims.dto.factory.SemiFinishedStocktakeItemUpdateDTO;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 半成品盘点任务服务接口 (镜像 SP7 {@link FactoryStocktakeService})。
 *
 * <p>针对 {@link com.cretas.aims.entity.SemiFinishedInventory} 做周期盘点校准。
 * 状态机: INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED / REJECTED。
 * 审批复用 SP12 INVENTORY_ADJUSTMENT 工作流 (角色: finance_manager / factory_super_admin / platform_admin)。
 */
public interface SemiFinishedStocktakeService {

    /**
     * 发起半成品盘点任务 (INITIATED) — 快照工厂全部 AVAILABLE 半成品行。
     * 月底约束: dayOfMonth < threshold 时抛 409 (可配置, 默认 >=29)。
     */
    SemiFinishedStocktakeDTO initiate(String factoryId, CreateSemiFinishedStocktakeRequest req, Long userId);

    /** 批量更新明细行的实盘数量 (计算差异 SURPLUS/SHORTAGE/MATCH), 状态 → COUNTING。 */
    void updateItems(String stocktakeId, String factoryId, List<SemiFinishedStocktakeItemUpdateDTO> items, Long userId);

    /** 提交审批 (COUNTING / INITIATED / REJECTED → PENDING_APPROVAL)。 */
    void submit(String stocktakeId, String factoryId, Long userId);

    /**
     * 审批通过 (PENDING_APPROVAL → APPROVED)。
     * 角色检查通过 requestRole 参数传入 (非 SecurityContext, C1 孪生坑)。
     */
    void approve(String stocktakeId, String factoryId, Long approverId, String requestRole);

    /** 驳回 (PENDING_APPROVAL → REJECTED)。 */
    void reject(String stocktakeId, String factoryId, String reason, Long userId, String requestRole);

    /**
     * 生效: 写差异到 SemiFinishedInventory + 生成 ADJUST/STOCKTAKE 流水 (APPROVED → APPLIED)。
     * 幂等: 已 APPLIED → 抛 409。availableQuantity 校准为 actualQty (盘点真值)。
     */
    void apply(String stocktakeId, String factoryId, Long userId);

    /** 差异预览 (生效前展示变化, 只读)。 */
    SemiFinishedStocktakeDiffPreviewDTO previewDiff(String stocktakeId, String factoryId);

    /** 分页查询半成品盘点任务列表。 */
    Page<SemiFinishedStocktakeDTO> list(String factoryId, SemiFinishedStocktake.Status status, Pageable pageable);

    /** 查询单个半成品盘点任务详情 (含明细行)。 */
    SemiFinishedStocktakeDTO getDetail(String stocktakeId, String factoryId);

    /**
     * SP12 复用: 提交审批并启动 INVENTORY_ADJUSTMENT workflow。
     * 状态 COUNTING/INITIATED/REJECTED → PENDING_APPROVAL + workflowInstanceId 设置。
     *
     * @return workflowInstanceId (供前端跳转审批中心)
     */
    String submitForApproval(String stocktakeId, String factoryId, Long userId);

    /**
     * SP12 复用: 仅供 workflow callback 调用 — 审批通过后执行半成品盘点调账 (APPROVED → APPLIED)。
     * 红线: 校验 workflowInstanceId 不为 null 且状态 APPROVED, 否则 403。
     */
    void executeAdjustment(String stocktakeId);
}
