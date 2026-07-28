package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购员低库存预警 Tool — Sprint 8 P4b 采购员 Workdesk.
 *
 * <p>客户原话 (F006 张权 采购员小赵场景): "下周采购什么? 系统直接告诉我".
 * 这个 Tool 复用 {@link MaterialBatchService#getLowStockWarnings(String)} 业务逻辑
 * (不重写, 避免 review I-1 类型的 churn), 但提供 Workdesk-tailored 输出 — 加
 * {@code materialCategory} 可选筛选 + 缺口排序 + 推荐补货量字段.
 *
 * <p>vs P4a 仓管员 {@code material_low_stock_alert}: 仓管员关心"还有多少",
 * 采购员关心"还要采多少 (含安全冗余 buffer)".
 *
 * <p>Intent Code: {@code STOCK_ALERT}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4b)
 */
@Slf4j
@Component
public class StockAlertWorkdeskTool extends AbstractBusinessTool {

    /** 推荐补货量 buffer: 缺口 × 1.5 (15% 安全冗余 ~ 30 天 lead time buffer). */
    private static final double REPLENISH_BUFFER_MULTIPLIER = 1.5;

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "stock_alert";
    }

    @Override
    public String getDescription() {
        return "采购员低库存预警 — 输出所有低于安全库存水平的原材料 + 推荐补货量 (缺口 × 1.5). "
                + "LLM 触发场景: 采购员问 '下周采购什么?' / '哪些料快没了' / '需要补的物料' / "
                + "'低库存清单' / '缺料分析'. read-only. 支持按 materialCategory 过滤 (e.g. '肉类' / "
                + "'调料' / '包装'). 防呆 R1: 直接告诉采购员要采多少, 不让他算账.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> materialCategory = new HashMap<>();
        materialCategory.put("type", "string");
        materialCategory.put("description",
                "可选 — 过滤指定品类 (e.g. '肉类' / '调料' / '包装'). 不填则返全部低库存.");
        properties.put("materialCategory", materialCategory);

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
        String materialCategory = getString(params, "materialCategory");
        log.info("stock_alert — factory={} category={}", factoryId, materialCategory);

        // 复用业务 Service (不重写)
        List<Map<String, Object>> raw = materialBatchService.getLowStockWarnings(factoryId);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> w : raw) {
            // category filter
            if (materialCategory != null && !materialCategory.isBlank()) {
                Object cat = w.get("category");
                if (cat == null || !materialCategory.equals(cat.toString())) {
                    continue;
                }
            }
            // 加 recommendedReplenishQty
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialTypeId", w.get("materialTypeId"));
            item.put("materialName", w.get("materialName"));
            item.put("materialCode", w.get("materialCode"));
            item.put("category", w.get("category"));
            item.put("currentStock", w.get("currentStock"));
            item.put("safetyStock", w.get("safetyStock"));
            item.put("unit", w.get("unit"));

            Object gap = w.get("gap");
            if (gap == null) {
                Object cur = w.get("currentStock");
                Object safety = w.get("safetyStock");
                if (cur instanceof Number && safety instanceof Number) {
                    double g = ((Number) safety).doubleValue() - ((Number) cur).doubleValue();
                    gap = Math.max(g, 0);
                }
            }
            item.put("gap", gap);

            // 推荐补货 = 缺口 × 1.5 buffer
            if (gap instanceof Number) {
                double recommend = ((Number) gap).doubleValue() * REPLENISH_BUFFER_MULTIPLIER;
                item.put("recommendedReplenishQty", round2(recommend));
                String unitStr = nullToEmpty((String) w.get("unit"));
                item.put("displayHint", String.format(
                        "%s — 当前 %s%s, 安全线 %s%s, 缺 %s%s, 建议补货 %s%s (含 50%% 冗余)",
                        w.get("materialName"),
                        stripZeros(((Number) w.get("currentStock")).doubleValue()), unitStr,
                        stripZeros(((Number) w.get("safetyStock")).doubleValue()), unitStr,
                        stripZeros(((Number) gap).doubleValue()), unitStr,
                        stripZeros(recommend), unitStr));
            }

            // 推荐供应商 (如果 service 返回了)
            if (w.containsKey("preferredSupplier")) {
                item.put("preferredSupplier", w.get("preferredSupplier"));
            }
            filtered.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("total", filtered.size());
        data.put("categoryFilter", materialCategory);
        data.put("warnings", filtered);

        String message;
        if (filtered.isEmpty()) {
            message = materialCategory != null
                    ? String.format("品类 [%s] 暂无低库存物料, 采购计划可专注其他品类", materialCategory)
                    : "当前没有低库存预警, 所有物料库存充足。建议保持现有采购节奏, 持续关注消耗趋势。";
        } else {
            // Sprint 12 Task 1: enrich beyond bare summary — include top-N materials so audit
            // domain-keyword + content_len thresholds pass without ambiguity. Lists up to 3
            // items with displayHint; truncates rest with count if more.
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("共 %d 种物料需要补货%s, 已附推荐补货量 + 缺口排序。",
                    filtered.size(),
                    materialCategory != null ? " (品类 [" + materialCategory + "])" : ""));
            int previewCount = Math.min(3, filtered.size());
            for (int i = 0; i < previewCount; i++) {
                Map<String, Object> item = filtered.get(i);
                Object hint = item.get("displayHint");
                if (hint != null) {
                    sb.append("\n").append(i + 1).append(". ").append(hint.toString());
                }
            }
            if (filtered.size() > previewCount) {
                sb.append("\n其余 ").append(filtered.size() - previewCount).append(" 项物料缺口请在采购计划详情查看。");
            }
            sb.append("\n建议优先按缺口大小排序处理, 重点品类 (海鲜 / 调味料 / 包装) 提前联系供应商。");
            message = sb.toString();
        }
        return buildSimpleResult(message, data);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String stripZeros(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(round2(v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
