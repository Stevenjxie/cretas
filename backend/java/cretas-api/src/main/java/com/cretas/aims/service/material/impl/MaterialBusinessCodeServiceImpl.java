package com.cretas.aims.service.material.impl;

import com.cretas.aims.entity.material.MaterialBusinessCodeCounter;
import com.cretas.aims.entity.material.MaterialBusinessCodePrefix;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialBusinessCodeCounterRepository;
import com.cretas.aims.repository.material.MaterialBusinessCodePrefixRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.material.MaterialBusinessCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MaterialBusinessCodeServiceImpl implements MaterialBusinessCodeService {

    static final int MAX_BUSINESS_CODE_LENGTH = 14;
    private static final String SEGMENT_PATTERN = "^(?:[0-9]{3}|[0-9]{6}|[0-9]{10})$";
    private static final String PREFIX_PATTERN = "^[A-Z0-9]{2,8}$";
    private static final String BUSINESS_CODE_PATTERN = "^[A-Z0-9]+$";

    private final MaterialBusinessCodePrefixRepository prefixRepository;
    private final MaterialBusinessCodeCounterRepository counterRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialCodeSegmentRepository segmentRepository;

    @Override
    @Transactional(readOnly = true)
    public BusinessCodePreview previewBusinessCode(
            String factoryId, String classificationSegmentCode) {
        String safeFactoryId = requireNonBlank(factoryId, "factoryId");
        String safeSegmentCode = validateClassificationLeaf(
                safeFactoryId, classificationSegmentCode, false);
        ResolvedPrefix resolved = resolvePrefix(safeFactoryId, safeSegmentCode, false);
        long next = counterRepository
                .findByFactoryIdAndCodePrefix(safeFactoryId, resolved.codePrefix())
                .map(MaterialBusinessCodeCounter::getLastAllocated)
                .map(last -> last + 1L)
                .orElse(1L);
        String candidate = findAvailableCandidate(
                safeFactoryId, resolved.codePrefix(), resolved.sequenceLength(), next);
        return new BusinessCodePreview(candidate, resolved.codePrefix(),
                resolved.source(), resolved.sourceSegmentCode());
    }

    @Override
    @Transactional
    public String allocateBusinessCode(String factoryId, String classificationSegmentCode) {
        String safeFactoryId = requireNonBlank(factoryId, "factoryId");
        // Lock the immutable L3 identity first. It serializes first-time fallback-prefix creation
        // without relying on an absent prefix row lock.
        String safeSegmentCode = validateClassificationLeaf(
                safeFactoryId, classificationSegmentCode, true);

        ResolvedPrefix resolved = resolvePrefix(safeFactoryId, safeSegmentCode, true);
        String prefix = resolved.codePrefix();
        int sequenceLength = resolved.sequenceLength();

        MaterialBusinessCodeCounter counter = counterRepository
                .lockByFactoryIdAndCodePrefix(safeFactoryId, prefix)
                .orElseGet(() -> MaterialBusinessCodeCounter.builder()
                        .factoryId(safeFactoryId)
                        .codePrefix(prefix)
                        .lastAllocated(0L)
                        .build());

        long next = counter.getLastAllocated() == null ? 1L : counter.getLastAllocated() + 1L;
        String candidate = findAvailableCandidate(safeFactoryId, prefix, sequenceLength, next);
        long allocated = Long.parseLong(candidate.substring(prefix.length()));
        counter.setLastAllocated(allocated);
        counterRepository.saveAndFlush(counter);
        return candidate;
    }

    private String validateClassificationLeaf(
            String factoryId, String classificationSegmentCode, boolean lock) {
        String safeSegmentCode = requireNonBlank(
                classificationSegmentCode, "classificationSegmentCode");
        if (!safeSegmentCode.matches(SEGMENT_PATTERN)) {
            throw invalidConfig("分类段编码必须为3、6或10位数字");
        }
        MaterialCodeSegment segment = (lock
                ? segmentRepository.lockByFactoryIdAndSegmentCode(factoryId, safeSegmentCode)
                : segmentRepository.findByFactoryIdAndSegmentCode(factoryId, safeSegmentCode))
                .orElseThrow(() -> new BusinessException(400, "所选物料分类不存在或不属于当前工厂")
                        .withCode("MATERIAL_CLASSIFICATION_NOT_AVAILABLE")
                        .withHint("请重新选择当前工厂中启用的 L3 小类")
                        .withHintTarget("segmentCode"));
        if (segment.getLevel() == null || segment.getLevel() != 3
                || !Boolean.TRUE.equals(segment.getIsActive())) {
            throw new BusinessException(400, "所选物料分类不是可用的 L3 小类")
                    .withCode("MATERIAL_CLASSIFICATION_NOT_AVAILABLE")
                    .withHint("已停用或非叶子分类不能用于新建原料")
                    .withHintTarget("segmentCode");
        }
        return safeSegmentCode;
    }

    private ResolvedPrefix resolvePrefix(
            String factoryId, String classificationSegmentCode, boolean persistFallback) {
        List<MaterialBusinessCodePrefix> matchingPrefixes = persistFallback
                ? prefixRepository.lockMatchingPrefixes(factoryId, classificationSegmentCode)
                : prefixRepository.findMatchingPrefixes(factoryId, classificationSegmentCode);
        if (!matchingPrefixes.isEmpty()) {
            MaterialBusinessCodePrefix configured = matchingPrefixes.get(0);
            return new ResolvedPrefix(validatePrefixConfig(configured),
                    configured.getSequenceLength(), "CONFIGURED",
                    configured.getClassificationSegmentCode());
        }

        // Stable fallback: encode the complete immutable 10-digit L3 identity in base36. This is
        // injective for the supported range, does not depend on mutable labels, is <= 8 chars with
        // the leading M, and therefore cannot arbitrarily collide between generated L3 prefixes.
        String derivedPrefix = deriveStablePrefix(classificationSegmentCode);
        validateDerivedPrefixAvailability(factoryId, classificationSegmentCode, derivedPrefix);
        if (persistFallback) {
            MaterialBusinessCodePrefix generated = MaterialBusinessCodePrefix.builder()
                    .factoryId(factoryId)
                    .classificationSegmentCode(classificationSegmentCode)
                    .codePrefix(derivedPrefix)
                    .sequenceLength(MaterialBusinessCodePrefix.DEFAULT_SEQUENCE_LENGTH)
                    .isActive(true)
                    .build();
            prefixRepository.saveAndFlush(generated);
        }
        return new ResolvedPrefix(derivedPrefix,
                MaterialBusinessCodePrefix.DEFAULT_SEQUENCE_LENGTH,
                "SYSTEM_STABLE", classificationSegmentCode);
    }

    private String deriveStablePrefix(String classificationSegmentCode) {
        long numericIdentity;
        try {
            numericIdentity = Long.parseLong(classificationSegmentCode);
        } catch (NumberFormatException ex) {
            throw invalidConfig("分类段编码无法生成稳定业务编码前缀");
        }
        String prefix = "M" + Long.toString(numericIdentity, 36).toUpperCase(Locale.ROOT);
        if (!prefix.matches(PREFIX_PATTERN)) {
            throw invalidConfig("分类段编码生成的稳定业务前缀不符合长度或字符契约");
        }
        return prefix;
    }

    private void validateDerivedPrefixAvailability(
            String factoryId, String classificationSegmentCode, String derivedPrefix) {
        prefixRepository.findByFactoryIdAndClassificationSegmentCode(factoryId, classificationSegmentCode)
                // Active exact matches have already been returned by resolvePrefix. Reaching this
                // branch means the exact record is inactive. Never reactivate or duplicate it as a
                // side effect of material creation; a master-data administrator must decide whether
                // the retired contract can be restored.
                .ifPresent(existing -> {
                    throw prefixConflict(classificationSegmentCode);
                });
        if (prefixRepository.existsByFactoryIdAndCodePrefixIgnoreCase(factoryId, derivedPrefix)
                && prefixRepository.findByFactoryIdAndClassificationSegmentCode(
                        factoryId, classificationSegmentCode).isEmpty()) {
            throw prefixConflict(classificationSegmentCode);
        }
    }

    private BusinessException prefixConflict(String classificationSegmentCode) {
        return new BusinessException(409, "所选分类的稳定业务编码前缀与现有配置冲突")
                .withCode("MATERIAL_BUSINESS_CODE_PREFIX_CONFLICT")
                .withHint("请由主数据管理员核对分类 " + classificationSegmentCode
                        + " 的前缀配置；系统不会猜测或覆盖历史编码")
                .withHintTarget("segmentCode");
    }

    private String findAvailableCandidate(
            String factoryId, String prefix, int sequenceLength, long firstCandidate) {
        long next = firstCandidate;
        while (next <= MaterialBusinessCodeCounter.MAX_SEQUENCE) {
            String candidate = prefix + String.format(
                    Locale.ROOT, "%0" + sequenceLength + "d", next);
            validateGeneratedCode(candidate);
            if (!materialTypeRepository.existsByFactoryIdAndBusinessCodeIgnoreCase(
                    factoryId, candidate)) {
                return candidate;
            }
            next++;
        }
        throw new BusinessException(409, "该物料业务编码前缀的6位序列已用尽")
                .withCode("MATERIAL_BUSINESS_CODE_EXHAUSTED")
                .withHint("请新增受控分类前缀，不能重置或复用历史业务编码");
    }

    private String validatePrefixConfig(MaterialBusinessCodePrefix config) {
        if (config.getSequenceLength() == null
                || config.getSequenceLength() != MaterialBusinessCodePrefix.DEFAULT_SEQUENCE_LENGTH) {
            throw invalidConfig("业务编码序列必须固定为6位");
        }
        String prefix = requireNonBlank(config.getCodePrefix(), "codePrefix");
        if (!prefix.matches(PREFIX_PATTERN)) {
            throw invalidConfig("业务编码前缀只能包含2至8位大写英文字母和数字");
        }
        if (prefix.length() + config.getSequenceLength() > MAX_BUSINESS_CODE_LENGTH) {
            throw invalidConfig("业务编码总长度不能超过14位");
        }
        return prefix;
    }

    private void validateGeneratedCode(String code) {
        if (!code.matches(BUSINESS_CODE_PATTERN) || code.length() > MAX_BUSINESS_CODE_LENGTH) {
            throw invalidConfig("生成的业务编码不符合大写字母加数字且不含分隔符的契约");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidConfig(field + "不能为空");
        }
        return value.trim();
    }

    private static BusinessException invalidConfig(String message) {
        return new BusinessException(400, message).withCode("MATERIAL_BUSINESS_CODE_CONFIG_INVALID");
    }

    private record ResolvedPrefix(String codePrefix,
                                  int sequenceLength,
                                  String source,
                                  String sourceSegmentCode) {
    }
}
