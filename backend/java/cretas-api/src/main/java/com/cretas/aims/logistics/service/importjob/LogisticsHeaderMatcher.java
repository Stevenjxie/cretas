package com.cretas.aims.logistics.service.importjob;

import com.cretas.aims.logistics.dto.importjob.ColumnMapping;
import com.cretas.aims.logistics.dto.importjob.ColumnMappingResult;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物流订单导入 — 任意 Excel 表头 → 目标字段 的识别引擎（纯函数、无 Spring/DB 依赖，可单测）。
 *
 * <p>动机：客户真实 Excel 表头五花八门（「客户」而非「门店名称」、「数量」而非「件数」…）。
 * 老逻辑 {@code LogisticsOrderImportServiceImpl.parseTable} 只做「去空白后精确匹配固定表头」，
 * 匹配不上整列漏读或抛「表头无法识别」。本类用**同义词字典 + 归一化**做识别，识别不全时
 * 返回部分映射交给前端「映射确认面板」兜（不抛异常），并支持用户**显式覆盖映射**。
 *
 * <p>目标字段 id = {@link LogisticsOrderImportRow} 的 Java 字段名（storeName/address/…），
 * 与前端 override 契约（列索引 → 字段名）统一。**不引入 LLM** —— 物流订单字段集小且固定，
 * 字典 + 人工确认足够，确定性/免费/防呆（见 spec 2026-07-13）。
 */
public final class LogisticsHeaderMatcher {

    private LogisticsHeaderMatcher() {
    }

    /** 硬必填字段（每个都必须被某列覆盖）。件数/箱数二选一另算（见 {@link #QUANTITY_FIELDS}）。 */
    private static final List<String> HARD_REQUIRED = List.of("storeName", "address", "weightKg", "volumeCbm");

    /** 数量字段：件数/箱数至少覆盖一个。 */
    private static final List<String> QUANTITY_FIELDS = List.of("pieces", "boxes");

    /**
     * 字段 → 同义词别名（原文，匹配时两侧都归一化）。规范表头本身也在别名里 → 现有模板/固定表头零回归。
     * 顺序用 LinkedHashMap 固定，保证歧义时「取第一个字段」稳定可测。
     */
    private static final Map<String, List<String>> FIELD_ALIASES = new LinkedHashMap<>();

    static {
        FIELD_ALIASES.put("storeCode", List.of("订单号", "单号", "订单编号", "订单No", "编号", "订单号码"));
        FIELD_ALIASES.put("storeName", List.of("门店名称", "门店", "店名", "门店名", "客户", "客户名称", "客户名",
                "收货方", "收货客户", "收货门店", "网点", "店铺", "终端"));
        FIELD_ALIASES.put("address", List.of("配送地址", "地址", "收货地址", "送货地址", "详细地址", "门店地址",
                "收货地址明细", "送货地址明细"));
        FIELD_ALIASES.put("pieces", List.of("件数", "件", "pcs", "总件数", "数量", "总数"));
        FIELD_ALIASES.put("boxes", List.of("箱数", "箱", "纸箱数", "总箱数", "箱子"));
        FIELD_ALIASES.put("weightKg", List.of("重量kg", "重量", "毛重", "净重", "总重", "公斤", "kg"));
        FIELD_ALIASES.put("volumeCbm", List.of("体积m³", "体积m3", "体积", "方数", "立方", "总体积", "m3", "cbm"));
        FIELD_ALIASES.put("businessDate", List.of("业务日期", "日期", "配送日期", "送货日期"));
        FIELD_ALIASES.put("windowStart", List.of("配送开始时间", "送达开始", "时间窗开始", "最早送达"));
        FIELD_ALIASES.put("windowEnd", List.of("配送结束时间", "送达结束", "时间窗结束", "最晚送达"));
        FIELD_ALIASES.put("longitude", List.of("经度", "lng", "longitude"));
        FIELD_ALIASES.put("latitude", List.of("纬度", "lat", "latitude"));
        FIELD_ALIASES.put("areaCode", List.of("区域", "片区", "配送区域", "大区", "区"));
    }

    /** 归一化别名 → 字段（精确匹配用）。同一归一化别名若被多字段声明，先声明者胜（LinkedHashMap 顺序）。 */
    private static final Map<String, String> EXACT_ALIAS = new LinkedHashMap<>();

