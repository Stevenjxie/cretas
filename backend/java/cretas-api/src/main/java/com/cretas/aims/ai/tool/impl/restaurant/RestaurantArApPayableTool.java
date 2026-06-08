package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 餐饮供应商应付账款查询工具 (GAP-06-C, QA 组5).
 *
 * <p>回答 "应付多少 / 应付账款 / 欠供应商多少" 类问题：返回当前工厂的供应商应付账款余额
 * (AP_INVOICE - AP_PAYMENT 净额)，以及最近 5 笔待确认的 AP 挂账明细。
 *
 * <p><b>区分 "佣金应付" vs "供应商应付"</b>：
 * <ul>
 *   <li>{@code RestaurantRepCommissionQueryTool} → 营销员月度提成 (我的提成 / 本月提成多少)</li>
 *   <li>{@code RestaurantArApPayableTool} (本类) → 供应商应付账款余额</li>
 * </ul>
 * 短语路由在 {@code IntentKnowledgeBase.restaurantPhraseMapping} 中确定性区分两者，
 * 不依赖语义评分。
 *
 * <p>对应意图: {@code RESTAURANT_ARAP_PAYABLE_QUERY}
 *
 * @author Cretas Team
 * @since 2026-06-09 (fix QA-组5 GAP-06-C)
 */
@Slf4j
@Component
public class RestaurantArApPayableTool extends AbstractBusinessTool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 明细最多返回 5 条最近 AP_INVOICE，避免 AI 回复过长 */
    private static final int DETAIL_LIMIT = 5;

    @Autowired
    private ArApTransactionRepository arApTransactionRepository;

    @Override
    public String getToolName() {
        return "restaurant_arap_payable_query";
    }

    @Override
    public String getDescription() {
        return "查询餐厅供应商应付账款余额（欠款 / 应付多少 / 应付账款）：" +
                "返回当前工厂对供应商的净应付余额（AP挂账 - AP已付），以及最近 " + DETAIL_LIMIT + " 笔" +
                "AP挂账明细（交易编号、对手方、金额、到期日）。" +
                "适用场景：应付多少 / 应付账款 / 欠款多少 / 供应商应付 / 要付多少钱。" +
                "注意：此工具查询供应商应付账款，不查询营销员提成应付。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of(); // 无必填参数: factoryId 由框架注入
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        log.debug("[restaurant_arap_payable_query] factoryId={}", factoryId);

        // 1. 应付余额合计
        BigDecimal totalPayable = arApTransactionRepository.sumPayables(factoryId);
        if (totalPayable == null) {
            totalPayable = BigDecimal.ZERO;
        }

        // 2. 最近 DETAIL_LIMIT 笔 AP 明细 (含挂账和付款, 按交易日期倒序)
        List<ArApTransaction> recent = arApTransactionRepository
                .findByFactoryIdAndCounterpartyTypeOrderByTransactionDateDesc(
                        factoryId, CounterpartyType.SUPPLIER,
                        PageRequest.of(0, DETAIL_LIMIT))
                .getContent();

        // 3. 逾期应付
        List<ArApTransaction> overdue = arApTransactionRepository
                .findOverduePayables(factoryId, LocalDate.now());

        // 4. 构建自然语言回复
        StringBuilder message = new StringBuilder();
        message.append("当前供应商应付账款余额: ¥").append(totalPayable.toPlainString());
        if (totalPayable.compareTo(BigDecimal.ZERO) == 0) {
            message.append("（暂无欠款）");
        }
        message.append("。");

        if (!overdue.isEmpty()) {
            message.append(" 其中已逾期 ").append(overdue.size()).append(" 笔，请尽快安排付款。");
        }

        if (!recent.isEmpty()) {
            message.append(" 最近 ").append(recent.size()).append(" 笔交易如下：");
        }

        // 5. 构建数据对象
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalPayable", totalPayable);
        data.put("overdueCount", overdue.size());

        List<Map<String, Object>> details = new ArrayList<>();
        for (ArApTransaction tx : recent) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("transactionNumber", tx.getTransactionNumber());
            item.put("counterpartyName", tx.getCounterpartyName());
            item.put("transactionType", tx.getTransactionType().getDisplayName());
            item.put("amount", tx.getAmount());
            item.put("transactionDate", tx.getTransactionDate() != null
                    ? tx.getTransactionDate().format(DATE_FMT) : null);
            item.put("dueDate", tx.getDueDate() != null
                    ? tx.getDueDate().format(DATE_FMT) : null);
            details.add(item);
        }
        data.put("recentTransactions", details);

        return buildSimpleResult(message.toString(), data);
    }
}
