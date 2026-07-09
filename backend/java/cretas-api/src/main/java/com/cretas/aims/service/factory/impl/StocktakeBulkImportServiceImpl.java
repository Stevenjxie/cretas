package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO.MatchedLine;
import com.cretas.aims.dto.factory.StocktakeBulkImportPreviewDTO.RowError;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeImportRowDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.dto.material.OpeningInventoryItem;
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
import com.cretas.aims.service.inventory.OpeningInventoryService;
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
    /** 期初建账建壳复用 (期初已并入盘点, Steve 架构决策 2026-07)。*/
    private final OpeningInventoryService openingInventoryService;

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
            // 🔴 Fix (🔒🔒 phantom-variance): 模板「账面数量」= 货架实物量 (receipt − used), 与 initiate()
            // 快照同口径。不用 gross receiptQuantity (已领用量会被误计为盘亏, #1201), 也不用可用量
            // getCurrentQuantity()(含 −reserved)(预留量会被误计为盘盈, #1201 overshoot 本次修):
            // 预留货物物理仍在货架, 仓管照实物数, 账面须= receipt − used (见 MaterialBatch.getPhysicalQuantity)。
            row.setSystemQty(scale4(batch.getPhysicalQuantity()));
            row.setUnit(batch.getQuantityUnit());
            row.setActualQty(null); // 留空待仓管填写
            rows.add(row);
        }
        // 期初建账 fool-proof (低文化用户): 仓库当前没有任何批次(通常是新工厂第一次建账)时,
        // 模板只有表头一片空白, 用户不知道"新物料怎么填"。加一行示例(批次号留空 = 新物料),
        // 直接演示"物料名称+数量+单价"三格怎么填。示例行物料名称含提示文字, 若用户没删除就导入,
        // 会在物料字典里查不到而诚实报错(不是静默生效), 前端预览表已能展示该错误行 + 原因,
        // 用户看得到"这行有问题"而不是无声失败。
        if (rows.isEmpty()) {
            StocktakeImportRowDTO example = new StocktakeImportRowDTO();
            example.setMaterialName("示例：白砂糖（请改成你的物料名称，删掉本行示例后再导入）");
            example.setBatchNumber(""); // 留空 = 新物料，从 0 建账
            example.setWarehouseName(warehouse.getName());
            example.setSystemQty(BigDecimal.ZERO);
            example.setUnit("kg");
            example.setActualQty(new BigDecimal("100")); // 示例：期初库存 100 kg
            example.setUnitPrice(new BigDecimal("6.50")); // 示例单价，可留空
            rows.add(example);
        }
        log.info("盘点批量导入: 导出模板 factoryId={} warehouseId={} rows={}", factoryId, warehouseId, rows.size());
        return excelUtil.exportToExcel(rows, StocktakeImportRowDTO.class, SHEET_NAME);
    }

    // -------------------------------------------------------
    // 预览（read-only）
    // -------------------------------------------------------

    @Override
    public StocktakeBulkImportPreviewDTO preview(String factoryId, String warehouseId,
            FactoryStocktake.ImportMode importMode, InputStream inputStream) {
        FactoryStocktake.ImportMode mode = importMode != null ? importMode : FactoryStocktake.ImportMode.NORMAL;
        FactoryWarehouse warehouse = requireWarehouse(factoryId, warehouseId);
        List<StocktakeImportRowDTO> rows = readRows(inputStream);
        return match(factoryId, warehouse, rows, mode);
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
        boolean opening = mode == FactoryStocktake.ImportMode.OPENING;
        FactoryWarehouse warehouse = requireWarehouse(factoryId, warehouseId);
        List<StocktakeImportRowDTO> rows = readRows(inputStream);
        StocktakeBulkImportPreviewDTO preview = match(factoryId, warehouse, rows, mode);
        preview.setPeriodMonth(periodMonth);

        // 无任何可回填的实盘数量 → 拒绝创建空盘点（诚实提示）。
        // 期初「将新建」行也带 actualQty（期初数量），因此同样被这里视为有效动作。
        boolean hasActual = preview.getMatchedLines().stream().anyMatch(l -> l.getActualQty() != null);
        if (!hasActual) {
            throw new BusinessException(400,
                    "没有任何有效的实盘数量可导入（匹配成功 " + preview.getMatchedCount()
                            + " 行，其中已填实盘 0 行，失败 " + preview.getErrorCount() + " 行）")
                    .withHint("请在模板「实盘数量」列填写后再导入");
        }

        // OPENING 期初建账：未匹配现有库存的新物料行，先建「空壳」批次 (数量=0, 不过凭证/不挂应付)。
        // 建壳后该批次被下面 initiate 快照 (systemQty=0)，回填实盘=期初数量 → 生效 apply 走盘盈机制
        // 把数量+价值补入并计入盘点同一张期初凭证 (借1403/贷4001)。confirm 整体 @Transactional：
        // 若 initiate 因同仓同月防重等抛异常，建壳一并回滚，不留孤儿空批次。
        if (opening) {
            String rawKey = "OPENSTK-" + warehouseId + "-" + (periodMonth != null ? periodMonth : "");
            String batchKey = rawKey.length() > 64 ? rawKey.substring(0, 64) : rawKey;
            for (StocktakeBulkImportPreviewDTO.MatchedLine line : preview.getMatchedLines()) {
                if (!line.isWillCreate()) {
                    continue;
                }
                OpeningInventoryItem item = new OpeningInventoryItem();
                item.setMaterialTypeId(line.getMaterialTypeId());
                item.setWarehouseId(warehouseId);
                item.setQuantity(line.getActualQty());   // 仅语义参考；建壳实际 receiptQuantity=0
                item.setUnitPrice(line.getUnitPrice());  // 诚实-null: 可空
                item.setQuantityUnit(line.getUnit());
                item.setBatchNumber(blankToNull(line.getBatchNumber()));
                MaterialBatch shell = openingInventoryService.createOpeningBatchShell(
                        factoryId, item, batchKey, userId);
                line.setMaterialBatchId(shell.getId());   // 回填真实 batchId，供快照映射
                line.setBatchNumber(shell.getBatchNumber());
            }
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
        log.info("盘点批量导入: 确认创建 factoryId={} stocktakeId={} mode={} 回填 {} 行 (跳过未盘 {} / 将新建 {} / 失败 {})",
                factoryId, created.getId(), mode, updates.size(),
                preview.getSkippedCount(), preview.getWillCreateCount(), preview.getErrorCount());
        return preview;
    }

    // -------------------------------------------------------
    // 核心匹配 + 差异计算（preview / confirm 共用，保证口径一致）
    // -------------------------------------------------------

    private StocktakeBulkImportPreviewDTO match(String factoryId, FactoryWarehouse warehouse,
                                                List<StocktakeImportRowDTO> rows,
                                                FactoryStocktake.ImportMode mode) {
        boolean opening = mode == FactoryStocktake.ImportMode.OPENING;
        List<MaterialBatch> batches = materialBatchRepo.findByFactoryIdAndWarehouseId(
                factoryId, warehouse.getId());
        Map<String, MaterialBatch> batchByNo = new HashMap<>();
        for (MaterialBatch b : batches) {
            if (b.getBatchNumber() != null) {
                batchByNo.put(b.getBatchNumber().trim(), b);
            }
        }
        Map<String, String> nameById = loadMaterialNames(batches);
        // OPENING 才需要名称/编码 → 物料类型 解析 (create-from-zero)。
        Map<String, RawMaterialType> typeByKey = opening ? loadMaterialTypeIndex(factoryId) : Map.of();

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

            // 非空批次号在文件内去重 (空批次号在 OPENING 表示"新物料自动生成批次号", 不去重)。
            if (!batchNo.isEmpty() && !seenBatchNos.add(batchNo)) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName, "批次号在导入文件中重复出现"));
                continue;
            }

            MaterialBatch batch = batchNo.isEmpty() ? null : batchByNo.get(batchNo);

            if (batch != null) {
                // ---- 既有批次校正 (ADJUST, NORMAL/OPENING 共用) ----
                BigDecimal actual = row.getActualQty();
                if (actual != null && actual.compareTo(BigDecimal.ZERO) < 0) {
                    dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName, "实盘数量不能为负数"));
                    continue;
                }
                if (hasMoreThanTwoDecimals(actual)) {
                    dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                            "实盘数量最多保留2位小数，请改为两位以内后再导入（当前: " + actual.toPlainString() + "）"));
                    continue;
                }
                MatchedLine line = new MatchedLine();
                line.setMaterialBatchId(batch.getId());
                line.setBatchNumber(batchNo);
                line.setMaterialName(nameById.getOrDefault(batch.getMaterialTypeId(), batch.getMaterialTypeId()));
                line.setUnit(batch.getQuantityUnit());
                // 🔴 Fix (🔒🔒 phantom-variance): 预览「账面数量」+ 差异计算基准 = 货架实物量
                // (receipt − used), 与 initiate() 快照 + updateItems() 重算 + apply 生效口径一致。
                // 不用 gross (已领用误计盘亏, #1201), 也不用可用量 getCurrentQuantity()(预留误计盘盈,
                // #1201 overshoot 本次修) —— 否则预览差异与 apply 实际生效差异不一致, 误导仓管 + 假盘盈/盘亏。
                BigDecimal systemQty = scale4(batch.getPhysicalQuantity());
                line.setSystemQty(systemQty);
                if (actual == null) {
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
                continue;
            }

            // ---- batch == null：未匹配现有库存 ----
            if (!opening) {
                // NORMAL：语义不变——未知批次一律诚实报错。
                if (batchNo.isEmpty()) {
                    dto.getErrors().add(new RowError(rowNum, "", rowMaterialName, "批次号为空，无法匹配库存"));
                } else {
                    dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                            "批次号未在仓库[" + warehouse.getName() + "]找到当前库存（可能已消耗或录入错误）"));
                }
                continue;
            }

            // OPENING 期初建账：新物料行 → 从 0 盘盈建账 (create-from-zero)。
            RawMaterialType type = resolveMaterialType(typeByKey, rowMaterialName);
            if (type == null) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "物料名称/编码未匹配到物料字典，无法新建期初批次（请核对名称，或先在「原料类型字典」创建该物料）"));
                continue;
            }
            BigDecimal actual = row.getActualQty();
            if (actual == null) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "期初新建物料必须在「实盘数量」列填写期初数量"));
                continue;
            }
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "期初新建物料的数量必须大于 0"));
                continue;
            }
            if (hasMoreThanTwoDecimals(actual)) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "期初数量最多保留2位小数，请改为两位以内后再导入（当前: " + actual.toPlainString() + "）"));
                continue;
            }
            if (hasMoreThanTwoDecimals(row.getUnitPrice())) {
                dto.getErrors().add(new RowError(rowNum, batchNo, rowMaterialName,
                        "期初单价最多保留2位小数，请改为两位以内后再导入（当前: " + row.getUnitPrice().toPlainString() + "）"));
                continue;
            }

            MatchedLine line = new MatchedLine();
            line.setMaterialBatchId(null);   // 建壳后 confirm 回填
            line.setBatchNumber(batchNo);    // 可空 → 建壳时系统生成
            line.setMaterialName(type.getName());
            line.setMaterialTypeId(type.getId());
            String unit = (row.getUnit() != null && !row.getUnit().isBlank())
                    ? row.getUnit()
                    : (type.getUnit() != null ? type.getUnit() : "kg");
            line.setUnit(unit);
            line.setSystemQty(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            BigDecimal actual4 = scale4(actual);
            line.setActualQty(actual4);
            line.setDifferenceQty(actual4);   // 从 0 盘盈
            line.setDifferenceType("SURPLUS");
            line.setUnitPrice(row.getUnitPrice());   // 诚实-null
            line.setWillCreate(true);
            dto.getMatchedLines().add(line);
            dto.setSurplusCount(dto.getSurplusCount() + 1);
            dto.setWillCreateCount(dto.getWillCreateCount() + 1);
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

    /**
     * OPENING 名称/编码 → RawMaterialType 索引。key = 规范化(去空格+小写) 的 name 与 code。
     * 名称优先于编码；仅精确匹配（create 会过财务凭证，不做模糊猜测，未匹配则诚实报错）。
     */
    private Map<String, RawMaterialType> loadMaterialTypeIndex(String factoryId) {
        List<RawMaterialType> types = rawMaterialTypeRepo.findByFactoryId(factoryId);
        Map<String, RawMaterialType> index = new HashMap<>();
        // 先放 code（低优先），再放 name（覆盖 code，name 优先）。
        for (RawMaterialType t : types) {
            if (t.getCode() != null && !t.getCode().isBlank()) {
                index.putIfAbsent(normalizeKey(t.getCode()), t);
            }
        }
        for (RawMaterialType t : types) {
            if (t.getName() != null && !t.getName().isBlank()) {
                index.put(normalizeKey(t.getName()), t);
            }
        }
        return index;
    }

    private RawMaterialType resolveMaterialType(Map<String, RawMaterialType> typeByKey, String raw) {
        if (raw == null || raw.isBlank() || typeByKey.isEmpty()) {
            return null;
        }
        return typeByKey.get(normalizeKey(raw));
    }

    private static String normalizeKey(String s) {
        return s.trim().toLowerCase();
    }

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s : null;
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

    private static boolean hasMoreThanTwoDecimals(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() > 2;
    }
}
