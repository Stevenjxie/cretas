package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.ChartMeta;
import com.cretas.aims.dto.smartbi.DynamicChartConfig;
import com.cretas.aims.dto.smartbi.FieldMappingWithChartRole;
import com.cretas.aims.dto.smartbi.FieldMappingWithChartRole.ChartAxisRole;
import com.cretas.aims.dto.smartbi.FieldMappingWithChartRole.FieldRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for U1: ChartMeta derivation from fieldMappings.
 * Spec §2.5 (B1 root-fix): builder attaches _meta to DynamicChartConfig.
 *
 * Contract:
 * - xDim: X_AXIS field with role=TIME → "time";
 *         standardField contains store/门店 → "store";
 *         standardField contains product/产品/菜品 → "product";
 *         standardField contains channel/渠道/平台 → "channel";
 *         standardField contains category/分类 → "category";
 *         else → "other"
 * - yMetric: first Y_AXIS/METRIC field standardField semantics
 *         revenue/营收/销售额 → "revenue"; quantity/数量 → "quantity";
 *         margin/毛利 → "margin"; cost/成本 → "cost";
 *         count/单数 → "count"; rate/率/pct/percentage → "pct"; else → "other"
 * - aggregation: from Y metric's aggregationType (SUM→sum, AVG→avg, MAX→max, COUNT→count)
 * - domain: from factory businessType string (RESTAURANT→restaurant, FACTORY→factory, else factory)
 */
@DisplayName("DynamicChartConfigBuilder — ChartMeta derivation (U1)")
class DynamicChartConfigBuilderMetaTest {

    private DynamicChartConfigBuilder builder;

    // Minimal aggregated data so buildConfig doesn't return empty config
    private static final Map<String, Object> SAMPLE_DATA = Map.of(
            "data", List.of(
                    Map.of("月份", "2026-01", "营收", 100000),
                    Map.of("月份", "2026-02", "营收", 120000),
                    Map.of("月份", "2026-03", "营收", 110000),
                    Map.of("月份", "2026-04", "营收", 130000)
            )
    );

    private static final Map<String, Object> STORE_DATA = Map.of(
            "data", List.of(
                    Map.of("store_name", "店A", "sales_amount", 50000),
                    Map.of("store_name", "店B", "sales_amount", 80000)
            )
    );

    private static final Map<String, Object> PRODUCT_DATA = Map.of(
            "data", List.of(
                    Map.of("product_name", "猪舌", "quantity", 500),
                    Map.of("product_name", "牛腱", "quantity", 300)
            )
    );

    @BeforeEach
    void setUp() {
        builder = new DynamicChartConfigBuilder();
    }

    // ── Case 1: TIME xDim + revenue yMetric + SUM + RESTAURANT domain ──────

    @Test
    @DisplayName("TIME x-axis + revenue y-metric + SUM + RESTAURANT → meta={time, revenue, sum, restaurant}")
    void case1_timeRevenueSumRestaurant() {
        List<FieldMappingWithChartRole> fields = List.of(
                FieldMappingWithChartRole.createTimeField("月份", "月份", "月份", 0.95),
                FieldMappingWithChartRole.createMetricField("营收", "营收", "营收", "SUM", 0.9)
        );

        DynamicChartConfig config = builder.buildConfigWithMeta(fields, SAMPLE_DATA, "RESTAURANT");

        assertThat(config.getMeta()).isNotNull();
        ChartMeta meta = config.getMeta();
        assertThat(meta.getXDim()).isEqualTo("time");
        assertThat(meta.getYMetric()).isEqualTo("revenue");
        assertThat(meta.getAggregation()).isEqualTo("sum");
        assertThat(meta.getDomain()).isEqualTo("restaurant");
    }

    // ── Case 2: store xDim + quantity yMetric + AVG + FACTORY domain ────────

