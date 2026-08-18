package com.cretas.aims.dto.material;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「把这个物料标记成副产」的请求体 —— 只带这一个事实。
 *
 * <p>⚠️ 刻意不复用 {@link RawMaterialTypeDTO}: 那个 DTO 上有 {@code @NotBlank name},
 * 而控制器的 {@code @Valid} 在进 service 之前就跑完。于是「只发 isByproduct」的部分更新
 * 恒定被 400「原材料名称不能为空」拒掉 —— service 里的 null-tolerant 分支是**到不了的代码**。
 * 画布上「标记并选入」点了报「原材料名称不能为空」就是这个成因。
 *
 * <p>放宽 {@code RawMaterialTypeDTO} 的 {@code @NotBlank} 不是解 —— 那条约束在**新建**
 * 物料时是真的在守东西。所以把「标记副产」做成一个自带契约的窄动作。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "副产标记请求")
public class ByproductMarkRequest {

    /** 不给默认值 —— 「没传」和「传了 false」是两件事, 不猜。 */
    @NotNull(message = "副产标记不能为空")
    @Schema(description = "true=标记为副产, false=取消副产标记", example = "true")
    private Boolean isByproduct;
}
