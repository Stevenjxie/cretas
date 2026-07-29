package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.SalesOpportunityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商机漏斗统计 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>聚合 SalesOpportunity 按 stage 分桶: count + totalValue + expectedValue
 * (value × probability / 100). 用于财务月度复盘 "我们 pipeline 上有多少潜在收入?".
 *
 * <p>读路径无副作用. 防呆 R2 — 每桶含 stage / count / 总金额 / 加权预期金额.
 *
 * <p>Intent Code: {@code OPPORTUNITY_FUNNEL_STATS}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class OpportunityFunnelStatsTool extends AbstractBusinessTool {

    @Autowired
    private SalesOpportunityRepository salesOpportunityRepository;

    @Override
    public String getToolName() {
        return "opportunity_funnel_stats";
    }

    @Override
    public String getDescription() {
        return "聚合销售商机 8 阶段 funnel 统计 (LEAD → QUALIFIED → DEMO → PROPOSAL → NEGOTIATE → "
                + "VERBAL → CLOSED_WON/LOST), 每阶段 count + 总金额 + 加权预期金额 (value × probability). "
                + "LLM 触发场景: 用户问 '商机漏斗' / '商机分布' / '我们 pipeline 多少钱' / "
                + "'各阶段商机数' / 'funnel'. 可选 ownerId. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> ownerId = new HashMap<>();
        ownerId.put("type", "integer");
        ownerId.put("description", "可选 — 仅统计指定业务员 User.id");
        properties.put("ownerId", ownerId);

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
        Long ownerId = getLong(params, "ownerId");

        log.info("opportunity_funnel_stats — factory={} ownerId={}", factoryId, ownerId);

        List<SalesOpportunityRepository.FunnelStat> stats = salesOpportunityRepository
                .getFunnelStats(factoryId, ownerId);

        // 按 stage flow order 排序 (LEAD → CLOSED), 确保 missing stage 也显 count=0
        Map<OpportunityStage, SalesOpportunityRepository.FunnelStat> byStage = new HashMap<>();
        for (SalesOpportunityRepository.FunnelStat s : stats) {
            byStage.put(s.getStage(), s);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalCount = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalExpected = BigDecimal.ZERO;
        BigDecimal activeValue = BigDecimal.ZERO;
        BigDecimal activeExpected = BigDecimal.ZERO;
        long activeCount = 0;

        for (OpportunityStage stage : OpportunityStage.values()) {
            SalesOpportunityRepository.FunnelStat s = byStage.get(stage);
            long cnt = s != null && s.getCnt() != null ? s.getCnt() : 0L;
            BigDecimal tv = s != null && s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO;
            BigDecimal ev = s != null && s.getExpectedValue() != null
                    ? s.getExpectedValue() : BigDecimal.ZERO;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("stageDisplay", stage.getDisplayName());
            row.put("count", cnt);
            row.put("totalValue", tv.setScale(2, RoundingMode.HALF_UP));
            row.put("expectedValue", ev.setScale(2, RoundingMode.HALF_UP));
            rows.add(row);

            totalCount += cnt;
            totalValue = totalValue.add(tv);
            totalExpected = totalExpected.add(ev);
            if (!stage.isTerminal()) {
                activeCount += cnt;
                activeValue = activeValue.add(tv);
                activeExpected = activeExpected.add(ev);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("ownerId", ownerId);
        data.put("funnel", rows);
        data.put("totalCount", totalCount);
        data.put("totalValue", totalValue.setScale(2, RoundingMode.HALF_UP));
        data.put("totalExpected", totalExpected.setScale(2, RoundingMode.HALF_UP));
        data.put("activeCount", activeCount);
        data.put("activeValue", activeValue.setScale(2, RoundingMode.HALF_UP));
        data.put("activeExpected", activeExpected.setScale(2, RoundingMode.HALF_UP));
        data.put("actionHint", "/crm/opportunity/funnel");

        String message = String.format(
                "🎯 商机漏斗: 活跃 %d 个 (¥%s 预估 / ¥%s 加权), 全部 %d 个 (¥%s 预估)",
                activeCount,
                activeValue.setScale(2, RoundingMode.HALF_UP),
                activeExpected.setScale(2, RoundingMode.HALF_UP),
                totalCount,
                totalValue.setScale(2, RoundingMode.HALF_UP));
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
