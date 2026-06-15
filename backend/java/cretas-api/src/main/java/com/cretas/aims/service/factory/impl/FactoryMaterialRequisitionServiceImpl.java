package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem.MaterialCategory;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.inventory.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 工厂物料需求单 Service 实现 (P0-5, W2-3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryMaterialRequisitionServiceImpl implements FactoryMaterialRequisitionService {

    private final FactoryMaterialRequisitionRepository repository;
    private final FactoryMaterialRequisitionItemRepository itemRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final BomItemRepository bomItemRepository;
    private final TransferService transferService;
    private final FactoryWarehouseRepository warehouseRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final ProductionMaterialReturnRepository productionMaterialReturnRepository;

    /**
     * T143: 物料单位换算器 (箱↔kg). required=false 兼容单测. 物料需求单的需求量应以
     * 库存单位 (箱) 记录, 与仓库实际领料口径一致, 而不是 BOM 配方单位 (g).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    /** T144: 读库存单位 (回退用 RawMaterialType.unit). required=false. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "material_requisition", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * T144: 读取物料的实际库存单位 = AVAILABLE 批次的 {@code MaterialBatch.quantityUnit} (称重口径 kg),
     * <b>不是</b> {@code RawMaterialType.unit} (箱). 物料需求单需求量与仓库实际称重领料口径一致.
     *
     * <p>无可用批次时回退 RawMaterialType.unit. 各批次单位混用时记 warning 取最常见.
     */
    private String resolveMaterialStockUnit(String factoryId, String materialTypeId) {
        if (materialTypeId == null) {
            return null;
        }
        if (materialBatchRepository != null) {
            try {
                java.util.List<String> units = materialBatchRepository
                        .findStockUnitsByMaterialType(factoryId, materialTypeId);
                if (units != null && !units.isEmpty()) {
                    if (units.size() > 1) {
                        log.warn("物料 {} 可用批次单位混用 {}, 取最常见 {}", materialTypeId, units, units.get(0));
                    }
                    return units.get(0);
                }
            } catch (Exception e) {
                log.debug("读取批次库存单位失败: {} ({})", materialTypeId, e.getMessage());
            }
        }
        // 无可用批次: 回退 RawMaterialType.unit
        if (rawMaterialTypeRepository == null) {
            return null;
        }
        try {
            return rawMaterialTypeRepository.findById(materialTypeId)
                    .map(com.cretas.aims.entity.RawMaterialType::getUnit)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("读取物料库存单位失败: {} ({})", materialTypeId, e.getMessage());
            return null;
        }
    }

    /**
     * T159-B Change4: 优先从 RawMaterialType 主数据读取原料真实名称, BOM 快照作 fallback.
     */
    private String resolveLiveMaterialName(String materialTypeId, String snapshotName) {
        if (rawMaterialTypeRepository != null && materialTypeId != null) {
            try {
                var mt = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
                if (mt != null && mt.getName() != null && !mt.getName().isBlank()) {
                    return mt.getName();
                }
            } catch (Exception e) {
                log.debug("[T159] 读取原料名失败 {} ({})", materialTypeId, e.getMessage());
            }
        }
        if (snapshotName != null && !snapshotName.isBlank()) {
            return snapshotName;
        }
        return materialTypeId != null ? materialTypeId : "?";
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition generateFromPlan(String factoryId, String productionPlanId, Long requestedBy) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "status", "PENDING",
            "planId", productionPlanId != null ? productionPlanId : ""));
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(productionPlanId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + productionPlanId)
                        .withHint("请刷新生产计划列表后重新选择").withHintTarget("productionPlanId"));

        // 按 BOM 展开
        List<BomItem> bomItems = bomItemRepository
                .findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(factoryId, plan.getProductTypeId());
        if (bomItems.isEmpty()) {
            throw new BusinessException(404, "产品 BOM 未配置, 无法生成物料需求单: productTypeId=" + plan.getProductTypeId())
                    .withHint("请前往「生产管理 → BOM成本管理」配置产品 BOM");
        }

        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setFactoryId(factoryId);
        mr.setRequisitionNo(generateRequisitionNo(factoryId));
        mr.setProductionPlanId(productionPlanId);
        mr.setStatus(Status.PENDING);
        mr.setRequiredDate(plan.getExpectedCompletionDate());
        mr.setRequestedBy(requestedBy);

        // P1-4: auto-populate source (物流仓) + target (鲜棉仓) from FactoryWarehouse lookup
        // 为 B1 InternalTransfer 流水提供 warehouse 上下文 (之前是 null).
        List<FactoryWarehouse> logisticsList = warehouseRepository
                .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, WarehouseType.LOGISTICS);
        List<FactoryWarehouse> workshopList = warehouseRepository
                .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, WarehouseType.WORKSHOP);
        if (!logisticsList.isEmpty()) {
            mr.setSourceWarehouseId(logisticsList.get(0).getId());
        }
        if (!workshopList.isEmpty()) {
            mr.setTargetWarehouseId(workshopList.get(0).getId());
        }

        BigDecimal plannedQty = plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : BigDecimal.ZERO;
        for (BomItem bom : bomItems) {
            FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
            item.setRequisition(mr);
            item.setMaterialTypeId(bom.getMaterialTypeId());
            item.setMaterialName(bom.getMaterialName());
            // P0-14/N5: 从 BOM 透传物料分类; 半成品 BOM 行优先按引用字段识别 (不依赖 materialCategory 字符串)
            MaterialCategory category = MaterialCategory.RAW;
            boolean isSemiFinished = (bom.getSemiFinishedRefCode() != null
                    && !bom.getSemiFinishedRefCode().trim().isEmpty())
                    || (bom.getSubProductTypeId() != null
                    && !bom.getSubProductTypeId().trim().isEmpty());
            if (isSemiFinished) {
                category = MaterialCategory.SEMI_FINISHED;
            } else if (bom.getMaterialCategory() != null) {
                try {
                    category = MaterialCategory.valueOf(bom.getMaterialCategory());
                } catch (IllegalArgumentException ex) {
                    log.warn("未知的 BOM materialCategory={}, 降级为 RAW", bom.getMaterialCategory());
                }
            }
            item.setMaterialCategory(category);
            item.setBomItemId(bom.getId());
            // required_qty = planned_quantity * actual_quantity (按出成率调整), 单位 = BOM unit (e.g. g)
            BigDecimal perUnit = bom.getActualQuantity();
            BigDecimal requiredBom = plannedQty.multiply(perUnit);
            String bomUnit = bom.getUnit();

            // T144: 把需求量换算到称重批次单位 (kg), 与仓库实际称重领料口径一致.
            String stockUnit = resolveMaterialStockUnit(factoryId, bom.getMaterialTypeId());
            BigDecimal requiredQty = requiredBom;
            String requiredUnit = bomUnit;
            if (materialUomConverter != null && bomUnit != null && stockUnit != null
                    && !bomUnit.trim().equalsIgnoreCase(stockUnit.trim())) {
                com.cretas.aims.service.uom.MaterialUomConverter.ConversionResult conv =
                        materialUomConverter.toComparableQuantity(
                                bom.getMaterialTypeId(), requiredBom, bomUnit, stockUnit);
                if (conv.isConverted()) {
                    requiredQty = conv.getQuantity();
                    requiredUnit = stockUnit;
                } else if (conv.isAbacaSkip()) {
                    // 抄码料: 无确定箱重, 需求量保留 BOM 单位 (领料时按实际称重).
                    log.info("T143 抄码料 {} 物料需求单保留源单位 {}", bom.getMaterialName(), bomUnit);
                } else {
                    // T144 安全网: BOM 单位与库存批次单位维度不可换算 (e.g. 个 vs kg) → 真实配置错误.
                    // T159-B Change4: prefer live RawMaterialType.name over BOM snapshot.
                    String matName = resolveLiveMaterialName(bom.getMaterialTypeId(), bom.getMaterialName());
                    throw new BusinessException(409,
                            String.format("原料「%s」BOM单位(%s)与库存单位(%s)无法换算，请核对单位配置",
                                    matName, bomUnit, stockUnit))
                            .withCode("MATERIAL_UOM_UNCONFIGURED")
                            .withHint("请核对该原料 BOM 配方单位与入库称重单位是否同一计量维度")
                            .withHintTarget(bom.getMaterialTypeId())
                            .withSeverity("BLOCKING");
                }
            }
            item.setRequiredQty(requiredQty);
            item.setUnit(requiredUnit);
            mr.getItems().add(item);
        }

        FactoryMaterialRequisition saved = repository.save(mr);
        log.info("✅ 生成物料需求单: {} factory={} plan={} items={}",
                saved.getRequisitionNo(), factoryId, productionPlanId, saved.getItems().size());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public FactoryMaterialRequisition getById(String factoryId, String id) {
        FactoryMaterialRequisition mr = repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "物料需求单不存在: " + id)
                        .withHint("请刷新物料需求单列表后重新选择").withHintTarget("id"));
        // 触发懒加载
        mr.getItems().size();
        return mr;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FactoryMaterialRequisition> list(String factoryId, Status status, Pageable pageable) {
        if (status == null) {
            return repository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
        }
        return repository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FactoryMaterialRequisition> listByPlan(String factoryId, String productionPlanId) {
        return repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, productionPlanId);
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition startPicking(String factoryId, String id, Long operatorId) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "status", "PICKING",
            "planId", id != null ? id : ""));
        FactoryMaterialRequisition mr = getById(factoryId, id);
        assertStatus(mr, Status.PENDING);
        mr.setStatus(Status.PICKING);
        mr.setPickedBy(operatorId);
        return repository.save(mr);
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public FactoryMaterialRequisition confirmPicking(String factoryId, String id, Long operatorId, List<Map<String, Object>> items) {
        FactoryMaterialRequisition mr = getById(factoryId, id);
        assertStatus(mr, Status.PICKING);

        Map<String, FactoryMaterialRequisitionItem> byId = new HashMap<>();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            byId.put(it.getId(), it);
        }

        for (Map<String, Object> input : items) {
            String itemId = (String) input.get("itemId");
            FactoryMaterialRequisitionItem item = byId.get(itemId);
            if (item == null) continue;
            Object pickedQty = input.get("pickedQty");
            if (pickedQty != null) {
                item.setPickedQty(new BigDecimal(pickedQty.toString()));
            }
            Object batches = input.get("batchNumbers");
            if (batches instanceof List) {
                item.setBatchNumbers((List<Map<String, Object>>) batches);
            }
        }

        mr.setPickedBy(operatorId);
        mr.setPickedAt(LocalDateTime.now());
        return repository.save(mr);
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition transferToFactory(String factoryId, String id, Long operatorId) {
        FactoryMaterialRequisition mr = getById(factoryId, id);
        assertStatus(mr, Status.PICKING);
        // 所有行 issued_qty = picked_qty
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            it.setIssuedQty(it.getPickedQty());
        }
        mr.setStatus(Status.TRANSFERRED);
        mr.setTransferredBy(operatorId);
        mr.setTransferredAt(LocalDateTime.now());

        // P0-5: 创建备料调出 InternalTransfer (物流仓 → 工厂鲜棉仓)
        List<CreateTransferRequest.TransferItemDTO> outboundItems = new ArrayList<>();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal issued = it.getIssuedQty();
            if (issued != null && issued.compareTo(BigDecimal.ZERO) > 0) {
                outboundItems.add(new CreateTransferRequest.TransferItemDTO(
                        "RAW_MATERIAL",
                        it.getMaterialTypeId(),
                        null,
                        it.getMaterialName(),
                        issued,
                        it.getUnit() != null ? it.getUnit() : "kg",
                        null,
                        "备料调出: " + mr.getRequisitionNo()
                ));
            }
        }
        if (!outboundItems.isEmpty()) {
            CreateTransferRequest req = new CreateTransferRequest();
            req.setTransferType("FACTORY_TO_FACTORY");
            req.setTargetFactoryId(factoryId);
            req.setSourceWarehouseId(mr.getSourceWarehouseId());
            req.setTargetWarehouseId(mr.getTargetWarehouseId());
            req.setTransferDate(LocalDate.now());
            req.setRemark("物料需求单 " + mr.getRequisitionNo() + " 备料调出");
            req.setItems(outboundItems);
            InternalTransfer outbound = transferService.createTransfer(factoryId, req, operatorId);
            mr.setOutboundTransferId(outbound.getId());
            log.info("✅ 物料需求单 {} 备料调出 InternalTransfer 已创建: {}",
                    mr.getRequisitionNo(), outbound.getId());
        }

        return repository.save(mr);
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition receive(String factoryId, String id, Long operatorId) {
        FactoryMaterialRequisition mr = getById(factoryId, id);
        assertStatus(mr, Status.TRANSFERRED);
        mr.setStatus(Status.ISSUED);
        mr.setReceivedBy(operatorId);
        mr.setReceivedAt(LocalDateTime.now());
        return repository.save(mr);
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition close(String factoryId, String id, Long operatorId) {
        return close(factoryId, id, operatorId, List.of());
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition close(String factoryId, String id, Long operatorId, List<Map<String, Object>> closeItems) {
        FactoryMaterialRequisition mr = getById(factoryId, id);
        if (mr.getStatus() != Status.ISSUED && mr.getStatus() != Status.IN_USE) {
            throw new BusinessException(409, "状态 " + mr.getStatus() + " 不允许关单")
                    .withHint("请刷新物料需求单列表查看最新状态");
        }
        // 自动计算退料 returned = issued - consumed
        Map<String, BigDecimal> wastageByItemId = parseWastageByItemId(closeItems);
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal issued = it.getIssuedQty() != null ? it.getIssuedQty() : BigDecimal.ZERO;
            BigDecimal consumed = it.getConsumedQty() != null ? it.getConsumedQty() : BigDecimal.ZERO;
            BigDecimal wastage = wastageByItemId.getOrDefault(it.getId(),
                    it.getWastageQty() != null ? it.getWastageQty() : BigDecimal.ZERO);
            if (wastage.compareTo(BigDecimal.ZERO) < 0) {
                throw invalidReturnQuantity(it, issued, consumed, wastage);
            }
            BigDecimal returned = issued.subtract(consumed).subtract(wastage);
            if (returned.compareTo(BigDecimal.ZERO) < 0) {
                throw invalidReturnQuantity(it, issued, consumed, wastage);
            }
            it.setWastageQty(wastage);
            it.setReturnedQty(returned);
        }
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal returned = it.getReturnedQty();
            if (returned != null && returned.compareTo(BigDecimal.ZERO) > 0) {
                executeMaterialReturn(factoryId, mr, it, returned, operatorId);
            }
        }
        mr.setStatus(Status.CLOSED);
        mr.setClosedBy(operatorId);
        mr.setClosedAt(LocalDateTime.now());

        // P0-5: 创建退料调入 InternalTransfer (工厂鲜棉仓 → 物流仓), 仅在有退料时
        List<CreateTransferRequest.TransferItemDTO> returnItems = new ArrayList<>();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal returned = it.getReturnedQty();
            if (returned != null && returned.compareTo(BigDecimal.ZERO) > 0) {
                returnItems.add(new CreateTransferRequest.TransferItemDTO(
                        "RAW_MATERIAL",
                        it.getMaterialTypeId(),
                        null,
                        it.getMaterialName(),
                        returned,
                        it.getUnit() != null ? it.getUnit() : "kg",
                        null,
                        "退料调入: " + mr.getRequisitionNo()
                ));
            }
        }
        if (!returnItems.isEmpty()) {
            CreateTransferRequest req = new CreateTransferRequest();
            req.setTransferType("FACTORY_TO_FACTORY");
            req.setTargetFactoryId(factoryId);
            req.setSourceWarehouseId(mr.getTargetWarehouseId());
            req.setTargetWarehouseId(mr.getSourceWarehouseId());
            req.setTransferDate(LocalDate.now());
            req.setRemark("物料需求单 " + mr.getRequisitionNo() + " 退料调入");
            req.setItems(returnItems);
            InternalTransfer returnTransfer = transferService.createTransfer(factoryId, req, operatorId);
            mr.setReturnTransferId(returnTransfer.getId());
            log.info("✅ 物料需求单 {} 退料调入 InternalTransfer 已创建: {}",
                    mr.getRequisitionNo(), returnTransfer.getId());
        }

        return repository.save(mr);
    }

    @Override
    @Transactional
    public FactoryMaterialRequisition cancel(String factoryId, String id, Long operatorId, String reason) {
        FactoryMaterialRequisition mr = getById(factoryId, id);
        if (mr.getStatus() == Status.CLOSED || mr.getStatus() == Status.CANCELLED) {
            throw new BusinessException(409, "状态 " + mr.getStatus() + " 不允许取消")
                    .withHint("请刷新物料需求单列表查看最新状态");
        }
        mr.setStatus(Status.CANCELLED);
        mr.setRemarks((mr.getRemarks() == null ? "" : mr.getRemarks() + " | ") + "取消原因: " + reason);
        return repository.save(mr);
    }

    // ---------- helpers ----------

    private Map<String, BigDecimal> parseWastageByItemId(List<Map<String, Object>> closeItems) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (closeItems == null) {
            return result;
        }
        for (Map<String, Object> item : closeItems) {
            if (item == null || item.get("itemId") == null) {
                continue;
            }
            Object wastage = item.get("wastageQty");
            result.put(item.get("itemId").toString(), wastage == null ? BigDecimal.ZERO : new BigDecimal(wastage.toString()));
        }
        return result;
    }

    private BusinessException invalidReturnQuantity(FactoryMaterialRequisitionItem item,
                                                    BigDecimal issued,
                                                    BigDecimal consumed,
                                                    BigDecimal wastage) {
        return new BusinessException(400, String.format(
                "退料数据异常: 物料 %s 发出 %s, 实用 %s, 损耗 %s, 用量+损耗不能大于发出量",
                materialLabel(item), issued.toPlainString(), consumed.toPlainString(), wastage.toPlainString()))
                .withCode("PRODUCTION_MATERIAL_RETURN_INVALID_QUANTITY")
                .withHint("请核对实用量和损耗后重新关单")
                .withHintTarget(item.getId())
                .withSeverity("BLOCKING");
    }

    private void executeMaterialReturn(String factoryId,
                                       FactoryMaterialRequisition requisition,
                                       FactoryMaterialRequisitionItem item,
                                       BigDecimal returned,
                                       Long operatorId) {
        if (materialBatchRepository == null) {
            throw new BusinessException(500, "退料回库失败: MaterialBatchRepository 未注入")
                    .withCode("PRODUCTION_MATERIAL_RETURN_STOCK_RESTORE_UNAVAILABLE")
                    .withHint("请联系管理员检查后端库存服务配置")
                    .withSeverity("BLOCKING");
        }
        List<Map<String, Object>> batchRows = item.getBatchNumbers();
        if (batchRows == null || batchRows.isEmpty()) {
            throw new BusinessException(400, String.format(
                    "退料回库失败: 物料 %s 退回 %s 但缺少领料批次",
                    materialLabel(item), returned.toPlainString()))
                    .withCode("PRODUCTION_MATERIAL_RETURN_BATCH_REQUIRED")
                    .withHint("请先补录该物料领料批次后再关单")
                    .withHintTarget(item.getId())
                    .withSeverity("BLOCKING");
        }

        BigDecimal remaining = returned;
        for (Map<String, Object> batchRow : batchRows) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal issuedFromBatch = batchQuantity(batchRow);
            if (issuedFromBatch.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal returnQty = remaining.min(issuedFromBatch);
            String batchId = resolveBatchId(factoryId, batchRow);
            MaterialBatch batch = materialBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> new BusinessException(400, String.format(
                            "退料回库失败: 物料 %s 批次 %s 不存在, 退回量 %s",
                            materialLabel(item), batchId, returnQty.toPlainString()))
                            .withCode("PRODUCTION_MATERIAL_RETURN_BATCH_NOT_FOUND")
                            .withHint("请核对领料批次后重新关单")
                            .withHintTarget(item.getId())
                            .withSeverity("BLOCKING"));
            restoreBatchUsedQuantity(factoryId, batch, item, returnQty);
            writeMaterialReturnTrace(factoryId, requisition, item, batch, returnQty, operatorId);
            writeProductionMaterialReturn(factoryId, requisition, item, batch.getId(), returnQty);
            remaining = remaining.subtract(returnQty);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(400, String.format(
                    "退料回库失败: 物料 %s 退回 %s, 领料批次可退数量不足, 剩余 %s 未匹配",
                    materialLabel(item), returned.toPlainString(), remaining.toPlainString()))
                    .withCode("PRODUCTION_MATERIAL_RETURN_BATCH_QTY_MISMATCH")
                    .withHint("请核对领料批次数量和退回量后重新关单")
                    .withHintTarget(item.getId())
                    .withSeverity("BLOCKING");
        }
    }

    private void restoreBatchUsedQuantity(String factoryId,
                                          MaterialBatch batch,
                                          FactoryMaterialRequisitionItem item,
                                          BigDecimal returnQty) {
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "退料回库失败: 批次不属于当前工厂 " + batch.getId())
                    .withHint("请切换到正确工厂或核对批次")
                    .withSeverity("BLOCKING");
        }
        BigDecimal used = batch.getUsedQuantity() != null ? batch.getUsedQuantity() : BigDecimal.ZERO;
        if (used.compareTo(returnQty) < 0) {
            throw new BusinessException(400, String.format(
                    "退料回库失败: 物料 %s 批次 %s 已用量 %s 小于退回量 %s",
                    materialLabel(item), batch.getId(), used.toPlainString(), returnQty.toPlainString()))
                    .withCode("PRODUCTION_MATERIAL_RETURN_USED_QTY_UNDERFLOW")
                    .withHint("请核对报工消耗和退料数量后重新关单")
                    .withHintTarget(item.getId())
                    .withSeverity("BLOCKING");
        }
        batch.setUsedQuantity(used.subtract(returnQty));
        if (batch.getStatus() == MaterialBatchStatus.USED_UP && batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) > 0) {
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
        }
        materialBatchRepository.save(batch);
    }

    private void writeMaterialReturnTrace(String factoryId,
                                          FactoryMaterialRequisition requisition,
                                          FactoryMaterialRequisitionItem item,
                                          MaterialBatch batch,
                                          BigDecimal returnQty,
                                          Long operatorId) {
        BigDecimal unitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
        MaterialConsumption trace = new MaterialConsumption();
        trace.setFactoryId(factoryId);
        trace.setProductionPlanId(requisition.getProductionPlanId());
        trace.setBatchId(batch.getId());
        trace.setQuantity(returnQty.negate());
        trace.setUnitPrice(unitPrice);
        trace.setTotalCost(returnQty.negate().multiply(unitPrice));
        trace.setConsumptionTime(LocalDateTime.now());
        trace.setConsumedAt(LocalDateTime.now());
        trace.setRecordedBy(operatorId != null ? operatorId : 0L);
        trace.setMaterialTypeId(item.getMaterialTypeId());
        trace.setSourceType("MATERIAL_RETURN");
        trace.setNotes("production material return: requisition=" + requisition.getId() + ", item=" + item.getId());
        materialConsumptionRepository.save(trace);
    }

    private void writeProductionMaterialReturn(String factoryId,
                                               FactoryMaterialRequisition requisition,
                                               FactoryMaterialRequisitionItem item,
                                               String batchId,
                                               BigDecimal returnQty) {
        ProductionMaterialReturn materialReturn = new ProductionMaterialReturn();
        materialReturn.setFactoryId(factoryId);
        materialReturn.setRequisitionId(requisition.getId());
        materialReturn.setRequisitionItemId(item.getId());
        materialReturn.setMaterialTypeId(item.getMaterialTypeId());
        materialReturn.setMaterialBatchId(batchId);
        materialReturn.setReturnQuantity(returnQty);
        materialReturn.setReturnStatus(ProductionMaterialReturn.ReturnStatus.EXECUTED);
        productionMaterialReturnRepository.save(materialReturn);
    }

    private String resolveBatchId(String factoryId, Map<String, Object> batchRow) {
        Object explicitId = firstNonNull(batchRow, "batchId", "materialBatchId", "id");
        if (explicitId != null && !explicitId.toString().isBlank()) {
            return explicitId.toString();
        }
        Object batchNo = firstNonNull(batchRow, "batchNo", "batchNumber");
        if (batchNo != null && !batchNo.toString().isBlank()) {
            return materialBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNo.toString())
                    .map(MaterialBatch::getId)
                    .orElseThrow(() -> new BusinessException(400, "退料回库失败: 批次号不存在 " + batchNo)
                            .withCode("PRODUCTION_MATERIAL_RETURN_BATCH_NOT_FOUND")
                            .withHint("请核对领料批次后重新关单")
                            .withSeverity("BLOCKING"));
        }
        throw new BusinessException(400, "退料回库失败: 领料批次缺少 batchId/materialBatchId/batchNo")
                .withCode("PRODUCTION_MATERIAL_RETURN_BATCH_REQUIRED")
                .withHint("请补录领料批次后重新关单")
                .withSeverity("BLOCKING");
    }

    private Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal batchQuantity(Map<String, Object> batchRow) {
        Object value = firstNonNull(batchRow, "qty", "quantity", "pickedQty", "issuedQty");
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private String materialLabel(FactoryMaterialRequisitionItem item) {
        if (item.getMaterialName() != null && !item.getMaterialName().isBlank()) {
            return item.getMaterialName();
        }
        return item.getMaterialTypeId() != null ? item.getMaterialTypeId() : item.getId();
    }

    private String generateRequisitionNo(String factoryId) {
        String datePart = LocalDateTime.now().format(DATE_FMT);
        String prefix = "MR" + datePart;
        long count = repository.countByFactoryIdAndRequisitionNoPrefix(factoryId, prefix);
        return String.format("%s-%04d", prefix, count + 1);
    }

    private void assertStatus(FactoryMaterialRequisition mr, Status expected) {
        if (mr.getStatus() != expected) {
            throw new BusinessException(409, "状态不匹配: 需要 " + expected + ", 当前 " + mr.getStatus())
                    .withHint("请刷新物料需求单列表查看最新状态");
        }
    }
}
