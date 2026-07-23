package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.UpdateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.SupplierStatusChangeRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.supplier.SupplierImportService;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 供应商管理控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
/**
 * Bug #318 fix: method-level @RequirePermission on write methods only.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/suppliers")
@RequiredArgsConstructor
@Tag(name = "供应商管理", description = "供应商管理相关接口，包括供应商的创建、查询、更新、删除，状态管理、评级评分、信用额度管理，供货历史统计、Excel导入导出等功能")
public class SupplierController {

    private final SupplierService supplierService;
    private final MobileService mobileService;
    private final PriceMaskResolver priceMaskResolver;
    private final SupplierImportService supplierImportService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 创建供应商
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "创建供应商", description = "创建新的供应商信息，需要提供供应商名称、联系方式等基本信息")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "CREATE",
            entityType = "Supplier", summary = "'创建供应商 ' + #request.name")
    public ApiResponse<SupplierDTO> createSupplier(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true)
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateSupplierRequest request) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("创建供应商: factoryId={}, name={}", factoryId, request.getName());
        SupplierDTO supplier = supplierService.createSupplier(factoryId, request, userId.longValue());
        return ApiResponse.success("供应商创建成功", supplier);
    }

    /**
     * 更新供应商
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/{supplierId}")
    @Operation(summary = "更新供应商", description = "更新指定供应商的信息 (partial update: 所有字段可选)")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "UPDATE",
            entityType = "Supplier", entityIdParam = "supplierId")
    public ApiResponse<SupplierDTO> updateSupplier(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {

        log.info("更新供应商: factoryId={}, supplierId={}", factoryId, supplierId);
        SupplierDTO supplier = supplierService.updateSupplier(factoryId, supplierId, request);
        return ApiResponse.success("供应商更新成功", supplier);
    }

    /**
     * 删除尚无任何业务历史的误建供应商。
     *
     * <p>Web 已以状态生命周期替代常规删除，但 RN 供应商管理和 SupplierDeleteTool 仍消费该接口。
     * Service 会拒绝删除已有物料批次或采购历史的供应商，因此暂时保留的是受控纠错能力，不是
     * 可随 Web 删除按钮一起移除的冗余写路径。</p>
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/{supplierId}")
    @Operation(summary = "删除供应商", description = "删除指定的供应商记录")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "DELETE",
            entityType = "Supplier", entityIdParam = "supplierId")
    public ApiResponse<Void> deleteSupplier(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId) {

        log.info("删除供应商: factoryId={}, supplierId={}", factoryId, supplierId);
        supplierService.deleteSupplier(factoryId, supplierId);
        return ApiResponse.success("供应商删除成功", null);
    }

    /**
     * 获取供应商详情
     */
    @GetMapping("/{supplierId}")
    @Operation(summary = "获取供应商详情", description = "根据ID获取供应商的详细信息")
    public ApiResponse<SupplierDTO> getSupplierById(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId) {

        SupplierDTO supplier = supplierService.getSupplierById(factoryId, supplierId);
        return ApiResponse.success(supplier);
    }

    /**
     * 获取供应商列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取供应商列表（分页）", description = "分页查询工厂的供应商列表")
    public ApiResponse<PageResponse<SupplierDTO>> getSupplierList(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Valid PageRequest pageRequest) {

        PageResponse<SupplierDTO> response = supplierService.getSupplierList(factoryId, pageRequest);
        return ApiResponse.success(response);
    }

    /**
     * 获取活跃供应商列表
     */
    @GetMapping("/active")
    @Operation(summary = "获取活跃供应商列表", description = "获取工厂所有状态为活跃的供应商")
    public ApiResponse<List<SupplierDTO>> getActiveSuppliers(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        List<SupplierDTO> suppliers = supplierService.getActiveSuppliers(factoryId);
        return ApiResponse.success(suppliers);
    }

    /**
     * 搜索供应商
     */
    @GetMapping("/search")
    @Operation(summary = "搜索供应商", description = "根据关键词搜索供应商，支持按名称、代码模糊匹配")
    public ApiResponse<List<SupplierDTO>> searchSuppliers(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "搜索关键词", example = "海鲜", required = true)
            @RequestParam @NotBlank String keyword) {

        List<SupplierDTO> suppliers = supplierService.searchSuppliersByName(factoryId, keyword);
        return ApiResponse.success(suppliers);
    }

    /**
     * 按材料类型获取供应商
     */
    @GetMapping("/by-material")
    @Operation(summary = "按材料类型获取供应商", description = "获取提供指定材料类型的所有供应商")
    public ApiResponse<List<SupplierDTO>> getSuppliersByMaterialType(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "材料类型", example = "带鱼", required = true)
            @RequestParam @NotBlank String materialType) {

        List<SupplierDTO> suppliers = supplierService.getSuppliersByMaterialType(factoryId, materialType);
        return ApiResponse.success(suppliers);
    }

    /**
     * 切换供应商状态
     * 支持两种参数格式：
     * 1. URL参数: PUT /{supplierId}/status?isActive=true
     * 2. Request Body: PUT /{supplierId}/status {"isActive": true}
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/{supplierId}/status")
    @Operation(summary = "切换供应商状态", description = "启用或禁用供应商，支持URL参数或请求体两种方式传递状态")
    public ApiResponse<SupplierDTO> toggleSupplierStatus(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId,
            @Parameter(description = "激活状态 (URL参数)", example = "true")
            @RequestParam(required = false) Boolean isActive,
            @Parameter(description = "激活状态 (Body参数)", example = "{\"isActive\": true}")
            @RequestBody(required = false) Map<String, Boolean> body) {

        // 优先使用URL参数，如果没有则从body获取
        Boolean activeStatus = isActive;
        if (activeStatus == null && body != null) {
            activeStatus = body.get("isActive");
        }
        if (activeStatus == null) {
            throw new BusinessException(400, "参数错误: isActive 是必需的").withHint("请在请求体中提供 isActive 字段").withHintTarget("isActive");
        }

        log.info("切换供应商状态: factoryId={}, supplierId={}, isActive={}",
                factoryId, supplierId, activeStatus);
        SupplierDTO supplier = supplierService.toggleSupplierStatus(factoryId, supplierId, activeStatus);
        return ApiResponse.success("供应商状态更新成功", supplier);
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/{supplierId}/lifecycle")
    @Operation(summary = "暂停或恢复供应商合作", description = "需要原因和乐观锁版本；重复同状态请求为幂等 no-op")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "UPDATE",
            entityType = "Supplier", entityIdParam = "supplierId")
    public ApiResponse<SupplierDTO> changeSupplierLifecycle(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String supplierId,
            @Valid @RequestBody SupplierStatusChangeRequest request) {
        SupplierDTO supplier = supplierService.changeSupplierStatus(factoryId, supplierId,
                request.getIsActive(), request.getReason(), request.getVersion());
        return ApiResponse.success(Boolean.TRUE.equals(request.getIsActive()) ? "供应商已恢复合作" : "供应商已暂停合作", supplier);
    }

    /**
     * 更新供应商评级
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/{supplierId}/rating")
    @Operation(summary = "更新供应商评级", description = "为供应商设置评级分数，1-5分，可附加评级说明")
    public ApiResponse<SupplierDTO> updateSupplierRating(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId,
            @Parameter(description = "评级(1-5)", example = "4", required = true)
            @RequestParam @NotNull Integer rating,
            @Parameter(description = "评级说明", example = "交货准时，质量稳定")
            @RequestParam(required = false) String notes) {

        log.info("更新供应商评级: factoryId={}, supplierId={}, rating={}",
                factoryId, supplierId, rating);
        SupplierDTO supplier = supplierService.updateSupplierRating(factoryId, supplierId, rating, notes);
        return ApiResponse.success("供应商评级更新成功", supplier);
    }

    /**
     * 更新供应商信用额度
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/{supplierId}/credit-limit")
    @Operation(summary = "更新供应商信用额度", description = "设置供应商的最大信用额度，单位为元")
    public ApiResponse<SupplierDTO> updateCreditLimit(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId,
            @Parameter(description = "信用额度", example = "100000.00", required = true)
            @RequestParam @NotNull BigDecimal creditLimit) {

        log.info("更新供应商信用额度: factoryId={}, supplierId={}, creditLimit={}",
                factoryId, supplierId, creditLimit);
        SupplierDTO supplier = supplierService.updateCreditLimit(factoryId, supplierId, creditLimit);
        return ApiResponse.success("信用额度更新成功", supplier);
    }

    /**
     * 获取供应商统计信息
     */
    @GetMapping("/{supplierId}/statistics")
    @Operation(summary = "获取供应商统计信息", description = "获取供应商的供货统计，包括供货次数、总金额、质量评分等")
    public ApiResponse<Map<String, Object>> getSupplierStatistics(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId) {

        Map<String, Object> statistics = supplierService.getSupplierStatistics(factoryId, supplierId);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取供应商供货历史
     */
    @GetMapping("/{supplierId}/history")
    @Operation(summary = "获取供应商供货历史", description = "获取供应商的历史供货记录列表")
    public ApiResponse<List<Map<String, Object>>> getSupplierHistory(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商ID", example = "SUP-001", required = true)
            @PathVariable @NotBlank String supplierId) {

        List<Map<String, Object>> history = supplierService.getSupplierHistory(factoryId, supplierId);
        return ApiResponse.success(history);
    }

    /**
     * 检查供应商代码是否存在
     */
    @GetMapping("/check-code")
    @Operation(summary = "检查供应商代码是否存在", description = "验证供应商代码在当前工厂是否已被使用")
    public ApiResponse<Boolean> checkSupplierCode(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "供应商代码", example = "SUP-NEW-001", required = true)
            @RequestParam @NotBlank String supplierCode) {

        boolean exists = supplierService.checkSupplierCodeExists(factoryId, supplierCode);
        return ApiResponse.success(exists);
    }

    /**
     * 导出供应商列表
     */
    @GetMapping("/export")
    @Operation(summary = "导出供应商列表",
            description = "将工厂所有供应商信息导出为Excel文件。"
                    + "RBAC: 无 procurement:price:view 权限的角色, Excel 中 信用额度 列显示 '—'.")
    public ResponseEntity<byte[]> exportSupplierList(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization) {

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mirror PR #450 pattern.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出供应商列表: factoryId={}, maskPrice={}", factoryId, maskPrice);
        byte[] excelBytes = supplierService.exportSupplierList(factoryId, maskPrice);

        // 生成文件名（包含时间戳）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "供应商列表_" + timestamp + ".xlsx";

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(excelBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(excelBytes);
    }

    /**
     * 下载供应商导入模板
     */
    @GetMapping("/export/template")
    @Operation(summary = "下载供应商导入模板", description = "下载用于批量导入供应商的Excel模板文件")
    public ResponseEntity<byte[]> downloadSupplierTemplate(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        log.info("下载供应商导入模板: factoryId={}", factoryId);
        byte[] templateBytes = supplierService.generateImportTemplate();

        // 设置文件名
        String filename = "供应商导入模板.xlsx";

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(templateBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(templateBytes);
    }

    /**
     * 从Excel文件批量导入供应商
     */
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/import")
    @Operation(summary = "从Excel文件批量导入供应商", description = "上传Excel文件批量导入供应商信息，仅支持.xlsx格式，文件大小限制10MB")
    public ApiResponse<com.cretas.aims.dto.common.ImportResult<SupplierDTO>> importSuppliersFromExcel(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "Excel文件（.xlsx格式）", required = true)
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        throw new BusinessException(409, "供应商导入必须先预览再确认")
                .withHint("请使用 /suppliers/import/preview 预览，并通过 /suppliers/import/confirm 确认")
                .withHintTarget("file");
    }

    @GetMapping("/import/template")
    @Operation(summary = "下载标准供应商导入模板")
    public ResponseEntity<byte[]> downloadStandardSupplierTemplate(@PathVariable @NotBlank String factoryId) {
        byte[] bytes = supplierImportService.generateTemplate();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=supplier-import-template.xlsx")
                .contentLength(bytes.length).body(bytes);
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "预览供应商Excel（不写业务数据）")
    public ApiResponse<com.cretas.aims.dto.supplier.SupplierImportPreviewDTO> previewSupplierImport(
            @PathVariable @NotBlank String factoryId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(defaultValue = "STANDARD") String mode,
            @RequestParam(required = false) String columnMappingJson) {
        byte[] fileBytes = validateSupplierImportFile(file);
        Map<String, String> mapping = Map.of();
        if (columnMappingJson != null && !columnMappingJson.isBlank()) {
            try {
                mapping = objectMapper.readValue(columnMappingJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            } catch (Exception ex) {
                throw new BusinessException(400, "columnMappingJson 格式不正确").withHintTarget("columnMappingJson");
            }
        }
        return ApiResponse.success("预览完成，尚未写入供应商数据",
                supplierImportService.preview(factoryId, fileBytes, mode, mapping));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/import/confirm")
    @Operation(summary = "确认供应商导入（原子且幂等）")
    @com.cretas.aims.annotation.Loggable(module = "SUPPLIER", action = "IMPORT", entityType = "Supplier")
    public ApiResponse<com.cretas.aims.dto.supplier.SupplierImportConfirmResultDTO> confirmSupplierImport(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody com.cretas.aims.dto.supplier.SupplierImportConfirmRequest request) {
        Long userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
        return ApiResponse.success("供应商导入完成",
                supplierImportService.confirm(factoryId, request, userId));
    }

    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/import/error-report")
    @Operation(summary = "下载供应商导入错误报告")
    public ResponseEntity<byte[]> downloadSupplierImportErrorReport(
            @PathVariable @NotBlank String factoryId,
            @RequestBody List<com.cretas.aims.dto.supplier.SupplierImportPreviewDTO.Row> rows) {
        byte[] bytes = supplierImportService.generateErrorReport(rows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=supplier-import-errors.xlsx")
                .contentLength(bytes.length).body(bytes);
    }

    private byte[] validateSupplierImportFile(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择Excel文件").withHintTarget("file");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        if (!(filename.endsWith(".xlsx") || filename.endsWith(".xls")) || filename.endsWith(".xlsm")) {
            throw new BusinessException(400, "仅支持不含宏的 .xlsx 或 .xls 文件").withHintTarget("file");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new BusinessException(400, "Excel文件不能超过10MB").withHintTarget("file");
        }
        try {
            byte[] bytes = file.getBytes();
            boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
            boolean ole = bytes.length >= 8 && (bytes[0] & 0xff) == 0xD0 && (bytes[1] & 0xff) == 0xCF
                    && (bytes[2] & 0xff) == 0x11 && (bytes[3] & 0xff) == 0xE0;
            if (!zip && !ole) throw new BusinessException(400, "文件内容不是有效Excel").withHintTarget("file");
            return bytes;
        } catch (java.io.IOException ex) {
            throw new BusinessException(400, "无法读取Excel文件", ex).withHintTarget("file");
        }
    }

    /**
     * 获取供应商评级分布
     */
    @GetMapping("/rating-distribution")
    @Operation(summary = "获取供应商评级分布", description = "统计工厂内各评级分数的供应商数量分布")
    public ApiResponse<Map<Integer, Long>> getSupplierRatingDistribution(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        Map<Integer, Long> distribution = supplierService.getSupplierRatingDistribution(factoryId);
        return ApiResponse.success(distribution);
    }

    /**
     * 获取有欠款的供应商
     */
    @GetMapping("/outstanding-balance")
    @Operation(summary = "获取有欠款的供应商", description = "获取所有存在未结清欠款的供应商列表")
    public ApiResponse<List<SupplierDTO>> getSuppliersWithOutstandingBalance(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        List<SupplierDTO> suppliers = supplierService.getSuppliersWithOutstandingBalance(factoryId);
        return ApiResponse.success(suppliers);
    }
}
