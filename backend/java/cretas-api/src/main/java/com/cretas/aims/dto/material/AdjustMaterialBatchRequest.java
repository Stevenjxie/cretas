package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 调整原材料批次库存请求
 *
 * 🔴 2026-08-13 订正。这个 DTO 原来公布的是一套【增减量】契约
 * ({@code adjustmentType: INCREASE/DECREASE} + {@code quantity: 调整数量}),
 * 而接口实现的是【绝对值】语义 —— {@code MaterialBatchController#adjustBatchQuantity}
 * 把 {@code quantity} 原样当成 URL 参数 {@code newQuantity} 传给 service,
 * service 再算 {@code adjustment = newQuantity − 当前剩余}。照原文档发
 * {@code {adjustmentType:"DECREASE", quantity:50}} 实际执行的是「把剩余量设为 50」,
 * 方向相反, 且在剩余量小于 50 时**凭空造出库存**, 落库的审计行还被标成 INCREASE。
 *
 * ⚠️ 这里原先那 4 个 {@code @NotBlank/@NotNull/@DecimalMin} 一条都没生效 ——
 * controller 的 {@code @RequestBody} 上没有 {@code @Valid}。**没有把它们激活**,
 * 因为真实契约横跨 URL 参数与 body 两条来源(URL 传了 newQuantity 时 body 的
 * quantity 本就可以为空), 逐字段 bean validation 表达不了, 激活反而会误伤。
 * 校验改为写在 controller 里, 见该方法内的三道 guard。此处只保留**如实的**文档。
 */
@Data
@Schema(description = "调整原材料批次库存请求")
public class AdjustMaterialBatchRequest {

    @Schema(description = "⛔ 已不受理: 本接口没有增减量语义。传了会被 400 拒绝, "
            + "以免做出方向相反的静默写入。请改用 quantity 传【调整后应剩多少】。",
            deprecated = true)
    private String adjustmentType;

    @Schema(description = "调整后的剩余数量(绝对值, 不是增减量), 必须 ≥ 0。"
            + "例: 批次剩 53 想减 50, 传 3。与 URL 参数 newQuantity 二选一, URL 参数优先。",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private BigDecimal quantity;

    @Schema(description = "调整原因(必填, 写入调整审计记录)。与 notes 拼接后不超过 255 字。",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "盘点调整")
    private String reason;

    @Schema(description = "备注。审计表没有独立入口, 由 controller 拼进 reason 一并落库 "
            + "(与 web-admin 对「其他」原因的既有做法一致)。")
    private String notes;
}
