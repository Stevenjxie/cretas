package com.cretas.aims.dto.smartbi;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图表语义元数据 — 随图表响应下发给前端 (spec §2.5, B1 root-fix)。
 *
 * <p>由后端从 {@link DynamicChartConfig#getFieldMappings()} 中的
 * {@link FieldMappingWithChartRole} 列表派生，使前端 chartInsight.ts 能识别
 * 五族图表模式并计算 insight 签名，无需前端再解析 ECharts options。
 *
 * <h3>字段派生规则</h3>
 * <ul>
 *   <li><b>xDim</b>: 取 chartAxis=X_AXIS 的字段
 *       <ul>
 *         <li>role=TIME → {@code "time"}</li>
 *         <li>standardField/alias 含 store/门店 → {@code "store"}</li>
 *         <li>standardField/alias 含 product/产品/菜品 → {@code "product"}</li>
 *         <li>standardField/alias 含 channel/渠道/平台 → {@code "channel"}</li>
 *         <li>standardField/alias 含 category/分类 → {@code "category"}</li>
 *         <li>其他 → {@code "other"}</li>
 *       </ul>
 *   </li>
 *   <li><b>yMetric</b>: 取第一个 chartAxis=Y_AXIS 或 role=METRIC 的字段
 *       <ul>
 *         <li>standardField/alias 含 revenue/营收/销售额 → {@code "revenue"}</li>
 *         <li>standardField/alias 含 quantity/数量 → {@code "quantity"}</li>
 *         <li>standardField/alias 含 margin/毛利 → {@code "margin"}</li>
 *         <li>standardField/alias 含 cost/成本 → {@code "cost"}</li>
 *         <li>standardField/alias 含 count/单数 → {@code "count"}</li>
 *         <li>standardField/alias 含 rate/率/pct/percentage → {@code "pct"}</li>
 *         <li>其他 → {@code "other"}</li>
 *       </ul>
 *   </li>
 *   <li><b>aggregation</b>: Y 度量字段的 aggregationType
 *       (SUM→sum, AVG→avg, MAX→max, COUNT→count, 其他→sum)</li>
 *   <li><b>domain</b>: 工厂 businessType 字符串
 *       (RESTAURANT→restaurant, 其他→factory)</li>
 * </ul>
 *
 * <p>序列化后在 JSON 响应中以下划线前缀 {@code _meta} 输出（见前端契约），
 * 但 Java DTO 字段名保持 camelCase {@code meta}，由响应层或前端适配器处理前缀。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-06-10
 * @see DynamicChartConfig#getMeta()
 * @see com.cretas.aims.service.smartbi.impl.DynamicChartConfigBuilder#buildConfigWithMeta
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartMeta {

    /**
     * X 轴维度语义。
     * 取值: {@code time | store | product | channel | category | other}
     */
    @JsonProperty("xDim")
    private String xDim;

    /**
     * Y 轴度量语义。
     * 取值: {@code revenue | quantity | margin | cost | count | pct | other}
     */
    @JsonProperty("yMetric")
    private String yMetric;

    /**
     * 聚合方式。
     * 取值: {@code sum | avg | max | count}
     */
    @JsonProperty("aggregation")
    private String aggregation;

    /**
     * 业务领域（来自工厂 businessType）。
     * 取值: {@code restaurant | factory | finance}
     */
    @JsonProperty("domain")
    private String domain;
}
