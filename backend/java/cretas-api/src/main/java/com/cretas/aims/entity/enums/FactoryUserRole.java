package com.cretas.aims.entity.enums;

/**
 * 工厂用户角色枚举
 *
 * 角色层级架构:
 * - Level 0: 工厂总监 (最高权限)
 * - Level 10: 职能部门经理 (各部门主管)
 * - Level 20: 车间管理层 (车间主任)
 * - Level 30: 一线员工 (操作员、质检员、仓库员)
 * - Level 50/99: 特殊角色 (查看者/未激活)
 *
 * 所有角色支持双平台 (Web + Mobile)
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-01-09
 */
public enum FactoryUserRole {
    // ===== Level -1: 平台最高管理层 (跨工厂) =====
    /**
     * 平台管理员
     * 跨工厂权限, factoryId 可为 null 或特殊值 "PLATFORM"
     * 拥有所有工厂所有模块 rw
     */
    platform_admin("平台管理员", "跨工厂平台级权限", -1, "platform"),

    // ===== Level 0: 工厂最高管理层 =====
    /**
     * 工厂超级管理员/总监
     * 拥有工厂所有权限
     */
    factory_super_admin("工厂总监", "拥有工厂所有权限", 0, "all"),

    // ===== Level 10: 职能部门经理 =====
    /**
     * HR管理员
     * 人事管理、考勤、薪资、白名单
     */
    hr_admin("HR管理员", "人事管理、考勤、薪资", 10, "hr"),

    /**
     * 采购主管
     * 供应商管理、采购订单、成本控制
     */
    procurement_manager("采购主管", "供应商、采购、成本", 10, "procurement"),

    /**
     * 销售主管
     * 客户管理、销售订单、出货管理
     */
    sales_manager("销售主管", "客户、订单、出货", 10, "sales"),

    /**
     * 调度
     * 生产调度、数据分析、趋势监控、全局视图
     */
    dispatcher("调度", "生产调度、数据分析、趋势监控", 10, "dispatch"),

    /**
     * 生产经理 (向后兼容，已重命名为 dispatcher)
     * @deprecated 请使用 dispatcher 角色
     */
    @Deprecated
    production_manager("调度", "生产调度、数据分析、趋势监控", 10, "dispatch"),

    /**
     * 仓储主管
     * 库存管理、出入库、盘点
     */
    warehouse_manager("仓储主管", "库存、出入库、盘点", 10, "warehouse"),

    /**
     * 设备管理员
     * 设备台账、维护保养、告警处理
     */
    equipment_admin("设备管理员", "设备维护、保养、告警", 10, "equipment"),

    /**
     * 质量经理
     * 质量体系、质检审核、标准制定
     */
    quality_manager("质量经理", "质量体系、质检审核", 10, "quality"),

    /**
     * 品控 (SP12 T1 新增)
     * 质量标准制定、特批、处置审核
     */
    quality_controller("品控", "质量标准制定、特批、处置审核", 15, "quality"),

    /**
     * 财务主管
     * 成本核算、费用管理、财务报表
     */
    finance_manager("财务主管", "成本核算、费用、报表", 10, "finance"),

    /**
     * 出纳 (SP12 T1 新增)
     * 负责付款操作、银行对账
     */
    cashier("出纳", "负责付款操作、银行对账", 15, "finance"),

    /**
     * 餐饮管理
     * 菜单/配方/厨房/餐厅运营
     */
    restaurant_manager("餐饮管理", "菜单、配方、餐厅运营", 10, "restaurant"),

    /**
     * 餐饮老板
     * 全餐饮运营视角: restaurant/procurement/finance/warehouse/analytics 完整读写
     * 价格异常审批、月对账确认、采购付款等老板级操作
     */
    restaurant_owner("餐饮老板", "全餐饮运营、价格异常审批、月对账", 5, "restaurant"),

    /**
     * 厨师长
     * 负责厨房生产侧: 报货(领料申请)、验收入库
     * 需要 restaurant:rw + warehouse:rw
     */
    restaurant_chef("厨师长", "报货、领料、验收入库", 15, "restaurant"),

    /**
     * 餐饮采购
     * 负责采购到付款全链路: 请购、采购订单确认/审批
     * 需要 restaurant:rw + procurement:rw
     */
    restaurant_purchaser("餐饮采购", "请购、采购确认、采购审批", 15, "restaurant"),

    // ===== Level 20: 车间管理层 =====
    /**
     * 车间主任
     * 车间日常管理、人员调度、生产执行
     */
    workshop_supervisor("车间主任", "车间日常、人员调度", 20, "workshop"),

