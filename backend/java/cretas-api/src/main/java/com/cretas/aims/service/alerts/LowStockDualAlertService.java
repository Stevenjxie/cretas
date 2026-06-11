package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 低库存双向报警服务 — F-034 (t2b 行 3023 [88:55]).
 *
 * <p>客户原话: "库存不够以后立马仓库和采购这边全部同步报你"。
 *
 * <p>职责:
 * <ul>
 *   <li>检测工厂原料库存 &lt; {@code RawMaterialType.minStock}（安全库存）</li>
 *   <li>同步推通知给 <b>仓储主管 (warehouse_manager)</b> 和
 *       <b>采购主管 (procurement_manager)</b></li>
 *   <li>通知内容含防呆上下文: 物料名 + 当前库存 + 安全库存 + 缺口量</li>
 * </ul>
 *
 * <p>触发时机:
 * <ol>
 *   <li><b>实时</b>: {@code AlertInventoryLowListener} 收到 {@code InventoryStockChangedEvent}
 *       后调用 {@link #checkAndNotify(String, String, BigDecimal, BigDecimal, String)}</li>
 *   <li><b>兜底定时</b>: {@code AlertInventoryLowScheduler} 每 15 min 调用
 *       {@link #sweepFactory(String)}</li>
 * </ol>
 *
 * <p>去重: 通知文字相同时 {@link NotificationService#sendToRole} 内部幂等
 * (source=LOW_STOCK_ALERT + materialTypeId as sourceId), 下游查询时可过滤重复。
 *
 * @since 2026-06-11 (F-034)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LowStockDualAlertService {

    private static final String SOURCE = "LOW_STOCK_ALERT";

    private final NotificationService notificationService;
    private final MaterialBatchRepository materialBatchRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    // ─── 实时路径 (由 AlertInventoryLowListener 调用) ───────────────────────

    /**
     * 实时检查单个原料: 库存变更后立即评估是否低于安全库存。
     *
     * <p>若 {@code minStockLevel} 为 null 或 &le;0 — 无安全库存配置 → 不报警。
     *
     * @param factoryId      工厂 ID
     * @param materialTypeId 原料类型 ID
     * @param currentStock   变更后当前库存量
     * @param minStockLevel  安全库存阈值 (来自 {@code RawMaterialType.minStock})
     * @param unit           计量单位
     */
    public void checkAndNotify(String factoryId, String materialTypeId,
                               BigDecimal currentStock, BigDecimal minStockLevel, String unit) {
        if (factoryId == null || materialTypeId == null) {
            log.warn("LowStockDualAlertService.checkAndNotify: missing factoryId or materialTypeId");
            return;
        }
        if (minStockLevel == null || minStockLevel.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("LowStockDualAlertService: no minStock configured for {} — skip", materialTypeId);
            return;
        }
        if (currentStock == null) {
            currentStock = BigDecimal.ZERO;
        }
        if (currentStock.compareTo(minStockLevel) >= 0) {
            // 库存充足，不报
            return;
        }

        // 回查物料名称
        String materialName = rawMaterialTypeRepository.findById(materialTypeId)
                .map(RawMaterialType::getName)
                .orElse(materialTypeId);

        sendDualNotification(factoryId, materialTypeId, materialName, currentStock, minStockLevel,
                unit != null ? unit : "kg");
    }

    // ─── 兜底定时路径 (由 AlertInventoryLowScheduler 调用) ─────────────────

    /**
     * 全量扫描工厂所有原料类型，对低于安全库存的发双向报警。
     *
     * <p>每 15 分钟由 {@code AlertInventoryLowScheduler} 调用，兜底事件漏发。
     *
     * @param factoryId 工厂 ID
     */
    public void sweepFactory(String factoryId) {
        if (factoryId == null) return;

        // 取有 minStock 配置的原料类型
        List<RawMaterialType> types = rawMaterialTypeRepository
                .findMaterialTypesWithStockWarning(factoryId);

        if (types.isEmpty()) {
            log.debug("sweepFactory {}: no material types with minStock configured", factoryId);
            return;
        }

        // 批量取当前库存汇总 (sumQuantityByMaterialType 已有)
        List<Object[]> stockRows = materialBatchRepository.sumQuantityByMaterialType(factoryId);
        Map<String, BigDecimal> stockMap = stockRows.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO));

        int alerted = 0;
        for (RawMaterialType mt : types) {
            BigDecimal minStock = mt.getMinStock();
            // findMaterialTypesWithStockWarning 已过滤 null/<=0，防御性再判
            if (minStock == null || minStock.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal currentStock = stockMap.getOrDefault(mt.getId(), BigDecimal.ZERO);
            if (currentStock.compareTo(minStock) < 0) {
                sendDualNotification(factoryId, mt.getId(), mt.getName(),
                        currentStock, minStock,
                        mt.getUnit() != null ? mt.getUnit() : "kg");
                alerted++;
            }
        }

        log.info("sweepFactory {}: checked {} materials, {} below minStock → dual alerts sent",
                factoryId, types.size(), alerted);
    }

    // ─── 核心推送 ───────────────────────────────────────────────────────────

    /**
     * 同步推通知给仓储主管 + 采购主管。
     *
     * <p>防呆内容格式: "【库存预警】{物料名} 当前 {current}{unit}，
     * 低于安全库存 {min}{unit}，缺口 {gap}{unit}，请尽快补货。"
     */
    void sendDualNotification(String factoryId, String materialTypeId,
                              String materialName, BigDecimal currentStock,
                              BigDecimal minStock, String unit) {
        BigDecimal gap = minStock.subtract(currentStock).max(BigDecimal.ZERO);
        String title = "【库存预警】" + materialName + " 库存不足";
        String content = String.format(
                "%s 当前库存 %s %s，低于安全库存 %s %s，缺口 %s %s，请尽快补货。",
                materialName,
                currentStock.setScale(2, RoundingMode.HALF_UP).toPlainString(), unit,
                minStock.setScale(2, RoundingMode.HALF_UP).toPlainString(), unit,
                gap.setScale(2, RoundingMode.HALF_UP).toPlainString(), unit);

        log.info("低库存双向报警: factoryId={} materialTypeId={} name={} current={} min={} gap={}",
                factoryId, materialTypeId, materialName, currentStock, minStock, gap);

        try {
            // 1. 仓储主管
            List<?> whNotifs = notificationService.sendToRole(
                    factoryId, FactoryUserRole.warehouse_manager,
                    title, content,
                    NotificationType.WARNING, SOURCE, materialTypeId);
            log.debug("warehouse_manager 通知已发: count={}", whNotifs != null ? whNotifs.size() : 0);
        } catch (Exception e) {
            log.error("低库存报警 warehouse_manager 发送失败: factoryId={} materialTypeId={}",
                    factoryId, materialTypeId, e);
        }

        try {
            // 2. 采购主管
            List<?> procNotifs = notificationService.sendToRole(
                    factoryId, FactoryUserRole.procurement_manager,
                    title, content,
                    NotificationType.WARNING, SOURCE, materialTypeId);
            log.debug("procurement_manager 通知已发: count={}", procNotifs != null ? procNotifs.size() : 0);
        } catch (Exception e) {
            log.error("低库存报警 procurement_manager 发送失败: factoryId={} materialTypeId={}",
                    factoryId, materialTypeId, e);
        }
    }

    /** 诊断方法: 返回工厂所有低库存物料的快照 (供 AI Tool / API 查询用)。 */
    public List<Map<String, Object>> getLowStockSnapshot(String factoryId) {
        if (factoryId == null) return List.of();

        List<RawMaterialType> types = rawMaterialTypeRepository
                .findMaterialTypesWithStockWarning(factoryId);
        List<Object[]> stockRows = materialBatchRepository.sumQuantityByMaterialType(factoryId);
        Map<String, BigDecimal> stockMap = stockRows.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO));

        return types.stream()
                .filter(mt -> mt.getMinStock() != null && mt.getMinStock().compareTo(BigDecimal.ZERO) > 0)
                .filter(mt -> stockMap.getOrDefault(mt.getId(), BigDecimal.ZERO)
                        .compareTo(mt.getMinStock()) < 0)
                .map(mt -> {
                    BigDecimal current = stockMap.getOrDefault(mt.getId(), BigDecimal.ZERO);
                    BigDecimal gap = mt.getMinStock().subtract(current).max(BigDecimal.ZERO);
                    Map<String, Object> m = new HashMap<>();
                    m.put("materialTypeId", mt.getId());
                    m.put("materialName", mt.getName());
                    m.put("materialCode", mt.getCode());
                    m.put("currentStock", current);
                    m.put("safetyStock", mt.getMinStock());
                    m.put("gap", gap);
                    m.put("unit", mt.getUnit() != null ? mt.getUnit() : "kg");
                    return m;
                })
                .collect(Collectors.toList());
    }
}
