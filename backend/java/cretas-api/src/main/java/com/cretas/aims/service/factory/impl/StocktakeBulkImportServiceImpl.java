package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO.MatchedLine;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO.RowError;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeImportRowDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.factory.StocktakeBulkImportService;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 盘点批量导入服务实现。
 *
 * <p>复用铁律见接口 javadoc：确认走 {@link FactoryStocktakeService#initiate} + {@code updateItems}，
 * 不新建库存/过账逻辑。差异计算口径与 {@code FactoryStocktakeServiceImpl.updateItems}
 * 保持一致（scale=4 HALF_UP，differenceQty = actual - system）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StocktakeBulkImportServiceImpl implements StocktakeBulkImportService {

    private final MaterialBatchRepository materialBatchRepo;
    private final RawMaterialTypeRepository rawMaterialTypeRepo;
    private final FactoryWarehouseRepository warehouseRepo;
    private final FactoryStocktakeService stocktakeService;
    private final ExcelUtil excelUtil;

    private static final String SHEET_NAME = "库存盘点";

    // -------------------------------------------------------
    // 导出模板
    // -------------------------------------------------------

    @Override
    public byte[] exportTemplate(String factoryId, String warehouseId) {
        FactoryWarehouse warehouse = requireWarehouse(factoryId, warehouseId);
        List<MaterialBatch> batches = materialBatchRepo.findByFactoryIdAndWarehouseId(factoryId, warehouseId);
        Map<String, String> nameById = loadMaterialNames(batches);

        List<StocktakeImportRowDTO> rows = new ArrayList<>(batches.size());
        for (MaterialBatch batch : batches) {
            StocktakeImportRowDTO row = new StocktakeImportRowDTO();
            row.setMaterialName(nameById.getOrDefault(batch.getMaterialTypeId(), batch.getMaterialTypeId()));
            row.setBatchNumber(batch.getBatchNumber());
            row.setWarehouseName(warehouse.getName());
            row.setSystemQty(scale4(batch.getReceiptQuantity()));
            row.setUnit(batch.getQuantityUnit());
            row.setActualQty(null); // 留空待仓管填写
            rows.add(row);
        }
        log.info("盘点批量导入: 导出模板 factoryId={} warehouseId={} rows={}", factoryId, warehouseId, rows.size());
        return excelUtil.exportToExcel(rows, StocktakeImportRowDTO.class, SHEET_NAME);
    }

    // -------------------------------------------------------
    // 预览（read-only）
    // -------------------------------------------------------

    @Override
    public StocktakeBulkImportPreviewDTO preview(String factoryId, String warehouseId, InputStream inputStream) {
        FactoryWarehouse warehouse = requireWarehouse(factoryId, warehouseId);
        List<StocktakeImportRowDTO> rows = readRows(inputStream);
        return match(factoryId, warehouse, rows);
    }

    // -------------------------------------------------------
    // 确认（创建盘点 + 回填实盘 — 复用现有服务）
    // -------------------------------------------------------

    @Override
    @Transactional
    public StocktakeBulkImportPreviewDTO confirm(String factoryId, String warehouseId, String periodMonth,
                                                 String notes, FactoryStocktake.ImportMode importMode,
                                                 InputStream inputStream, Long userId) {
        FactoryStocktake.ImportMode mode = importMode != null ? importMode : FactoryStocktake.ImportMode.NORMAL;
        FactoryWarehouse warehouse = requireWarehouse(factoryId, warehouseId);
        List<StocktakeImportRowDTO> rows = readRows(inputStream);
        StocktakeBulkImportPreviewDTO preview = match(factoryId, warehouse, rows);
        preview.setPeriodMonth(periodMonth);

        // 无任何可回填的实盘数量 → 拒绝创建空盘点（诚实提示）
        boolean hasActual = preview.getMatchedLines().stream().anyMatch(l -> l.getActualQty() != null);
        if (!hasActual) {
            throw new BusinessException(400,
                    "没有任何有效的实盘数量可导入（匹配成功 " + preview.getMatchedCount()
                            + " 行，其中已填实盘 0 行，失败 " + preview.getErrorCount() + " 行）")
                    .withHint("请在模板「实盘数量」列填写后再导入");
        }

        // 1) 创建盘点任务（复用 initiate：快照账面 + 月底约束 + 同仓同月防重）
        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(warehouseId);
        req.setPeriodMonth(periodMonth);
        req.setNotes(notes != null && !notes.isBlank()
                ? notes
                : "批量导入盘点（匹配 " + preview.getMatchedCount() + " / 失败 " + preview.getErrorCount() + "）");
        // initiate 直接落 importMode（驱动 apply 过账科目）+ OPENING 跳过月底约束（Decision 4）。
        StocktakeDTO created = stocktakeService.initiate(factoryId, req, userId, mode);

        // 2) 建 materialBatchId → itemId 映射（来自 initiate 的快照明细）
        Map<String, String> itemIdByBatchId = new HashMap<>();
        if (created.getItems() != null) {
            for (StocktakeDTO.StocktakeItemDTO item : created.getItems()) {
                itemIdByBatchId.put(item.getMaterialBatchId(), item.getId());
            }
        }

        // 3) 回填实盘（复用 updateItems：内部重算 differenceQty / differenceType）
        List<StocktakeItemUpdateDTO> updates = new ArrayList<>();
        int unlinked = 0;
        for (MatchedLine line : preview.getMatchedLines()) {
            if (line.getActualQty() == null) {
                continue; // 未盘点行不回填
            }
            String itemId = itemIdByBatchId.get(line.getMaterialBatchId());
            if (itemId == null) {
                // 理论上不会发生（同一 initiate 快照）；诚实兜底为错误行而非静默
                unlinked++;
                preview.getErrors().add(new RowError(0, line.getBatchNumber(), line.getMaterialName(),
                        "批次在盘点快照中缺失（可能刚被消耗），已跳过"));
                continue;
            }
            StocktakeItemUpdateDTO u = new StocktakeItemUpdateDTO();
            u.setItemId(itemId);
            u.setActualQty(line.getActualQty());
            updates.add(u);
        }
        if (!updates.isEmpty()) {
            stocktakeService.updateItems(created.getId(), factoryId, updates, userId);
        }
        if (unlinked > 0) {
            preview.setErrorCount(preview.getErrors().size());
        }

        preview.setStocktakeId(created.getId());
        preview.setStocktakeNo(created.getStocktakeNo());
        log.info("盘点批量导入: 确认创建 factoryId={} stocktakeId={} 回填 {} 行 (跳过未盘 {} / 失败 {})",
                factoryId, created.getId(), updates.size(), preview.getSkippedCount(), preview.getErrorCount());
        return preview;
    }

    // -------------------------------------------------------
    // 核心匹配 + 差异计算（preview / confirm 共用，保证口径一致）
    // -------------------------------------------------------

    private StocktakeBulkImportPreviewDTO match(String factoryId, FactoryWarehouse warehouse,
                                                List<StocktakeImportRowDTO> rows) {
        List<MaterialBatch> batches = materialBatchRepo.findByFactoryIdAndWarehouseId(
                factoryId, warehouse.getId());
        Map<String, MaterialBatch> batchByNo = new HashMap<>();
        for (MaterialBatch b : batches) {
            if (b.getBatchNumber() != null) {
                batchByNo.put(b.getBatchNumber().trim(), b);
            }
        }
        Map<String, String> nameById = loadMaterialNames(batches);

        StocktakeBulkImportPreviewDTO dto = new StocktakeBulkImportPreviewDTO();
        dto.setWarehouseId(warehouse.getId());
        dto.setWarehouseName(warehouse.getName());
        dto.setTotalRows(rows.size());

        Set<String> seenBatchNos = new HashSet<>();
        int rowNum = 1; // 表头是第 1 行，数据从第 2 行起
        for (StocktakeImportRowDTO row : rows) {
            rowNum++;
            String batchNo = row.getBatchNumber() != null ? row.getBatchNumber().trim() : "";
            String rowMaterialName = row.getMaterialName();

            if (batchNo.isEmpty()) {
                dto.getErrors().add(new RowError(rowNum, "", rowMaterialName, "批次号为空，无法匹配库存"));
                continue;
            }
            if (!seenBatchNos.add(batchNo)) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName, "批次号在导入文件中重复出现"));
                continue;
            }
            MaterialBatch batch = batchByNo.get(batchNo);
            if (batch == null) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "批次号未在仓库[" + warehouse.getName() + "]找到当前库存（可能已消耗或录入错误）"));
                continue;
            }

            BigDecimal actual = row.getActualQty();
            if (actual != null && actual.compareTo(BigDecimal.ZERO) < 0) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName, "实盘数量不能为负数"));
                continue;
            }

            MatchedLine line = new MatchedLine();
            line.setMaterialBatchId(batch.getId());
            line.setBatchNumber(batchNo);
            line.setMaterialName(nameById.getOrDefault(batch.getMaterialTypeId(), batch.getMaterialTypeId()));
            line.setUnit(batch.getQuantityUnit());
            BigDecimal systemQty = scale4(batch.getReceiptQuantity());
            line.setSystemQty(systemQty);

            if (actual == null) {
                // 未盘点：不校正
                line.setActualQty(null);
                line.setDifferenceQty(null);
                line.setDifferenceType(null);
                dto.setSkippedCount(dto.getSkippedCount() + 1);
            } else {
                BigDecimal actual4 = scale4(actual);
                BigDecimal diff = actual4.subtract(systemQty).setScale(4, RoundingMode.HALF_UP);
                line.setActualQty(actual4);
                line.setDifferenceQty(diff);
                int cmp = diff.compareTo(BigDecimal.ZERO);
                if (cmp > 0) {
                    line.setDifferenceType("SURPLUS");
                    dto.setSurplusCount(dto.getSurplusCount() + 1);
                } else if (cmp < 0) {
                    line.setDifferenceType("SHORTAGE");
                    dto.setShortageCount(dto.getShortageCount() + 1);
                } else {
                    line.setDifferenceType("MATCH");
                    dto.setMatchCount(dto.getMatchCount() + 1);
                }
            }
            dto.getMatchedLines().add(line);
        }

        dto.setMatchedCount(dto.getMatchedLines().size());
        dto.setErrorCount(dto.getErrors().size());
        return dto;
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private FactoryWarehouse requireWarehouse(String factoryId, String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw new BusinessException(400, "仓库 ID 不能为空");
        }
        return warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "仓库不存在或不属于当前工厂: " + warehouseId));
    }

    private List<StocktakeImportRowDTO> readRows(InputStream inputStream) {
        List<StocktakeImportRowDTO> rows;
        try {
            rows = excelUtil.importFromExcel(inputStream, StocktakeImportRowDTO.class);
        } catch (RuntimeException e) {
            throw new BusinessException(400, "Excel 文件解析失败，请使用系统导出的模板: " + e.getMessage());
        }
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(400, "导入文件没有数据行，请先导出模板并填写实盘数量")
                    .withHint("点击「导出模板」获取当前库存后填写");
        }
        return rows;
    }

    private Map<String, String> loadMaterialNames(List<MaterialBatch> batches) {
        Set<String> typeIds = batches.stream()
                .map(MaterialBatch::getMaterialTypeId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> nameById = new HashMap<>();
        for (RawMaterialType t : rawMaterialTypeRepo.findAllById(typeIds)) {
            nameById.put(t.getId(), t.getName());
        }
        return nameById;
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v != null ? v.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
}
