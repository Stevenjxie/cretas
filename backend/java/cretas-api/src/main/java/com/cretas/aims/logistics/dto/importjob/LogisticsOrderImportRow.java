package com.cretas.aims.logistics.dto.importjob;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 物流订单导入模板行 — 后端唯一事实源 (handoff §8.1)。
 *
 * <p>字段全声明为 {@link String}：EasyExcel 读取时按表头文本匹配列（不依赖列顺序），
 * 数值/日期解析和校验交给 {@code LogisticsOrderImportServiceImpl} 逐行做，
 * 这样才能给出精确的行号+列名+错误原因 (spec §8.2)，而不是让 EasyExcel 的
 * 类型转换异常中断整个文件解析。
 *
 * <p>{@link com.cretas.aims.utils.ExcelUtil#generateTemplate} 用同一个类生成
 * 空模板（仅表头），保证「模板下载」与「预检解析」用的是同一份列定义 — 不允许
 * Vue 端另外维护一份表头 (handoff §8.2 "下载模板必须与后端实际校验字段一致")。
 */
@Data
public class LogisticsOrderImportRow {

    @ExcelProperty("业务日期")
    private String businessDate;

    @ExcelProperty("门店编码")
    private String storeCode;

    @ExcelProperty("门店名称")
    private String storeName;

    @ExcelProperty("配送地址")
    private String address;

    @ExcelProperty("件数")
    private String pieces;

    @ExcelProperty("箱数")
    private String boxes;

    @ExcelProperty("重量kg")
    private String weightKg;

    @ExcelProperty("体积m³")
    private String volumeCbm;

    @ExcelProperty("配送开始时间")
    private String windowStart;

    @ExcelProperty("配送结束时间")
    private String windowEnd;

    @ExcelProperty("经度")
    private String longitude;

    @ExcelProperty("纬度")
    private String latitude;

    @ExcelProperty("区域")
    private String areaCode;
}
