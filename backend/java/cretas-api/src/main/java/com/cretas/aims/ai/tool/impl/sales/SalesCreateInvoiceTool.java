package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.finance.RecordTransactionRequest;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.service.finance.ArApService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class SalesCreateInvoiceTool extends AbstractBusinessTool {

    @Autowired
    private ArApService arApService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "sales_create_invoice";
    }

    @Override
    public String getDescription() {
        return "创建应收账款记录（开票/挂账）。记录客户应付金额，支持关联销售订单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> counterpartyId = new HashMap<>();
        counterpartyId.put("type", "string");
        counterpartyId.put("description", "客户ID");
        properties.put("counterpartyId", counterpartyId);

        Map<String, Object> orderId = new HashMap<>();
        orderId.put("type", "string");
        orderId.put("description", "关联的销售订单ID（可选）");
        properties.put("orderId", orderId);

        Map<String, Object> amount = new HashMap<>();
        amount.put("type", "number");
        amount.put("description", "开票金额（元）");
        properties.put("amount", amount);

        Map<String, Object> dueDate = new HashMap<>();
        dueDate.put("type", "string");
        dueDate.put("description", "到期日，格式: yyyy-MM-dd");
        properties.put("dueDate", dueDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("counterpartyId", "amount"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("counterpartyId", "amount");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String counterpartyId = getString(params, "counterpartyId");
        String orderId = getString(params, "orderId");
        BigDecimal amount = getBigDecimal(params, "amount");

        LocalDate dueDate = null;
        String dueDateStr = getString(params, "dueDate");
        if (dueDateStr != null) {
            dueDate = LocalDate.parse(dueDateStr, DATE_FORMATTER);
        }

        String remark = getString(params, "remark");
        Long userId = context.get("userId") != null ? ((Number) context.get("userId")).longValue() : 0L;

        ArApTransaction transaction = arApService.recordReceivable(
                factoryId, counterpartyId, orderId, amount, dueDate, userId, remark);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "应收记录创建成功");
        result.put("transactionId", transaction.getId());
        result.put("counterpartyId", counterpartyId);
        result.put("amount", amount);
        result.put("type", transaction.getTransactionType());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("counterpartyId", "请问给哪个客户开票？请提供客户ID。");
        questions.put("amount", "请问开票金额是多少？");
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
            "counterpartyId", "客户ID",
            "amount", "金额",
            "orderId", "订单ID",
            "dueDate", "到期日"
        );
        return names.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
