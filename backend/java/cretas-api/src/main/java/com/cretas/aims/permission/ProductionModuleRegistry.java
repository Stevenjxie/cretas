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
            String category,
            String permissionModule) {
    }

    private static final List<ModuleDefinition> MODULES = List.of(
            new ModuleDefinition("production_plan", "生产计划", "生产", "production"),
            new ModuleDefinition("production_report", "生产报工", "生产", "production"),
            new ModuleDefinition("work_process", "工序执行", "生产", "production"),
            new ModuleDefinition("bom", "BOM/配方", "生产", "production"),
            new ModuleDefinition("scheduling", "智能排程", "生产", "scheduling"),
            new ModuleDefinition("warehouse", "仓储", "仓储", "warehouse"),
            new ModuleDefinition("quality_inspection", "质量检验", "质量", "quality"),
            new ModuleDefinition("purchase_order", "采购订单", "采购", "procurement"),
            new ModuleDefinition("sales_order", "销售订单", "销售", "sales"),
            new ModuleDefinition("equipment", "设备", "设备", "equipment"),
            new ModuleDefinition("finance_ap", "应付财务", "财务", "finance"),
            new ModuleDefinition("finance_ar", "应收财务", "财务", "finance"),
            new ModuleDefinition("hr_employee", "员工/人事", "人事", "hr"),
            new ModuleDefinition("restaurant", "餐饮经营", "餐饮", "restaurant"),
            new ModuleDefinition("production", "生产管理", "兼容", "production")
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
                new ModuleDefinition(moduleCode, moduleCode, "未分类", moduleCode));
    }

    public static String permissionModule(String moduleCode) {
        return resolve(moduleCode).permissionModule();
    }
}
