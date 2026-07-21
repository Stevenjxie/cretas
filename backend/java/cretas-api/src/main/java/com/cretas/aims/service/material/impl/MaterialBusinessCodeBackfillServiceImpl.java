package com.cretas.aims.service.material.impl;

import com.cretas.aims.dto.material.MaterialBusinessCodeBackfillReportDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.material.MaterialBusinessCodeBackfillService;
import com.cretas.aims.service.material.MaterialBusinessCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialBusinessCodeBackfillServiceImpl
        implements MaterialBusinessCodeBackfillService {

    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("^[0-9]{16}$");

    private final RawMaterialTypeRepository materialRepository;
    private final MaterialCodeSegmentRepository segmentRepository;
    private final MaterialBusinessCodeService businessCodeService;

    @Override
    @Transactional(readOnly = true)
    public MaterialBusinessCodeBackfillReportDTO preview(String factoryId) {
        String safeFactoryId = requireNonBlank(factoryId, "factoryId");
        List<RawMaterialType> materials = sorted(materialRepository.findByFactoryId(safeFactoryId));
        return buildReport(safeFactoryId, materials, true);
    }

    @Override
    @Transactional
    public MaterialBusinessCodeBackfillReportDTO backfill(
            String factoryId, String idempotencyKey) {
        String safeFactoryId = requireNonBlank(factoryId, "factoryId");
        String safeIdempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");

        // Lock all material rows for this factory. Concurrent replays wait, then observe the codes
        // assigned by the first transaction and become a no-op instead of consuming new numbers.
        List<RawMaterialType> materials = materialRepository
                .lockByFactoryIdForBusinessCodeBackfill(safeFactoryId);
        MaterialBusinessCodeBackfillReportDTO report = buildReport(
                safeFactoryId, sorted(materials), false);
        log.info("Historical material business-code backfill completed: factoryId={}, "
                        + "idempotencyKey={}, total={}, mapped={}, skipped={}, alreadyMapped={}",
                safeFactoryId, safeIdempotencyKey, report.getTotal(), report.getMapped(),
                report.getSkipped(), report.getAlreadyMapped());
        return report;
    }

    private MaterialBusinessCodeBackfillReportDTO buildReport(
            String factoryId, List<RawMaterialType> materials, boolean dryRun) {
        Set<String> activeL3 = new HashSet<>();
        for (MaterialCodeSegment segment :
                segmentRepository.findByFactoryIdAndLevelOrderBySortOrderAscSegmentCodeAsc(
                        factoryId, (short) 3)) {
            if (Boolean.TRUE.equals(segment.getIsActive())) {
                activeL3.add(segment.getSegmentCode());
            }
        }

        List<MaterialBusinessCodeBackfillReportDTO.Item> items = new ArrayList<>();
        Map<String, Long> nextSequenceByPrefix = new HashMap<>();
        Set<String> reservedPreviewCodes = new HashSet<>();
        int alreadyMapped = 0;
        int eligible = 0;
        int mapped = 0;
        int skipped = 0;

        for (RawMaterialType material : materials) {
            String legacyCode = material.getCode();
            if (hasText(material.getBusinessCode())) {
                alreadyMapped++;
                items.add(item(material, legacyCode, extractL3(legacyCode),
                        material.getBusinessCode(), material.getBusinessCode(),
                        "ALREADY_MAPPED", null, null));
                continue;
            }
            if (legacyCode == null || !LEGACY_CODE_PATTERN.matcher(legacyCode).matches()) {
                skipped++;
                items.add(item(material, legacyCode, null, null, legacyCode,
                        "INVALID_LEGACY_CODE", "旧编码不是16位数字，不能推导L3", null));
                continue;
            }

            String l3Code = extractL3(legacyCode);
            if (!activeL3.contains(l3Code)) {
                skipped++;
                items.add(item(material, legacyCode, l3Code, null, legacyCode,
                        "MISSING_ACTIVE_L3", "当前工厂不存在对应的启用L3分类，已安全跳过", null));
                continue;
            }

            eligible++;
            if (dryRun) {
                MaterialBusinessCodeService.BusinessCodePreview preview =
                        businessCodeService.previewBusinessCode(factoryId, l3Code);
                String proposed = reserveUniquePreview(
                        factoryId, preview, nextSequenceByPrefix, reservedPreviewCodes);
                items.add(item(material, legacyCode, l3Code, proposed, proposed,
                        "READY", null, preview.prefixSource()));
                continue;
            }

            String assigned = businessCodeService.allocateBusinessCode(factoryId, l3Code);
            int updated = materialRepository.assignBusinessCodeIfMissing(
                    factoryId, material.getId(), assigned);
            if (updated != 1) {
                throw new BusinessException(409, "历史物料业务编码在回填期间已发生变化")
                        .withCode("MATERIAL_BUSINESS_CODE_BACKFILL_CONFLICT")
                        .withHint("请重新执行只读预览后再确认，系统未覆盖任何既有业务编码")
                        .withHintTarget("businessCode");
            }
            mapped++;
            items.add(item(material, legacyCode, l3Code, assigned, assigned,
                    "MAPPED", null, null));
        }

        return MaterialBusinessCodeBackfillReportDTO.builder()
                .factoryId(factoryId)
                .dryRun(dryRun)
                .total(materials.size())
                .alreadyMapped(alreadyMapped)
                .eligible(eligible)
                .mapped(mapped)
                .skipped(skipped)
                .items(items)
                .build();
    }

    private String reserveUniquePreview(
            String factoryId,
            MaterialBusinessCodeService.BusinessCodePreview preview,
            Map<String, Long> nextSequenceByPrefix,
            Set<String> reservedPreviewCodes) {
        String prefix = preview.codePrefix();
        int sequenceLength = preview.code().length() - prefix.length();
        long firstAvailable = parseSequence(preview.code(), prefix);
        long candidateSequence = Math.max(
                firstAvailable, nextSequenceByPrefix.getOrDefault(prefix, firstAvailable));

        while (candidateSequence <= 999999L) {
            String candidate = prefix + String.format(
                    Locale.ROOT, "%0" + sequenceLength + "d", candidateSequence);
            if (!reservedPreviewCodes.contains(candidate)
                    && !materialRepository.existsByFactoryIdAndBusinessCodeIgnoreCase(
                            factoryId, candidate)) {
                reservedPreviewCodes.add(candidate);
                nextSequenceByPrefix.put(prefix, candidateSequence + 1L);
                return candidate;
            }
            candidateSequence++;
        }
        throw new BusinessException(409, "该物料业务编码前缀的6位序列已用尽")
                .withCode("MATERIAL_BUSINESS_CODE_EXHAUSTED");
    }

    private long parseSequence(String code, String prefix) {
        try {
            return Long.parseLong(code.substring(prefix.length()));
        } catch (RuntimeException ex) {
            throw new BusinessException(500, "业务编码预览结果不符合前缀与序列契约")
                    .withCode("MATERIAL_BUSINESS_CODE_PREVIEW_INVALID");
        }
    }

    private MaterialBusinessCodeBackfillReportDTO.Item item(
            RawMaterialType material,
            String legacyCode,
            String l3Code,
            String businessCode,
            String displayCode,
            String status,
            String reason,
            String prefixSource) {
        return MaterialBusinessCodeBackfillReportDTO.Item.builder()
                .materialId(material.getId())
                .legacyClassificationCode(legacyCode)
                .l3SegmentCode(l3Code)
                .businessCode(businessCode)
                .displayCode(displayCode)
                .status(status)
                .reason(reason)
                .prefixSource(prefixSource)
                .build();
    }

    private List<RawMaterialType> sorted(List<RawMaterialType> materials) {
        return materials.stream()
                .sorted(Comparator.comparing(
                                RawMaterialType::getCode,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(RawMaterialType::getId))
                .toList();
    }

    private String extractL3(String legacyCode) {
        return legacyCode != null && legacyCode.length() >= 10
                ? legacyCode.substring(0, 10)
                : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, field + "不能为空")
                    .withCode("MATERIAL_BUSINESS_CODE_BACKFILL_INVALID_REQUEST");
        }
        return value.trim();
    }
}
