package com.cretas.aims.service.smartbi;

import com.cretas.aims.dto.smartbi.DynamicChartConfig;
import com.cretas.aims.dto.smartbi.FieldMappingWithChartRole;

import java.util.List;
import java.util.Map;

/**
 * 动态图表配置构建器服务接口
 *
 * 根据字段的 chartAxis 角色自动生成 ECharts 配置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
public interface DynamicChartConfigBuilderService {

    /**
     * 根据字段映射和聚合数据构建图表配置
     *
     * @param fields         带图表角色的字段映射列表
     * @param aggregatedData 聚合数据（可以是 List 或包含 data 的 Map）
     * @return 动态图表配置
     */
    DynamicChartConfig buildConfig(List<FieldMappingWithChartRole> fields,
                                    Map<String, Object> aggregatedData);

    /**
     * 根据字段映射和聚合数据构建图表配置，并附加语义元数据 {@link com.cretas.aims.dto.smartbi.ChartMeta}。
     *
     * <p>在 {@link #buildConfig} 基础上从字段和工厂业态推导 xDim/yMetric/aggregation/domain，
     * 供前端 Tier1 chartInsight.ts 识别图表族。{@link #buildConfig} 保持 meta=null，向后兼容。
     *
     * @param fields         带图表角色的字段映射列表
     * @param aggregatedData 聚合数据
     * @param businessType   工厂业态字符串（"RESTAURANT"/"FACTORY"/null）
     * @return 图表配置（含 meta）
     */
    DynamicChartConfig buildConfigWithMeta(List<FieldMappingWithChartRole> fields,
                                            Map<String, Object> aggregatedData,
                                            String businessType);

    /**
     * 使用指定的字段构建图表配置
     *
     * @param fields          字段映射列表
     * @param aggregatedData  聚合数据
     * @param xAxisFieldName  指定的 X 轴字段名
     * @param seriesFieldName 指定的 Series 字段名（可为 null）
     * @param measureFieldNames 指定的度量字段名列表
     * @return 动态图表配置
     */
    DynamicChartConfig buildConfigWithFields(List<FieldMappingWithChartRole> fields,
                                              Map<String, Object> aggregatedData,
                                              String xAxisFieldName,
                                              String seriesFieldName,
                                              List<String> measureFieldNames);

    /**
     * 使用指定的字段构建图表配置，并附加语义元数据。
     *
     * <p>在 {@link #buildConfigWithFields} 基础上加 businessType 传入，调整 meta.domain。
     *
     * @param fields            字段映射列表
     * @param aggregatedData    聚合数据
     * @param xAxisFieldName    指定的 X 轴字段名
     * @param seriesFieldName   指定的 Series 字段名（可为 null）
     * @param measureFieldNames 指定的度量字段名列表
     * @param businessType      工厂业态字符串（"RESTAURANT"/"FACTORY"/null）
     * @return 图表配置（含 meta）
     */
    DynamicChartConfig buildConfigWithFieldsAndMeta(List<FieldMappingWithChartRole> fields,
                                                     Map<String, Object> aggregatedData,
                                                     String xAxisFieldName,
                                                     String seriesFieldName,
                                                     List<String> measureFieldNames,
                                                     String businessType);

    /**
     * 指定图表类型构建配置
     *
     * @param fields         字段映射列表
     * @param aggregatedData 聚合数据
     * @param chartType      图表类型: LINE, BAR, PIE, SCATTER
     * @return 动态图表配置
     */
    DynamicChartConfig buildConfigWithChartType(List<FieldMappingWithChartRole> fields,
                                                 Map<String, Object> aggregatedData,
                                                 String chartType);
}
