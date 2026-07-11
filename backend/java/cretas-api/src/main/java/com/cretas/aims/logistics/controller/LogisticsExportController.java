package com.cretas.aims.logistics.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.logistics.service.export.LogisticsExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 物流排线计划导出 (handoff §11.3 {@code /plans/{planId}/export.csv|.xlsx}).
 *
 * <p>factoryId 一律取自 {@code @PathVariable}（{@link com.cretas.aims.config.JwtAuthInterceptor}
 * 已在全局拦截器层校验 URL 中的 factoryId 与 JWT 中的 factoryId 一致 —— 与
 * {@link LogisticsOrderImportController}/{@link LogisticsResourceController} 的既有约定一致，
 * 不在 body/query 里信任/重复传 factoryId）。
 *
 * <p>导出内容与计划详情逐字段一致 (handoff §16.1) — {@link LogisticsExportService} 直接读已落库
 * 的 plan/trip/stop 值，不重新计算。缺路线数据的车次 (status=NEEDS_ROUTE_DATA) 公里数原样显示为
 * 0 并附注"待补路线数据"，绝不伪造/拼直线距离。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/logistics")
@RequiredArgsConstructor
@Tag(name = "物流-计划导出")
public class LogisticsExportController {

    private final LogisticsExportService exportService;

    @RequireModule("scheduling")
    @GetMapping("/plans/{planId}/export.csv")
    @Operation(summary = "导出排线计划 CSV", description = "UTF-8 BOM，内容与计划详情逐字段一致")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable String factoryId,
            @PathVariable String planId) {
        log.info("[LogisticsExport] exportCsv factoryId={} planId={}", factoryId, planId);
        byte[] bytes = exportService.exportCsv(factoryId, planId);
        return fileResponse(bytes, "logistics-plan-" + planId + ".csv", "text/csv;charset=UTF-8");
    }

    @RequireModule("scheduling")
    @GetMapping("/plans/{planId}/export.xlsx")
    @Operation(summary = "导出排线计划 XLSX", description = "EasyExcel 生成，内容与计划详情逐字段一致")
    public ResponseEntity<byte[]> exportXlsx(
            @PathVariable String factoryId,
            @PathVariable String planId) {
        log.info("[LogisticsExport] exportXlsx factoryId={} planId={}", factoryId, planId);
        byte[] bytes = exportService.exportXlsx(factoryId, planId);
        return fileResponse(bytes, "logistics-plan-" + planId + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private ResponseEntity<byte[]> fileResponse(byte[] bytes, String filename, String contentType) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
