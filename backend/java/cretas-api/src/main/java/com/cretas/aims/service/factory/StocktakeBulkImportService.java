package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;

import java.io.InputStream;

/**
 * 盘点批量导入服务（张权客户需求 2026-07-02：月度整仓盘点提效）。
 *
 * <p>真实工作流：导出当前库存 → 固定模板填实盘数 → 导入系统 → 系统比对出盘盈/盘亏报告
 * → 确认 → 系统按实际数校正库存。
 *
 * <p>⚠️ 复用铁律：本服务 <b>不</b> 自建任何库存变动 / 会计过账逻辑。确认后走既有
 * {@link FactoryStocktakeService#initiate} + {@link FactoryStocktakeService#updateItems}
 * 创建盘点任务，后续 提交→审批→生效({@code apply}) 完全复用现有链路。
 */
public interface StocktakeBulkImportService {

    /**
     * 导出指定仓库当前账面库存的填报模板（实盘数量列留空）。
     *
     * @return xlsx 字节
     */
    byte[] exportTemplate(String factoryId, String warehouseId);

    /**
     * 预览：解析上传的 Excel，按批次号匹配当前库存并计算差异。read-only，不落库。
     * 匹配失败行以 errors 诚实上报，不静默丢弃。
     *
     * <p>{@code importMode=OPENING}（期初建账）时：未匹配现有批次的新物料行不再报错，而是标记为
     * 「将新建」(willCreate)——确认后从 0 盘盈建账（需物料名称/编码可在物料字典解析 + 填实盘数量）。
     * {@code NORMAL} 时保持原语义：未知批次一律报错。
     */
    StocktakeBulkImportPreviewDTO preview(String factoryId, String warehouseId,
                                          FactoryStocktake.ImportMode importMode, InputStream inputStream);

    /**
     * 确认：再次解析校验后，创建盘点任务并回填实盘数量（复用 initiate + updateItems）。
     * 返回带 stocktakeId 的预览结果，前端引导用户进入既有 提交审批 → 生效 流程。
     *
     * <p>幂等：同仓库同月已有进行中盘点时，{@code initiate} 抛 409 DUPLICATE_STOCKTAKE（复用既有防重）。
     *
     * @param importMode NORMAL=常规盘点 / OPENING=期初建账（决定 apply 时过账科目）
     */
    StocktakeBulkImportPreviewDTO confirm(String factoryId, String warehouseId, String periodMonth,
                                          String notes, FactoryStocktake.ImportMode importMode,
                                          InputStream inputStream, Long userId);
}
