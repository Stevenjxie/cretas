package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.ApprovalWorkflowService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 「发起人能否审批自己的单」的唯一判定处。
 *
 * <p>此前这条规则在销售 / 采购 / 调拨三处各自承载, 且行为并不一致: 采购单有
 * {@code isExplicitCurrentNodeApprover} 私有例外, 销售单与调拨单则无条件禁止。
 * 因为那个方法是 private 且全仓仅此一份, 另外两处想复用也复用不了 —— 于是同一条
 * 规则长出了两种行为。本类把它提出来, 三处统一委托。
 *
 * <p><b>放行条件(满足其一)</b>:
 * <ol>
 *   <li>OA 节点在 {@code approverUserIds} 里<b>显式点名</b>了这个人 —— 配置者的明确意图;</li>
 *   <li>这个人是 {@code factory_super_admin} —— 单人工厂里超管必须能推进自己的单,
 *       否则单据结构性卡死(六膳门实测: 唯一的 super_admin 就是发起人)。</li>
 * </ol>
 *
 * <p><b>⚠️ 刻意不走本类的三处</b>(它们是独立业务语义, 不是本规则的实例):
 * <ul>
 *   <li>{@code FactoryStocktakeServiceImpl} 两处 {@code STOCKTAKE_SELF_APPROVAL_FORBIDDEN} ——
 *       用 409 而非 403, <b>仅当存在盘盈/盘亏时</b>才拦, 且判据含录入人与提交人;</li>
 *   <li>{@code ReportReversalServiceImpl} 的 {@code SELF_APPROVAL_FORBIDDEN} —— 撤回冲销, 属红线。</li>
 * </ul>
 * 若将来要把它们并进来, 先改 spec
 * {@code docs/superpowers/specs/2026-08-01-oa-self-approval-and-budget-design.md} §1,
 * 并同步 {@code SelfApprovalCarrierContractTest}。
 */
@Component
public class SelfApprovalPolicy {

    private static final String FACTORY_SUPER_ADMIN = "factory_super_admin";
    private static final String APPROVAL_NODE_TYPE = "approval";
    private static final String APPROVER_USER_IDS = "approverUserIds";

    @Nullable
    private final ApprovalWorkflowService approvalWorkflowService;

    public SelfApprovalPolicy(@Nullable ApprovalWorkflowService approvalWorkflowService) {
        this.approvalWorkflowService = approvalWorkflowService;
    }

    /**
     * @return {@code true} 表示「actor 虽然就是发起人, 但允许他审批」。
     *         调用方仍需自行判断 actor 是否等于发起人 —— 本方法只回答「例外成不成立」。
     */
    public boolean allowsSelfApproval(String factoryId,
                                      ApprovalWorkflowInstance instance,
                                      Long actorId,
                                      String actorRole) {
        if (FACTORY_SUPER_ADMIN.equals(actorRole)) {
            return true;
        }
        return isExplicitCurrentNodeApprover(factoryId, instance, actorId);
    }

    /**
     * A workflow may intentionally name the initiator as the approver for a single-user factory.
     * Role membership alone is not enough to bypass separation of duties: the active node must
     * explicitly contain the actor in {@code approverUserIds}.
     */
    private boolean isExplicitCurrentNodeApprover(String factoryId,
                                                  ApprovalWorkflowInstance instance,
                                                  Long actorId) {
        if (approvalWorkflowService == null
                || actorId == null
                || instance == null
                || instance.getCurrentNodeIds() == null
                || instance.getCurrentNodeIds().isEmpty()) {
            return false;
        }
        ApprovalWorkflow workflow = approvalWorkflowService
                .getById(factoryId, instance.getWorkflowId())
                .orElse(null);
        if (workflow == null) {
            return false;
        }
        String currentNodeId = instance.getCurrentNodeIds().get(0);
        ApprovalWorkflowNode currentNode = approvalWorkflowService
                .deserializeNodes(workflow.getNodesJson()).stream()
                .filter(node -> currentNodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
        if (currentNode == null
                || !APPROVAL_NODE_TYPE.equalsIgnoreCase(currentNode.getType())
                || currentNode.getConfig() == null) {
            return false;
        }
        Object configuredApprovers = currentNode.getConfig().get(APPROVER_USER_IDS);
        if (!(configuredApprovers instanceof Iterable<?> approvers)) {
            return false;
        }
        for (Object configuredApprover : approvers) {
            if (configuredApprover instanceof Number number
                    && actorId.equals(number.longValue())) {
                return true;
            }
            if (configuredApprover != null
                    && actorId.toString().equals(String.valueOf(configuredApprover).trim())) {
                return true;
            }
        }
        return false;
    }
}
