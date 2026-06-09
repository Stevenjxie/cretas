package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.inventory.WastageReport;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建报损单请求 DTO (SP7 T1).
 */
@Data
public class CreateWastageReportRequest {

    @NotNull(message = "报损轨道不能为空")
    private WastageReport.TrackType trackType;

    /** WAREHOUSE 轨必填 */
    private String warehouseId;

    @NotBlank(message = "批次 ID 不能为空")
    private String materialBatchId;

    /** 冗余品名，展示用 */
    private String rawMaterialTypeId;

    @NotNull(message = "报损数量不能为空")
    @DecimalMin(value = "0.0001", message = "报损数量必须大于0")
    private BigDecimal wastageQty;

    @NotNull(message = "报损原因不能为空")
    private WastageReport.WastageReason wastageReason;

    /** 选 OTHER 时必填 */
    private String reasonDetail;

    /**
     * 照片 URL JSON 数组，至少1张。
     * 格式: ["https://oss.../photo1.jpg"]
     * 注意：照片校验在 service 层执行（字符串解析后验证元素数量）
     */
    @NotBlank(message = "报损单必须包含照片信息")
    private String photoUrls;

    private String notes;
}
