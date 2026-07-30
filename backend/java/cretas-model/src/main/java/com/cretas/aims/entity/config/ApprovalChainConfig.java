package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.util.List;

/**
 * 审批链路配置实体
 *
 * 用于配置化审批流程：
 * - 什么操作需要审批
 * - 谁有权审批
 * - 多级审批规则
 * - 升级规则
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Entity
@Table(name = "approval_chain_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"factory_id", "decision_type", "name"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalChainConfig extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 决策类型
     * 如：FORCE_INSERT, QUALITY_RELEASE, SUPPLIER_APPROVAL, BATCH_STATUS_CHANGE
     */
    @Column(name = "decision_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private DecisionType decisionType;

    /**
     * 配置名称
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * 配置描述
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 触发条件 (JSON格式的规则表达式)
     * 例如: {"impactLevel": "HIGH", "delayMinutes": ">60"}
     */
    @Column(name = "trigger_condition", columnDefinition = "TEXT")
    private String triggerCondition;

    /**
     * 审批级别 (1=一级审批, 2=二级审批, ...)
     */
    @Column(name = "approval_level", nullable = false)
    private Integer approvalLevel;

    /**
     * 必需审批人数 (如2表示需要至少2人审批)
     */
    @Column(name = "required_approvers")
    @Builder.Default
    private Integer requiredApprovers = 1;

    /**
     * 可审批角色列表 (JSON数组)
     * 例如: ["factory_super_admin", "department_admin"]
     */
    @Column(name = "approver_roles", columnDefinition = "TEXT")
    private String approverRoles;

    /**
     * 可审批用户ID列表 (JSON数组，可选)
     */
    @Column(name = "approver_user_ids", columnDefinition = "TEXT")
    private String approverUserIds;

    /**
     * 超时时间 (分钟)
     */
    @Column(name = "timeout_minutes")
    private Integer timeoutMinutes;

    /**
     * 超时后升级到的配置ID
     */
    @Column(name = "escalation_config_id", length = 36)
    private String escalationConfigId;

    /**
     * 自动审批条件 (JSON格式)
     * 满足条件时可自动审批通过
     */
    @Column(name = "auto_approve_condition", columnDefinition = "TEXT")
    private String autoApproveCondition;

    /**
     * 自动拒绝条件 (JSON格式)
     */
    @Column(name = "auto_reject_condition", columnDefinition = "TEXT")
    private String autoRejectCondition;

    /**
     * 优先级 (数值越大优先级越高)
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 配置版本
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 决策类型枚举.
     *
     * <p><b>Sprint 5 Track-H H-3 扩展 (2026-05-19)</b>: 从 Phase 1 Canvas-Workflow 的
     * 14 + CUSTOM 扩到 28 + CUSTOM. 对齐 R-HJ Round 13 §10 finding: HJ 115+ workflow
     * 定义中最常用的 ~25 类. 不全 ship 115 实例 (单独 ApprovalChainConfig 行需要 admin UI
     * 填好后才生效), 仅扩 base enum 让 Track-A personal view + Canvas Phase 2 admin UI
     * 可以下拉到这些类别, 业务 service 接入 in Sprint 6+.
     *
     * <p>分组:
     * <ul>
     *   <li>生产 / 工序: FORCE_INSERT, PRODUCTION_PLAN_CHANGE, EQUIPMENT_STATUS_CHANGE,
     *       BOM_VERSION_APPROVAL, ECN_APPROVAL, WORK_ORDER_APPROVAL</li>
     *   <li>质检 / 物料: QUALITY_RELEASE, QUALITY_EXCEPTION, BATCH_STATUS_CHANGE,
     *       MATERIAL_DISPOSAL, MATERIAL_REQUISITION_APPROVAL</li>
     *   <li>采购 / 供应商: SUPPLIER_APPROVAL, SUPPLIER_STATUS_CHANGE,
     *       PURCHASE_ORDER_APPROVAL, PURCHASE_PAYMENT_APPROVAL,
     *       PURCHASE_RETURN_APPROVAL</li>
     *   <li>销售 / 客户: SALES_ORDER_APPROVAL, SALES_RETURN_APPROVAL,
     *       SALES_DISCOUNT_APPROVAL, CUSTOMER_CREDIT_APPROVAL,
     *       INVOICE_ISSUANCE_APPROVAL</li>
     *   <li>财务 / 凭证: VOUCHER_APPROVAL, EXPENSE_APPROVAL, BUDGET_APPROVAL,
     *       PAYMENT_APPROVAL, TAX_FILING_APPROVAL</li>
     *   <li>人事 / 工资: LEAVE_APPROVAL, OVERTIME_APPROVAL, WAGE_RECORD_APPROVAL,
     *       HIRE_APPROVAL</li>
     *   <li>仓储 / 调拨: INVENTORY_TRANSFER_APPROVAL, INVENTORY_ADJUSTMENT_APPROVAL,
     *       WASTAGE_APPROVAL</li>
     * </ul>
     */
    public enum DecisionType {
        // ============================================
        // 生产 / 工序 类 (production / process)
        // ============================================

        /**
         * 强制插单
         */
        FORCE_INSERT,

        /**
         * 生产计划变更
         */
        PRODUCTION_PLAN_CHANGE,

        /**
         * 设备状态变更
         */
        EQUIPMENT_STATUS_CHANGE,

        /**
         * BOM 版本审批 (Sprint 5 H-2, M-BOM-VER-1)
         */
        BOM_VERSION_APPROVAL,

        /**
         * ECN (工程变更通知) 审批
         */
        ECN_APPROVAL,

        /**
         * 工单审批 (任务单 / 工艺单 / 加工单)
         */
        WORK_ORDER_APPROVAL,

        /**
         * 生产撤回审批 (SP12 T2 新增) —— 生产计划已完成后申请撤回/重置.
         * 六扇门红线: 撤回操作必须主管审批, 不允许操作员自主执行.
         * 接入: ProductionPlanServiceImpl.requestCancelWithApproval
         */
        PRODUCTION_REVERSAL_APPROVAL,

        // ============================================
        // 质检 / 物料 类 (quality / material)
        // ============================================

        /**
         * 质检放行
         */
        QUALITY_RELEASE,

        /**
         * 质检特批
         */
        QUALITY_EXCEPTION,

        /**
         * 批次状态变更
         */
        BATCH_STATUS_CHANGE,

        /**
         * 原材料处置 (报废/冻结)
         */
        MATERIAL_DISPOSAL,

        /**
         * 请购单审批 (Sprint 5 D, P-REQUISITION-1, 在谈食品厂 P1)
         */
        MATERIAL_REQUISITION_APPROVAL,

        // ============================================
        // 采购 / 供应商 类 (purchase / supplier)
        // ============================================

        /**
         * 供应商准入
         */
        SUPPLIER_APPROVAL,

        /**
         * 供应商状态变更 (暂停/恢复)
         */
        SUPPLIER_STATUS_CHANGE,

        /**
         * 采购订单审批 (Phase 1 Canvas-Workflow, 2026-05-18) —
         * 替代 PR #859 purchase_order_approval_rules 临时方案.
         * PurchaseServiceImpl.submitOrder 通过 ApprovalWorkflowExecutor
         * 按此 DecisionType 查 active workflow 决定路由.
         */
        PURCHASE_ORDER_APPROVAL,

        /**
         * 采购付款申请审批
         */
        PURCHASE_PAYMENT_APPROVAL,

        /**
         * 采购退货审批
         */
        PURCHASE_RETURN_APPROVAL,

        // ============================================
        // 销售 / 客户 类 (sales / customer)
        // ============================================

        /**
         * 销售订单审批 (Phase 1 Canvas-Workflow, 2026-05-18) —
         * SalesOrderService 预留, 实际接入在 Phase B-2.
         */
        SALES_ORDER_APPROVAL,

        /**
         * 销售退货审批
         */
        SALES_RETURN_APPROVAL,

        /**
         * 销售折扣 / 让利 审批 (超过权限的价格调整)
         */
        SALES_DISCOUNT_APPROVAL,

        /**
         * 客户额度 (信用) 审批 (新客户开户 / 提额 / 临时额度)
         */
        CUSTOMER_CREDIT_APPROVAL,

        /**
         * 发票开具审批 (普票 / 专票 / 数电票, Sprint 5 B F-TAX-DIRECT-1 配套)
         */
        INVOICE_ISSUANCE_APPROVAL,

        // ============================================
        // 财务 / 凭证 类 (finance / voucher)
        // ============================================

        /**
         * 凭证审核 (会计 → 出纳 → 审计 多级)
         */
        VOUCHER_APPROVAL,

        /**
         * 报销审批 (Sprint 4 W2 H-EXP-1)
         */
        EXPENSE_APPROVAL,

        /**
         * 预算审批 (年度 / 季度 / 月度预算 + 超预算授权)
         */
        BUDGET_APPROVAL,

        /**
         * 付款审批 (出纳付款前的审批)
         */
        PAYMENT_APPROVAL,

        /**
         * 报税 / 税务申报 审批
         */
        TAX_FILING_APPROVAL,

        // ============================================
        // 人事 / 工资 类 (HR / wage)
        // ============================================

        /**
         * 请假审批 (Sprint 4 W2 H-LEAVE-1)
         */
        LEAVE_APPROVAL,

        /**
         * 加班/调休审批 (Sprint 4 W2 H-OVT-1)
         */
        OVERTIME_APPROVAL,

        /**
         * 工资记录审批 (Sprint 5 E, M-WAGE-INTEGRATION-1; 月底工资 batch 审批)
         */
        WAGE_RECORD_APPROVAL,

        /**
         * 招聘 / 入职审批 (新员工入职 + 转正)
         */
        HIRE_APPROVAL,

        // ============================================
        // 仓储 / 调拨 类 (warehouse / transfer)
        // ============================================

        /**
         * 库存调拨审批 (跨仓 / 跨厂)
         */
        INVENTORY_TRANSFER_APPROVAL,

        /**
         * 库存调整审批 (盘盈 / 盘亏 调账)
         */
        INVENTORY_ADJUSTMENT_APPROVAL,

        /**
         * 损耗记录审批 (报废 / 残次品记账)
         */
        WASTAGE_APPROVAL,

        /**
         * Human review of the single allowlisted Restaurant Agent action proposal.
         * Approval never applies ERP mutations; it may only unlock a safe navigation target.
         */
        RESTAURANT_AGENT_ACTION_REVIEW,

        /**
         * 其他自定义
         */
        CUSTOM
    }
}
