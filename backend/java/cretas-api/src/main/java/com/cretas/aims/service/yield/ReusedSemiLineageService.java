package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ①d 复用半成品前段出成率/血缘拼接 (READ-ONLY 派生, 结算不写)。
 *
 * <p><b>问题</b> (客户 07-01 原话): 「最终结算时…因为我选的是这个批次的半成品, 所以前面的数据是有的,
 * 那我就还按前面的数据再接上我后面的数据算」。当计划 P2 复用某更早计划产出的半成品(SFI)批次 X 起步
 * (如从"滚揉"开始), P2 的出成率若只以 P2 自身原料投入为分母, 会虚高/不完整 —— 缺了 X 的前段原料投入。
 *
 * <p><b>本服务</b>: 从 P2 的 process_sheet_rows 里读出所有 {@code semiFinished=true} 的上游投料
 * (被复用的外部 SFI 批次 + 领用量), 对每个批次 READ-ONLY 反查其前段原料投入重, 按领用比例接续,
 * 供 {@link ProductionSummaryService} 加进出成率分母 + 输出血缘。<b>不改任何结算/库存写入路径。</b>
 *
 * <h3>前段原料反查 (READ-ONLY)</h3>
 * <ol>
 *   <li>SFI 行 X (by intermediateBatchNo): 拿 producedQuantity(该批次自身总产出)。</li>
 *   <li>解析 X 的来源计划 P1:
 *     <ul>
 *       <li>X.batchId != null → ProductionBatch → productionPlanId (RN/任务路径 SFI);</li>
 *       <li>否则 X = 小结锚 {@code CLK-SEMI-{planId8}-{productTypeId8}} → 按 planId8 前缀唯一命中计划
 *           (F006 文员小结路径, 锚不带 batchId)。多命中/无命中 = 歧义 → 诚实不接。</li>
 *     </ul>
 *   </li>
 *   <li>P1 前段原料 = P1 自身首道原料投入 (与 {@link ProductionSummaryService#sumFirstProcessRawInput}
 *       同口径, 本身已诚实-null 感知)。</li>
 *   <li>接续量 = P1前段原料 × (领用量 / X.producedQuantity)  ←按比例 (只领 40% 只接 40% 前段)。</li>
 * </ol>
 *
 * <p><b>🔴 诚实 null (禁止降级, 绝不显错数)</b>: SFI 行缺失 / X.producedQuantity≤0 / 来源计划不可解析 /
 * P1前段原料未录(≤0) → 该批次前段<b>不计入分母</b> + note 点名批号 (frontRawIncluded=false, frontRawInput=null)。
 * 宁可分母偏小(出成率偏保守) + 明确告知, 不伪造前段数。
 *
 * <p><b>范围限制 (一层)</b>: 只接 X 的直接来源计划 P1 的自身原料。若 P1 本身也复用了更上游半成品,
 * 那更深一层的前段未在此递归接入 (P1 的 totalRawInput 只含 P1 自身首道原料)。窄拓扑, 文档化;
 * 需要全链递归时可在此扩展 (注意环)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReusedSemiLineageService {

    private static final int RAW_SCALE = 4;

    private final ProcessSheetRowRepository rowRepository;
    private final SemiFinishedInventoryRepository wipRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProcessSheetService processSheetService;
    private final ObjectMapper objectMapper;

    /** 复用半成品前段接续结果 (供 summary 消费)。 */
    @Data
    @Builder
    public static class ReusedFrontLineage {
        /** Σ 已计入分母的前段原料重(kg); 无复用/全缺失 → 0。 */
        private BigDecimal totalIncludedFrontRaw;
        /** 每个被复用外部 SFI 批次的血缘明细。 */
        private List<ProductionSummaryDTO.ReusedSemiLineage> lineages;
        /** 是否存在前段 provenance 缺失的批次 (分母未含其前段)。 */
        private boolean hasMissingProvenance;
        /** 缺失批次点名 note (无缺失 → null)。 */
        private String note;

        public static ReusedFrontLineage empty() {
            return ReusedFrontLineage.builder()
                    .totalIncludedFrontRaw(BigDecimal.ZERO)
                    .lineages(new ArrayList<>())
                    .hasMissingProvenance(false)
                    .note(null)
                    .build();
        }
    }

    /**
     * 解析计划 planId 复用的所有外部半成品批次的前段接续 + 血缘。
     *
     * @return 永不 null; 无复用 → {@link ReusedFrontLineage#empty()}。
     */
    public ReusedFrontLineage resolve(String factoryId, String planId) {
        // 1. 聚合本计划所有 semiFinished=true 上游投料: sourceBatchNumber → Σ feedQuantityKg
        Map<String, BigDecimal> drawnBySource = collectReusedSemiFeeds(factoryId, planId);
        if (drawnBySource.isEmpty()) {
            return ReusedFrontLineage.empty();
        }

        BigDecimal totalIncluded = BigDecimal.ZERO;
        List<ProductionSummaryDTO.ReusedSemiLineage> lineages = new ArrayList<>();
        List<String> missingBatches = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> e : drawnBySource.entrySet()) {
            String sourceBatchNumber = e.getKey();
            BigDecimal drawn = nz(e.getValue());

            SemiFinishedInventory sfi = wipRepository
                    .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, sourceBatchNumber)
                    .orElse(null);
            BigDecimal sourceProduced = sfi == null ? null : sfi.getProducedQuantity();
            String sourcePlanId = sfi == null ? null : resolveSourcePlanId(factoryId, sfi, planId);

            BigDecimal drawnRatio = null;
            if (sourceProduced != null && sourceProduced.signum() > 0) {
                drawnRatio = drawn.multiply(BigDecimal.valueOf(100))
                        .divide(sourceProduced, RAW_SCALE, RoundingMode.HALF_UP);
            }

            // 前段原料反查
            BigDecimal frontRaw = null;
            String note = null;
            if (sfi == null) {
                note = "半成品库存行缺失, 无法反查前段";
            } else if (sourceProduced == null || sourceProduced.signum() <= 0) {
                note = "该批次自身产出量缺失, 无法按比例折算前段";
            } else if (sourcePlanId == null) {
                note = "来源计划不可解析(锚前缀歧义/无 batchId), 前段未接入";
            } else {
                BigDecimal sourcePlanRaw = resolvePlanFrontRaw(factoryId, sourcePlanId);
                if (sourcePlanRaw == null || sourcePlanRaw.signum() <= 0) {
                    note = "来源计划前段原料投入未录, 前段未接入";
                } else {
                    // 按领用比例接续: 只领 X 的一部分只接对应比例的前段
                    frontRaw = sourcePlanRaw.multiply(drawn)
                            .divide(sourceProduced, RAW_SCALE, RoundingMode.HALF_UP);
                }
            }

            boolean included = frontRaw != null && frontRaw.signum() > 0;
            if (included) {
                totalIncluded = totalIncluded.add(frontRaw);
            } else {
                missingBatches.add(sourceBatchNumber);
            }

            lineages.add(ProductionSummaryDTO.ReusedSemiLineage.builder()
                    .sourceBatchNumber(sourceBatchNumber)
                    .drawnQuantity(drawn)
                    .sourceProducedQuantity(sourceProduced)
                    .drawnRatio(drawnRatio)
                    .sourcePlanId(sourcePlanId)
                    .frontRawInput(included ? frontRaw : null)
                    .frontRawIncluded(included)
                    .note(included ? null : note)
                    .build());
        }

        String aggregateNote = missingBatches.isEmpty() ? null
                : "复用批次 " + String.join(", ", missingBatches)
                        + " 前段数据缺失，出成率未含其前段";
        return ReusedFrontLineage.builder()
                .totalIncludedFrontRaw(totalIncluded)
                .lineages(lineages)
                .hasMissingProvenance(!missingBatches.isEmpty())
                .note(aggregateNote)
                .build();
    }

    /**
     * 从本计划 process_sheet_rows 读出所有 {@code semiFinished=true} 上游投料, 按来源批号 Σ 领用量。
     * SFI 锚批号(CLK-SEMI-)天然不是本计划在制行的 batchNumber, 故必为外部复用。
     */
    private Map<String, BigDecimal> collectReusedSemiFeeds(String factoryId, String planId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        List<ProcessSheetRow> rows = rowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (rows == null) {
            return out;
        }
        for (ProcessSheetRow row : rows) {
            ProcessSheetRowRequest req = parsePayload(row.getRowPayload());
            if (req == null || req.getUpstreamSources() == null) {
                continue;
            }
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if (!ref.isSemiFinished()) {
                    continue; // in-plan 在制 WIP 投料由既有 provenance 链处理, 非跨计划复用
                }
                String src = ref.getSourceBatchNumber();
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (src == null || feed.signum() <= 0) {
                    continue;
                }
                out.merge(src, feed, BigDecimal::add);
            }
        }
        return out;
    }

    /** 解析 SFI 批次 X 的来源计划 id; 不可解析 (歧义/无链/自指) → null (诚实不接)。 */
    private String resolveSourcePlanId(String factoryId, SemiFinishedInventory sfi, String consumingPlanId) {
        // (a) 任务/RN 路径: SFI 带 batchId → ProductionBatch → productionPlanId
        if (sfi.getBatchId() != null) {
            ProductionBatch batch = productionBatchRepository
                    .findByIdAndFactoryId(sfi.getBatchId(), factoryId).orElse(null);
            String pid = batch == null ? null : batch.getProductionPlanId();
            return sameOrNull(pid, consumingPlanId);
        }
        // (b) 文员小结锚: CLK-SEMI-{planId8}-{productTypeId8} → 按 planId8 前缀唯一命中
        String prefix = anchorPlanPrefix(sfi.getIntermediateBatchNo());
        if (prefix == null) {
            return null;
        }
        List<ProductionPlan> plans = productionPlanRepository
                .findByFactoryIdAndIdStartingWith(factoryId, prefix);
        if (plans == null || plans.size() != 1) {
            return null; // 无命中或多命中 = 歧义, 诚实不接
        }
        return sameOrNull(plans.get(0).getId(), consumingPlanId);
    }

    /** 自指(来源计划==消费计划)返回 null, 避免把本计划自身原料重复接进分母/递归。 */
    private String sameOrNull(String candidatePlanId, String consumingPlanId) {
        if (candidatePlanId == null || candidatePlanId.equals(consumingPlanId)) {
            return null;
        }
        return candidatePlanId;
    }

    /** 解析锚批号 CLK-SEMI-{planId8}-{productTypeId8} 的 planId8 段; 非锚格式 → null。 */
    private String anchorPlanPrefix(String intermediateBatchNo) {
        if (intermediateBatchNo == null || !intermediateBatchNo.startsWith("CLK-SEMI-")) {
            return null;
        }
        String rest = intermediateBatchNo.substring("CLK-SEMI-".length());
        int dash = rest.indexOf('-');
        String planId8 = dash >= 0 ? rest.substring(0, dash) : rest;
        // 占位 00000000 段无意义 (planId 为 null 时的 head8 占位)
        if (planId8.isEmpty() || "00000000".equals(planId8)) {
            return null;
        }
        return planId8;
    }

    /** 来源计划自身首道原料投入重 (与 ProductionSummary 同口径; ≤0/无 → null)。 */
    private BigDecimal resolvePlanFrontRaw(String factoryId, String sourcePlanId) {
        List<ProcessSheetInventoryItem> items =
                processSheetService.getInventoryYieldCard(factoryId, sourcePlanId);
        BigDecimal raw = ProductionSummaryService.sumFirstProcessRawInput(items);
        return raw != null && raw.signum() > 0 ? raw : null;
    }

    private ProcessSheetRowRequest parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception ex) {
            log.warn("[reused-semi-lineage] 无法解析 process_sheet_row payload: {}", ex.getMessage());
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
