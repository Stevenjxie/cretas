package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限服务实现
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-27
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceImpl.class);

    @Autowired(required = false)
    private PlatformRolePermissionRepository platformRepo;

    @Autowired(required = false)
    private FactoryModuleConfigRepository factoryConfigRepo;

    // 简易 TTL cache: permissionKey -> [expiresAtMs, Boolean]
    // Key: userId:permissionCode:factoryId
    // TTL: 5 minutes. Cleared on any matrix update via invalidateCache().
    private final Map<String, long[]> resolveCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> resolveResult = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final String LEGACY_IMPORT_PERMISSION = "inventory:legacy_import";

    /**
     * 清除权限解析缓存. L1/L2 PUT API 调用时触发.
     */
    public void invalidateCache() {
        resolveCache.clear();
        resolveResult.clear();
    }

    /**
     * 模块列表
     */
    private static final List<String> ALL_MODULES = Arrays.asList(
            "dashboard", "production", "warehouse", "quality",
            "procurement", "sales", "hr", "equipment", "finance", "system", "analytics",
            "scheduling", "work_report", "inventory", "report",
            "rd", "restaurant"
    );

    /**
     * 角色权限矩阵 (内存缓存)
     * 格式: role -> module -> permission_type (none/read/write/read_write)
     */
    private static final Map<FactoryUserRole, Map<String, String>> PERMISSION_MATRIX = new HashMap<>();

    static {
        // 初始化权限矩阵
        initPermissionMatrix();
    }

    private static void initPermissionMatrix() {
        // factory_super_admin: 所有模块读写
        Map<String, String> superAdminPerms = new HashMap<>();
        ALL_MODULES.forEach(m -> superAdminPerms.put(m, "read_write"));
        PERMISSION_MATRIX.put(FactoryUserRole.factory_super_admin, superAdminPerms);

        // platform_admin: 平台级, 同 super_admin (所有模块读写)
        Map<String, String> platformAdminPerms = new HashMap<>();
        ALL_MODULES.forEach(m -> platformAdminPerms.put(m, "read_write"));
        PERMISSION_MATRIX.put(FactoryUserRole.platform_admin, platformAdminPerms);

        // dispatcher (调度): 生产读写 + 全模块只读 + 数据分析读写
        Map<String, String> dispatcherPerms = new HashMap<>();
        dispatcherPerms.put("dashboard", "read_write");
        dispatcherPerms.put("production", "read_write");
        dispatcherPerms.put("warehouse", "read");
        dispatcherPerms.put("quality", "read");
        dispatcherPerms.put("procurement", "read");
        dispatcherPerms.put("sales", "read_write"); // Apr 18 2026 bug #45: 调度员需能提交/审核/驳回 SO (协调生产发货)
        dispatcherPerms.put("hr", "read");
        dispatcherPerms.put("equipment", "read");
        dispatcherPerms.put("finance", "read");     // 新增: 可查看财务分析
        dispatcherPerms.put("system", "read");
        dispatcherPerms.put("analytics", "read_write"); // 新增: 数据分析中心
        dispatcherPerms.put("scheduling", "read_write");
        dispatcherPerms.put("work_report", "read_write");
        dispatcherPerms.put("inventory", "read");
        dispatcherPerms.put("report", "read");
        dispatcherPerms.put("rd", "read_write");  // 调度协调 RD 样品到生产
        PERMISSION_MATRIX.put(FactoryUserRole.dispatcher, dispatcherPerms);
        PERMISSION_MATRIX.put(FactoryUserRole.production_manager, dispatcherPerms); // 向后兼容

        // quality_manager
        Map<String, String> qualityManagerPerms = new HashMap<>();
        qualityManagerPerms.put("dashboard", "read");
        qualityManagerPerms.put("production", "read");
        qualityManagerPerms.put("quality", "read_write");
        qualityManagerPerms.put("rd", "read");  // QA 审核样品但不创建
        PERMISSION_MATRIX.put(FactoryUserRole.quality_manager, qualityManagerPerms);

        // workshop_supervisor
        Map<String, String> workshopPerms = new HashMap<>();
        workshopPerms.put("dashboard", "read");
        workshopPerms.put("production", "read_write");
        workshopPerms.put("warehouse", "read");
        workshopPerms.put("quality", "read_write"); // #963 same-cause: 车间主任需读质检/缺陷列表(原 write-only→read 全 403)
        workshopPerms.put("hr", "read");
        workshopPerms.put("equipment", "read");
        workshopPerms.put("scheduling", "read");
        workshopPerms.put("work_report", "read_write");
        workshopPerms.put("report", "read");
        workshopPerms.put("rd", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.workshop_supervisor, workshopPerms);

        // team_leader (大组长) — 车间主任下一级,去掉 hr/report 审批类
        Map<String, String> teamLeaderPerms = new HashMap<>();
        teamLeaderPerms.put("dashboard", "read");
        teamLeaderPerms.put("production", "read_write");
        teamLeaderPerms.put("warehouse", "read");
        teamLeaderPerms.put("quality", "read_write"); // #963 same-cause: 大组长读本线质检结果(write-only 读 403)
        teamLeaderPerms.put("equipment", "read");
        teamLeaderPerms.put("scheduling", "read");
        teamLeaderPerms.put("work_report", "read_write");
        teamLeaderPerms.put("rd", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.team_leader, teamLeaderPerms);

        // group_leader (小组长) — 只管本组,去掉 scheduling 全局查看
        Map<String, String> groupLeaderPerms = new HashMap<>();
        groupLeaderPerms.put("dashboard", "read");
        groupLeaderPerms.put("production", "read_write");
        groupLeaderPerms.put("quality", "read_write"); // #963 same-cause: 小组长读本组质检结果(write-only 读 403)
        groupLeaderPerms.put("equipment", "read");
        groupLeaderPerms.put("work_report", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.group_leader, groupLeaderPerms);

        // quality_inspector
        // Issue #812 fix (2026-05-17): grant read_write (was "write" only).
        // Inspector needs to GET inspection records they created + GET defect
        // lists (which require quality:read / quality:read_write). The "write"
        // legacy level fails checkAction("read_write") AND checkAction("read"),
        // blocking both POST /quality/inspections AND GET /quality-defects.
        // The role name literally is "inspector" — they need both read AND write.
        Map<String, String> inspectorPerms = new HashMap<>();
        inspectorPerms.put("dashboard", "read");
        inspectorPerms.put("production", "read");
        inspectorPerms.put("quality", "read_write");
        inspectorPerms.put("work_report", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.quality_inspector, inspectorPerms);

        // operator
        // V20261024_24 / #963 同因: 报工角色本职="读任务/上限/WIP + 写报工", 只写不读本职
        // 模块 → 所有 production/work_report GET 端点 403。production/work_report 升 read_write。
        Map<String, String> operatorPerms = new HashMap<>();
        operatorPerms.put("dashboard", "read");
        operatorPerms.put("production", "read_write");
        operatorPerms.put("work_report", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.operator, operatorPerms);

        // yield_operator: ordinary report-work account, limited to production reporting.
        // V20261024_24 实测: production='w' 导致逐道报工 GET yield/limits/wip 全 403 (无法报工)。
        Map<String, String> yieldOperatorPerms = new HashMap<>();
        yieldOperatorPerms.put("dashboard", "read");
        yieldOperatorPerms.put("production", "read_write");
        yieldOperatorPerms.put("work_report", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.yield_operator, yieldOperatorPerms);

        // warehouse_manager
        Map<String, String> warehouseManagerPerms = new HashMap<>();
        warehouseManagerPerms.put("dashboard", "read_write");
        warehouseManagerPerms.put("warehouse", "read_write");
        warehouseManagerPerms.put("production", "read");
        warehouseManagerPerms.put("scheduling", "read");
        warehouseManagerPerms.put("inventory", "read_write");
        warehouseManagerPerms.put("report", "read");
        warehouseManagerPerms.put("procurement", "read");
        warehouseManagerPerms.put("sales", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.warehouse_manager, warehouseManagerPerms);

        // warehouse_worker
        // Issue: 扫码收货 403 (2026-06-17, F006). Same bug-class as #812: a role with
        // legacy "write" on its own module fails checkAction("read") AND
        // checkAction("read_write"), blocking every GET endpoint for that module.
        // 仓库员 must READ warehouse + inventory (扫码收货拿订单 / 出库 / 盘点 / 查库存),
        // not just write. The authoritative fix is the L1 DB migration
        // V20261024_22 (platform_role_permissions); this matrix entry is the
        // hardcoded fallback and is aligned for consistency.
        Map<String, String> warehouseWorkerPerms = new HashMap<>();
        warehouseWorkerPerms.put("dashboard", "read");
        warehouseWorkerPerms.put("warehouse", "read_write");
        warehouseWorkerPerms.put("inventory", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.warehouse_worker, warehouseWorkerPerms);

        // hr_admin
        Map<String, String> hrPerms = new HashMap<>();
        hrPerms.put("dashboard", "read");
        hrPerms.put("hr", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.hr_admin, hrPerms);

        // equipment_admin
        Map<String, String> equipmentPerms = new HashMap<>();
        equipmentPerms.put("dashboard", "read");
        equipmentPerms.put("equipment", "read_write");
        PERMISSION_MATRIX.put(FactoryUserRole.equipment_admin, equipmentPerms);

        // procurement_manager
        Map<String, String> procurementPerms = new HashMap<>();
        procurementPerms.put("dashboard", "read");
        procurementPerms.put("procurement", "read_write");
        procurementPerms.put("warehouse", "read");
        procurementPerms.put("production", "read");
        procurementPerms.put("finance", "read");
        procurementPerms.put("inventory", "read");
        procurementPerms.put("report", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.procurement_manager, procurementPerms);

        // sales_manager
        Map<String, String> salesPerms = new HashMap<>();
        salesPerms.put("dashboard", "read");
        salesPerms.put("sales", "read_write");
        salesPerms.put("warehouse", "read");
        salesPerms.put("production", "read");
        salesPerms.put("finance", "read");
        salesPerms.put("analytics", "read");
        salesPerms.put("report", "read");
        salesPerms.put("rd", "read_write");  // 销售驱动 RD 需求/样品
        PERMISSION_MATRIX.put(FactoryUserRole.sales_manager, salesPerms);

        // finance_manager: 财务主管 - SmartBI完整权限（含上传Excel）
        Map<String, String> financePerms = new HashMap<>();
        financePerms.put("dashboard", "read");
        financePerms.put("finance", "read_write");
        financePerms.put("production", "read");
        financePerms.put("procurement", "read");
        financePerms.put("sales", "read");
        financePerms.put("analytics", "read_write");  // SmartBI 完整权限
        financePerms.put("report", "read");
        financePerms.put("rd", "read");  // 定价参考
        PERMISSION_MATRIX.put(FactoryUserRole.finance_manager, financePerms);

        // restaurant_manager: 餐饮管理 (跨工厂类型餐饮 domain 角色)
        Map<String, String> restaurantManagerPerms = new HashMap<>();
        restaurantManagerPerms.put("dashboard", "read");
        restaurantManagerPerms.put("restaurant", "read_write");
        restaurantManagerPerms.put("procurement", "read");
        restaurantManagerPerms.put("finance", "read");
        restaurantManagerPerms.put("analytics", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.restaurant_manager, restaurantManagerPerms);

        // restaurant_owner: 餐饮老板 — 全餐饮运营 + 价格异常审批 + 月对账确认
        // 涵盖 restaurant/procurement/finance/warehouse/analytics 完整读写
        Map<String, String> restaurantOwnerPerms = new HashMap<>();
        restaurantOwnerPerms.put("dashboard", "read_write");
        restaurantOwnerPerms.put("restaurant", "read_write");
        restaurantOwnerPerms.put("procurement", "read_write"); // 采购审批、请购确认
        restaurantOwnerPerms.put("finance", "read_write");     // 月对账确认、财务审核通过/驳回
        restaurantOwnerPerms.put("warehouse", "read_write");   // 报货/领料审批、验收入库
        restaurantOwnerPerms.put("analytics", "read_write");   // 经营分析
        restaurantOwnerPerms.put("report", "read");
        PERMISSION_MATRIX.put(FactoryUserRole.restaurant_owner, restaurantOwnerPerms);

        // restaurant_chef: 厨师长 — 报货/领料(MaterialRequisition) + 验收入库(SupplierDeliveryNote)
        // 需要 restaurant:rw + warehouse:rw; 不需要 procurement:rw (不审采购单)
        Map<String, String> restaurantChefPerms = new HashMap<>();
        restaurantChefPerms.put("dashboard", "read");
        restaurantChefPerms.put("restaurant", "read_write");   // RestaurantModule 访问
        restaurantChefPerms.put("warehouse", "read_write");    // 报货/领料 create+submit+approve + 验收入库 confirm
        restaurantChefPerms.put("procurement", "read");        // 只读采购单 (查看状态)
        restaurantChefPerms.put("analytics", "read");          // 基础数据查看
        PERMISSION_MATRIX.put(FactoryUserRole.restaurant_chef, restaurantChefPerms);

        // restaurant_purchaser: 餐饮采购 — 请购单+采购订单 全链路
        // 需要 restaurant:rw + procurement:rw; 不需要 warehouse:rw (不验收入库)
        Map<String, String> restaurantPurchaserPerms = new HashMap<>();
        restaurantPurchaserPerms.put("dashboard", "read");
        restaurantPurchaserPerms.put("restaurant", "read_write");    // RestaurantModule 访问
        restaurantPurchaserPerms.put("procurement", "read_write");   // 请购 create/submit/approve + 采购 confirm/approve
        restaurantPurchaserPerms.put("warehouse", "read");           // 只读仓库 (查看库存状态)
        restaurantPurchaserPerms.put("finance", "read");             // 查看采购金额 (不能财务审核)
        restaurantPurchaserPerms.put("analytics", "read");           // 基础数据查看
        PERMISSION_MATRIX.put(FactoryUserRole.restaurant_purchaser, restaurantPurchaserPerms);

        // viewer: 所有模块只读
        Map<String, String> viewerPerms = new HashMap<>();
        ALL_MODULES.stream()
                .filter(m -> !m.equals("system"))
                .forEach(m -> viewerPerms.put(m, "read"));
        PERMISSION_MATRIX.put(FactoryUserRole.viewer, viewerPerms);

        // 向后兼容的角色
        PERMISSION_MATRIX.put(FactoryUserRole.permission_admin, superAdminPerms);
        PERMISSION_MATRIX.put(FactoryUserRole.department_admin, dispatcherPerms);

        // unactivated: 无权限
        PERMISSION_MATRIX.put(FactoryUserRole.unactivated, new HashMap<>());
    }

    /**
     * 角色白名单 — 允许查看金额/价格字段 ({@code procurement:price:view}).
     *
     * <p>PR #415 RBAC audit verdict 🔴 RED 修复 (2026-05-12): 仓库管理员/质检员/操作员
     * 等不需要查看价格的角色, 被 {@link com.cretas.aims.security.PriceFieldResponseAdvice}
     * 后端剥离 totalAmount / unitPrice / costUnitPrice 等字段.
     *
     * <p>白名单角色: factory_super_admin / platform_admin / procurement_manager /
     * finance_manager / sales_manager / dispatcher / production_manager /
     * restaurant_manager. 其余角色一律不可见.
     */
    private static final Set<FactoryUserRole> PRICE_VIEW_ROLES = Set.of(
            FactoryUserRole.factory_super_admin,
            FactoryUserRole.platform_admin,
            FactoryUserRole.procurement_manager,
            FactoryUserRole.finance_manager,
            FactoryUserRole.sales_manager,
            // 调度员 / 生产主管 需协调成本预算
            FactoryUserRole.dispatcher,
            FactoryUserRole.production_manager,
            // 餐饮主管查看食材/菜品成本
            FactoryUserRole.restaurant_manager,
            // 餐饮老板/采购需查看采购价格 (价格异常审批需对比价格)
            FactoryUserRole.restaurant_owner,
            FactoryUserRole.restaurant_purchaser,
            // 向后兼容
            FactoryUserRole.permission_admin,
            FactoryUserRole.department_admin
    );

    /** 价格查看权限代码 (与 PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION 一致). */
    private static final String PRICE_VIEW_PERMISSION = "procurement:price:view";

    /**
     * 角色白名单 — 允许查看财务待审采购单 (Issue #736 fix, 2026-05-17).
     *
     * <p>{@code /procurement/finance-review} 列表暴露 PENDING_FINANCE_REVIEW 状态的采购订单，
     * 含供应商名、总金额、价格异常告警等业务敏感数据。viewer 角色虽然有 procurement:read,
     * 但本不该看到这个工作流, 必须显式 deny.
     *
     * <p>白名单: factory_super_admin / platform_admin (兜底) / procurement_manager (流程发起方) /
     * finance_manager (审核执行方) / dispatcher (协调). 其余一律不可见.
     */
    private static final Set<FactoryUserRole> FINANCE_REVIEW_VIEW_ROLES = Set.of(
            FactoryUserRole.factory_super_admin,
            FactoryUserRole.platform_admin,
            FactoryUserRole.procurement_manager,
            FactoryUserRole.finance_manager,
            FactoryUserRole.dispatcher,
            // 餐饮老板需查看财务待审采购单 (采购金额审批)
            FactoryUserRole.restaurant_owner,
            // 向后兼容
            FactoryUserRole.permission_admin,
            FactoryUserRole.department_admin
    );

    /** 财务待审采购单查看权限代码 (Issue #736). */
    public static final String FINANCE_REVIEW_VIEW_PERMISSION = "procurement:finance_review:view";

    @Override
    public boolean hasPermission(User user, String permissionCode) {
        if (user == null || permissionCode == null || permissionCode.isEmpty()) {
            return false;
        }

        FactoryUserRole role = user.getRoleEnum();
        if (role == null || role == FactoryUserRole.unactivated) {
            return false;
        }

        // 超级管理员拥有所有权限
        if (role == FactoryUserRole.factory_super_admin || role == FactoryUserRole.platform_admin) {
            return true;
        }

        if (LEGACY_IMPORT_PERMISSION.equals(permissionCode)) {
            return false;
        }

        // procurement:price:view — 价格字段查看权限 (PR #415 Option B)
        // 走独立角色白名单, 不复用 module:action 矩阵 (该矩阵只支持 read/write 二元).
        if (PRICE_VIEW_PERMISSION.equals(permissionCode)) {
            return PRICE_VIEW_ROLES.contains(role);
        }

        // procurement:finance_review:view — 财务待审采购单查看 (Issue #736 fix).
        // 走独立角色白名单, viewer 不在内 (修复 P0 RBAC bypass).
        if (FINANCE_REVIEW_VIEW_PERMISSION.equals(permissionCode)) {
            return FINANCE_REVIEW_VIEW_ROLES.contains(role);
        }

        // 解析权限代码 (格式: module:action)
        String[] parts = permissionCode.split(":");
        if (parts.length != 2) {
            return false;
        }
        String module = parts[0];
        String action = parts[1];

        // 缓存查询 (5min TTL)
        String cacheKey = (user.getId() == null ? "0" : user.getId())
            + ":" + permissionCode
            + ":" + (user.getFactoryId() == null ? "" : user.getFactoryId());
        long now = System.currentTimeMillis();
        long[] exp = resolveCache.get(cacheKey);
        if (exp != null && exp[0] > now) {
            Boolean cached = resolveResult.get(cacheKey);
            if (cached != null) return cached;
        }

        // 按 L2 → L1 → hardcoded fallback 顺序解析 permission level
        String permType = null;

        // L2: factory-level override
        if (user.getFactoryId() != null && factoryConfigRepo != null) {
            permType = resolveLayer2(user.getFactoryId(), role.name(), module);
        }

        // L1: platform global default (from DB, seeded by Flyway V20260419_01)
        if (permType == null && platformRepo != null) {
            permType = resolveLayer1(role.name(), module);
        }

        // Fallback: hardcoded PERMISSION_MATRIX (safety net)
        if (permType == null) {
            Map<String, String> rolePerms = PERMISSION_MATRIX.get(role);
            if (rolePerms != null) {
                permType = rolePerms.get(module);
            }
        }

        boolean result = checkAction(permType, action);
        resolveCache.put(cacheKey, new long[]{now + CACHE_TTL_MS});
        resolveResult.put(cacheKey, result);
        return result;
    }

    /**
     * L2 查询: factory_module_configs.role_module_override JSONB.
     * 返回 normalized level (rw/r/w/-) 或 null (未配置).
     */
    private String resolveLayer2(String factoryId, String roleCode, String moduleCode) {
        try {
            List<FactoryModuleConfig> configs = factoryConfigRepo.findByFactoryIdAndConfigVersion(factoryId, 1);
            for (FactoryModuleConfig cfg : configs) {
                Map<String, Map<String, String>> override = cfg.getRoleModuleOverride();
                if (override == null || override.isEmpty()) continue;
                Map<String, String> roleOverrides = override.get(roleCode);
                if (roleOverrides == null) continue;
                String level = roleOverrides.get(moduleCode);
                if (level != null) return normalizeLevel(level);
            }
        } catch (Exception e) {
            log.warn("L2 override resolve failed for factory={}, role={}, module={}: {}",
                factoryId, roleCode, moduleCode, e.getMessage());
        }
        return null;
    }

    /**
     * L1 查询: platform_role_permissions 表.
     * 返回 legacy-format level (read_write/read/write/none) 或 null.
     */
    private String resolveLayer1(String roleCode, String moduleCode) {
        try {
            return platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode)
                .map(PlatformRolePermission::getPermissionLevel)
                .map(this::denormalizeLevel)  // DB 存 rw/r/w/-, checkAction 用 legacy format
                .orElse(null);
        } catch (Exception e) {
            log.warn("L1 resolve failed for role={}, module={}: {}", roleCode, moduleCode, e.getMessage());
            return null;
        }
    }

    /**
     * Normalize legacy level (read_write/read/write/none) → new (rw/r/w/-).
     */
    private String normalizeLevel(String level) {
        if (level == null) return null;
        switch (level) {
            case "read_write": return "rw";
            case "read": return "r";
            case "write": return "w";
            case "none": return "-";
            default: return level;  // already normalized (rw/r/w/-)
        }
    }

    /**
     * Denormalize new level (rw/r/w/-) → legacy (read_write/read/write/none)
     * 用于与现有 checkAction 签名兼容.
     */
    private String denormalizeLevel(String level) {
        if (level == null) return null;
        switch (level) {
            case "rw": return "read_write";
            case "r": return "read";
            case "w": return "write";
            case "-": return "none";
            default: return level;  // already legacy
        }
    }

    /**
     * 基于 permType 和 action 判断是否允许.
     * permType 格式: read_write/read/write/none (legacy).
     */
    private boolean checkAction(String permType, String action) {
        if (permType == null || permType.equals("none") || permType.equals("-")) {
            return false;
        }

        // 检查操作权限
        switch (action) {
            case "read":
                return permType.contains("read");
            case "write":
                return permType.contains("write");
            case "create":
                // 创建/提交：需要写权限（write 或 read_write）
                return permType.contains("write");
            case "approve":
                // 审批：需要完整读写权限（仅 read_write）
                return permType.equals("read_write");
            case "read_write":
                // 明确要求完整读写权限
                return permType.equals("read_write");
            case "*":
                return true;
            default:
                return permType.equals("read_write");
        }
    }

    @Override
    public boolean hasAnyPermission(User user, String... permissionCodes) {
        if (permissionCodes == null || permissionCodes.length == 0) {
            return false;
        }
        for (String code : permissionCodes) {
            if (hasPermission(user, code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAllPermissions(User user, String... permissionCodes) {
        if (permissionCodes == null || permissionCodes.length == 0) {
            return false;
        }
        for (String code : permissionCodes) {
            if (!hasPermission(user, code)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<String> getUserPermissions(User user) {
        Set<String> permissions = new HashSet<>();
        if (user == null) {
            return permissions;
        }

        FactoryUserRole role = user.getRoleEnum();
        if (role == null) {
            return permissions;
        }

        Map<String, String> rolePerms = PERMISSION_MATRIX.get(role);
        if (rolePerms == null) {
            return permissions;
        }

        for (Map.Entry<String, String> entry : rolePerms.entrySet()) {
            String module = entry.getKey();
            String permType = entry.getValue();

            if (permType.contains("read")) {
                permissions.add(module + ":read");
            }
            if (permType.contains("write")) {
                permissions.add(module + ":write");
            }
        }

        return permissions;
    }

    @Override
    public List<String> getAccessibleModules(User user) {
        if (user == null) {
            return Collections.emptyList();
        }

        FactoryUserRole role = user.getRoleEnum();
        if (role == null) {
            return Collections.emptyList();
        }

        Map<String, String> rolePerms = PERMISSION_MATRIX.get(role);
        if (rolePerms == null) {
            return Collections.emptyList();
        }

        return rolePerms.entrySet().stream()
                .filter(e -> !e.getValue().equals("none"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public boolean canAccessModule(User user, String module) {
        if (user == null || module == null) {
            return false;
        }

        FactoryUserRole role = user.getRoleEnum();
        if (role == null || role == FactoryUserRole.unactivated) {
            return false;
        }

        if (role == FactoryUserRole.factory_super_admin) {
            return true;
        }

        Map<String, String> rolePerms = PERMISSION_MATRIX.get(role);
        if (rolePerms == null) {
            return false;
        }

        String permType = rolePerms.get(module);
        return permType != null && !permType.equals("none");
    }

    @Override
    public boolean canManageUser(User manager, User target) {
        if (manager == null || target == null) {
            return false;
        }
        return manager.canManage(target);
    }

    @Override
    public boolean canAccessFactory(User user, String factoryId) {
        if (user == null || factoryId == null) {
            return false;
        }

        // 用户只能访问自己所属工厂的数据
        return factoryId.equals(user.getFactoryId());
    }

    @Override
    public boolean matchPermission(String pattern, String permission) {
        if (pattern == null || permission == null) {
            return false;
        }

        // 完全匹配
        if (pattern.equals(permission)) {
            return true;
        }

        // 通配符匹配
        if (pattern.equals("*") || pattern.equals("*:*")) {
            return true;
        }

        String[] patternParts = pattern.split(":");
        String[] permParts = permission.split(":");

        if (patternParts.length != 2 || permParts.length != 2) {
            return false;
        }

        boolean moduleMatch = patternParts[0].equals("*") || patternParts[0].equals(permParts[0]);
        boolean actionMatch = patternParts[1].equals("*") || patternParts[1].equals(permParts[1]);

        return moduleMatch && actionMatch;
    }

    @Override
    public Set<String> getRolePermissions(FactoryUserRole role) {
        Set<String> permissions = new HashSet<>();
        if (role == null) {
            return permissions;
        }

        Map<String, String> rolePerms = PERMISSION_MATRIX.get(role);
        if (rolePerms == null) {
            return permissions;
        }

        for (Map.Entry<String, String> entry : rolePerms.entrySet()) {
            String module = entry.getKey();
            String permType = entry.getValue();

            if (permType.contains("read")) {
                permissions.add(module + ":read");
            }
            if (permType.contains("write")) {
                permissions.add(module + ":write");
            }
        }

        return permissions;
    }
}
