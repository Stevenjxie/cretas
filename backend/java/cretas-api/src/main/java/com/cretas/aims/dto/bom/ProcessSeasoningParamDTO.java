package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 调料配方按工序 (2026-07-13): 单道工序的锅序/注射量参数 (对应 bom_process_seasoning 一行)。
 *
 * <p>熟制工序用 {@code subsequentPotRatio} (第二锅起比例);
 * 注射工序用 {@code injectionAmountKg} (绝对注射量 kg)。
 */
@Data
public class ProcessSeasoningParamDTO {

    /** 归属工序 (work_processes.id)。 */
    @NotBlank(message = "workProcessId 不可为空")
    private String workProcessId;

    /** 熟制: 第二锅起比例, 如 0.3333。 */
    private BigDecimal subsequentPotRatio;

    /** 注射: 绝对注射量 (kg)。 */
    private BigDecimal injectionAmountKg;

    /** 备注 (可选)。 */
    private String notes;
}
