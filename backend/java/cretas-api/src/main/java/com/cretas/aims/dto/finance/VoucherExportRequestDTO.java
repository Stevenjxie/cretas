package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.enums.VoucherExportFileFormat;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * SP11: 凭证序时账导出请求体.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherExportRequestDTO {

    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;

    @NotNull(message = "目标系统不能为空")
    private VoucherTargetSystem targetSystem;

    /** 可选: 指定 VoucherExportConfig id; 不传则按 targetSystem 取当前工厂配置 */
    private String configId;

    /**
     * 可选: 金蝶导入模板文件格式 (XLSX / XLS / DBF). 仅对金蝶导入模板导出生效.
     * 不传默认 XLSX (向后兼容). 详见 {@link VoucherExportFileFormat}.
     */
    private VoucherExportFileFormat exportFormat;
}
