package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表头识别的整体结果 — 回给前端渲染「映射确认面板」。
 *
 * <p>{@link #autoConfident} 为 true 时前端可折叠成一行「已自动识别，一键确认」；
 * 为 false（有必填字段没被任何列覆盖，或有歧义列）时前端展开面板、阻断导入直到用户补齐映射。
 */
@Data
public class ColumnMappingResult {

    /** 逐列识别结果（顺序与源列一致）。 */
    private List<ColumnMapping> columns = new ArrayList<>();

    /**
     * 没被任何列覆盖的必填字段（{@link LogisticsOrderImportRow} 字段名）。
     * 件数/箱数二选一都缺时用伪 id {@code "quantity"} 表示（前端标签「件数或箱数」）。
     */
    private List<String> unmappedRequiredFields = new ArrayList<>();

    /** 全部必填覆盖且无歧义列 → 前端可一键确认。 */
    private boolean autoConfident;
}
