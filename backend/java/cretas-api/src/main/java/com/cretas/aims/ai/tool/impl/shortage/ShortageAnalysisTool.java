package com.cretas.aims.ai.tool.impl.shortage;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.shortage.ShortageAnalysisService;
import com.cretas.aims.service.shortage.dto.ProcurementSuggestion;
import com.cretas.aims.service.shortage.dto.ProductionPlanSuggestion;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S-MRP-1 销售订单缺料分析 (Sprint 2 Track E — N31).
 *
 * <p>客户原话 (六扇门 F006): "销售单审核通过 → 系统自动判断库存 → 缺料则分流采购,
 * 否则直接生产 → 输出 chain-card UI"。Tool 入口让用户/AI 通过自然语言查询任意销售单的缺料。
 *
 * <p>典型调用:
 * <ul>
 *   <li>"F006 销售单 SO-001 缺什么?"</li>
 *   <li>"这单缺多少原料?"</li>
 *   <li>"库存够吗?"</li>
 * </ul>
 *
 * <p>{@code @Lazy} 注入 {@link ShortageAnalysisService} — 防御性, 避免 Tool 框架/AI 子链
 * 启动期间形成隐式循环依赖。Service 本身不依赖任何 AI 组件, 这里 @Lazy 仅做保险。
 */
@Slf4j
@Component
public class ShortageAnalysisTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private ShortageAnalysisService shortageService;

    @Override
    public String getToolName() {
        return "shortage_analyze";
    }

    @Override
    public String getDescription() {
        return "分析销售订单的缺料情况, 返回需要采购的物料 + 推荐的生产任务 + chain-card 渲染提示。"
             + "适用场景: 销售单审批后或 AI 用户问 '这单缺什么 / 缺多少 / 库存够吗'。"
             + "数据为 read-only 快照, 不创建采购单/生产计划。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> salesOrderId = new HashMap<>();
        salesOrderId.put("type", "string");
        salesOrderId.put("description", "销售单 ID (e.g. UUID 或单号)。"
                + "若用户只提了单号 (e.g. 'SO-001'), 必要时先按单号搜出 ID 再调本 Tool。");

        Map<String, Object> properties = new HashMap<>();
        properties.put("salesOrderId", salesOrderId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("salesOrderId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("salesOrderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String salesOrderId = getString(params, "salesOrderId");
        log.info("[shortage_analyze] factoryId={}, salesOrderId={}", factoryId, salesOrderId);

        ShortageReport report = shortageService.analyzeForSalesOrder(factoryId, salesOrderId);
        List<ProcurementSuggestion> procurement = shortageService.suggestProcurement(factoryId, report);
        List<ProductionPlanSuggestion> production = shortageService.suggestProduction(factoryId, report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("salesOrderId", salesOrderId);
        result.put("analysisStatus", report.getAnalysisStatus());
        result.put("fullySatisfied", report.isFullySatisfied());
        result.put("finishedGoodsLineItems",
                report.getFinishedGoodsLineItems() != null ? report.getFinishedGoodsLineItems() : Collections.emptyList());
        result.put("totalRequired",
                report.getTotalRequired() != null ? report.getTotalRequired() : Collections.emptyList());
        result.put("shortage",
                report.getMaterialShortages() != null ? report.getMaterialShortages() : Collections.emptyList());
        result.put("procurementSuggestions", procurement);
        result.put("productionSuggestions", production);
        result.put("analysisSummary", report.getSummary());
        // 前端 (RN AIChat) 识别此 hint 自动渲染 <ShortageChainCard> 而非纯文字 reply
        result.put("displayHint", "chain-card");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
