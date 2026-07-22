package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
/**
 * 原材料批次实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Component
public class MaterialBatchMapper {
    /**
     * Entity 转 DTO
     */
    public MaterialBatchDTO toDTO(MaterialBatch batch) {
        if (batch == null) {
            return null;
        }
        MaterialBatchDTO dto = MaterialBatchDTO.builder()
                .id(batch.getId())
                .factoryId(batch.getFactoryId())
                .batchNumber(batch.getBatchNumber())
                .materialTypeId(batch.getMaterialTypeId())
                .supplierId(batch.getSupplierId())
                .receiptDate(batch.getReceiptDate())
                .expireDate(batch.getExpireDate())
                .receiptQuantity(batch.getReceiptQuantity())
                .quantityUnit(batch.getQuantityUnit())
                .boxCount(batch.getBoxCount())
                .weightPerUnit(batch.getWeightPerUnit())
                .totalWeight(batch.getTotalWeight())
                .currentQuantity(batch.getCurrentQuantity())
                .usedQuantity(batch.getUsedQuantity())
                .reservedQuantity(batch.getReservedQuantity())
                .totalValue(batch.getTotalValue())
                .unitPrice(batch.getUnitPrice())
                .totalPrice(batch.getTotalPrice())
                .status(batch.getStatus())
                .statusDisplayName(batch.getStatus() != null ? batch.getStatus().getDisplayName() : null)
                .warehouseId(batch.getWarehouseId())
                .ownership(batch.getOwnership())
                .ownerCustomerId(batch.getOwnerCustomerId())
                .sourceSalesOrderId(batch.getSourceSalesOrderId())
                .sourceSalesOrderItemId(batch.getSourceSalesOrderItemId())
                .storageLocation(batch.getStorageLocation())
                .qualityCertificate(batch.getQualityCertificate())
                .notes(batch.getNotes())
                .factoryNumber(batch.getFactoryNumber())
                .originPlace(batch.getOriginPlace())
                .createdBy(batch.getCreatedBy())
                .lastUsedAt(batch.getLastUsedAt())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .build();
        // 设置原材料信息
        if (batch.getMaterialType() != null) {
            dto.setMaterialName(batch.getMaterialType().getName());
            dto.setMaterialCode(batch.getMaterialType().getCode());
            dto.setMaterialCategory(batch.getMaterialType().getCategory());
            dto.setUnit(batch.getMaterialType().getUnit());
            dto.setStorageType(batch.getMaterialType().getStorageType());
        }
        // 设置供应商信息
        if (batch.getSupplier() != null) {
            dto.setSupplierName(batch.getSupplier().getName());
        }
        // 设置创建人信息
        if (batch.getCreatedByUser() != null) {
            dto.setCreatedByName(batch.getCreatedByUser().getFullName());
        }
        // 计算剩余天数
        if (batch.getExpireDate() != null) {
            long remainingDays = ChronoUnit.DAYS.between(LocalDate.now(), batch.getExpireDate());
            dto.setRemainingDays((int) remainingDays);
        }
        // 计算库存占用率
        if (batch.getReceiptQuantity() != null && batch.getReceiptQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal usedQuantity = batch.getReceiptQuantity().subtract(batch.getCurrentQuantity());
            BigDecimal usageRate = usedQuantity.divide(batch.getReceiptQuantity(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            dto.setUsageRate(usageRate);
        }
        return dto;
    }

    /**
     * CreateRequest 转 Entity
     */
    public MaterialBatch toEntity(CreateMaterialBatchRequest request, String factoryId, Long createdBy) {
        if (request == null) {
            return null;
        }
        MaterialBatch batch = new MaterialBatch();
        batch.setFactoryId(factoryId);
        // 使用用户提供的批次号，如果没有则自动生成
        String batchNumber = (request.getBatchNumber() != null && !request.getBatchNumber().trim().isEmpty())
                ? request.getBatchNumber()
                : generateBatchNumber();
        batch.setBatchNumber(batchNumber);
        batch.setMaterialTypeId(request.getMaterialTypeId());
        batch.setSupplierId(request.getSupplierId());
        batch.setReceiptDate(request.getReceiptDate());
        batch.setReceiptQuantity(request.getReceiptQuantity());
        batch.setQuantityUnit(request.getQuantityUnit());
        // T145: 箱数 (粗略统计用, 可空; 不参与任何库存计算). null 时不填.
        batch.setBoxCount(request.getBoxCount());
        // 处理weightPerUnit: 如果用户未提供，则从totalWeight反算
        if (request.getWeightPerUnit() != null) {
            batch.setWeightPerUnit(request.getWeightPerUnit());
        } else if (request.getTotalWeight() != null && request.getReceiptQuantity() != null) {
            // 从totalWeight反算weightPerUnit
            // R25 (reviewer #14 concern #4): align scale to 4, matching updateEntity (line 219).
            // Pre-R25 create used scale=3, update used scale=4 → create→edit roundtrip with no
            // weightPerUnit could shift the value by ~0.0009 (support-ticket risk).
            BigDecimal calculatedWeightPerUnit = request.getTotalWeight()
                .divide(request.getReceiptQuantity(), 4, RoundingMode.HALF_UP);
            batch.setWeightPerUnit(calculatedWeightPerUnit);
            log.info("自动计算每单位重量: totalWeight={}, receiptQuantity={}, weightPerUnit={}",
                request.getTotalWeight(), request.getReceiptQuantity(), calculatedWeightPerUnit);
        }

        // 注意: totalWeight, currentQuantity, totalQuantity, remainingQuantity, totalValue
        // 现在都是计算属性，不再需要手动设置

        // 计算单价并验证（以总价值为准）
        BigDecimal calculatedUnitPrice = request.getTotalValue()
            .divide(request.getTotalWeight(), 2, RoundingMode.HALF_UP);

        if (request.getUnitPrice() != null) {
            // 用户填写了单价，验证是否一致
            BigDecimal userUnitPrice = request.getUnitPrice();
            BigDecimal difference = calculatedUnitPrice.subtract(userUnitPrice).abs();
            BigDecimal tolerance = calculatedUnitPrice.multiply(new BigDecimal("0.05")); // 5%误差

            if (difference.compareTo(tolerance) > 0) {
                // 误差超过5%，记录警告
                BigDecimal diffPercent = difference.divide(calculatedUnitPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
                log.warn("⚠️ 单价不一致: 用户填写={}元/kg, 系统计算={}元/kg, 差异={}%, 以总价值为准",
                    userUnitPrice, calculatedUnitPrice, diffPercent);

                // 添加备注说明
                String warningNote = String.format("[系统提示] 单价已按总价值自动计算为%.2f元/kg (用户填写%.2f元/kg存在%.2f%%差异)",
                    calculatedUnitPrice, userUnitPrice, diffPercent);
                String originalNotes = request.getNotes() != null ? request.getNotes() : "";
                batch.setNotes(originalNotes + (originalNotes.isEmpty() ? "" : "\n") + warningNote);
            }
        }

        // 始终使用计算值（以总价值为准）
        batch.setUnitPrice(calculatedUnitPrice);
        // 注意: totalPrice 现在是计算属性 (unitPrice × receiptQuantity)，不再需要手动设置
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setStorageLocation(request.getStorageLocation());
        batch.setQualityCertificate(request.getQualityCertificate());
        // notes已在上面的验证逻辑中设置（如果有警告的话）
        // 如果没有警告，使用原始notes
        if (batch.getNotes() == null) {
            batch.setNotes(request.getNotes());
        }
        batch.setCreatedBy(createdBy);

        // SP4-A4: 生产厂家编号 + 产地 (nullable, 一物一码标签用)
        batch.setFactoryNumber(request.getFactoryNumber());
        batch.setOriginPlace(request.getOriginPlace());

        // P0-17: 发起单来源
        batch.setSourceDocType(request.getSourceDocType());
        batch.setSourceDocId(request.getSourceDocId());

        // D1 双仓流转 (2026-05-10 spec, PR #309 A1=A): 透传 DTO 显式 warehouseId.
        // null → MaterialBatchServiceImpl 默认 WH-LOG.
        if (request.getWarehouseId() != null && !request.getWarehouseId().isBlank()) {
            batch.setWarehouseId(request.getWarehouseId());
        }

        // 设置到期日期（如果提供了）
        if (request.getExpireDate() != null) {
            batch.setExpireDate(request.getExpireDate());
        }

        // TODO: productionDate字段等待数据库schema添加后支持
        // if (request.getProductionDate() != null) {
        //     batch.setProductionDate(request.getProductionDate());
        // }

        return batch;
    }

    /**
     * 更新实体
     *
     * W-04 fix (Round 8): previous version silently dropped receiptQuantity /
     * totalWeight / expireDate / quantityUnit on edit — PUT /material-batches/{id}
     * returned 200 OK while the DB row stayed unchanged for those fields.
     * Users correcting a batch's quantity, unit, weight or expiry date had no
     * way to persist the change via UI. Now all form-editable fields are
     * mapped with null-guards so partial updates still work.
     *
     * Intentionally immutable on edit (not mapped even if present in the DTO):
     *   - batchNumber  (tracking identifier, frontend disables input on edit)
     *   - factoryId    (owner, controlled by the URL path variable)
     *   - productionDate / sourceDocType / sourceDocId  (historical/audit)
     *   - customFields (flows through a separate DynamicFieldService path)
     */
    public void updateEntity(MaterialBatch batch, UpdateMaterialBatchRequest request) {
        if (request.getMaterialTypeId() != null) {
            batch.setMaterialTypeId(request.getMaterialTypeId());
        }
        if (request.getSupplierId() != null) {
            batch.setSupplierId(request.getSupplierId());
        }
        if (request.getReceiptDate() != null) {
            batch.setReceiptDate(request.getReceiptDate());
        }
        // W-04: previously dropped
        if (request.getReceiptQuantity() != null) {
            batch.setReceiptQuantity(request.getReceiptQuantity());
        }
        if (request.getQuantityUnit() != null) {
            batch.setQuantityUnit(request.getQuantityUnit());
        }
        // T145: 箱数 (粗略统计用, 不参与库存计算).
        if (request.getBoxCount() != null) {
            batch.setBoxCount(request.getBoxCount());
        }
        // R24 P1 (qa-prompt v2.4 Rule 17.2 sweep): pre-R24 weightPerUnit was only set
        // indirectly via totalWeight.divide(qty). API consumers / mobile / AI tools
        // sending weightPerUnit directly (without totalWeight) had it silent-dropped.
        // UI form (warehouse/materials/list.vue) doesn't expose weightPerUnit so the
        // gap was hidden — but the create path (toEntity line 113) does map it
        // directly, so symmetry was broken. Fix: map directly first, then derive from
        // totalWeight only if weightPerUnit wasn't explicitly sent.
        if (request.getWeightPerUnit() != null) {
            batch.setWeightPerUnit(request.getWeightPerUnit());
        }
        // totalWeight is a transient computed property (weightPerUnit × receiptQuantity).
        // If the form sends it (and weightPerUnit wasn't already set above), derive
        // weightPerUnit from receiptQuantity to persist.
        if (request.getWeightPerUnit() == null
                && request.getTotalWeight() != null
                && request.getTotalWeight().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal qty = batch.getReceiptQuantity();
            if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                batch.setWeightPerUnit(request.getTotalWeight().divide(qty, 4, RoundingMode.HALF_UP));
            }
        }
        if (request.getExpireDate() != null) {
            batch.setExpireDate(request.getExpireDate());
        }
        if (request.getTotalValue() != null) {
            // 注意: totalValue 现在是计算属性，不再存储
            // C1 fix (reviewer Apr 24): 如 form 同时发了 totalWeight 用 request 值,
            // 否则回退到 batch.getTotalWeight() (weightPerUnit × receiptQuantity, 用
            // 刚更新的 qty). 避免 request 只发 {qty,totalValue} 时 unitPrice 算错.
            BigDecimal effectiveTotalWeight = request.getTotalWeight() != null
                ? request.getTotalWeight()
                : batch.getTotalWeight();
            if (effectiveTotalWeight != null && effectiveTotalWeight.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newUnitPrice = request.getTotalValue()
                    .divide(effectiveTotalWeight, 2, RoundingMode.HALF_UP);
                batch.setUnitPrice(newUnitPrice);
            }
        }
        if (request.getUnitPrice() != null) {
            batch.setUnitPrice(request.getUnitPrice());
        }
        if (request.getStorageLocation() != null) {
            batch.setStorageLocation(request.getStorageLocation());
        }
        if (request.getQualityCertificate() != null) {
            batch.setQualityCertificate(request.getQualityCertificate());
        }
        if (request.getNotes() != null) {
            batch.setNotes(request.getNotes());
        }
        // SP4-A4: null-guard — don't overwrite existing value with null
        if (request.getFactoryNumber() != null) {
            batch.setFactoryNumber(request.getFactoryNumber());
        }
        if (request.getOriginPlace() != null) {
            batch.setOriginPlace(request.getOriginPlace());
        }
    }

    /**
     * 生成批次号
     * 格式: MAT-YYYYMMDD-HHMMSS
     */
    private String generateBatchNumber() {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String timeStr = now.format(DateTimeFormatter.ofPattern("HHmmss"));
        return String.format("MAT-%s-%s", dateStr, timeStr);
    }
}
