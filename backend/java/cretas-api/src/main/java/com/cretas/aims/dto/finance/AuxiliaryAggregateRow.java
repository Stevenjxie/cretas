package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.AuxiliaryType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 凭证分录按 auxiliaryEntityId 聚合后的一行结果.
 *
 * <p>Sprint 6 W4-A: 支持 R-HJ Round 11 §G.1 多维度明细账 — 客户应收 / 供应商应付 /
 * 部门费用 / 项目核算 / 库存批次 / 职员工资.
 *
 * <p>语义:
 * <ul>
 *   <li>{@code netBalance} = sum(debit) - sum(credit), 表"借方余额".
 *     CUSTOMER 应收账款 → 正值 = 客户欠我们 (期望); SUPPLIER 应付账款 → 负值 = 我们欠供应商.</li>
 *   <li>{@code entryCount} = 该 entity 在期间的分录条数 (含未过账 / 已过账, 由 status filter 决定).</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "凭证按辅助核算实体聚合一行")
public class AuxiliaryAggregateRow {

    @Schema(description = "辅助核算类型 (CUSTOMER / SUPPLIER / DEPT / EMPLOYEE / PROJECT / INVENTORY / OUTSOURCER)")
    private AuxiliaryType auxiliaryType;

    @Schema(description = "辅助核算实体 PK (Customer UUID / Employee Long-as-String / ...)")
    private String auxiliaryEntityId;

    @Schema(description = "期间内借方总额 (含所有过账状态除 VOID)")
    private BigDecimal totalDebit;

    @Schema(description = "期间内贷方总额 (含所有过账状态除 VOID)")
    private BigDecimal totalCredit;

    @Schema(description = "净余额 (debit - credit), 借方余额为正")
    private BigDecimal netBalance;

    @Schema(description = "分录条数")
    private Long entryCount;
}
