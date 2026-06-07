package com.cretas.aims.exception;

import com.cretas.aims.dto.bom.BomYieldStaleRowDTO;

import java.util.List;

/**
 * M10 乐观并发保护: BOM 出成率应用时发现数据库值已被其他操作修改.
 *
 * <p>由 {@link com.cretas.aims.service.bom.impl.BomYieldEstimateServiceImpl#recalculateApply}
 * 在预飞检查阶段抛出 (写入前, 不产生任何持久化副作用).
 * 前端应据 {@link #getStaleRows()} 列表提示用户重新预览后再应用.
 */
public class BomYieldStaleException extends RuntimeException {

    private final List<BomYieldStaleRowDTO> staleRows;

    public BomYieldStaleException(List<BomYieldStaleRowDTO> staleRows, String message) {
        super(message);
        this.staleRows = staleRows;
    }

    public List<BomYieldStaleRowDTO> getStaleRows() {
        return staleRows;
    }
}
