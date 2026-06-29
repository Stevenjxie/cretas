package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BOM 批量导入结果. inserted=0 且 failed>0 表示整批因校验失败未入库 (原子).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BomBatchImportResult {

    private int inserted;
    private int failed;
    private List<RowResult> rows;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RowResult {
        /** 行号 (从 1 起). */
        private int row;
        private boolean ok;
        /** 失败原因 (ok=false 时); 不静默, 明确每行错因. */
        private String error;
        /** 解析到的物料类型 id (ok=true 时). */
        private String resolvedMaterialTypeId;
        private String materialName;
    }
}
