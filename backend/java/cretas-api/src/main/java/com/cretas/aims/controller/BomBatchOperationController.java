package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.BatchAddRequest;
import com.cretas.aims.dto.bom.BatchDeleteRequest;
import com.cretas.aims.dto.bom.BatchModifyRequest;
import com.cretas.aims.dto.bom.BatchReplaceRequest;
import com.cretas.aims.dto.bom.BatchResult;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.bom.BomBatchOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * BOM 批量操作 REST Controller (Sprint 6 W4-C / M-BOM-VER-1).
 *
 * <p>路径前缀: {@code /api/mobile/{factoryId}/bom/batch}.
 *
 * <p>4 批量操作: modify / replace / delete / add. 每个操作 auto-create 1 ECN DRAFT + N BomVersion
 * DRAFT (1 per affected recipe). 不直接 mutate BomRecipe / BomRecipeItem — 走 ECN 审批后 apply.
 *
 * <p>RBAC: Write 方法沿用 BomController 模式 —
 * {@code @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})}.
 *
 * @author Cretas Team / Sprint 6 W4-C
 * @since 2026-05-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/batch")
@RequiredArgsConstructor
@Tag(name = "BOM 批量操作 (M-BOM-VER-1)",
        description = "4 批量操作: modify/replace/delete/add. 每个操作产生 1 ECN DRAFT + N BomVersion DRAFT")
@RequireModule("bom")
public class BomBatchOperationController {

    private final BomBatchOperationService batchService;

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/modify")
    @Operation(summary = "批量修改 — 多 BOM 同一物料的 standardQuantity (+unit) 全改",
            description = "Sprint 6 W4-C — 自动 createDraft N BomVersion + 1 ECN DRAFT, 不直接 mutate")
    public ApiResponse<BatchResult> batchModify(
            @PathVariable String factoryId,
            @Valid @RequestBody BatchModifyRequest req) {
        // factoryId from path takes precedence over body (multi-tenant安全).
        req.setFactoryId(factoryId);
        return ApiResponse.success(batchService.batchModify(req));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/replace")
    @Operation(summary = "批量替换 — 物料 A → B 跨多 BOM",
            description = "Sprint 6 W4-C — preserveQuantity 默认 true (只换物料引用)")
    public ApiResponse<BatchResult> batchReplace(
            @PathVariable String factoryId,
            @Valid @RequestBody BatchReplaceRequest req) {
        req.setFactoryId(factoryId);
        return ApiResponse.success(batchService.batchReplace(req));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/delete")
    @Operation(summary = "批量删除 — 多 BOM 同一物料 item 全删",
            description = "Sprint 6 W4-C — 走 ECN 审批后才真删, draft 阶段只是 snapshot")
    public ApiResponse<BatchResult> batchDelete(
            @PathVariable String factoryId,
            @Valid @RequestBody BatchDeleteRequest req) {
        req.setFactoryId(factoryId);
        return ApiResponse.success(batchService.batchDelete(req));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/add")
    @Operation(summary = "批量新增 — 多 BOM 同时加一物料 item",
            description = "Sprint 6 W4-C — skipIfAlreadyPresent 默认 true (含此 material 的 recipe 跳过)")
    public ApiResponse<BatchResult> batchAdd(
            @PathVariable String factoryId,
            @Valid @RequestBody BatchAddRequest req) {
        req.setFactoryId(factoryId);
        return ApiResponse.success(batchService.batchAdd(req));
    }
}
