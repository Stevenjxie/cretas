package com.cretas.aims.logistics.dto.importjob;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 物流订单导入模板【下载】用的精简列 —— 只保留最必要的 6 列（订单号 / 门店名称 / 配送地址 /
 * 箱数 / 重量kg / 体积m³）。
 *
 * <p>业务日期、件数、配送开始/结束时间、经度、纬度、区域都是选填，不放进下载模板，避免客户看着一堆
 * 列不知道填什么。<b>导入解析仍用 {@link LogisticsOrderImportRow}（全列）</b>：客户自己的文件带这些
 * 额外列也能正常读（表头匹配，缺失则按选填留空），所以模板精简不影响导入兼容性。
 */
@Data
public class LogisticsOrderTemplateRow {

    @ExcelProperty("订单号")
    private String storeCode;

    @ExcelProperty("门店名称")
    private String storeName;

    @ExcelProperty("配送地址")
    private String address;

    @ExcelProperty("箱数")
    private String boxes;

    @ExcelProperty("重量kg")
    private String weightKg;

    @ExcelProperty("体积m³")
    private String volumeCbm;
}
