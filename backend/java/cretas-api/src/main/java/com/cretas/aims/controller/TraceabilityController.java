package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 溯源控制器
 * 提供批次溯源、完整链路溯源和公开溯源查询功能
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "溯源管理", description = "产品溯源相关接口，包括批次溯源查询、完整链路追溯、消费者公开溯源查询、溯源码解析等功能，支持从原材料到出货的全链路追溯")
public class TraceabilityController {

    private final TraceabilityService traceabilityService;
    private final ProductionBatchRepository productionBatchRepository;
    private final LabelRepository labelRepository;

    /**
     * 获取溯源记录列表
     * 返回最近生产批次的溯源概要信息
     *
     * @param factoryId 工厂ID
     * @param page 页码（1-based）
     * @param size 每页大小
     * @return 溯源记录列表
     */
    @GetMapping("/api/mobile/{factoryId}/traceability")
    @Operation(summary = "获取溯源记录列表", description = "分页获取最近生产批次的溯源概要信息，包括批次号、产品名称、生产日期、状态、质检状态等，按创建时间倒序排列")
    public ApiResponse<PageResponse<TraceabilityDTO.TraceListItem>> getTraceabilityList(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码（1-based）", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size) {
        try {
            log.info("获取溯源记录列表: factoryId={}, page={}, size={}", factoryId, page, size);

            // Clamp page to 1-based minimum and size to sensible bounds before converting
            // to Spring Data's 0-based PageRequest. Clients occasionally send page=0.
            int pageIndex = Math.max(0, page - 1);
            int pageSize = Math.min(Math.max(1, size), 200);
            Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<ProductionBatch> batchPage = productionBatchRepository.findByFactoryId(factoryId, pageable);

            List<TraceabilityDTO.TraceListItem> items = batchPage.getContent().stream()
                    .map(batch -> TraceabilityDTO.TraceListItem.builder()
                            .batchNumber(batch.getBatchNumber())
                            .productName(batch.getProductName())
                            .productionDate(batch.getCreatedAt())
                            .status(batch.getStatus() != null ? batch.getStatus().name() : "UNKNOWN")
                            .qualityStatus(batch.getQualityStatus() != null ? batch.getQualityStatus().name() : "PENDING")
                            .completedQuantity(batch.getActualQuantity() != null ? batch.getActualQuantity().intValue() : 0)
                            .build())
                    .collect(Collectors.toList());

            PageResponse<TraceabilityDTO.TraceListItem> response = PageResponse.of(
                    items,
                    page,
                    size,
                    batchPage.getTotalElements()
            );

            return ApiResponse.success(response);
        } catch (BusinessException be) {
            throw be;

        } catch (Exception e) {
            log.error("获取溯源记录列表失败", e);
            throw new BusinessException(500, "获取溯源记录列表失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 获取基础溯源信息（批次级别）
     * 需要认证，返回该批次的基本生产信息和关联数据统计
     *
     * @param factoryId 工厂ID
     * @param batchNumber 批次号
     * @return 基础溯源响应
     */
    @GetMapping("/api/mobile/{factoryId}/traceability/batch/{batchNumber}")
    @Operation(summary = "获取批次溯源信息", description = "获取指定批次的基本生产信息和关联数据统计，包括生产批次详情、原材料使用、质检记录数量等，需要认证")
    public ResponseEntity<?> getBatchTrace(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "批次号", example = "BATCH-2026-001", required = true) String batchNumber) {
        try {
            log.info("获取批次溯源: factoryId={}, batchNumber={}", factoryId, batchNumber);

            TraceabilityDTO.BatchTraceResponse response = traceabilityService.getBatchTrace(factoryId, batchNumber);

            if (response == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "code", 404,
                        "message", "未找到该批次信息"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "code", 200,
                    "data", response,
                    "message", "获取批次溯源成功"
            ));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取批次溯源失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "code", 400,
                    "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取完整溯源链路
     * 需要认证，返回从原材料到出货的完整追溯信息
     *
     * @param factoryId 工厂ID
     * @param batchNumber 批次号
     * @return 完整溯源链路响应
     */
    @GetMapping("/api/mobile/{factoryId}/traceability/full/{batchNumber}")
    @Operation(summary = "获取完整溯源链路", description = "获取从原材料到出货的完整追溯信息，包括原材料来源、供应商信息、生产过程记录、各阶段质检结果、出货目的地等全链路数据，需要认证")
    public ResponseEntity<?> getFullTrace(
            @PathVariable @Parameter(description = "工厂ID", example = "F001", required = true) String factoryId,
            @PathVariable @Parameter(description = "批次号", example = "BATCH-2026-001", required = true) String batchNumber) {
        try {
            log.info("获取完整溯源链路: factoryId={}, batchNumber={}", factoryId, batchNumber);

            TraceabilityDTO.FullTraceResponse response = traceabilityService.getFullTrace(factoryId, batchNumber);

            if (response == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "code", 404,
                        "message", "未找到该批次信息"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "code", 200,
                    "data", response,
                    "message", "获取完整溯源链路成功"
            ));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("获取完整溯源链路失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "code", 400,
                    "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 公开溯源查询（消费者扫码）
     * 无需认证，返回脱敏的溯源信息
     *
     * @param batchNumber 批次号
     * @return 公开溯源信息
     */
    @GetMapping("/api/public/trace/{batchNumber}")
    @Operation(summary = "公开溯源查询", description = "消费者扫码查询产品溯源信息，无需认证。返回脱敏的溯源信息，包括产品名称、生产日期、质检状态、工厂基本信息等，敏感数据如供应商详情、内部批次信息会被隐藏")
    public ResponseEntity<?> getPublicTrace(
            @PathVariable @Parameter(description = "批次号", example = "BATCH-2026-001", required = true) String batchNumber) {
        try {
            log.info("公开溯源查询: batchNumber={}", batchNumber);

            TraceabilityDTO.PublicTraceResponse response = traceabilityService.getPublicTrace(batchNumber);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "code", 200,
                    "data", response,
                    "message", response.getIsValid() ? "溯源信息查询成功" : response.getMessage()
            ));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("公开溯源查询失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "code", 400,
                    "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 通过溯源码查询（消费者扫码）
     * 无需认证，支持扫描二维码获取的完整溯源码
     *
     * @param traceCode 溯源码 (格式: TRACE-{batchNumber}-{uuid})
     * @return 公开溯源信息
     */
    @GetMapping("/api/public/trace/code/{traceCode}")
    @Operation(summary = "溯源码查询", description = "通过完整溯源码查询产品信息，支持扫描产品包装上的二维码。系统会自动解析溯源码提取批次号，返回与公开溯源查询相同的脱敏信息，无需认证")
    public ResponseEntity<?> getTraceByCode(
            @PathVariable @Parameter(description = "溯源码，格式: TRACE-{batchNumber}-{uuid}", example = "TRACE-BATCH-2026-001-550e8400-e29b-41d4-a716-446655440000", required = true) String traceCode) {
        try {
            log.info("溯源码查询: traceCode={}", traceCode);

            // 解析溯源码 → 批次号 (支持两种格式, 见 resolveBatchNumberFromTraceCode)
            String batchNumber = resolveBatchNumberFromTraceCode(traceCode);

            if (batchNumber == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "code", 400,
                        "message", "无效的溯源码格式或未找到对应批次"
                ));
            }

            TraceabilityDTO.PublicTraceResponse response = traceabilityService.getPublicTrace(batchNumber);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "code", 200,
                    "data", response,
                    "message", response.getIsValid() ? "溯源信息查询成功" : response.getMessage()
            ));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("溯源码查询失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "code", 400,
                    "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 溯源码 → 批次号 解析 (两种格式):
     * <ol>
     *   <li><b>内嵌格式</b> {@code TRACE-{batchNumber}-{uuid}} (默认/无编码规则工厂) — 直接从码里提取批次号。</li>
     *   <li><b>编码规则格式</b> {@code TR-{工厂}-{日期}-{序号}} (配了编码规则的工厂, 如六膳门) —
     *       这种码经 EncodingRuleService 按序号生成, <b>不内嵌批次号</b>, 无法字符串解析;
     *       但打印标签/二维码时溯源码已持久化到 {@code labels.trace_code} (含 production_batch_id)。
     *       故反查 Label → production_batch_id → 批次号。修复前这种码扫码必 400。</li>
     * </ol>
     */
    private String resolveBatchNumberFromTraceCode(String traceCode) {
        // 策略 1: 内嵌格式
        String embedded = extractBatchNumberFromTraceCode(traceCode);
        if (embedded != null) {
            return embedded;
        }
        // 策略 2: 编码规则格式 → 反查打印时持久化的 Label
        if (traceCode != null && !traceCode.isBlank()) {
            return labelRepository.findByTraceCodeAndDeletedAtIsNull(traceCode.trim())
                    .filter(l -> l.getProductionBatchId() != null)
                    .flatMap(l -> productionBatchRepository.findById(l.getProductionBatchId()))
                    .map(ProductionBatch::getBatchNumber)
                    .orElse(null);
        }
        return null;
    }

    /**
     * 从溯源码中提取批次号
     */
    private String extractBatchNumberFromTraceCode(String traceCode) {
        if (traceCode == null || !traceCode.startsWith("TRACE-")) {
            return null;
        }

        // TRACE-{batchNumber}-{uuid}
        // 移除 "TRACE-" 前缀
        String remaining = traceCode.substring(6);

        // 找到最后一个 "-" 的位置（UUID前的分隔符）
        int lastDash = remaining.lastIndexOf("-");
        if (lastDash <= 0) {
            return null;
        }

        // 提取批次号
        return remaining.substring(0, lastDash);
    }
}
