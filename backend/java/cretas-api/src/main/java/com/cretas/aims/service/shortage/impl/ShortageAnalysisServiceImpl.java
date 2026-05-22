package com.cretas.aims.service.shortage.impl;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.orchestration.InventoryMatchingService;
import com.cretas.aims.service.shortage.ShortageAnalysisService;
import com.cretas.aims.service.shortage.dto.ProcurementSuggestion;
import com.cretas.aims.service.shortage.dto.ProductionPlanSuggestion;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售订单缺料分析编排实现 (Sprint 2 Track E — S-MRP-1 / N31)
 *
 * <p><b>设计原则 (read-only / 不重写)</b>:
 * <ul>
 *   <li>不创建 ProductionPlan / PurchaseOrder — 副作用仍由
 *       {@code SupplyChainOrchestrator.onSalesOrderFinanceApproved} 负责。</li>
 *   <li>不调用 {@code inventoryMatchingService.reserveStock} —
 *       预留由现有 orchestrator 完成。</li>
 *   <li>仅 read: {@code checkAvailability} + {@code expandBOM} +
 *       {@code checkMaterialAvailability}。</li>
 * </ul>
 *
 * <p><b>Day 2 MVP 范围</b>: 不查供应商/三价数据 — Day 3 通过 Track C 接入。
 */
