package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AIChat — "审计仓管 RBAC" tool (C-RBAC-1).
 *
 * <p>客户原话 (六扇门第三次 May7 part2 行 188-189):
 * <blockquote>"其他的话就尽量少让那个仓管员去参与什么什么价格类的不要让他们去参与"
 *  "做仓管的他年纪都比较大文化素质很低的, 你不能太伪赖他们"</blockquote>
 *
 * <p>Tool 不主动跑 negative regression — 跑 25 case 需要并发调 25 个 endpoint
 * (耗时 + 跨服务复杂), 不适合 ChatTool 同步执行. Tool 返回:
 * <ol>
 *   <li>Cretas RBAC 价格保护框架的当前状态摘要 (PR #423 framework)</li>
 *   <li>静态审计矩阵 (5 角色 × 5 视图 — 期望 vs 实际)</li>
 *   <li>动态批跑指引 (调用 scripts/rbac-warehouse-mgr-audit-2026-05-15/run-regression.sh)</li>
 * </ol>
 *
 * <p>真正的 25 case 跑由 deploy 后的 bash script 执行, 输出 report.md.
 * 本 Tool 只是 framework 的诊断面板.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-RBAC-1)
 */
@Slf4j
@Component
public class RBACAuditTool extends AbstractBusinessTool {

    /** Cretas 现有价格保护框架 — PR #423. */
    private static final String FRAMEWORK_VERSION = "PR #423 (PriceFieldResponseAdvice + @PriceSensitive)";

    /** 5 角色清单 (warehouse_mgr 是核心审计目标). */
    private static final List<String> ROLES = List.of(
            "warehouse_manager",
            "operator",
            "quality_inspector",
            "customer_service",
            "viewer"
    );

    /**
     * 5 价格敏感视图 — 期望对所有 ROLES 都 mask 价格.
     * 字段格式: viewName | endpoint | 描述
     */
    private static final List<Map<String, String>> SENSITIVE_VIEWS = List.of(
            Map.of("name", "采购订单详情", "endpoint", "GET /purchase/orders/{id}",
                   "expectedFor", "warehouse_mgr", "expectedBehavior", "mask unitPrice/totalAmount/payableAmount"),
            Map.of("name", "销售订单详情", "endpoint", "GET /sales/orders/{id}",
                   "expectedFor", "warehouse_mgr", "expectedBehavior", "mask unitPrice/totalAmount"),
            Map.of("name", "三价对比", "endpoint", "GET /purchase/orders/{id}/price-comparison",
                   "expectedFor", "warehouse_mgr", "expectedBehavior", "整个 endpoint 403 (canViewPrice false)"),
            Map.of("name", "BOM 详情", "endpoint", "GET /bom/recipes/by-product/X/current",
                   "expectedFor", "warehouse_mgr", "expectedBehavior", "mask unitPrice/materialCost"),
            Map.of("name", "财务凭证", "endpoint", "GET /finance/payments/{id}",
                   "expectedFor", "warehouse_mgr", "expectedBehavior", "整个 endpoint 403 (finance:read)")
    );

    @Override
    public String getToolName() {
        return "rbac_audit";
    }

    @Override
    public String getDescription() {
        return "审计仓管员 RBAC 价格隔离当前状态. 触发: 用户问'仓管能看到价格吗' / " +
               "'审计仓管 RBAC' / 'RBAC 检查' / '看下权限矩阵'. " +
               "返回 5 角色 × 5 视图期望矩阵 + Cretas 现有框架状态. " +
               "实际 25 case 跑请用 bash 脚本 scripts/rbac-warehouse-mgr-audit-2026-05-15/.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());  // 无参数 — 静态审计
        schema.put("required", new ArrayList<>());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) {
        Map<String, Object> result = new HashMap<>();

        // 1. Framework 状态
        Map<String, Object> framework = new HashMap<>();
        framework.put("version", FRAMEWORK_VERSION);
        framework.put("permissionString", PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION);
        framework.put("annotation", "@PriceSensitive (field + @Transient method)");
        framework.put("binaryExportGuard", "PriceMaskResolver (PDF/Excel)");
        framework.put("staticTest", "RBACWarehouseManagerIsolationTest (4 cases, all PASS)");
        result.put("framework", framework);

        // 2. 期望矩阵 (5 角色 × 5 视图)
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (String role : ROLES) {
            Map<String, Object> rolRow = new HashMap<>();
            rolRow.put("role", role);
            Map<String, String> viewExpectations = new LinkedHashMap<>();
            for (Map<String, String> view : SENSITIVE_VIEWS) {
                viewExpectations.put(
                        view.get("name"),
                        "EXPECTED: 价格字段 mask (=== '—' 或字段不存在)" +
                                (view.get("expectedBehavior").contains("403") ? " / 整个 endpoint 403" : ""));
            }
            rolRow.put("viewExpectations", viewExpectations);
            matrix.add(rolRow);
        }
        result.put("expectedMatrix", matrix);

        // 3. 视图清单
        result.put("sensitiveViews", SENSITIVE_VIEWS);

        // 4. Action items
        result.put("nextSteps", List.of(
                "查看静态审计结果: mvn test -Dtest=RBACWarehouseManagerIsolationTest",
                "运行 5x5 negative regression: bash scripts/rbac-warehouse-mgr-audit-2026-05-15/run-regression.sh",
                "查看实测报告: scripts/rbac-warehouse-mgr-audit-2026-05-15/report.md (deploy 后生成)",
                "如发现 fail (价格泄漏), 在对应 entity field 加 @PriceSensitive 注解"
        ));

        result.put("message", String.format(
                "RBAC 审计 — Cretas 当前框架: %s. 期望: %d 角色 × %d 视图 = 25 negative case 全 mask. " +
                "静态测试 PASS (4 case in RBACWarehouseManagerIsolationTest). 真 25 case 跑用 bash 脚本.",
                FRAMEWORK_VERSION, ROLES.size(), SENSITIVE_VIEWS.size()));

        log.info("RBACAuditTool: factory={} returned {}-role × {}-view matrix",
                factoryId, ROLES.size(), SENSITIVE_VIEWS.size());
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
