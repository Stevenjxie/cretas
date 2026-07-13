package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

import java.util.Map;

/**
 * {@code POST /order-import/preview-paste} 请求体 —— 从 Excel 连表头复制的一段文本直接预检。
 *
 * <p>{@code rawText} 是用户从 Excel 选中含表头区域后 Ctrl+C 的原文（默认 TSV / 制表符分隔，
 * 也容忍 CSV）。后端 {@code LogisticsHeaderMatcher} 按同一套字典识别列 → 目标字段，
 * 与文件上传完全共用一条「识别 → 确认映射 → 预览」管线。
 */
@Data
public class PastePreviewRequest {

    /** 从 Excel 复制的原始文本（第一行为表头）。 */
    private String rawText;

    /** 兜底业务日期（粘贴内容无「业务日期」列时用；留空则由校验层兜底当天）。 */
    private String businessDate;

    /**
     * 用户在「映射确认面板」手动调整的覆盖映射：列索引 → 目标字段名
     * （{@link LogisticsOrderImportRow} 字段名）。值为 {@code "__ignore__"} 表示忽略该列。
     * 首次预览可为空（用自动识别）。
     */
    private Map<Integer, String> columnMapping;
}
