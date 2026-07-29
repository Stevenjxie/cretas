package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AIChat: 为指定业务单生成财务凭证.
 *
 * 输入:
 *   - businessType: SALES_ORDER / PURCHASE_ORDER / RETURN_ORDER /
 *                   INTERNAL_TRANSFER / WASTAGE_RECORD / PAYROLL_RECORD
 *   - businessId: 业务单 UUID (PAYROLL_RECORD 用 Long-as-String)
 *
 * 输出:
 *   - voucherNumber, voucherType, totalDebit, totalCredit, entries
 *
 * 已存在凭证 (idempotent hit) 直接返回 existing 信息, 不抛错.
 */
@Slf4j
@Component
public class VoucherGenerateTool extends AbstractBusinessTool {

    @Autowired
    private VoucherService voucherService;

    @Override
    public String getToolName() {
        return "voucher_generate";
    }

    @Override
    public String getDescription() {
        return "为业务单生成财务凭证. 输入 businessType (SALES_ORDER / PURCHASE_ORDER / " +
                "RETURN_ORDER / INTERNAL_TRANSFER / WASTAGE_RECORD / PAYROLL_RECORD) 和 businessId, " +
                "自动 dispatch 到对应 VoucherGenerator, 返回凭证号 + 借贷分录预览. " +
                "Idempotent: 已存在直接返回 existing voucher.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "businessType", Map.of(
                                "type", "string",
                                "enum", List.of("SALES_ORDER", "PURCHASE_ORDER", "RETURN_ORDER",
                                        "INTERNAL_TRANSFER", "WASTAGE_RECORD", "PAYROLL_RECORD"),
                                "description", "业务单类型"
                        ),
                        "businessId", Map.of(
                                "type", "string",
                                "description", "业务单 ID"
                        )
                ),
                "required", List.of("businessType", "businessId")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("businessType", "businessId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String businessType = getString(params, "businessType");
        String businessId = getString(params, "businessId");

        Voucher voucher = voucherService.createFromBusiness(factoryId, businessType, businessId);
        return buildSimpleResult("凭证已生成", toResult(voucher));
    }

    private Map<String, Object> toResult(Voucher v) {
        Map<String, Object> r = new HashMap<>();
        r.put("voucherId", v.getId());
        r.put("voucherNumber", v.getVoucherNumber());
        r.put("voucherType", v.getVoucherType().name());
        r.put("voucherDate", v.getVoucherDate() != null ? v.getVoucherDate().toString() : null);
        r.put("status", v.getStatus().name());
        r.put("totalDebit", v.getTotalDebit());
        r.put("totalCredit", v.getTotalCredit());
        r.put("description", v.getDescription());
        r.put("entries", v.getEntries().stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .map(this::entryToMap)
                .toList());
        return r;
    }

    private Map<String, Object> entryToMap(VoucherEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("lineNo", e.getLineNo());
        m.put("subjectCode", e.getSubjectCode());
        m.put("subjectName", e.getSubjectName());
        m.put("debit", e.getDebit());
        m.put("credit", e.getCredit());
        m.put("description", e.getDescription());
        m.put("costCenter", e.getCostCenter());
        return m;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
