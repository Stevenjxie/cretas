package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 固定资产折旧凭证 generator.
 *
 * <pre>
 * 借: 6602.02 管理费用-折旧 — 辅助核算 DEPT=deptId (优先) 或 PROJECT=projectId (备选)
 * 贷: 1602 累计折旧
 * </pre>
 *
 * <p>业务: 月底批量计提折旧 (无现成业务单 entity — 通过 Map&lt;String,Object&gt; 输入).
 * 后续 F-DEPRECIATION-SCHEDULE 引入 DepreciationSchedule entity 时切换为 typed.
 *
 * <p>期望 input Map keys:
 * <ul>
 *   <li>businessId: String (e.g. "DEP-202605" — 月份标识)</li>
 *   <li>amount: BigDecimal</li>
 *   <li>voucherDate: LocalDate</li>
 *   <li>assetCategory: String (描述用)</li>
 *   <li><b>deptId</b>: String (可选, Sprint 6 W4-A) — 部门归集</li>
 *   <li><b>projectId</b>: String (可选, Sprint 6 W4-A) — 项目归集 (优先级低于 deptId)</li>
 * </ul>
 *
 * <p>Sprint 6 W4-A: 管理费用 line 加 DEPT 辅助核算 (deptId 优先) 或 PROJECT (projectId 备选).
 * 支持 R-HJ Round 11 §G.1 部门费用账 / 项目核算账.
 * 二者皆空时不挂辅助 (向后兼容老月底折旧 batch).
 */
@Component
public class DepreciationVoucherGenerator extends AbstractVoucherGenerator<Map<String, Object>> {

    public static final String BUSINESS_TYPE = "DEPRECATION";

    @Override
    public VoucherType getType() {
        return VoucherType.DEPRECATION;
    }

    @Override
    public boolean supports(String businessType) {
        return BUSINESS_TYPE.equals(businessType);
    }

    @Override
    protected String extractSourceBusinessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String extractSourceBusinessId(Map<String, Object> input) {
        Object v = input.get("businessId");
        return v != null ? v.toString() : null;
    }

    @Override
    protected LocalDate extractVoucherDate(Map<String, Object> input) {
        Object v = input.get("voucherDate");
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof String s) return LocalDate.parse(s);
        return LocalDate.now();
    }

    @Override
    protected String extractDescription(Map<String, Object> input) {
        Object cat = input.get("assetCategory");
        return "固定资产折旧" + (cat != null ? " — " + cat : "");
    }

    @Override
    public List<VoucherEntry> buildEntries(Map<String, Object> input) {
        BigDecimal amount = toBigDecimal(input.get("amount"));
        String description = extractDescription(input);

        // Sprint 6 W4-A: 部门 / 项目辅助核算 (优先 DEPT, 后 PROJECT, 都空则无)
        String deptId = stringOrNull(input.get("deptId"));
        String projectId = stringOrNull(input.get("projectId"));
        AuxiliaryType auxType = null;
        String auxId = null;
        if (deptId != null) {
            auxType = AuxiliaryType.DEPT;
            auxId = deptId;
        } else if (projectId != null) {
            auxType = AuxiliaryType.PROJECT;
            auxId = projectId;
        }

        return List.of(
                // 管理费用按部门/项目归集 (R-HJ Round 11 §G.1 部门费用账 / 项目核算账)
                debitEntryWithAuxiliary(1, "6602.02", "管理费用-折旧", amount, description, auxType, auxId),
                creditEntry(2, "1602", "累计折旧", amount, "累计折旧增加")
        );
    }

    /**
     * 🔴🔒 #1207: template 路径把 DEPT/PROJECT 辅助核算附到管理费用借方行 (与 buildEntries 一致)。
     * 借方 = 管理费用-折旧 (按部门优先/项目备选归集), 前缀 6602; 累计折旧贷方不挂。
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, Map<String, Object> input) {
        String deptId = stringOrNull(input.get("deptId"));
        String projectId = stringOrNull(input.get("projectId"));
        if (deptId != null) {
            attachAuxiliary(entries, EntrySide.DEBIT, "6602", AuxiliaryType.DEPT, deptId);
        } else if (projectId != null) {
            attachAuxiliary(entries, EntrySide.DEBIT, "6602", AuxiliaryType.PROJECT, projectId);
        }
    }

    private BigDecimal toBigDecimal(Object v) {
        Objects.requireNonNull(v, "DepreciationVoucherGenerator: amount is required");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }

    private String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
