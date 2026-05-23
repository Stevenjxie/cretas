package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canvas-SupplierAdmission 供应商准入 Canvas controller (Phase BCP3 — 2026-05-22).
 *
 * <p>P3 半-Canvas-ed: Supplier entity 已存; "准入规则" 不是独立实体, 而是
 * Supplier 行上的 admission_status / admission_reviewed_at / admission_reviewer_id
 * 三字段 (per V20260824_04 Flyway).
 *
 * <p>本 controller 提供 admission-only 视角: 列出供应商 + 审核准入状态.
 * 全字段 CRUD 走 legacy SupplierAdmissionController 或 SupplierController.
 *
 * <p>4-in-1 防呆 UX: 所有错误响应携带 actionHint + severity + hintTarget.
 *
 * @since Canvas Phase BCP3 (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-supplier-admission")
@Tag(name = "Canvas-SupplierAdmission 供应商准入",
     description = "工厂级供应商准入审核 (PENDING/APPROVED/REJECTED/SUSPENDED)")
@RequiredArgsConstructor
public class CanvasSupplierAdmissionController {

    private static final Set<String> VALID_STATUS = Set.of(
            "PENDING", "APPROVED", "REJECTED", "SUSPENDED");
    private static final int NOTES_MAX = 2000;

    private final SupplierRepository supplierRepository;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的所有供应商 (准入视角)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin",
                  "procurement_manager", "procurement_specialist"})
    public ApiResponse<List<Map<String, Object>>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String admissionStatus) {
        List<Supplier> all = supplierRepository.findByFactoryId(factoryId);
        List<Map<String, Object>> items = all.stream()
                .filter(s -> admissionStatus == null || admissionStatus.isBlank()
                        || admissionStatus.equals(s.getAdmissionStatus()))
                .map(CanvasSupplierAdmissionController::toAdmissionView)
                .toList();
        return ApiResponse.success("查询成功", items);
    }

    @GetMapping("/{supplierId}")
    @Operation(summary = "查询单个供应商 (准入视角)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin",
                  "procurement_manager", "procurement_specialist"})
    public ApiResponse<Map<String, Object>> getById(
            @PathVariable String factoryId,
            @PathVariable String supplierId) {
        Supplier s = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在: " + supplierId)
                        .withHint("请确认 supplierId 拼写")
                        .withSeverity("warning")
                        .withHintTarget("supplierId"));
        return ApiResponse.success("查询成功", toAdmissionView(s));
    }

    // ==================== Create (准入审核 — 不是新建供应商) ====================

    @PostMapping("/{supplierId}/review")
    @Operation(summary = "提交准入审核结果 (设定 admission_status)")
    @RequireRole({"factory_super_admin", "permission_admin", "procurement_manager"})
    @Transactional
    public ApiResponse<Map<String, Object>> review(
            @PathVariable String factoryId,
            @PathVariable String supplierId,
            @RequestBody Map<String, Object> body) {
        Supplier s = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在: " + supplierId));

        String newStatus = required(body, "admissionStatus");
        if (!VALID_STATUS.contains(newStatus)) {
            throw new BusinessException(400,
                    "无效的 admissionStatus: " + newStatus
                            + " (有效值: PENDING, APPROVED, REJECTED, SUSPENDED)")
                    .withHint("请选择 4 种状态之一")
                    .withSeverity("warning")
                    .withHintTarget("admissionStatus");
        }

        String notes = asString(body.get("notes"));
        if (notes != null && notes.length() > NOTES_MAX) {
            throw new BusinessException(400,
                    "审核备注最长 " + NOTES_MAX + " 字符 (当前 " + notes.length() + ")")
                    .withHint("请精简备注")
                    .withSeverity("warning")
                    .withHintTarget("notes");
        }

        // 防呆 Rule 1 (max display): REJECTED/SUSPENDED 必须填写理由
        if (("REJECTED".equals(newStatus) || "SUSPENDED".equals(newStatus))
                && (notes == null || notes.isBlank())) {
            throw new BusinessException(400,
                    "REJECTED / SUSPENDED 必须填写审核理由 (notes)")
                    .withHint("请在 notes 字段填写拒绝/暂停理由")
                    .withSeverity("warning")
                    .withHintTarget("notes");
        }

        s.setAdmissionStatus(newStatus);
        s.setAdmissionReviewedAt(LocalDateTime.now());
        Long reviewerId = asLong(body.get("reviewerId"), null);
        if (reviewerId != null) {
            s.setAdmissionReviewerId(reviewerId);
        }
        if (notes != null) {
            // 写入 rating_notes (复用已有字段, 不破坏老数据)
            String existingNotes = s.getRatingNotes();
            String prefixedNotes = "[" + LocalDateTime.now() + " " + newStatus + "]\n" + notes;
            String combined = existingNotes == null || existingNotes.isBlank()
                    ? prefixedNotes
                    : prefixedNotes + "\n---\n" + existingNotes;
            s.setRatingNotes(combined);
        }
        // 同步 isActive: REJECTED/SUSPENDED → false, APPROVED → true
        if ("APPROVED".equals(newStatus)) {
            s.setIsActive(true);
        } else if ("REJECTED".equals(newStatus) || "SUSPENDED".equals(newStatus)) {
            s.setIsActive(false);
        }

        Supplier saved = supplierRepository.saveAndFlush(s);
        log.info("review supplier admission: factoryId={}, supplierId={}, newStatus={}",
                factoryId, supplierId, newStatus);
        return ApiResponse.success("准入审核已提交", toAdmissionView(saved));
    }

    // ==================== Update (PATCH semantics) ====================

    @PutMapping("/{supplierId}")
    @Operation(summary = "修改准入字段 (PATCH, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin", "procurement_manager"})
    @Transactional
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String factoryId,
            @PathVariable String supplierId,
            @RequestBody Map<String, Object> body) {
        Supplier s = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在: " + supplierId));

        // AUD-4 P1: optimistic lock check (Supplier 已有 @Version, body key "version")
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(s.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + s.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        if (body.containsKey("admissionStatus")) {
            String newStatus = asString(body.get("admissionStatus"));
            if (newStatus != null && !VALID_STATUS.contains(newStatus)) {
                throw new BusinessException(400,
                        "无效的 admissionStatus: " + newStatus
                                + " (有效值: PENDING, APPROVED, REJECTED, SUSPENDED)")
                        .withHint("请选择 4 种状态之一")
                        .withSeverity("warning")
                        .withHintTarget("admissionStatus");
            }
            s.setAdmissionStatus(newStatus);
            s.setAdmissionReviewedAt(LocalDateTime.now());
        }
        if (body.containsKey("ratingNotes")) {
            String n = asString(body.get("ratingNotes"));
            if (n != null && n.length() > NOTES_MAX) {
                throw new BusinessException(400,
                        "ratingNotes 最长 " + NOTES_MAX + " 字符 (当前 " + n.length() + ")")
                        .withSeverity("warning").withHintTarget("ratingNotes");
            }
            s.setRatingNotes(n);
        }
        if (body.containsKey("rating")) {
            Integer r = asInteger(body.get("rating"), null);
            if (r != null && (r < 1 || r > 5)) {
                throw new BusinessException(400,
                        "rating 必须在 1-5 之间 (1=最高, 5=最低), 当前 " + r)
                        .withSeverity("warning").withHintTarget("rating");
            }
            s.setRating(r);
        }

        Supplier saved = supplierRepository.saveAndFlush(s);
        log.info("update supplier admission: factoryId={}, supplierId={}", factoryId, supplierId);
        return ApiResponse.success("准入信息已更新", toAdmissionView(saved));
    }

    // ==================== Delete (soft, suspend admission) ====================

    @DeleteMapping("/{supplierId}")
    @Operation(summary = "暂停准入 (将 admission_status 改 SUSPENDED, 不删除供应商)")
    @RequireRole({"factory_super_admin", "permission_admin", "procurement_manager"})
    @Transactional
    public ApiResponse<Void> suspend(
            @PathVariable String factoryId,
            @PathVariable String supplierId) {
        Supplier s = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在: " + supplierId));

        s.setAdmissionStatus("SUSPENDED");
        s.setAdmissionReviewedAt(LocalDateTime.now());
        s.setIsActive(false);
        supplierRepository.save(s);
        log.info("suspend supplier admission: factoryId={}, supplierId={}", factoryId, supplierId);
        return ApiResponse.success("供应商准入已暂停", null);
    }

    // ==================== Helpers ====================

    private static Map<String, Object> toAdmissionView(Supplier s) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", s.getId());
        view.put("supplierCode", s.getSupplierCode());
        view.put("name", s.getName());
        view.put("contactPerson", s.getContactPerson());
        view.put("contactPhone", s.getContactPhone());
        view.put("businessLicense", s.getBusinessLicense() != null ? "已提供" : "未提供");
        view.put("qualityCertificates", s.getQualityCertificates());
        view.put("creditLevel", s.getCreditLevel());
        view.put("rating", s.getRating());
        view.put("ratingNotes", s.getRatingNotes());
        view.put("isActive", s.getIsActive());
        view.put("admissionStatus", s.getAdmissionStatus());
        view.put("admissionReviewedAt", s.getAdmissionReviewedAt());
        view.put("admissionReviewerId", s.getAdmissionReviewerId());
        view.put("version", s.getVersion());
        view.put("createdAt", s.getCreatedAt());
        view.put("updatedAt", s.getUpdatedAt());
        return view;
    }

    private static String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BusinessException(400, "字段不能为空: " + key)
                    .withSeverity("warning")
                    .withHintTarget(key);
        }
        return v.toString().trim();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInteger(Object v, Integer defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long asLong(Object v, Long defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
