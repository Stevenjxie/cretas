package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BOM 出成率批量应用结果 DTO.
 *
 * <p>POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-apply 的响应 data 体.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldApplyResultDTO {

    /** 实际更新的行数 (RAW 主料行, 仅出成率确实发生变化的行) */
    private int applied;

    /** 本次写入的 BomChangeLog ID 列表 */
    private List<String> changeLogIds;
}
