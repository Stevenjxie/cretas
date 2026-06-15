package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;

import java.util.List;

/**
 * 销售订单行价格调整服务 — warn-not-block 模式
 *
 * <p>支持:
 * <ul>
 *   <li>改价留痕 (老价/新价/操作人/原因)</li>
 *   <li>改价立即生效; 超阈值 (降价>10%/涨价>20%) 返回 priceWarning=true + 审计标记</li>
 *   <li>已发货行拒绝改价 (409)</li>
 *   <li>改价后重算 SO 总额</li>
 *   <li>幂等防重 (5 分钟内相同行+相同价 → 返回已有记录)</li>
 * </ul>
 */
public interface SalesPriceAdjustmentService {

    /**
     * 调整销售订单行单价.
     *
     * <p>改价永远立即生效。超阈值时响应包含 priceWarning=true 和预警说明，不阻塞。
     *
     * @param factoryId 工厂 ID (安全隔离)
     * @param orderId   销售订单 ID
     * @param lineId    行 ID
     * @param request   改价请求 (新单价 + 原因类型 + 原因明细)
     * @param userId    操作人 ID
     * @return 改价结果 (含是否有超阈值预警)
     */
    AdjustPriceResponse adjustLinePrice(String factoryId, String orderId, Long lineId,
                                        AdjustPriceRequest request, Long userId);

    /**
     * 获取销售订单的改价历史.
     *
     * @param factoryId 工厂 ID
     * @param orderId   销售订单 ID
     * @return 改价记录列表 (按时间倒序)
     */
    List<SalesPriceAdjustmentRecordDTO> getPriceAdjustmentHistory(String factoryId, String orderId);
}
