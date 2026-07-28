package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 收付款状态查询 Tool
 *
 * 查询应收账款、应付账款及账龄分析数据。
 * 对应意图: PAYMENT_STATUS_QUERY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PaymentStatusTool extends AbstractBusinessTool {

    @Autowired
    private ReportService reportService;

    @Override
    public String getToolName() {
        return "payment_status";
    }

    @Override
    public String getDescription() {
        return "查询收付款状态，包含应收账款、应付账款及账龄分析。" +
                "适用场景：收付款查询、应收应付查询、账龄分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行收付款状态查询 - 工厂ID: {}", factoryId);

        Map<String, Object> finance = reportService.getFinanceReport(factoryId,
                LocalDate.now().minusDays(30), LocalDate.now());

        Map<String, Object> result = new HashMap<>();
        result.put("financeData", finance);
        result.put("queryType", "payment_status");
        result.put("message", "收付款状态查询完成，包含应收账款、应付账款及账龄分析数据。");

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
