package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.ByproductCreditDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * 工厂盘点任务服务接口 (SP7 §5.1).
 *
 * <p>月底约束: 每月29日后才允许发起盘点 (可配置，默认 >=29)。
 * <p>状态机: INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED / REJECTED
 */
public interface FactoryStocktakeService {

    /**
     * 发起盘点任务 (INITIATED).
     * 月底约束: dayOfMonth < 29 时抛 BusinessException 409。
     *
     * @param factoryId   工厂 ID
     * @param req         请求 DTO（warehouseId + periodMonth）
     * @param userId      发起人 ID
     * @return 创建的盘点任务 DTO
     */
    StocktakeDTO initiate(String factoryId, CreateStocktakeRequest req, Long userId);

    /**
     * 发起盘点任务，带批量导入模式 (SP7 + 盘点批量导入)。
     *
     * <p>importMode: null=逐项 UI 盘点 / NORMAL=批量常规盘点 / OPENING=批量期初建账。
     * <p>Decision 4: OPENING 期初建账 <b>跳过月底约束</b>（可任意日发起）；NORMAL/null 仍受约束。
     *
     * @param importMode 导入模式（决定 apply 过账科目 + 是否跳过月底约束）
     */
    StocktakeDTO initiate(String factoryId, CreateStocktakeRequest req, Long userId,
                          FactoryStocktake.ImportMode importMode);

    /**
     * 查询"发起盘点"的月底约束展示态 (fool-proof-design Rule 1: 边界必须在用户填表前展示,
     * 不能等填完表单点提交才报错). 只读, 不做任何校验/side-effect —— {@link #initiate}
     * 仍是唯一真正 enforce 约束的地方, 本方法只是把同一个 threshold 换算成前端可直接渲染的
     * 展示字段 (是否今天可发起 / 下次可发起日期), 不改变约束本身。
     *
     * <p>只覆盖 NORMAL/逐项盘点路径 (OPENING 期初建账不受月底约束, Decision 4)。
     *
     * @return {@code monthEndThreshold}(int) / {@code canInitiateToday}(boolean) /
     *         {@code today}(LocalDate) / {@code nextAllowedDate}(LocalDate)
     */
    Map<String, Object> getInitiateConstraint();

    /**
     * 批量更新明细行的实盘数量。
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID（多租户校验）
     * @param items       明细更新列表
     * @param userId      操作人
     */
    void updateItems(String stocktakeId, String factoryId, List<StocktakeItemUpdateDTO> items, Long userId);

    /**
     * 提交审批 (COUNTING / INITIATED → PENDING_APPROVAL).
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @param userId      提交人 ID
     */
    void submit(String stocktakeId, String factoryId, Long userId);

    /**
     * 财务审批通过 (PENDING_APPROVAL → APPROVED).
     * 角色检查: FINANCE，通过 requestRole 参数传入（非 SecurityContext，C1孪生坑教训）。
     *
     * @param stocktakeId  盘点任务 ID
     * @param factoryId    工厂 ID
     * @param approverId   审批人 ID
     * @param requestRole  从 request.getAttribute("role") 获取的角色
     */
    void approve(String stocktakeId, String factoryId, Long approverId, String requestRole);

    void approve(String stocktakeId, String factoryId, Long approverId, String requestRole, Long expectedVersion);

    /**
     * 驳回 (PENDING_APPROVAL → REJECTED).
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @param reason      驳回原因
     * @param userId      审批人 ID
     * @param requestRole 从 request.getAttribute("role") 获取的角色
     */
    void reject(String stocktakeId, String factoryId, String reason, Long userId, String requestRole);

    /**
     * 生效: 写差异到 MaterialBatch + 生成 MaterialBatchAdjustment (APPROVED → APPLIED).
     * 幂等: 已 APPLIED → 抛 BusinessException 409。
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @param userId      操作人 ID
     */
    void apply(String stocktakeId, String factoryId, Long userId);

    void apply(String stocktakeId, String factoryId, Long userId, Long expectedVersion);

    /**
     * 差异预览（生效前展示变化，只读）。
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @return 差异预览 DTO
     */
    StocktakeDiffPreviewDTO previewDiff(String stocktakeId, String factoryId);

    /**
     * 分页查询盘点任务列表。
     *
     * @param factoryId 工厂 ID
     * @param status    状态过滤（可选）
     * @param pageable  分页
     * @return 分页结果
     */
    Page<StocktakeDTO> list(String factoryId, FactoryStocktake.Status status, Pageable pageable);

    /**
     * 查询单个盘点任务详情（含明细行）。
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @return 盘点任务 DTO
     */
    StocktakeDTO getDetail(String stocktakeId, String factoryId);

    /**
     * SP12 §5.2: 提交审批并启动 INVENTORY_ADJUSTMENT workflow。
     * 替代旧的 submit()，状态 COUNTING/INITIATED/REJECTED → PENDING_APPROVAL + workflowInstanceId 设置。
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @param userId      提交人 ID
     * @return workflowInstanceId (供前端跳转审批中心)
     */
    String submitForApproval(String stocktakeId, String factoryId, Long userId);

    FactoryStocktake applyWorkflowAction(String factoryId, String stocktakeId, String instanceId,
            Long actorId, String actorRole, HistoryAction action, String notes);

    /**
     * SP12 §5.2: 仅供 workflow callback 调用 — 审批通过后执行盘点调账 (APPROVED → APPLIED)。
     * 不对外暴露 REST 端点，只能被 WorkflowEngineService onApproved callback 触发。
     * 红线 §7.R1: 校验 workflowInstanceId 不为 null 且实例状态 APPROVED，否则 403。
     *
     * @param stocktakeId 盘点任务 ID
     */
    void executeAdjustment(String stocktakeId);

    /**
     * 盘点单上的副产批次列表（「副产价值确认」区）。
     *
     * <p>只返回 {@code byproduct_source_report_id IS NOT NULL} 的批次 —— 那条链由 Task 4
     * 在报工时写入。抵扣额由 {@code ByproductCreditService} 算好返回，前端只做格式化。</p>
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID（多租户校验）
     * @return 副产批次列表；没有副产时返回空列表，<b>不是错误</b>
     */
    List<ByproductCreditDTO> listByproductCredits(String stocktakeId, String factoryId);

    /**
     * 确认一条副产批次的单价（盘点时人工拍板）。
     *
     * <p>🔴 单价<b>允许为 0</b> —— 那是「确认这批不值钱」这个真实结论，与「没确认」(null)
     * 是两回事。但<b>不允许为负</b>，负单价会变成凭空增加主产品成本。</p>
     *
     * <p>🔴 只能确认<b>本盘点单里的副产批次</b>：批次要属于该工厂、要是副产批次、
     * 且要出现在这张盘点单的明细里。三条任一不满足都拒绝（fail-closed）。</p>
     *
     * @param stocktakeId 盘点任务 ID
     * @param factoryId   工厂 ID
     * @param batchId     副产批次 ID
     * @param unitPrice   确认单价（可为 0，不可为负，不可为 null）
     * @param userId      确认人
     * @return 确认后的该行（含重算过的抵扣额）
     */
    ByproductCreditDTO confirmByproductPrice(String stocktakeId, String factoryId,
            String batchId, java.math.BigDecimal unitPrice, Long userId);
}