    /** 用于子串匹配的别名（长度 ≥ 2，避免「区」「件」这类单字误命中）：归一化别名 → 字段。 */
    private static final Map<String, String> SUBSTRING_ALIAS = new LinkedHashMap<>();

    static {
        for (Map.Entry<String, List<String>> e : FIELD_ALIASES.entrySet()) {
            String field = e.getKey();
            for (String alias : e.getValue()) {
                String norm = normalizeHeader(alias);
                if (norm.isEmpty()) {
                    continue;
                }
                EXACT_ALIAS.putIfAbsent(norm, field);
                if (norm.length() >= 2) {
                    SUBSTRING_ALIAS.putIfAbsent(norm, field);
                }
            }
        }
    }

    /**
     * 表头归一化：去所有空白（含换行/制表/全角空格）+ 去成对括号内的单位/说明（(kg)/（含套餐）/【…】/[…]）
     * + 英文转小写。³/m3 等不动，靠别名列表覆盖。
     */
    public static String normalizeHeader(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\s\\u3000]+", "");
        t = t.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "");
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * 识别整张表格的表头（第 0 行）→ {@link ColumnMappingResult}。
     * 首个数据行（第 1 行，若有）填充每列 sampleValue 供用户核对。不抛异常。
     */
    public static ColumnMappingResult detect(List<List<String>> table) {
        ColumnMappingResult result = new ColumnMappingResult();
        if (table == null || table.isEmpty()) {
            markUnmappedRequired(result); // 空表：所有必填都缺
            return result;
        }
        List<String> header = table.get(0);
        List<String> sampleRow = table.size() > 1 ? table.get(1) : List.of();

        int n = header.size();
        MatchResult[] matches = new MatchResult[n];
        for (int c = 0; c < n; c++) {
            matches[c] = matchHeader(header.get(c));
        }
        // 同字段多列时选「赢家列」：置信度最高，并列取最小列索引（升序扫 + 严格 < 保证并列不覆盖）
        Map<String, Integer> fieldWinner = new LinkedHashMap<>();
        for (int c = 0; c < n; c++) {
            MatchResult m = matches[c];
            if (m == null) {
                continue;
            }
            Integer curr = fieldWinner.get(m.field());
            if (curr == null || matches[curr].confidence() < m.confidence()) {
                fieldWinner.put(m.field(), c);
            }
        }
        for (int c = 0; c < n; c++) {
            String raw = header.get(c);
            ColumnMapping cm = new ColumnMapping();
            cm.setIndex(c);
            cm.setHeader(raw == null ? "" : raw);
            cm.setSampleValue(c < sampleRow.size() ? sampleRow.get(c) : null);

            MatchResult m = matches[c];
            if (m != null && fieldWinner.get(m.field()) == c) {
                cm.setMappedField(m.field());
                cm.setConfidence(m.confidence());
                cm.setAmbiguous(m.ambiguous());
            }
            result.getColumns().add(cm);
        }

        computeCoverage(result);
        return result;
    }

    /**
     * 解析「列索引 → 目标字段名」的最终映射：override 优先（用户在确认面板改的），未指定的列用自动识别。
     * override 中值为 null/空/{@code "__ignore__"} 表示该列忽略。同字段被映射到多列时取最小列索引。
     */
    public static Map<Integer, String> resolveColumns(List<String> header, Map<Integer, String> override) {
        Map<Integer, String> auto = new LinkedHashMap<>();
        if (header != null) {
            // 复用 detect 的赢家列选择，保证「用户在面板看到的映射」== 「applyMapping 实际用的映射」
            ColumnMappingResult detected = detect(List.of(header));
            for (ColumnMapping cm : detected.getColumns()) {
                if (cm.getMappedField() != null) {
                    auto.put(cm.getIndex(), cm.getMappedField());
                }
            }
        }
        if (override != null) {
            for (Map.Entry<Integer, String> e : override.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isBlank() || "__ignore__".equals(v)) {
                    auto.remove(e.getKey());
                } else {
                    auto.put(e.getKey(), v);
                }
            }
        }
        // 同字段多列 → 取最小列索引
        Map<String, Integer> fieldToFirstCol = new LinkedHashMap<>();
        Map<Integer, String> resolved = new LinkedHashMap<>();
        List<Integer> cols = new ArrayList<>(auto.keySet());
        cols.sort(Integer::compareTo);
        for (Integer c : cols) {
            String field = auto.get(c);
            if (!fieldToFirstCol.containsKey(field)) {
                fieldToFirstCol.put(field, c);
                resolved.put(c, field);
            }
        }
        return resolved;
    }

    /**
     * 按映射把原始二维表格 → {@link LogisticsOrderImportRow} 列表（第 0 行表头，其余数据行）。
     * override 为 null 时用自动识别。整行空白跳过。未映射的列忽略。
     */
    public static List<LogisticsOrderImportRow> applyMapping(List<List<String>> table, Map<Integer, String> override) {
        List<LogisticsOrderImportRow> rows = new ArrayList<>();
        if (table == null || table.isEmpty()) {
            return rows;
        }
        Map<Integer, String> colToField = resolveColumns(table.get(0), override);
        Map<Integer, Field> colToJavaField = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : colToField.entrySet()) {
            Field f = fieldByName(e.getValue());
            if (f != null) {
                colToJavaField.put(e.getKey(), f);
            }
        }
        for (int r = 1; r < table.size(); r++) {
            List<String> cols = table.get(r);
            if (cols.stream().allMatch(s -> s == null || s.isBlank())) {
                continue;
            }
            LogisticsOrderImportRow row = new LogisticsOrderImportRow();
            for (Map.Entry<Integer, Field> e : colToJavaField.entrySet()) {
                int idx = e.getKey();
                if (idx < cols.size()) {
                    try {
                        e.getValue().set(row, cols.get(idx));
                    } catch (IllegalAccessException ignore) {
                        // setAccessible(true) 已放开，理论不可达
                    }
                }
            }
            rows.add(row);
        }
        return rows;
    }

    // ==================== 内部 ====================

    private record MatchResult(String field, double confidence, boolean ambiguous) {
    }

    /** 单个表头 → 匹配结果（精确别名 1.0 → 子串别名 0.7；子串命中多字段标 ambiguous）。无命中返 null。 */
    private static MatchResult matchHeader(String header) {
        String norm = normalizeHeader(header);
        if (norm.isEmpty()) {
            return null;
        }
        String exact = EXACT_ALIAS.get(norm);
        if (exact != null) {
            return new MatchResult(exact, 1.0, false);
        }
        // 子串：源表头包含别名，或别名包含源表头
        List<String> hitFields = new ArrayList<>();
        for (Map.Entry<String, String> e : SUBSTRING_ALIAS.entrySet()) {
            String alias = e.getKey();
            if (norm.contains(alias) || alias.contains(norm)) {
                if (!hitFields.contains(e.getValue())) {
                    hitFields.add(e.getValue());
                }
            }
        }
        if (hitFields.isEmpty()) {
            return null;
        }
        return new MatchResult(hitFields.get(0), 0.7, hitFields.size() > 1);
    }

    /** 计算必填覆盖 + autoConfident，写入 result。 */
    private static void computeCoverage(ColumnMappingResult result) {
        List<String> mapped = new ArrayList<>();
        boolean anyAmbiguous = false;
        for (ColumnMapping cm : result.getColumns()) {
            if (cm.getMappedField() != null) {
                mapped.add(cm.getMappedField());
            }
            if (cm.isAmbiguous()) {
                anyAmbiguous = true;
            }
        }
        for (String req : HARD_REQUIRED) {
            if (!mapped.contains(req)) {
                result.getUnmappedRequiredFields().add(req);
            }
        }
        boolean quantityCovered = QUANTITY_FIELDS.stream().anyMatch(mapped::contains);
        if (!quantityCovered) {
            result.getUnmappedRequiredFields().add("quantity");
        }
        result.setAutoConfident(result.getUnmappedRequiredFields().isEmpty() && !anyAmbiguous);
    }

    /** 空表：把所有必填标记为缺失。 */
    private static void markUnmappedRequired(ColumnMappingResult result) {
        result.getUnmappedRequiredFields().addAll(HARD_REQUIRED);
        result.getUnmappedRequiredFields().add("quantity");
        result.setAutoConfident(false);
    }

    private static Field fieldByName(String name) {
        try {
            Field f = LogisticsOrderImportRow.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
