package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateSaltedDeductionRequest;
import com.cretas.aims.dto.factory.SaltedReportDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.SaltedWarehouseDeduction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.factory.SaltedWarehouseDeductionRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.SaltedWarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 盐化仓独立扣量服务实现 (SP7 F11).
 *
 * <p>复用 MaterialBatch 扣量基建 (usedQuantity 字段)，不重造扣量轮子。
 * 盐化记录作"标记层"，提供独立报表隔离口径。
 * 不破坏通用仓：通用仓扣量路径 (MaterialBatchServiceImpl.useBatchMaterial) 保持不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaltedWarehouseServiceImpl implements SaltedWarehouseService {

    private final SaltedWarehouseDeductionRepository deductionRepo;
    private final FactoryWarehouseRepository warehouseRepo;
    private final MaterialBatchRepository batchRepo;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    // ── CREATE ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SaltedWarehouseDeduction createDeduction(String factoryId,
                                                     CreateSaltedDeductionRequest req,
                                                     Long operatorId) {
        // 1. 幂等查重 (同 orderNumber 防重复提交)
        if (deductionRepo.existsByFactoryIdAndOrderNumberAndDeletedAtIsNull(factoryId, req.getOrderNumber())) {
            throw new BusinessException(409, "盐化供单号 " + req.getOrderNumber() + " 已存在扣量记录，请勿重复提交")
                    .withCode("SALTED_DEDUCTION_DUPLICATE")
                    .withHint("如需查看已有记录请搜索该供单号");
        }

        // 2. 校验仓库类型必须是 SALTED
        FactoryWarehouse warehouse = warehouseRepo
                .findByIdAndFactoryIdAndDeletedAtIsNull(req.getWarehouseId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "盐化仓不存在: " + req.getWarehouseId())
                        .withHint("请在仓库管理中确认盐化仓已配置"));

        if (warehouse.getType() != FactoryWarehouse.WarehouseType.SALTED) {
            throw new BusinessException(422, "仓库 " + warehouse.getName() + " 不是盐化仓，无法创建盐化扣量")
                    .withCode("WAREHOUSE_NOT_SALTED")
                    .withHint("盐化扣量只允许在 type=SALTED 的仓库执行");
        }

        // 3. 验证批次归属 + 可用库存充足
        MaterialBatch batch = batchRepo.findByIdAndFactoryId(req.getMaterialBatchId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + req.getMaterialBatchId())
                        .withHint("请确认批次 ID 正确且属于本工厂"));

        // 批次必须在盐化仓
        if (!req.getWarehouseId().equals(batch.getWarehouseId())) {
            throw new BusinessException(422,
                    "批次 " + batch.getBatchNumber() + " 不在盐化仓 " + warehouse.getName() + " 中")
                    .withCode("BATCH_NOT_IN_SALTED_WAREHOUSE")
                    .withHint("请选择属于该盐化仓的批次");
        }

        // 批次状态必须可用
        if (batch.getStatus() == MaterialBatchStatus.USED_UP
                || batch.getStatus() == MaterialBatchStatus.DEPLETED
                || batch.getStatus() == MaterialBatchStatus.SCRAPPED) {
            throw new BusinessException(422, "批次 " + batch.getBatchNumber() + " 已无库存 (状态: " + batch.getStatus() + ")")
                    .withCode("BATCH_UNAVAILABLE")
                    .withHint("请选择状态为 AVAILABLE 的批次");
        }

        BigDecimal remaining = batch.getRemainingQuantity();
        if (remaining.compareTo(req.getQuantity()) < 0) {
            throw new BusinessException(422,
                    "批次 " + batch.getBatchNumber() + " 剩余库存 " + remaining + " " + batch.getQuantityUnit()
                    + " 不足，扣减 " + req.getQuantity() + " " + batch.getQuantityUnit())
                    .withCode("INSUFFICIENT_STOCK")
                    .withHint("请调整扣减数量或选择其他批次");
        }

        // 4. 扣减 MaterialBatch.usedQuantity (复用现有基建)
        batch.setUsedQuantity(batch.getUsedQuantity().add(req.getQuantity()));
        if (batch.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }
        batchRepo.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, "OUT");

        // 5. 创建盐化扣量记录
        SaltedWarehouseDeduction deduction = SaltedWarehouseDeduction.builder()
                .factoryId(factoryId)
                .warehouseId(req.getWarehouseId())
                .materialBatchId(req.getMaterialBatchId())
                .orderNumber(req.getOrderNumber())
                .deductionDate(req.getDeductionDate() != null ? req.getDeductionDate() : LocalDate.now())
                .quantity(req.getQuantity())
                .quantityUnit(batch.getQuantityUnit())
                .unitPrice(req.getUnitPrice())
                .customerName(req.getCustomerName())
                .operatorId(operatorId)
                .remarks(req.getRemarks())
                .build();
        deduction.computeTotal();

        SaltedWarehouseDeduction saved = deductionRepo.save(deduction);
        log.info("盐化扣量成功: factory={}, warehouse={}, batch={}, qty={} {}, orderNo={}",
                factoryId, warehouse.getName(), batch.getBatchNumber(),
                req.getQuantity(), batch.getQuantityUnit(), req.getOrderNumber());
        return saved;
    }

    // ── REPORT ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SaltedReportDTO getReport(String factoryId,
                                     String warehouseId,
                                     LocalDate startDate,
                                     LocalDate endDate) {
        // warehouseId=null 时查工厂下所有盐化仓; 否则单仓
        List<SaltedWarehouseDeduction> records;
        String warehouseName = null;

        if (warehouseId != null && !warehouseId.isBlank()) {
            FactoryWarehouse warehouse = warehouseRepo
                    .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "盐化仓不存在: " + warehouseId));
            if (warehouse.getType() != FactoryWarehouse.WarehouseType.SALTED) {
                throw new BusinessException(422, "仓库 " + warehouse.getName() + " 不是盐化仓")
                        .withCode("WAREHOUSE_NOT_SALTED");
            }
            warehouseName = warehouse.getName();
            records = deductionRepo.findByWarehouseAndDateRange(factoryId, warehouseId, startDate, endDate);
        } else {
            // 所有盐化仓 — 从分页 repo 查全量 (报表场景数量可控)
            org.springframework.data.domain.Pageable unpaged = org.springframework.data.domain.Pageable.unpaged();
            records = deductionRepo.findByFactoryIdAndDateRange(factoryId, startDate, endDate, unpaged).getContent();
        }

        // Enrich batch info
        List<SaltedReportDTO.LineItem> lines = records.stream().map(d -> {
            String batchNo = null;
            String matName = null;
            try {
                MaterialBatch b = batchRepo.findById(d.getMaterialBatchId()).orElse(null);
                if (b != null) {
                    batchNo = b.getBatchNumber();
                    if (b.getMaterialType() != null) matName = b.getMaterialType().getName();
                }
            } catch (Exception ignored) { /* enrich failure non-fatal */ }

            return SaltedReportDTO.LineItem.builder()
                    .id(d.getId())
                    .orderNumber(d.getOrderNumber())
                    .deductionDate(d.getDeductionDate() != null ? d.getDeductionDate().toString() : null)
                    .quantity(d.getQuantity())
                    .quantityUnit(d.getQuantityUnit())
                    .unitPrice(d.getUnitPrice())
                    .totalAmount(d.getTotalAmount())
                    .customerName(d.getCustomerName())
                    .materialBatchId(d.getMaterialBatchId())
                    .batchNumber(batchNo)
                    .materialName(matName)
                    .remarks(d.getRemarks())
                    .build();
        }).collect(Collectors.toList());

        BigDecimal totalQty = records.stream()
                .map(SaltedWarehouseDeduction::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAmt = records.stream()
                .filter(d -> d.getTotalAmount() != null)
                .map(SaltedWarehouseDeduction::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return SaltedReportDTO.builder()
                .factoryId(factoryId)
                .warehouseId(warehouseId)
                .warehouseName(warehouseName)
                .startDate(startDate.toString())
                .endDate(endDate.toString())
                .totalQuantity(totalQty)
                .totalAmount(totalAmt)
                .lines(lines)
                .lineCount(lines.size())
                .build();
    }

    // ── LIST ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SaltedWarehouseDeduction> listDeductions(String factoryId,
                                                          String warehouseId,
                                                          LocalDate startDate,
                                                          LocalDate endDate) {
        return deductionRepo.findByWarehouseAndDateRange(factoryId, warehouseId, startDate, endDate);
    }
}
