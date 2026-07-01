package com.cretas.aims.service.yield;

import com.cretas.aims.dto.production.InterimSettleReversalRequestDTO;
import com.cretas.aims.entity.InterimSettleReversalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 撤销小结治理 (interim-settle reversal governance): 申请 → 审批 → 执行。
 *
 * <p>撤销不再免审即时: {@link #requestReverse} 创建<b>撤销申请</b> (PENDING_APPROVAL, <b>零库存副作用</b>),
 * {@link #approve} 由 {@code STOCKTAKE_APPROVAL_ROLES} 审批并<b>内联执行</b>真正的逆转
 * ({@link InterimSettleReversalService#reverseInterimSettle}, 悲观锁+下游守卫+原子 不变),
 * {@link #reject} 关闭申请零副作用。
 *
 * <p><b>1天时间窗</b>: 小结 postedAt 起 24h 内才可撤销; 申请端 + 审批端两端都校验 (postedAt 是锚, 非申请时间)。
 *
 * <p>🔒 红线: 审批权限 + 真实库存逆转 + 多租户。
 */
public interface InterimSettleReversalRequestService {

    /**
     * 撤销申请 (request): 创建 PENDING_APPROVAL 申请, 零库存副作用 (行仍 已小结, 库存不动)。
     *
     * @param factoryId  工厂 ID 🔒
     * @param planId     计划 ID
     * @param sessionSeq 目标小结序号 (null → 最近一次)
     * @param reason     撤销原因 (必填)
     * @param userId     申请人
     * @throws com.cretas.aims.exception.BusinessException
     *   404 计划不存在 / 400 非 SAFETY_STOCK / 400 reason 为空 / 409 小结不存在或已撤销 /
     *   409 INTERIM_REVERSE_WINDOW_EXPIRED (超 24h) / 409 已有待审批的撤销申请
     */
    InterimSettleReversalRequestDTO requestReverse(String factoryId, String planId, Integer sessionSeq,
                                                   String reason, Long userId);

    /**
     * 审批通过 (approve) → 内联执行逆转。需 STOCKTAKE_APPROVAL_ROLES。执行侧下游守卫仍会 loud-fail (审批不绕过)。
     *
     * @throws com.cretas.aims.exception.BusinessException 403 角色不足 / 404 申请不存在 / 409 状态非 PENDING /
     *   409 INTERIM_REVERSE_WINDOW_EXPIRED (审批时超 24h) / 执行侧 409 下游已消耗 (SFI/FG_DOWNSTREAM_CONSUMED)
     */
    InterimSettleReversalRequestDTO approve(String requestId, String factoryId, Long approverId, String requestRole);

    /** 驳回 (reject): 关闭申请, 零副作用。需 STOCKTAKE_APPROVAL_ROLES。 */
    InterimSettleReversalRequestDTO reject(String requestId, String factoryId, String reason,
                                           Long approverId, String requestRole);

    /** 审批中心 / 审计: 工厂级列表 (可选 status + planId 过滤)。 */
    Page<InterimSettleReversalRequestDTO> list(String factoryId, InterimSettleReversalRequest.Status status,
                                               String planId, Pageable pageable);
}
