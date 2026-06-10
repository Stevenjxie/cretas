package com.cretas.aims.service.impl;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.LabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 标签服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelServiceImpl implements LabelService {

    private final LabelRepository labelRepository;
    private final MaterialBatchRepository materialBatchRepository;

    @Override
    public Page<Label> getLabels(String factoryId, Pageable pageable) {
        return labelRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public Page<Label> getLabelsByType(String factoryId, String labelType, Pageable pageable) {
        return labelRepository.findByFactoryIdAndLabelTypeAndDeletedAtIsNull(factoryId, labelType, pageable);
    }

    @Override
    public Page<Label> getLabelsByStatus(String factoryId, String status, Pageable pageable) {
        return labelRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
    }

    @Override
    public Optional<Label> getLabelById(String factoryId, String id) {
        return labelRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
    }

    @Override
    public Optional<Label> getByLabelCode(String labelCode) {
        return labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode);
    }

    @Override
    public Optional<Label> getByTraceCode(String traceCode) {
        return labelRepository.findByTraceCodeAndDeletedAtIsNull(traceCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label createLabel(Label label) {
        // 检查标签编码是否已存在
        if (label.getLabelCode() != null &&
            labelRepository.existsByLabelCodeAndDeletedAtIsNull(label.getLabelCode())) {
            throw new RuntimeException("标签编码已存在: " + label.getLabelCode());
        }

        if (label.getId() == null || label.getId().isEmpty()) {
            label.setId(UUID.randomUUID().toString());
        }
        if (label.getLabelCode() == null || label.getLabelCode().isEmpty()) {
            label.setLabelCode(generateLabelCode(label.getFactoryId(), label.getLabelType()));
        }
        if (label.getStatus() == null) {
            label.setStatus("ACTIVE");
        }
        if (label.getPrintCount() == null) {
            label.setPrintCount(0);
        }

        log.info("创建标签: factoryId={}, labelCode={}", label.getFactoryId(), label.getLabelCode());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Label> createLabels(List<Label> labels) {
        List<Label> created = new ArrayList<>();
        for (Label label : labels) {
            created.add(createLabel(label));
        }
        log.info("批量创建标签: count={}", created.size());
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label updateLabel(String id, Label updateData) {
        Label existing = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));

        if (updateData.getProductName() != null) existing.setProductName(updateData.getProductName());
        if (updateData.getSpecification() != null) existing.setSpecification(updateData.getSpecification());
        if (updateData.getProductionDate() != null) existing.setProductionDate(updateData.getProductionDate());
        if (updateData.getShelfLifeDays() != null) existing.setShelfLifeDays(updateData.getShelfLifeDays());
        if (updateData.getExpiryDate() != null) existing.setExpiryDate(updateData.getExpiryDate());
        if (updateData.getExtraData() != null) existing.setExtraData(updateData.getExtraData());

        return labelRepository.save(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label printLabel(String id, Long printedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("PRINTED");
        label.setPrintCount(label.getPrintCount() + 1);
        label.setLastPrintedAt(LocalDateTime.now());
        label.setPrintedBy(printedBy);
        log.info("打印标签: id={}, printCount={}", id, label.getPrintCount());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label applyLabel(String id, Long appliedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("APPLIED");
        label.setAppliedAt(LocalDateTime.now());
        label.setAppliedBy(appliedBy);
        log.info("贴标: id={}", id);
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label voidLabel(String id, Long updatedBy) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setStatus("VOIDED");
        log.info("作废标签: id={}", id);
        return labelRepository.save(label);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLabel(String id) {
        Label label = labelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("标签不存在: " + id));
        label.setDeletedAt(LocalDateTime.now());
        labelRepository.save(label);
        log.info("删除标签: id={}", id);
    }

    @Override
    public List<Label> getLabelsByBatchId(String batchId) {
        return labelRepository.findByBatchIdAndDeletedAtIsNull(batchId);
    }

    @Override
    public List<Label> getLabelsByProductionBatchId(Long productionBatchId) {
        return labelRepository.findByProductionBatchIdAndDeletedAtIsNull(productionBatchId);
    }

    @Override
    public List<Label> getExpiringLabels(String factoryId, int daysAhead) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusDays(daysAhead);
        return labelRepository.findExpiringLabels(factoryId, now, threshold);
    }

    @Override
    public List<Label> getExpiredLabels(String factoryId) {
        return labelRepository.findExpiredLabels(factoryId, LocalDateTime.now());
    }

    @Override
    public long countByFactory(String factoryId) {
        return labelRepository.countByFactoryIdAndDeletedAtIsNull(factoryId);
    }

    @Override
    public long countByStatus(String factoryId, String status) {
        return labelRepository.countByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status);
    }

    @Override
    public String generateLabelCode(String factoryId, String labelType) {
        String prefix = labelType != null ? labelType.substring(0, 2) : "LB";
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String seq = String.format("%04d", (int) (Math.random() * 10000));
        return prefix + "-" + factoryId + "-" + dateStr + "-" + seq;
    }

    @Override
    public String generateTraceCode(String factoryId, String batchNumber) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = String.format("%06d", (int) (Math.random() * 1000000));
        return "TRACE-" + factoryId + "-" + dateStr + "-" + seq;
    }

    // ========== SP4-T5: 物料批次标签生成 + 扫码查询 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Label generateMaterialBatchLabel(String factoryId, String batchId, Long userId) {
        // 1. 批次存在校验
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new RuntimeException("物料批次不存在: batchId=" + batchId));

        // 2. 防呆: 已有 ACTIVE 标签则拒绝重复生成
        List<Label> existing = labelRepository.findByBatchIdAndDeletedAtIsNull(batchId);
        boolean hasActive = existing.stream()
                .anyMatch(l -> "ACTIVE".equals(l.getStatus())
                        || "PRINTED".equals(l.getStatus()));
        if (hasActive) {
            throw new RuntimeException("该物料批次已存在有效标签, batchId=" + batchId
                    + " — 请先作废旧标签再重新生成 (already have active/printed label)");
        }

        // 3. 构建 MATERIAL 类型标签
        Label label = new Label();
        label.setId(UUID.randomUUID().toString());
        label.setFactoryId(factoryId);
        label.setBatchType("MATERIAL");
        label.setBatchId(batchId);
        label.setStatus("ACTIVE");
        label.setPrintCount(0);
        label.setCreatedBy(userId);

        // A3: 数字前缀复用 SP8 primaryCode (RawMaterialType.primaryCode 前三位),
        // 避免双编码体系。无 primaryCode 时降级为 "MA" 兜底。
        String sp8Prefix = (batch.getMaterialType() != null
                && batch.getMaterialType().getPrimaryCode() != null
                && !batch.getMaterialType().getPrimaryCode().isBlank())
                ? batch.getMaterialType().getPrimaryCode()
                : "MA";
        label.setLabelCode(generateLabelCode(factoryId, sp8Prefix));
        label.setTraceCode(generateTraceCode(factoryId, batch.getBatchNumber()));
        // 物料名称从 materialType 关联实体获取 (lazy, but within transaction)
        if (batch.getMaterialType() != null) {
            label.setProductName(batch.getMaterialType().getName());
        }
        log.info("SP4-T5: 生成物料批次标签 batchId={}, labelCode={}", batchId, label.getLabelCode());
        return labelRepository.save(label);
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialBatchLabelScanResponse scanLabel(String factoryId, String labelCode) {
        // 1. 标签存在
        Label label = labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode)
                .orElseThrow(() -> new RuntimeException("标签不存在: labelCode=" + labelCode));

        // 2. 跨工厂访问防护
        if (!factoryId.equals(label.getFactoryId())) {
            throw new RuntimeException("无权访问该标签: 标签属于工厂 "
                    + label.getFactoryId() + ", 当前工厂 " + factoryId
                    + " (cross-factory access denied)");
        }

        // 3. VOIDED 标签禁止使用
        if ("VOIDED".equals(label.getStatus())) {
            throw new RuntimeException("标签已作废 (VOIDED), 禁止使用: labelCode=" + labelCode);
        }

        // 4. 获取批次信息 (MATERIAL 类型绑定到 MaterialBatch)
        MaterialBatch batch = null;
        if ("MATERIAL".equals(label.getBatchType()) && label.getBatchId() != null) {
            batch = materialBatchRepository
                    .findByIdAndFactoryId(label.getBatchId(), factoryId)
                    .orElse(null); // 标签可能在批次删除后仍存在
        }

        // 5. 组装响应 DTO
        MaterialBatchLabelScanResponse.MaterialBatchLabelScanResponseBuilder builder =
                MaterialBatchLabelScanResponse.builder()
                        .labelId(label.getId())
                        .labelCode(label.getLabelCode())
                        .labelStatus(label.getStatus())
                        .traceCode(label.getTraceCode())
                        .batchId(label.getBatchId())
                        .labelCreatedAt(label.getCreatedAt());

        if (batch != null) {
            // 物料名称从 materialType 关联实体获取, specification 用数量单位代替
            String materialName = (batch.getMaterialType() != null)
                    ? batch.getMaterialType().getName() : null;
            String specification = batch.getQuantityUnit();   // e.g. "kg" / "箱"
            builder.batchNumber(batch.getBatchNumber())
                    .materialName(materialName)
                    .specification(specification)
                    .factoryNumber(batch.getFactoryNumber())
                    .originPlace(batch.getOriginPlace())
                    .batchCreatedAt(batch.getCreatedAt());
        }

        log.info("SP4-T5: 扫码查询 labelCode={}, batchId={}", labelCode, label.getBatchId());
        return builder.build();
    }
}
