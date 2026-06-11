package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.factory.CreateSaltedDeductionRequest;
import com.cretas.aims.dto.factory.SaltedReportDTO;
import com.cretas.aims.entity.factory.SaltedWarehouseDeduction;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.factory.SaltedWarehouseService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 盐化仓独立扣量 & 报表接口 (SP7 F11).
 *
 * <p>端点一览:
 * <pre>
 * POST  /api/mobile/{factoryId}/factory/salted-deductions          — 创建扣量记录
 * GET   /api/mobile/{factoryId}/factory/salted-deductions          — 扣量记录列表
 * GET   /api/mobile/{factoryId}/factory/salted-deductions/report   — 盐化报表 (独立口径)
 * </pre>
 *
 * <p>权限: 写操作 {@code warehouse:read_write}; 读操作 {@code warehouse:read}。
 * "谁盐化谁建" (转录 [91:51]) — 盐化业务销售也拥有 warehouse:read_write 权限。
 *
 * <p><b>通用仓兜底保留</b>: 本 Controller 只处理 SALTED 专属路径, 不修改任何通用仓逻辑。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/factory/salted-deductions")
@RequiredArgsConstructor
@Tag(name = "盐化仓管理", description = "盐化(代加工)独立扣量记录与独立报表 (SP7 F11)")
public class SaltedWarehouseController {

    private final SaltedWarehouseService saltedService;
    private final MobileService mobileService;

    /**
     * 创建盐化仓扣量记录 (并同步扣减 MaterialBatch.usedQuantity).
     *
     * <p>防呆 (Rule 1+4): 服务层校验 SALTED 仓类型 + 库存上限 + 幂等去重。
     * 前端应先 GET /inventory/by-warehouse?warehouseId= 展示当前可扣上限。
     */
    @PostMapping
    @Operation(summary = "创建盐化仓扣量记录",
               description = "盐化单独扣减，不走销售报表口径 (SP7 F11 — 转录 [91:31]×4次)")
    @RequirePermission("warehouse:read_write")
    public ApiResponse<SaltedWarehouseDeduction> createDeduction(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateSaltedDeductionRequest request) {

        Long userId = extractUserId(authorization);
        log.info("盐化扣量请求: factory={}, warehouse={}, batch={}, qty={}, orderNo={}",
                factoryId, request.getWarehouseId(), request.getMaterialBatchId(),
                request.getQuantity(), request.getOrderNumber());

        SaltedWarehouseDeduction created = saltedService.createDeduction(factoryId, request, userId);
        return ApiResponse.success("盐化扣量成功", created);
    }

    /**
     * 查询盐化仓扣量记录列表 (按仓库+日期区间).
     *
     * @param warehouseId 盐化仓 ID (必填)
     * @param startDate   开始日期 (yyyy-MM-dd)
     * @param endDate     结束日期 (yyyy-MM-dd)
     */
    @GetMapping
    @Operation(summary = "盐化仓扣量记录列表",
               description = "查询指定盐化仓在日期区间内的扣量记录")
    @RequirePermission({"warehouse:read_write", "warehouse:read"})
    public ApiResponse<List<SaltedWarehouseDeduction>> listDeductions(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String warehouseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<SaltedWarehouseDeduction> list = saltedService.listDeductions(factoryId, warehouseId, startDate, endDate);
        return ApiResponse.success("查询成功", list);
    }

    /**
     * 盐化仓报表 (独立口径，不混入正常销售报表).
     *
     * <p>转录 [91:39]-[91:40]: "你盐化指标就走盐化的报表指标"。
     * 期中出库盘账勾稽: totalQuantity × unitPrice = totalAmount → 账平验证。
     *
     * @param warehouseId 盐化仓 ID (null = 工厂下所有盐化仓汇总)
     * @param startDate   开始日期 (yyyy-MM-dd)
     * @param endDate     结束日期 (yyyy-MM-dd)
     */
    @GetMapping("/report")
    @Operation(summary = "盐化仓报表 (独立口径)",
               description = "盐化出量报表，独立指标，不混入销售报表 — 转录 [91:39]-[91:40]")
    @RequirePermission({"warehouse:read_write", "warehouse:read"})
    public ApiResponse<SaltedReportDTO> getReport(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String warehouseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        SaltedReportDTO report = saltedService.getReport(factoryId, warehouseId, startDate, endDate);
        return ApiResponse.success("盐化报表查询成功", report);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}
