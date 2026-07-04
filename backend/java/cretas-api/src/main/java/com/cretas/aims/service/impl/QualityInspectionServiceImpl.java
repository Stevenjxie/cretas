package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductionAlert;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.event.ProductionAlertEvent;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.QualityInspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量检验服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityInspectionServiceImpl implements QualityInspectionService {

    private final QualityInspectionRepository qualityInspectionRepository;
    private final ProductionAlertRepository productionAlertRepository;
    private final ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionBatchRepository productionBatchRepository;

    /**
     * 🔒🔒 QC 生产门 (食品安全): 质检判 FAILED 时用于隔离该生产计划已入库的可售成品批次。
     * optional (required=false) 保持与 productionBatchRepository 一致, 不破坏未 wire 该 bean 的单测。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /**
     * Round 10 Task 4 — Canvas Integration Template 5th service to receive this hook.
     * Writes factory-configured dynamic fields into the cf_* columns on
     * quality_inspections so downstream readers (reports, trigger chains, exports)
     * can read them without cracking open the legacy JSONB custom_fields column.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "quality_inspection", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    public PageResponse<QualityInspection> getInspections(String factoryId, String productionBatchId, PageRequest pageRequest) {
        log.info("分页查询质量检验记录: factoryId={}, productionBatchId={}, page={}, size={}",
                factoryId, productionBatchId, pageRequest.getPage(), pageRequest.getSize());

        org.springframework.data.domain.PageRequest springPageRequest =
                org.springframework.data.domain.PageRequest.of(
                    pageRequest.getPage() - 1,
                    pageRequest.getSize()
                );

        Page<QualityInspection> page;
        if (productionBatchId != null && !productionBatchId.trim().isEmpty()) {
            page = qualityInspectionRepository.findByFactoryIdAndProductionBatchId(
                    factoryId, Long.parseLong(productionBatchId), springPageRequest);
        } else {
            page = qualityInspectionRepository.findByFactoryId(factoryId, springPageRequest);
        }

        PageResponse<QualityInspection> response = new PageResponse<>();
        response.setContent(page.getContent());
        response.setPage(pageRequest.getPage());
        response.setSize(pageRequest.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setFirst(page.isFirst());
        response.setLast(page.isLast());

        log.info("查询成功: 共{}条记录", page.getTotalElements());
        return response;
    }

    @Override
    public QualityInspection getInspectionById(String factoryId, String inspectionId) {
        log.info("获取质量检验记录详情: factoryId={}, inspectionId={}", factoryId, inspectionId);

        return qualityInspectionRepository.findById(inspectionId)
                .filter(inspection -> inspection.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new EntityNotFoundException("QualityInspection", inspectionId));
    }

    @Override
    @Transactional
    public QualityInspection createInspection(String factoryId, QualityInspection inspection) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "result", inspection.getResult() != null ? inspection.getResult() : ""));
        log.info("创建质量检验记录: factoryId={}, productionBatchId={}",
                factoryId, inspection.getProductionBatchId());

        // 生成UUID作为ID
        if (inspection.getId() == null || inspection.getId().trim().isEmpty()) {
            inspection.setId(java.util.UUID.randomUUID().toString());
        }

        inspection.setFactoryId(factoryId);
        QualityInspection saved = qualityInspectionRepository.save(inspection);

        log.info("质量检验记录创建成功: inspectionId={}", saved.getId());

        // Round 10 Fix Task 4 (R8-α Gap #3 per-module template): persist Canvas V3
        // dynamic fields into cf_* columns on quality_inspections. Customer-configured
        // fields (二次复检结果, 异常照片链接, 设备状态标记, etc.) now land in dedicated
        // columns — previously they only lived in the legacy JSONB custom_fields column
        // which downstream readers don't consult. Silent failure here must not break
        // QI creation or downstream alert publishing.
        if (dynamicFieldService != null && saved.getCustomFields() != null && !saved.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "quality_inspection", saved.getId(), saved.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for quality inspection {}: {}", saved.getId(), e.getMessage());
            }
        }

        // QI FAIL → 自动创建告警 + 发送通知
        if ("FAIL".equalsIgnoreCase(saved.getResult())) {
            createQualityFailAlert(saved);
        }

        propagateToProductionBatch(saved);

        return saved;
    }

    private void propagateToProductionBatch(QualityInspection inspection) {
        // Writes back ONLY qualityStatus (PASS/FAIL/CONDITIONAL) from the latest
        // inspection. batch.yieldRate has a different meaning — it is the batch's
        // production yield (goodQuantity / actualQuantity) computed in
        // ProductionBatch.recalculate() on production events. Inspection.passRate
        // is an audit sampling ratio (passCount / sampleSize) and must not
        // overwrite the production yield.
        if (productionBatchRepository == null) return;
        Long batchId = inspection.getProductionBatchId();
        if (batchId == null) return;
        try {
            productionBatchRepository.findById(batchId).ifPresent(batch -> {
                QualityStatus newStatus = mapInspectionResultToBatchQuality(inspection.getResult());
                if (newStatus == null) return;
                batch.setQualityStatus(newStatus);
                productionBatchRepository.save(batch);
                // 🔒🔒 QC 生产门 (食品安全, 2026-07-04): 质检判 FAILED → 该生产计划已入库的可售成品批次
                // 立即隔离为 DEFECTIVE (不可售)。覆盖"先小结产出可售成品, 后质检失败"的时序。成品可售/FEFO
                // 查询过滤 status='AVAILABLE' 自动排除 DEFECTIVE。质检经理复核后可放行(→AVAILABLE)。
                if (newStatus == QualityStatus.FAILED) {
                    quarantineFinishedGoodsForFailedBatch(batch);
                }
            });
        } catch (Exception e) {
            log.warn("Batch writeback failed for inspection {} → batch {}: {}",
                    inspection.getId(), batchId, e.getMessage());
        }
    }

    /**
     * 🔒🔒 QC 生产门 (食品安全): 生产批次质检 FAILED → 隔离该生产计划下所有可售成品批次。
     *
     * <p>追溯链: {@code QualityInspection.productionBatchId → ProductionBatch.productionPlanId →
     * FinishedGoodsBatch.productionPlanId}。一个生产计划可跨多次「小结」产出多个成品批次 (1:N),
     * 故按 productionPlanId 批量隔离。只把 AVAILABLE 的可售批次改 DEFECTIVE (不动 DEPLETED/EXPIRED/
     * REVERSED/FROZEN 等非可售终态)。已发货部分不回退, 但剩余可售库存立即冻结, 阻止不合格品继续售出。
     *
     * <p>fail-soft: 隔离失败仅记 error 日志, 不回滚质检结果写回 (质检记录是权威食品安全事件, 必须落库)。
     */
    private void quarantineFinishedGoodsForFailedBatch(ProductionBatch batch) {
        if (finishedGoodsBatchRepository == null) return;
        String planId = batch.getProductionPlanId();
        if (planId == null || planId.isBlank()) return;
        try {
            var fgBatches = finishedGoodsBatchRepository
                    .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(batch.getFactoryId(), planId);
            int quarantined = 0;
            for (var fg : fgBatches) {
                if ("AVAILABLE".equals(fg.getStatus())) {
                    fg.setStatus("DEFECTIVE");
                    finishedGoodsBatchRepository.save(fg);
                    quarantined++;
                }
            }
            if (quarantined > 0) {
                log.warn("QC 失败隔离成品: productionBatchId={}, planId={}, 隔离 {} 个可售成品批次 → DEFECTIVE (不可售, 待质检复核)",
                        batch.getId(), planId, quarantined);
            }
        } catch (Exception e) {
            log.error("QC 失败隔离成品异常 (不阻断质检结果落库): productionBatchId={}, planId={}: {}",
                    batch.getId(), planId, e.getMessage());
        }
    }

    private QualityStatus mapInspectionResultToBatchQuality(String result) {
        if (result == null) return null;
        switch (result.toUpperCase()) {
            case "PASS":
            case "PASSED":
            case "QUALIFIED":
                return QualityStatus.PASSED;
            case "FAIL":
            case "FAILED":
                return QualityStatus.FAILED;
            case "CONDITIONAL":
            case "PARTIAL_PASS":
                return QualityStatus.PARTIAL_PASS;
            case "PENDING":
                return QualityStatus.PENDING_INSPECTION;
            default:
                return null;
        }
    }

    @Override
    @Transactional
    public QualityInspection updateInspection(String factoryId, String inspectionId, QualityInspection inspection) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "inspectionId", inspectionId,
            "result", inspection.getResult() != null ? inspection.getResult() : ""));
        log.info("更新质量检验记录: factoryId={}, inspectionId={}", factoryId, inspectionId);

        QualityInspection existing = getInspectionById(factoryId, inspectionId);

        // 更新允许修改的字段
        if (inspection.getInspectorId() != null) {
            existing.setInspectorId(inspection.getInspectorId());
        }
        if (inspection.getResult() != null) {
            existing.setResult(inspection.getResult());
        }
        if (inspection.getSampleSize() != null) {
            existing.setSampleSize(inspection.getSampleSize());
        }
        if (inspection.getPassCount() != null) {
            existing.setPassCount(inspection.getPassCount());
        }
        if (inspection.getFailCount() != null) {
            existing.setFailCount(inspection.getFailCount());
        }
        if (inspection.getPassRate() != null) {
            existing.setPassRate(inspection.getPassRate());
        }
        if (inspection.getNotes() != null) {
            existing.setNotes(inspection.getNotes());
        }

        // R13: merge incoming customFields into existing before save
        if (inspection.getCustomFields() != null && !inspection.getCustomFields().isEmpty()) {
            java.util.Map<String, Object> merged = new java.util.HashMap<>(
                    existing.getCustomFields() != null ? existing.getCustomFields() : java.util.Map.of());
            merged.putAll(inspection.getCustomFields());
            existing.setCustomFields(merged);
        }

        QualityInspection updated = qualityInspectionRepository.save(existing);

        // R13: persist dynamic fields on update path (R10 T4 only did create)
        if (dynamicFieldService != null && updated.getCustomFields() != null && !updated.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "quality_inspection", updated.getId(), updated.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for QI update {}: {}", updated.getId(), e.getMessage());
            }
        }

        log.info("质量检验记录更新成功: inspectionId={}", updated.getId());

        if ("FAIL".equalsIgnoreCase(updated.getResult())) {
            createQualityFailAlert(updated);
        }

        return updated;
    }

    /**
     * 质检不合格时创建 QUALITY_FAIL 告警并发布事件通知相关人员
     */
    private void createQualityFailAlert(QualityInspection inspection) {
        try {
            String level = "CRITICAL";
            double passRate = inspection.getPassRate() != null ? inspection.getPassRate().doubleValue() : 0;
            if (passRate >= 70) {
                level = "WARNING";
            }

            String description = String.format("质检不合格 — 批次ID: %d, 合格率: %.1f%%, 不合格数: %s",
                    inspection.getProductionBatchId(),
                    passRate,
                    inspection.getFailCount() != null ? inspection.getFailCount().toPlainString() : "N/A");

            ProductionAlert alert = ProductionAlert.builder()
                    .factoryId(inspection.getFactoryId())
                    .alertType("QUALITY_FAIL")
                    .level(level)
                    .status("ACTIVE")
                    .metricName("quality_pass_rate")
                    .currentValue(passRate)
                    .baselineValue(95.0)
                    .thresholdValue(70.0)
                    .batchId(inspection.getProductionBatchId())
                    .description(description)
                    .build();

            ProductionAlert saved = productionAlertRepository.save(alert);

            // 发布事件 → AlertNotificationListener 异步通知相关人员
            eventPublisher.publishEvent(new ProductionAlertEvent(
                    this, saved.getId(), saved.getFactoryId(),
                    saved.getAlertType(), saved.getLevel(), saved.getDescription()));

            log.info("质检不合格告警已创建: alertId={}, level={}, batchId={}",
                    saved.getId(), level, inspection.getProductionBatchId());
        } catch (Exception e) {
            log.error("创建质检告警失败: inspectionId={}", inspection.getId(), e);
        }
    }
}
