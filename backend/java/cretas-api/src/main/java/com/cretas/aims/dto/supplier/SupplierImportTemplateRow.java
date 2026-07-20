package com.cretas.aims.dto.supplier;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/** Standard supplier import template. Required columns are marked with *. */
@Data
public class SupplierImportTemplateRow {
    @ExcelProperty(value = "供应商名称*", index = 0) @ColumnWidth(24)
    private String name;
    @ExcelProperty(value = "联系人*", index = 1) @ColumnWidth(16)
    private String contactPerson;
    @ExcelProperty(value = "联系电话*", index = 2) @ColumnWidth(20)
    private String phone;
    @ExcelProperty(value = "地址*", index = 3) @ColumnWidth(36)
    private String address;
    @ExcelProperty(value = "邮箱", index = 4) @ColumnWidth(28)
    private String email;
    @ExcelProperty(value = "银行账户", index = 5) @ColumnWidth(24)
    private String bankAccount;
    @ExcelProperty(value = "税号", index = 6) @ColumnWidth(24)
    private String taxNumber;
    @ExcelProperty(value = "供应商编码", index = 7) @ColumnWidth(18)
    private String supplierCode;
    @ExcelProperty(value = "备注", index = 8) @ColumnWidth(32)
    private String notes;
}
