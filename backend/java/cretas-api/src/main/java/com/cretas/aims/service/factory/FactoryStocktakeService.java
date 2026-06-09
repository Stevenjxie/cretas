package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

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
}
