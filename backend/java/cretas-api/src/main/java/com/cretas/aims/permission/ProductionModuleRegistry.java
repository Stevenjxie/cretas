package com.cretas.aims.permission;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ProductionModuleRegistry {

    public record ModuleDefinition(
            String moduleCode,
            String displayName,
            String parentCode,
            String parentName,
            String routePath,
            int sortOrder,
            boolean writeSupported) {

        public String category() {
            return parentName;
        }

        public String permissionModule() {
            return parentCode;
        }
    }

    private static ModuleDefinition module(
            String moduleCode,
            String displayName,
            String parentCode,
            String parentName,
            String routePath,
            int sortOrder,
            boolean writeSupported) {
        return new ModuleDefinition(
                moduleCode,
                displayName,
                parentCode,
                parentName,
                routePath,
                sortOrder,
                writeSupported);
    }

    private static final List<ModuleDefinition> MODULES = List.of(
            module("production_plan", "生产计划", "production", "生产管理", "/production/plans", 10, true),
            module("production_report", "生产报工", "production", "生产管理", "/production/approval", 20, true),
            module("work_process", "工序执行", "production", "生产管理", "/production/process-io", 30, true),
            module("bom", "BOM/配方", "production", "生产管理", "/production/bom", 40, true),
            module("scheduling", "智能排程", "scheduling", "智能调度", "/scheduling/plans", 50, true),
            module("warehouse", "仓储", "warehouse", "仓储管理", "/warehouse/materials", 60, true),
            module("quality_inspection", "质量检验", "quality", "质量管理", "/quality/inspections", 70, true),
            module("purchase_order", "采购订单", "procurement", "采购管理", "/procurement/orders", 80, true),
            module("sales_order", "销售订单", "sales", "销售管理", "/sales/orders", 90, true),
            module("equipment", "设备", "equipment", "设备管理", "/equipment/list", 100, true),
            module("finance_ap", "应付财务", "finance", "财务管理", "/finance/ar-ap", 110, true),
            module("finance_ar", "应收财务", "finance", "财务管理", "/finance/ar-ap", 120, true),
            module("hr_employee", "员工/人事", "hr", "人事管理", "/hr/employees", 130, true),
            module("restaurant", "餐饮经营", "restaurant", "餐饮运营", "/restaurant", 140, true),
            module("production", "生产管理", "production", "生产管理", "/production", 150, true),
            module("permission_employee_management", "员工管理", "permission_settings", "权限设置", "/permissions/employees", 10, true),
            module("permission_role_templates", "角色权限模板", "permission_settings", "权限设置", "/permissions/role-templates", 20, true),
            module("permission_employee_overrides", "员工权限", "permission_settings", "权限设置", "/permissions/employee-permissions", 30, true),
            module("permission_preview", "权限预览", "permission_settings", "权限设置", "/permissions/preview", 40, false)
    );

    private static final Map<String, ModuleDefinition> BY_CODE = MODULES.stream()
            .collect(Collectors.toUnmodifiableMap(ModuleDefinition::moduleCode, Function.identity()));

    private ProductionModuleRegistry() {
    }

    public static List<ModuleDefinition> modules() {
        return MODULES;
    }

    public static Set<String> moduleCodes() {
        return BY_CODE.keySet();
    }

    public static Optional<ModuleDefinition> find(String moduleCode) {
        return Optional.ofNullable(BY_CODE.get(moduleCode));
    }

    public static ModuleDefinition resolve(String moduleCode) {
        return find(moduleCode).orElseGet(() ->
                new ModuleDefinition(moduleCode, moduleCode, moduleCode, "未分类", "/" + moduleCode, Integer.MAX_VALUE, false));
    }

    public static String permissionModule(String moduleCode) {
        return resolve(moduleCode).permissionModule();
    }
}
