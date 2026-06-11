package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.InventoryLedgerDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;

import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * SP11: 进销存台账查询服务.
 *
 * <p>提供按日期范围/物料类型的进销存聚合查询.
 * 金额字段通过 @PriceSensitive 对非财务角色自动遮蔽 (PriceFieldResponseAdvice 处理).
 *
 * <p>W8: 盘盈/盘损分列 — InventoryLedgerLineDTO 新增 stocktakeProfitQty / stocktakeLossQty
 * 分列展示, 同时提供 {@link com.cretas.aims.service.inventory.impl.InventoryLedgerServiceImpl#buildKingdeeMovementSummary}
 * 静态方法用于生成金蝶 per-movement 凭证摘要.
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

    /**
     * 导出进销存台账 xlsx (写入 out 流).
     *
     * <p>导出内容基于 {@link #getLedger} 的同源台账数据 (期初/入库/生产领用/销售出货/
     * 调拨进出/盘盈损/期末), <b>不是</b>凭证序时账. 修复审计 Tier0 #04 "货不对板":
     * 旧实现错调 VoucherExportService.exportSequentialLedger 导出凭证流水.
     *
     * <p>金额列 ({@code @PriceSensitive}) 仅在 {@code includePrices == true} 时写入;
     * 非财务角色 (无 procurement:price:view + finance:read_write) 导出的 xlsx 不含金额列,
     * 与 PriceFieldResponseAdvice 在 JSON 响应上的遮蔽行为对齐 (binary 流绕过 advice, 必须手动遮蔽).
     *
     * @param factoryId      工厂 ID
     * @param startDate      期间开始日 (inclusive)
     * @param endDate        期间结束日 (inclusive)
     * @param materialTypeId 可选: 按物料类型过滤; null = 全部
     * @param includePrices  是否写入金额列 (由调用方根据用户权限计算)
     * @param out            输出流 (xlsx binary)
     * @return 文件名 (含扩展名)
     */
    String exportInventoryLedger(String factoryId, LocalDate startDate, LocalDate endDate,
                                 String materialTypeId, boolean includePrices,
                                 OutputStream out) throws Exception;
}
