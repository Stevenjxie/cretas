package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Component
public class SkuGrossMarginTool extends AbstractBusinessTool {

    @Autowired
    private ProductionBatchRepository productionBatchRepository;
    @Autowired
    private BatchConsumptionService batchConsumptionService;

    @Override
    public String getToolName() {
        return "report_sku_gross_margin";
    }

    @Override
    public String getDescription() {
        return "查询SKU毛利率，基于实际物料成本+人工成本计算。当用户问'毛利率'、'SKU利润'、'哪个产品赚钱'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "productName", Map.of("type", "string", "description", "产品名称(可选，不填则查全部)"),
                        "batchNumber", Map.of("type", "string", "description", "生产批次号(可选，查特定批次)")
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String productName = getString(params, "productName");
        String batchNumber = getString(params, "batchNumber");

        List<ProductionBatch> batches;

        if (batchNumber != null) {
            batches = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                    .map(List::of)
                    .orElse(List.of());
        } else {
            // 取最近的已完成批次
            batches = productionBatchRepository.findRecentCompletedByFactory(factoryId);
        }

        if (productName != null) {
            batches = batches.stream()
                    .filter(b -> b.getProductName() != null && b.getProductName().contains(productName))
                    .toList();
        }

        // 按产品类型聚合
        Map<String, List<ProductionBatch>> byProduct = new LinkedHashMap<>();
        for (ProductionBatch b : batches) {
            String key = b.getProductName() != null ? b.getProductName() : b.getProductTypeId();
            byProduct.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }

        List<Map<String, Object>> margins = new ArrayList<>();
        for (Map.Entry<String, List<ProductionBatch>> entry : byProduct.entrySet()) {
            BigDecimal totalMaterialCost = BigDecimal.ZERO;
            BigDecimal totalLaborCost = BigDecimal.ZERO;
            BigDecimal totalQuantity = BigDecimal.ZERO;

            for (ProductionBatch b : entry.getValue()) {
                totalMaterialCost = totalMaterialCost.add(
                        b.getMaterialCost() != null ? b.getMaterialCost() : BigDecimal.ZERO);
                totalLaborCost = totalLaborCost.add(
                        b.getLaborCost() != null ? b.getLaborCost() : BigDecimal.ZERO);
                totalQuantity = totalQuantity.add(
                        b.getGoodQuantity() != null ? b.getGoodQuantity() : BigDecimal.ZERO);
            }

            BigDecimal totalCost = totalMaterialCost.add(totalLaborCost);
            BigDecimal unitCost = totalQuantity.compareTo(BigDecimal.ZERO) > 0
                    ? totalCost.divide(totalQuantity, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productName", entry.getKey());
            item.put("batchCount", entry.getValue().size());
            item.put("totalQuantity", totalQuantity);
            item.put("materialCost", totalMaterialCost);
            item.put("laborCost", totalLaborCost);
            item.put("totalCost", totalCost);
            item.put("unitCost", unitCost);
            // 毛利率需要销售价，如果没有则只展示成本
            item.put("note", "销售价待接入后可自动计算毛利率");
            margins.add(item);
        }

        // 2026-07-24 修: "查询成功"占位符无内容 (工厂读电池钉出)。汇总关键
        // 数字进 message; 无数据时诚实空态 — 不用零值冒充毛利率。
        if (margins.isEmpty()) {
            return buildSimpleResult(
                    "当前没有可计算成本的生产批次数据，无法给出 SKU 成本/毛利分析，也不会用零值替代。",
                    Map.of("skuMargins", margins, "totalProducts", 0));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SKU 成本分析: 覆盖 ").append(margins.size()).append(" 个产品。");
        int shown = 0;
        for (Map<String, Object> m : margins) {
            if (shown++ >= 3) {
                sb.append(" 等");
                break;
            }
            sb.append(" ").append(m.get("productName"))
              .append(" 单位成本 ¥").append(m.get("unitCost")).append(";");
        }
        sb.append(" 注: 销售价待接入后可自动计算毛利率, 当前仅展示成本侧。");
        return buildSimpleResult(sb.toString(), Map.of(
                "skuMargins", margins,
                "totalProducts", margins.size()
        ));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
