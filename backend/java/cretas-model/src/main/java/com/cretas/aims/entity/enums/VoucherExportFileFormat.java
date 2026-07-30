package com.cretas.aims.entity.enums;

/**
 * 凭证导入模板文件格式.
 *
 * <p>不同金蝶版本的原生"引入"工具接受不同文件格式:
 * <ul>
 *   <li>{@link #XLSX} — 金蝶云星空 "凭证导入" (现代 xlsx). KIS 也可用 xlsx 中转, 但部分 KIS 版本不认。</li>
 *   <li>{@link #XLS} — 金蝶 KIS专业版 "文件→引入标准格式凭证" 接受 legacy .xls (BIFF), 不认 .xlsx。</li>
 *   <li>{@link #DBF} — 金蝶 KIS标准版/迷你版/K3 原生 "引入" 走 dBASE .dbf 交换文件。</li>
 * </ul>
 *
 * <p>未指定时默认 {@link #XLSX} (向后兼容: 老 caller 只传 targetSystem 不传 format).
 */
public enum VoucherExportFileFormat {
    XLSX,
    XLS,
    DBF
}
