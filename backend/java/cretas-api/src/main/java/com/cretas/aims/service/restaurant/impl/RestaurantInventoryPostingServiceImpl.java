package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.DeliveryPostingStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 餐饮送货单过账到采购收货/批次库存.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantInventoryPostingServiceImpl implements RestaurantInventoryPostingService {

    private final SupplierDeliveryNoteRepository noteRepository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final PurchaseService purchaseService;
    private final WarehouseResolver warehouseResolver;
    private final MaterialBatchService materialBatchService;
    private final MaterialRequisitionRepository materialRequisitionRepository;
    private final WastageRecordRepository wastageRecordRepository;
    private final StocktakingRecordRepository stocktakingRecordRepository;
    private final MaterialUomConverter materialUomConverter;

    @Override
    @Transactional
    public SupplierDeliveryNote postSupplierDeliveryToInventory(String factoryId, String noteId, Long userId) {
        SupplierDeliveryNote note = noteRepository.findByIdAndFactoryId(noteId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "送货单不存在: " + noteId)
                        .withHint("请刷新送货单列表后重试"));

        if (note.getStatus() != DeliveryNoteStatus.DRAFT) {
            throw new BusinessException(409, "只有草稿送货单可验收入库")
                    .withCode("INVALID_STATUS")
                    .withHint("该送货单已确认或已拒绝，请刷新列表查看最新状态");
        }
        if (note.getPostingStatus() == DeliveryPostingStatus.POSTED) {
            throw new BusinessException(409, "该送货单已入库，请勿重复操作")
                    .withCode("ALREADY_POSTED")
                    .withHint("可在入库记录或批次库存中查看已生成的批次");
        }
        if (note.getLines() == null || note.getLines().isEmpty()) {
            throw new BusinessException(400, "送货单无行项目，不能验收入库")
                    .withHint("请先补充至少一行食材、数量和单位");
        }
        if (!StringUtils.hasText(note.getSupplierId())) {
            throw new BusinessException(400, "送货单未绑定供应商，不能验收入库")
                    .withHint("请先选择供应商后再确认入库");
        }
        supplierRepository.findByIdAndFactoryId(note.getSupplierId(), factoryId)
                .orElseThrow(() -> new BusinessException(400, "供应商不存在或不属于当前组织")
                        .withHint("请先在供应商管理中新建或选择正确供应商"));

        note.setPostingStatus(DeliveryPostingStatus.POSTING);
        note.setPostingError(null);
        noteRepository.saveAndFlush(note);

        CreateReceiveRecordRequest request = new CreateReceiveRecordRequest();
        request.setPurchaseOrderId(null);
        request.setSupplierId(note.getSupplierId());
        request.setReceiveDate(note.getDeliveryDate());
        String warehouseId = StringUtils.hasText(note.getWarehouseId())
                ? note.getWarehouseId()
                : warehouseResolver.resolveLogisticsId(factoryId);
        request.setWarehouseId(warehouseId);
        request.setRemark("餐饮送货单 " + displayNumber(note));

        List<CreateReceiveRecordRequest.ReceiveItemDTO> receiveItems = new ArrayList<>();
        for (SupplierDeliveryNoteLine line : note.getLines()) {
            RawMaterialType material = validateLine(factoryId, line);
            // 🔒 落库单位归一化守卫 (T155-B chunk3): OCR 自由文本单位 vs 物料标准单位.
            NormalizedQuantity normalized = normalizeToMaterialUnit(line, material);
            CreateReceiveRecordRequest.ReceiveItemDTO item = new CreateReceiveRecordRequest.ReceiveItemDTO();
            item.setMaterialTypeId(line.getRawMaterialTypeId());
            item.setMaterialName(line.getIngredientName());
            item.setReceivedQuantity(normalized.quantity());
            item.setUnit(normalized.unit());
            item.setUnitPrice(adjustUnitPrice(line, normalized));
            item.setQcResult(line.getQcResult());
            item.setRemark(line.getRemark());
            receiveItems.add(item);
        }
        request.setItems(receiveItems);

        PurchaseReceiveRecord draft = purchaseService.createReceiveRecord(factoryId, request, userId);
        PurchaseReceiveRecord confirmed = purchaseService.confirmReceive(factoryId, draft.getId(), userId);
        bindMaterialBatchIds(note.getLines(), confirmed.getItems());

        note.setWarehouseId(warehouseId);
        note.setReceiveRecordId(confirmed.getId());
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        note.setPostingStatus(DeliveryPostingStatus.POSTED);
        note.setPostedAt(LocalDateTime.now());
        note.setPostedBy(userId);
        note.setConfirmedAt(note.getPostedAt());
        note.setConfirmedBy(userId);
        note.setPostingError(null);

        SupplierDeliveryNote saved = noteRepository.save(note);
        log.info("餐饮送货单过账成功: factoryId={}, noteId={}, receiveRecordId={}",
                factoryId, noteId, confirmed.getId());
        return saved;
    }

    @Override
    @Transactional
    public String postMaterialRequisitionIssue(String factoryId, MaterialRequisition requisition, Long userId) {
        if (requisition.getInventoryPostedAt() != null) {
            return requisition.getInventoryPostingDetail();
        }
        validateMaterial(factoryId, requisition.getRawMaterialTypeId(), "领料单");
        BigDecimal quantity = positiveQuantity(requisition.getActualQuantity(), "领料实发数量", "actualQuantity");
        String detail = consumeMaterial(factoryId, requisition.getRawMaterialTypeId(), requisition.getMaterialBatchId(),
                quantity, "餐饮领料扣库存 " + displayId(requisition.getRequisitionNumber(), requisition.getId()));
        requisition.setInventoryPostedAt(LocalDateTime.now());
        requisition.setInventoryPostedBy(userId);
        requisition.setInventoryPostingDetail(detail);
        requisition.setInventoryPostingError(null);
        requisition.setActualCost(calculatePostedCost(detail));
        if (!StringUtils.hasText(requisition.getMaterialBatchId())) {
            requisition.setMaterialBatchId(firstBatchId(detail));
        }
        return detail;
    }

    @Override
    @Transactional
    public String postWastageDeduction(String factoryId, WastageRecord record, Long userId) {
        if (record.getInventoryPostedAt() != null) {
            return record.getInventoryPostingDetail();
        }
        validateMaterial(factoryId, record.getRawMaterialTypeId(), "损耗记录");
        BigDecimal quantity = positiveQuantity(record.getQuantity(), "损耗数量", "quantity");
        String detail = consumeMaterial(factoryId, record.getRawMaterialTypeId(), record.getMaterialBatchId(),
                quantity, "餐饮损耗扣库存 " + displayId(record.getWastageNumber(), record.getId()));
        record.setInventoryPostedAt(LocalDateTime.now());
        record.setInventoryPostedBy(userId);
        record.setInventoryPostingDetail(detail);
        record.setInventoryPostingError(null);
        if (!StringUtils.hasText(record.getMaterialBatchId())) {
            record.setMaterialBatchId(firstBatchId(detail));
        }
        record.setEstimatedCost(calculatePostedCost(detail));
        return detail;
    }

    @Override
    @Transactional
    public String postStocktakingAdjustment(String factoryId, StocktakingRecord record, Long userId) {
        if (record.getInventoryPostedAt() != null) {
            return record.getInventoryPostingDetail();
        }
        validateMaterial(factoryId, record.getRawMaterialTypeId(), "盘点单");
        BigDecimal actualQuantity = nonNegativeQuantity(record.getActualQuantity(), "实盘数量", "actualQuantity");
        BigDecimal systemQuantity = record.getSystemQuantity();
        if (systemQuantity == null) {
            systemQuantity = totalCurrentQuantity(factoryId, record.getRawMaterialTypeId());
            record.setSystemQuantity(systemQuantity);
        }
        record.setActualQuantity(actualQuantity);
        record.calculateDifference();

        BigDecimal difference = record.getDifferenceQuantity() != null
                ? record.getDifferenceQuantity()
                : actualQuantity.subtract(systemQuantity);
        String detail;
        if (difference.signum() < 0) {
            detail = consumeMaterial(factoryId, record.getRawMaterialTypeId(), null, difference.abs(),
                    "餐饮盘亏扣库存 " + displayId(record.getStocktakingNumber(), record.getId()));
        } else if (difference.signum() > 0) {
            detail = increaseExistingBatch(factoryId, record.getRawMaterialTypeId(), difference,
                    "餐饮盘盈调库存 " + displayId(record.getStocktakingNumber(), record.getId()), userId);
        } else {
            detail = "MATCH:0";
        }

        record.setInventoryPostedAt(LocalDateTime.now());
        record.setInventoryPostedBy(userId);
        record.setInventoryPostingDetail(detail);
        record.setInventoryPostingError(null);
        record.setDifferenceAmount(calculatePostedCost(detail));
        return detail;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSupplierDeliveryPostingFailed(String factoryId, String noteId, String errorMessage) {
        noteRepository.findByIdAndFactoryId(noteId, factoryId).ifPresent(note -> {
            if (note.getStatus() != DeliveryNoteStatus.DRAFT
                    || note.getPostingStatus() == DeliveryPostingStatus.POSTED) {
                return;
            }
            note.setPostingStatus(DeliveryPostingStatus.FAILED);
            note.setPostingError(trimError(errorMessage));
            noteRepository.save(note);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMaterialRequisitionPostingFailed(String factoryId, String requisitionId, String errorMessage) {
        materialRequisitionRepository.findByIdAndFactoryId(requisitionId, factoryId).ifPresent(req -> {
            if (req.getInventoryPostedAt() != null) {
                return;
            }
            req.setInventoryPostingError(trimError(errorMessage));
            materialRequisitionRepository.save(req);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markWastagePostingFailed(String factoryId, String wastageId, String errorMessage) {
        wastageRecordRepository.findByIdAndFactoryId(wastageId, factoryId).ifPresent(record -> {
            if (record.getInventoryPostedAt() != null) {
                return;
            }
            record.setInventoryPostingError(trimError(errorMessage));
            wastageRecordRepository.save(record);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStocktakingPostingFailed(String factoryId, String recordId, String errorMessage) {
        stocktakingRecordRepository.findByIdAndFactoryId(recordId, factoryId).ifPresent(record -> {
            if (record.getInventoryPostedAt() != null) {
                return;
            }
            record.setInventoryPostingError(trimError(errorMessage));
            stocktakingRecordRepository.save(record);
        });
    }

    private void validateMaterial(String factoryId, String rawMaterialTypeId, String docName) {
        if (!StringUtils.hasText(rawMaterialTypeId)) {
            throw new BusinessException(400, docName + "缺少食材主数据，不能扣减库存")
                    .withCode("MISSING_RAW_MATERIAL")
                    .withHint("请先选择正确食材，再重新提交审批")
                    .withHintTarget("rawMaterialTypeId");
        }
        RawMaterialType material = rawMaterialTypeRepository.findById(rawMaterialTypeId)
                .orElseThrow(() -> new BusinessException(400, docName + "食材主数据不存在: " + rawMaterialTypeId)
                        .withCode("MISSING_RAW_MATERIAL")
                        .withHint("请先维护食材主数据，或重新选择正确食材")
                        .withHintTarget("rawMaterialTypeId"));
        if (!factoryId.equals(material.getFactoryId())) {
            throw new BusinessException(403, docName + "食材不属于当前组织: " + rawMaterialTypeId)
                    .withCode("RAW_MATERIAL_FACTORY_MISMATCH")
                    .withHint("请切换到正确组织，或重新选择当前门店下的食材")
                    .withHintTarget("rawMaterialTypeId");
        }
    }

    private BigDecimal positiveQuantity(BigDecimal quantity, String label, String hintTarget) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(400, label + "必须大于 0")
                    .withCode("INVALID_QUANTITY")
                    .withHint("请填写大于 0 的数量后重试")
                    .withHintTarget(hintTarget);
        }
        return quantity;
    }

    private BigDecimal nonNegativeQuantity(BigDecimal quantity, String label, String hintTarget) {
        if (quantity == null || quantity.signum() < 0) {
            throw new BusinessException(400, label + "不能为负数")
                    .withCode("INVALID_QUANTITY")
                    .withHint("请填写 0 或正数后重试")
                    .withHintTarget(hintTarget);
        }
        return quantity;
    }

    private String consumeMaterial(String factoryId, String rawMaterialTypeId, String preferredBatchId,
                                   BigDecimal quantity, String reason) {
        List<MaterialBatchDTO> batches;
        if (StringUtils.hasText(preferredBatchId)) {
            MaterialBatchDTO batch = materialBatchService.getMaterialBatchById(factoryId, preferredBatchId);
            if (!rawMaterialTypeId.equals(batch.getMaterialTypeId())) {
                throw new BusinessException(400, "选择的批次不属于该食材，不能扣库存")
                        .withCode("BATCH_MATERIAL_MISMATCH")
                        .withHint("请重新选择该食材对应的库存批次")
                        .withHintTarget("materialBatchId");
            }
            batches = List.of(batch);
        } else {
            batches = materialBatchService.getFIFOBatches(factoryId, rawMaterialTypeId, quantity);
        }

        BigDecimal available = batches.stream()
                .map(this::currentQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(quantity) < 0) {
            throw new BusinessException(409, "库存不足，当前可用 " + available.toPlainString()
                    + "，本次需要 " + quantity.toPlainString())
                    .withCode("INSUFFICIENT_INVENTORY")
                    .withHint("请先完成供应商验收入库，或选择有库存的批次后再审批")
                    .withHintTarget("rawMaterialTypeId");
        }

        BigDecimal remaining = quantity;
        List<String> details = new ArrayList<>();
        for (MaterialBatchDTO batch : batches) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal useQty = currentQuantity(batch).min(remaining);
            if (useQty.signum() <= 0) {
                continue;
            }
            materialBatchService.useBatchQuantity(factoryId, batch.getId(), useQty);
            details.add(batch.getId() + ":" + useQty.toPlainString() + ":" + priceText(batch.getUnitPrice()));
            remaining = remaining.subtract(useQty);
        }
        return String.join(";", details);
    }

    private String increaseExistingBatch(String factoryId, String rawMaterialTypeId, BigDecimal quantity,
                                         String reason, Long userId) {
        List<MaterialBatchDTO> batches = materialBatchService.getFIFOBatches(factoryId, rawMaterialTypeId, quantity);
        if (batches.isEmpty()) {
            throw new BusinessException(409, "没有可调整的库存批次，不能记录盘盈")
                    .withCode("NO_ADJUSTABLE_BATCH")
                    .withHint("请先为该食材完成一次验收入库，再重新完成盘点")
                    .withHintTarget("rawMaterialTypeId");
        }
        MaterialBatchDTO batch = batches.get(0);
        BigDecimal newQuantity = currentQuantity(batch).add(quantity);
        materialBatchService.adjustBatchQuantity(factoryId, batch.getId(), newQuantity, reason, userId);
        return batch.getId() + ":" + quantity.toPlainString() + ":" + priceText(batch.getUnitPrice());
    }

    private BigDecimal totalCurrentQuantity(String factoryId, String rawMaterialTypeId) {
        return materialBatchService.getMaterialBatchesByType(factoryId, rawMaterialTypeId).stream()
                .map(this::currentQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculatePostedCost(String detail) {
        if (!StringUtils.hasText(detail)) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (String item : detail.split(";")) {
            String[] parts = item.split(":");
            if (parts.length < 3 || "null".equals(parts[2])) {
                continue;
            }
            total = total.add(new BigDecimal(parts[1]).multiply(new BigDecimal(parts[2])));
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal currentQuantity(MaterialBatchDTO batch) {
        return batch.getCurrentQuantity() != null ? batch.getCurrentQuantity() : BigDecimal.ZERO;
    }

    private String firstBatchId(String detail) {
        if (!StringUtils.hasText(detail) || !detail.contains(":")) {
            return null;
        }
        return detail.split(":", 2)[0];
    }

    private String priceText(BigDecimal unitPrice) {
        return unitPrice == null ? "null" : unitPrice.toPlainString();
    }

    private String displayId(String number, String id) {
        return StringUtils.hasText(number) ? number : id;
    }

    private RawMaterialType validateLine(String factoryId, SupplierDeliveryNoteLine line) {
        if (!StringUtils.hasText(line.getRawMaterialTypeId())) {
            throw new BusinessException(400, "食材未匹配主数据，不能验收入库: " + line.getIngredientName())
                    .withHint("请先在送货单行项目中选择正确食材");
        }
        RawMaterialType material = rawMaterialTypeRepository.findById(line.getRawMaterialTypeId())
                .orElseThrow(() -> new BusinessException(400, "食材主数据不存在: " + line.getRawMaterialTypeId())
                        .withHint("请重新选择食材后再确认入库"));
        if (!factoryId.equals(material.getFactoryId())) {
            throw new BusinessException(403, "食材不属于当前组织: " + line.getIngredientName())
                    .withHint("请重新选择当前门店/组织下的食材");
        }
        if (line.getQuantity() == null || line.getQuantity().signum() <= 0) {
            throw new BusinessException(400, "送货数量必须大于 0: " + line.getIngredientName())
                    .withHint("请检查该行到货数量");
        }
        if (!StringUtils.hasText(line.getUnit())) {
            throw new BusinessException(400, "送货单位不能为空: " + line.getIngredientName())
                    .withHint("请填写 kg、斤、包等单位");
        }
        return material;
    }

    /**
     * 🔒 落库单位归一化守卫 (T155-B chunk3) — 改善两业态, 对工厂业态关键。
     *
     * <p>OCR 识别的自由文本单位 (line.getUnit(), 如 "斤"/"个"/"pcs") 必须与物料
     * 标准单位 ({@link RawMaterialType#getUnit()}, 库存权威单位) 对账后再落批次,
     * 否则会创建错误单位的库存批次 (kg口径 被污染)。
     *
     * <ul>
     *   <li>物料无标准单位 → 沿用 OCR 单位 (无可对账基准, 不阻断)。</li>
     *   <li>同单位 → 透传。</li>
     *   <li>同维度可换算 (斤↔kg / g↔kg / ml↔L) → 换算数量到物料标准单位。</li>
     *   <li>离散等价别名 (个/pcs/件/只) → 同维度透传 (数量不变, 统一为标准单位名)。</li>
     *   <li>抄码料 (每箱重量不一) → 跳过按规格换算, 沿用 OCR 单位 (不阻断)。</li>
     *   <li>确实不兼容 → fail-loud (绝不静默创建错误单位批次)。</li>
     * </ul>
     */
    private NormalizedQuantity normalizeToMaterialUnit(SupplierDeliveryNoteLine line, RawMaterialType material) {
        String ocrUnit = line.getUnit();
        String materialUnit = material != null ? material.getUnit() : null;
        BigDecimal qty = line.getQuantity();

        // 物料无标准单位 → 无对账基准, 沿用 OCR 单位 (不阻断)。
        if (!StringUtils.hasText(materialUnit)) {
            return new NormalizedQuantity(qty, ocrUnit);
        }
        // 同单位 (大小写/空白无关) → 透传, 用物料标准单位名规范化。
        if (sameUnit(ocrUnit, materialUnit)) {
            return new NormalizedQuantity(qty, materialUnit);
        }
        // 离散等价别名 (个/pcs/件/只) → 数量不变, 统一为物料标准单位名。
        if (sameDiscreteUnit(ocrUnit, materialUnit)) {
            return new NormalizedQuantity(qty, materialUnit);
        }

        // 经 MaterialUomConverter (T156 注入别名/装箱规格换算) 把 OCR 量折算到物料标准单位。
        MaterialUomConverter.ConversionResult result = materialUomConverter.toComparableQuantity(
                line.getRawMaterialTypeId(), qty, ocrUnit, materialUnit);
        if (result.isConverted()) {
            return new NormalizedQuantity(result.getQuantity(), materialUnit);
        }
        if (result.isAbacaSkip()) {
            // 抄码料每箱重量不一 — 没有确定系数, 沿用 OCR 单位/数量不阻断。
            log.info("[UOM] 送货单抄码料 {} ({}↔{}) 沿用 OCR 单位入库",
                    line.getIngredientName(), ocrUnit, materialUnit);
            return new NormalizedQuantity(qty, ocrUnit);
        }
        // 本地兜底: 斤↔kg (0.5kg/斤) — converter 未配置同维度换算时仍正确归一。
        BigDecimal jinKg = convertJinKg(qty, ocrUnit, materialUnit);
        if (jinKg != null) {
            return new NormalizedQuantity(jinKg, materialUnit);
        }

        // 确实不兼容 → fail-loud (Rule 2 context + Rule 5 actionHint)。
        throw new BusinessException(400, "送货单单位「" + ocrUnit + "」与物料「"
                + line.getIngredientName() + "」标准单位「" + materialUnit + "」不一致，请核对")
                .withCode("DELIVERY_UNIT_MISMATCH")
                .withHint("请把送货单该行单位改为「" + materialUnit
                        + "」(或可换算单位 kg/斤/g) 后再确认入库")
                .withHintTarget("unit");
    }

    /**
     * 单位换算后单价校正: 数量被换算 (qty×factor) 时, 单价需反向除以同一 factor,
     * 使行金额 (qty×price) 守恒。仅当数量实际改变且单位实际改变时校正。
     */
    private BigDecimal adjustUnitPrice(SupplierDeliveryNoteLine line, NormalizedQuantity normalized) {
        BigDecimal originalQty = line.getQuantity();
        BigDecimal unitPrice = line.getUnitPrice();
        if (unitPrice == null || originalQty == null || originalQty.signum() == 0
                || normalized.quantity() == null || normalized.quantity().signum() == 0) {
            return unitPrice;
        }
        // 单位未变 → 单价不变 (避免浮点 churn)。
        if (sameUnit(line.getUnit(), normalized.unit())
                || sameDiscreteUnit(line.getUnit(), normalized.unit())) {
            return unitPrice;
        }
        // factor = 新量 / 原量; 新单价 = 原单价 / factor = 原单价 × 原量 / 新量。
        return unitPrice.multiply(originalQty)
                .divide(normalized.quantity(), 6, java.math.RoundingMode.HALF_UP);
    }

    private static boolean sameUnit(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** 离散计件等价别名: 个 / pcs / 件 / 只 视为同一维度 (数量 1:1)。 */
    private static boolean sameDiscreteUnit(String a, String b) {
        java.util.Set<String> discrete = java.util.Set.of("个", "pcs", "pc", "件", "只");
        if (a == null || b == null) {
            return false;
        }
        return discrete.contains(a.trim().toLowerCase()) && discrete.contains(b.trim().toLowerCase());
    }

    /** 斤↔kg 本地兜底换算 (1 斤 = 0.5 kg)。非该方向返回 null (caller 继续 fail-loud)。 */
    private static BigDecimal convertJinKg(BigDecimal qty, String fromUnit, String toUnit) {
        if (qty == null || fromUnit == null || toUnit == null) {
            return null;
        }
        String from = fromUnit.trim().toLowerCase();
        String to = toUnit.trim().toLowerCase();
        if ("斤".equals(from) && "kg".equals(to)) {
            return qty.multiply(new BigDecimal("0.5"));
        }
        if ("kg".equals(from) && "斤".equals(to)) {
            return qty.multiply(new BigDecimal("2"));
        }
        return null;
    }

    /** 归一化后的数量 + 单位 (落批次用)。 */
    private record NormalizedQuantity(BigDecimal quantity, String unit) {
    }

    private void bindMaterialBatchIds(List<SupplierDeliveryNoteLine> lines, List<PurchaseReceiveItem> receiveItems) {
        if (lines == null || receiveItems == null || lines.size() != receiveItems.size()) {
            throw new BusinessException(500, "入库批次回写失败：送货行与收货行数量不一致")
                    .withHint("请联系管理员检查送货单过账日志");
        }
        for (int i = 0; i < lines.size(); i++) {
            SupplierDeliveryNoteLine line = lines.get(i);
            PurchaseReceiveItem item = receiveItems.get(i);
            if (!line.getRawMaterialTypeId().equals(item.getMaterialTypeId())) {
                throw new BusinessException(500, "入库批次回写失败：送货行与收货行食材不一致")
                        .withHint("请联系管理员检查送货单过账日志");
            }
            line.setMaterialBatchId(item.getMaterialBatchId());
        }
    }

    private String displayNumber(SupplierDeliveryNote note) {
        if (StringUtils.hasText(note.getNoteNumber())) {
            return note.getNoteNumber();
        }
        return note.getId();
    }

    private String trimError(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return "过账失败";
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }
}
