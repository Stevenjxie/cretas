package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.restaurant.SupplierDeliveryNoteService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工厂业态-供应商送货单 OCR / 人工录入 Controller (T155-B chunk2, 方案 A1)。
 *
 * <p><b>A1 方案</b>: 这是一个面向 FACTORY 业态的<b>薄 controller</b>, 委托给与餐饮
 * <b>同一个</b> {@link SupplierDeliveryNoteService} bean — 不复制任何 service 逻辑。
 * 表 {@code supplier_delivery_notes} 本就是 factory_id-scoped, 过账走工厂自己的
 * {@code PurchaseService} 进共享 {@code material_batches}。耦合是表面的。
 *
 * <p>与餐饮 controller ({@code SupplierDeliveryNoteController}, 路径
 * {@code /restaurant/supplier-delivery-notes}, {@code @RequireModule("restaurant")})
 * 完全独立 — 餐饮 controller 字节不变。本 controller 路径
 * {@code /warehouse/supplier-delivery-notes}, 门控 {@code @RequireModule("warehouse")}。
 *
 * <p>业态隔离: factoryId = 租户。OCR prompt 由 service 按 resolveBusinessDomain
 * 自动选 (FACTORY → 物料中性 prompt)。所有查询/写入都按 factoryId scope, 工厂租户
 * 查不到餐饮租户的数据 (天然隔离)。
 *
 * @since 2026-06-08 (T155-B)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/warehouse/supplier-delivery-notes")
@RequiredArgsConstructor
@Tag(name = "工厂-供应商进货录入(OCR入库)")
public class FactorySupplierDeliveryController {

    private final SupplierDeliveryNoteService service;
    private final SupplierDeliveryNoteRepository noteRepository;
    private final ObjectMapper objectMapper;

    /** 低置信阈值 — 与 service / 餐饮 controller 保持一致 (Rule 5)。 */
    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.75");

    // ==================== OCR 解析 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/ocr-parse")
    @Operation(summary = "OCR 解析送货单照片", description = "上传照片 → Vision 识别 → 返回草稿 + 行项 (工厂物料 prompt)")
    public ApiResponse<SupplierDeliveryNoteDto> ocrParse(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestParam("photo") MultipartFile photo,
            @RequestParam(value = "deliveryDate", required = false) String deliveryDate,
            @RequestParam(value = "supplierId", required = false) String supplierId) {
        if (photo == null || photo.isEmpty()) {
            throw new BusinessException(400, "请上传送货单照片").withHint("支持 JPG / PNG 格式");
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            throw new BusinessException(400, "照片读取失败: " + e.getMessage());
        }
        LocalDate date = StringUtils.hasText(deliveryDate) ? LocalDate.parse(deliveryDate) : LocalDate.now();
        SupplierDeliveryNote note = service.parseAndDraft(
                factoryId, userId, bytes, photo.getContentType(), date, supplierId);
        return ApiResponse.success("识别完成", toDto(note));
    }

    // ==================== 人工录入 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/manual")
    @Operation(summary = "手动录入送货单", description = "OCR 失败/低置信时纯手工录入 (source_type=MANUAL)")
    public ApiResponse<SupplierDeliveryNoteDto> createManual(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody SupplierDeliveryNoteDto.ManualCreateRequest req) {
        SupplierDeliveryNote note = service.createManual(factoryId, userId, req);
        return ApiResponse.success("录入成功", toDto(note));
    }

    // ==================== Rule 1 限制 ====================

    @RequirePermission({"warehouse:read", "warehouse:read_write"})
    @RequireModule("warehouse")
    @GetMapping("/limits")
    @Operation(summary = "本月录入限制信息 (Rule 1)")
    public ApiResponse<Object> getLimits(
            @PathVariable String factoryId,
            @RequestParam(value = "month", required = false) String month) {
        LocalDate monthDate = StringUtils.hasText(month)
                ? YearMonth.parse(month).atDay(1) : LocalDate.now();
        return ApiResponse.success(service.getLimits(factoryId, monthDate));
    }

    // ==================== 列表 ====================

