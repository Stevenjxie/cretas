package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临期物料处置建议 Tool (Sprint 8 P4 — 仓管员 Workdesk V1).
 *
 * <p>F006 真场景: 仓管员张师傅看到一批卤猪蹄毛料还剩 3 天到期, 不知道该 a) 调拨到分店 / b) 降价
 * 用掉 / c) 申请退货. 系统按内置规则输出 AI 处置建议. (Sprint 9 接 LLM 真分析, 当前用规则.)
 *
 * <p>vs HJ 模式: 仓管员手动看保质期报表 → 找门店经理打电话 → 报领导 (30 min).
 * Cretas: 1 屏 30 秒 + 推荐行动按钮.
 *
 * <p>规则 (rule-based MVP):
 * <ul>
 *   <li>剩余 0-2 天 + 剩余数量 &lt; 30% 总量 → 推荐"立即降价用掉"</li>
 *   <li>剩余 0-2 天 + 剩余数量 ≥ 30% → 推荐"调拨到分店"</li>
 *   <li>剩余 3-5 天 → 推荐"调拨到分店 / FEFO 优先消耗"</li>
 *   <li>剩余 6-7 天 + 来自单一供应商 → 推荐"联系供应商协商退货"</li>
 *   <li>已过期 → 推荐"报废 + 财务核销"</li>
 * </ul>
 *
 * <p>Intent Code: {@code MATERIAL_DISPOSAL_RECOMMENDATION}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4)
 */
@Slf4j
@Component
public class MaterialDisposalRecommendationTool extends AbstractBusinessTool {

    private static final int DEFAULT_DAYS_TO_EXPIRY = 7;
    private static final int MAX_DAYS_TO_EXPIRY = 30;

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Override
    public String getToolName() {
        return "material_disposal_recommendation";
    }

