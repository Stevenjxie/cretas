package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.SettlementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SP11: 结算属性→科目映射 DTO (全 4 处: create / update null-guard / convertToDTO / Entity).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherSubjectMappingDTO {
    private String id;
    private String factoryId;
    private SettlementType settlementType;
    private String businessType;
    private String debitSubjectCode;
    private String debitSubjectName;
    private String creditSubjectCode;
    private String creditSubjectName;
    private String remark;
}
