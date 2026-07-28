package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AIChat: 批量补凭证 — 扫描 factory 下所有 vflag=UNCREATED 的业务单, 一次性 generate.
 *
 * 输入:
 *   - businessType: 同 VoucherGenerateTool 6 选 1
 *
 * 输出:
 *   - businessType
 *   - count: 实际生成的凭证数 (失败的已置 vflag=FAILED, 不计数)
 *
 * 典型场景: 财务月底批量处理 "本月所有未凭证销售单".
 */
@Slf4j
@Component
public class VoucherBatchGenerateTool extends AbstractBusinessTool {

    @Autowired
    private VoucherService voucherService;

    @Override
    public String getToolName() {
        return "voucher_batch_generate";
    }

    @Override
    public String getDescription() {
        return "批量补凭证: 扫描指定 businessType 下所有 vflag=UNCREATED 的业务单, " +
                "逐个生成对应凭证. 失败的业务单 vflag 置 FAILED, 不阻塞其他. " +
                "返回实际生成数量.";
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
                                "description", "要批量补凭证的业务单类型"
                        )
                ),
                "required", List.of("businessType")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("businessType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String businessType = getString(params, "businessType");
        int count = voucherService.batchCreateForFactory(factoryId, businessType);
        return buildSimpleResult(
                "批量凭证生成完成",
                Map.of(
                        "businessType", businessType,
                        "count", count
                )
        );
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