@Service
@RequiredArgsConstructor
public class ShortageAnalysisServiceImpl implements ShortageAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ShortageAnalysisServiceImpl.class);

    /**
     * 生产建议默认 lead time fallback (天) — 实际值通过 thresholdResolver 读取, DB 未配置时 fallback 到此处.
     * Day 3 由 Track D2 工序链推算; Canvas Phase A 引入工厂级 override.
     */
    private static final int FALLBACK_PRODUCTION_LEAD_DAYS = 7;

    private final InventoryMatchingService inventoryMatchingService;
    private final BomExpansionService bomExpansionService;
    /**
     * Canvas-Thresholds resolver (Phase A) — overlays FALLBACK_PRODUCTION_LEAD_DAYS with per-factory config.
     */
    private final ThresholdResolverService thresholdResolver;

    private int productionLeadDays(String factoryId) {
        return thresholdResolver.getInteger(factoryId,
                ThresholdKeys.SHORTAGE_DEFAULT_PRODUCTION_LEAD_DAYS, FALLBACK_PRODUCTION_LEAD_DAYS);
    }

    @Override
    @Transactional(readOnly = true)
    public ShortageReport analyzeForSalesOrder(String factoryId, String salesOrderId) {
        log.info("[ShortageAnalysis] analyze: factoryId={}, salesOrderId={}", factoryId, salesOrderId);

        // ① FG 库存匹配 (InventoryMatchingService 内部已校验 SO 存在 + 抛 BusinessException)
        StockCheckResult fgResult = inventoryMatchingService.checkAvailability(factoryId, salesOrderId);
        List<LineItemMatch> fgLineItems = fgResult.getLineItems() != null
                ? fgResult.getLineItems()
                : Collections.emptyList();

        // ② 对每个 FG 缺口展开 BOM
        List<MaterialRequirement> aggregatedRequirements = aggregateBomNeeds(factoryId, fgLineItems);

        // ③ 原料库存检查
        MaterialCheckResult materialResult = aggregatedRequirements.isEmpty()
                ? emptyMaterialCheckResult()
                : bomExpansionService.checkMaterialAvailability(factoryId, aggregatedRequirements);

        List<MaterialShortfall> shortages = materialResult.getShortfalls() != null
                ? materialResult.getShortfalls()
                : Collections.emptyList();

        boolean fully = fgResult.isAllSatisfied() && materialResult.isAllSatisfied();

        return ShortageReport.builder()
                .salesOrderId(salesOrderId)
                .factoryId(factoryId)
                .analysisStatus("COMPLETED")
                .analyzedAt(LocalDateTime.now())
                .finishedGoodsLineItems(fgLineItems)
                .totalRequired(aggregatedRequirements)
                .materialShortages(shortages)
                .fullySatisfied(fully)
                .summary(buildSummary(salesOrderId, fgLineItems, shortages))
                .build();
    }

    @Override
    public List<ProcurementSuggestion> suggestProcurement(String factoryId, ShortageReport report) {
        if (report == null || report.getMaterialShortages() == null || report.getMaterialShortages().isEmpty()) {
            return Collections.emptyList();
        }

        // Day 2 MVP: 仅根据短缺量出建议。Day 3 接入 Track C MaterialPriceComparisonDTO + 供应商历史。
        List<ProcurementSuggestion> suggestions = new ArrayList<>(report.getMaterialShortages().size());
        for (MaterialShortfall sf : report.getMaterialShortages()) {
            suggestions.add(ProcurementSuggestion.builder()
                    .materialId(sf.getMaterialTypeId())
                    .materialName(sf.getMaterialTypeName())
                    .suggestedQty(sf.getShortfallQuantity())
                    .build());
        }
        return suggestions;
    }

    @Override
    public List<ProductionPlanSuggestion> suggestProduction(String factoryId, ShortageReport report) {
        if (report == null || report.getFinishedGoodsLineItems() == null
                || report.getFinishedGoodsLineItems().isEmpty()) {
            return Collections.emptyList();
        }

        // Day 2 MVP: 默认 lead time 7 天 (Canvas Phase A 可配置), workProcess 留空 (Day 3 接入 Track D2)。
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(productionLeadDays(factoryId));

        List<ProductionPlanSuggestion> suggestions = new ArrayList<>();
        for (LineItemMatch lim : report.getFinishedGoodsLineItems()) {
            if (lim.isFullySatisfied()) {
                continue;
            }
            suggestions.add(ProductionPlanSuggestion.builder()
                    .productId(lim.getProductTypeId())
                    .productName(lim.getProductTypeName())
                    .plannedQty(lim.getShortfallQuantity())
                    .workProcessIds(Collections.emptyList())
                    .workProcessNames(Collections.emptyList())
                    .startDate(start)
                    .endDate(end)
                    .build());
        }
        return suggestions;
    }

    /**
     * 对每个 FG 缺口展开 BOM, 按 materialTypeId 聚合 requiredQuantity (跨多个 SKU 共用原料合并)。
     * 不合并会导致 {@code checkMaterialAvailability} 同一 materialTypeId 产出多条 shortfall。
     */
    private List<MaterialRequirement> aggregateBomNeeds(String factoryId, List<LineItemMatch> fgLineItems) {
        // 保留 insertion order 便于 debug
        Map<String, MaterialRequirement> aggregated = new LinkedHashMap<>();

        for (LineItemMatch lim : fgLineItems) {
            if (lim.isFullySatisfied()) {
                continue;
            }
            List<MaterialRequirement> perProduct = bomExpansionService.expandBOM(
                    factoryId, lim.getProductTypeId(), lim.getShortfallQuantity());
            if (perProduct == null) {
                continue;
            }
            for (MaterialRequirement req : perProduct) {
                MaterialRequirement existing = aggregated.get(req.getMaterialTypeId());
                if (existing == null) {
                    aggregated.put(req.getMaterialTypeId(), copyRequirement(req));
                } else {
                    BigDecimal a = existing.getRequiredQuantity() != null ? existing.getRequiredQuantity() : BigDecimal.ZERO;
                    BigDecimal b = req.getRequiredQuantity() != null ? req.getRequiredQuantity() : BigDecimal.ZERO;
                    existing.setRequiredQuantity(a.add(b));
                }
            }
        }
        return new ArrayList<>(aggregated.values());
    }

    private MaterialRequirement copyRequirement(MaterialRequirement src) {
        MaterialRequirement copy = new MaterialRequirement();
        copy.setMaterialTypeId(src.getMaterialTypeId());
        copy.setMaterialTypeName(src.getMaterialTypeName());
        copy.setRequiredQuantity(src.getRequiredQuantity());
        copy.setWastageRate(src.getWastageRate());
        copy.setSourceUnit(src.getSourceUnit());
        return copy;
    }

    private MaterialCheckResult emptyMaterialCheckResult() {
        MaterialCheckResult result = new MaterialCheckResult();
        result.setAllSatisfied(true);
        result.setShortfalls(Collections.emptyList());
        result.setAllocations(Collections.emptyList());
        return result;
    }

    private String buildSummary(String salesOrderId,
                                List<LineItemMatch> fgLineItems,
                                List<MaterialShortfall> materialShortages) {
        long fgShort = fgLineItems.stream().filter(l -> !l.isFullySatisfied()).count();
        long matShort = materialShortages.size();
        if (fgShort == 0 && matShort == 0) {
            return String.format("销售订单 %s: 库存充足, 无需采购或加工。", salesOrderId);
        }
        return String.format("销售订单 %s: %d 个 SKU 成品库存不足, %d 种原料短缺, 已生成对应采购/生产建议。",
                salesOrderId, fgShort, matShort);
    }
}
