package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.client.SupplierPriceGoldClient;
import com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteSourceType;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.PriceAnomalyApprovalStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteLineRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import com.cretas.aims.service.restaurant.SupplierDeliveryNoteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 供应商送货单 OCR / 人工录入 Service 实现 (G7 Tier A)。
 *
 * @since 2026-06-03 (G7)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierDeliveryNoteServiceImpl implements SupplierDeliveryNoteService {

    private final SupplierDeliveryNoteRepository noteRepository;
    private final SupplierDeliveryNoteLineRepository lineRepository;
    private final DashScopeVisionClient visionClient;
    private final OssService ossService;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final SupplierPriceGoldClient goldClient;
    private final RestaurantInventoryPostingService postingService;
    private final ArApService arApService;
    private final NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> PRICE_ANOMALY_APPROVER_ROLES = Set.of(
            FactoryUserRole.factory_super_admin.name(),
            FactoryUserRole.restaurant_manager.name(),
            FactoryUserRole.platform_admin.name()
    );

    /** 低置信阈值 — 前端据此显示重拍提示 (Rule 5)。 */
    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.75");
    private static final BigDecimal PRICE_ANOMALY_THRESHOLD = new BigDecimal("0.05");
    private static final String PAYABLE_SOURCE_TYPE = "SUPPLIER_DELIVERY_NOTE";

    private static final String SUPPLIER_NOTE_OCR_PROMPT = """
            你是中餐厅供应链单据识别专家。请识别这张供应商送货单/进货单图片。
            严格以 JSON 返回(无其他文字):
            {
              "note_number": "送货单号(无则null)",
              "delivery_date": "YYYY-MM-DD(无则今日)",
              "supplier_name": "供应商名称",
              "items": [
                {
                  "ingredient_name": "食材名称",
                  "quantity": 数量数值,
                  "unit": "单位(kg/斤/个/包等)",
                  "unit_price": 单价数值或null,
                  "line_amount": 金额数值或null
                }
              ],
              "total_amount": 合计金额数值或null,
              "confidence": 0.0-1.0整体置信度
            }
            要点:1.数量/金额为纯数字无单位 2.日期无法识别返回null 3.confidence<0.5时items返回空数组
            """;

    @Override
    @Transactional
    public SupplierDeliveryNote parseAndDraft(String factoryId, Long userId,
            byte[] photoBytes, String photoContentType,
            LocalDate deliveryDate, String supplierId) {
        if (photoBytes == null || photoBytes.length == 0) {
            throw new BusinessException(400, "照片内容为空").withHint("请重新上传清晰的送货单照片");
        }
        // 1. SHA-256 幂等检查 (Rule 4)
        String photoHash = sha256(photoBytes);
        var dup = noteRepository.findByPhotoHash(photoHash);
        if (dup.isPresent()) {
            throw new BusinessException(409, "已有该照片的草稿 (单号 " + dup.get().getId() + "), 是否前往查看?")
                    .withCode("DUPLICATE_PHOTO")
                    .withHint("同一张照片已上传过, 请勿重复录入")
                    .withHintTarget(dup.get().getId());
        }

        // 2. Vision 可用性 (Rule 5 死路改导航)
        if (!visionClient.isAvailable()) {
            throw new BusinessException(503, "视觉识别服务暂不可用, 请改用手动录入")
                    .withCode("VISION_UNAVAILABLE")
                    .withHint("可点击「手动录入」直接填写送货单行项");
        }

        // 3. 上传 OSS (失败不阻断 — 仍可 OCR, 只是没留存原图)
        String ossUrl = null;
        try {
            if (ossService.isAvailable()) {
                String filename = "delivery-note-" + System.currentTimeMillis()
                        + extFor(photoContentType);
                ossUrl = ossService.uploadBytes(photoBytes,
                        "restaurant/" + factoryId + "/delivery-notes",
                        factoryId, filename,
                        photoContentType != null ? photoContentType : "image/jpeg");
            }
        } catch (Exception e) {
            log.warn("送货单照片 OSS 上传失败 (不阻断 OCR): {}", e.getMessage());
        }

        // 4. 调 Vision OCR
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setFactoryId(factoryId);
        note.setSourceType(DeliveryNoteSourceType.OCR);
        note.setPhotoHash(photoHash);
        note.setPhotoOssUrl(ossUrl);
        note.setSupplierId(supplierId);
        note.setDeliveryDate(deliveryDate != null ? deliveryDate : LocalDate.now());
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setCreatedBy(userId);
        note.setOcrParsedAt(LocalDateTime.now());

        String rawResponse;
        try {
            // analyzeImage 优先 (OSS 可读时); 无 OSS URL 则直接走 bytes 路径 (兜底)。
            if (ossUrl != null) {
                rawResponse = visionClient.analyzeImage(ossUrl, SUPPLIER_NOTE_OCR_PROMPT);
            } else {
                // 无 OSS 时无法用 URL 路径 — 此处明确报错 (不降级假数据)。
                throw new BusinessException(503, "照片未能留存且无法直接识别, 请重试或手动录入")
                        .withCode("OSS_REQUIRED");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("送货单 OCR vision 调用失败: {}", e.getMessage());
            note.setOcrErrorMessage("OCR 识别失败: " + e.getMessage());
            note.setOcrConfidence(BigDecimal.ZERO);
            return noteRepository.save(note);  // 留草稿, 用户可手工补行项
        }

        // 5. 解析 JSON
        parseOcrJsonIntoNote(note, factoryId, rawResponse);
        refreshPriceAnomalies(note);

        return noteRepository.save(note);
    }

    /** 把 OCR 原始 JSON 解析进 note + 行项 (软匹配 raw_material_types)。 */
    private void parseOcrJsonIntoNote(SupplierDeliveryNote note, String factoryId, String rawResponse) {
        note.setOcrRawJson(rawResponse);
        try {
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(rawResponse != null ? rawResponse : "");
            if (!matcher.find()) {
                note.setOcrErrorMessage("无法从识别结果中提取 JSON, 请手动填写行项");
                note.setOcrConfidence(BigDecimal.ZERO);
                return;
            }
            JsonNode json = objectMapper.readTree(matcher.group());

            note.setNoteNumber(jsonStr(json, "note_number"));
            String supplierName = jsonStr(json, "supplier_name");
            if (supplierName != null && note.getSupplierName() == null) {
                note.setSupplierName(supplierName);
            }
            String parsedDate = jsonStr(json, "delivery_date");
            if (parsedDate != null) {
                try {
                    note.setDeliveryDate(LocalDate.parse(parsedDate));
                } catch (Exception ignore) {
                    // keep caller-supplied / today
                }
            }
            note.setTotalAmount(jsonDecimal(json, "total_amount"));
            BigDecimal confidence = jsonDecimal(json, "confidence");
            note.setOcrConfidence(confidence != null ? confidence : BigDecimal.ZERO);

            JsonNode items = json.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
                    String ingredientName = jsonStr(item, "ingredient_name");
                    line.setIngredientName(ingredientName != null ? ingredientName : "未知食材");
                    line.setQuantity(jsonDecimal(item, "quantity"));
                    line.setUnit(jsonStr(item, "unit"));
                    line.setUnitPrice(jsonDecimal(item, "unit_price"));
                    line.setLineAmount(jsonDecimal(item, "line_amount"));
                    line.setOcrConfidence(confidence);
                    // 软匹配 raw_material_types
                    line.setRawMaterialTypeId(softMatchMaterial(factoryId, ingredientName));
                    note.addLine(line);
                }
            }
        } catch (Exception e) {
            log.warn("送货单 OCR JSON 解析失败: {}", e.getMessage());
            note.setOcrErrorMessage("解析失败: " + e.getMessage() + " — 请手动校对行项");
            if (note.getOcrConfidence() == null) {
                note.setOcrConfidence(BigDecimal.ZERO);
            }
        }
    }

    /** 软匹配食材名 → raw_material_types.id (取最相关的一条); 无匹配返 null。 */
    private String softMatchMaterial(String factoryId, String ingredientName) {
        if (ingredientName == null || ingredientName.isBlank()) {
            return null;
        }
        try {
            Page<RawMaterialType> page = rawMaterialTypeRepository.searchMaterialTypes(
                    factoryId, ingredientName.trim(), PageRequest.of(0, 1));
            if (page != null && page.hasContent()) {
                return page.getContent().get(0).getId();
            }
        } catch (Exception e) {
            log.debug("食材软匹配失败 ({}): {}", ingredientName, e.getMessage());
        }
        return null;
    }

    @Override
    @Transactional
    public SupplierDeliveryNote createManual(String factoryId, Long userId,
            SupplierDeliveryNoteDto.ManualCreateRequest req) {
        if (req == null) {
            throw new BusinessException(400, "录入内容为空");
        }
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setFactoryId(factoryId);
        note.setSourceType(DeliveryNoteSourceType.MANUAL);
        note.setSupplierId(req.getSupplierId());
        note.setSupplierName(req.getSupplierName());
        note.setDeliveryDate(req.getDeliveryDate() != null ? req.getDeliveryDate() : LocalDate.now());
        note.setWarehouseId(req.getWarehouseId());
        note.setNoteNumber(req.getNoteNumber());
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setCreatedBy(userId);

        BigDecimal total = BigDecimal.ZERO;
        if (req.getLines() != null) {
            for (SupplierDeliveryNoteDto.LineDto dto : req.getLines()) {
                SupplierDeliveryNoteLine line = toLineEntity(dto);
                line.setRawMaterialTypeId(dto.getRawMaterialTypeId() != null
                        ? dto.getRawMaterialTypeId()
                        : softMatchMaterial(factoryId, dto.getIngredientName()));
                note.addLine(line);
                if (line.getLineAmount() != null) {
                    total = total.add(line.getLineAmount());
                }
            }
        }
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            note.setTotalAmount(total);
        }
        refreshPriceAnomalies(note);
        return noteRepository.save(note);
    }

    @Override
    @Transactional
    public SupplierDeliveryNote confirmNote(String factoryId, String noteId, Long userId) {
        SupplierDeliveryNote draft = mustFindDraftOrAny(factoryId, noteId);
        if (draft.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿送货单可验收入库 (当前: " + draft.getStatus() + ")")
                    .withCode("INVALID_STATUS")
                    .withHint("请刷新送货单详情，确认当前状态后再操作")
                    .withHintTarget("status");
        }
        refreshPriceAnomalies(draft);
        List<SupplierDeliveryNoteLine> unexplained = draft.getLines() == null ? List.of() : draft.getLines().stream()
                .filter(line -> Boolean.TRUE.equals(line.getPriceAnomalyFlag()))
                .filter(line -> isBlank(line.getPriceAnomalyExplanation()))
                .toList();
        if (!unexplained.isEmpty()) {
            noteRepository.save(draft);
            SupplierDeliveryNoteLine first = unexplained.get(0);
            throw new BusinessException(409, "进价异常需先填写供应商解释: " + first.getIngredientName())
                    .withCode("PRICE_ANOMALY_EXPLANATION_REQUIRED")
                    .withHint("请在送货单行项目里填写涨价原因和供应商解释，再确认验收入库")
                    .withHintTarget("priceAnomalyExplanation");
        }
        if (hasPriceAnomaly(draft)) {
            assertPriceAnomalyApproved(draft);
        }
        noteRepository.save(draft);

        SupplierDeliveryNote saved;
        try {
            saved = postingService.postSupplierDeliveryToInventory(factoryId, noteId, userId);
        } catch (RuntimeException e) {
            postingService.markSupplierDeliveryPostingFailed(factoryId, noteId, e.getMessage());
            throw e;
        }

        ArApTransaction payable = arApService.recordPayableFromSource(
                factoryId,
                saved.getSupplierId(),
                PAYABLE_SOURCE_TYPE,
                saved.getId(),
                resolvePayableAmount(saved),
                saved.getDeliveryDate() != null ? saved.getDeliveryDate().plusDays(30) : LocalDate.now().plusDays(30),
                userId,
                "餐饮送货验收入库自动挂账-" + displayNumber(saved));
        saved.setPayableTransactionId(payable.getId());
        saved.setPayablePostedAt(LocalDateTime.now());
        saved.setPayablePostingError(null);
        saved = noteRepository.save(saved);

        // 写 gold (D5 fail-soft: 失败不回滚确认, 记 error message)
        try {
            List<Map<String, Object>> goldLines = new ArrayList<>();
            for (SupplierDeliveryNoteLine line : saved.getLines()) {
                if (line.getUnitPrice() == null) {
                    continue;  // 无单价无法构成进价记录, 跳过
                }
                Map<String, Object> row = new HashMap<>();
                row.put("ingredientName", line.getIngredientName());
                row.put("normalizedName", normalizeName(line.getIngredientName()));
                row.put("rawMaterialTypeId", line.getRawMaterialTypeId());
                row.put("supplierId", saved.getSupplierId());
                row.put("supplierName", saved.getSupplierName());
                row.put("deliveryDate", saved.getDeliveryDate() != null
                        ? saved.getDeliveryDate().toString() : null);
                row.put("unitPrice", line.getUnitPrice());
                row.put("quantity", line.getQuantity());
                row.put("unit", line.getUnit());
                row.put("lineAmount", line.getLineAmount());
                goldLines.add(row);
            }
            if (!goldLines.isEmpty()) {
                int count = goldClient.upsertSupplierPriceBatch(factoryId, saved.getId(), goldLines);
                log.info("送货单 {} 确认 → gold 写入 {} 行进价", saved.getId(), count);
            }
        } catch (Exception e) {
            log.error("送货单 {} 确认成功, 但 gold 进价同步失败 (fail-soft): {}",
                    saved.getId(), e.getMessage());
            saved.setOcrErrorMessage("gold 进价同步失败: " + e.getMessage()
                    + " (送货单已确认, 可稍后重新同步)");
            // best-effort persist the error flag; if this also fails, swallow (confirm already committed)
            try {
                noteRepository.save(saved);
            } catch (Exception ignore) {
                // confirm already persisted above; error flag is advisory
            }
        }
        return saved;
    }

    @Override
    @Transactional
    public SupplierDeliveryNote rejectNote(String factoryId, String noteId,
            String rejectReasonCode, String rejectReasonNote, Long userId) {
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿状态可拒绝 (当前: " + note.getStatus() + ")")
                    .withCode("INVALID_STATUS")
                    .withHint("Refresh the delivery note detail and check the latest status before retrying.")
                    .withHintTarget("status");
        }
        if (rejectReasonCode == null || rejectReasonCode.isBlank()) {
            throw new BusinessException(400, "请选择拒绝原因")
                    .withCode("REJECT_REASON_REQUIRED")
                    .withHint("可选: 图片模糊 / 光线不足 / 单据不对 / 供应商不存在 / 其他")
                    .withHintTarget("rejectReasonCode");
        }
        note.setStatus(DeliveryNoteStatus.REJECTED);
        note.setRejectReasonCode(rejectReasonCode);
        note.setRejectReasonNote(rejectReasonNote);
        return noteRepository.save(note);
    }

    @Override
    @Transactional
    public SupplierDeliveryNote updateLines(String factoryId, String noteId,
            List<SupplierDeliveryNoteDto.LineDto> lines) {
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿状态可编辑行项")
                    .withCode("INVALID_STATUS")
                    .withHint("Refresh the delivery note detail and check the latest status before retrying.")
                    .withHintTarget("status");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(400, "Supplier delivery lines are required")
                    .withCode("LINES_REQUIRED")
                    .withHint("Add at least one ingredient line with quantity and unit before saving.")
                    .withHintTarget("lines");
        }
        note.getLines().clear();  // orphanRemoval 删旧行
        BigDecimal total = BigDecimal.ZERO;
        if (lines != null) {
            for (SupplierDeliveryNoteDto.LineDto dto : lines) {
                SupplierDeliveryNoteLine line = toLineEntity(dto);
                line.setRawMaterialTypeId(dto.getRawMaterialTypeId() != null
                        ? dto.getRawMaterialTypeId()
                        : softMatchMaterial(factoryId, dto.getIngredientName()));
                note.addLine(line);
                if (line.getLineAmount() != null) {
                    total = total.add(line.getLineAmount());
                }
            }
        }
        note.setTotalAmount(total.compareTo(BigDecimal.ZERO) > 0 ? total : null);
        refreshPriceAnomalies(note);
        return noteRepository.save(note);
    }

    @Override
    @Transactional
    public void deleteDraft(String factoryId, String noteId) {
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw new BusinessException(409, "仅草稿可删除")
                    .withCode("INVALID_STATUS")
                    .withHint("Refresh the delivery note detail and check the latest status before retrying.")
                    .withHintTarget("status");
        }
        note.softDelete();
        noteRepository.save(note);
    }

    @Override
    @Transactional
    public SupplierDeliveryNote submitPriceAnomalyApproval(String factoryId, String noteId, Long userId) {
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw invalidDraftStatus();
        }
        refreshPriceAnomalies(note);
        if (!hasPriceAnomaly(note)) {
            throw new BusinessException(400, "该送货单无价格异常，无需提交老板审批")
                    .withCode("PRICE_ANOMALY_NOT_FOUND")
                    .withHint("请直接确认验收入库")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        List<SupplierDeliveryNoteLine> unexplained = note.getLines().stream()
                .filter(line -> Boolean.TRUE.equals(line.getPriceAnomalyFlag()))
                .filter(line -> isBlank(line.getPriceAnomalyExplanation()))
                .toList();
        if (!unexplained.isEmpty()) {
            throw new BusinessException(409, "进价异常需先填写供应商解释: " + unexplained.get(0).getIngredientName())
                    .withCode("PRICE_ANOMALY_EXPLANATION_REQUIRED")
                    .withHint("请在送货单行项目里填写涨价原因和供应商解释，再提交老板审批")
                    .withHintTarget("priceAnomalyExplanation");
        }
        PriceAnomalyApprovalStatus current = note.getPriceAnomalyApprovalStatus() != null
                ? note.getPriceAnomalyApprovalStatus() : PriceAnomalyApprovalStatus.NONE;
        if (current == PriceAnomalyApprovalStatus.PENDING) {
            throw new BusinessException(409, "该异常已在等待老板审批")
                    .withCode("PRICE_ANOMALY_ALREADY_PENDING")
                    .withHint("请通知老板/店长处理价格异常待办")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        if (current == PriceAnomalyApprovalStatus.APPROVED) {
            throw new BusinessException(409, "该异常已批准，可直接确认入库")
                    .withCode("PRICE_ANOMALY_ALREADY_APPROVED")
                    .withHint("请返回送货单详情点击确认验收入库")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.PENDING);
        note.setPriceAnomalySubmittedBy(userId);
        note.setPriceAnomalySubmittedAt(LocalDateTime.now());
        note.setPriceAnomalyApprovedBy(null);
        note.setPriceAnomalyApprovedAt(null);
        note.setPriceAnomalyRejectedBy(null);
        note.setPriceAnomalyRejectedAt(null);
        note.setPriceAnomalyApprovalComment(null);
        SupplierDeliveryNote saved = noteRepository.save(note);
        notifyPriceAnomalyApprovers(factoryId, saved);
        return saved;
    }

    @Override
    @Transactional
    public SupplierDeliveryNote approvePriceAnomaly(String factoryId, String noteId, String comment,
            Long userId, String userRole) {
        assertCanApprovePriceAnomaly(userRole);
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw invalidDraftStatus();
        }
        if (note.getPriceAnomalyApprovalStatus() == PriceAnomalyApprovalStatus.APPROVED) {
            throw new BusinessException(409, "该异常已批准，无需重复审批")
                    .withCode("PRICE_ANOMALY_ALREADY_APPROVED")
                    .withHint("仓管可直接确认验收入库")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        if (note.getPriceAnomalyApprovalStatus() != PriceAnomalyApprovalStatus.PENDING) {
            throw new BusinessException(409, "该送货单未处于待审批状态")
                    .withCode("PRICE_ANOMALY_NOT_PENDING")
                    .withHint("请仓管先提交价格异常审批")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.APPROVED);
        note.setPriceAnomalyApprovedBy(userId);
        note.setPriceAnomalyApprovedAt(LocalDateTime.now());
        note.setPriceAnomalyApprovalComment(comment);
        return noteRepository.save(note);
    }

    @Override
    @Transactional
    public SupplierDeliveryNote rejectPriceAnomaly(String factoryId, String noteId, String comment,
            Long userId, String userRole) {
        assertCanApprovePriceAnomaly(userRole);
        SupplierDeliveryNote note = mustFindDraftOrAny(factoryId, noteId);
        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw invalidDraftStatus();
        }
        if (note.getPriceAnomalyApprovalStatus() == PriceAnomalyApprovalStatus.REJECTED) {
            throw new BusinessException(409, "该异常已驳回")
                    .withCode("PRICE_ANOMALY_ALREADY_REJECTED")
                    .withHint("请采购/仓管重新核对价格或与供应商沟通后修改送货单")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        if (note.getPriceAnomalyApprovalStatus() != PriceAnomalyApprovalStatus.PENDING) {
            throw new BusinessException(409, "该送货单未处于待审批状态")
                    .withCode("PRICE_ANOMALY_NOT_PENDING")
                    .withHint("请仓管先提交价格异常审批")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.REJECTED);
        note.setPriceAnomalyRejectedBy(userId);
        note.setPriceAnomalyRejectedAt(LocalDateTime.now());
        note.setPriceAnomalyApprovalComment(comment);
        return noteRepository.save(note);
    }

    @Override
    public Page<SupplierDeliveryNote> listPendingPriceAnomalyApprovals(
            String factoryId, String userRole, Pageable pageable) {
        assertCanApprovePriceAnomaly(userRole);
        return noteRepository.findByFactoryIdAndPriceAnomalyApprovalStatus(
                factoryId, PriceAnomalyApprovalStatus.PENDING, pageable);
    }

    @Override
    @Transactional
    public SupplierDeliveryNote createFromProcurementConfirmation(String factoryId, Long userId,
            SupplierDeliveryNoteDto.ProcurementConfirmRequest req) {
        if (req == null) {
            throw new BusinessException(400, "采购确认内容为空");
        }
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new BusinessException(400, "请至少添加一行物料")
                    .withCode("LINES_REQUIRED")
                    .withHintTarget("lines");
        }
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setFactoryId(factoryId);
        note.setSourceType(DeliveryNoteSourceType.MANUAL);
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setCreatedBy(userId);
        note.setSupplierId(req.getSupplierId());
        note.setSupplierName(req.getSupplierName());
        note.setDeliveryDate(req.getDeliveryDate() != null ? req.getDeliveryDate() : LocalDate.now());
        note.setExpectedDeliveryDate(req.getExpectedDeliveryDate());
        note.setSourceRequisitionId(req.getSourceRequisitionId());
        note.setProcurementConfirmedBy(userId);
        note.setProcurementConfirmedAt(LocalDateTime.now());
        note.setSupplierContactNote(req.getSupplierContactNote());
        note.setVoiceAudioUrl(req.getVoiceAudioUrl());
        note.setVoiceTranscriptText(req.getVoiceTranscriptText());
        if (req.getSupplierQuotePhotoUrls() != null && !req.getSupplierQuotePhotoUrls().isEmpty()) {
            try {
                note.setSupplierQuotePhotoUrls(objectMapper.writeValueAsString(req.getSupplierQuotePhotoUrls()));
            } catch (Exception e) {
                throw new BusinessException(400, "报价照片列表格式无效");
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierDeliveryNoteDto.LineDto dto : req.getLines()) {
            SupplierDeliveryNoteLine line = toLineEntity(dto);
            line.setRawMaterialTypeId(dto.getRawMaterialTypeId() != null
                    ? dto.getRawMaterialTypeId()
                    : softMatchMaterial(factoryId, dto.getIngredientName()));
            note.addLine(line);
            if (line.getLineAmount() != null) {
                total = total.add(line.getLineAmount());
            }
        }
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            note.setTotalAmount(total);
        }
        refreshPriceAnomalies(note);
        return noteRepository.save(note);
    }

    @Override
    public Map<String, Object> getLimits(String factoryId, LocalDate month) {
        YearMonth ym = YearMonth.from(month != null ? month : LocalDate.now());
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        long confirmed = noteRepository.countByFactoryIdAndStatusAndMonth(
                factoryId, DeliveryNoteStatus.CONFIRMED, start, end);
        long draft = noteRepository.countByFactoryIdAndStatusAndMonth(
                factoryId, DeliveryNoteStatus.DRAFT, start, end);
        Map<String, Object> result = new HashMap<>();
        result.put("month", ym.toString());
        result.put("confirmedCount", confirmed);
        result.put("draftCount", draft);
        result.put("totalCount", confirmed + draft);
        return result;
    }

    // ==================== helpers ====================

    private SupplierDeliveryNote mustFindDraftOrAny(String factoryId, String noteId) {
        return noteRepository.findByIdAndFactoryId(noteId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "送货单不存在: " + noteId)
                        .withHint("请检查单号是否正确"));
    }

    private SupplierDeliveryNoteLine toLineEntity(SupplierDeliveryNoteDto.LineDto dto) {
        SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
        line.setIngredientName(dto.getIngredientName());
        line.setQuantity(dto.getQuantity());
        line.setUnit(dto.getUnit());
        line.setUnitPrice(dto.getUnitPrice());
        line.setBaselineUnitPrice(dto.getBaselineUnitPrice());
        line.setPriceVarianceRate(dto.getPriceVarianceRate());
        line.setPriceAnomalyFlag(Boolean.TRUE.equals(dto.getPriceAnomalyFlag()));
        line.setPriceAnomalyReasonCode(dto.getPriceAnomalyReasonCode());
        line.setPriceAnomalyExplanation(dto.getPriceAnomalyExplanation());
        line.setQcResult(dto.getQcResult());
        line.setMaterialBatchId(dto.getMaterialBatchId());
        line.setRemark(dto.getRemark());
        // 数字联动 (Rule 3): line_amount 缺失但有 qty×price 则自动算
        if (dto.getLineAmount() != null) {
            line.setLineAmount(dto.getLineAmount());
        } else if (dto.getQuantity() != null && dto.getUnitPrice() != null) {
            line.setLineAmount(dto.getQuantity().multiply(dto.getUnitPrice()));
        }
        line.setOcrConfidence(dto.getOcrConfidence());
        return line;
    }

    private void refreshPriceAnomalies(SupplierDeliveryNote note) {
        if (note == null || note.getLines() == null || note.getLines().isEmpty()) {
            return;
        }
        LocalDate deliveryDate = note.getDeliveryDate() != null ? note.getDeliveryDate() : LocalDate.now();
        for (SupplierDeliveryNoteLine line : note.getLines()) {
            BigDecimal unitPrice = line.getUnitPrice();
            BigDecimal baseline = resolveBaselineUnitPrice(note.getFactoryId(), deliveryDate, line);
            line.setBaselineUnitPrice(baseline);
            if (unitPrice == null || baseline == null || baseline.compareTo(BigDecimal.ZERO) <= 0) {
                line.setPriceVarianceRate(null);
                line.setPriceAnomalyFlag(false);
                continue;
            }
            BigDecimal varianceRate = unitPrice.subtract(baseline)
                    .divide(baseline, 4, RoundingMode.HALF_UP);
            line.setPriceVarianceRate(varianceRate);
            line.setPriceAnomalyFlag(varianceRate.compareTo(PRICE_ANOMALY_THRESHOLD) > 0);
            if (!Boolean.TRUE.equals(line.getPriceAnomalyFlag())) {
                line.setPriceAnomalyReasonCode(null);
                line.setPriceAnomalyExplanation(null);
            }
        }
        if (!hasPriceAnomaly(note)) {
            note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.NONE);
            note.setPriceAnomalySubmittedBy(null);
            note.setPriceAnomalySubmittedAt(null);
            note.setPriceAnomalyApprovedBy(null);
            note.setPriceAnomalyApprovedAt(null);
            note.setPriceAnomalyRejectedBy(null);
            note.setPriceAnomalyRejectedAt(null);
            note.setPriceAnomalyApprovalComment(null);
        }
    }

    private boolean hasPriceAnomaly(SupplierDeliveryNote note) {
        return note.getLines() != null && note.getLines().stream()
                .anyMatch(line -> Boolean.TRUE.equals(line.getPriceAnomalyFlag()));
    }

    private void assertPriceAnomalyApproved(SupplierDeliveryNote note) {
        PriceAnomalyApprovalStatus status = note.getPriceAnomalyApprovalStatus() != null
                ? note.getPriceAnomalyApprovalStatus() : PriceAnomalyApprovalStatus.NONE;
        if (status == PriceAnomalyApprovalStatus.NONE) {
            throw new BusinessException(400, "该单存在价格异常，请先提交老板审批")
                    .withCode("PRICE_ANOMALY_APPROVAL_REQUIRED")
                    .withHint("填写解释后点击“提交老板审批”，等待老板/店长批准后再确认入库")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        if (status == PriceAnomalyApprovalStatus.PENDING) {
            throw new BusinessException(409, "该异常仍在等待审批，暂不能入库")
                    .withCode("PRICE_ANOMALY_PENDING_APPROVAL")
                    .withHint("请通知老板/店长处理价格异常待办")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
        if (status == PriceAnomalyApprovalStatus.REJECTED) {
            throw new BusinessException(409, "该异常已被驳回，不能确认入库")
                    .withCode("PRICE_ANOMALY_REJECTED")
                    .withHint("请采购/仓管重新核对价格或与供应商沟通后修改送货单")
                    .withHintTarget("priceAnomalyApprovalStatus");
        }
    }

    private void assertCanApprovePriceAnomaly(String userRole) {
        if (userRole == null || !PRICE_ANOMALY_APPROVER_ROLES.contains(userRole)) {
            throw new BusinessException(403, "仅老板/店长可审批价格异常")
                    .withCode("PRICE_ANOMALY_APPROVAL_FORBIDDEN")
                    .withHint("请使用老板或店长账号登录后操作")
                    .withHintTarget("role");
        }
    }

    private void notifyPriceAnomalyApprovers(String factoryId, SupplierDeliveryNote note) {
        String title = "送货单价格异常待审批";
        String body = "送货单 " + displayNumber(note) + " 存在进价异常，请审批后再允许入库";
        try {
            notificationService.notifyRole(factoryId, FactoryUserRole.factory_super_admin.name(), title, body);
            notificationService.notifyRole(factoryId, FactoryUserRole.restaurant_manager.name(), title, body);
        } catch (Exception e) {
            log.warn("价格异常审批通知发送失败 note={}: {}", note.getId(), e.getMessage());
        }
    }

    private BusinessException invalidDraftStatus() {
        return new BusinessException(409, "只有草稿送货单可操作")
                .withCode("INVALID_STATUS")
                .withHint("请刷新送货单详情，确认当前状态后再操作")
                .withHintTarget("status");
    }

    private BigDecimal resolveBaselineUnitPrice(String factoryId, LocalDate deliveryDate, SupplierDeliveryNoteLine line) {
        if (line == null || isBlank(factoryId)) {
            return null;
        }
        try {
            if (!isBlank(line.getRawMaterialTypeId())) {
                BigDecimal byMaterial = lineRepository.averageRecentConfirmedPriceByMaterial(
                        factoryId, line.getRawMaterialTypeId(), deliveryDate);
                if (byMaterial != null) {
                    return byMaterial;
                }
            }
            if (!isBlank(line.getIngredientName())) {
                return lineRepository.averageRecentConfirmedPriceByIngredientName(
                        factoryId, line.getIngredientName(), deliveryDate);
            }
        } catch (Exception e) {
            log.warn("送货单价格基线计算失败 factory={} ingredient={}: {}",
                    factoryId, line.getIngredientName(), e.getMessage());
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private BigDecimal resolvePayableAmount(SupplierDeliveryNote note) {
        if (note.getTotalAmount() != null && note.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
            return note.getTotalAmount();
        }
        if (note.getLines() == null || note.getLines().isEmpty()) {
            throw missingPayableAmount();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierDeliveryNoteLine line : note.getLines()) {
            BigDecimal lineAmount = line.getLineAmount();
            if (lineAmount == null && line.getQuantity() != null && line.getUnitPrice() != null) {
                lineAmount = line.getQuantity().multiply(line.getUnitPrice());
            }
            if (lineAmount == null || lineAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw missingPayableAmount();
            }
            total = total.add(lineAmount);
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw missingPayableAmount();
        }
        note.setTotalAmount(total);
        return total;
    }

    private BusinessException missingPayableAmount() {
        return new BusinessException(400, "缺少金额，需补录单价/金额")
                .withCode("PAYABLE_AMOUNT_REQUIRED")
                .withHint("请先补录送货单总金额，或为每个食材行补录行金额/单价后再确认入库")
                .withHintTarget("amount");
    }

    private String displayNumber(SupplierDeliveryNote note) {
        if (note.getNoteNumber() != null && !note.getNoteNumber().isBlank()) {
            return note.getNoteNumber();
        }
        return note.getId();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "照片哈希计算失败");
        }
    }

    /** 与 Python _normalize_name 一致: 小写 + strip + 折叠空白。 */
    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return String.join(" ", name.toLowerCase().trim().split("\\s+"));
    }

    private String extFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        if (contentType.contains("png")) return ".png";
        if (contentType.contains("webp")) return ".webp";
        return ".jpg";
    }

    private String jsonStr(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private BigDecimal jsonDecimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
