package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.service.workflow.DecisionTypeMetadata.Category;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 决策类型元数据注册中心 — 32 个 DecisionType 的中文名/分类/默认角色/moduleCode 单一来源.
 *
 * <p>Sprint 6 Track W3-B (2026-05-19) — 配合 admin UI dropdown 替换原 12 个 hardcode
 * 选项 + Sprint 5 PR #55 H 加入的 17 新 enum 值 backend 接入.
 *
 * <p><b>设计</b>: PostConstruct 时一次性 build EnumMap, 之后 get* 全部 read-only,
 * 线程安全. Bean 名 {@code decisionTypeMetadataRegistry} 自动注入.
 *
 * <p><b>moduleCode 约定</b>:
 * <ul>
 *   <li>moduleCode 是 frontend 触发审批时传给 {@link WorkflowEngineService#startWorkflow}
 *       的 string key, 跟 entity 类型/业务功能对应 (PURCHASE_ORDER / LEAVE_REQUEST 等).</li>
 *   <li>本 registry 把每个 DecisionType 标准化 moduleCode (英文 SNAKE_CASE),
 *       业务 service 接入时直接拿 metadata.getModuleCode() 用.</li>
 *   <li>CUSTOM 的 moduleCode = null — 用户在 admin UI 自定义场景, 不参与 moduleCode lookup.</li>
 * </ul>
 *
 * <p><b>wired flag</b>:
 * <ul>
 *   <li>true: 已有业务 service 调用 ApprovalWorkflowExecutor.start 触发审批 (e.g.
 *       LeaveRequestServiceImpl 调 LEAVE_APPROVAL). admin UI 显 "已接入" 标签.</li>
 *   <li>false: enum + UI dropdown 已上, 但业务 service 接入留 follow-up. admin UI
 *       显 "未接入业务 (审批可配置, 但需 service 代码改造才会触发)" 警示.</li>
 * </ul>
 *
 * @since 2026-05-19 (Sprint 6 W3-B)
 */
@Component
@Slf4j
public class DecisionTypeMetadataRegistry {

    private final Map<DecisionType, DecisionTypeMetadata> registry = new EnumMap<>(DecisionType.class);

    @PostConstruct
    public void init() {
        // ============================================
        // 生产 / 工序 类 (6)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.FORCE_INSERT)
                .chineseName("强制插单")
                .description("打断当前生产排程, 紧急插入新订单")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("factory_super_admin", "production_manager"))
                .moduleCode("FORCE_INSERT")
                .wired(true)  // UrgentInsertServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PRODUCTION_PLAN_CHANGE)
                .chineseName("生产计划变更")
                .description("修改已发布的生产计划 (调整数量 / 顺序 / 截止日期)")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("production_manager", "factory_super_admin"))
                .moduleCode("PRODUCTION_PLAN")
                .wired(false)  // 业务 service 待接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.EQUIPMENT_STATUS_CHANGE)
                .chineseName("设备状态变更")
                .description("设备投产 / 停用 / 报废 / 检修状态变更")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("equipment_manager", "production_manager"))
                .moduleCode("EQUIPMENT_STATUS")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.BOM_VERSION_APPROVAL)
                .chineseName("BOM 版本审批")
                .description("产品物料清单 (BOM) 新版本发布前的审批")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("technical_director", "quality_manager"))
                .moduleCode("BOM_VERSION")
                .wired(false)  // M-BOM-VER-1 Sprint 6 W4-C 接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.ECN_APPROVAL)
                .chineseName("ECN 工程变更通知")
                .description("Engineering Change Notice — 工艺/物料/设计变更")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("technical_director", "production_manager", "quality_manager"))
                .moduleCode("ECN")
                .wired(false)  // Sprint 6 W4-C 接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.WORK_ORDER_APPROVAL)
                .chineseName("工单审批")
                .description("任务单 / 工艺单 / 加工单的派工审批")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("production_manager", "department_admin"))
                .moduleCode("WORK_ORDER")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PRODUCTION_REVERSAL_APPROVAL)
                .chineseName("生产撤回审批")
                .description("生产计划已完成后申请撤回/重置，六扇门红线：撤回操作必须主管审批，不允许操作员自主执行")
                .category(Category.PRODUCTION)
                .defaultApproverRoles(List.of("factory_super_admin", "dispatcher", "workshop_supervisor"))
                .moduleCode("PRODUCTION_REVERSAL")
                .wired(false)  // SP12 T3 接入后改为 true
                .build());

        // ============================================
        // 质检 / 物料 类 (5)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.QUALITY_RELEASE)
                .chineseName("质检放行")
                .description("质量检验通过后批次放行入库 / 出库审批")
                .category(Category.QUALITY_MATERIAL)
                .defaultApproverRoles(List.of("quality_manager", "factory_super_admin"))
                .moduleCode("QUALITY_RELEASE")
                .wired(true)  // QualityDispositionRuleServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.QUALITY_EXCEPTION)
                .chineseName("质检特批")
                .description("质量异常物料的特殊放行 (让步接收 / 限制使用)")
                .category(Category.QUALITY_MATERIAL)
                .defaultApproverRoles(List.of("quality_manager", "factory_super_admin"))
                .moduleCode("QUALITY_EXCEPTION")
                .wired(true)  // QualityDispositionRuleServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.BATCH_STATUS_CHANGE)
                .chineseName("批次状态变更")
                .description("原料 / 产品批次状态切换 (合格 / 不合格 / 冻结 / 报废)")
                .category(Category.QUALITY_MATERIAL)
                .defaultApproverRoles(List.of("quality_manager", "warehouse_manager"))
                .moduleCode("BATCH_STATUS")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.MATERIAL_DISPOSAL)
                .chineseName("原材料处置")
                .description("不合格原材料的报废 / 退货 / 让步使用决策")
                .category(Category.QUALITY_MATERIAL)
                .defaultApproverRoles(List.of("quality_manager", "warehouse_manager", "purchasing_manager"))
                .moduleCode("MATERIAL_DISPOSAL")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.MATERIAL_REQUISITION_APPROVAL)
                .chineseName("请购单审批")
                .description("生产 / 采购前的物料申请单审批 (P-REQUISITION-1)")
                .category(Category.QUALITY_MATERIAL)
                .defaultApproverRoles(List.of("department_admin", "purchasing_manager"))
                .moduleCode("MATERIAL_REQUISITION")
                .wired(false)  // Sprint 5 D P-REQUISITION-1 entity ship, service trigger 待接入
                .build());

        // ============================================
        // 采购 / 供应商 类 (5)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.SUPPLIER_APPROVAL)
                .chineseName("供应商准入")
                .description("新供应商首次合作前的资质审核")
                .category(Category.PURCHASE_SUPPLIER)
                .defaultApproverRoles(List.of("purchasing_manager", "quality_manager", "factory_super_admin"))
                .moduleCode("SUPPLIER_APPROVAL")
                .wired(true)  // SupplierAdmissionRuleServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.SUPPLIER_STATUS_CHANGE)
                .chineseName("供应商状态变更")
                .description("已审核供应商的暂停 / 恢复 / 黑名单等状态切换")
                .category(Category.PURCHASE_SUPPLIER)
                .defaultApproverRoles(List.of("purchasing_manager", "quality_manager"))
                .moduleCode("SUPPLIER_STATUS")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PURCHASE_ORDER_APPROVAL)
                .chineseName("采购订单审批")
                .description("Phase 1 Canvas-Workflow — PO 提交时按金额 / 类别走配置审批链")
                .category(Category.PURCHASE_SUPPLIER)
                .defaultApproverRoles(List.of("purchasing_manager", "finance_manager", "factory_super_admin"))
                .moduleCode("PURCHASE_ORDER")
                .wired(true)  // WorkflowEngineServiceImpl 已 wire
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PURCHASE_PAYMENT_APPROVAL)
                .chineseName("采购付款申请审批")
                .description("PO 收货后, 财务付款前的审批")
                .category(Category.PURCHASE_SUPPLIER)
                .defaultApproverRoles(List.of("finance_manager", "factory_super_admin"))
                .moduleCode("PURCHASE_PAYMENT")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PURCHASE_RETURN_APPROVAL)
                .chineseName("采购退货审批")
                .description("已入库物料退给供应商前的审批")
                .category(Category.PURCHASE_SUPPLIER)
                .defaultApproverRoles(List.of("purchasing_manager", "warehouse_manager", "quality_manager"))
                .moduleCode("PURCHASE_RETURN")
                .wired(false)
                .build());

        // ============================================
        // 销售 / 客户 类 (5)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.SALES_ORDER_APPROVAL)
                .chineseName("销售订单审批")
                .description("Phase 1 Canvas-Workflow — SO 接单时按金额 / 客户额度走配置审批链")
                .category(Category.SALES_CUSTOMER)
                .defaultApproverRoles(List.of("sales_manager", "finance_manager", "factory_super_admin"))
                .moduleCode("SALES_ORDER")
                .wired(true)  // WorkflowEngineServiceImpl 已 wire
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.SALES_RETURN_APPROVAL)
                .chineseName("销售退货审批")
                .description("客户退货入库前的审批 (核对原因 / 责任归属)")
                .category(Category.SALES_CUSTOMER)
                .defaultApproverRoles(List.of("sales_manager", "quality_manager", "warehouse_manager"))
                .moduleCode("SALES_RETURN")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.SALES_DISCOUNT_APPROVAL)
                .chineseName("销售折扣审批")
                .description("超过销售员权限的价格让利 / 折扣审批")
                .category(Category.SALES_CUSTOMER)
                .defaultApproverRoles(List.of("sales_manager", "finance_manager", "factory_super_admin"))
                .moduleCode("SALES_DISCOUNT")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.CUSTOMER_CREDIT_APPROVAL)
                .chineseName("客户额度审批")
                .description("新客户开户 / 提高信用额度 / 临时额度审批")
                .category(Category.SALES_CUSTOMER)
                .defaultApproverRoles(List.of("sales_manager", "finance_manager", "factory_super_admin"))
                .moduleCode("CUSTOMER_CREDIT")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.INVOICE_ISSUANCE_APPROVAL)
                .chineseName("发票开具审批")
                .description("普票 / 专票 / 数电票开具前的审批")
                .category(Category.SALES_CUSTOMER)
                .defaultApproverRoles(List.of("finance_manager", "factory_super_admin"))
                .moduleCode("INVOICE_ISSUANCE")
                .wired(false)
                .build());

        // ============================================
        // 财务 / 凭证 类 (5)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.VOUCHER_APPROVAL)
                .chineseName("凭证审核")
                .description("会计凭证多级审核 (制单 → 审核 → 记账)")
                .category(Category.FINANCE_VOUCHER)
                .defaultApproverRoles(List.of("accountant", "finance_manager"))
                .moduleCode("VOUCHER")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.EXPENSE_APPROVAL)
                .chineseName("报销审批")
                .description("员工报销单审批 (Sprint 4 W2 H-EXP-1)")
                .category(Category.FINANCE_VOUCHER)
                .defaultApproverRoles(List.of("department_admin", "finance_manager"))
                .moduleCode("EXPENSE")
                .wired(true)  // ExpenseRequestServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.BUDGET_APPROVAL)
                .chineseName("预算审批")
                .description("年度 / 季度 / 月度预算 + 超预算授权 + 期间结账审批 (Sprint 7 T2 AccountingPeriodServiceImpl.requestClose 触发)")
                .category(Category.FINANCE_VOUCHER)
                .defaultApproverRoles(List.of("finance_manager", "factory_super_admin"))
                .moduleCode("BUDGET")
                .wired(true)  // Sprint 8 P2 follow-up: AccountingPeriodServiceImpl 是首个 wired caller
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.PAYMENT_APPROVAL)
                .chineseName("付款审批")
                .description("出纳付款前的审批 (含 PO 付款 / 工资发放 / 报销支付)")
                .category(Category.FINANCE_VOUCHER)
                .defaultApproverRoles(List.of("finance_manager", "factory_super_admin"))
                .moduleCode("PAYMENT")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.TAX_FILING_APPROVAL)
                .chineseName("税务申报审批")
                .description("月度 / 季度税务申报数据审批 (增值税 / 所得税 / 个税)")
                .category(Category.FINANCE_VOUCHER)
                .defaultApproverRoles(List.of("accountant", "finance_manager", "factory_super_admin"))
                .moduleCode("TAX_FILING")
                .wired(false)
                .build());

        // ============================================
        // 人事 / 工资 类 (4)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.LEAVE_APPROVAL)
                .chineseName("请假审批")
                .description("员工请假单审批 (Sprint 4 W2 H-LEAVE-1)")
                .category(Category.HR_WAGE)
                .defaultApproverRoles(List.of("department_admin", "hr_manager"))
                .moduleCode("LEAVE")
                .wired(true)  // LeaveRequestServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.OVERTIME_APPROVAL)
                .chineseName("加班/调休审批")
                .description("加班申请 / 调休申请审批 (Sprint 4 W2 H-OVT-1)")
                .category(Category.HR_WAGE)
                .defaultApproverRoles(List.of("department_admin", "hr_manager"))
                .moduleCode("OVERTIME")
                .wired(true)  // OvertimeRequestServiceImpl 已接入
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.WAGE_RECORD_APPROVAL)
                .chineseName("工资记录审批")
                .description("月底工资 batch 审批 (Sprint 5 E M-WAGE-INTEGRATION-1 + Sprint 6 W4-B PIECE_RATE/HOURLY/MIXED modes)")
                .category(Category.HR_WAGE)
                .defaultApproverRoles(List.of("hr_manager", "finance_manager", "factory_super_admin"))
                .moduleCode("WAGE_RECORD")
                .wired(true)  // Sprint 6 W4-B 工资 modes 接入 (WagePolicyController + WageMonthlyScheduler)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.HIRE_APPROVAL)
                .chineseName("招聘/入职审批")
                .description("新员工入职 + 转正审批")
                .category(Category.HR_WAGE)
                .defaultApproverRoles(List.of("hr_manager", "department_admin", "factory_super_admin"))
                .moduleCode("HIRE")
                .wired(false)
                .build());

        // ============================================
        // 仓储 / 调拨 类 (3)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.INVENTORY_TRANSFER_APPROVAL)
                .chineseName("库存调拨审批")
                .description("跨仓 / 跨厂的库存调拨申请审批")
                .category(Category.WAREHOUSE_TRANSFER)
                .defaultApproverRoles(List.of("warehouse_manager", "department_admin"))
                .moduleCode("INVENTORY_TRANSFER")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.INVENTORY_ADJUSTMENT_APPROVAL)
                .chineseName("库存调整审批")
                .description("盘盈 / 盘亏调账审批")
                .category(Category.WAREHOUSE_TRANSFER)
                .defaultApproverRoles(List.of("warehouse_manager", "finance_manager"))
                .moduleCode("INVENTORY_ADJUSTMENT")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.WASTAGE_APPROVAL)
                .chineseName("损耗记录审批")
                .description("报废 / 残次品记账审批")
                .category(Category.WAREHOUSE_TRANSFER)
                .defaultApproverRoles(List.of("warehouse_manager", "quality_manager", "finance_manager"))
                .moduleCode("WASTAGE")
                .wired(false)
                .build());

        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .chineseName("餐饮智能建议复核")
                .description("仅复核菜品成本资料补全建议；通过后只允许导航到菜谱资料页")
                .category(Category.OTHER)
                .defaultApproverRoles(List.of(
                        "restaurant_owner", "restaurant_manager", "finance_manager"))
                .moduleCode("restaurant.dish-cost-data-review.v1")
                .wired(true)
                .build());

        // ============================================
        // 其他 (1)
        // ============================================
        register(DecisionTypeMetadata.builder()
                .decisionType(DecisionType.CUSTOM)
                .chineseName("自定义")
                .description("自定义决策类型, 由 admin 在 UI 自由定义场景")
                .category(Category.OTHER)
                .defaultApproverRoles(List.of("factory_super_admin"))
                .moduleCode(null)  // CUSTOM 不参与 moduleCode lookup
                .wired(false)
                .build());

        // 完整性自检 — 所有 enum 值都必须 register, 否则 admin UI 缺项.
        for (DecisionType dt : DecisionType.values()) {
            if (!registry.containsKey(dt)) {
                throw new IllegalStateException(
                        "DecisionTypeMetadataRegistry 缺少 enum 元数据: " + dt +
                        " — 添加 register(...) 调用");
            }
        }

        log.info("DecisionTypeMetadataRegistry 初始化完成 — 已注册 {} 个 DecisionType 元数据 ({} wired, {} unwired)",
                registry.size(),
                registry.values().stream().filter(DecisionTypeMetadata::isWired).count(),
                registry.values().stream().filter(m -> !m.isWired()).count());
    }

    /**
     * 注册单个 DecisionType 元数据. 重复 register 同 DecisionType 抛 IllegalStateException
     * (init 时自检, 防开发 typo).
     */
    private void register(DecisionTypeMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(metadata.getDecisionType(), "decisionType must not be null");
        DecisionTypeMetadata prev = registry.put(metadata.getDecisionType(), metadata);
        if (prev != null) {
            throw new IllegalStateException(
                    "重复注册 DecisionType 元数据: " + metadata.getDecisionType() +
                    " (旧: " + prev.getChineseName() + ", 新: " + metadata.getChineseName() + ")");
        }
    }

    /** 取单个 DecisionType 的元数据. 未注册返 null. */
    public DecisionTypeMetadata get(DecisionType decisionType) {
        if (decisionType == null) return null;
        return registry.get(decisionType);
    }

    /**
     * 取全部 32 个 DecisionType 的元数据. 调用方不可修改返回 map (read-only view).
     */
    public Map<DecisionType, DecisionTypeMetadata> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    /** 已 wired 的 DecisionType 个数 — admin UI badge 显示 "X/32 已接入业务" 用. */
    public int getWiredCount() {
        return (int) registry.values().stream().filter(DecisionTypeMetadata::isWired).count();
    }

    /** 全部 enum 个数 (registry 完整性自检后等于 DecisionType.values().length). */
    public int size() {
        return registry.size();
    }

    /**
     * moduleCode → DecisionType 反向查找. 用于 WorkflowEngineService.lookupDecisionType
     * 不再 hardcode 两条 entry, 改读 metadata.
     *
     * @return 匹配的 DecisionType, null 表示无该 moduleCode 对应的 DecisionType
     */
    public DecisionType lookupByModuleCode(String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) return null;
        for (DecisionTypeMetadata m : registry.values()) {
            if (moduleCode.equals(m.getModuleCode())) {
                return m.getDecisionType();
            }
        }
        return null;
    }
}
