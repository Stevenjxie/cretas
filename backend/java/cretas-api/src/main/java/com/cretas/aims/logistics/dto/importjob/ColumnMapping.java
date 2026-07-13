package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

/**
 * 单个源列的识别结果 — 「源表头 → 目标字段」的一条。前端映射确认面板逐列渲染。
 *
 * <p>{@code mappedField} 是 {@link LogisticsOrderImportRow} 的 Java 字段名（storeName/address/…），
 * 空表示未识别；与前端 override 契约（列索引 → 字段名）用同一套 id。
 */
@Data
public class ColumnMapping {

    /** 源列索引（0-based，对应原始表格的列）。 */
    private int index;

    /** 源表头原文（未归一化，用于展示）。 */
    private String header;

    /** 首个数据行在该列的样例值（可空，供用户核对映射对不对）。 */
    private String sampleValue;

    /** 识别到的目标字段名（{@link LogisticsOrderImportRow} 字段名），未识别为 null。 */
    private String mappedField;

    /** 置信度：1.0=表头别名精确命中，0.7=子串命中，未识别为 0。 */
    private double confidence;

    /** 该列子串命中了多个字段（语义歧义），前端高亮提示用户手动确认。 */
    private boolean ambiguous;
}
