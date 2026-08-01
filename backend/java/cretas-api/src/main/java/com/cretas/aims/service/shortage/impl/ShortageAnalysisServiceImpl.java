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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
     *
     * <p>Optional injection (CI test compatibility): tests that pre-date Thresholds Hub
     * construct this service via Mockito @InjectMocks without a ThresholdResolverService.
     * Mirror ComplexityRouterImpl pattern — @Autowired(required=false) + null-check
     * fallback to FALLBACK_PRODUCTION_LEAD_DAYS keeps existing tests passing while
     * allowing prod injection. NOT `final` so Lombok @RequiredArgsConstructor skips it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    private int productionLeadDays(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PRODUCTION_LEAD_DAYS;
        return thresholdResolver.getInteger(factoryId,
                ThresholdKeys.SHORTAGE_DEFAULT_PRODUCTION_LEAD_DAYS, FALLBACK_PRODUCTION_LEAD_DAYS);
    }

    /**
     * doomed-tx 修复 (2026-08-01, incident: SO-20260801-0001 财审通过后, 日志打印
     * "[ShortageReport] async snapshot" / "analyze failed" 但 sales_order_shortage_report
     * 建表以来 0 行, 连 FAILED 占位行都没有).
     *
     * <p>根因(prod 全栈追出, 非猜测): {@link #aggregateBomNeeds} 内部调
     * {@code bomExpansionService.expandBOM}, 该方法自身 {@code @Transactional(readOnly=true)},
     * propagation 默认 REQUIRED —— 在旧代码里会 JOIN 调用方
     * {@code SalesOrderShortageReportListener.onSalesOrderFinanceApproved} 的
     * {@code @Transactional(REQUIRES_NEW)} 联动事务。产品无已激活 BOM 配方时 expandBOM 抛
     * {@code BusinessException}("产品尚无已激活的新版 BOM 配方"), Spring tx 拦截器在 expandBOM
     * 自己的 AOP 边界上就把"共享事务"标记 rollback-only —— 即使 listener 外层 try/catch 把异常
     * 吞掉、接着调用 {@code persistFailedPlaceholder} 想写一条 FAILED 占位行, 该 save 仍在同一
     * 已被标记的事务里, listener 方法正常返回后 commit 时抛 UnexpectedRollbackException, 整个联动
     * 事务(含刚 save 的 FAILED 占位行)被回滚 —— prod 日志显示"analyze failed"却查无此行, 正是
     * 这个机制(与 SupplyChainOrchestrator 里 onMaterialReceived/onBatchCompleted 2026-06-12
     * "第4次复发"的 doomed-tx 是同一套 Spring AOP 机制, 只是这次触发点是 REQUIRES_NEW 内部
     * 而非 REQUIRES_NEW 外层)。
     *
     * <p>本方法独立开 propagation=REQUIRES_NEW: 它挂起调用方事务, 在自己的物理事务里跑;
     * expandBOM 抛异常时只标记/回滚这个独立子事务, 调用方(listener)的联动事务不受牵连,
     * listener 的 catch 块 + persistFailedPlaceholder 才能在健康的事务里真正落库。
     * 返回值 {@link ShortageReport} 是纯 DTO(非 JPA 受管实体), 跨事务边界返回没有
     * 脱管(detached)风险。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ShortageReport analyzeForSalesOrder(String factoryId, String salesOrderId) {
        log.info("[ShortageAnalysis] analyze: factoryId={}, salesOrderId={}", factoryId, salesOrderId);

        // ① FG 库存匹配 (InventoryMatchingService 内部已校验 SO 存在 + 抛 BusinessException)
        StockCheckResult fgResult = inventoryMatchingService.checkAvailability(factoryId, salesOrderId);
        // 2026-08-01 incident 关联修复 (防御性, 非本次 prod 76/77 失败的主因 — 主因另有其人,
        // 是 LineItemMatch.isFullySatisfied() 计算 getter 被 Jackson 当属性序列化却无 setter
        // 可反序列化回去, 见 com.cretas.aims.dto.orchestration.LineItemMatch 类注释及本 PR
        // 描述; 该文件不在本次允许改动范围, 未修): 这里及下方原先的 Collections.emptyList()
        // 一旦被赋给 SalesOrderShortageReport 的 JSONB 列, Hibernate 每次 save() 前 dirty-check
        // 用的 deepCopy(hypersistence-utils ObjectMapperWrapper.clone) 会用"运行时 class"反
        // 序列化回原类型 —— Collections.emptyList() 的运行时类型是 JDK 包私有单例
        // java.util.Collections$EmptyList, 理论上 Jackson 造不出这个类的实例; 实测中这条尚
        // 未在 prod 堆栈里单独命中过(可能被 bug 2 更早触发抢先掩盖), 但作为已知的 Jackson/
        // Hibernate JSONB 反序列化风险点一并修掉, 改用 new ArrayList<>()(公开可反序列化类),
        // 语义不变, 没有下行风险。
        List<LineItemMatch> fgLineItems = fgResult.getLineItems() != null
                ? fgResult.getLineItems()
                : new ArrayList<>();

        // ② 对每个 FG 缺口展开 BOM
        List<MaterialRequirement> aggregatedRequirements = aggregateBomNeeds(factoryId, fgLineItems);

        // ③ 原料库存检查
        MaterialCheckResult materialResult = aggregatedRequirements.isEmpty()
                ? emptyMaterialCheckResult()
                : bomExpansionService.checkMaterialAvailability(factoryId, aggregatedRequirements);

        List<MaterialShortfall> shortages = materialResult.getShortfalls() != null
                ? materialResult.getShortfalls()
                : new ArrayList<>();

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
            return new ArrayList<>();
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
            return new ArrayList<>();
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
                    .workProcessIds(new ArrayList<>())
                    .workProcessNames(new ArrayList<>())
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
        result.setShortfalls(new ArrayList<>());
        result.setAllocations(new ArrayList<>());
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
