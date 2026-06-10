package com.cretas.aims.dto.smartbi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 动态图表配置 DTO
 * 扩展 ChartConfig，增加 ECharts 特有配置和可切换维度
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicChartConfig {

    /**
     * 图表类型: LINE, BAR, PIE, GAUGE, SCATTER, HEATMAP, MAP, RADAR
     */
    private String chartType;

    /**
     * 图表标题
     */
    private String title;

    /**
     * 图表子标题
     */
    private String subTitle;

    // ==================== X 轴配置 ====================

    /**
     * X 轴配置
     */
    private AxisConfig xAxis;

    // ==================== Y 轴配置 ====================

    /**
     * Y 轴配置列表
     * 支持双 Y 轴场景
     */
    private List<AxisConfig> yAxis;

    // ==================== 图例配置 ====================

    /**
     * 图例配置
     */
    private LegendConfig legend;

    // ==================== 系列配置 ====================

    /**
     * 系列数据
     */
    private List<SeriesConfig> series;

    // ==================== 提示框配置 ====================

    /**
     * 提示框配置
     */
    private TooltipConfig tooltip;

    // ==================== 可切换维度 ====================

    /**
     * 可切换的 X 轴维度
     */
    private List<AlternativeDimension> alternativeXAxis;

    /**
     * 可切换的 Series 维度
     */
    private List<AlternativeDimension> alternativeSeries;

    /**
     * 可切换的 Y 轴度量
     */
    private List<AlternativeDimension> alternativeMeasures;

    // ==================== 原始数据 ====================

    /**
     * 原始聚合数据
     */
    private List<Map<String, Object>> rawData;

    /**
     * 数据总行数
     */
    private Integer totalRows;

    // ==================== 元数据 ====================

    /**
     * 当前使用的字段映射
     */
    private List<FieldMappingWithChartRole> fieldMappings;

    /**
     * 扩展配置（透传给 ECharts）
     */
    private Map<String, Object> options;

    /**
     * 图表语义元数据 (spec §2.5, B1 root-fix)。
     * 由后端从 fieldMappings 派生，使前端 chartInsight.ts 能识别图表族并生成洞察。
     * 仅 {@link com.cretas.aims.service.smartbi.impl.DynamicChartConfigBuilder#buildConfigWithMeta}
     * 调用时附加；现有 {@code buildConfig} 调用保持 null（向后兼容）。
     */
    private ChartMeta meta;

    // ==================== 内部类 ====================

    /**
     * 轴配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AxisConfig {
        /**
         * 轴类型: category, value, time, log
         */
        private String type;

        /**
         * 轴名称
         */
        private String name;

        /**
         * 轴数据（仅 category 类型）
         */
        private List<String> data;

        /**
         * 是否显示轴线
         */
        @Builder.Default
        private Boolean show = true;

        /**
         * 轴标签配置
         */
        private Map<String, Object> axisLabel;

        /**
         * 轴线配置
         */
        private Map<String, Object> axisLine;

        /**
         * 分隔线配置
         */
        private Map<String, Object> splitLine;

        /**
         * 位置: left, right (Y轴), top, bottom (X轴)
         */
        private String position;

        /**
         * 最小值
         */
        private Object min;

        /**
         * 最大值
         */
        private Object max;
    }

    /**
     * 图例配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LegendConfig {
        /**
         * 是否显示图例
         */
        @Builder.Default
        private Boolean show = true;

        /**
         * 图例数据
         */
        private List<String> data;

        /**
         * 位置: top, bottom, left, right
         */
        @Builder.Default
        private String position = "top";

        /**
         * 布局方式: horizontal, vertical
         */
        @Builder.Default
        private String orient = "horizontal";

        /**
         * 选中状态
         */
        private Map<String, Boolean> selected;
    }

    /**
     * 系列配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeriesConfig {
        /**
         * 系列名称
         */
        private String name;

        /**
         * 系列类型: line, bar, pie, scatter
         */
        private String type;

        /**
         * 系列数据
         */
        private List<Object> data;

        /**
         * 使用的 Y 轴索引
         */
        @Builder.Default
        private Integer yAxisIndex = 0;

        /**
         * 是否堆叠
         */
        private String stack;

        /**
         * 是否平滑曲线
         */
        @Builder.Default
        private Boolean smooth = false;

        /**
         * 是否显示区域填充
         */
        @Builder.Default
        private Boolean areaStyle = false;

        /**
         * 标签配置
         */
        private Map<String, Object> label;

        /**
         * 样式配置
         */
        private Map<String, Object> itemStyle;

        /**
         * 强调样式
         */
        private Map<String, Object> emphasis;
    }

    /**
     * 提示框配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TooltipConfig {
        /**
         * 触发类型: item, axis, none
         */
        @Builder.Default
        private String trigger = "axis";

        /**
         * 坐标轴指示器
         */
        private Map<String, Object> axisPointer;

        /**
         * 格式化函数（字符串形式，前端 eval）
         */
        private String formatter;

        /**
         * 值格式化模板
         */
        private String valueFormatter;
    }

    /**
     * 转换为简化的 ChartConfig
     */
    public ChartConfig toChartConfig() {
        return ChartConfig.builder()
                .chartType(this.chartType)
                .title(this.title)
                .xAxisField(this.xAxis != null ? this.xAxis.getName() : null)
                .yAxisField(this.yAxis != null && !this.yAxis.isEmpty() ? this.yAxis.get(0).getName() : null)
                .seriesField(this.legend != null && this.legend.getData() != null && !this.legend.getData().isEmpty()
                        ? this.legend.getData().get(0) : null)
                .data(this.rawData)
                .options(this.options)
                .build();
    }

    /**
     * 创建简单柱状图配置
     */
    public static DynamicChartConfig simpleBar(String title, List<String> xData,
                                                String yName, List<Object> yData) {
        return DynamicChartConfig.builder()
                .chartType("BAR")
                .title(title)
                .xAxis(AxisConfig.builder()
                        .type("category")
                        .data(xData)
                        .build())
                .yAxis(List.of(AxisConfig.builder()
                        .type("value")
                        .name(yName)
                        .build()))
                .series(List.of(SeriesConfig.builder()
                        .type("bar")
                        .name(yName)
                        .data(yData)
                        .build()))
                .tooltip(TooltipConfig.builder()
                        .trigger("axis")
                        .build())
                .build();
    }

    /**
     * 创建简单折线图配置
     */
    public static DynamicChartConfig simpleLine(String title, List<String> xData,
                                                 String yName, List<Object> yData) {
        return DynamicChartConfig.builder()
                .chartType("LINE")
                .title(title)
                .xAxis(AxisConfig.builder()
                        .type("category")
                        .data(xData)
                        .build())
                .yAxis(List.of(AxisConfig.builder()
                        .type("value")
                        .name(yName)
                        .build()))
                .series(List.of(SeriesConfig.builder()
                        .type("line")
                        .name(yName)
                        .data(yData)
                        .smooth(true)
                        .build()))
                .tooltip(TooltipConfig.builder()
                        .trigger("axis")
                        .build())
                .build();
    }
}