    @Override
    public String getDescription() {
        return "对临期 / 已过期原材料批次给出处置建议 (调拨 / 降价用掉 / 退货 / 报废). "
                + "LLM 触发场景: 仓管员问 '快过期的怎么处理?' / '临期物料建议' / "
                + "'这批要过期的怎么办' / '过期原料处理' / '保质期紧张的物料'. "
                + "read-only — 仅推荐, 实际执行需仓管员另发指令 (e.g. 调拨单).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> daysToExpiry = new HashMap<>();
        daysToExpiry.put("type", "integer");
        daysToExpiry.put("description",
                "查询多少天内将过期的批次 (默认 7, 上限 30). 已过期批次 (剩余 <0 天) 永远包含");
        daysToExpiry.put("default", DEFAULT_DAYS_TO_EXPIRY);
        daysToExpiry.put("minimum", 1);
        daysToExpiry.put("maximum", MAX_DAYS_TO_EXPIRY);
        properties.put("daysToExpiry", daysToExpiry);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        int daysToExpiry = clamp(getInteger(params, "daysToExpiry", DEFAULT_DAYS_TO_EXPIRY),
                1, MAX_DAYS_TO_EXPIRY);
        LocalDate today = LocalDate.now();
        LocalDate warning = today.plusDays(daysToExpiry);

        log.info("material_disposal_recommendation — factory={} daysToExpiry={}",
                factoryId, daysToExpiry);

        // 1. 临期 (today < expireDate <= warning)
        List<MaterialBatch> expiring = materialBatchRepository.findExpiringBatches(
                factoryId, warning);
        // 2. 已过期 (expireDate < today, 状态非 EXPIRED — 防重复处理)
        List<MaterialBatch> expired = materialBatchRepository.findExpiredBatches(factoryId);

        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (MaterialBatch b : expiring) {
            // 排除已 SCRAPPED / USED_UP / DEPLETED
            if (excludeStatus(b.getStatus())) continue;
            recommendations.add(buildRow(b, today, false));
        }
        for (MaterialBatch b : expired) {
            if (excludeStatus(b.getStatus())) continue;
            recommendations.add(buildRow(b, today, true));
        }

        // 按 daysRemaining 升序排 (过期最近排前)
        recommendations.sort((a, c) -> {
            Long ra = (Long) a.get("daysRemaining");
            Long rc = (Long) c.get("daysRemaining");
            if (ra == null && rc == null) return 0;
            if (ra == null) return 1;
            if (rc == null) return -1;
            return ra.compareTo(rc);
        });

        // 按建议行动统计
        Map<String, Integer> actionCount = new HashMap<>();
        for (Map<String, Object> r : recommendations) {
            String act = (String) r.get("recommendedAction");
            actionCount.merge(act, 1, Integer::sum);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("today", today.toString());
        data.put("daysToExpiry", daysToExpiry);
        data.put("expiringCount", expiring.size());
        data.put("expiredCount", expired.size());
        data.put("recommendations", recommendations);
        data.put("actionSummary", actionCount);

        String message;
        if (recommendations.isEmpty()) {
            message = String.format("近 %d 天内无临期 / 已过期物料, 库存健康", daysToExpiry);
        } else {
            int expiredN = (int) recommendations.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("isExpired"))).count();
            int expiringN = recommendations.size() - expiredN;
            message = String.format(
                    "%d 临期 + %d 已过期 = %d 批待处置. 建议优先处理已过期 (报废) + 0-2 天 (立即降价)",
                    expiringN, expiredN, recommendations.size());
        }
        return buildSimpleResult(message, data);
    }

    private boolean excludeStatus(MaterialBatchStatus s) {
        return s == MaterialBatchStatus.SCRAPPED
                || s == MaterialBatchStatus.USED_UP
                || s == MaterialBatchStatus.DEPLETED
                || s == MaterialBatchStatus.EXPIRED;
    }

    private Map<String, Object> buildRow(MaterialBatch b, LocalDate today, boolean isExpired) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", b.getId());
        row.put("batchNumber", b.getBatchNumber());
        row.put("materialTypeId", b.getMaterialTypeId());
        row.put("materialName", b.getMaterialType() != null
                ? b.getMaterialType().getName() : null);
        row.put("expireDate", b.getExpireDate() != null ? b.getExpireDate().toString() : null);

        Long daysRemaining = null;
        if (b.getExpireDate() != null) {
            daysRemaining = ChronoUnit.DAYS.between(today, b.getExpireDate());
        }
        row.put("daysRemaining", daysRemaining);
        row.put("isExpired", isExpired);

        BigDecimal current = b.getCurrentQuantity();
        BigDecimal total = b.getReceiptQuantity();
        row.put("currentQuantity", current);
        row.put("receiptQuantity", total);
        row.put("usedQuantity", b.getUsedQuantity());
        row.put("storageLocation", b.getStorageLocation());
        row.put("status", b.getStatus() != null ? b.getStatus().name() : null);

        // 计算 used 比例
        BigDecimal usedPct = BigDecimal.ZERO;
        if (total != null && total.compareTo(BigDecimal.ZERO) > 0 && current != null) {
            BigDecimal used = total.subtract(current);
            usedPct = used.divide(total, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }
        row.put("usedPct", usedPct);

        // 推荐行动
        String action;
        String reason;
        if (isExpired) {
            action = "SCRAP";
            reason = "已过期, 食品安全风险, 必须报废并财务核销";
        } else if (daysRemaining != null && daysRemaining <= 2) {
            // 0-2 天: 看剩余比例
            BigDecimal remainPct = current != null && total != null
                    && total.compareTo(BigDecimal.ZERO) > 0
                    ? current.divide(total, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
            if (remainPct.compareTo(new BigDecimal("30")) < 0) {
                action = "DISCOUNT_SELL";
                reason = String.format("剩 %d 天 + 库存量小 (%.0f%%), 建议立即降价用掉",
                        daysRemaining, remainPct);
            } else {
                action = "TRANSFER";
                reason = String.format("剩 %d 天 + 库存量大 (%.0f%%), 建议调拨到分店或集中消化",
                        daysRemaining, remainPct);
            }
        } else if (daysRemaining != null && daysRemaining <= 5) {
            action = "TRANSFER";
            reason = String.format("剩 %d 天, 建议调拨到分店或 FEFO 优先消耗", daysRemaining);
        } else if (daysRemaining != null && daysRemaining <= 7 && b.getSupplierId() != null) {
            action = "RETURN_TO_SUPPLIER";
            reason = String.format("剩 %d 天 + 单一供应商, 建议联系供应商协商退货", daysRemaining);
        } else {
            action = "MONITOR";
            reason = String.format("剩 %s 天, 继续 FEFO 消化即可",
                    daysRemaining != null ? daysRemaining.toString() : "?");
        }
        row.put("recommendedAction", action);
        row.put("recommendedActionDisplay", actionDisplay(action));
        row.put("recommendedReason", reason);
        return row;
    }

    private String actionDisplay(String action) {
        switch (action) {
            case "SCRAP": return "🗑️ 报废 + 财务核销";
            case "DISCOUNT_SELL": return "💸 立即降价消化";
            case "TRANSFER": return "🚚 调拨到分店";
            case "RETURN_TO_SUPPLIER": return "↩️ 协商退货";
            case "MONITOR": return "👀 继续观察";
            default: return action;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
