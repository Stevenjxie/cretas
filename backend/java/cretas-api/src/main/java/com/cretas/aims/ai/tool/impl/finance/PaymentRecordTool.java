package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.service.finance.PaymentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PaymentRecordTool extends AbstractBusinessTool {

    @Autowired
    private PaymentRecordService paymentRecordService;

    @Override
    public String getToolName() { return "finance_payment_record"; }

    @Override
    public String getDescription() { return "录入客户收款记录，关联销售订单"; }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "salesOrderId", Map.of("type", "string", "description", "销售订单ID"),
                "amount", Map.of("type", "number", "description", "收款金额"),
                "paymentMethod", Map.of("type", "string", "description", "支付方式: BANK_TRANSFER/CASH/WECHAT/ALIPAY"),
                "paymentDate", Map.of("type", "string", "description", "收款日期 YYYY-MM-DD"),
                "paymentReference", Map.of("type", "string", "description", "银行流水号"),
                "remark", Map.of("type", "string", "description", "备注")
        ), "required", List.of("salesOrderId", "amount"));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of("salesOrderId", "amount"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String methodStr = getString(params, "paymentMethod");
        PaymentMethod method = methodStr != null ? PaymentMethod.valueOf(methodStr) : PaymentMethod.BANK_TRANSFER;
        String dateStr = getString(params, "paymentDate");
        LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : null;

        var record = paymentRecordService.recordPayment(
                factoryId,
                getString(params, "salesOrderId"),
                new BigDecimal(params.get("amount").toString()),
                method, date,
                getString(params, "paymentReference"),
                null, // receiptUrl via API upload, not AI tool
                getLong(params, "userId"),
                getString(params, "remark"));
        return buildSimpleResult("收款记录已创建", Map.of(
                "paymentNumber", record.getPaymentNumber(),
                "amount", record.getAmount(),
                "status", record.getStatus().name()));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
