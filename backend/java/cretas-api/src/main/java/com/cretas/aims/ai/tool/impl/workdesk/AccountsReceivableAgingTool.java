package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.service.finance.ArApService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应收账龄分析 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>Wrap {@link ArApService#getAgingAnalysis} for CUSTOMER (AR). 返 6 桶账龄 (0-30 / 31-60 / 61-90 /
 * 91-180 / 181-365 / 365+ 天). 财务月结看应收老化情况.
 *
 * <p>读路径无副作用. 防呆 R2 — 含 bucket label + amount + count + 警告 (60+ 天高风险).
 *
 * <p>Intent Code: {@code ACCOUNTS_RECEIVABLE_AGING}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class AccountsReceivableAgingTool extends AbstractBusinessTool {

    @Autowired
    private ArApService arApService;

    @Override
    public String getToolName() {
        return "accounts_receivable_aging";
    }

    @Override
    public String getDescription() {
        return "应收账龄 6 桶分析 (0-30/31-60/61-90/91-180/181-365/365+ 天). 显每桶客户数 + 总金额, "
                + "60+ 天为高风险. LLM 触发场景: 用户问 '应收账龄' / '账龄分析' / '60 天以上欠款' / "
                + "'坏账风险' / 'AR aging' / '应收老化'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
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
        log.info("accounts_receivable_aging — factory={}", factoryId);

        List<Map<String, Object>> aging = arApService.getAgingAnalysis(factoryId, CounterpartyType.CUSTOMER);

        // 简单聚合: 60+ 天高风险总额
        java.math.BigDecimal highRiskAmount = java.math.BigDecimal.ZERO;
        long highRiskCount = 0;
        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        long totalCount = 0;
        for (Map<String, Object> bucket : aging) {
            Object amt = bucket.get("amount");
            Object cnt = bucket.get("count");
            java.math.BigDecimal a = amt instanceof Number
                    ? new java.math.BigDecimal(amt.toString()) : java.math.BigDecimal.ZERO;
            long c = cnt instanceof Number ? ((Number) cnt).longValue() : 0L;
            totalAmount = totalAmount.add(a);
            totalCount += c;
            Object label = bucket.get("bucket");
            String labelStr = label != null ? label.toString() : "";
            if (labelStr.contains("61") || labelStr.contains("91")
                    || labelStr.contains("181") || labelStr.contains("365")) {
                highRiskAmount = highRiskAmount.add(a);
                highRiskCount += c;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("counterpartyType", "CUSTOMER");
        data.put("aging", aging);
        data.put("totalAmount", totalAmount);
        data.put("totalCount", totalCount);
        data.put("highRiskAmount", highRiskAmount);
        data.put("highRiskCount", highRiskCount);
        data.put("actionHint", "/finance/ar-ap?tab=aging&type=CUSTOMER");

        String warning = highRiskCount > 0
                ? String.format(" 警告: 60+ 天高风险 %d 客户共 ¥%s, 建议立即催收",
                        highRiskCount, highRiskAmount)
                : "";
        // Sprint 12: emoji-free (B-end emoji=0) + ≥80-char message.
        StringBuilder msg = new StringBuilder();
        msg.append(String.format("本月应收账龄汇总: 共 %d 客户 / ¥%s 待收", totalCount, totalAmount));
        if (totalCount == 0) {
            msg.append(". 当前没有应收账款待催收记录, 建议: 1. 检查本月销售出库已开票数量; 2. 复核客户对账单生成情况; 3. 联系销售确认收款节点。");
        } else {
            msg.append(warning);
            msg.append(". 建议: 1. 优先催收 60+ 天逾期客户; 2. 与客户对账确认; 3. 月底前完成催收回款。");
        }
        return buildSimpleResult(msg.toString(), data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
