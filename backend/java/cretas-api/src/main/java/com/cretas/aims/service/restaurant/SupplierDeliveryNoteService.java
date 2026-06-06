package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 供应商送货单 OCR / 人工录入 Service (G7 Tier A)。
 *
 * @since 2026-06-03 (G7)
 */
public interface SupplierDeliveryNoteService {

    /**
     * OCR 解析照片并持久化草稿。
     *
     * <p>流程: SHA-256 幂等检查 (Rule 4 → 重复抛 409) → OSS 上传 → DashScope Vision 解析
     * → 行项软匹配 raw_material_types → 持久化 DRAFT。</p>
     *
     * @throws com.cretas.aims.exception.BusinessException 409 (code=DUPLICATE_PHOTO,
     *         hintTarget=existingId) 若 photoHash 已存在
     */
    SupplierDeliveryNote parseAndDraft(String factoryId, Long userId,
            byte[] photoBytes, String photoContentType,
            LocalDate deliveryDate, String supplierId);

    /**
     * 人工录入 (决策 D2: OCR 失败/低置信时纯手工)。source_type=MANUAL。
     */
    SupplierDeliveryNote createManual(String factoryId, Long userId,
            SupplierDeliveryNoteDto.ManualCreateRequest req);

    /**
     * 人工确认草稿 → CONFIRMED + 写 agg_supplier_price gold 表。
     *
     * <p>gold 写失败 fail-soft (D5): note 仍标 CONFIRMED, ocrErrorMessage 记 "gold sync 失败"。</p>
     */
    SupplierDeliveryNote confirmNote(String factoryId, String noteId, Long userId);

    /** 拒绝草稿 (Rule 3 标准原因码)。 */
    SupplierDeliveryNote rejectNote(String factoryId, String noteId,
            String rejectReasonCode, String rejectReasonNote, Long userId);

    /** 编辑解析行项 (人工校对)。 */
    SupplierDeliveryNote updateLines(String factoryId, String noteId,
            List<SupplierDeliveryNoteDto.LineDto> lines);

    /** 软删除 (仅 DRAFT)。 */
    void deleteDraft(String factoryId, String noteId);

    /**
     * Rule 1: 限制信息 — 当月已录 N 张 (确认 / 草稿)。无业务上限, 返回计数。
     */
    Map<String, Object> getLimits(String factoryId, LocalDate month);

    SupplierDeliveryNote submitPriceAnomalyApproval(String factoryId, String noteId, Long userId);

    SupplierDeliveryNote approvePriceAnomaly(String factoryId, String noteId, String comment,
            Long userId, String userRole);

    SupplierDeliveryNote rejectPriceAnomaly(String factoryId, String noteId, String comment,
            Long userId, String userRole);

    Page<SupplierDeliveryNote> listPendingPriceAnomalyApprovals(
            String factoryId, String userRole, Pageable pageable);

    SupplierDeliveryNote createFromProcurementConfirmation(String factoryId, Long userId,
            SupplierDeliveryNoteDto.ProcurementConfirmRequest req);
}
