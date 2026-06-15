package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;

import java.util.List;

/**
 * 销售订单行价格调整服务
 *
 * <p>支持:
 * <ul>
 *   <li>改价留痕 (老价/新价/操作人/原因)</li>
 *   <li>阈值触发审批 (降价 > 10% 或 涨价 > 20%)</li>
 *   <li>已发货/已开票行拒绝改价</li>
 *   <li>改价后重算 SO 总额</li>
 *   <li>幂等防重 (同一行已有 PENDING 审批则返回已有记录)</li>
 * </ul>
 */
public interface SalesPriceAdjustmentService {

    /**
     * 调整销售订单行单价.
     *
     * @param factoryId 工厂 ID (安全隔离)
     * @param orderId   销售订单 ID
     * @param lineId    行 ID
     * @param request   改价请求 (新单价 + 原因类型 + 原因明细)
     * @param userId    操作人 ID
     * @return 改价结果 (含是否触发审批)
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
