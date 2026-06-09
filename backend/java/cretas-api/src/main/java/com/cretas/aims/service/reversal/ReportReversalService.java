package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ReportReversalLog;

import java.util.List;

/**
 * SP2 整单撤回 (Whole-Order Reversal) 服务接口。
 *
 * <p><b>核心保证</b>:
 * <ul>
 *   <li>三层 fail-closed 守卫 (G1 下游消费 / G2 成品出货 / G3 幂等) 在 @Transactional 外执行</li>
 *   <li>撤回执行 ({@link #executeReversal}) 是单一原子事务: 失败即全部回滚</li>
 *   <li>流水账不可修改: 取消写 REVERSE 行, 不修改原 IN 行</li>
 *   <li>移动均价回放: 回放剩余 IN 行重算 unitCost; 无剩余则置 null</li>
 * </ul>
 */
public interface ReportReversalService {

    /**
     * 提交撤回申请。执行三层守卫后创建 ReportReversalLog。
     *
     * <p>有报工数据 → 状态=PENDING (需审批); 无报工数据 → 直接执行并返回 DONE。
     *
     * @param factoryId   工厂 ID
     * @param batchId     生产批次 ID
     * @param submittedBy 提交人 ID
     * @param reason      撤回原因
     * @return 创建的撤回申请记录
     */
    ReportReversalLog submitReversal(String factoryId, Long batchId, Long submittedBy, String reason);

    /**
     * 执行撤回 (单一 @Transactional 原子操作)。
     *
     * <p>将该批次所有 ProductionReport 软删除, 创建对应 REVERSE SIT 行, 并回放移动均价。
     * 须由审批通过或 DONE 直通 (无报工数据时) 路径触发。
     *
     * @param logId     ReportReversalLog.id
     * @param factoryId 工厂 ID
     */
    void executeReversal(Long logId, String factoryId);

    /**
     * 审批通过。仅 PENDING 状态可批准, 批准后自动执行撤回。
     *
     * @param logId      ReportReversalLog.id
     * @param approvedBy 审批人 ID
     */
    void approveReversal(Long logId, Long approvedBy);

    /**
     * 审批拒绝。仅 PENDING 状态可拒绝。
     *
     * @param logId      ReportReversalLog.id
     * @param approvedBy 审批人 ID
     * @param reason     拒绝原因
     */
    void rejectReversal(Long logId, Long approvedBy, String reason);

    /**
     * 列出工厂的撤回申请, 可按状态过滤。
     *
     * @param factoryId 工厂 ID
     * @param status    状态过滤 (null = 全部)
     * @return 撤回申请列表 (按 createdAt DESC)
     */
    List<ReportReversalLog> listReversals(String factoryId, String status);
}