    /**
     * 大组长
     * 管理多个小组,承担车间主任下一级的带班职责
     */
    team_leader("大组长", "多组带班、任务分派", 25, "workshop"),

    /**
     * 小组长
     * 管理单个生产小组,负责本组日常执行
     */
    group_leader("小组长", "本组日常、现场执行", 28, "workshop"),

    // ===== Level 30: 一线员工 =====
    /**
     * 质检员
     * 执行质检、提交报告
     * 双重汇报: 日常归车间主任，质检结果报质量经理
     */
    quality_inspector("质检员", "执行质检、提交报告", 30, "quality"),

    /**
     * 操作员
     * 生产执行、打卡记录、批次操作
     */
    operator("操作员", "生产执行、打卡记录", 30, "production"),

    /**
     * 报工操作员 (X-6 六扇门补全)
     * 仅负责报工写入 (产量/工时/工序报告), 无管理界面
     * V20261011_02 liushanmen_rbac_matrix 引用此角色; 无此枚举值则 fromRoleCode 静默返回 unactivated
     */
    yield_operator("报工操作员", "报工写入: 产量/工时/工序报告", 30, "production"),

    /**
     * 仓库员
     * 出入库操作、盘点、库存维护
     */
    warehouse_worker("仓库员", "出入库操作、盘点", 30, "warehouse"),

    // ===== 特殊角色 =====
    /**
     * 权限管理员 (向后兼容)
     * @deprecated 建议使用 factory_super_admin 或具体部门角色
     */
    @Deprecated
    permission_admin("权限管理员", "管理用户权限和角色", 10, "system"),

    /**
     * 部门管理员 (向后兼容)
     * @deprecated 建议使用具体的部门经理角色
     */
    @Deprecated
    department_admin("部门管理员", "管理部门相关业务", 15, "department"),

    /**
     * 查看者
     * 只读访问所有数据
     */
    viewer("查看者", "只读访问", 50, "none"),

    /**
     * 未激活用户
     * 账户未激活，无任何权限
     */
    unactivated("未激活", "账户未激活", 99, "none");

    private final String displayName;
    private final String description;
    private final int level;
    private final String department;

    FactoryUserRole(String displayName, String description, int level, String department) {
        this.displayName = displayName;
        this.description = description;
        this.level = level;
        this.department = department;
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取角色描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取权限级别 (0最高, 99最低)
     */
    public int getLevel() {
        return level;
    }

    /**
     * 获取所属部门
     */
    public String getDepartment() {
        return department;
    }

    /**
     * 检查是否为管理层角色 (Level 0-20)
     */
    public boolean isManager() {
        return level <= 20;
    }

    /**
     * 检查是否为一线员工 (Level 30)
     */
    public boolean isWorker() {
        return level == 30;
    }

    /**
     * 检查是否有效角色 (非未激活)
     */
    public boolean isActive() {
        return this != unactivated;
    }

    /**
     * 检查是否可以管理指定角色
     * @param target 目标角色
     * @return 如果当前角色级别更高返回true
     */
    public boolean canManage(FactoryUserRole target) {
        return this.level < target.level;
    }

    /**
     * 获取角色的权限前缀
     * 用于 module:action 格式的权限检查
     */
    public String getPermissionPrefix() {
        switch (this) {
            case platform_admin:
            case factory_super_admin:
            case permission_admin:
                return "*";
            case restaurant_manager:
            case restaurant_owner:
            case restaurant_chef:
            case restaurant_purchaser:
                return "restaurant";
            case hr_admin:
                return "hr";
            case procurement_manager:
                return "procurement";
            case sales_manager:
                return "sales";
            case dispatcher:
            case production_manager:
            case workshop_supervisor:
            case team_leader:
            case group_leader:
            case operator:
            case yield_operator:
                return "production";
            case warehouse_manager:
            case warehouse_worker:
                return "warehouse";
            case equipment_admin:
                return "equipment";
            case quality_manager:
            case quality_controller:
            case quality_inspector:
                return "quality";
            case finance_manager:
            case cashier:
                return "finance";
            case viewer:
                return "view";
            default:
                return "none";
        }
    }

    /**
     * 根据角色代码获取角色枚举
     * @param roleCode 角色代码字符串
     * @return 对应的角色枚举，如果未找到返回 unactivated
     */
    public static FactoryUserRole fromRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isEmpty()) {
            return unactivated;
        }
        try {
            return valueOf(roleCode);
        } catch (IllegalArgumentException e) {
            return unactivated;
        }
    }
}
