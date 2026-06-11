package com.cretas.aims.dto.factory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建盐化仓扣量请求 DTO (SP7 F11).
 *
 * <p>防呆 (fool-proof-design Rule 1+2):
 * <ul>
 *   <li>前端应先 GET /inventory/by-warehouse?warehouseId= 确认当前库存, 显示可扣上限</li>
 *   <li>orderNumber 由调用方自建(谁盐化谁建), 系统作幂等查重防重复提交</li>
 * </ul>
 */
@Data
public class CreateSaltedDeductionRequest {

    /** 盐化仓 ID (must be type=SALTED) */
    @NotBlank(message = "warehouseId 不能为空")
    private String warehouseId;

    /** 被扣减的原料批次 ID */
    @NotBlank(message = "materialBatchId 不能为空")
    private String materialBatchId;

    /** 盐化供单号 (谁盐化谁建; 幂等去重 key) */
    @NotBlank(message = "orderNumber 不能为空")
    private String orderNumber;

    /** 扣量日期 */
    @NotNull(message = "deductionDate 不能为空")
    private LocalDate deductionDate;

    /** 扣减数量 (>0) */
    @NotNull(message = "quantity 不能为空")
    @DecimalMin(value = "0.001", message = "扣减数量必须 > 0")
    private BigDecimal quantity;

    /** 单价 (可选；期中出库盘账勾稽) */
    private BigDecimal unitPrice;

    /** 外商/代加工客户名称 */
    private String customerName;

    /** 备注 */
    private String remarks;
}
