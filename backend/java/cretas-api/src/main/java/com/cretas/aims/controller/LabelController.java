package com.cretas.aims.controller;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.LabelService;
import com.cretas.aims.util.ErrorSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签控制器
 * 用于管理产品追溯标签、条码、二维码
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/labels")
@RequiredArgsConstructor
@Tag(name = "标签管理", description = "产品追溯标签管理接口")
public class LabelController {

    private final LabelService labelService;

    /**
     * 获取标签列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取标签列表", description = "分页查询标签")
    public ResponseEntity<?> getLabels(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "0") @Parameter(description = "页码") int page,
            @RequestParam(defaultValue = "10") @Parameter(description = "每页大小") int size,
            @RequestParam(required = false) @Parameter(description = "标签类型") String labelType,
            @RequestParam(required = false) @Parameter(description = "状态") String status) {
        try {
            Page<Label> labels;
            PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

            if (labelType != null && !labelType.isEmpty()) {
                labels = labelService.getLabelsByType(factoryId, labelType, pageRequest);
            } else if (status != null && !status.isEmpty()) {
                labels = labelService.getLabelsByStatus(factoryId, status, pageRequest);
            } else {
                labels = labelService.getLabels(factoryId, pageRequest);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", labels.getContent());
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", labels.getTotalElements());
            response.put("totalPages", labels.getTotalPages());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取标签列表失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取标签统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取标签统计", description = "获取工厂标签统计数据")
    public ResponseEntity<?> getStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("total", labelService.countByFactory(factoryId));
            stats.put("active", labelService.countByStatus(factoryId, "ACTIVE"));
            stats.put("printed", labelService.countByStatus(factoryId, "PRINTED"));
            stats.put("applied", labelService.countByStatus(factoryId, "APPLIED"));
            stats.put("voided", labelService.countByStatus(factoryId, "VOIDED"));
            stats.put("expiringSoon", labelService.getExpiringLabels(factoryId, 7).size());
            stats.put("expired", labelService.getExpiredLabels(factoryId).size());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats
            ));
        } catch (Exception e) {
            log.error("获取标签统计失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取即将过期的标签
     */
    @GetMapping("/expiring")
    @Operation(summary = "获取即将过期标签", description = "获取即将过期的标签列表")
    public ResponseEntity<?> getExpiringLabels(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "7") @Parameter(description = "提前天数") int daysAhead) {
        try {
            List<Label> expiring = labelService.getExpiringLabels(factoryId, daysAhead);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", expiring
            ));
        } catch (Exception e) {
            log.error("获取即将过期标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取已过期的标签
     */
    @GetMapping("/expired")
    @Operation(summary = "获取已过期标签", description = "获取已过期的标签列表")
    public ResponseEntity<?> getExpiredLabels(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        try {
            List<Label> expired = labelService.getExpiredLabels(factoryId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", expired
            ));
        } catch (Exception e) {
            log.error("获取已过期标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 根据追溯码查询
     */
    @GetMapping("/trace/{traceCode}")
    @Operation(summary = "根据追溯码查询", description = "根据追溯码查询标签信息")
    public ResponseEntity<?> getByTraceCode(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "追溯码") String traceCode) {
        try {
            return labelService.getByTraceCode(traceCode)
                    .map(label -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", label
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("根据追溯码查询失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 根据标签编码查询
     */
    @GetMapping("/code/{labelCode}")
    @Operation(summary = "根据标签编码查询", description = "根据标签编码查询标签信息")
    public ResponseEntity<?> getByLabelCode(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签编码") String labelCode) {
        try {
            return labelService.getByLabelCode(labelCode)
                    .map(label -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", label
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("根据标签编码查询失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 根据批次查询标签
     */
    @GetMapping("/batch/{batchId}")
    @Operation(summary = "根据批次查询标签", description = "查询指定批次的所有标签")
    public ResponseEntity<?> getByBatchId(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "批次ID") String batchId) {
        try {
            List<Label> labels = labelService.getLabelsByBatchId(batchId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", labels
            ));
        } catch (Exception e) {
            log.error("根据批次查询标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 获取单个标签详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取标签详情", description = "根据ID获取标签详情")
    public ResponseEntity<?> getLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id) {
        try {
            return labelService.getLabelById(factoryId, id)
                    .map(label -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", label
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("获取标签详情失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 创建标签
     */
    @RequirePermission({"production:read_write"})
    @PostMapping
    @Operation(summary = "创建标签", description = "创建新的标签")
    public ResponseEntity<?> createLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Parameter(description = "标签信息") Label label) {
        try {
            // 防呆: 拒绝空白幽灵标签 (无类型 + 无批次引用 + 无溯源码 → 会生成 qrContent=null 的可打印空标签)。
            // 不给共享 Label 实体加 @NotBlank (避免污染其他创建路径), 在入口处做内容校验。
            boolean blankLabel = (label.getLabelType() == null || label.getLabelType().isBlank())
                    && (label.getBatchId() == null || label.getBatchId().isBlank())
                    && label.getProductionBatchId() == null
                    && (label.getTraceCode() == null || label.getTraceCode().isBlank());
            if (blankLabel) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "标签信息不完整: 请至少提供标签类型(labelType)或关联批次(batchId/productionBatchId)"
                ));
            }
            label.setFactoryId(factoryId);
            label.setCreatedBy(userId);
            Label created = labelService.createLabel(label);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "标签创建成功"
            ));
        } catch (Exception e) {
            log.error("创建标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 批量创建标签
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/batch")
    @Operation(summary = "批量创建标签", description = "批量创建多个标签")
    public ResponseEntity<?> createLabels(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Parameter(description = "标签列表") List<Label> labels) {
        try {
            labels.forEach(label -> {
                label.setFactoryId(factoryId);
                label.setCreatedBy(userId);
            });
            List<Label> created = labelService.createLabels(labels);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "标签批量创建成功，数量: " + created.size()
            ));
        } catch (Exception e) {
            log.error("批量创建标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 更新标签
     */
    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新标签", description = "更新标签信息")
    public ResponseEntity<?> updateLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id,
            @RequestBody @Parameter(description = "更新数据") Label updateData) {
        try {
            Label updated = labelService.updateLabel(id, updateData);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "标签更新成功"
            ));
        } catch (Exception e) {
            log.error("更新标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 打印标签
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/{id}/print")
    @Operation(summary = "打印标签", description = "记录标签打印操作")
    public ResponseEntity<?> printLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            Label printed = labelService.printLabel(id, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", printed,
                "message", "标签已打印"
            ));
        } catch (Exception e) {
            log.error("打印标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 贴标操作
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/{id}/apply")
    @Operation(summary = "贴标操作", description = "记录标签贴附操作")
    public ResponseEntity<?> applyLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            Label applied = labelService.applyLabel(id, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", applied,
                "message", "贴标成功"
            ));
        } catch (Exception e) {
            log.error("贴标操作失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 作废标签
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/{id}/void")
    @Operation(summary = "作废标签", description = "将标签标记为作废")
    public ResponseEntity<?> voidLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            Label voided = labelService.voidLabel(id, userId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", voided,
                "message", "标签已作废"
            ));
        } catch (Exception e) {
            log.error("作废标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 删除标签
     */
    @RequirePermission({"production:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "删除标签", description = "删除指定的标签")
    public ResponseEntity<?> deleteLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签ID") String id) {
        try {
            labelService.deleteLabel(id);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "标签删除成功"
            ));
        } catch (Exception e) {
            log.error("删除标签失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 生成标签编码
     */
    @GetMapping("/generate-code")
    @Operation(summary = "生成标签编码", description = "生成新的标签编码")
    public ResponseEntity<?> generateLabelCode(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "QR_CODE") @Parameter(description = "标签类型") String labelType) {
        try {
            String code = labelService.generateLabelCode(factoryId, labelType);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("labelCode", code)
            ));
        } catch (Exception e) {
            log.error("生成标签编码失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * 生成追溯码
     */
    @GetMapping("/generate-trace-code")
    @Operation(summary = "生成追溯码", description = "生成新的追溯码")
    public ResponseEntity<?> generateTraceCode(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "批次号") String batchNumber) {
        try {
            String code = labelService.generateTraceCode(factoryId, batchNumber);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("traceCode", code)
            ));
        } catch (Exception e) {
            log.error("生成追溯码失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    // ========== SP4-T5: 物料批次标签生成 + 扫码查询 ==========

    /**
     * SP4-T5: 为物料批次生成 MATERIAL 类型标签.
     * POST /api/mobile/{factoryId}/labels/material-batch/{batchId}
     */
    @RequirePermission({"production:read_write"})
    @PostMapping("/material-batch/{batchId}")
    @Operation(summary = "生成物料批次标签", description = "SP4-T5: 为指定物料批次生成可扫描的 MATERIAL 标签")
    public ResponseEntity<?> generateMaterialBatchLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "物料批次ID") String batchId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        try {
            Label label = labelService.generateMaterialBatchLabel(factoryId, batchId, userId);
            return ResponseEntity.status(201).body(Map.of(
                "success", true,
                "data", label,
                "message", "物料批次标签生成成功"
            ));
        } catch (Exception e) {
            log.error("生成物料批次标签失败: factoryId={}, batchId={}", factoryId, batchId, e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }

    /**
     * SP4-T5: 扫描物料标签, 返回批次完整信息.
     * GET /api/mobile/{factoryId}/labels/scan/{labelCode}
     */
    @GetMapping("/scan/{labelCode}")
    @Operation(summary = "扫码查询物料批次", description = "SP4-T5: 通过扫描标签编码获取物料批次完整信息")
    public ResponseEntity<?> scanLabel(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "标签编码 (扫码值)") String labelCode) {
        try {
            MaterialBatchLabelScanResponse response = labelService.scanLabel(factoryId, labelCode);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", response,
                "message", "扫码查询成功"
            ));
        } catch (Exception e) {
            log.error("扫码查询失败: factoryId={}, labelCode={}", factoryId, labelCode, e);
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", ErrorSanitizer.sanitize(e)
            ));
        }
    }
}
