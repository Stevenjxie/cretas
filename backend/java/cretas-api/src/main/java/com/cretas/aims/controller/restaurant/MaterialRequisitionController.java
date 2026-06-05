package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.service.restaurant.MaterialRequisitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;

/**
 * 领料/日消耗管理 Controller
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/requisitions")
@RequiredArgsConstructor
@Tag(name = "餐饮-领料管理")
public class MaterialRequisitionController {

    private final MaterialRequisitionRepository requisitionRepository;
    private final com.cretas.aims.service.restaurant.VoiceRequisitionParserService voiceRequisitionParserService;
    private final MaterialRequisitionService materialRequisitionService;

    /**
     * AUD-5 B-A3 sister sweep batch 4b (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code material_requisitions} table (see {@link MaterialRequisition}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@code notes} is {@code @Column(columnDefinition="TEXT")} (unbounded) so no length
     * check needed for it (used as reject reason). {@code productTypeId} / {@code rawMaterialTypeId} /
     * {@code materialBatchId} (length=191) are UUID/ID references (not free-form text).
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
     */
    private static final int UNIT_MAX_LENGTH = 20;

    // ==================== 列表查询 ====================

    @GetMapping
    @Operation(summary = "领料单列表", description = "支持按日期、状态、类型组合筛选；任意参数可为空")
    public ApiResponse<Page<MaterialRequisition>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);

        // May 9 fix (Bug 1-4): 旧 if-else 链不支持组合筛选，且空字符串
        // 当作 non-null 触发 IllegalArgumentException — 改用 findByFilters 统一处理。
        MaterialRequisition.Status statusEnum = null;
        if (StringUtils.hasText(status)) {
            try {
                statusEnum = MaterialRequisition.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的状态值: " + status)
                        .withHint("可选: DRAFT / SUBMITTED / APPROVED / REJECTED");
            }
        }
        MaterialRequisition.RequisitionType typeEnum = null;
        if (StringUtils.hasText(type)) {
            try {
                typeEnum = MaterialRequisition.RequisitionType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的领料类型: " + type)
                        .withHint("可选: PRODUCTION / MANUAL");
            }
        }
        return ApiResponse.success(
                requisitionRepository.findByFilters(factoryId, statusEnum, typeEnum, date, pageable));
    }

    // ==================== 详情 ====================

    @GetMapping("/{requisitionId}")
    @Operation(summary = "领料单详情")
    public ApiResponse<MaterialRequisition> detail(
            @PathVariable String factoryId,
            @PathVariable String requisitionId) {
        return requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(404, "领料单不存在: " + requisitionId).withHint("请检查 ID 是否正确"));
    }

    // ==================== 创建 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("restaurant")
    @PostMapping
    @Operation(summary = "创建领料单")
    public ApiResponse<MaterialRequisition> create(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Valid MaterialRequisition requisition) {
        log.info("创建领料单: factoryId={}, type={}, userId={}", factoryId, requisition.getType(), userId);

        // AUD-5 B-A3 sister sweep batch 4b: length pre-check for unit (VARCHAR 20) BEFORE
        // dispatching to repository where PG would surface a generic 409.
        validateEntityLengths(requisition);

        requisition.setId(null);
        requisition.setFactoryId(factoryId);
        requisition.setRequestedBy(userId);
        requisition.setStatus(MaterialRequisition.Status.DRAFT);
        if (requisition.getRequisitionDate() == null) {
            requisition.setRequisitionDate(LocalDate.now());
        }

        // 自动生成单号
        long todayCount = requisitionRepository.countByFactoryIdAndDate(factoryId, requisition.getRequisitionDate());
        String dateStr = requisition.getRequisitionDate().toString().replace("-", "");
        requisition.setRequisitionNumber(String.format("REQ-%s-%03d", dateStr, todayCount + 1));

        MaterialRequisition saved = requisitionRepository.save(requisition);
        return ApiResponse.success("领料单创建成功", saved);
    }

    // ==================== 语音录入草稿 (G7 Tier B) ====================

    /**
     * 语音录入领料草稿 — 从语音识别文本提取食材/数量/单位 (Rule 2 二段式)。
     *
     * <p>先调 POST /voice/recognize 拿到 recognizedText, 再调本接口解析为 slot。
     * <b>不写库</b> — 前端回填表单, 人工确认后调 POST /requisitions 创建。</p>
     */
    @RequireModule("restaurant")
    @PostMapping("/voice-draft")
    @Operation(summary = "语音录入领料草稿", description = "语音文本 → 提取食材/数量/单位 (返回草稿, 不落库)")
    public ApiResponse<com.cretas.aims.dto.restaurant.VoiceRequisitionSlot> createVoiceDraft(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String voiceText = body != null && body.get("voiceText") != null
                ? body.get("voiceText").toString() : null;
        if (!StringUtils.hasText(voiceText)) {
            throw new BusinessException(400, "请提供语音识别文本 (voiceText)")
                    .withHint("先调用 /voice/recognize 获取识别文本");
        }
        var slot = voiceRequisitionParserService.parse(factoryId, voiceText);
        return ApiResponse.success(slot.getMessage(), slot);
    }

    // ==================== 提交审批 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{requisitionId}/submit")
    @Operation(summary = "提交领料单", description = "将草稿提交审批")
    public ApiResponse<MaterialRequisition> submit(
            @PathVariable String factoryId,
            @PathVariable String requisitionId) {
        MaterialRequisition req = requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("领料单", "id", requisitionId));
        if (req.getStatus() != MaterialRequisition.Status.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的领料单可以提交")
                    .withHint("请刷新领料单列表查看最新状态");
        }
        req.setStatus(MaterialRequisition.Status.SUBMITTED);
        MaterialRequisition updated = requisitionRepository.save(req);
        return ApiResponse.success("领料单已提交", updated);
    }

    // ==================== 审批通过 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{requisitionId}/approve")
    @Operation(summary = "审批通过", description = "审批通过并填写实际领用量")
    public ApiResponse<MaterialRequisition> approve(
            @PathVariable String factoryId,
            @PathVariable String requisitionId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long approverId,
            @RequestBody(required = false) Map<String, Object> body) {
        BigDecimal actualQuantity = null;
        if (body != null && body.containsKey("actualQuantity")) {
            actualQuantity = new BigDecimal(body.get("actualQuantity").toString());
        }
        MaterialRequisition updated = materialRequisitionService.approveRequisition(
                factoryId, requisitionId, approverId, actualQuantity);
        return ApiResponse.success("领料单已审批通过", updated);
    }

    // ==================== 驳回 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{requisitionId}/reject")
    @Operation(summary = "驳回领料单")
    public ApiResponse<MaterialRequisition> reject(
            @PathVariable String factoryId,
            @PathVariable String requisitionId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long approverId,
            @RequestBody(required = false) Map<String, Object> body) {
        MaterialRequisition req = requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("领料单", "id", requisitionId));
        if (req.getStatus() != MaterialRequisition.Status.SUBMITTED) {
            throw new BusinessException(409, "只有已提交的领料单可以驳回")
                    .withHint("请刷新领料单列表查看最新状态");
        }
        req.setStatus(MaterialRequisition.Status.REJECTED);
        req.setApprovedBy(approverId);
        req.setApprovedAt(LocalDateTime.now());
        if (body != null && body.containsKey("reason")) {
            req.setNotes(body.get("reason").toString());
        }
        MaterialRequisition updated = requisitionRepository.save(req);
        return ApiResponse.success("领料单已驳回", updated);
    }

    // ==================== 日汇总 ====================

    @GetMapping("/daily-summary")
    @Operation(summary = "日消耗汇总", description = "按食材聚合当天所有已审批的领料量")
    public ApiResponse<Map<String, Object>> dailySummary(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        List<Object[]> rows = requisitionRepository.getDailySummaryByMaterial(factoryId, targetDate);

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rawMaterialTypeId", row[0]);
            m.put("unit", row[1]);
            m.put("totalQuantity", row[2]);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", targetDate.toString());
        result.put("materialCount", items.size());
        result.put("items", items);
        return ApiResponse.success(result);
    }

    // ==================== 统计概览 ====================

    @GetMapping("/statistics")
    @Operation(summary = "领料统计概览")
    public ApiResponse<Map<String, Object>> statistics(@PathVariable String factoryId) {
        long total = requisitionRepository.findByFactoryIdOrderByCreatedAtDesc(
                factoryId, PageRequest.of(0, 1)).getTotalElements();
        long pending = requisitionRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                factoryId, MaterialRequisition.Status.SUBMITTED, PageRequest.of(0, 1)).getTotalElements();
        long approved = requisitionRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                factoryId, MaterialRequisition.Status.APPROVED, PageRequest.of(0, 1)).getTotalElements();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequisitions", total);
        result.put("pendingApproval", pending);
        result.put("approved", approved);
        return ApiResponse.success(result);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 4b) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 4b (edge audit 2026-05-20): length pre-check for
     * {@code unit} (PG VARCHAR 20) on the create path. Mirrors PR #48 / PR #78 / PR #92
     * pattern.
     *
     * <p>{@code notes} (TEXT, unbounded — used as reject reason) is not length-bounded by
     * PG, so no pre-check is needed.
     *
     * <p>Without this, an over-length {@code unit} input lets the request reach PG and
     * surfaces as {@link org.springframework.dao.DataIntegrityViolationException} →
     * generic 409 "数据处理异常". Pre-check delivers a specific 400 with the actual vs
     * allowed length so the user can immediately fix the input.
     */
    private void validateEntityLengths(MaterialRequisition requisition) {
        String unit = requisition.getUnit();
        if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "计量单位最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.length() + ")")
                    .withHint("请使用更短的计量单位 (如 kg / 箱 / 件)")
                    .withSeverity("warning")
                    .withHintTarget("unit");
        }
    }
}
