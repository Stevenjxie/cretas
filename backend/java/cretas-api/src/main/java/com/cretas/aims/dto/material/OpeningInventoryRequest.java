package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 期初建账 (opening inventory onboarding) 批量录入请求。
 *
 * <p>一次调用建立多条起始库存 (支持 200+ 行, 六膳门 215 个物料一次导入) + 过<b>一张</b>期初凭证
 * (借 1403 原材料 / 贷 4001 实收资本 = Σ数量×单价)。<b>不产生任何应付账款</b> —— 这正是它跟
 * 采购入库 (会挂供应商应付) 的本质区别, 修复客户被迫走采购入库建账导致的 ¥436k 幽灵应付。
 *
 * <p><b>幂等</b>: 相同 {@code batchKey} (或相同内容自动派生的 key) 重复提交只建一次, 不双建批次/不双过账。
 */
@Data
@Schema(description = "期初建账批量录入请求 (建批次 + 过 借1403/贷4001 期初凭证, 无应付)")
public class OpeningInventoryRequest {

    @Schema(description = "期初库存行 (每行一个原料在一个仓库的起始数量)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "期初建账明细不能为空")
    @Valid
    private List<OpeningInventoryItem> items;

    @Schema(description = "备注 (可空, 写入凭证摘要 + 批次备注)")
    @Size(max = 500)
    private String remark;

    /**
     * 幂等业务键。传入则用它做去重键; 不传则由内容 (工厂 + 全部行签名) 自动派生稳定 hash。
     * 相同 key 重复提交 → 返回已有结果, 不双建批次也不双过凭证。
     */
    @Schema(description = "幂等业务键 (可空; 不传则按内容自动派生, 防重复提交双建)")
    @Size(max = 64)
    private String batchKey;
}
