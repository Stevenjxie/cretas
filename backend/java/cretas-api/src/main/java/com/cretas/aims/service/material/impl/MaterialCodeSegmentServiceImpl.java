package com.cretas.aims.service.material.impl;

import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.material.MaterialCodeSegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SP8: 物料分段编码字典 Service 实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialCodeSegmentServiceImpl implements MaterialCodeSegmentService {

    private final MaterialCodeSegmentRepository repo;
    private final RawMaterialTypeRepository materialTypeRepository;

    @Override
    public List<MaterialCodeSegmentDTO> listByLevel(String factoryId, short level) {
        return repo.findByFactoryIdAndLevelOrderBySortOrderAscSegmentCodeAsc(factoryId, level)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialCodeSegmentDTO> getTree(String factoryId) {
        List<MaterialCodeSegment> all = repo.findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(factoryId);

        // 按 parentCode 分组; L1 nodes have parentCode = null
        Map<String, List<MaterialCodeSegmentDTO>> byParent = all.stream()
                .filter(s -> s.getParentCode() != null)
                .map(this::toDTO)
                .collect(Collectors.groupingBy(MaterialCodeSegmentDTO::getParentCode));

        // Build L2 subtrees first
        // L2 children map: keyed by L2's segmentCode for L3 attachment
        List<MaterialCodeSegmentDTO> l1Nodes = all.stream()
                .filter(s -> s.getLevel() != null && s.getLevel() == 1)
                .map(this::toDTO)
                .collect(Collectors.toList());

        for (MaterialCodeSegmentDTO l1 : l1Nodes) {
            List<MaterialCodeSegmentDTO> l2Children = byParent.getOrDefault(l1.getSegmentCode(), new ArrayList<>());
            for (MaterialCodeSegmentDTO l2 : l2Children) {
                List<MaterialCodeSegmentDTO> l3Children = byParent.getOrDefault(l2.getSegmentCode(), new ArrayList<>());
                l2.setChildren(l3Children.isEmpty() ? null : l3Children);
            }
            l1.setChildren(l2Children.isEmpty() ? null : l2Children);
        }

        return l1Nodes;
    }

    @Override
    @Transactional
    public MaterialCodeSegmentDTO create(String factoryId, CreateMaterialCodeSegmentRequest req) {
        // Validate segment_code uniqueness
        if (repo.existsByFactoryIdAndSegmentCode(factoryId, req.getSegmentCode())) {
            throw new BusinessException(409, "编码段 " + req.getSegmentCode() + " 在该工厂已存在")
                    .withHint("请使用不同的编码值").withHintTarget("segmentCode");
        }

        // Validate parent exists for level 2/3
        if (req.getLevel() > 1) {
            if (req.getParentCode() == null || req.getParentCode().isBlank()) {
                throw new BusinessException(400, "level " + req.getLevel() + " 节点必须指定 parentCode");
            }
            repo.findByFactoryIdAndSegmentCode(factoryId, req.getParentCode())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "父级编码 " + req.getParentCode() + " 不存在, 请先创建上级节点"));
        }

        MaterialCodeSegment entity = MaterialCodeSegment.builder()
                .factoryId(factoryId)
                .level(req.getLevel())
                .segmentCode(req.getSegmentCode())
                .segmentLabel(req.getSegmentLabel())
                .parentCode(req.getParentCode())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        entity = repo.save(entity);
        log.info("SP8: 创建物料编码段 factoryId={} code={} label={}", factoryId, entity.getSegmentCode(), entity.getSegmentLabel());
        return toDTO(entity);
    }

    @Override
    @Transactional
    public MaterialCodeSegmentDTO update(String factoryId, Long id, CreateMaterialCodeSegmentRequest req) {
        MaterialCodeSegment entity = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("物料编码段不存在: id=" + id));

        if (!factoryId.equals(entity.getFactoryId())) {
            throw new BusinessException(403, "无权限操作此编码段");
        }

        // If segmentCode changes, check new code uniqueness
        if (req.getSegmentCode() != null && !req.getSegmentCode().equals(entity.getSegmentCode())) {
            if (repo.existsByFactoryIdAndSegmentCode(factoryId, req.getSegmentCode())) {
                throw new BusinessException(409, "编码段 " + req.getSegmentCode() + " 已存在")
                        .withHint("请使用不同的编码值").withHintTarget("segmentCode");
            }
            entity.setSegmentCode(req.getSegmentCode());
        }

        if (req.getSegmentLabel() != null) entity.setSegmentLabel(req.getSegmentLabel());
        if (req.getParentCode() != null) entity.setParentCode(req.getParentCode());
        if (req.getSortOrder() != null) entity.setSortOrder(req.getSortOrder());
        if (req.getIsActive() != null) entity.setIsActive(req.getIsActive());
        entity.setUpdatedAt(LocalDateTime.now());

        entity = repo.save(entity);
        return toDTO(entity);
    }

    @Override
    @Transactional
    public void delete(String factoryId, Long id) {
        MaterialCodeSegment entity = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("物料编码段不存在: id=" + id));

        if (!factoryId.equals(entity.getFactoryId())) {
            throw new BusinessException(403, "无权限删除此编码段");
        }

        entity.setDeletedAt(LocalDateTime.now());
        repo.save(entity);
        log.info("SP8: 软删除物料编码段 factoryId={} id={} code={}", factoryId, id, entity.getSegmentCode());
    }

    @Override
    public boolean hasSegmentDictionary(String factoryId) {
        return repo.countByFactoryIdAndLevel(factoryId, (short) 1) > 0;
    }

    /**
     * generate-code 端点实现: 取 L3 segmentCode (10位) 作前缀, 扫已有编码序号, +1 返回16位预览.
     * 若字典未配置则返回 null.
     */
    @Override
    public String generateCode(String factoryId, String l1, String l2, String l3) {
        // 字典未配置 — 诚实返回 null, 调用方降级
        if (!hasSegmentDictionary(factoryId)) {
            log.info("generate-code: 工厂 {} 尚未配置分段字典, 返回 null", factoryId);
            return null;
        }

        // L3 segmentCode 是最终的10位累积编码 (l3 就是累积的10位)
        // 但前端传的是各级独立 segmentCode, l3 本身应是10位 cumulative code.
        // 规范: L3 cumulative = l3 (already 10-digit per schema).
        // 若 l3 不是10位 → 视为参数无效, 直接返回 null.
        if (l3 == null || !l3.matches("[0-9]{10}")) {
            log.warn("generate-code: l3={} 不是10位数字, 参数无效", l3);
            return null;
        }

        String segmentPrefix = l3; // 10-digit cumulative L3 code
        List<String> existing = materialTypeRepository
                .findCodesByFactoryIdAndSegmentPrefix(factoryId, segmentPrefix);

        int maxSeq = 0;
        for (String code : existing) {
            if (code.length() == 16) {
                String seqPart = code.substring(10);
                try {
                    int seq = Integer.parseInt(seqPart);
                    if (seq > maxSeq) maxSeq = seq;
                } catch (NumberFormatException ignored) {
                    // skip non-numeric suffix
                }
            }
        }
        String generated = String.format("%s%06d", segmentPrefix, maxSeq + 1);
        log.info("generate-code: factoryId={} l1={} l2={} l3={} → {}", factoryId, l1, l2, l3, generated);
        return generated;
    }

    // ——— helpers ———

    MaterialCodeSegmentDTO toDTO(MaterialCodeSegment e) {
        return MaterialCodeSegmentDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .level(e.getLevel())
                .segmentCode(e.getSegmentCode())
                .segmentLabel(e.getSegmentLabel())
                .parentCode(e.getParentCode())
                .sortOrder(e.getSortOrder())
                .isActive(e.getIsActive())
                .build();
    }
}
