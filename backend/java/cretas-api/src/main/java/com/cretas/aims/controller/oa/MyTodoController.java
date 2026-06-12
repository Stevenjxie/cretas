package com.cretas.aims.controller.oa;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.service.oa.MyTodoAggregatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OA 我的待办聚合 REST 控制器 — RN OA 待办中心。
 *
 * <p>提供两个轻量端点:
 * <ul>
 *   <li>{@code GET /api/mobile/{factoryId}/my-todos} — 按调用方角色返回待办列表</li>
 *   <li>{@code GET /api/mobile/{factoryId}/my-todos/count} — 返回待办总数（RN 徽标用）</li>
 * </ul>
 *
 * <p>角色从 {@code request.getAttribute("role")} 取（JwtAuthInterceptor 在拦截器层设置）。
 * **禁止**使用 SecurityContext — 该项目 SecurityContext 永远为空（C1 孪生坑）。
 *
 * <p>角色映射（聚合层决定，Controller 仅透传）:
 * <ul>
 *   <li>finance_manager → {采购财审, 销售财审, 价格异常, 盘点审批}</li>
 *   <li>cashier → {已审付款}</li>
 *   <li>其他 → 空列表（HTTP 200，非 403）</li>
 * </ul>
 *
 * <p>🔒 红线: 权限/角色过滤在 {@link MyTodoAggregatorService} 层实现；
 * 本 Controller 不做额外过滤。0 改现有审批端点。
 *
 * @since 2026-06-12 (OA 待办聚合 B3)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/my-todos")
@RequiredArgsConstructor
@Tag(name = "OA 我的待办", description = "RN OA 待办中心 — 财务/出纳待审批聚合")
@RequirePermission({"finance:read", "finance:read_write"})
public class MyTodoController {

    private final MyTodoAggregatorService myTodoAggregatorService;

    // ─── 待办列表 ──────────────────────────────────────────────────────────────

    /**
     * 返回调用方角色对应的全部待办项，按提交时间倒序。
     *
     * <p>角色无匹配（如 operator）时返回空列表（200 OK），非 403。
     * 各域查询 fail-soft: 单域异常不影响其他域正常返回。
     *
     * @param factoryId 工厂 ID（租户隔离）
     * @param request   HTTP 请求（用于从 attribute 读取 role）
     * @return 待办列表（可为空，从不为 null）
     */
    @GetMapping
    @Operation(
            summary = "获取我的待办列表",
            description = "按调用方角色（finance_manager / cashier）返回对应域的待审批待办。"
                    + " fail-soft: 单域查询异常不影响其他域。"
                    + " 角色无匹配时返回空列表（200），而非 403。")
    public ApiResponse<List<TodoItemDTO>> listMyTodos(
            @PathVariable @NotBlank String factoryId,
            HttpServletRequest request) {
        // ⚠️ C1 孪生坑规避: 角色必须从 request attribute 取，SecurityContext 永空
        String callerRole = (String) request.getAttribute("role");
        log.debug("[MyTodo] listTodos factoryId={} callerRole={}", factoryId, callerRole);

        List<TodoItemDTO> todos = myTodoAggregatorService.listTodos(factoryId, callerRole);
        return ApiResponse.success("待办查询成功", todos);
    }

    // ─── 待办数量（徽标） ─────────────────────────────────────────────────────

    /**
     * 返回调用方待办总数，用于 RN 导航 tab 徽标显示。
     *
     * <p>等价于 {@code listMyTodos().data.size()}，独立端点避免 RN 为拿徽标反序列化整个列表。
     *
     * @param factoryId 工厂 ID
     * @param request   HTTP 请求（读取 role）
     * @return 待办总数（0 表示无待办）
     */
    @GetMapping("/count")
    @Operation(
            summary = "获取我的待办数量",
            description = "返回调用方待办总数，用于 RN 导航 tab 的数字徽标（badge）显示。"
                    + " fail-soft 同列表端点。角色无匹配时返回 0。")
    public ApiResponse<Integer> countMyTodos(
            @PathVariable @NotBlank String factoryId,
            HttpServletRequest request) {
        String callerRole = (String) request.getAttribute("role");
        log.debug("[MyTodo] countTodos factoryId={} callerRole={}", factoryId, callerRole);

        int count = myTodoAggregatorService.countTodos(factoryId, callerRole);
        return ApiResponse.success("待办数量查询成功", count);
    }
}
