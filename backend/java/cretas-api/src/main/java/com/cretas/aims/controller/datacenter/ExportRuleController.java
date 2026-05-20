package com.cretas.aims.controller.datacenter;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.datacenter.ExportJob;
import com.cretas.aims.entity.datacenter.ExportRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.datacenter.ExportService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.cretas.aims.config.RequireRole;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 导出规则中心 — Rule CRUD + 触发 export + Job 状态查询 + 下载.
 *
 * <p>Sprint 4 Chat K C-EXPORT-CENTER-1.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/export-rules")
@RequiredArgsConstructor
public class ExportRuleController {

    private final ExportService exportService;

    /**
     * AUD-5 B-A3 sister sweep batch 4a (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code export_rules} table (see {@link ExportRule}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@link ExportRule} is accepted as raw {@code @RequestBody} (no DTO wrapper /
     * no {@code @Size}), so controller-side pre-check is the safety net. The
     * {@code description} and {@code filter_expression} columns are {@code TEXT}
     * (unbounded), so no length check needed for them.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
     */
    private static final int MODULE_CODE_MAX_LENGTH = 64;
    private static final int RULE_NAME_MAX_LENGTH = 200;
    private static final int FORMAT_MAX_LENGTH = 10;
    private static final int TARGET_ENTITY_MAX_LENGTH = 200;

    // ───── Rule CRUD ─────

    @GetMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<Page<ExportRule>>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String moduleCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<ExportRule> result = exportService.listRules(factoryId, moduleCode,
                PageRequest.of(Math.max(page, 0), safeSize));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{ruleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<ExportRule>> get(
            @PathVariable String factoryId,
            @PathVariable Long ruleId) {
        return ResponseEntity.ok(ApiResponse.success(exportService.getRule(factoryId, ruleId)));
    }

    @PostMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<ExportRule>> create(
            @PathVariable String factoryId,
            @RequestBody ExportRule rule) {
        // AUD-5 B-A3 sister sweep batch 4a: length pre-check for moduleCode (64) + ruleName (200) +
        // format (10) + targetEntity (200) BEFORE persisting to PG.
        validateExportRuleLengths(rule);
        rule.setFactoryId(factoryId);
        return ResponseEntity.ok(ApiResponse.success("规则创建成功", exportService.createRule(rule)));
    }

    @PutMapping("/{ruleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<ExportRule>> update(
            @PathVariable String factoryId,
            @PathVariable Long ruleId,
            @RequestBody ExportRule patch) {
        // AUD-5 B-A3 sister sweep batch 4a (Rule 16: entry-point matrix — update path).
        validateExportRuleLengths(patch);
        return ResponseEntity.ok(ApiResponse.success("规则更新成功",
                exportService.updateRule(factoryId, ruleId, patch)));
    }

    @DeleteMapping("/{ruleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String factoryId,
            @PathVariable Long ruleId) {
        exportService.deleteRule(factoryId, ruleId);
        return ResponseEntity.ok(ApiResponse.success("规则删除成功", null));
    }

    // ───── Run ─────

    /**
     * 按规则触发导出. 返同步 byte[] (base64) 或异步 jobId, 由 service 根据 rowThreshold 决定.
     *
     * @param body {@code {"runtimeParams":{"startDate":"2026-01-01",...}}}
     */
    @PostMapping("/{ruleId}/run")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(
            @PathVariable String factoryId,
            @PathVariable Long ruleId,
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeParams = body == null ? null
                : (Map<String, Object>) body.getOrDefault("runtimeParams", null);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(
                exportService.exportByRule(factoryId, ruleId, userId, runtimeParams)));
    }

    // ───── Job tracking ─────

    @GetMapping("/jobs")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<Page<ExportJob>>> listJobs(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok(ApiResponse.success(
                exportService.listJobs(factoryId, status, PageRequest.of(Math.max(page, 0), safeSize))));
    }

    @GetMapping("/jobs/{jobId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<ApiResponse<ExportJob>> getJob(
            @PathVariable String factoryId,
            @PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(exportService.getJob(factoryId, jobId)));
    }

    /**
     * 下载已完成的 Job 文件. 仅 status=SUCCESS 且 filePath 非空时返文件流.
     */
    @GetMapping("/jobs/{jobId}/download")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ResponseEntity<Resource> download(
            @PathVariable String factoryId,
            @PathVariable String jobId) {
        ExportJob job = exportService.getJob(factoryId, jobId);
        if (!"SUCCESS".equals(job.getStatus()) || job.getFilePath() == null) {
            return ResponseEntity.status(409).build();  // CONFLICT — not ready
        }
        File file = new File(job.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.status(410).build();  // GONE — file pruned
        }
        String filename = "export-" + jobId + extensionFor(file.getName());
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    private static String extensionFor(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 4a) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 4a (edge audit 2026-05-20): length pre-check for
     * {@code moduleCode} (PG VARCHAR 64), {@code ruleName} (200), {@code format} (10),
     * {@code targetEntity} (200) on the create + update paths
     * (Rule 16: entry-point matrix). Mirrors PR #48 / PR #78 / PR #92 pattern.
     *
     * <p>Without this, an over-length input lets the request reach PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check delivers a specific 400 with the actual vs allowed length
     * so the user can immediately fix the input.
     *
     * <p>{@code description} (TEXT, unbounded) and {@code filterExpression} (TEXT, unbounded)
     * are not length-bounded by PG, so no pre-check is needed for them.
     */
    private void validateExportRuleLengths(ExportRule rule) {
        if (rule == null) {
            return;
        }
        String moduleCode = rule.getModuleCode();
        if (moduleCode != null && moduleCode.length() > MODULE_CODE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "模块代码最长 " + MODULE_CODE_MAX_LENGTH + " 字符 (当前 " + moduleCode.length() + ")")
                    .withHint("请使用更短的模块代码 (上限 64 字符)")
                    .withSeverity("warning")
                    .withHintTarget("moduleCode");
        }
        String ruleName = rule.getRuleName();
        if (ruleName != null && ruleName.length() > RULE_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "规则名称最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + ruleName.length() + ")")
                    .withHint("请使用更短的规则名称 (上限 200 字符)")
                    .withSeverity("warning")
                    .withHintTarget("ruleName");
        }
        String format = rule.getFormat();
        if (format != null && format.length() > FORMAT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "导出格式最长 " + FORMAT_MAX_LENGTH + " 字符 (当前 " + format.length() + ")")
                    .withHint("请使用预定义格式 (XLSX / CSV / PDF)")
                    .withSeverity("warning")
                    .withHintTarget("format");
        }
        String targetEntity = rule.getTargetEntity();
        if (targetEntity != null && targetEntity.length() > TARGET_ENTITY_MAX_LENGTH) {
            throw new BusinessException(400,
                    "目标实体类名最长 " + TARGET_ENTITY_MAX_LENGTH + " 字符 (当前 " + targetEntity.length() + ")")
                    .withHint("请使用更短的全限定类名 (上限 200 字符)")
                    .withSeverity("warning")
                    .withHintTarget("targetEntity");
        }
    }
}