    @Test
    @DisplayName("store x-axis + quantity y-metric + AVG + FACTORY → meta={store, quantity, avg, factory}")
    void case2_storeQuantityAvgFactory() {
        FieldMappingWithChartRole storeField = FieldMappingWithChartRole.builder()
                .originalField("store_name")
                .standardField("store_name")
                .alias("门店")
                .role(FieldRole.DIMENSION)
                .chartAxis(ChartAxisRole.X_AXIS)
                .aggregationType("GROUP_BY")
                .axisPriority(1)
                .dataType("STRING")
                .confidence(0.9)
                .build();

        FieldMappingWithChartRole quantityField = FieldMappingWithChartRole.builder()
                .originalField("sales_amount")
                .standardField("quantity")
                .alias("销量")
                .role(FieldRole.METRIC)
                .chartAxis(ChartAxisRole.Y_AXIS)
                .aggregationType("AVG")
                .axisPriority(1)
                .dataType("NUMBER")
                .confidence(0.9)
                .build();

        DynamicChartConfig config = builder.buildConfigWithMeta(
                List.of(storeField, quantityField), STORE_DATA, "FACTORY");

        assertThat(config.getMeta()).isNotNull();
        ChartMeta meta = config.getMeta();
        assertThat(meta.getXDim()).isEqualTo("store");
        assertThat(meta.getYMetric()).isEqualTo("quantity");
        assertThat(meta.getAggregation()).isEqualTo("avg");
        assertThat(meta.getDomain()).isEqualTo("factory");
    }

    // ── Case 3: product xDim + margin yMetric + MAX ───────────────────────

    @Test
    @DisplayName("product x-axis + margin y-metric + MAX → meta={product, margin, max, factory}")
    void case3_productMarginMaxFactory() {
        FieldMappingWithChartRole productField = FieldMappingWithChartRole.builder()
                .originalField("product_name")
                .standardField("product_name")
                .alias("菜品")
                .role(FieldRole.DIMENSION)
                .chartAxis(ChartAxisRole.X_AXIS)
                .aggregationType("GROUP_BY")
                .axisPriority(1)
                .dataType("STRING")
                .confidence(0.9)
                .build();

        FieldMappingWithChartRole marginField = FieldMappingWithChartRole.builder()
                .originalField("gross_margin")
                .standardField("gross_margin")
                .alias("毛利")
                .role(FieldRole.METRIC)
                .chartAxis(ChartAxisRole.Y_AXIS)
                .aggregationType("MAX")
                .axisPriority(1)
                .dataType("NUMBER")
                .confidence(0.9)
                .build();

        DynamicChartConfig config = builder.buildConfigWithMeta(
                List.of(productField, marginField), PRODUCT_DATA, "FACTORY");

        assertThat(config.getMeta()).isNotNull();
        ChartMeta meta = config.getMeta();
        assertThat(meta.getXDim()).isEqualTo("product");
        assertThat(meta.getYMetric()).isEqualTo("margin");
        assertThat(meta.getAggregation()).isEqualTo("max");
        assertThat(meta.getDomain()).isEqualTo("factory");
    }

    // ── Case 4: channel standardField + count yMetric + COUNT ────────────

    @Test
    @DisplayName("channel x-axis (standardField contains 'channel') + count y-metric → meta={channel, count, count, restaurant}")
    void case4_channelCountRestaurant() {
        FieldMappingWithChartRole channelField = FieldMappingWithChartRole.builder()
                .originalField("order_channel")
                .standardField("order_channel")
                .alias("渠道")
                .role(FieldRole.DIMENSION)
                .chartAxis(ChartAxisRole.X_AXIS)
                .aggregationType("GROUP_BY")
                .axisPriority(1)
                .dataType("STRING")
                .confidence(0.9)
                .build();

        FieldMappingWithChartRole countField = FieldMappingWithChartRole.builder()
                .originalField("order_count")
                .standardField("order_count")
                .alias("单数")
                .role(FieldRole.METRIC)
                .chartAxis(ChartAxisRole.Y_AXIS)
                .aggregationType("COUNT")
                .axisPriority(1)
                .dataType("NUMBER")
                .confidence(0.9)
                .build();

        Map<String, Object> channelData = Map.of("data", List.of(
                Map.of("order_channel", "美团", "order_count", 150),
                Map.of("order_channel", "堂食", "order_count", 200)
        ));

        DynamicChartConfig config = builder.buildConfigWithMeta(
                List.of(channelField, countField), channelData, "RESTAURANT");

        assertThat(config.getMeta()).isNotNull();
        ChartMeta meta = config.getMeta();
        assertThat(meta.getXDim()).isEqualTo("channel");
        assertThat(meta.getYMetric()).isEqualTo("count");
        assertThat(meta.getAggregation()).isEqualTo("count");
        assertThat(meta.getDomain()).isEqualTo("restaurant");
    }

