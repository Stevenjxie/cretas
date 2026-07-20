package com.cretas.aims.service.material.impl;

import com.cretas.aims.entity.material.MaterialBusinessCodeCounter;
import com.cretas.aims.entity.material.MaterialBusinessCodePrefix;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialBusinessCodeCounterRepository;
import com.cretas.aims.repository.material.MaterialBusinessCodePrefixRepository;
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

    @Override
    @Transactional
    public String allocateBusinessCode(String factoryId, String classificationSegmentCode) {
        String safeFactoryId = requireNonBlank(factoryId, "factoryId");
        String safeSegmentCode = requireNonBlank(classificationSegmentCode, "classificationSegmentCode");
        if (!safeSegmentCode.matches(SEGMENT_PATTERN)) {
            throw invalidConfig("分类段编码必须为3、6或10位数字");
        }

        List<MaterialBusinessCodePrefix> matchingPrefixes =
                prefixRepository.lockMatchingPrefixes(safeFactoryId, safeSegmentCode);
        if (matchingPrefixes.isEmpty()) {
            throw new BusinessException(409, "当前物料分类尚未配置业务编码前缀")
                    .withCode("MATERIAL_BUSINESS_CODE_PREFIX_REQUIRED")
                    .withHint("请先由主数据管理员为该分类配置业务编码前缀");
        }

        MaterialBusinessCodePrefix prefixConfig = matchingPrefixes.get(0);
        String prefix = validatePrefixConfig(prefixConfig);
        int sequenceLength = prefixConfig.getSequenceLength();

        MaterialBusinessCodeCounter counter = counterRepository
                .lockByFactoryIdAndCodePrefix(safeFactoryId, prefix)
                .orElseGet(() -> MaterialBusinessCodeCounter.builder()
                        .factoryId(safeFactoryId)
                        .codePrefix(prefix)
                        .lastAllocated(0L)
                        .build());

        long next = counter.getLastAllocated() == null ? 1L : counter.getLastAllocated() + 1L;
        while (next <= MaterialBusinessCodeCounter.MAX_SEQUENCE) {
            String candidate = prefix + String.format(Locale.ROOT, "%0" + sequenceLength + "d", next);
            validateGeneratedCode(candidate);
            if (!materialTypeRepository.existsByFactoryIdAndBusinessCodeIgnoreCase(safeFactoryId, candidate)) {
                counter.setLastAllocated(next);
                counterRepository.saveAndFlush(counter);
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
}
