package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 仓库盘点控制器 (SP7 §4.1).
 *
 * <p>基础路径: {@code /api/mobile/{factoryId}/stocktakes}
 *
 * <p>端点列表:
 * <ol>
 *   <li>POST /stocktakes — 发起盘点任务</li>
 *   <li>GET  /stocktakes — 分页查询</li>
 *   <li>GET  /stocktakes/{id} — 详情</li>
 *   <li>PUT  /stocktakes/{id}/items — 批量更新明细实盘数</li>
 *   <li>POST /stocktakes/{id}/submit — 提交审批</li>
 *   <li>POST /stocktakes/{id}/approve — 审批通过</li>
 *   <li>POST /stocktakes/{id}/reject — 驳回</li>
 *   <li>POST /stocktakes/{id}/apply — 生效（写差异）</li>
 *   <li>GET  /stocktakes/{id}/diff-preview — 差异预览</li>
 * </ol>
 *
 * <p>⚠️ 角色检查用 {@code request.getAttribute("role")}，非 SecurityContext（C1 孪生坑）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/stocktakes")
@RequiredArgsConstructor
@Tag(name = "仓库盘点", description = "SP7 仓库盘点任务管理")
public class StocktakeController {

    private final FactoryStocktakeService stocktakeService;

    @PostMapping
    @Operation(summary = "发起盘点任务", description = "月底约束: 每月29日后才允许发起")
    public ApiResponse<StocktakeDTO> initiate(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Valid CreateStocktakeRequest req,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 发起盘点 factoryId={} warehouseId={}", factoryId, req.getWarehouseId());
        StocktakeDTO result = stocktakeService.initiate(factoryId, req, userId);
        return ApiResponse.success("盘点任务已发起", result);
    }

    @GetMapping("/initiate-constraint")
    @Operation(summary = "查询发起盘点的月底约束展示态",
               description = "fool-proof Rule 1: 边界必须在用户填表前展示, 供前端弹窗打开时立即展示" +
                       " (是否今天可发起 / 下次可发起日期), 不做任何校验, initiate() 仍是唯一 enforce 处")
    public ApiResponse<Map<String, Object>> getInitiateConstraint(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        return ApiResponse.success(stocktakeService.getInitiateConstraint());
    }

    @GetMapping
    @Operation(summary = "分页查询盘点任务", description = "支持按状态过滤")
    public ApiResponse<Page<StocktakeDTO>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态过滤") FactoryStocktake.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<StocktakeDTO> result = stocktakeService.list(factoryId, status, pageable);
        return ApiResponse.success(result);
    }

    @GetMapping("/{stocktakeId}")
    @Operation(summary = "查询盘点任务详情（含明细行）")
    public ApiResponse<StocktakeDTO> getDetail(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId) {
        StocktakeDTO result = stocktakeService.getDetail(stocktakeId, factoryId);
        return ApiResponse.success(result);
    }

    @PutMapping("/{stocktakeId}/items")
    @Operation(summary = "批量更新明细实盘数量", description = "可多次调用，幂等更新")
    public ApiResponse<Void> updateItems(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId,
            @RequestBody @Valid List<StocktakeItemUpdateDTO> items,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 更新盘点明细 stocktakeId={} itemCount={}", stocktakeId, items.size());
        stocktakeService.updateItems(stocktakeId, factoryId, items, userId);
        return ApiResponse.success("明细已更新", null);
    }

    @PostMapping("/{stocktakeId}/submit")
    @Operation(summary = "提交盘点任务审批", description = "COUNTING/INITIATED → PENDING_APPROVAL")
    public ApiResponse<Void> submit(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 提交盘点审批 stocktakeId={}", stocktakeId);
        stocktakeService.submit(stocktakeId, factoryId, userId);
        return ApiResponse.success("盘点任务已提交审批", null);
    }

    @PostMapping("/{stocktakeId}/approve")
    @Operation(summary = "审批通过盘点任务", description = "需要 FINANCE/FACTORY_SUPER_ADMIN 角色")
    public ApiResponse<Void> approve(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        String role = extractRole(request);
        log.info("SP7: 审批盘点通过 stocktakeId={} role={}", stocktakeId, role);
        Long expectedVersion = extractExpectedVersion(body);
        stocktakeService.approve(stocktakeId, factoryId, userId, role, expectedVersion);
        return ApiResponse.success("盘点任务已审批通过", null);
    }

    @PostMapping("/{stocktakeId}/reject")
    @Operation(summary = "驳回盘点任务", description = "需要 FINANCE/FACTORY_SUPER_ADMIN 角色")
    public ApiResponse<Void> reject(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        String role = extractRole(request);
        String reason = body.getOrDefault("reason", "");
        log.info("SP7: 驳回盘点 stocktakeId={} role={}", stocktakeId, role);
        stocktakeService.reject(stocktakeId, factoryId, reason, userId, role);
        return ApiResponse.success("盘点任务已驳回", null);
    }

    @PostMapping("/{stocktakeId}/apply")
    @Operation(summary = "生效盘点（写差异到库存）", description = "APPROVED → APPLIED，幂等：重复调用返 409")
    public ApiResponse<Void> apply(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Long userId = extractUserId(request);
        log.info("SP7: 盘点生效 stocktakeId={}", stocktakeId);
        stocktakeService.apply(stocktakeId, factoryId, userId, extractExpectedVersion(body));
        return ApiResponse.success("盘点已应用完成；仅存在差异时调整库存", null);
    }

    @GetMapping("/{stocktakeId}/diff-preview")
    @Operation(summary = "预览盘点差异", description = "只读，不写库存，用于生效前确认")
    public ApiResponse<StocktakeDiffPreviewDTO> previewDiff(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "盘点任务ID") String stocktakeId) {
        StocktakeDiffPreviewDTO result = stocktakeService.previewDiff(stocktakeId, factoryId);
        return ApiResponse.success(result);
    }

    // -------------------------------------------------------
    // private helpers (C1孪生坑: 角色从 request.getAttribute 取，非 SecurityContext)
    // -------------------------------------------------------

    private Long extractUserId(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj instanceof Long) return (Long) userIdObj;
        if (userIdObj instanceof Number) return ((Number) userIdObj).longValue();
        return 0L; // fallback — JWT 拦截器已校验过 token 合法性
    }

    private String extractRole(HttpServletRequest request) {
        String role = (String) request.getAttribute("role");
        if (role == null || role.isBlank()) {
            throw new BusinessException(401, "无法获取角色信息，请重新登录");
        }
        return role;
    }

    private Long extractExpectedVersion(Map<String, Object> body) {
        if (body == null || body.get("expectedVersion") == null) {
            throw new BusinessException(400, "expectedVersion 为必填字段，请刷新盘点数据后重试")
                    .withCode("STOCKTAKE_VERSION_REQUIRED");
        }
        Object value = body.get("expectedVersion");
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "expectedVersion 必须是整数")
                    .withCode("STOCKTAKE_VERSION_INVALID");
        }
    }
}
