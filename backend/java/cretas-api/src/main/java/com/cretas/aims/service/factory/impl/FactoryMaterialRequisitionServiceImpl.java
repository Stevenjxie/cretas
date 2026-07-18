package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem.MaterialCategory;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
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
    private final BomRecipeItemRepository bomRecipeItemRepository;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /**
     * ② Part A: 采购落点仓解析器. 领料需求单的来源仓应取「采购实际落点」(而不是硬编码物流仓),
     * 让采购进原料仓的工厂 (e.g. LIUSHANMEN, PURCHASE_INBOUND_DEFAULT=原料仓) 领料能拣到已入库原料.
     * required=false 兼容单测 (缺失时防御回退到老 LOGISTICS 仓查找, 行为与现状一致).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;

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
        List<BomRecipeItem> bomItems = bomRecipeItemRepository
                .findCurrentByProduct(factoryId, plan.getProductTypeId());
        if (bomItems.isEmpty()) {
            throw new BusinessException(404, "产品 BOM 未配置, 无法生成物料需求单: productTypeId=" + plan.getProductTypeId())
                    .withHint("请前往「生产管理 → BOM成本管理」配置产品 BOM");
        }

        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setFactoryId(factoryId);
        mr.setRequisitionNo(generateRequisitionNo(factoryId));
        mr.setProductionPlanId(productionPlanId);
        mr.setProductionPlanNumber(plan.getPlanNumber());
        if (productTypeRepository != null) {
            productTypeRepository.findByIdAndFactoryId(plan.getProductTypeId(), factoryId)
                    .ifPresent(product -> mr.setProductName(product.getName()));
        }
        mr.setStatus(Status.PENDING);
        mr.setRequiredDate(plan.getExpectedCompletionDate());
        mr.setRequestedBy(requestedBy);

        // ② Part A / P1-4: 领料来源仓 = 采购实际落点仓 (resolvePurchaseInboundWh: PURCHASE_INBOUND_DEFAULT
        // 配置 → 否则回退 WH-LOG). 目标仓 = 车间/生产仓. 为 B1 InternalTransfer 流水 + 下游 FEFO 自动分配
        // (autoAllocatePickedBatchesIfMissing 按 sourceWarehouseId 挑批次) 提供 warehouse 上下文.
        // 采购进原料仓的工厂 (e.g. LIUSHANMEN) → source=原料仓, 连上其采购; 未配置工厂 (F006) → WH-LOG = 现状.
        mr.setSourceWarehouseId(resolveRequisitionSourceWarehouseId(factoryId));
        List<FactoryWarehouse> workshopList = warehouseRepository
                .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, WarehouseType.WORKSHOP);
        if (!workshopList.isEmpty()) {
            mr.setTargetWarehouseId(workshopList.get(0).getId());
        }

        BigDecimal plannedQty = plan.getPlannedQuantity() != null ? plan.getPlannedQuantity() : BigDecimal.ZERO;
        for (BomRecipeItem bom : bomItems) {
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
            item.setBomRecipeItemId(bom.getId());
            // required_qty = planned_quantity * actual_quantity (按出成率调整), 单位 = BOM unit (e.g. g)
            BigDecimal perUnit = bom.calculateActualQuantity();
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

    /**
     * 解析领料需求单的来源仓 = 采购实际落点仓。
     *
     * <p>优先用 {@link com.cretas.aims.service.factory.WarehouseResolver#resolvePurchaseInboundWh}
     * (读 {@code PURCHASE_INBOUND_DEFAULT} 配置, 否则回退 WH-LOG), 使采购进原料仓的工厂领料能从原料仓
     * 拣到已入库原料; 未配置的工厂回退 WH-LOG = 现状 (向后兼容, 零行为变化)。
     *
     * <p>防御回退 (resolver 未注入 / 返回空 / 抛异常时): 老 LOGISTICS 类型仓查找 → 任意仓库, 避免
     * 来源仓为 null 破坏下游 FEFO 自动分配 ({@code autoAllocatePickedBatchesIfMissing} 按
     * {@code sourceWarehouseId} 挑批次)。
     */
    private String resolveRequisitionSourceWarehouseId(String factoryId) {
        if (warehouseResolver != null) {
            try {
                String resolved = warehouseResolver.resolvePurchaseInboundWh(factoryId);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            } catch (Exception e) {
                log.warn("解析采购落点仓失败, 回退 LOGISTICS 仓查找: factory={} ({})", factoryId, e.getMessage());
            }
        }
        // 防御回退 1: 老 LOGISTICS 类型仓 (与拆分前 P1-4 行为一致)
        List<FactoryWarehouse> logisticsList = warehouseRepository
                .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, WarehouseType.LOGISTICS);
        if (!logisticsList.isEmpty()) {
            return logisticsList.get(0).getId();
        }
        // 防御回退 2: 任意仓库 (避免来源仓为 null 破坏 FEFO 自动分配)
        List<FactoryWarehouse> anyList = warehouseRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId);
        return anyList.isEmpty() ? null : anyList.get(0).getId();
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

        // 防呆 (Rule 4 幂等 + 反假成功): 调拨前必须已「确认领料」录入实际拣货数量。若所有行 picked_qty
        // 均为 null/0, 说明仓管跳过了确认领料直接点调拨 → 之前会静默把状态推到 TRANSFERRED 并返 200
        // (相relocate/outbound 两个循环全 skip, 零库存移动), 用户以为「已调拨」实则料没搬 → 假成功。
        // 现 loud-block, 明确告诉仓管下一步动作。
        boolean anyPicked = mr.getItems().stream()
                .anyMatch(it -> it.getPickedQty() != null
                        && it.getPickedQty().compareTo(BigDecimal.ZERO) > 0);
        if (!anyPicked) {
            throw new BusinessException(409, "调拨失败: 该领料单尚未确认领料数量, 无料可调拨到生产仓")
                    .withCode("PRODUCTION_REQUISITION_NOT_PICKED")
                    .withHint("请先点击「确认领料」逐物料录入实际拣货数量, 再执行调拨")
                    .withSeverity("BLOCKING");
        }

        // 所有行 issued_qty = picked_qty
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            it.setIssuedQty(it.getPickedQty());
        }

        // P0-5 / 2026-07-03 物理迁移: 领料确认后, 把已拣批次从原料仓(主仓, WH-LOG) 实际转移到生产仓
        // (WORKSHOP, WH-WKS)。此前只建 DRAFT InternalTransfer (不移库存) → 料从不真正到达生产仓, 客户
        // 料流 (采购→原料仓→领料→生产仓→报工从生产仓扣) 断裂。现按已拣批次逐一划出源仓、在生产仓建批次,
        // 保留单价/单位/效期/批号血缘。幂等: 状态机 PICKING→TRANSFERRED 已防重放, batch_row 内再记
        // workshopBatchId 二次防呆。
        String targetWarehouseId = resolveWorkshopWarehouseId(factoryId, mr);
        // 防呆 (客户原话「你告诉他这个东西你要收多少就行了」): 仓管确认领料只录数量, 系统按 FEFO 从
        // 原料仓自动分配领料批次。若某行确认时已带批次 (前端预选/老单) 则不动。
        autoAllocatePickedBatchesIfMissing(factoryId, mr);
        relocatePickedMaterialToWorkshop(factoryId, mr, targetWarehouseId, operatorId);

        mr.setStatus(Status.TRANSFERRED);
        mr.setTransferredBy(operatorId);
        mr.setTransferredAt(LocalDateTime.now());

        // 🔒🔒 2026-07-03 双扣防呆 (bug #3): 不再创建「备料调出」InternalTransfer。实际库存迁移已由
        // 上方 relocatePickedMaterialToWorkshop 完成 (源仓批次划出 + 生产仓建同物料新批次)。此前额外建
        // 一张 DRAFT InternalTransfer 号称「可追溯审计凭证」, 但 DRAFT 与真实调拨单在「调拨管理」列表里
        // 无法区分, 是一个可被走完的动作单。#1177 打通同厂调拨的提交→审批→发货→签收按钮后, 被训练「把单子
        // 走完」的仓管会 提交→审批→发货 (TransferServiceImpl.shipTransfer → deductSourceInventory 按
        // 物料 FEFO 二次扣减真实库存, 很可能扣到别的批次) → 签收/确认 (createTargetInventory 再建一张生产
        // 仓批次) → 原料被扣两遍 + 生产仓重复批次, 全程 HTTP 200 零拦截 = 静默库存双扣。
        // 审计留痕由需求单自身承载: 状态 TRANSFERRED + transferredBy/transferredAt + 每行 batchNumbers
        // 携 workshopBatchId, 精确记录「哪个源批次划出多少 → 哪个生产仓批次」。
        // ⛔ 不要为了「在调拨管理里可见」重新加回这张 transfer —— 它就是双扣陷阱的根源。outboundTransferId
        // 字段保留 (向后兼容历史数据), 新流程留 null。
        return repository.save(mr);
    }

    /**
     * 解析该领料单的目标生产仓 (WORKSHOP/WH-WKS) id。优先用 generateFromPlan 预填的 targetWarehouseId,
     * 缺失 (老单/未 seed) 时按 WORKSHOP 类型回退查询。仍无 → 防呆 loud-fail (不静默跳过物理迁移)。
     */
    private String resolveWorkshopWarehouseId(String factoryId, FactoryMaterialRequisition mr) {
        String targetWarehouseId = mr.getTargetWarehouseId();
        if ((targetWarehouseId == null || targetWarehouseId.isBlank()) && warehouseRepository != null) {
            List<FactoryWarehouse> workshopList = warehouseRepository
                    .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, WarehouseType.WORKSHOP);
            if (!workshopList.isEmpty()) {
                targetWarehouseId = workshopList.get(0).getId();
                mr.setTargetWarehouseId(targetWarehouseId);
            }
        }
        if (targetWarehouseId == null || targetWarehouseId.isBlank()) {
            throw new BusinessException(409, "领料调拨失败: 未找到该工厂的生产仓 (WORKSHOP/WH-WKS)")
                    .withCode("PRODUCTION_WORKSHOP_WAREHOUSE_NOT_FOUND")
                    .withHint("请先在「工厂配置 → 仓库管理」维护生产/车间仓后再确认领料")
                    .withSeverity("BLOCKING");
        }
        return targetWarehouseId;
    }

    /**
     * 防呆自动分批: 仓管确认领料只录「实际拣货数量」(picked_qty), 不选批次。调拨前按 FEFO (先到期先出)
     * 从原料仓 (mr.sourceWarehouseId, 缺失回退工厂全仓) 自动为每行分配领料批次, 写进 batch_numbers,
     * 供 {@link #relocatePickedMaterialToWorkshop} 逐批划出。已带批次的行 (前端预选 / 老单) 跳过。
     * 库存不足 → loud-fail (honest, 不静默少领), 明确告诉仓管缺口。
     */
    private void autoAllocatePickedBatchesIfMissing(String factoryId, FactoryMaterialRequisition mr) {
        if (materialBatchRepository == null) {
            return; // relocate 会再报 UNAVAILABLE
        }
        String sourceWarehouseId = mr.getSourceWarehouseId();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal issued = it.getIssuedQty();
            if (issued == null || issued.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<Map<String, Object>> existing = it.getBatchNumbers();
            if (existing != null && !existing.isEmpty()) {
                continue; // 已选批次 (前端预选或老单), 尊重之
            }
            String materialTypeId = it.getMaterialTypeId();
            List<MaterialBatch> candidates = Collections.emptyList();
            if (sourceWarehouseId != null && !sourceWarehouseId.isBlank()) {
                candidates = materialBatchRepository
                        .findAvailableBatchesFEFOByWarehouse(factoryId, materialTypeId, sourceWarehouseId);
            }
            // 源仓无该物料可用批次 → 回退工厂全仓 FEFO (兼容批次未标 warehouse 的老数据)
            if (candidates.isEmpty()) {
                candidates = materialBatchRepository.findAvailableBatchesFEFO(factoryId, materialTypeId);
            }

            BigDecimal remaining = issued;
            List<Map<String, Object>> allocated = new ArrayList<>();
            for (MaterialBatch b : candidates) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal avail = b.getCurrentQuantity();
                if (avail == null || avail.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = avail.min(remaining);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", b.getId());
                row.put("batchNumber", b.getBatchNumber());
                row.put("qty", take.toPlainString());
                allocated.add(row);
                remaining = remaining.subtract(take);
            }
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessException(409, String.format(
                        "领料调拨失败: 物料 %s 原料仓可用库存不足以领出 %s (尚缺 %s)",
                        materialLabel(it), issued.toPlainString(), remaining.toPlainString()))
                        .withCode("PRODUCTION_REQUISITION_TRANSFER_INSUFFICIENT_STOCK")
                        .withHint("请核对原料仓实际库存, 或在「确认领料」调低该物料拣货数量")
                        .withHintTarget(it.getId())
                        .withSeverity("BLOCKING");
            }
            it.setBatchNumbers(allocated);
            log.info("✅ 领料自动分批 物料需求单 {} 物料 {}: 按 FEFO 分配 {} 个批次共 {}{}",
                    mr.getRequisitionNo(), materialLabel(it), allocated.size(),
                    issued.toPlainString(), it.getUnit() != null ? it.getUnit() : "");
        }
    }

    /**
     * 物理迁移: 逐已拣批次把 issued 数量从源批次 (原料仓) 划出, 在生产仓建同物料新批次。
     * 保留单价/单位/效期/批号血缘。把新建的生产仓批次 id 回写进 batch_numbers 行 (workshopBatchId),
     * 供关单退料时精确反向划出。幂等: batch_row 已带 workshopBatchId → 跳过 (二次防呆)。
     */
    private void relocatePickedMaterialToWorkshop(String factoryId, FactoryMaterialRequisition mr,
                                                  String targetWarehouseId, Long operatorId) {
        if (materialBatchRepository == null) {
            throw new BusinessException(500, "领料调拨失败: MaterialBatchRepository 未注入")
                    .withCode("PRODUCTION_REQUISITION_TRANSFER_UNAVAILABLE")
                    .withHint("请联系管理员检查后端库存服务配置")
                    .withSeverity("BLOCKING");
        }
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal issued = it.getIssuedQty();
            if (issued == null || issued.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<Map<String, Object>> batchRows = it.getBatchNumbers();
            if (batchRows == null || batchRows.isEmpty()) {
                throw new BusinessException(400, String.format(
                        "领料调拨失败: 物料 %s 已发 %s 但缺少领料批次, 无法迁移到生产仓",
                        materialLabel(it), issued.toPlainString()))
                        .withCode("PRODUCTION_REQUISITION_TRANSFER_BATCH_REQUIRED")
                        .withHint("请先在拣货确认时补录该物料的领料批次")
                        .withHintTarget(it.getId())
                        .withSeverity("BLOCKING");
            }
            List<Map<String, Object>> rebuilt = new ArrayList<>(batchRows.size());
            for (Map<String, Object> batchRow : batchRows) {
                Map<String, Object> mutableRow = new LinkedHashMap<>(batchRow);
                // 幂等: 已迁移过 (带 workshopBatchId) → 原样保留, 不重复划出
                Object existingWks = mutableRow.get("workshopBatchId");
                BigDecimal moveQty = batchQuantity(batchRow);
                if ((existingWks != null && !existingWks.toString().isBlank())
                        || moveQty.compareTo(BigDecimal.ZERO) <= 0) {
                    rebuilt.add(mutableRow);
                    continue;
                }
                String batchId = resolveBatchId(factoryId, batchRow);
                MaterialBatch source = materialBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                        .orElseThrow(() -> new BusinessException(400, String.format(
                                "领料调拨失败: 物料 %s 批次 %s 不存在",
                                materialLabel(it), batchId))
                                .withCode("PRODUCTION_REQUISITION_TRANSFER_BATCH_NOT_FOUND")
                                .withHint("请核对领料批次后重新确认领料")
                                .withHintTarget(it.getId())
                                .withSeverity("BLOCKING"));
                if (!factoryId.equals(source.getFactoryId())) {
                    throw new BusinessException(403, "领料调拨失败: 批次不属于当前工厂 " + source.getId())
                            .withHint("请切换到正确工厂或核对批次").withSeverity("BLOCKING");
                }
                BigDecimal available = source.getCurrentQuantity();
                if (available.compareTo(moveQty) < 0) {
                    throw new BusinessException(409, String.format(
                            "领料调拨失败: 物料 %s 批次 %s 可用 %s 不足以调出 %s",
                            materialLabel(it), source.getBatchNumber(),
                            available.toPlainString(), moveQty.toPlainString()))
                            .withCode("PRODUCTION_REQUISITION_TRANSFER_INSUFFICIENT_STOCK")
                            .withHint("请核对原料仓实际库存或调整领料数量")
                            .withHintTarget(it.getId())
                            .withSeverity("BLOCKING");
                }
                // 源批次划出 (原料仓库存减少)
                BigDecimal used = source.getUsedQuantity() != null ? source.getUsedQuantity() : BigDecimal.ZERO;
                source.setUsedQuantity(used.add(moveQty));
                if (source.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    source.setStatus(MaterialBatchStatus.DEPLETED);
                }
                materialBatchRepository.save(source);

                // 生产仓建同物料新批次 (库存增加), 保留单价/单位/效期血缘
                MaterialBatch workshop = new MaterialBatch();
                workshop.setId(UUID.randomUUID().toString());
                workshop.setFactoryId(factoryId);
                workshop.setBatchNumber(buildWorkshopBatchNumber(source.getBatchNumber()));
                workshop.setMaterialTypeId(it.getMaterialTypeId() != null
                        ? it.getMaterialTypeId() : source.getMaterialTypeId());
                workshop.setSupplierId(source.getSupplierId());
                workshop.setReceiptQuantity(moveQty);
                workshop.setUsedQuantity(BigDecimal.ZERO);
                workshop.setReservedQuantity(BigDecimal.ZERO);
                workshop.setQuantityUnit(source.getQuantityUnit() != null
                        ? source.getQuantityUnit() : (it.getUnit() != null ? it.getUnit() : "kg"));
                workshop.setUnitPrice(source.getUnitPrice());
                workshop.setReceiptDate(LocalDate.now());
                workshop.setProductionDate(source.getProductionDate());
                workshop.setExpireDate(source.getExpireDate());
                workshop.setWarehouseId(targetWarehouseId);
                workshop.setStatus(MaterialBatchStatus.AVAILABLE);
                workshop.setCreatedBy(operatorId != null ? operatorId : 0L);
                workshop.setSourceDocType("MATERIAL_REQUISITION");
                workshop.setSourceDocId(mr.getId());
                materialBatchRepository.save(workshop);

                mutableRow.put("workshopBatchId", workshop.getId());
                rebuilt.add(mutableRow);
                log.info("✅ 领料迁移 物料需求单 {} 物料 {}: 源批次 {} 划出 {} → 生产仓批次 {}",
                        mr.getRequisitionNo(), materialLabel(it), source.getId(),
                        moveQty.toPlainString(), workshop.getId());
            }
            it.setBatchNumbers(rebuilt);
        }
    }

    /** 生产仓批次号: 源批号 + "-WKS-" + 短 UUID, 满足 batch_number 唯一约束。 */
    private String buildWorkshopBatchNumber(String sourceBatchNumber) {
        String base = (sourceBatchNumber != null && !sourceBatchNumber.isBlank()) ? sourceBatchNumber : "MR";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String candidate = base + "-WKS-" + suffix;
        return candidate.length() > 64 ? candidate.substring(candidate.length() - 64) : candidate;
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

        // 🔴🔒🔒 2026-07-04 关单-前-小结 防呆 (幻库存 + 永久卡死 修复, F006 live 复现):
        //   物料消耗采「延迟扣减」设计 — 报工时写 MaterialConsumption (未结, batchId=生产仓 WKS 批次) 但
        //   不扣 WKS.usedQuantity; 直到「小结」才逐笔扣 WKS.usedQuantity。若在小结前关单:
        //     ① computeWorkshopRemaining 读到 WKS.currentQuantity 仍 = 全额 issued (扣减尚未落地) →
        //        误判「未消耗剩余 = 全额」→ 把已被报工消耗的料一并退回原料仓 (幻库存 +已报工量) +
        //        drawDownWorkshopBatchesForItem 把 WKS 批次划空;
        //     ② 随后「小结」对已被划空的 WKS 批次扣减 → afterCurrent < 0 → 「批次可用不足」409;
        //        CLOSED 不能 reopen、cancel() 拒绝 CLOSED → 永久卡死, 真实报工消耗永远无法结算。
        //   防呆 (fool-proof Rule 5 明确下一步 + Rule 2 显示条数): 本单 WKS 批次尚有未结报工消耗时
        //   loud-block 关单, 引导先完成小结。小结后关单 (WKS.currentQuantity 已 = issued − 实耗) 走既有
        //   正确路径 (#1202 post-settle), 不受影响; 无 WKS 物化批次的老单/未走物理迁移单不触发 (行为不变);
        //   未报工即关单 (无未结消耗) 亦正常退全额 (issued 未被消耗, 应退回)。
        //
        //   ⚠️ 2026-07-04 收窄 (bug fix, 首版 #1215 误伤): 该守卫只对「存货生产 (SAFETY_STOCK) 计划」有效 ——
        //   `interimSettledAt IS NULL` 仅在小结 (InterimSettleServiceImpl, 仅 SAFETY_STOCK) 路径才被盖戳。
        //   非 SAFETY_STOCK 计划走「结单」(ProductionPlanServiceImpl.settleProduction → postMaterialBatchConsumption)
        //   即时扣减 usedQuantity, 永不盖 interimSettledAt → 其消耗行恒 `interimSettledAt IS NULL` 但早已落库扣减,
        //   并非「待小结」。若不区分计划族, 结单族的领料单 (报工+结单后, 计划已 COMPLETED) 会被此守卫永久 409,
        //   而其 hint「请先小结」指向的小结端对结单族 400 (仅存货生产可小结) → 死路。故仅在计划确为 SAFETY_STOCK
        //   (真存在「小结-待结算」窗口) 时才 count-and-block; 计划非 SAFETY_STOCK / 无 planId / 计划查不到 →
        //   无该窗口, 守卫不触发, 结单族领料单正常关单退料。
        //
        //   ⓘ 2026-07-04 根修后 (MaterialConsumptionRepository.stampInterimSettledForPlan): 结单族计划结单时
        //   已给其报工消耗行打戳 → 残留 #2 (跨计划投料把结单族未结行留在他单 WKS 批次 → 本守卫误 count 永久 409)
        //   随结单族结单即闭合。此处 isInterimSettlePlan 收窄门控退化为「冗余而安全」(结单族行结单后非 null, 天然
        //   不被 countUnsettledConsumptionByBatchIds 统计), 保留以防结单前跨计划窗口 + 语义清晰, 本 PR 不移除。
        if (isInterimSettlePlan(factoryId, mr.getProductionPlanId())) {
            List<String> workshopBatchIds = collectWorkshopBatchIds(mr);
            if (!workshopBatchIds.isEmpty() && materialConsumptionRepository != null) {
                long unsettled = materialConsumptionRepository
                        .countUnsettledConsumptionByBatchIds(factoryId, workshopBatchIds);
                if (unsettled > 0) {
                    throw new BusinessException(409, String.format(
                            "关单失败: 本领料单尚有 %d 笔报工消耗未小结, 无法关单退料", unsettled))
                            .withCode("PRODUCTION_REQUISITION_CLOSE_BEFORE_SETTLE")
                            .withHint("请先在「生产计划 → 小结」完成本次报工消耗的结算, 再回到本单关单退料")
                            .withHintTarget(mr.getId())
                            .withSeverity("BLOCKING");
                }
            }
        }

        // 自动计算退料 returned = 生产仓未消耗剩余 − 损耗
        Map<String, BigDecimal> wastageByItemId = parseWastageByItemId(closeItems);
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal issued = it.getIssuedQty() != null ? it.getIssuedQty() : BigDecimal.ZERO;
            BigDecimal wastage = wastageByItemId.getOrDefault(it.getId(),
                    it.getWastageQty() != null ? it.getWastageQty() : BigDecimal.ZERO);
            if (wastage.compareTo(BigDecimal.ZERO) < 0) {
                throw invalidReturnQuantity(it, issued, BigDecimal.ZERO, wastage);
            }
            // 🔴🔒🔒 2026-07-03 幻库存修复 (bug #1): consumedQty 是死字段 (整个 main 代码零个 setConsumedQty
            //   调用点, grep 确认)。旧算法 returned = issued − consumed(恒 0) − wastage → 恒退回全部发出量,
            //   即使报工/小结实际消耗了一部分 → 被消耗的料被重复加回原料仓 = 幽灵库存 (issued 55.556, 实耗 20,
            //   旧代码退 55.556 而非 35.556 → +20 phantom)。
            //   真实"未消耗剩余"= 领料时物化到生产仓 (WKS) 的该行批次现存合计: 领料按 issued 建 WKS 批,
            //   报工/小结逐笔扣 WKS.usedQuantity → currentQuantity = issued − 实际消耗。故:
            //     returned = WKS现存 − wastage   (= 物理退回原料仓的量 = 未消耗剩余)
            //     consumed = issued − WKS现存    (真实消耗, 回写死字段供打印/核算)
            WorkshopRemaining wr = computeWorkshopRemaining(factoryId, it);
            BigDecimal consumed;
            BigDecimal returned;
            if (wr.hasWorkshopBatch()) {
                consumed = issued.subtract(wr.remaining());
                if (consumed.signum() < 0) {
                    consumed = BigDecimal.ZERO; // 数据异常防御: WKS 现存 > 发出量 → 消耗不为负 (不放大退回)
                }
                returned = wr.remaining().subtract(wastage);
            } else {
                // 无 WKS 物化批次 (撤单前旧数据 / 未走物理迁移的单): 无物理锚可推导, 沿用死字段口径 (≡0), 不臆造。
                consumed = it.getConsumedQty() != null ? it.getConsumedQty() : BigDecimal.ZERO;
                returned = issued.subtract(consumed).subtract(wastage);
            }
            if (returned.compareTo(BigDecimal.ZERO) < 0) {
                // honest-fail: 损耗 > 生产仓未消耗剩余 (WKS balance < expected) → 数据矛盾, 不静默造负库存。
                throw invalidReturnQuantity(it, issued, consumed, wastage);
            }
            it.setWastageQty(wastage);
            it.setConsumedQty(consumed);
            it.setReturnedQty(returned);
        }
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            BigDecimal returned = it.getReturnedQty();
            if (returned != null && returned.compareTo(BigDecimal.ZERO) > 0) {
                executeMaterialReturn(factoryId, mr, it, returned, operatorId);
            }
            // 2026-07-03: 物理迁移反向收尾。领料时 issued 已从原料仓迁到生产仓 (WKS 新批次)。关单时
            // 退回料 (returned) 经上方 executeMaterialReturn 加回原料仓源批次; 这里把 (returned + wastage)
            // 从生产仓 WKS 批次划出, 令 WKS 批次归零 (issued = 报工消耗 + 退回 + 损耗), 避免生产仓幽灵库存。
            BigDecimal wastage = it.getWastageQty() != null ? it.getWastageQty() : BigDecimal.ZERO;
            BigDecimal drawFromWorkshop = (returned != null ? returned : BigDecimal.ZERO).add(wastage);
            if (drawFromWorkshop.compareTo(BigDecimal.ZERO) > 0) {
                drawDownWorkshopBatchesForItem(factoryId, it, drawFromWorkshop);
            }
        }
        mr.setStatus(Status.CLOSED);
        mr.setClosedBy(operatorId);
        mr.setClosedAt(LocalDateTime.now());

        // 🔒🔒 2026-07-03 双退防呆 (bug #3 同因 sweep): 不再创建「退料调入」InternalTransfer。退料回原料仓
        // 已由上方 executeMaterialReturn 完成 (源批次已用量减回 + 负数 MaterialConsumption 留痕), 生产仓
        // 侧由 drawDownWorkshopBatchesForItem 划平。此前额外建的 DRAFT 退料调入单同为可走完的动作单, 仓管
        // 走完 → 再次 FEFO 扣生产仓库存 + 在原料仓重复建批次 → 退料被处理两遍 (与备料调出对称的双计陷阱)。
        // 审计留痕由 ProductionMaterialReturn (EXECUTED) + MaterialConsumption (负数, MATERIAL_RETURN) 承载。
        // ⛔ 不要为「调拨管理可见」重新加回。returnTransferId 字段保留兼容历史数据, 新流程留 null。
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

    /**
     * 关单-前-小结 防呆的计划族判定: 该计划是否为「存货生产 (SAFETY_STOCK)」——即真正存在「小结-待结算」窗口
     * (报工写未结消耗、暂不扣 WKS.usedQuantity, 直到小结才逐笔扣) 的唯一计划族。只有此族的 {@code interimSettledAt
     * IS NULL} 消耗才代表「待小结、尚未落库扣减」; 非此族 (结单路径即时扣减、永不盖戳) 的 null 戳消耗早已扣减,
     * 不可当作待结算而阻止关单。planId 缺失 / 计划查不到 → 保守视为「非小结族」(无待结算窗口 → 守卫不触发)。
     */
    private boolean isInterimSettlePlan(String factoryId, String productionPlanId) {
        if (productionPlanId == null || productionPlanId.isBlank() || productionPlanRepository == null) {
            return false;
        }
        return productionPlanRepository.findByIdAndFactoryId(productionPlanId, factoryId)
                .map(p -> p.getSourceType() == com.cretas.aims.entity.enums.PlanSourceType.SAFETY_STOCK)
                .orElse(false);
    }

    /**
     * 收集本领料单全部行在生产仓 (WKS) 物化的批次 id (领料迁移时回写进 {@code batchNumbers[*].workshopBatchId})。
     * 关单-前-小结 防呆据此定位「小结将扣减、关单将划空」的同一批次集合。无 WKS 锚 (老单/未走物理迁移) → 空集。
     */
    private List<String> collectWorkshopBatchIds(FactoryMaterialRequisition mr) {
        List<String> ids = new ArrayList<>();
        for (FactoryMaterialRequisitionItem it : mr.getItems()) {
            List<Map<String, Object>> rows = it.getBatchNumbers();
            if (rows == null) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                Object wks = row.get("workshopBatchId");
                if (wks != null && !wks.toString().isBlank()) {
                    ids.add(wks.toString());
                }
            }
        }
        return ids;
    }

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
        // 退料恢复后若批次曾被打满 USED_UP 或 DEPLETED (两者都是"耗尽终态", 见
        // ReportReversalServiceImpl.restoreMaterialBatchConsumption / MaterialBatchServiceImpl.releaseBatchReservation
        // 的同 pattern), 且退料后现存 > 0, 必须翻回 AVAILABLE —— 否则批次永久滞留耗尽状态,
        // FEFO/FIFO 查询硬编码 status='AVAILABLE' 捞不到, 库存被冻结不可用 (2026-07 F006 事故: 10 批 67.40kg 冻结)。
        if ((batch.getStatus() == MaterialBatchStatus.USED_UP || batch.getStatus() == MaterialBatchStatus.DEPLETED)
                && batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) > 0) {
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
        }
        materialBatchRepository.save(batch);
    }

    /**
     * 计算该领料行在生产仓 (WKS) 物化批次的现存合计 + 是否存在 WKS 锚。关单时据此推导真实消耗与可退量:
     * 领料迁移时按 issued 在生产仓建 WKS 批 (id 记于 {@code batchNumbers[*].workshopBatchId}), 报工/小结
     * 逐笔扣 WKS.usedQuantity → 现存 (currentQuantity) = issued − 实际消耗 = 未消耗剩余。
     *
     * <p>用 {@code findByIdAndFactoryIdForUpdate} 悲观锁读 (与随后 {@link #drawDownWorkshopBatchesForItem}
     * 划出同锁, 消除 compute→drawdown 之间的 TOCTOU)。记录了 workshopBatchId 但批次已不存在 → 视作 0 现存
     * (已划平/删除, 诚实不臆造可退量)。同一 WKS 批次去重只计一次。
     */
    private WorkshopRemaining computeWorkshopRemaining(String factoryId, FactoryMaterialRequisitionItem item) {
        List<Map<String, Object>> batchRows = item.getBatchNumbers();
        if (batchRows == null || batchRows.isEmpty() || materialBatchRepository == null) {
            return new WorkshopRemaining(false, BigDecimal.ZERO);
        }
        boolean hasWks = false;
        BigDecimal remaining = BigDecimal.ZERO;
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : batchRows) {
            Object wksId = row.get("workshopBatchId");
            if (wksId == null || wksId.toString().isBlank()) {
                continue;
            }
            if (!seen.add(wksId.toString())) {
                continue; // 去重: 同一 WKS 批次只计一次
            }
            hasWks = true;
            MaterialBatch wks = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(wksId.toString(), factoryId).orElse(null);
            if (wks == null) {
                continue; // 记录了 WKS 批次但已不存在 → 视作 0 现存 (诚实)
            }
            BigDecimal cur = wks.getCurrentQuantity();
            if (cur != null && cur.signum() > 0) {
                remaining = remaining.add(cur);
            }
        }
        return new WorkshopRemaining(hasWks, remaining);
    }

    /** {@link #computeWorkshopRemaining} 返回值: 是否存在 WKS 物化批次 + 现存合计。 */
    private record WorkshopRemaining(boolean hasWorkshopBatch, BigDecimal remaining) {
    }

    /**
     * 关单反向收尾: 从该行领料时物化的生产仓 (WKS) 批次划出 {@code qtyToDraw} = (退回 + 损耗),
     * 令生产仓批次归零, 避免退料 (已加回原料仓) 后生产仓仍留幽灵库存造成双计。
     *
     * <p>WKS 批次 id 记录在 {@code item.batchNumbers[*].workshopBatchId} (领料迁移时回写)。按记录顺序
     * 逐批划出。诚实处理: 记录了 workshopBatchId 但批次已不存在 → warn 跳过 (不阻断关单退料主流程);
     * 全部划完仍有剩余 (报工从原料仓而非生产仓消耗的不一致配置, 或重复关单) → warn, 不静默造负库存。
     */
    private void drawDownWorkshopBatchesForItem(String factoryId,
                                                FactoryMaterialRequisitionItem item,
                                                BigDecimal qtyToDraw) {
        if (materialBatchRepository == null || qtyToDraw == null
                || qtyToDraw.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<Map<String, Object>> batchRows = item.getBatchNumbers();
        if (batchRows == null || batchRows.isEmpty()) {
            return;
        }
        BigDecimal remaining = qtyToDraw;
        for (Map<String, Object> batchRow : batchRows) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            Object wksId = batchRow.get("workshopBatchId");
            if (wksId == null || wksId.toString().isBlank()) {
                continue;
            }
            MaterialBatch wks = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(wksId.toString(), factoryId)
                    .orElse(null);
            if (wks == null) {
                log.warn("关单退料: 物料需求单行 {} 生产仓批次 {} 不存在, 跳过生产仓划出",
                        item.getId(), wksId);
                continue;
            }
            BigDecimal available = wks.getCurrentQuantity();
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal draw = remaining.min(available);
            BigDecimal used = wks.getUsedQuantity() != null ? wks.getUsedQuantity() : BigDecimal.ZERO;
            wks.setUsedQuantity(used.add(draw));
            if (wks.getCurrentQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                wks.setStatus(MaterialBatchStatus.DEPLETED);
            }
            materialBatchRepository.save(wks);
            remaining = remaining.subtract(draw);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("关单退料: 物料需求单行 {} 生产仓批次可划出量不足, 剩余 {} 未从生产仓划出 "
                    + "(报工可能从原料仓而非生产仓消耗, 或重复关单)", item.getId(), remaining.toPlainString());
        }
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
