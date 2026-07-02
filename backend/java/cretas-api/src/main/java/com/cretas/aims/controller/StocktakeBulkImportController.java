package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.factory.StocktakeBulkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 盘点批量导入控制器（张权客户需求 2026-07-02：月度整仓盘点提效）。
 *
 * <p>基础路径: {@code /api/mobile/{factoryId}/stocktakes/bulk-import}
 *
 * <p>流程（防呆：先预览报告→再确认落库，复用既有盘点链路）：
 * <ol>
 *   <li>GET  /template?warehouseId= — 导出当前账面库存填报模板（实盘列留空）</li>
 *   <li>POST /preview — 上传填好的表，read-only 比对出盘盈/盘亏报告 + 匹配失败明细</li>
 *   <li>POST /confirm — 确认后创建盘点任务 + 回填实盘（复用 initiate + updateItems），
 *       后续走既有 提交审批 → 生效 流程校正库存 + 会计留痕</li>
 * </ol>
 *
 * <p>⚠️ 角色/用户从 {@code request.getAttribute} 取（C1 孪生坑，与 {@link StocktakeController} 一致）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/stocktakes/bulk-import")
@RequiredArgsConstructor
@Tag(name = "盘点批量导入", description = "月度整仓盘点：导出模板→填实盘→导入比对→确认校正")
public class StocktakeBulkImportController {

    private final StocktakeBulkImportService bulkImportService;

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10MB

    @GetMapping("/template")
    @Operation(summary = "导出盘点填报模板", description = "按仓库导出当前账面库存，实盘数量列留空待填")
    public void exportTemplate(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "仓库ID") String warehouseId,
            HttpServletResponse response) throws IOException {
        log.info("盘点批量导入: 导出模板 factoryId={} warehouseId={}", factoryId, warehouseId);
        byte[] data = bulkImportService.exportTemplate(factoryId, warehouseId);
        String filename = URLEncoder.encode("库存盘点模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"stocktake-template.xlsx\"; filename*=UTF-8''" + filename);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    @PostMapping("/preview")
    @Operation(summary = "预览导入比对", description = "read-only：不落库，返回盘盈/盘亏报告 + 匹配失败明细")
    public ApiResponse<StocktakeBulkImportPreviewDTO> preview(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "仓库ID") String warehouseId,
            @RequestParam("file") MultipartFile file) throws IOException {
        validateFile(file);
        log.info("盘点批量导入: 预览 factoryId={} warehouseId={} file={}",
                factoryId, warehouseId, file.getOriginalFilename());
        StocktakeBulkImportPreviewDTO result = bulkImportService.preview(factoryId, warehouseId, file.getInputStream());
        return ApiResponse.success("比对完成", result);
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认导入",
            description = "创建盘点任务并回填实盘（复用现有链路），返回 stocktakeId 供后续提交审批→生效。"
                    + "openingMode=true 时按期初建账过账（借1403原材料/贷4001实收资本），否则常规盘盈/盘亏。")
    public ApiResponse<StocktakeBulkImportPreviewDTO> confirm(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "仓库ID") String warehouseId,
            @RequestParam @Parameter(description = "盘点月份 YYYY-MM") String periodMonth,
            @RequestParam(required = false) @Parameter(description = "备注") String notes,
            @RequestParam(defaultValue = "false") @Parameter(description = "是否期初建账导入") boolean openingMode,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        validateFile(file);
        Long userId = extractUserId(request);
        FactoryStocktake.ImportMode mode = openingMode
                ? FactoryStocktake.ImportMode.OPENING
                : FactoryStocktake.ImportMode.NORMAL;
        log.info("盘点批量导入: 确认 factoryId={} warehouseId={} periodMonth={} mode={} file={}",
                factoryId, warehouseId, periodMonth, mode, file.getOriginalFilename());
        StocktakeBulkImportPreviewDTO result = bulkImportService.confirm(
                factoryId, warehouseId, periodMonth, notes, mode, file.getInputStream(), userId);
        String msg = openingMode
                ? "期初建账盘点已创建，请前往提交审批后生效（将过账期初权益凭证）"
                : "盘点任务已创建，请前往提交审批后生效";
        return ApiResponse.success(msg, result);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要导入的 Excel 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".xlsx") || name.toLowerCase().endsWith(".xls"))) {
            throw new BusinessException(400, "仅支持 .xlsx / .xls 格式，请使用系统导出的模板");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(400, "文件过大（>10MB），请检查文件");
        }
    }

    private Long extractUserId(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj instanceof Long) return (Long) userIdObj;
        if (userIdObj instanceof Number) return ((Number) userIdObj).longValue();
        return 0L; // JWT 拦截器已校验 token 合法性（与 StocktakeController 一致）
    }
}
