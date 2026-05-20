package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 决策类型元数据 — admin UI 选择 decisionType 时的展示与默认配置.
 *
 * <p>Sprint 6 Track W3-B (2026-05-19): Sprint 5 PR #55 H 把 DecisionType enum 从 14
 * 扩到 32 + CUSTOM, 但 admin UI 仍 hardcode 12 个 dropdown 选项, 业务 service
 * 也只有 LeaveRequest/OvertimeRequest/Expense/Purchase/Sales/Quality/Force-insert
 * 7 个真正接 ApprovalWorkflowExecutor. 本 metadata 把所有 enum 暴露给 UI +
 * 提供"该 decisionType 默认审批角色 / 适合的 moduleCode / 是否启用"标准化信息.
 *
 * <p><b>用法</b>:
 * <ul>
 *   <li>Admin UI: GET /api/mobile/{factoryId}/approval-workflows/decision-types-meta
 *       拿全部 32 个元数据, dropdown 直接渲染 chineseName + 英文 enum.</li>
 *   <li>业务 service: 创建审批节点时取 defaultApproverRoles 作为预设, 用户可改.</li>
 *   <li>WorkflowEngineService: moduleCode → DecisionType registry 复用本元数据
 *       自动扩展 (Sprint 5 前需手动维护两份).</li>
 * </ul>
 *
 * @since 2026-05-19
 */
@Getter
@Builder
@AllArgsConstructor
public class DecisionTypeMetadata {

    /** 枚举值 (英文标识). */
    private final DecisionType decisionType;

    /** 中文名 (UI 显示主标题). */
    private final String chineseName;

    /** 中文描述 (UI tooltip / 帮助文本). */
    private final String description;

    /** 业务分类 (UI 分组). */
    private final Category category;

    /**
     * 默认审批角色 (UI 创建审批节点时预设, 用户可改).
     * 注: 这只是建议默认值, 实际审批人在 ApprovalWorkflowNode.config.approverRoles 配置.
     */
    private final List<String> defaultApproverRoles;

    /**
     * 业务模块代码 (WorkflowEngineService.startWorkflow 的 moduleCode 参数).
     * null 表示该 DecisionType 不通过 moduleCode 启动 (e.g. CUSTOM).
     */
    private final String moduleCode;

    /**
     * Sprint 6 W3-B 阶段是否已 ship backend trigger 接入 (true=已有 service 调用
     * ApprovalWorkflowExecutor.start; false=仅 enum + UI dropdown, service 接入留后续).
     */
    private final boolean wired;

    /**
     * 业务分类 — 对应 ApprovalChainConfig.DecisionType javadoc 的 7 组.
     */
    public enum Category {
        /** 生产 / 工序 */
        PRODUCTION("生产"),
        /** 质检 / 物料 */
        QUALITY_MATERIAL("质量物料"),
        /** 采购 / 供应商 */
        PURCHASE_SUPPLIER("采购"),
        /** 销售 / 客户 */
        SALES_CUSTOMER("销售"),
        /** 财务 / 凭证 */
        FINANCE_VOUCHER("财务"),
        /** 人事 / 工资 */
        HR_WAGE("人事"),
        /** 仓储 / 调拨 */
        WAREHOUSE_TRANSFER("仓储"),
        /** 其他 (CUSTOM) */
        OTHER("其他");

        private final String chineseName;

        Category(String chineseName) {
            this.chineseName = chineseName;
        }

        public String getChineseName() {
            return chineseName;
        }
    }
}
