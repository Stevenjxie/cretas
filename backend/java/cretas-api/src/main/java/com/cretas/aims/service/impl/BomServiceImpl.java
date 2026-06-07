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

        for (BomItem item : bomItems) {
            BigDecimal actualQuantity = calculateActualQuantity(factoryId, item.getStandardQuantity(), item.getYieldRate());
            BigDecimal subtotal = calculateMaterialCost(factoryId, actualQuantity, item.getUnitPrice());

            BomCostSummaryDTO.MaterialCostItem costItem = BomCostSummaryDTO.MaterialCostItem.builder()
                .materialName(item.getMaterialName())
                .materialTypeId(item.getMaterialTypeId())
                .standardQuantity(item.getStandardQuantity())
                .yieldRate(item.getYieldRate())
                .actualQuantity(actualQuantity)
                .unit(item.getUnit())
                .unitPrice(item.getUnitPrice())
                .taxRate(item.getTaxRate())
                .subtotal(subtotal)
                .build();

            materialCostItems.add(costItem);
            materialCostTotal = materialCostTotal.add(subtotal);
        }

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
