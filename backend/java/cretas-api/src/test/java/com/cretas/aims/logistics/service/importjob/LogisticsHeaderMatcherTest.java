package com.cretas.aims.logistics.service.importjob;

import com.cretas.aims.logistics.dto.importjob.ColumnMapping;
import com.cretas.aims.logistics.dto.importjob.ColumnMappingResult;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LogisticsHeaderMatcher} 纯函数单测 —— 任意 Excel 表头 → 目标字段 识别引擎。
 *
 * <p>覆盖：别名命中 / 单位括号剥离 / 换行表头 / 歧义列 / 必填未覆盖 / 全中 autoConfident /
 * 覆盖映射优先 / 规范表头零回归 / applyMapping 落值。
 */
class LogisticsHeaderMatcherTest {

    /** 便捷构造二维表格。 */
    private static List<List<String>> table(String[]... rows) {
        List<List<String>> t = new ArrayList<>();
        for (String[] r : rows) {
            t.add(new ArrayList<>(Arrays.asList(r)));
        }
        return t;
    }

    private static String fieldOf(ColumnMappingResult r, String header) {
        return r.getColumns().stream()
                .filter(c -> header.equals(c.getHeader()))
                .findFirst()
                .map(ColumnMapping::getMappedField)
                .orElse(null);
    }

    private static Optional<ColumnMapping> col(ColumnMappingResult r, String header) {
        return r.getColumns().stream().filter(c -> header.equals(c.getHeader())).findFirst();
    }

    @Nested
    @DisplayName("表头归一化")
    class Normalize {

        @Test
        @DisplayName("去所有空白（含换行/制表/全角空格）")
        void stripsWhitespace() {
            assertThat(LogisticsHeaderMatcher.normalizeHeader("重量\n kg")).isEqualTo("重量kg");
            assertThat(LogisticsHeaderMatcher.normalizeHeader("门店　名称")).isEqualTo("门店名称");
        }

        @Test
        @DisplayName("去成对括号内的单位/说明")
        void stripsBracketedUnits() {
            assertThat(LogisticsHeaderMatcher.normalizeHeader("重量(kg)")).isEqualTo("重量");
            assertThat(LogisticsHeaderMatcher.normalizeHeader("体积（m³）")).isEqualTo("体积");
            assertThat(LogisticsHeaderMatcher.normalizeHeader("门店名称【必填】")).isEqualTo("门店名称");
        }

        @Test
        @DisplayName("英文转小写")
        void lowercasesAscii() {
            assertThat(LogisticsHeaderMatcher.normalizeHeader("KG")).isEqualTo("kg");
        }
    }

    @Nested
    @DisplayName("别名命中")
    class AliasMatch {

