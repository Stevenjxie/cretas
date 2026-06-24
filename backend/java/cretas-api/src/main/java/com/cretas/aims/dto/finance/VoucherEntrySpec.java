package com.cretas.aims.dto.finance;

import java.math.BigDecimal;

/**
 * createManual 入参: 一条凭证分录的规格 (debit XOR credit, 另一方传 null 或 0)。
 */
public record VoucherEntrySpec(
        String subjectCode,
        String subjectName,
        BigDecimal debit,
        BigDecimal credit,
        String description) {
}
