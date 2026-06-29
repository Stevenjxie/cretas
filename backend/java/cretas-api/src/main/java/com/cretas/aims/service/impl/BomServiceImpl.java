package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.config.EffectiveField;
import com.cretas.aims.dto.config.EffectiveModuleConfig;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.entity.bom.OverheadCostConfig;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.config.FactoryConfigService;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BOM 成本计算服务实现
 *
 * 成本计算公式:
 * - 原料成本 = 成品含量 / 出成率 * 单价
 * - 人工成本 = 工序单价 * 操作量
 * - 均摊费用 = 单价 * 分摊量
 * - 总成本 = 原料成本 + 人工成本 + 均摊费用
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
@Service
@RequiredArgsConstructor
public class BomServiceImpl implements BomService {

    private static final Logger log = LoggerFactory.getLogger(BomServiceImpl.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String COST_CALIBER_PRE_TAX = "PRE_TAX";
    private static final String PRE_TAX_CALIBER_HINT =
            "BOM成本按未税价核算；BOM unitPrice 为未税价，含税采购价需先按税率换算税前。";
    private static final String MISSING_TAX_RATE_HINT =
            "部分物料缺税率，请补齐税率用于含税/未税来源追踪；成本仍按已存未税 unitPrice 计算。";

    private final BomItemRepository bomItemRepository;
    private final LaborCostConfigRepository laborCostConfigRepository;
    private final OverheadCostConfigRepository overheadCostConfigRepository;

    /** P1-9 BOM 变更痕迹记录, 可选注入 (老环境未迁移时不阻塞) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomChangeLogRepository bomChangeLogRepository;

    /** Canvas Config — 可选注入，模块未部署时不影响现有逻辑 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FactoryConfigService factoryConfigService;

    /** Canvas V2: DB-driven formula engine */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.FormulaEngine formulaEngine;

    /** Canvas V2: DB-driven default value resolver */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DefaultValueResolver defaultValueResolver;

    /**
     * R13 (2026-06-22): 计量单位换算 — 成本计算前把 BOM 行用量按重量单位归一到
     * 物料主数据单位, 使其与"按主数据单位计价"的 unitPrice 口径一致.
     * 可选注入: 单测旧构造器 (3-arg) 不传, null → 退化为不归一 (旧行为, 不崩).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.UnitConversionService unitConversionService;

    /**
     * R13: 物料主数据仓库 — 取 RawMaterialType.unit 作为计价单位标尺.
     * 可选注入, 同上.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.RawMaterialTypeRepository rawMaterialTypeRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    private void runBomValidation(String factoryId, String operation, BomItem bomItem) {
        if (validationRuleEvaluator == null) return;
        try {
            java.util.Map<String, Object> context = new java.util.HashMap<>();
            context.put("productTypeId", bomItem.getProductTypeId());
            context.put("materialTypeId", bomItem.getMaterialTypeId());
            context.put("yieldRate", bomItem.getYieldRate());
            context.put("taxRate", bomItem.getTaxRate());
            context.put("standardQuantity", bomItem.getStandardQuantity());
            context.put("sortOrder", bomItem.getSortOrder());
            validationRuleEvaluator.validate(factoryId, "bom", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("BOM canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    // ============ BOM Items ============

    @Override
    @Transactional(readOnly = true)
    public List<BomItem> getBomItemsByProduct(String factoryId, String productTypeId) {
        log.debug("获取产品BOM项目: factoryId={}, productTypeId={}", factoryId, productTypeId);
        return bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
            factoryId, productTypeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomItem> getAllBomItems(String factoryId) {
        log.debug("获取工厂所有BOM项目: factoryId={}", factoryId);
        return bomItemRepository.findByFactoryIdAndDeletedAtIsNullOrderByProductTypeIdAscSortOrderAsc(factoryId);
    }

    @Override
    @Transactional
    public BomItem saveBomItem(BomItem bomItem) {
        log.info("保存BOM项目: factoryId={}, productTypeId={}, materialTypeId={}",
            bomItem.getFactoryId(), bomItem.getProductTypeId(), bomItem.getMaterialTypeId());

        // BUG-3 fix (depth-e2e qa-v2.4, PR #370): standardQuantity 必须 > 0
        // 之前 entity / canvas validator 都未拦截 standardQuantity=0, 产生无意义 BOM 行.
        // 服务端 hard-rule 拒绝, 比依赖 canvas data-driven 规则更可靠 (validationRuleEvaluator
        // 可能未配置, 此处确保所有 factory 都有保护).
        if (bomItem.getStandardQuantity() == null
                || bomItem.getStandardQuantity().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "成品克数必须大于 0 (当前: "
                            + (bomItem.getStandardQuantity() == null ? "null" : bomItem.getStandardQuantity())
                            + ")");
        }

        // Canvas V2: DB-driven validation (runs before defaults so rules like "#yieldRate > 0" can catch invalid user input)
        String operation = (bomItem.getId() == null) ? "CREATE" : "UPDATE";
        runBomValidation(bomItem.getFactoryId(), operation, bomItem);

        // P1-9 BOM 痕迹追踪: 如果是 UPDATE, 先查老值做 snapshot
        BomItem oldSnapshot = null;
        if (bomItem.getId() != null) {
            oldSnapshot = bomItemRepository.findById(bomItem.getId()).orElse(null);
        }

        // 设置默认值（优先从 Canvas Config 读取，不可用时使用硬编码 fallback）
        // B1 (BOM yield null sentinel removed): yieldRate null = 待评估, 持久化 null,
        // 不再强制填充 100.00. getActualQuantity() 已对 null yieldRate 回退到 standardQuantity.
        if (bomItem.getYieldRate() == null) {
            // Canvas config override only (factory may configure a custom default).
            // If no config, persist null (待评估 signal) instead of hardcoded 100.00.
            Object configDefault = getConfigDefault(bomItem.getFactoryId(), "yieldRate", null);
            if (configDefault instanceof Number) {
                bomItem.setYieldRate(new BigDecimal(configDefault.toString()));
            }
            // else: leave null (待评估)
        }
        if (bomItem.getTaxRate() == null) {
            Object configDefault = getConfigDefault(bomItem.getFactoryId(), "taxRate", BigDecimal.ZERO);
            bomItem.setTaxRate(configDefault instanceof Number
                    ? new BigDecimal(configDefault.toString())
                    : BigDecimal.ZERO);
        }
        if (bomItem.getSortOrder() == null) {
            Object configDefault = getConfigDefault(bomItem.getFactoryId(), "sortOrder", 0);
            bomItem.setSortOrder(configDefault instanceof Number
                    ? ((Number) configDefault).intValue()
                    : 0);
        }

        BomItem saved = bomItemRepository.save(bomItem);
        log.info("BOM项目保存成功: id={}", saved.getId());

        // D4 Path B (2026-05-10 customer meeting, PR #309 A2=B): BomExpansionService.expandBOM
        // 现已优先读 bom_items 表 (BomItem)，本次保存的 BOM 数据将被生产计划展开直接使用。
        // RPF (MaterialProductConversion) 仅作 fallback (老工厂数据无 BOM 配置时沿用)。
        // 详见 docs/architecture/2026-05-10-rpf-vs-bomitem-divergence.md §7
        log.info("[D4-B active] BOM 已保存 (productTypeId={}), 将被生产计划展开 (BomExpansionService) 直接使用",
                saved.getProductTypeId());

        // P1-9 写 BomChangeLog (best-effort, 失败不影响主业务)
        recordBomChange(saved, oldSnapshot,
                oldSnapshot == null ? BomChangeLog.ChangeType.CREATE : BomChangeLog.ChangeType.UPDATE);

        return saved;
    }

    /**
     * P1-9 写 BomChangeLog (v1 §2.2.6 BOM 变更痕迹追踪).
     * best-effort — 任何异常不影响主业务.
     */
    private void recordBomChange(BomItem newItem, BomItem oldItem, BomChangeLog.ChangeType type) {
        if (bomChangeLogRepository == null) return;
        try {
            BomChangeLog log = new BomChangeLog();
            log.setFactoryId(newItem != null ? newItem.getFactoryId() : (oldItem != null ? oldItem.getFactoryId() : null));
            log.setBomId(newItem != null ? newItem.getProductTypeId() : (oldItem != null ? oldItem.getProductTypeId() : null));
            log.setBomItemId(newItem != null ? newItem.getId() : (oldItem != null ? oldItem.getId() : null));
            log.setChangeType(type);
            log.setOldValue(snapshotBomItem(oldItem));
            log.setNewValue(snapshotBomItem(newItem));
            bomChangeLogRepository.save(log);
        } catch (Exception e) {
            BomServiceImpl.log.warn("[P1-9] BomChangeLog 写入失败 (non-blocking): {}", e.getMessage());
        }
    }

    private Map<String, Object> snapshotBomItem(BomItem item) {
        if (item == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", item.getId());
        snapshot.put("productTypeId", item.getProductTypeId());
        snapshot.put("productName", item.getProductName());
        snapshot.put("materialTypeId", item.getMaterialTypeId());
        snapshot.put("materialName", item.getMaterialName());
        snapshot.put("materialCategory", item.getMaterialCategory());
        snapshot.put("standardQuantity", item.getStandardQuantity());
        snapshot.put("yieldRate", item.getYieldRate());
        snapshot.put("unit", item.getUnit());
        snapshot.put("unitPrice", item.getUnitPrice());
        snapshot.put("taxRate", item.getTaxRate());
        snapshot.put("sortOrder", item.getSortOrder());
        snapshot.put("remark", item.getRemark());
        return snapshot;
    }

    @Override
    @Transactional
    public List<BomItem> saveBomItems(List<BomItem> bomItems) {
        log.info("批量保存BOM项目: count={}", bomItems.size());
        return bomItems.stream()
            .map(this::saveBomItem)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public com.cretas.aims.dto.bom.BomBatchImportResult batchImportBomItems(
            String factoryId, com.cretas.aims.dto.bom.BomBatchImportRequest request) {
        String productTypeId = request.getProductTypeId();
        List<com.cretas.aims.dto.bom.BomBatchImportRequest.Row> rows = request.getItems();
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new com.cretas.aims.exception.BusinessException(400, "productTypeId 不可为空");
        }
        if (rows == null || rows.isEmpty()) {
            throw new com.cretas.aims.exception.BusinessException(400, "导入明细不可为空");
        }
        List<com.cretas.aims.entity.RawMaterialType> types = rawMaterialTypeRepository.findByFactoryId(factoryId);

        List<BomItem> toInsert = new ArrayList<>();
        List<com.cretas.aims.dto.bom.BomBatchImportResult.RowResult> results = new ArrayList<>();
        boolean anyError = false;
        int idx = 0;
        for (com.cretas.aims.dto.bom.BomBatchImportRequest.Row r : rows) {
            idx++;
            try {
                String mtId = r.getMaterialTypeId();
                String matName = r.getMaterialName();
                if (mtId == null || mtId.isBlank()) {
                    final String mn = matName;
                    if (mn == null || mn.isBlank()) {
                        throw new com.cretas.aims.exception.BusinessException(400, "物料名与物料类型ID 至少填一个");
                    }
                    List<com.cretas.aims.entity.RawMaterialType> exact = types.stream()
                            .filter(t -> mn.equals(t.getName())).collect(Collectors.toList());
                    List<com.cretas.aims.entity.RawMaterialType> matches = !exact.isEmpty() ? exact
                            : types.stream().filter(t -> t.getName() != null
                                    && (t.getName().contains(mn) || mn.contains(t.getName())))
                              .collect(Collectors.toList());
                    if (matches.isEmpty()) {
                        throw new com.cretas.aims.exception.BusinessException(400,
                                "物料 '" + mn + "' 匹配不到原料类型, 请先建该原料类型或填 materialTypeId");
                    }
                    if (matches.size() > 1) {
                        throw new com.cretas.aims.exception.BusinessException(400,
                                "物料 '" + mn + "' 匹配到多个原料类型(" + matches.size() + "), 请填明确 materialTypeId");
                    }
                    mtId = matches.get(0).getId();
                    if (matName == null || matName.isBlank()) matName = matches.get(0).getName();
                }
                if (r.getStandardQuantity() == null || r.getStandardQuantity().signum() <= 0) {
                    throw new com.cretas.aims.exception.BusinessException(400, "成品含量 (standardQuantity) 必须 > 0");
                }
                String cat = (r.getMaterialCategory() == null || r.getMaterialCategory().isBlank())
                        ? "RAW" : r.getMaterialCategory().trim().toUpperCase();
                if (!java.util.Set.of("RAW", "AUXILIARY", "PACKAGING").contains(cat)) {
                    throw new com.cretas.aims.exception.BusinessException(400,
                            "物料类别须为 RAW/AUXILIARY/PACKAGING (当前: " + cat + ")");
                }
                String unit = (r.getUnit() == null || r.getUnit().isBlank())
                        ? ("PACKAGING".equals(cat) ? "pcs" : "g") : r.getUnit().trim();
                BomItem item = BomItem.builder()
                        .productTypeId(productTypeId)
                        .materialTypeId(mtId)
                        .materialName(matName)
                        .standardQuantity(r.getStandardQuantity())
                        .yieldRate(r.getYieldRate())
                        .unit(unit)
                        .materialCategory(cat)
                        .sortOrder(idx - 1)
                        .build();
                item.setFactoryId(factoryId);
                toInsert.add(item);
                results.add(new com.cretas.aims.dto.bom.BomBatchImportResult.RowResult(idx, true, null, mtId, matName));
            } catch (Exception e) {
                anyError = true;
                results.add(new com.cretas.aims.dto.bom.BomBatchImportResult.RowResult(
                        idx, false, e.getMessage(), null, r.getMaterialName()));
            }
        }
        if (anyError) {
            int failed = (int) results.stream().filter(x -> !x.isOk()).count();
            log.warn("[BOM-IMPORT] factory={} product={} 校验失败 {} 行, 整批不入库", factoryId, productTypeId, failed);
            return new com.cretas.aims.dto.bom.BomBatchImportResult(0, failed, results);
        }
        saveBomItems(toInsert);
        log.info("[BOM-IMPORT] factory={} product={} 导入 {} 行", factoryId, productTypeId, toInsert.size());
        return new com.cretas.aims.dto.bom.BomBatchImportResult(toInsert.size(), 0, results);
    }

    @Override
    @Transactional
    public void deleteBomItem(Long id) {
        log.info("删除BOM项目: id={}", id);
        BomItem bomItem = bomItemRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("BomItem", id.toString()));
        // P1-9 snapshot before delete
        BomItem oldSnapshot = bomItem;
        bomItem.softDelete();
        bomItemRepository.save(bomItem);
        log.info("BOM项目删除成功: id={}", id);
        // P1-9 写 DELETE log
        recordBomChange(null, oldSnapshot, BomChangeLog.ChangeType.DELETE);
    }

    // ============ Labor Cost ============

    @Override
    @Transactional(readOnly = true)
    public List<LaborCostConfig> getLaborCostsByProduct(String factoryId, String productTypeId) {
        log.debug("获取产品人工成本配置: factoryId={}, productTypeId={}", factoryId, productTypeId);
        return laborCostConfigRepository
            .findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(
                factoryId, productTypeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LaborCostConfig> getGlobalLaborCosts(String factoryId) {
        log.debug("获取工厂全局人工成本配置: factoryId={}", factoryId);
        return laborCostConfigRepository
            .findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc(factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LaborCostConfig> getAllLaborCosts(String factoryId) {
        log.debug("获取工厂所有人工成本配置: factoryId={}", factoryId);
        return laborCostConfigRepository.findByFactoryIdAndDeletedAtIsNullOrderBySortOrderAsc(factoryId);
    }

    @Override
    @Transactional
    public LaborCostConfig saveLaborCost(LaborCostConfig config) {
        log.info("保存人工成本配置: factoryId={}, processName={}",
            config.getFactoryId(), config.getProcessName());

        // 设置默认值
        if (config.getDefaultQuantity() == null) {
            config.setDefaultQuantity(BigDecimal.ONE);
        }
        if (config.getIsActive() == null) {
            config.setIsActive(true);
        }
        if (config.getSortOrder() == null) {
            config.setSortOrder(0);
        }

        LaborCostConfig saved = laborCostConfigRepository.save(config);
        log.info("人工成本配置保存成功: id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public void deleteLaborCost(Long id) {
        log.info("删除人工成本配置: id={}", id);
        LaborCostConfig config = laborCostConfigRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LaborCostConfig", id.toString()));
        config.softDelete();
        laborCostConfigRepository.save(config);
        log.info("人工成本配置删除成功: id={}", id);
    }

    // ============ Overhead Cost ============

    @Override
    @Transactional(readOnly = true)
    public List<OverheadCostConfig> getOverheadCosts(String factoryId) {
        log.debug("获取工厂均摊费用配置: factoryId={}", factoryId);
        return overheadCostConfigRepository.findByFactoryIdAndDeletedAtIsNullOrderBySortOrderAsc(factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OverheadCostConfig> getActiveOverheadCosts(String factoryId) {
        log.debug("获取工厂启用的均摊费用配置: factoryId={}", factoryId);
        return overheadCostConfigRepository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(factoryId);
    }

    @Override
    @Transactional
    public OverheadCostConfig saveOverheadCost(OverheadCostConfig config) {
        log.info("保存均摊费用配置: factoryId={}, name={}", config.getFactoryId(), config.getName());

        // 设置默认值
        if (config.getAllocationMethod() == null) {
            config.setAllocationMethod("PER_UNIT");
        }
        if (config.getAllocationRate() == null) {
            config.setAllocationRate(BigDecimal.ONE);
        }
        if (config.getIsActive() == null) {
            config.setIsActive(true);
        }
        if (config.getSortOrder() == null) {
            config.setSortOrder(0);
        }

        OverheadCostConfig saved = overheadCostConfigRepository.save(config);
        log.info("均摊费用配置保存成功: id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public OverheadCostConfig updateOverheadCost(String factoryId, Long id, OverheadCostConfig body) {
        log.info("更新均摊费用配置: factoryId={}, id={}", factoryId, id);
        OverheadCostConfig existing = overheadCostConfigRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("OverheadCostConfig", id.toString()));
        if (!factoryId.equals(existing.getFactoryId())) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该均摊费用配置")
                    .withHint("请确认费用配置归属或切换工厂账号");
        }
        // T-R5-3: select-then-merge — non-null body fields override; null fields preserve existing.
        if (body.getName() != null) existing.setName(body.getName());
        if (body.getCategory() != null) existing.setCategory(body.getCategory());
        if (body.getUnitPrice() != null) existing.setUnitPrice(body.getUnitPrice());
        if (body.getPriceUnit() != null) existing.setPriceUnit(body.getPriceUnit());
        if (body.getAllocationMethod() != null) existing.setAllocationMethod(body.getAllocationMethod());
        if (body.getAllocationRate() != null) existing.setAllocationRate(body.getAllocationRate());
        if (body.getIsActive() != null) existing.setIsActive(body.getIsActive());
        if (body.getSortOrder() != null) existing.setSortOrder(body.getSortOrder());
        if (body.getRemark() != null) existing.setRemark(body.getRemark());
        return overheadCostConfigRepository.save(existing);
    }

    @Override
    @Transactional
    public void deleteOverheadCost(Long id) {
        log.info("删除均摊费用配置: id={}", id);
        OverheadCostConfig config = overheadCostConfigRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("OverheadCostConfig", id.toString()));
        config.softDelete();
        overheadCostConfigRepository.save(config);
        log.info("均摊费用配置删除成功: id={}", id);
    }

    // ============ Cost Calculation ============

    @Override
    @Transactional(readOnly = true)
    public BomCostSummaryDTO calculateProductCost(String factoryId, String productTypeId) {
        log.info("计算产品成本: factoryId={}, productTypeId={}", factoryId, productTypeId);

        // 1. 获取BOM项目
        List<BomItem> bomItems = getBomItemsByProduct(factoryId, productTypeId);

        // 2. 获取人工成本（优先产品级别，其次全局）
        List<LaborCostConfig> laborCosts = getLaborCostsByProduct(factoryId, productTypeId);
        if (laborCosts.isEmpty()) {
            laborCosts = getGlobalLaborCosts(factoryId);
        }

        // 3. 获取均摊费用
        List<OverheadCostConfig> overheadCosts = getActiveOverheadCosts(factoryId);

        // 4. 计算原辅料成本
        List<BomCostSummaryDTO.MaterialCostItem> materialCostItems = new ArrayList<>();
        BigDecimal materialCostTotal = BigDecimal.ZERO;
        boolean hasMissingTaxRate = false;
        // B-BUG-1 (2026-06-21 transcript-e2e R1): 显式追踪缺单价行 (禁止降级)。
        // 缺单价行无法计入成本 (单价未知), 之前静默当 ¥0 使 materialCostTotal "看起来完整",
        // 现在标记每行 + 汇总, 让前端展示"成本不完整, 缺 N 行价格"。
        List<String> missingPriceMaterials = new ArrayList<>();

        for (BomItem item : bomItems) {
            BigDecimal actualQuantity = calculateActualQuantity(factoryId, item.getStandardQuantity(), item.getYieldRate());
            // R13: 价-量口径对齐. unitPrice 按物料主数据单位计价 (auto-fill 自
            //   RawMaterialType.unitPrice), 但 BOM 行用量可能按"斤"等其他重量单位录入.
            //   计价用量须先折算到主数据单位 (e.g. 斤→kg), 否则 斤量×kg价 翻倍/折半失真.
            BigDecimal pricingQuantity = reconcileQuantityForPricing(actualQuantity, item);
            BigDecimal subtotal = calculateMaterialCost(factoryId, pricingQuantity, item.getUnitPrice());
            if (item.getTaxRate() == null) {
                hasMissingTaxRate = true;
            }
            boolean missingPrice = item.getUnitPrice() == null;
            if (missingPrice) {
                missingPriceMaterials.add(
                    item.getMaterialName() != null ? item.getMaterialName()
                        : (item.getMaterialTypeId() != null ? item.getMaterialTypeId() : "(未命名物料)"));
            }

            BomCostSummaryDTO.MaterialCostItem costItem = BomCostSummaryDTO.MaterialCostItem.builder()
                .materialName(item.getMaterialName())
                .materialTypeId(item.getMaterialTypeId())
                .standardQuantity(item.getStandardQuantity())
                .yieldRate(item.getYieldRate())
                .actualQuantity(actualQuantity)
                .unit(item.getUnit())
                .unitPrice(item.getUnitPrice())
                .unitPriceCaliber(COST_CALIBER_PRE_TAX)
                .caliberHint(materialCaliberHint(item.getTaxRate()))
                .taxRate(item.getTaxRate())
                .subtotal(subtotal)
                .missingPrice(missingPrice)
                .build();

            materialCostItems.add(costItem);
            materialCostTotal = materialCostTotal.add(subtotal);
        }
        boolean hasMissingPrice = !missingPriceMaterials.isEmpty();

        // 5. 计算人工成本
        List<BomCostSummaryDTO.LaborCostItem> laborCostItems = new ArrayList<>();
        BigDecimal laborCostTotal = BigDecimal.ZERO;

        for (LaborCostConfig config : laborCosts) {
            BigDecimal subtotal = calculateLaborCost(factoryId, config.getUnitPrice(), config.getDefaultQuantity());

            BomCostSummaryDTO.LaborCostItem costItem = BomCostSummaryDTO.LaborCostItem.builder()
                .processName(config.getProcessName())
                .processCategory(config.getProcessCategory())
                .unitPrice(config.getUnitPrice())
                .priceUnit(config.getPriceUnit())
                .quantity(config.getDefaultQuantity())
                .subtotal(subtotal)
                .build();

            laborCostItems.add(costItem);
            laborCostTotal = laborCostTotal.add(subtotal);
        }

        // 6. 计算均摊费用
        List<BomCostSummaryDTO.OverheadCostItem> overheadCostItems = new ArrayList<>();
        BigDecimal overheadCostTotal = BigDecimal.ZERO;

        for (OverheadCostConfig config : overheadCosts) {
            BigDecimal subtotal = calculateOverheadCost(factoryId, config.getUnitPrice(), config.getAllocationRate());

            BomCostSummaryDTO.OverheadCostItem costItem = BomCostSummaryDTO.OverheadCostItem.builder()
                .name(config.getName())
                .category(config.getCategory())
                .unitPrice(config.getUnitPrice())
                .priceUnit(config.getPriceUnit())
                .allocationRate(config.getAllocationRate())
                .subtotal(subtotal)
                .build();

            overheadCostItems.add(costItem);
            overheadCostTotal = overheadCostTotal.add(subtotal);
        }

        // 7. 计算总成本
        BigDecimal totalCost = materialCostTotal
            .add(laborCostTotal)
            .add(overheadCostTotal)
            .setScale(4, RoundingMode.HALF_UP);

        // 8. 获取产品名称
        String productName = bomItems.isEmpty() ? null : bomItems.get(0).getProductName();

        // 9. 构建返回结果
        BomCostSummaryDTO summary = BomCostSummaryDTO.builder()
            .productTypeId(productTypeId)
            .productName(productName)
            .materialCosts(materialCostItems)
            .materialCostTotal(materialCostTotal.setScale(4, RoundingMode.HALF_UP))
            .laborCosts(laborCostItems)
            .laborCostTotal(laborCostTotal.setScale(4, RoundingMode.HALF_UP))
            .overheadCosts(overheadCostItems)
            .overheadCostTotal(overheadCostTotal.setScale(4, RoundingMode.HALF_UP))
            .totalCost(totalCost)
            .costCaliber(COST_CALIBER_PRE_TAX)
            .caliberHint(summaryCaliberHint(hasMissingTaxRate, hasMissingPrice, missingPriceMaterials.size()))
            // B-BUG-1: 缺价完整性标记
            .hasMissingPrice(hasMissingPrice)
            .missingPriceCount(missingPriceMaterials.size())
            .missingPriceMaterials(hasMissingPrice ? missingPriceMaterials : null)
            .calculatedAt(LocalDateTime.now().format(DATE_FORMATTER))
            .build();

        log.info("产品成本计算完成: productTypeId={}, totalCost={}", productTypeId, totalCost);
        return summary;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomCostSummaryDTO> calculateProductCosts(String factoryId, List<String> productTypeIds) {
        log.info("批量计算产品成本: factoryId={}, count={}", factoryId, productTypeIds.size());
        return productTypeIds.stream()
            .map(productTypeId -> calculateProductCost(factoryId, productTypeId))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getProductTypesWithBom(String factoryId) {
        log.debug("获取有BOM配置的产品类型: factoryId={}", factoryId);
        return bomItemRepository.findDistinctProductTypeIds(factoryId);
    }

    // ============ Private Helper Methods ============

    private String summaryCaliberHint(boolean hasMissingTaxRate, boolean hasMissingPrice, int missingPriceCount) {
        StringBuilder sb = new StringBuilder(PRE_TAX_CALIBER_HINT);
        if (hasMissingTaxRate) {
            sb.append(MISSING_TAX_RATE_HINT);
        }
        // B-BUG-1: 缺价时显式提示成本不完整 (禁止降级 — 不假装总成本完整)。
        if (hasMissingPrice) {
            sb.append("⚠️ 成本不完整: 有 ").append(missingPriceCount)
              .append(" 行原辅料缺单价, 其成本未计入合计, 总成本被低估, 请补全单价后重新计算。");
        }
        return sb.toString();
    }

    private String materialCaliberHint(BigDecimal taxRate) {
        if (taxRate == null) {
            return "BOM unitPrice 为未税价；缺税率，无法展示含税/未税换算来源。";
        }
        return "BOM unitPrice 为未税价；税率仅用于发票口径追踪，成本不二次除税。";
    }

    /**
     * Canvas Config: 从配置中获取字段默认值。
     * 如果 factoryConfigService 未注入（模块未部署），返回 fallback。
     */
    private Object getConfigDefault(String factoryId, String fieldCode, Object fallback) {
        // Canvas V2 Layer B: try DefaultValueResolver first (condition-based defaults)
        if (defaultValueResolver != null) {
            try {
                Object val = defaultValueResolver.resolve(factoryId, "bom", fieldCode, java.util.Map.of());
                if (val != null) return val;
            } catch (Exception e) {
                log.debug("DefaultValueResolver failed for bom.{}: {}", fieldCode, e.getMessage());
            }
        }
        // Phase 1: try FactoryConfigService (schema-level defaults)
        if (factoryConfigService != null) {
            try {
                Object val = factoryConfigService.getFieldDefault(factoryId, "bom", fieldCode);
                if (val != null) return val;
            } catch (Exception e) {
                log.debug("获取BOM字段 {} 配置默认值失败，使用 fallback: {}", fieldCode, fallback);
            }
        }
        return fallback;
    }

    /**
     * 计算实际用量（考虑出成率）
     * 公式: 实际用量 = 标准用量 / (出成率 / 100)
     */
    private BigDecimal calculateActualQuantity(String factoryId, BigDecimal standardQuantity, BigDecimal yieldRate) {
        if (standardQuantity == null) return BigDecimal.ZERO;
        if (yieldRate == null || yieldRate.compareTo(BigDecimal.ZERO) == 0) return standardQuantity;

        // Canvas V2: try DB formula first
        if (formulaEngine != null && factoryId != null) {
            BigDecimal result = formulaEngine.evaluate(factoryId, "bom", "ACTUAL_QUANTITY",
                    java.util.Map.of("standardQuantity", standardQuantity, "yieldRate", yieldRate));
            if (result != null) return result;
        }

        // Hardcoded fallback
        return standardQuantity.divide(
            yieldRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP),
            6, RoundingMode.HALF_UP
        );
    }

    /**
     * R13 (2026-06-22): 计价口径对齐 — 把 BOM 行用量折算到物料主数据计价单位.
     *
     * <p><b>问题:</b> BOM 行 {@code unitPrice} 默认 auto-fill 自 {@link com.cretas.aims.entity.RawMaterialType#getUnitPrice()}
     * (见 {@code BomBatchOperationServiceImpl#290}, {@code BomReverseQueryServiceImpl#134}),
     * 该价按物料主数据单位 ({@code RawMaterialType.unit}, 通常 kg) 计价. 但 BOM 行用量
     * ({@code standardQuantity}) 可能按"斤"等其他<b>重量</b>单位录入 (R10 加入斤白名单).
     * 直接 {@code 斤量 × kg价} = 失真 2× (1斤=0.5kg).
     *
     * <p><b>归一规则 (仅重量维度, 最小爆炸半径):</b>
     * <ul>
     *   <li>行单位与主数据单位<b>都是重量单位且不同</b> (e.g. 斤 vs kg, g vs kg) → 把用量
     *       从行单位折算到主数据单位 (经 {@link com.cretas.aims.service.UnitConversionService}),
     *       使 {@code 折算量 × 主数据价} 口径一致.</li>
     *   <li>行单位 == 主数据单位 (e.g. 都 kg, 或 BOM 自定价 per 斤) → 透传, 不变 (旧行为).</li>
     *   <li>非重量单位 (个/箱/袋) / 跨维度 / 缺主数据 / 缺换算依赖 → 透传原量 (绝不强行当 kg).</li>
     * </ul>
     *
     * <p><b>为何只在成本层归一, 不改存储:</b> 改存储语义 (把斤量存成kg) 会动 BOM 行展示
     * 与所有读路径, 爆炸半径大且不可逆. 成本计算点归一是单点、可逆、口径自洽的最小改动.
     *
     * @param actualQuantity 行实际用量 (行单位)
     * @param item           BOM 行 (取 unit + materialTypeId)
     * @return 折算到主数据计价单位后的用量; 无法/无需归一时返回原量
     */
    private BigDecimal reconcileQuantityForPricing(BigDecimal actualQuantity, BomItem item) {
        if (actualQuantity == null || item == null) return actualQuantity;
        // 依赖未注入 (旧单测 3-arg 构造) → 退化为不归一 (旧行为)
        if (unitConversionService == null || rawMaterialTypeRepository == null) return actualQuantity;

        String itemUnit = item.getUnit();
        String materialTypeId = item.getMaterialTypeId();
        if (itemUnit == null || itemUnit.isBlank() || materialTypeId == null || materialTypeId.isBlank()) {
            return actualQuantity;
        }
        // 仅重量单位参与归一; 个/箱/袋/ml/L 等不归一
        if (!unitConversionService.isWeightUnit(itemUnit)) {
            return actualQuantity;
        }

        com.cretas.aims.entity.RawMaterialType material;
        try {
            material = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
        } catch (Exception e) {
            log.debug("[R13-UOM] 加载物料失败 {}: {} — 用量不归一", materialTypeId, e.getMessage());
            return actualQuantity;
        }
        if (material == null) return actualQuantity;

        String masterUnit = material.getUnit();
        if (masterUnit == null || masterUnit.isBlank()) return actualQuantity;
        // 主数据单位非重量 (e.g. 主数据按"箱"计价) → 不在本最小修范围内, 透传 (见终审点)
        if (!unitConversionService.isWeightUnit(masterUnit)) return actualQuantity;

        BigDecimal converted = unitConversionService.convert(actualQuantity, itemUnit, masterUnit);
        if (converted == null) return actualQuantity;   // 不应发生 (两者均重量), 防御
        if (converted.compareTo(actualQuantity) != 0) {
            log.debug("[R13-UOM] BOM 计价口径归一: material={} {}{} → {}{} (主数据计价单位)",
                    materialTypeId, actualQuantity, itemUnit, converted, masterUnit);
        }
        return converted;
    }

    /**
     * 计算原料成本
     * 公式: 成本 = 实际用量 * 单价
     */
    private BigDecimal calculateMaterialCost(String factoryId, BigDecimal actualQuantity, BigDecimal unitPrice) {
        if (actualQuantity == null || unitPrice == null) return BigDecimal.ZERO;

        if (formulaEngine != null && factoryId != null) {
            BigDecimal result = formulaEngine.evaluate(factoryId, "bom", "MATERIAL_COST",
                    java.util.Map.of("actualQuantity", actualQuantity, "unitPrice", unitPrice));
            if (result != null) return result;
        }
        return actualQuantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算人工成本
     * 公式: 成本 = 单价 * 操作量
     */
    private BigDecimal calculateLaborCost(String factoryId, BigDecimal unitPrice, BigDecimal quantity) {
        if (unitPrice == null) return BigDecimal.ZERO;
        BigDecimal qty = quantity != null ? quantity : BigDecimal.ONE;

        if (formulaEngine != null && factoryId != null) {
            BigDecimal result = formulaEngine.evaluate(factoryId, "bom", "LABOR_COST",
                    java.util.Map.of("unitPrice", unitPrice, "quantity", qty));
            if (result != null) return result;
        }
        return unitPrice.multiply(qty).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算均摊费用
     * 公式: 成本 = 单价 * 分摊比例
     */
    private BigDecimal calculateOverheadCost(String factoryId, BigDecimal unitPrice, BigDecimal allocationRate) {
        if (unitPrice == null) return BigDecimal.ZERO;
        BigDecimal rate = allocationRate != null ? allocationRate : BigDecimal.ONE;

        if (formulaEngine != null && factoryId != null) {
            BigDecimal result = formulaEngine.evaluate(factoryId, "bom", "OVERHEAD_COST",
                    java.util.Map.of("unitPrice", unitPrice, "allocationRate", rate));
            if (result != null) return result;
        }
        return unitPrice.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }
}
