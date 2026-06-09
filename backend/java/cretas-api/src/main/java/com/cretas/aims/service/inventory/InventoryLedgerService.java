package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.InventoryLedgerDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * SP11: 进销存台账查询服务.
 *
 * <p>提供按日期范围/物料类型的进销存聚合查询.
 * 金额字段通过 @PriceSensitive 对非财务角色自动遮蔽 (PriceFieldResponseAdvice 处理).
 */
public interface InventoryLedgerService {

    /**
     * 查询进销存台账.
     *
     * @param factoryId      工厂 ID
     * @param startDate      期间开始日 (inclusive)
     * @param endDate        期间结束日 (inclusive)
     * @param materialTypeId 可选: 按物料类型过滤; null = 全部
     * @return 每种物料一行的台账 DTO 列表
     */
    List<InventoryLedgerLineDTO> getLedger(String factoryId, LocalDate startDate,
                                           LocalDate endDate, String materialTypeId);
}