    @RequirePermission({"warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"})
    @RequireModule("warehouse")
    @GetMapping
    @Operation(summary = "送货单列表")
    public ApiResponse<Page<SupplierDeliveryNoteDto>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);
        Page<SupplierDeliveryNote> notes;
        if (StringUtils.hasText(status)) {
            DeliveryNoteStatus st;
            try {
                st = DeliveryNoteStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效状态: " + status)
                        .withHint("可选: DRAFT / CONFIRMED / REJECTED");
            }
            notes = noteRepository.findByFactoryIdAndStatus(factoryId, st, pageable);
        } else {
            notes = noteRepository.findByFactoryId(factoryId, pageable);
        }
        return ApiResponse.success(notes.map(this::toSummaryDto));
    }

    // ==================== 详情 ====================

    @RequirePermission({"warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"})
    @RequireModule("warehouse")
    @GetMapping("/{id}")
    @Operation(summary = "送货单详情 (含行项)")
    public ApiResponse<SupplierDeliveryNoteDto> detail(
            @PathVariable String factoryId,
            @PathVariable String id) {
        SupplierDeliveryNote note = noteRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "送货单不存在: " + id));
        return ApiResponse.success(toDto(note));
    }

    // ==================== 价格异常审批 ====================

    @RequirePermission({"warehouse:read_write", "procurement:write"})
    @RequireModule("warehouse")
    @PostMapping("/{id}/price-anomaly/submit")
    @Operation(summary = "提交价格异常审批")
    public ApiResponse<SupplierDeliveryNoteDto> submitPriceAnomalyApproval(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        SupplierDeliveryNote note = service.submitPriceAnomalyApproval(factoryId, id, userId);
        return ApiResponse.success("已提交审批", toDto(note));
    }

    @RequirePermission({"warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{id}/price-anomaly/approve")
    @Operation(summary = "批准价格异常")
    public ApiResponse<SupplierDeliveryNoteDto> approvePriceAnomaly(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role,
            @RequestBody(required = false) SupplierDeliveryNoteDto.PriceAnomalyApprovalRequest req) {
        String comment = req != null ? req.getComment() : null;
        SupplierDeliveryNote note = service.approvePriceAnomaly(factoryId, id, comment, userId, role);
        return ApiResponse.success("价格异常已批准", toDto(note));
    }

    @RequirePermission({"warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{id}/price-anomaly/reject")
    @Operation(summary = "驳回价格异常")
    public ApiResponse<SupplierDeliveryNoteDto> rejectPriceAnomaly(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role,
            @RequestBody(required = false) SupplierDeliveryNoteDto.PriceAnomalyApprovalRequest req) {
        String comment = req != null ? req.getComment() : null;
        SupplierDeliveryNote note = service.rejectPriceAnomaly(factoryId, id, comment, userId, role);
        return ApiResponse.success("价格异常已驳回", toDto(note));
    }

    @RequirePermission({"warehouse:read", "warehouse:read_write", "finance:read", "finance:read_write"})
    @RequireModule("warehouse")
    @GetMapping("/price-anomaly/pending")
    @Operation(summary = "价格异常待审批列表")
    public ApiResponse<Page<SupplierDeliveryNoteDto>> listPendingPriceAnomalyApprovals(
            @PathVariable String factoryId,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);
        Page<SupplierDeliveryNote> notes = service.listPendingPriceAnomalyApprovals(factoryId, role, pageable);
        return ApiResponse.success(notes.map(this::toSummaryDto));
    }

    @RequirePermission({"procurement:write", "warehouse:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/procurement-confirm")
    @Operation(summary = "采购确认生成待验收送货单")
    public ApiResponse<SupplierDeliveryNoteDto> createFromProcurementConfirmation(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody SupplierDeliveryNoteDto.ProcurementConfirmRequest req) {
        SupplierDeliveryNote note = service.createFromProcurementConfirmation(factoryId, userId, req);
        return ApiResponse.success("采购确认单已生成", toDto(note));
    }

    // ==================== 确认 (验收入库) ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @RequestMapping(value = "/{id}/confirm", method = {RequestMethod.PUT, RequestMethod.POST})
    @Operation(summary = "验收入库", description = "草稿送货单确认后过账生成真实库存批次 (落库单位归一化守卫)")
    public ApiResponse<SupplierDeliveryNoteDto> confirm(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        SupplierDeliveryNote note = service.confirmNote(factoryId, id, userId);
        return ApiResponse.success("验收入库成功", toDto(note));
    }

    // ==================== 拒绝 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @RequestMapping(value = "/{id}/reject", method = {RequestMethod.PUT, RequestMethod.POST})
    @Operation(summary = "拒绝送货单 (标准原因码)")
    public ApiResponse<SupplierDeliveryNoteDto> reject(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody SupplierDeliveryNoteDto.RejectRequest req) {
        SupplierDeliveryNote note = service.rejectNote(
                factoryId, id, req.getRejectReasonCode(), req.getRejectReasonNote(), userId);
        return ApiResponse.success("已拒绝", toDto(note));
    }

    // ==================== 编辑行项 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @RequestMapping(value = "/{id}/lines", method = {RequestMethod.PUT, RequestMethod.POST})
    @Operation(summary = "编辑解析行项")
    public ApiResponse<SupplierDeliveryNoteDto> updateLines(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody List<SupplierDeliveryNoteDto.LineDto> lines) {
        SupplierDeliveryNote note = service.updateLines(factoryId, id, lines);
        return ApiResponse.success("行项已更新", toDto(note));
    }

    // ==================== 删除 ====================

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除草稿 (软删除, 仅 DRAFT)")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        service.deleteDraft(factoryId, id);
        return ApiResponse.successMessage("已删除");
    }

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{id}/delete")
    @Operation(summary = "POST 兼容删除草稿 (仅 DRAFT)")
    public ApiResponse<Void> deleteByPost(
            @PathVariable String factoryId,
            @PathVariable String id) {
        return delete(factoryId, id);
    }

    // ==================== entity → DTO ====================

    private SupplierDeliveryNoteDto toDto(SupplierDeliveryNote n) {
        boolean lowConf = n.getOcrConfidence() != null
                && n.getOcrConfidence().compareTo(LOW_CONFIDENCE_THRESHOLD) < 0
                && n.getSourceType() == com.cretas.aims.entity.restaurant.enums.DeliveryNoteSourceType.OCR;
        List<SupplierDeliveryNoteDto.LineDto> lineDtos = n.getLines() == null ? List.of()
                : n.getLines().stream().map(this::toLineDto).collect(Collectors.toList());
        return SupplierDeliveryNoteDto.builder()
                .id(n.getId())
                .factoryId(n.getFactoryId())
                .sourceType(n.getSourceType() != null ? n.getSourceType().name() : null)
                .photoOssUrl(n.getPhotoOssUrl())
                .supplierId(n.getSupplierId())
                .supplierName(n.getSupplierName())
                .deliveryDate(n.getDeliveryDate())
                .warehouseId(n.getWarehouseId())
                .noteNumber(n.getNoteNumber())
                .totalAmount(n.getTotalAmount())
                .ocrConfidence(n.getOcrConfidence())
                .ocrErrorMessage(n.getOcrErrorMessage())
                .status(n.getStatus() != null ? n.getStatus().name() : null)
                .postingStatus(n.getPostingStatus() != null ? n.getPostingStatus().name() : null)
                .receiveRecordId(n.getReceiveRecordId())
                .postedAt(n.getPostedAt() != null ? n.getPostedAt().toString() : null)
                .postingError(n.getPostingError())
                .payableTransactionId(n.getPayableTransactionId())
                .payablePostedAt(n.getPayablePostedAt() != null ? n.getPayablePostedAt().toString() : null)
                .payablePostingError(n.getPayablePostingError())
                .rejectReasonCode(n.getRejectReasonCode())
                .rejectReasonNote(n.getRejectReasonNote())
                .priceAnomalyApprovalStatus(n.getPriceAnomalyApprovalStatus() != null
                        ? n.getPriceAnomalyApprovalStatus().name() : null)
                .priceAnomalySubmittedBy(n.getPriceAnomalySubmittedBy())
                .priceAnomalySubmittedAt(n.getPriceAnomalySubmittedAt() != null
                        ? n.getPriceAnomalySubmittedAt().toString() : null)
                .priceAnomalyApprovedBy(n.getPriceAnomalyApprovedBy())
                .priceAnomalyApprovedAt(n.getPriceAnomalyApprovedAt() != null
                        ? n.getPriceAnomalyApprovedAt().toString() : null)
                .priceAnomalyRejectedBy(n.getPriceAnomalyRejectedBy())
                .priceAnomalyRejectedAt(n.getPriceAnomalyRejectedAt() != null
                        ? n.getPriceAnomalyRejectedAt().toString() : null)
                .priceAnomalyApprovalComment(n.getPriceAnomalyApprovalComment())
                .sourceRequisitionId(n.getSourceRequisitionId())
                .procurementConfirmedBy(n.getProcurementConfirmedBy())
                .procurementConfirmedAt(n.getProcurementConfirmedAt() != null
                        ? n.getProcurementConfirmedAt().toString() : null)
                .supplierContactNote(n.getSupplierContactNote())
                .voiceAudioUrl(n.getVoiceAudioUrl())
                .voiceTranscriptText(n.getVoiceTranscriptText())
                .supplierQuotePhotoUrls(parseQuotePhotoUrls(n.getSupplierQuotePhotoUrls()))
                .expectedDeliveryDate(n.getExpectedDeliveryDate())
                .lowConfidenceWarning(lowConf)
                .lines(lineDtos)
                .build();
    }

    /** 列表用 — 不带 lines (避免 N+1 lazy 触发)。 */
    private SupplierDeliveryNoteDto toSummaryDto(SupplierDeliveryNote n) {
        boolean lowConf = n.getOcrConfidence() != null
                && n.getOcrConfidence().compareTo(LOW_CONFIDENCE_THRESHOLD) < 0
                && n.getSourceType() == com.cretas.aims.entity.restaurant.enums.DeliveryNoteSourceType.OCR;
        return SupplierDeliveryNoteDto.builder()
                .id(n.getId())
                .factoryId(n.getFactoryId())
                .sourceType(n.getSourceType() != null ? n.getSourceType().name() : null)
                .supplierId(n.getSupplierId())
                .supplierName(n.getSupplierName())
                .deliveryDate(n.getDeliveryDate())
                .warehouseId(n.getWarehouseId())
                .noteNumber(n.getNoteNumber())
                .totalAmount(n.getTotalAmount())
                .ocrConfidence(n.getOcrConfidence())
                .status(n.getStatus() != null ? n.getStatus().name() : null)
                .postingStatus(n.getPostingStatus() != null ? n.getPostingStatus().name() : null)
                .receiveRecordId(n.getReceiveRecordId())
                .postedAt(n.getPostedAt() != null ? n.getPostedAt().toString() : null)
                .postingError(n.getPostingError())
                .payableTransactionId(n.getPayableTransactionId())
                .payablePostedAt(n.getPayablePostedAt() != null ? n.getPayablePostedAt().toString() : null)
                .payablePostingError(n.getPayablePostingError())
                .priceAnomalyApprovalStatus(n.getPriceAnomalyApprovalStatus() != null
                        ? n.getPriceAnomalyApprovalStatus().name() : null)
                .sourceRequisitionId(n.getSourceRequisitionId())
                .expectedDeliveryDate(n.getExpectedDeliveryDate())
                .lowConfidenceWarning(lowConf)
                .build();
    }

    private SupplierDeliveryNoteDto.LineDto toLineDto(SupplierDeliveryNoteLine l) {
        return SupplierDeliveryNoteDto.LineDto.builder()
                .id(l.getId())
                .ingredientName(l.getIngredientName())
                .rawMaterialTypeId(l.getRawMaterialTypeId())
                .quantity(l.getQuantity())
                .unit(l.getUnit())
                .unitPrice(l.getUnitPrice())
                .baselineUnitPrice(l.getBaselineUnitPrice())
                .priceVarianceRate(l.getPriceVarianceRate())
                .priceAnomalyFlag(l.getPriceAnomalyFlag())
                .priceAnomalyReasonCode(l.getPriceAnomalyReasonCode())
                .priceAnomalyExplanation(l.getPriceAnomalyExplanation())
                .lineAmount(l.getLineAmount())
                .qcResult(l.getQcResult())
                .materialBatchId(l.getMaterialBatchId())
                .remark(l.getRemark())
                .ocrConfidence(l.getOcrConfidence())
                .build();
    }

    private List<String> parseQuotePhotoUrls(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(raw);
        }
    }
}
