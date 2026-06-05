package com.cretas.aims.service.wip.impl;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WipInventoryServiceImpl implements WipInventoryService {

    private final SemiFinishedInventoryRepository wipRepo;
    private final BatchLineageEdgeRepository lineageEdgeRepo;

    @Override
    public SemiFinishedInventory validateSourceWip(String sourceWipNo, BigDecimal inputQuantity, String inputUnit) {
        if (sourceWipNo == null || sourceWipNo.isBlank()) {
            return null;
        }
        SemiFinishedInventory sourceWip = wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(sourceWipNo)
                .orElseThrow(() -> new BusinessException(404, "源半成品库存不存在: " + sourceWipNo)
                        .withHint("请重新选择要领用的上道半成品批次")
                        .withHintTarget("sourceWipNo"));
        if (inputQuantity == null) {
            throw new BusinessException(409, "领用半成品时必须填写本道投入量")
                    .withCode("WIP_INPUT_REQUIRED")
                    .withHint("已选择上道半成品，请填写本道实际投入量")
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
        validateUnit(sourceWip, inputUnit);
        validateAvailable(sourceWip, inputQuantity);
        return sourceWip;
    }

    @Override
    @Transactional
    public void postApprovedOutput(String factoryId, ProductionReport report, WorkProcessTask task, Long operatorId) {
        if (report == null || task == null || task.getProductionBatchId() == null) {
            return;
        }
        if (report.getSourceWipNo() != null && !report.getSourceWipNo().isBlank()
                && report.getInputQuantity() != null) {
            SemiFinishedInventory sourceWip = validateSourceWip(
                    report.getSourceWipNo(), report.getInputQuantity(), report.getInputUnit());
            consumeSourceWip(sourceWip, report.getInputQuantity(), report, task, operatorId);
        }
        if (report.getOutputQuantity() != null && report.getOutputQuantity().compareTo(BigDecimal.ZERO) > 0) {
            upsertProducedWip(factoryId, report, task);
        }
    }

    private void upsertProducedWip(String factoryId, ProductionReport report, WorkProcessTask task) {
        String wipNo = generateBatchNo(task);
        BigDecimal out = nz(report.getOutputQuantity());
        SemiFinishedInventory wip = wipRepo.findByIntermediateBatchNoAndDeletedAtIsNull(wipNo).orElse(null);
        String outputUnit = firstNonBlank(report.getOutputUnit(), task.getPlannedUnit());

        if (wip == null) {
            wip = SemiFinishedInventory.builder()
                    .factoryId(factoryId)
                    .batchId(task.getProductionBatchId())
                    .intermediateBatchNo(wipNo)
                    .sourceWorkProcessTaskId(task.getId())
                    .processOrder(task.getProcessOrder())
                    .productTypeId(task.getProductTypeId())
                    .producedQuantity(out)
                    .consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(out)
                    .unit(outputUnit)
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .materialBatchRefs(report.getMaterialBatchRefs())
                    .build();
        } else {
            BigDecimal produced = nz(wip.getProducedQuantity()).add(out);
            BigDecimal consumed = nz(wip.getConsumedQuantity());
            wip.setProducedQuantity(produced);
            wip.setAvailableQuantity(produced.subtract(consumed));
            if (wip.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0
                    && !SemiFinishedInventory.Status.RETURNED.equals(wip.getStatus())) {
                wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
            }
            if (wip.getUnit() == null) {
                wip.setUnit(outputUnit);
            }
        }

        wip.setAccumulatedCost(nullSafeAdd(wip.getAccumulatedCost(), report.getLaborCost(), report.getMaterialCost()));
        BigDecimal produced = wip.getProducedQuantity();
        if (wip.getAccumulatedCost() != null && produced != null && produced.signum() > 0) {
            wip.setUnitCost(wip.getAccumulatedCost().divide(produced, 4, RoundingMode.HALF_UP));
        } else {
            wip.setUnitCost(null);
        }
        wipRepo.save(wip);
    }

    private void consumeSourceWip(SemiFinishedInventory sourceWip, BigDecimal input,
                                  ProductionReport report, WorkProcessTask task, Long operatorId) {
        BigDecimal consumed = nz(sourceWip.getConsumedQuantity()).add(input);
        BigDecimal produced = nz(sourceWip.getProducedQuantity());
        sourceWip.setConsumedQuantity(consumed);
        sourceWip.setAvailableQuantity(produced.subtract(consumed));
        if (sourceWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            sourceWip.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sourceWip);
        recordWipLineageEdge(report.getFactoryId(), sourceWip, task, input, operatorId);
    }

    private void validateUnit(SemiFinishedInventory sourceWip, String inputUnit) {
        String wipUnit = sourceWip.getUnit();
        if (wipUnit != null && !wipUnit.isBlank()
                && inputUnit != null && !inputUnit.isBlank()
                && !wipUnit.equals(inputUnit)) {
            throw new BusinessException(409, "半成品单位与本道投入单位不一致")
                    .withCode("WIP_UNIT_MISMATCH")
                    .withHint(String.format("WIP 单位为 %s, 本道投入单位为 %s, 跨单位领用需先配置换算系数",
                            wipUnit, inputUnit))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputUnit");
        }
    }

    private void validateAvailable(SemiFinishedInventory sourceWip, BigDecimal inputQuantity) {
        if (inputQuantity == null) {
            return;
        }
        BigDecimal avail = nz(sourceWip.getAvailableQuantity());
        if (inputQuantity.compareTo(avail) > 0) {
            String u = sourceWip.getUnit() == null ? "" : sourceWip.getUnit();
            throw new BusinessException(409, "领用量超过半成品余额")
                    .withCode("WIP_INSUFFICIENT")
                    .withHint(String.format("WIP 余额仅 %s %s, 不能领 %s %s",
                            avail.stripTrailingZeros().toPlainString(), u,
                            inputQuantity.stripTrailingZeros().toPlainString(), u))
                    .withSeverity("BLOCKING")
                    .withHintTarget("inputQuantity");
        }
    }

    private void recordWipLineageEdge(String factoryId, SemiFinishedInventory sourceWip,
                                      WorkProcessTask task, BigDecimal qty, Long operatorId) {
        try {
            BatchLineageEdge edge = new BatchLineageEdge();
            edge.setFactoryId(factoryId);
            edge.setEdgeType("WIP_CONSUME");
            edge.setSourceType("PRODUCTION_BATCH");
            edge.setSourceId(String.valueOf(sourceWip.getBatchId() == null
                    ? task.getProductionBatchId() : sourceWip.getBatchId()));
            edge.setTargetType("PRODUCTION_BATCH");
            edge.setTargetId(String.valueOf(task.getProductionBatchId()));
            edge.setQuantityUsed(qty);
            edge.setUnit(sourceWip.getUnit());
            edge.setEventTime(LocalDateTime.now());
            edge.setOperatorId(operatorId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("sourceWipNo", sourceWip.getIntermediateBatchNo());
            meta.put("targetWorkProcessTaskId", task.getId());
            meta.put("targetProcessOrder", task.getProcessOrder());
            edge.setMeta(meta);
            lineageEdgeRepo.save(edge);
        } catch (Exception e) {
            log.warn("[lineage] WIP 领用边写入失败 (fail-soft, 不阻塞报工): sourceWipNo={} batchId={} qty={}",
                    sourceWip.getIntermediateBatchNo(), task.getProductionBatchId(), qty, e);
        }
    }

    private String generateBatchNo(WorkProcessTask task) {
        return String.format("%s-B%d-S%d-%d",
                task.getProductTypeId() == null ? "NA" : task.getProductTypeId(),
                task.getProductionBatchId(),
                task.getProcessOrder() == null ? 0 : task.getProcessOrder(),
                task.getId());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nullSafeAdd(BigDecimal base, BigDecimal... values) {
        BigDecimal out = base;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            out = out == null ? value : out.add(value);
        }
        return out;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
