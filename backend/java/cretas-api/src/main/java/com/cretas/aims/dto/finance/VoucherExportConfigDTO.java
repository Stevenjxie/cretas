package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.VoucherTargetSystem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SP11: 凭证导出配置 DTO (全 4 处: create set / update null-guard / convertToDTO / Entity).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherExportConfigDTO {
    private String id;
    private String factoryId;
    private VoucherTargetSystem targetSystem;
    private String colVoucherNo;
    private String colDate;
    private String colSummary;
    private String colSubjectCode;
    private String colSubjectName;
    private String colDebit;
    private String colCredit;
    private String colAuxiliary;
    private String colCurrency;
    private Boolean isActive;
}
