package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.service.finance.ArApService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
public class SalesRecordPaymentTool extends AbstractBusinessTool {

    @Autowired
    private ArApService arApService;

    @Override
    public String getToolName() {
        return "sales_record_payment";
    }

    @Override
    public String getDescription() {
        return "记录客户收款（冲减应收账款）。记录客户实际付款金额和方式。";
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

        Map<String, Object> amount = new HashMap<>();
        amount.put("type", "number");
        amount.put("description", "收款金额（元）");
        properties.put("amount", amount);

        Map<String, Object> paymentMethod = new HashMap<>();
        paymentMethod.put("type", "string");
        paymentMethod.put("description", "付款方式: BANK_TRANSFER(银行转账), CASH(现金), WECHAT(微信), ALIPAY(支付宝), CHECK(支票)");
        paymentMethod.put("enum", Arrays.asList("BANK_TRANSFER", "CASH", "WECHAT", "ALIPAY", "CHECK"));
        properties.put("paymentMethod", paymentMethod);

        Map<String, Object> paymentReference = new HashMap<>();
        paymentReference.put("type", "string");
        paymentReference.put("description", "付款凭证号（可选）");
        properties.put("paymentReference", paymentReference);

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
        BigDecimal amount = getBigDecimal(params, "amount");

        String methodStr = getString(params, "paymentMethod");
        PaymentMethod method = PaymentMethod.BANK_TRANSFER;
        if (methodStr != null) {
            try {
                method = PaymentMethod.valueOf(methodStr);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown payment method: {}, using BANK_TRANSFER", methodStr);
            }
        }

        String paymentReference = getString(params, "paymentReference");
        String remark = getString(params, "remark");
        // Issue #317 fix: AI tool path doesn't scope by SO; pass null so payment
        // posts as on-account customer prepayment without SO link.
        String salesOrderId = getString(params, "salesOrderId");
        Long userId = context.get("userId") != null ? ((Number) context.get("userId")).longValue() : 0L;

        ArApTransaction transaction = arApService.recordArPayment(
                factoryId, counterpartyId, salesOrderId, amount, method,
                paymentReference, userId, remark);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "收款记录创建成功");
        result.put("transactionId", transaction.getId());
        result.put("counterpartyId", counterpartyId);
        result.put("amount", amount);
        result.put("paymentMethod", method.name());
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("counterpartyId", "请问是哪个客户付款？请提供客户ID。");
        questions.put("amount", "请问收款金额是多少？");
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
            "counterpartyId", "客户ID",
            "amount", "金额",
            "paymentMethod", "付款方式",
            "paymentReference", "凭证号"
        );
        return names.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
