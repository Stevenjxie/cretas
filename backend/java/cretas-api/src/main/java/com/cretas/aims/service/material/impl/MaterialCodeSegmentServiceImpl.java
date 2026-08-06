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
import org.springframework.dao.DataIntegrityViolationException;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
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
        validateHierarchy(factoryId, req.getLevel(), req.getSegmentCode(), req.getParentCode());
        String label = requireLabel(req.getSegmentLabel());
        String normalizedLabel = normalizeLabel(label);
        rejectDuplicateLabel(factoryId, req.getLevel(), req.getParentCode(), normalizedLabel, null);
        // Validate segment_code uniqueness —— 必须按**含软删除**的口径查, 与唯一约束
        // uk_mcs_factory_segment 对齐。用派生查询会被实体上的 @Where 挡住软删行,
        // 于是这里放行、INSERT 才炸, 而炸出来的报错还被 catch 成了「重名」。
        if (repo.existsBySegmentCodeIncludingDeleted(factoryId, req.getSegmentCode())) {
            throw segmentCodeTaken(factoryId, req.getSegmentCode());
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
                .segmentLabel(label)
                .normalizedLabel(normalizedLabel)
                .parentCode(req.getParentCode())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        try {
            entity = repo.save(entity);
            repo.flush();
        } catch (DataIntegrityViolationException conflict) {
            // ⛔ 不要把任何完整性冲突都说成「重名」。这张表有两个唯一约束:
            //   uk_mcs_factory_segment                (factory_id, segment_code)  ← 含软删除
            //   uq_mcs_parent_normalized_label_active (…, normalized_label) WHERE deleted_at IS NULL
            // 2026-08-06 客户撞的是前者(编码被软删行占着), 却收到后者的文案 +
            // 「请改个语义不同的名称」—— 改名字永远修不好编码冲突, 用户只能反复试。
            throw describeConflict(conflict, factoryId, req.getSegmentCode(), label);
        }
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
        short effectiveLevel = entity.getLevel();
        String effectiveCode = req.getSegmentCode() != null ? req.getSegmentCode() : entity.getSegmentCode();
        String effectiveParent = req.getParentCode() != null ? req.getParentCode() : entity.getParentCode();
        validateHierarchy(factoryId, effectiveLevel, effectiveCode, effectiveParent);
        String effectiveLabel = req.getSegmentLabel() == null
                ? entity.getSegmentLabel() : requireLabel(req.getSegmentLabel());
        String normalizedLabel = normalizeLabel(effectiveLabel);
        rejectDuplicateLabel(factoryId, effectiveLevel, effectiveParent, normalizedLabel, entity.getId());

        // If segmentCode changes, check new code uniqueness
        if (req.getSegmentCode() != null && !req.getSegmentCode().equals(entity.getSegmentCode())) {
            // 同 create(): 编码占用要按含软删除的口径查, 否则改编码时同样会被
            // uk_mcs_factory_segment 在 INSERT/UPDATE 阶段拦下并报出误导性文案。
            if (repo.existsBySegmentCodeIncludingDeleted(factoryId, req.getSegmentCode())) {
                throw segmentCodeTaken(factoryId, req.getSegmentCode());
            }
            entity.setSegmentCode(req.getSegmentCode());
        }

        if (req.getSegmentLabel() != null) {
            entity.setSegmentLabel(effectiveLabel);
            entity.setNormalizedLabel(normalizedLabel);
        }
        if (req.getParentCode() != null) entity.setParentCode(req.getParentCode());
        if (req.getSortOrder() != null) entity.setSortOrder(req.getSortOrder());
        if (req.getIsActive() != null) entity.setIsActive(req.getIsActive());
        entity.setUpdatedAt(LocalDateTime.now());

        try {
            entity = repo.save(entity);
            repo.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw duplicateLabel(effectiveLabel);
        }
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

        // ⛔ 2026-08-06 之前这里**一个守卫都没有**: 直接盖 deleted_at 就返回。
        // 后果是删除看起来毫无代价 —— 客户 08-04 一次性删掉 226 个 L3 + 2 个 L2,
        // 而编码被删掉的行继续占着(唯一约束含软删), 于是重建时撞码, 报错还说成「重名」。
        // 更隐蔽的是: 物料的分类归属在建完之后界面上根本不再显示(级联只在新建时用),
        // 所以删掉分类**当场没有任何症状**, 没有反馈回路阻止这个动作。
        long liveChildren = repo.countByFactoryIdAndParentCode(factoryId, entity.getSegmentCode());
        if (liveChildren > 0) {
            throw new BusinessException(409, "该分类下还有 " + liveChildren + " 个未删除的下级分类")
                    .withHint("请先处理下级分类；若只是不想再用它建新物料，改用「停用」即可")
                    .withHintTarget("segmentCode");
        }

        long materialsInUse = materialTypeRepository
                .countActiveByFactoryIdAndSegmentPrefix(factoryId, entity.getSegmentCode());
        if (materialsInUse > 0) {
            throw new BusinessException(409,
                    "有 " + materialsInUse + " 个在用物料的编码挂在该分类下（编码 "
                            + entity.getSegmentCode() + " 开头）")
                    .withHint("删除后这些物料的分类将无法追溯。若只是不想再用它建新物料，请改用「停用」——"
                            + "停用后该分类不再出现在新建物料的选项里，但历史物料的归属仍在")
                    .withHintTarget("segmentCode");
        }

        entity.setDeletedAt(LocalDateTime.now());
        repo.save(entity);
        log.info("SP8: 软删除物料编码段 factoryId={} id={} code={}", factoryId, id, entity.getSegmentCode());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaterialCodeSegmentDTO> listDeleted(String factoryId) {
        return repo.findDeletedByFactoryId(factoryId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 恢复一条被软删的分类。
     *
     * <p>🔴 为什么必须有这个入口: 分类是软删除, 但界面上**看不到也回不来** ——
     * 于是「误删 / 想重组」的唯一出路是新建, 而新建又会撞上被删行占着的编码。
     * 客户 2026-08-04 删掉的 226 条 L3 其实原封不动躺在库里, 恢复比重建正确得多
     * (编码不变 → 历史物料的 16 位码仍然指得回它的分类)。</p>
     */
    @Override
    @Transactional
    public MaterialCodeSegmentDTO restore(String factoryId, Long id) {
        MaterialCodeSegment entity = repo.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("物料编码段不存在: id=" + id));
        if (!factoryId.equals(entity.getFactoryId())) {
            throw new BusinessException(403, "无权限操作此编码段");
        }
        if (entity.getDeletedAt() == null) {
            throw new BusinessException(400, "该分类未被删除，无需恢复").withHintTarget("id");
        }
        // 父级必须还在 —— 否则恢复出来的是一条挂空的分类, 树里根本渲染不出。
        if (entity.getLevel() != null && entity.getLevel() > 1) {
            MaterialCodeSegment parent = repo
                    .findByFactoryIdAndSegmentCode(factoryId, entity.getParentCode()).orElse(null);
            if (parent == null) {
                throw new BusinessException(409,
                        "上级分类 " + entity.getParentCode() + " 已被删除，请先恢复上级")
                        .withHint("恢复顺序: 先 L1，再 L2，最后 L3")
                        .withHintTarget("parentCode");
            }
        }
        // 名字在这期间可能被别人用掉了 —— 恢复不能制造出两个同名兄弟。
        rejectDuplicateLabel(factoryId, entity.getLevel(), entity.getParentCode(),
                normalizeLabel(entity.getSegmentLabel()), entity.getId());

        repo.restoreById(id);
        log.info("SP8: 恢复物料编码段 factoryId={} id={} code={}", factoryId, id, entity.getSegmentCode());
        entity.setDeletedAt(null);
        return toDTO(entity);
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

    private void validateHierarchy(String factoryId, short level, String code, String parentCode) {
        int expectedLength = level == 1 ? 3 : level == 2 ? 6 : level == 3 ? 10 : -1;
        if (expectedLength < 0 || code == null || !code.matches("[0-9]{" + expectedLength + "}")) {
            throw new BusinessException(400, "分段编码层级与长度不匹配")
                    .withHintTarget("segmentCode");
        }
        if (level == 1) {
            if (parentCode != null && !parentCode.isBlank()) {
                throw new BusinessException(400, "L1节点不能配置父编码").withHintTarget("parentCode");
            }
            return;
        }
        if (parentCode == null || parentCode.isBlank()) {
            throw new BusinessException(400, "L2/L3节点必须选择直属父编码")
                    .withHintTarget("parentCode");
        }
        MaterialCodeSegment parent = repo.findByFactoryIdAndSegmentCode(factoryId, parentCode)
                .orElseThrow(() -> new ResourceNotFoundException("父级编码 " + parentCode + " 不存在"));
        if (parent.getLevel() == null || parent.getLevel() != level - 1
                || !Boolean.TRUE.equals(parent.getIsActive()) || !code.startsWith(parentCode)) {
            throw new BusinessException(400, "父节点层级、状态或编码前缀无效")
                    .withHint("请选择启用的直属父节点")
                    .withHintTarget("parentCode");
        }
    }

    private String requireLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空").withHintTarget("segmentLabel");
        }
        return label.trim();
    }

    private String normalizeLabel(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void rejectDuplicateLabel(
            String factoryId, short level, String parentCode, String normalizedLabel, Long excludeId) {
        if (repo.existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
                factoryId, level, parentCode, normalizedLabel,
                excludeId != null ? excludeId : -1L)) {
            throw duplicateLabel(normalizedLabel);
        }

        // V20261028_92 deliberately leaves normalizedLabel NULL on every member of
        // a historical collision instead of choosing a winner or rewriting master
        // data.  Those legacy rows must still block a new duplicate, so compare the
        // source labels with the same Java/NFKC contract used for new writes.
        List<MaterialCodeSegment> siblings = level == 1
                ? repo.findByFactoryIdAndLevelOrderBySortOrderAscSegmentCodeAsc(factoryId, level)
                : repo.findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(factoryId, parentCode);
        boolean legacyConflict = siblings.stream()
                .filter(segment -> segment.getDeletedAt() == null)
                .filter(segment -> segment.getLevel() != null && segment.getLevel() == level)
                .filter(segment -> excludeId == null || !excludeId.equals(segment.getId()))
                .map(MaterialCodeSegment::getSegmentLabel)
                .filter(label -> label != null && !label.isBlank())
                .map(this::normalizeLabel)
                .anyMatch(normalizedLabel::equals);
        if (legacyConflict) {
            throw duplicateLabel(normalizedLabel);
        }
    }

    private BusinessException duplicateLabel(String label) {
        return new BusinessException(409, "同一父级下已存在同名分类: " + label)
                .withHint("请选择现有共享分类，或使用语义不同的分类名称")
                .withHintTarget("segmentLabel");
    }

    /**
     * 编码已被占用 —— 把「被谁占的」说出来。
     *
     * 🔴 最常见的一种是**被一条已删除的分类占着**: 分类删除是软删除, 但编码必须继续
     * 保留(有外键指向它), 所以那个编码永远不会回到可用池。用户在界面上看不到那一行,
     * 只会觉得「系统抽风」。所以这里要点名是哪一条, 并给出真正可行的下一步。
     */
    private BusinessException segmentCodeTaken(String factoryId, String segmentCode) {
        String owner = repo.findLabelBySegmentCodeIncludingDeleted(factoryId, segmentCode).orElse(null);
        String message = owner == null
                ? "编码 " + segmentCode + " 在该工厂已被占用"
                : "编码 " + segmentCode + " 已被分类「" + owner + "」占用（可能是一条已删除的分类，删除后编码仍保留）";
        return new BusinessException(409, message)
                .withHint("请刷新页面重取系统编码；系统会跳过已删除分类占用的编码")
                .withHintTarget("segmentCode");
    }

    /** 按真正冲突的那个唯一约束给文案, 别一律说成「重名」。 */
    private BusinessException describeConflict(
            DataIntegrityViolationException conflict, String factoryId, String segmentCode, String label) {
        String detail = rootMessage(conflict);
        if (detail.contains("uk_mcs_factory_segment")) {
            return segmentCodeTaken(factoryId, segmentCode);
        }
        return duplicateLabel(label);
    }

    private String rootMessage(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) text.append(current.getMessage()).append('\n');
            if (current.getCause() == current) break;
        }
        return text.toString();
    }

    /**
     * 分配某父级下一个**真正可用**的子编码。
     *
     * ⛔ 这件事以前在**前端**做(`nextL3Suffix()` 对下拉里活着的子节点取 max+1),
     * 而前端拿不到、也不该拿到软删除的行 —— 六膳门把整个 L2 连同 30 个 L3 全删掉后,
     * 下拉是空的 → 算出 0001 → 撞上软删行占着的 0010010001。
     * 分配口径必须和唯一约束口径一致, 所以只能在服务端做。
     */
    @Override
    @Transactional(readOnly = true)
    public String nextSegmentCode(String factoryId, short level, String parentCode) {
        int suffixLength = level == 1 ? 3 : level == 2 ? 3 : level == 3 ? 4 : -1;
        if (suffixLength < 0) {
            throw new BusinessException(400, "层级无效: " + level).withHintTarget("level");
        }
        if (level > 1 && (parentCode == null || parentCode.isBlank())) {
            throw new BusinessException(400, "L2/L3 取编码必须指定父编码").withHintTarget("parentCode");
        }
        String prefix = level == 1 ? "" : parentCode.trim();
        Set<String> taken = new HashSet<>(
                repo.findSegmentCodesByParentIncludingDeleted(factoryId, level == 1 ? null : prefix));

        int max = (int) Math.pow(10, suffixLength) - 1;
        for (int candidate = 1; candidate <= max; candidate++) {
            String code = prefix + String.format("%0" + suffixLength + "d", candidate);
            // 同一个编码理论上只可能挂在同一个父级下(编码自带父前缀), 但保险起见按全工厂查一次:
            // 历史数据里出现过父级被改而编码没跟着改的行。
            if (!taken.contains(code) && !repo.existsBySegmentCodeIncludingDeleted(factoryId, code)) {
                return code;
            }
        }
        throw new BusinessException(409, "该父级下的编码位已用尽（" + max + " 个）")
                .withHint("请新建一个上级分类，或联系管理员清理历史编码")
                .withHintTarget("segmentCode");
    }
}
