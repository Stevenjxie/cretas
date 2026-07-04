package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 核对结单「原料领用」下拉 BOM 预过滤 (防呆 Rule 1: 预先显示边界, 不事后报错).
 *
 * <p>后端 {@code ensureMaterialBatchAllowedForSettlement}（结单提交时的写路径守卫）在提交
 * 后才 409 拒绝不属于产品当前 BOM 的原料批次。本只读端点把<b>同一份判定逻辑</b>
 * （见 {@code ProductionPlanServiceImpl#resolveBomEligibilityForSettlement}）提前暴露给前端，
 * 让「核对结单」dialog 的原料领用下拉能在用户选择前就只列出 BOM 允许的批次 —— 而不是选完
 * 提交才告诉他选错了。
 *
 * <p>后端写路径守卫保持不变，作为兜底防线 (defense in depth)；本响应只是同一逻辑的读路径镜像。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionSettlementBomEligibilityResponse {

    /**
     * 该计划的产品是否存在 BOM 限制。
     * <p>false = 计划未关联 productTypeId（或 BOM 模块未启用），原料领用不受限，前端显示全部批次。
     */
    private boolean restricted;

    /**
     * restricted=true 时，该产品是否找到当前生效 BOM。
     * <p>false = 没有 BOM（镜像后端 409 "该产品没有当前 BOM，不能直接核对原料领用"），
     * 前端应该阻止原料领用（显示空列表 + 引导去配置 BOM），而不是退化成显示全部批次。
     */
    private boolean bomFound;

    /**
     * BOM 允许的原料 materialTypeId 集合（按 BOM 明细行去重, 顺序不保证）。
     * <p>restricted=false 时忽略此字段（未受限，不代表"BOM 是空的"）。
     * <p>restricted=true 且 bomFound=true 但此列表为空 = 该 BOM 没有原料明细
     * （镜像后端 409 "该产品当前 BOM 没有原料明细，不能直接核对原料领用"）。
     */
    @Builder.Default
    private List<String> materialTypeIds = new ArrayList<>();
}
