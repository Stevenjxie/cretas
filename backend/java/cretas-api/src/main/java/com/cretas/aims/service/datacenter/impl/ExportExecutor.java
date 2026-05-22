package com.cretas.aims.service.datacenter.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import com.cretas.aims.entity.datacenter.ExportRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export 真实执行器. ExportServiceImpl 委托此类做 entity query + SpEL filter + EasyExcel 写.
 *
 * <p>Design: 通过 {@code EntityManager.createQuery("SELECT e FROM " + targetEntity + " e
 * WHERE e.factoryId = :fid")} 反射式查询. 然后用 Jackson 把 entity 转 Map, 应用 SpEL filter,
 * 最后通过 EasyExcel WriteSheet.head(List&lt;List&lt;String&gt;&gt;) + doWrite(List&lt;List&lt;Object&gt;&gt;)
 * 写文件 (复用 utils/ExcelUtil 模式).
 *
 * <p>Sprint 4 Chat K C-EXPORT-CENTER-1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportExecutor {

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper;

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    /** Result wrapper. {@code bytes} populated when {@code targetFile} null; otherwise written to file. */
    public static class Result {
        public final int rowCount;
        public final long fileSizeBytes;
        public final byte[] bytes;

        public Result(int rowCount, long fileSizeBytes, byte[] bytes) {
            this.rowCount = rowCount;
            this.fileSizeBytes = fileSizeBytes;
            this.bytes = bytes;
        }
    }

    /**
     * 同步执行. 若 targetFile 为 null, 数据写入内存返 bytes; 否则写文件返空 bytes.
     *
     * @param rule          导出规则
     * @param runtimeParams SpEL filter 变量
     * @param targetFile    文件输出目标 (null = 写内存)
     */
    public Result run(ExportRule rule, Map<String, Object> runtimeParams, File targetFile)
            throws Exception {
        List<Map<String, Object>> rows = queryAndFilter(rule, runtimeParams);
        List<Map<String, Object>> columns = rule.getColumns();
        List<List<String>> head = buildHead(columns);
        List<List<Object>> data = buildData(rows, columns);

        WriteCellStyle headStyle = new WriteCellStyle();
        WriteCellStyle bodyStyle = new WriteCellStyle();
        HorizontalCellStyleStrategy style = new HorizontalCellStyleStrategy(headStyle, bodyStyle);

        if (targetFile != null) {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建导出目录: " + parent.getAbsolutePath());
            }
            EasyExcel.write(targetFile)
                    .head(head)
                    .registerWriteHandler(style)
                    .sheet(safeSheet(rule.getRuleName()))
                    .doWrite(data);
            return new Result(rows.size(), targetFile.length(), null);
        } else {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                EasyExcel.write((OutputStream) baos)
                        .head(head)
                        .registerWriteHandler(style)
                        .sheet(safeSheet(rule.getRuleName()))
                        .doWrite(data);
                byte[] bytes = baos.toByteArray();
                return new Result(rows.size(), bytes.length, bytes);
            }
        }
    }

    /** 仅做 entity query + SpEL filter, 返 Map list. 单独抽出便于 unit test. */
    List<Map<String, Object>> queryAndFilter(ExportRule rule, Map<String, Object> runtimeParams) {
        String targetEntity = rule.getTargetEntity();
        if (targetEntity == null || targetEntity.isEmpty()) {
            throw new IllegalArgumentException("rule.targetEntity 必填");
        }
        String jpql = "SELECT e FROM " + targetEntity + " e WHERE e.factoryId = :fid";
        List<?> entities;
        try {
            entities = em.createQuery(jpql)
                    .setParameter("fid", rule.getFactoryId())
                    .getResultList();
        } catch (Exception ex) {
            throw new IllegalStateException("查询 entity 失败 (检查 targetEntity 是否含 factoryId 字段): "
                    + ex.getMessage(), ex);
        }

        List<Map<String, Object>> result = new ArrayList<>(entities.size());
        Expression filter = null;
        if (rule.getFilterExpression() != null && !rule.getFilterExpression().isBlank()) {
            try {
                filter = SPEL.parseExpression(rule.getFilterExpression());
            } catch (Exception ex) {
                log.warn("[ExportExecutor] SpEL filter parse fail: {} — 忽略 filter", ex.getMessage());
            }
        }

        for (Object e : entities) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = objectMapper.convertValue(e, Map.class);
            if (filter != null && !evalFilter(filter, row, runtimeParams)) continue;
            result.add(row);
        }
        return result;
    }

    /**
     * SECURITY (2026-05-22 Sprint 10 P0 #5 hotfix per BI audit):
     * <p>Previously used {@link org.springframework.expression.spel.support.StandardEvaluationContext}
     * which exposes full Java reflection (T(Runtime).getRuntime().exec() RCE etc.) to ANY
     * admin who can set ExportRule.filterExpression. Replaced with
     * {@link SimpleEvaluationContext} (read-only data binding) — restricts SpEL to property
     * reads on the supplied row/params, blocks T(...), constructors, static methods, and
     * arbitrary bean references.
     * <p>Pattern mirrors {@link com.cretas.aims.engine.SpelConditionEvaluator} Round 5 SEC-2 fix.
     * <p>Other unsafe SpEL sites flagged by audit (StateMachineServiceImpl, MetricFormulaServiceImpl,
     * LoggableAspect) tracked as Sprint 11 follow-up — each has distinct attacker-surface
     * analysis and is out of this hotfix scope.
     */
    static boolean evalFilter(Expression expr, Map<String, Object> row, Map<String, Object> runtimeParams) {
        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            ctx.setVariable("row", row);
            if (runtimeParams != null) {
                runtimeParams.forEach(ctx::setVariable);
            }
            Object v = expr.getValue(ctx);
            return v instanceof Boolean && (Boolean) v;
        } catch (Exception ex) {
            log.debug("[ExportExecutor] SpEL eval failed for row: {}", ex.getMessage());
            return false;
        }
    }

    static List<List<String>> buildHead(List<Map<String, Object>> columns) {
        List<List<String>> head = new ArrayList<>(columns.size());
        for (Map<String, Object> col : columns) {
            Object header = col.getOrDefault("header", col.get("field"));
            List<String> h = new ArrayList<>(1);
            h.add(header == null ? "" : String.valueOf(header));
            head.add(h);
        }
        return head;
    }

    static List<List<Object>> buildData(List<Map<String, Object>> rows, List<Map<String, Object>> columns) {
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<Object> r = new ArrayList<>(columns.size());
            for (Map<String, Object> col : columns) {
                Object field = col.get("field");
                if (field == null) {
                    r.add(null);
                } else {
                    r.add(row.get(String.valueOf(field)));
                }
            }
            data.add(r);
        }
        return data;
    }

    /** Excel sheet name 不允许特殊字符 且 ≤31 chars. */
    static String safeSheet(String name) {
        if (name == null || name.isEmpty()) return "Sheet1";
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
