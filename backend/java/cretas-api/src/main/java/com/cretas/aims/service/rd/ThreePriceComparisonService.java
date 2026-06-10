package com.cretas.aims.service.rd;

import com.cretas.aims.dto.rd.ThreePriceComparisonDTO;

/**
 * SP10: 三价对比服务接口.
 */
public interface ThreePriceComparisonService {

    /**
     * 获取指定试样的三价对比数据.
     *
     * @param factoryId 工厂 ID
     * @param sampleId  试样 ID (business key)
     * @return 三价对比 DTO (任一价格缺失时对应字段为 null)
     */
    ThreePriceComparisonDTO getThreePriceComparison(String factoryId, String sampleId);

    /**
     * 通过报价任务 ID 获取三价对比数据.
     *
     * <p>从 QuotationTask 解析 sampleId 后委托 {@link #getThreePriceComparison}.</p>
     *
     * @param factoryId     工厂 ID
     * @param quotationTaskId 报价任务 ID
     * @return 三价对比 DTO
     * @throws jakarta.persistence.EntityNotFoundException 报价任务不存在
     */
    ThreePriceComparisonDTO getThreePriceComparisonByTaskId(String factoryId, String quotationTaskId);
}
