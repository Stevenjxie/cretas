package com.cretas.aims.dto.supplier;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierImportErrorReportRow {
    @ExcelProperty(value = "Excel原行号", index = 0) @ColumnWidth(14)
    private Integer rowNumber;
    @ExcelProperty(value = "供应商名称", index = 1) @ColumnWidth(24)
    private String supplierName;
    @ExcelProperty(value = "分类", index = 2) @ColumnWidth(14)
    private String classification;
    @ExcelProperty(value = "错误原因", index = 3) @ColumnWidth(60)
    private String reason;
}
