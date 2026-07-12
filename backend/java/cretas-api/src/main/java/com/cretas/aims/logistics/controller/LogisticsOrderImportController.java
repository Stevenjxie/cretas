package com.cretas.aims.logistics.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.importjob.ManualOrderCreateRequest;
import com.cretas.aims.logistics.dto.importjob.OrderBatchDto;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.dto.importjob.UpdateLocationRequest;
import com.cretas.aims.logistics.service.importjob.LogisticsOrderImportService;
import com.cretas.aims.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 物流订单导入 (handoff §11.1) — 模板下载 / 两段式预检+提交 / 批次+订单查询 / 定位补录。
 *
 * <p>factoryId 一律取自 {@code @PathVariable}（{@link com.cretas.aims.config.JwtAuthInterceptor}
 * 已在全局拦截器层校验 URL 中的 factoryId 与 JWT 中的 factoryId 一致，跨工厂访问在到达这里之前
 * 就已经 403 —— 与项目内其他 {@code /api/mobile/{factoryId}/*} controller 的既有约定一致，
 * 不在 body 里信任/重复传 factoryId）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/logistics")
@RequiredArgsConstructor
@Tag(name = "物流-订单导入")
public class LogisticsOrderImportController {

    private final LogisticsOrderImportService importService;

    @GetMapping("/order-import/template")
    @Operation(summary = "下载订单导入模板", description = "后端唯一模板事实源，字段见 handoff §8.1")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String factoryId) {
        byte[] bytes = importService.downloadTemplate();
        String filename = "logistics-order-template.xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @RequireModule("scheduling")
    @PostMapping(value = "/order-import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "预检订单文件", description = "解析+逐行校验，不写业务订单为 live 数据；返回 jobId 供 commit 使用")
    public ApiResponse<PreviewResultDto> preview(
            @PathVariable String factoryId,
            @RequestParam("file") MultipartFile file) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("[LogisticsOrderImport] preview 请求 factory={} filename={} size={}",
                factoryId, file != null ? file.getOriginalFilename() : null, file != null ? file.getSize() : 0);
        return ApiResponse.success("预检完成", importService.preview(factoryId, file, userId));
    }

    @RequireModule("scheduling")
    @PostMapping("/order-import/manual")
    @Operation(summary = "手动录入订单(非文件)", description = "从表单结构化行创建订单批次, 复用文件导入的校验+提交+地理编码流程; 返回同 preview 的 jobId 供 commit")
    public ApiResponse<PreviewResultDto> previewManual(
            @PathVariable String factoryId,
            @RequestBody ManualOrderCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("[LogisticsOrderImport] previewManual 请求 factory={} rows={}",
                factoryId, request != null && request.getRows() != null ? request.getRows().size() : 0);
        return ApiResponse.success("录入完成", importService.previewManual(factoryId, request, userId));
    }

    @RequireModule("scheduling")
    @PostMapping("/order-import/{jobId}/commit")
    @Operation(summary = "提交导入批次", description = "幂等：重复提交同一 jobId 不重复写入")
    public ApiResponse<OrderBatchDto> commit(
            @PathVariable String factoryId,
            @PathVariable String jobId) {
        return ApiResponse.success("提交成功", importService.commit(factoryId, jobId));
    }

    @GetMapping("/order-batches")
    @Operation(summary = "导入批次列表", description = "分页，最新业务日期在前")
    public ApiResponse<Page<OrderBatchDto>> listBatches(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.success(
                importService.listBatches(factoryId, PageRequest.of(Math.max(page, 0), safeSize)));
    }

    @GetMapping("/order-batches/{batchId}")
    @Operation(summary = "导入批次详情")
    public ApiResponse<OrderBatchDto> getBatch(
            @PathVariable String factoryId,
            @PathVariable String batchId) {
        return ApiResponse.success(importService.getBatch(factoryId, batchId));
    }

    @GetMapping("/orders")
    @Operation(summary = "批次内订单列表", description = "分页；batchId 必填")
    public ApiResponse<Page<DeliveryOrderDto>> listOrders(
            @PathVariable String factoryId,
            @RequestParam String batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        return ApiResponse.success(
                importService.listOrders(factoryId, batchId, PageRequest.of(Math.max(page, 0), safeSize)));
    }

    @RequireModule("scheduling")
    @PutMapping("/orders/{orderId}/location")
    @Operation(summary = "补录/修正门店经纬度", description = "经纬度必须同时提供；成功后 locationStatus=RESOLVED")
    public ApiResponse<DeliveryOrderDto> updateLocation(
            @PathVariable String factoryId,
            @PathVariable String orderId,
            @RequestBody UpdateLocationRequest request) {
        return ApiResponse.success("定位已更新",
                importService.updateLocation(factoryId, orderId, request.getLongitude(), request.getLatitude()));
    }
}