        @Test
        @DisplayName("常见别名映射到目标字段：客户→门店、数量→件数、方数→体积、收货地址→地址")
        void mapsCommonAliases() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"客户", "收货地址", "数量", "重量", "方数"},
                    new String[]{"大润发", "苏州路1号", "10", "50", "3"}
            ));
            assertThat(fieldOf(r, "客户")).isEqualTo("storeName");
            assertThat(fieldOf(r, "收货地址")).isEqualTo("address");
            assertThat(fieldOf(r, "数量")).isEqualTo("pieces");
            assertThat(fieldOf(r, "重量")).isEqualTo("weightKg");
            assertThat(fieldOf(r, "方数")).isEqualTo("volumeCbm");
        }

        @Test
        @DisplayName("带换行的表头也能识别（曾导致「重量」整列漏读）")
        void matchesNewlineHeader() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"重量\nkg"},
                    new String[]{"50"}
            ));
            assertThat(fieldOf(r, "重量\nkg")).isEqualTo("weightKg");
        }

        @Test
        @DisplayName("带单位括号的表头：重量(kg)→weightKg、体积(m³)→volumeCbm")
        void matchesUnitBracketHeader() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"重量(kg)", "体积(m³)"}
            ));
            assertThat(fieldOf(r, "重量(kg)")).isEqualTo("weightKg");
            assertThat(fieldOf(r, "体积(m³)")).isEqualTo("volumeCbm");
        }

        @Test
        @DisplayName("识别不到的列 mappedField 为 null，不抛异常")
        void unknownColumnStaysNull() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"门店名称", "备注随便写"}
            ));
            assertThat(fieldOf(r, "门店名称")).isEqualTo("storeName");
            assertThat(fieldOf(r, "备注随便写")).isNull();
        }

        @Test
        @DisplayName("精确别名置信度 1.0，子串命中 0.7")
        void confidenceLevels() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"门店名称", "地址明细"}
            ));
            assertThat(col(r, "门店名称").orElseThrow().getConfidence()).isEqualTo(1.0);
            // "地址明细" 精确匹配不到，子串含「地址」→ address @ 0.7（不含其它字段别名，不歧义）
            ColumnMapping addr = col(r, "地址明细").orElseThrow();
            assertThat(addr.getMappedField()).isEqualTo("address");
            assertThat(addr.getConfidence()).isEqualTo(0.7);
            assertThat(addr.isAmbiguous()).isFalse();
        }
    }

    @Nested
    @DisplayName("歧义与去重")
    class AmbiguityAndDedup {

        @Test
        @DisplayName("子串命中多个字段 → 标记 ambiguous")
        void ambiguousColumnFlagged() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"重量体积合计"}
            ));
            ColumnMapping cm = col(r, "重量体积合计").orElseThrow();
            assertThat(cm.isAmbiguous()).isTrue();
            assertThat(cm.getConfidence()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("同字段被两列命中：精确列胜，子串列降级为未映射")
        void higherConfidenceColumnWins() {
            // "地址明细"(子串→address 0.7) 在前，"配送地址"(精确→address 1.0) 在后
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"地址明细", "配送地址"}
            ));
            assertThat(fieldOf(r, "配送地址")).isEqualTo("address");
            assertThat(fieldOf(r, "地址明细")).isNull(); // 降级，避免两列都写 address
        }
    }

    @Nested
    @DisplayName("必填覆盖 & autoConfident")
    class Coverage {

        @Test
        @DisplayName("全中：模板 6 列 → autoConfident=true，无未覆盖必填（零回归）")
        void templateHeadersFullyConfident() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"订单号", "门店名称", "配送地址", "箱数", "重量kg", "体积m³"}
            ));
            assertThat(r.getUnmappedRequiredFields()).isEmpty();
            assertThat(r.isAutoConfident()).isTrue();
        }

        @Test
        @DisplayName("完整 13 列 schema 全中（零回归）")
        void fullSchemaHeaders() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"业务日期", "订单号", "门店名称", "配送地址", "件数", "箱数",
                            "重量kg", "体积m³", "配送开始时间", "配送结束时间", "经度", "纬度", "区域"}
            ));
            assertThat(r.getUnmappedRequiredFields()).isEmpty();
            assertThat(r.isAutoConfident()).isTrue();
            assertThat(fieldOf(r, "经度")).isEqualTo("longitude");
            assertThat(fieldOf(r, "区域")).isEqualTo("areaCode");
        }

        @Test
        @DisplayName("缺体积和数量 → unmappedRequiredFields 含 volumeCbm + quantity，autoConfident=false")
        void missingRequiredFields() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"门店名称", "配送地址", "重量kg"}
            ));
            assertThat(r.getUnmappedRequiredFields()).contains("volumeCbm", "quantity");
            assertThat(r.getUnmappedRequiredFields()).doesNotContain("storeName", "address", "weightKg");
            assertThat(r.isAutoConfident()).isFalse();
        }

        @Test
        @DisplayName("件数或箱数任一覆盖即满足数量要求")
        void eitherQuantitySatisfies() {
            ColumnMappingResult onlyPieces = LogisticsHeaderMatcher.detect(table(
                    new String[]{"门店名称", "配送地址", "件数", "重量kg", "体积m³"}
            ));
            assertThat(onlyPieces.getUnmappedRequiredFields()).doesNotContain("quantity");
            assertThat(onlyPieces.isAutoConfident()).isTrue();
        }

        @Test
        @DisplayName("必填字段被歧义列覆盖时 autoConfident=false（即便必填都覆盖）")
        void ambiguousBlocksAutoConfident() {
            // "重量体积" 歧义命中 weightKg（首个），且它是 weightKg 唯一来源列 → 赢家且 ambiguous；
            // volumeCbm 另由「体积m³」精确覆盖。全必填覆盖但含歧义映射 → 不可一键确认。
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(table(
                    new String[]{"重量体积", "体积m³", "门店名称", "配送地址", "箱数"}
            ));
            assertThat(r.getUnmappedRequiredFields()).isEmpty();
            assertThat(col(r, "重量体积").orElseThrow().isAmbiguous()).isTrue();
            assertThat(r.isAutoConfident()).isFalse();
        }

        @Test
        @DisplayName("空表：所有必填标记未覆盖，autoConfident=false")
        void emptyTable() {
            ColumnMappingResult r = LogisticsHeaderMatcher.detect(new ArrayList<>());
            assertThat(r.getUnmappedRequiredFields()).contains("storeName", "address", "weightKg", "volumeCbm", "quantity");
            assertThat(r.isAutoConfident()).isFalse();
        }
    }

    @Nested
    @DisplayName("applyMapping 落值 & 覆盖映射")
    class ApplyMapping {

        @Test
        @DisplayName("自动识别把每行 2D → LogisticsOrderImportRow（字段值正确）")
        void appliesAutoMapping() {
            List<LogisticsOrderImportRow> rows = LogisticsHeaderMatcher.applyMapping(table(
                    new String[]{"客户", "收货地址", "箱数", "重量", "体积"},
                    new String[]{"大润发", "苏州路1号", "10", "50", "3"},
                    new String[]{"华润", "无锡路2号", "8", "40", "2"}
            ), null);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getStoreName()).isEqualTo("大润发");
            assertThat(rows.get(0).getAddress()).isEqualTo("苏州路1号");
            assertThat(rows.get(0).getBoxes()).isEqualTo("10");
            assertThat(rows.get(0).getWeightKg()).isEqualTo("50");
            assertThat(rows.get(0).getVolumeCbm()).isEqualTo("3");
            assertThat(rows.get(1).getStoreName()).isEqualTo("华润");
        }

        @Test
        @DisplayName("整行空白跳过")
        void skipsBlankRows() {
            List<LogisticsOrderImportRow> rows = LogisticsHeaderMatcher.applyMapping(table(
                    new String[]{"门店名称", "配送地址"},
                    new String[]{"大润发", "苏州路1号"},
                    new String[]{"", ""},
                    new String[]{"华润", "无锡路2号"}
            ), null);
            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("覆盖映射优先于自动识别：自动识别不了的「备注」列手动指到 address")
        void overrideBeatsAuto() {
            // "备注" 自动识别不到；override 把列 1 手动指到 address
            List<List<String>> t = table(
                    new String[]{"门店名称", "备注"},
                    new String[]{"大润发", "江苏路9号"}
            );
            Map<Integer, String> override = Map.of(1, "address");
            List<LogisticsOrderImportRow> rows = LogisticsHeaderMatcher.applyMapping(t, override);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getStoreName()).isEqualTo("大润发");
            assertThat(rows.get(0).getAddress()).isEqualTo("江苏路9号");
        }

        @Test
        @DisplayName("override 值为 __ignore__ → 该列忽略")
        void overrideIgnore() {
            Map<Integer, String> resolved = LogisticsHeaderMatcher.resolveColumns(
                    Arrays.asList("门店名称", "配送地址"),
                    Map.of(0, "__ignore__"));
            assertThat(resolved).doesNotContainKey(0);
            assertThat(resolved).containsEntry(1, "address");
        }

        @Test
        @DisplayName("resolveColumns 同字段多列去重 → 取最小列索引")
        void resolveDedupByFirstColumn() {
            // override 强行把两列都指 storeName → 只保留列 0
            Map<Integer, String> resolved = LogisticsHeaderMatcher.resolveColumns(
                    Arrays.asList("a", "b"),
                    Map.of(0, "storeName", 1, "storeName"));
            assertThat(resolved).containsEntry(0, "storeName");
            assertThat(resolved).doesNotContainKey(1);
        }
    }
}