    // ── Case 5: existing buildConfig (no meta) → meta is null (backward compat) ──

    @Test
    @DisplayName("existing buildConfig (no businessType) → meta is null (backward compatible)")
    void case5_existingBuildConfig_noMeta() {
        List<FieldMappingWithChartRole> fields = List.of(
                FieldMappingWithChartRole.createTimeField("月份", "月份", "月份", 0.95),
                FieldMappingWithChartRole.createMetricField("营收", "营收", "营收", "SUM", 0.9)
        );

        DynamicChartConfig config = builder.buildConfig(fields, SAMPLE_DATA);

        // existing method should still work; meta is null (null = not attached)
        assertThat(config).isNotNull();
        assertThat(config.getMeta()).isNull();
    }

    // ── Case 6: pct yMetric (rate/率) ────────────────────────────────────

    @Test
    @DisplayName("rate field (contains '率') → yMetric=pct")
    void case6_rateFieldMappsToPct() {
        FieldMappingWithChartRole timeField = FieldMappingWithChartRole.createTimeField(
                "日期", "日期", "日期", 0.9);
        FieldMappingWithChartRole rateField = FieldMappingWithChartRole.builder()
                .originalField("completion_rate")
                .standardField("completion_rate")
                .alias("完成率")
                .role(FieldRole.METRIC)
                .chartAxis(ChartAxisRole.Y_AXIS)
                .aggregationType("AVG")
                .axisPriority(1)
                .dataType("NUMBER")
                .confidence(0.9)
                .build();

        Map<String, Object> rateData = Map.of("data", List.of(
                Map.of("日期", "2026-01", "completion_rate", 0.85),
                Map.of("日期", "2026-02", "completion_rate", 0.92)
        ));

        DynamicChartConfig config = builder.buildConfigWithMeta(
                List.of(timeField, rateField), rateData, "FACTORY");

        assertThat(config.getMeta()).isNotNull();
        // "completion_rate" alias="完成率" contains '率' → pct
        assertThat(config.getMeta().getYMetric()).isEqualTo("pct");
    }

    // ── Case 7: sales_amount standardField → revenue ─────────────────────

    @Test
    @DisplayName("standardField 'sales_amount' → yMetric=revenue (contains '销售'/'amount' + alias '销售额')")
    void case7_salesAmountToRevenue() {
        FieldMappingWithChartRole timeField = FieldMappingWithChartRole.createTimeField(
                "月份", "月份", "月份", 0.9);
        FieldMappingWithChartRole revenueField = FieldMappingWithChartRole.builder()
                .originalField("sales_amount")
                .standardField("sales_amount")
                .alias("销售额")
                .role(FieldRole.METRIC)
                .chartAxis(ChartAxisRole.Y_AXIS)
                .aggregationType("SUM")
                .axisPriority(1)
                .dataType("NUMBER")
                .confidence(0.9)
                .build();

        DynamicChartConfig config = builder.buildConfigWithMeta(
                List.of(timeField, revenueField), SAMPLE_DATA, "RESTAURANT");

        assertThat(config.getMeta()).isNotNull();
        assertThat(config.getMeta().getYMetric()).isEqualTo("revenue");
        assertThat(config.getMeta().getAggregation()).isEqualTo("sum");
    }
}
