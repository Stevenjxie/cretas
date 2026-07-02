package com.cretas.aims.dto.factory;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 库存盘点 Excel 批量导入/导出行 DTO（EasyExcel 列映射）。
 *
 * <p>用于「盘点批量导入」流程：
 * <ul>
 *   <li>导出模板：系统按仓库导出当前账面库存，{@link #actualQty 实盘数量} 列留空待仓管填写</li>
 *   <li>导入回填：仓管填好实盘数量后上传，系统按 {@link #batchNumber 批次号} 匹配比对</li>
 * </ul>
 *
 * <p>匹配键 = 批次号（{@code material_batches.batch_number}，工厂内唯一）。
 * 物料名称 / 仓库 / 账面数量 / 单位 仅供仓管人工核对，导入时不参与匹配。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@HeadRowHeight(22)
@ContentRowHeight(18)
public class StocktakeImportRowDTO {

    @ExcelProperty(value = "物料名称", index = 0)
    @ColumnWidth(24)
    private String materialName;

    /** 匹配键：批次号（必填，工厂内唯一）。*/
    @ExcelProperty(value = "批次号*", index = 1)
    @ColumnWidth(28)
    private String batchNumber;

    @ExcelProperty(value = "仓库", index = 2)
    @ColumnWidth(18)
    private String warehouseName;

    /** 账面数量（系统导出时填充，仅供核对，导入时忽略以系统实时账面为准）。*/
    @ExcelProperty(value = "账面数量", index = 3)
    @ColumnWidth(14)
    private BigDecimal systemQty;

    @ExcelProperty(value = "单位", index = 4)
    @ColumnWidth(10)
    private String unit;

    /** 实盘数量（仓管填写。留空 = 未盘点，导入时跳过不校正）。*/
    @ExcelProperty(value = "实盘数量", index = 5)
    @ColumnWidth(14)
    private BigDecimal actualQty;
}
