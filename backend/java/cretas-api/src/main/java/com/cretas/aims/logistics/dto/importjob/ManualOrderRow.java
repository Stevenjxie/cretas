package com.cretas.aims.logistics.dto.importjob;

import lombok.Data;

/**
 * {@code POST /order-import/manual} 请求体内的单行订单 — 字段集合与
 * {@link LogisticsOrderImportRow} 一一对应 (镜像同一份校验/落库逻辑，见
 * {@code LogisticsOrderImportServiceImpl#buildPreviewFromRawRows})。
 *
 * <p>数值/坐标字段刻意用 {@link String}（而非 {@code Integer}/{@code BigDecimal}）：
 * 前端表单原样传字符串，交给与 xlsx/csv 路径完全一致的宽松解析+逐行错误收集
 * (spec §8.2 精确行号+列名+原因)，不在 DTO 层做提前类型收紧导致两条路径校验行为分叉。
 */
@Data
public class ManualOrderRow {
    private String storeCode;
    private String storeName;
    private String address;
    private String pieces;
    private String boxes;
    private String weightKg;
    private String volumeCbm;
    private String windowStart;
    private String windowEnd;
    private String longitude;
    private String latitude;
    private String areaCode;
}
