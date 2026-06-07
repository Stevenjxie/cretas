package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.dto.bom.BomYieldStaleRowDTO;
import com.cretas.aims.exception.BomYieldStaleException;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * BOM 出成率评估服务实现.
 *
 * <p><b>核心算法</b>:
 * <ul>
 *   <li>查询该产品最近 N={@link #MAX_SAMPLE_BATCHES} 个已完成批次 (factoryId 隔离)</li>
 *   <li>每批次调用 {@link YieldReportService#getYield} 取 cumulativeYieldRate (0~1 小数)</li>
 *   <li>cumulativeYieldRate 为 null 的批次排除 (跨单位/无克重数据)</li>
 *   <li>有效样本 ≥ {@link #MIN_SAMPLES} 时取 P50 中位数 ×100, 精度 2dp HALF_UP (无上限, 保水工序可合法超 100%)</li>
 *   <li>结果写入 bom_items.yield_rate 时同写 BomChangeLog (old→new)</li>
 * </ul>
 *
 * <p><b>factoryId 隔离</b>: 每一个 repository 查询都带 factoryId 参数,
 * 绝不返回/写入其他租户数据.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BomYieldEstimateServiceImpl implements BomYieldEstimateService {

    /** 最多取最近 N 个已完成批次作为样本 */
    static final int MAX_SAMPLE_BATCHES = 10;

    /** 需要 ≥ 3 个有效样本才给出 suggestedYieldRate */
    static final int MIN_SAMPLES = 3;

    private final ProductionBatchRepository productionBatchRepository;
    private final ProductTypeRepository productTypeRepository;
    private final BomItemRepository bomItemRepository;
    private final BomChangeLogRepository bomChangeLogRepository;

    /**
     * YieldReportService 注入: 接口级注入防止循环依赖
     * (YieldReportServiceImpl → ProductionBatchRepository,
     *  本 service → YieldReportService (interface), 无循环).
     */
    private final YieldReportService yieldReportService;

    // ─── 1. 单产品评估 ────────────────────────────────────────────────────────

    @Override
    public BomYieldEstimateDTO estimateForProduct(
            String factoryId, String productTypeId, String materialCategory) {

        // 1a. gramsPerUnit (建议成品含量)
        Optional<ProductType> ptOpt = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId);
        BigDecimal suggestedStandardQuantity = ptOpt
                .map(ProductType::getGramsPerUnit)
                .orElse(null);

        // 1b. 样本出成率
        List<BigDecimal> yieldSamples = collectYieldSamples(factoryId, productTypeId);
        int sampleCount = yieldSamples.size();

        if (sampleCount < MIN_SAMPLES) {
            // 样本不足
            String source = (suggestedStandardQuantity != null) ? "STANDARD_WEIGHT_ONLY" : "NONE";
            String reason = "INSUFFICIENT_SAMPLES";
            String actionHint = (suggestedStandardQuantity == null)
                    ? "请先在系统管理→产品维护填写标准克重" : null;
            if (suggestedStandardQuantity == null) {
                reason = "NO_GRAMS_PER_UNIT";
                source = "NONE";
            }
            return BomYieldEstimateDTO.builder()
                    .productTypeId(productTypeId)
                    .materialCategory(materialCategory)
                    .suggestedStandardQuantity(suggestedStandardQuantity)
                    .suggestedYieldRate(null)
                    .sampleCount(sampleCount)
                    .yieldMin(null)
                    .yieldMax(null)
                    .source(source)
                    .reason(reason)
                    .actionHint(actionHint)
                    .build();
        }

        // 1c. P50 中位数
        BigDecimal suggestedYieldRate = computeMedian(yieldSamples);
        BigDecimal yieldMin = yieldSamples.stream()
                .min(BigDecimal::compareTo)
                .map(v -> v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
        BigDecimal yieldMax = yieldSamples.stream()
                .max(BigDecimal::compareTo)
                .map(v -> v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                .orElse(null);

        String reason = (suggestedStandardQuantity == null) ? "NO_GRAMS_PER_UNIT" : null;
        String source = (sampleCount >= MIN_SAMPLES) ? "BATCH_REPORTING" : "STANDARD_WEIGHT_ONLY";
        String actionHint = (suggestedStandardQuantity == null)
                ? "请先在系统管理→产品维护填写标准克重" : null;

        return BomYieldEstimateDTO.builder()
                .productTypeId(productTypeId)
                .materialCategory(materialCategory)
                .suggestedStandardQuantity(suggestedStandardQuantity)
                .suggestedYieldRate(suggestedYieldRate)
                .sampleCount(sampleCount)
                .yieldMin(yieldMin)
                .yieldMax(yieldMax)
                .source(source)
                .reason(reason)
                .actionHint(actionHint)
                .build();
    }

    // ─── 2. 批量预览 (只读) ───────────────────────────────────────────────────

    @Override
    public List<BomYieldPreviewItemDTO> recalculatePreview(
            String factoryId, List<String> productTypeIds) {

        // 取需要评估的产品列表
        List<String> targetIds = resolveTargetProductIds(factoryId, productTypeIds);
        if (targetIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<BomYieldPreviewItemDTO> result = new ArrayList<>();

        for (String productTypeId : targetIds) {
            // 找到 RAW 主料行 (第一条 materialCategory = RAW)
            List<BomItem> bomItems = bomItemRepository
                    .findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                            factoryId, productTypeId);
            BomItem rawItem = bomItems.stream()
                    .filter(b -> "RAW".equalsIgnoreCase(b.getMaterialCategory()))
                    .findFirst()
                    .orElse(null);

            if (rawItem == null) {
                // 无 RAW 行 → SKIP
                continue;
            }

            List<BigDecimal> samples = collectYieldSamples(factoryId, productTypeId);
            int sampleCount = samples.size();

            if (sampleCount < MIN_SAMPLES) {
                result.add(BomYieldPreviewItemDTO.builder()
                        .productTypeId(productTypeId)
                        .productName(rawItem.getProductName())
                        .bomItemId(rawItem.getId())
                        .materialName(rawItem.getMaterialName())
                        .currentYieldRate(rawItem.getYieldRate())
                        .suggestedYieldRate(null)
                        .sampleCount(sampleCount)
                        .status("INSUFFICIENT_SAMPLES")
                        .build());
                continue;
            }

            BigDecimal suggestedYieldRate = computeMedian(samples);
            BigDecimal currentYieldRate = rawItem.getYieldRate();

            // UPDATABLE if suggested differs from current (null current treated as 100.00 default)
            BigDecimal current = (currentYieldRate != null) ? currentYieldRate : new BigDecimal("100.00");
            boolean changed = current.compareTo(suggestedYieldRate) != 0;

            result.add(BomYieldPreviewItemDTO.builder()
                    .productTypeId(productTypeId)
                    .productName(rawItem.getProductName())
                    .bomItemId(rawItem.getId())
                    .materialName(rawItem.getMaterialName())
                    .currentYieldRate(rawItem.getYieldRate())
                    .suggestedYieldRate(suggestedYieldRate)
                    .sampleCount(sampleCount)
                    .status(changed ? "UPDATABLE" : "SKIP")
                    .build());
        }

        return result;
    }

    // ─── 3. 批量应用 (写操作) ─────────────────────────────────────────────────

    @Override
    @Transactional
    public BomYieldApplyResultDTO recalculateApply(
            String factoryId, List<BomYieldApplyRequest> requests) {

        // ── H5: read acting user from JWT request attributes. JwtAuthInterceptor sets request
        //    attributes "userId"(Long) / "username"(String); SecurityContextHolder is NOT populated
        //    in this codebase (see ReportAuthGuard) so SecurityUtils.getCurrentUserId() would return
        //    null. null-safe: unit tests / async have no request context → changedBy stays null. ──
        Long actingUserId = null;
        String actingUsername = null;
        RequestAttributes reqAttrs = RequestContextHolder.getRequestAttributes();
        if (reqAttrs != null) {
            Object uid = reqAttrs.getAttribute("userId", RequestAttributes.SCOPE_REQUEST);
            if (uid instanceof Long) {
                actingUserId = (Long) uid;
            } else if (uid instanceof Integer) {
                actingUserId = ((Integer) uid).longValue();
            } else if (uid instanceof String) {
                try { actingUserId = Long.parseLong((String) uid); } catch (NumberFormatException ignored) { }
            }
            Object uname = reqAttrs.getAttribute("username", RequestAttributes.SCOPE_REQUEST);
            if (uname != null) {
                actingUsername = uname.toString();
            }
        }

        // ── M10 Phase 1: pre-flight staleness check (all-or-nothing) ────────────────────────────
        // Load each RAW item that has a non-null expectedCurrentYieldRate and verify the DB value
        // still matches. Collect all stale rows before touching anything; if any are stale, throw
        // 409 immediately so no writes happen (fool-proof Rule 4: idempotent / detect race).
        List<BomYieldStaleRowDTO> staleRows = new ArrayList<>();
        for (BomYieldApplyRequest req : requests) {
            BigDecimal expected = req.getExpectedCurrentYieldRate();
            if (expected == null) {
                continue; // caller chose not to pass expectedCurrentYieldRate — skip check for this row
            }
            Optional<BomItem> itemOpt = bomItemRepository.findById(req.getBomItemId());
            if (itemOpt.isEmpty()) {
                continue; // not-found rows are handled in the write loop below
            }
            BomItem item = itemOpt.get();
            // Only check ownership + RAW rows (mirrors write loop guards)
            if (!factoryId.equals(item.getFactoryId())) {
                continue;
            }
            if (!"RAW".equalsIgnoreCase(item.getMaterialCategory())) {
                continue;
            }
            BigDecimal dbCurrent = item.getYieldRate(); // may be null (待评估)
            boolean matches = (dbCurrent == null && expected == null)
                    || (dbCurrent != null && expected != null && dbCurrent.compareTo(expected) == 0);
            if (!matches) {
                staleRows.add(BomYieldStaleRowDTO.builder()
                        .bomItemId(req.getBomItemId())
                        .dbCurrent(dbCurrent)
                        .expected(expected)
                        .build());
                log.warn("[BomYieldApply] M10 stale: bomItemId={} expected={} dbCurrent={}",
                        req.getBomItemId(), expected, dbCurrent);
            }
        }
        if (!staleRows.isEmpty()) {
            throw new BomYieldStaleException(staleRows,
                    "部分行的出成率已被其他操作修改，请重新预览后再应用。共 " + staleRows.size() + " 行数据过期。");
        }
        // ─────────────────────────────────────────────────────────────────────────────────────────

        int applied = 0;
        List<String> changeLogIds = new ArrayList<>();

        for (BomYieldApplyRequest req : requests) {
            Long bomItemId = req.getBomItemId();
            BigDecimal newYieldRate = req.getYieldRate().setScale(2, RoundingMode.HALF_UP);

            // Load BomItem — must belong to factoryId AND be RAW
            Optional<BomItem> itemOpt = bomItemRepository.findById(bomItemId);
            if (itemOpt.isEmpty()) {
                log.warn("[BomYieldApply] bomItemId={} not found, skipping", bomItemId);
                continue;
            }
            BomItem item = itemOpt.get();

            // factoryId isolation — hard check
            if (!factoryId.equals(item.getFactoryId())) {
                log.warn("[BomYieldApply] SECURITY: bomItemId={} belongs to factory={}, request from factory={}; skipping",
                        bomItemId, item.getFactoryId(), factoryId);
                continue;
            }

            // Only update RAW main-material rows
            if (!"RAW".equalsIgnoreCase(item.getMaterialCategory())) {
                log.debug("[BomYieldApply] bomItemId={} is not RAW (category={}), skipping",
                        bomItemId, item.getMaterialCategory());
                continue;
            }

            // Snapshot old value before update (mirrors BomServiceImpl.snapshotBomItem pattern)
            Map<String, Object> oldSnapshot = snapshotBomItem(item);
            BigDecimal oldYieldRate = item.getYieldRate();

            // Write new yieldRate
            item.setYieldRate(newYieldRate);
            bomItemRepository.save(item);
            applied++;

            // Write BomChangeLog (old → new), mirrors BomServiceImpl.recordBomChange
            Map<String, Object> newSnapshot = snapshotBomItem(item);
            BomChangeLog changeLog = new BomChangeLog();
            changeLog.setFactoryId(factoryId);
            changeLog.setBomId(item.getProductTypeId());
            changeLog.setBomItemId(item.getId());
            changeLog.setChangeType(BomChangeLog.ChangeType.UPDATE);
            changeLog.setOldValue(oldSnapshot);
            changeLog.setNewValue(newSnapshot);
            changeLog.setChangeReason("BOM出成率评估系统建议更新 (旧值: " + oldYieldRate + " → 新值: " + newYieldRate + ")");
            // H5: populate acting user identity (null-safe for unit-test / system contexts)
            changeLog.setChangedBy(actingUserId);
            changeLog.setChangedByName(actingUsername);
            BomChangeLog saved = bomChangeLogRepository.save(changeLog);
            changeLogIds.add(saved.getId());

            log.info("[BomYieldApply] factoryId={} bomItemId={} yieldRate {} → {} by userId={}",
                    factoryId, bomItemId, oldYieldRate, newYieldRate, actingUserId);
        }

        return BomYieldApplyResultDTO.builder()
                .applied(applied)
                .changeLogIds(changeLogIds)
                .build();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * 取最近 MAX_SAMPLE_BATCHES 个已完成批次的有效 cumulativeYieldRate (0~1).
     * cumulativeYieldRate 为 null 的批次排除 (跨单位/无克重).
     */
    List<BigDecimal> collectYieldSamples(String factoryId, String productTypeId) {
        List<ProductionBatch> batches = productionBatchRepository
                .findRecentCompletedByFactoryAndProductType(
                        factoryId, productTypeId,
                        PageRequest.of(0, MAX_SAMPLE_BATCHES));

        List<BigDecimal> samples = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                BatchYieldDTO dto = yieldReportService.getYield(factoryId, batch.getId());
                if (dto != null && dto.getCumulativeYieldRate() != null) {
                    samples.add(dto.getCumulativeYieldRate());
                }
            } catch (Exception e) {
                log.debug("[BomYieldEstimate] getYield failed for batchId={}: {}", batch.getId(), e.getMessage());
            }
        }
        return samples;
    }

    /**
     * 计算中位数 P50, ×100 转为百分比, scale=2 HALF_UP.
     *
     * <p><b>注意</b>: 不设 ≤100 上限 — 保水/腌制等工序出成率合法超过 100%
     * (e.g. 六扇门猪舌保水工序实测 105–126%). capping 会静默腐蚀这些 BOM 行的数据.
     *
     * @param samples 原始 cumulativeYieldRate (0~1 小数, 也可 >1) 列表; 调用方保证非空且 ≥ MIN_SAMPLES
     */
    BigDecimal computeMedian(List<BigDecimal> samples) {
        List<BigDecimal> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        BigDecimal median;
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            // 偶数: 取中间两个的平均
            BigDecimal lo = sorted.get(n / 2 - 1);
            BigDecimal hi = sorted.get(n / 2);
            median = lo.add(hi).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        }
        // × 100 → 百分比, scale=2 HALF_UP. No ≤100 cap (water-gain processes legitimately > 100).
        return median.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 解析要评估的产品 ID 列表.
     * productTypeIds 为 null 或空时, 查工厂所有有 BOM 的产品.
     */
    private List<String> resolveTargetProductIds(String factoryId, List<String> productTypeIds) {
        if (productTypeIds != null && !productTypeIds.isEmpty()) {
            return productTypeIds;
        }
        return bomItemRepository.findDistinctProductTypeIds(factoryId);
    }

    /** 镜像 BomServiceImpl.snapshotBomItem, 用于 BomChangeLog.oldValue/newValue */
    private Map<String, Object> snapshotBomItem(BomItem item) {
        if (item == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", item.getId());
        snapshot.put("productTypeId", item.getProductTypeId());
        snapshot.put("productName", item.getProductName());
        snapshot.put("materialTypeId", item.getMaterialTypeId());
        snapshot.put("materialName", item.getMaterialName());
        snapshot.put("materialCategory", item.getMaterialCategory());
        snapshot.put("standardQuantity", item.getStandardQuantity());
        snapshot.put("yieldRate", item.getYieldRate());
        snapshot.put("unit", item.getUnit());
        snapshot.put("unitPrice", item.getUnitPrice());
        snapshot.put("taxRate", item.getTaxRate());
        snapshot.put("sortOrder", item.getSortOrder());
        snapshot.put("remark", item.getRemark());
        return snapshot;
    }
}
