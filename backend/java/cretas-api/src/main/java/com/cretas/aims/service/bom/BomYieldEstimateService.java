package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;

import java.util.List;

/**
 * BOM 出成率评估服务.
 *
 * <p>三个端点对应三个业务场景:
 * <ol>
 *   <li>{@link #estimateForProduct} — 单产品评估, 返回建议出成率 + 样本统计 (只读)</li>
 *   <li>{@link #recalculatePreview} — 批量预览, 对比当前 vs 建议值 (只读)</li>
 *   <li>{@link #recalculateApply} — 用户确认后批量写入 (写操作, 写 BomChangeLog)</li>
 * </ol>
 *
 * <p><b>factoryId 隔离</b>: 所有方法必须严格限定在 factoryId 范围内, 绝不触及其他租户数据.
 */
public interface BomYieldEstimateService {

    /**
     * 单产品出成率评估 (只读).
     *
     * <p>基于该产品最近 N=10 个已完成批次的 cumulativeYieldRate, 计算 P50 中位数作为建议出成率.
     * 有效样本 ≥3 才给出 suggestedYieldRate; 否则 reason=INSUFFICIENT_SAMPLES.
     *
     * @param factoryId       工厂 ID (租户隔离)
     * @param productTypeId   产品类型 ID
     * @param materialCategory 物料分类 (当前仅 "RAW")
     * @return 出成率评估结果
     */
    BomYieldEstimateDTO estimateForProduct(String factoryId, String productTypeId, String materialCategory);

    /**
     * 批量预览 (只读, 不写).
     *
     * <p>对工厂内所有有 BOM 的产品 (或指定列表) 评估出成率, 返回可更新/不足样本/跳过 三种状态行.
     *
     * @param factoryId      工厂 ID
     * @param productTypeIds 指定产品列表; null 或空表示全部有 BOM 的产品
     * @return 预览行列表
     */
    List<BomYieldPreviewItemDTO> recalculatePreview(String factoryId, List<String> productTypeIds);

    /**
     * 批量应用用户选中的行 (写操作).
     *
     * <p>仅更新 RAW 主料行的 yield_rate; 每次变更写一条 BomChangeLog (old→new).
     * 需要 production:read_write 权限 (由 Controller 层 @RequirePermission 门控).
     *
     * @param factoryId 工厂 ID
     * @param requests  用户选中的行列表
     * @return 应用结果 {applied, changeLogIds}
     */
    BomYieldApplyResultDTO recalculateApply(String factoryId, List<BomYieldApplyRequest> requests);
}
