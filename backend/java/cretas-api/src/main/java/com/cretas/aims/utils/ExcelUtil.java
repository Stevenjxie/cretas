package com.cretas.aims.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel工具类
 * 基于EasyExcel实现Excel导出和导入功能
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-20
 */
@Slf4j
@Component
public class ExcelUtil {

    /** Generic, business-agnostic first-sheet reader used by preview-before-commit imports. */
    public RawSheet readFirstSheetAsRows(InputStream inputStream, int maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("maxRows 必须大于0");
        try {
            @SuppressWarnings("unchecked")
            List<Map<Integer, String>> raw = (List<Map<Integer, String>>) (List<?>) EasyExcel
                    .read(inputStream)
                    .headRowNumber(0)
                    .sheet(0)
                    .doReadSync();
            if (raw.isEmpty()) return new RawSheet(List.of(), List.of());
            if (raw.size() - 1 > maxRows) {
                throw new IllegalArgumentException("Excel 数据行不能超过 " + maxRows + " 行");
            }
            Map<Integer, String> headerRow = raw.get(0);
            int lastColumn = headerRow.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i <= lastColumn; i++) {
                headers.add(normalizeCell(headerRow.get(i)));
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < raw.size(); rowIndex++) {
                Map<Integer, String> source = raw.get(rowIndex);
                Map<String, String> row = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    String header = headers.get(column);
                    if (!header.isEmpty()) row.put(header, normalizeCell(source.get(column)));
                }
                rows.add(row);
            }
            return new RawSheet(List.copyOf(headers), List.copyOf(rows));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Excel文件解析失败: " + ex.getMessage(), ex);
        }
    }

    private static String normalizeCell(String value) {
        return value == null ? "" : value.trim();
    }

    public record RawSheet(List<String> headers, List<Map<String, String>> rows) {}

    /**
     * 导出Excel文件（单个Sheet）
     *
     * @param data 数据列表
     * @param clazz 数据类型（需要使用EasyExcel注解定义列）
     * @param sheetName Sheet名称
     * @param <T> 数据类型
     * @return Excel文件的字节数组
     */
    public <T> byte[] exportToExcel(List<T> data, Class<T> clazz, String sheetName) {
        log.info("开始导出Excel: sheetName={}, dataSize={}", sheetName, data.size());

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            EasyExcel.write(outputStream, clazz)
                    .autoCloseStream(false)
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .sheet(sheetName)
                    .doWrite(data);

            byte[] bytes = outputStream.toByteArray();
            log.info("Excel导出成功: sheetName={}, fileSize={}KB", sheetName, bytes.length / 1024);
            return bytes;
        } catch (IOException e) {
            log.error("Excel导出失败: sheetName={}", sheetName, e);
            throw new RuntimeException("Excel文件生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成Excel模板（空数据，仅包含表头）
     *
     * @param clazz 数据类型（需要使用EasyExcel注解定义列）
     * @param sheetName Sheet名称
     * @param <T> 数据类型
     * @return Excel模板文件的字节数组
     */
    public <T> byte[] generateTemplate(Class<T> clazz, String sheetName) {
        log.info("生成Excel模板: sheetName={}", sheetName);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            EasyExcel.write(outputStream, clazz)
                    .autoCloseStream(false)
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .sheet(sheetName)
                    .doWrite(List.of()); // 空数据，仅生成表头

            byte[] bytes = outputStream.toByteArray();
            log.info("Excel模板生成成功: sheetName={}, fileSize={}KB", sheetName, bytes.length / 1024);
            return bytes;
        } catch (IOException e) {
            log.error("Excel模板生成失败: sheetName={}", sheetName, e);
            throw new RuntimeException("Excel模板生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导出多个Sheet的Excel文件
     *
     * @param sheets Sheet数据列表（每个元素包含数据、类型、Sheet名称）
     * @return Excel文件的字节数组
     */
    public byte[] exportMultiSheetExcel(List<SheetData<?>> sheets) {
        log.info("开始导出多Sheet Excel: sheetCount={}", sheets.size());

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ExcelWriter excelWriter = EasyExcel.write(outputStream)
                    .autoCloseStream(false)
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .build();

            for (int i = 0; i < sheets.size(); i++) {
                SheetData<?> sheetData = sheets.get(i);
                WriteSheet writeSheet = EasyExcel.writerSheet(i, sheetData.getSheetName())
                        .head(sheetData.getClazz())
                        .build();
                excelWriter.write(sheetData.getData(), writeSheet);
            }

            excelWriter.finish();
            byte[] bytes = outputStream.toByteArray();
            log.info("多Sheet Excel导出成功: fileSize={}KB", bytes.length / 1024);
            return bytes;
        } catch (IOException e) {
            log.error("多Sheet Excel导出失败", e);
            throw new RuntimeException("Excel文件生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从Excel导入数据
     *
     * @param inputStream Excel文件输入流
     * @param clazz 数据类型（需要使用EasyExcel注解定义列）
     * @param <T> 数据类型
     * @return 解析出的数据列表
     */
    public <T> List<T> importFromExcel(InputStream inputStream, Class<T> clazz) {
        log.info("开始从Excel导入数据: class={}", clazz.getSimpleName());

        try {
            List<T> data = EasyExcel.read(inputStream)
                    .head(clazz)
                    .sheet(0)
                    .doReadSync();

            log.info("Excel数据导入成功: class={}, count={}", clazz.getSimpleName(), data.size());
            return data;
        } catch (Exception e) {
            log.error("Excel数据导入失败: class={}", clazz.getSimpleName(), e);
            throw new RuntimeException("Excel文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * Sheet数据封装类
     *
     * @param <T> 数据类型
     */
    public static class SheetData<T> {
        private final List<T> data;
        private final Class<T> clazz;
        private final String sheetName;

        public SheetData(List<T> data, Class<T> clazz, String sheetName) {
            this.data = data;
            this.clazz = clazz;
            this.sheetName = sheetName;
        }

        public List<T> getData() {
            return data;
        }

        public Class<T> getClazz() {
            return clazz;
        }

        public String getSheetName() {
            return sheetName;
        }
    }
}
