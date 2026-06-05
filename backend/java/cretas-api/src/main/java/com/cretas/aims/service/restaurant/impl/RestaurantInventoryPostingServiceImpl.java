package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNoteLine;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.DeliveryPostingStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
            validateLine(factoryId, line);
            CreateReceiveRecordRequest.ReceiveItemDTO item = new CreateReceiveRecordRequest.ReceiveItemDTO();
            item.setMaterialTypeId(line.getRawMaterialTypeId());
            item.setMaterialName(line.getIngredientName());
            item.setReceivedQuantity(line.getQuantity());
            item.setUnit(line.getUnit());
            item.setUnitPrice(line.getUnitPrice());
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

    private void validateLine(String factoryId, SupplierDeliveryNoteLine line) {
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
