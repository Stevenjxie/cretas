package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderShortageReport;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderShortageReportRepository;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.shortage.dto.ProcurementSuggestion;
import com.cretas.aims.service.shortage.dto.ProductionPlanSuggestion;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监听销售订单财务审核通过事件, 异步生成 + 持久化缺料报告快照, 推工厂管理员通知。
 *
 * <p><b>跟 {@code SupplyChainOrchestrator.onSalesOrderFinanceApproved} 的分工</b>:
 * <ul>
 *   <li>Orchestrator (本来就有): 触发副作用 — 创建 PP / PO / 库存预留。</li>
 *   <li>本 Listener (新增 N31): 仅生成 read-only 快照 + 通知 — 不重复副作用。</li>
 * </ul>
 *
 * <p>通知走 main 现有 {@link NotificationService} (当前 impl=LoggingNotificationServiceImpl,
 * Track B1 钉钉 PoC merge 后会通过 @Primary 切换到 DingTalkNotificationServiceImpl,
 * 本 listener 0 改动)。
 *
 * <p>{@code @Async} 让快照生成不阻塞财务审核响应。Spring @EventListener 默认同步 —
 * 必须显式 {@link Async} 才异步。Async 配置见 {@code com.cretas.aims.config.AsyncConfig}。
 */
@Component
@RequiredArgsConstructor
public class SalesOrderShortageReportListener {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderShortageReportListener.class);

    /** 通知目标角色 — 工厂管理员能看到缺料告警, 后续可加 PURCHASE_MANAGER 等. */
    private static final String NOTIFY_ROLE_FACTORY_ADMIN = "FACTORY_ADMIN";

    private final ShortageAnalysisService shortageAnalysisService;
    private final SalesOrderShortageReportRepository reportRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final NotificationService notificationService;
    private final SalesOrderRepository salesOrderRepository;

    @Async
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        String factoryId = event.getFactoryId();
        String salesOrderId = event.getSalesOrderId();
        log.info("[ShortageReport] async snapshot: factoryId={}, SO={}, approvedBy={}",
                factoryId, salesOrderId, event.getApprovedBy());

        SalesOrder sourceOrder = salesOrderRepository.findById(salesOrderId)
                .filter(order -> factoryId.equals(order.getFactoryId()))
                .orElse(null);
        if (sourceOrder == null) {
            log.warn("[ShortageReport] sales order missing or cross-factory, skip: factoryId={}, SO={}",
                    factoryId, salesOrderId);
            return;
        }
        if (sourceOrder.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            log.info("[ShortageReport] customer-supplied order skips company procurement suggestions: SO={}",
                    salesOrderId);
            return;
        }

        ShortageReport report;
        try {
            report = shortageAnalysisService.analyzeForSalesOrder(factoryId, salesOrderId);
            List<ProcurementSuggestion> procurement = shortageAnalysisService.suggestProcurement(factoryId, report);
            List<ProductionPlanSuggestion> production = shortageAnalysisService.suggestProduction(factoryId, report);

            persistReport(report, procurement, production, "COMPLETED");
            // Sprint3-G S-LOCK-1 — 写回 SalesOrderItem 行内 reservedQty / lockedQty,
            // 让销售单列表行内 chip 一眼可见. ShortageAnalysisService 仍 readOnly,
            // 写副作用只在本 listener (已 REQUIRES_NEW + 已为 ShortageReport 写库).
            writebackItemInventoryStatus(salesOrderId, report);
            notifyIfShortage(factoryId, salesOrderId, report, procurement, production);

            log.info("[ShortageReport] persisted COMPLETED snapshot: SO={}, fgShort={}, matShort={}",
                    salesOrderId,
                    report.getFinishedGoodsLineItems() != null ? report.getFinishedGoodsLineItems().size() : 0,
                    report.getMaterialShortages() != null ? report.getMaterialShortages().size() : 0);
        } catch (Exception e) {
            // 不影响 orchestrator 主链路 — 仅记录 FAILED 占位行 + 通知管理员排查
            log.error("[ShortageReport] analyze failed (orchestrator 仍正常工作): SO={}, factoryId={}",
                    salesOrderId, factoryId, e);
            persistFailedPlaceholder(factoryId, salesOrderId, e.getMessage());
            notifyFailure(factoryId, salesOrderId, e.getMessage());
        }
    }

    private void persistReport(ShortageReport report,
                               List<ProcurementSuggestion> procurement,
                               List<ProductionPlanSuggestion> production,
                               String status) {
        SalesOrderShortageReport entity = reportRepository
                .findByFactoryIdAndSalesOrderId(report.getFactoryId(), report.getSalesOrderId())
                .orElseGet(SalesOrderShortageReport::new);

        entity.setFactoryId(report.getFactoryId());
        entity.setSalesOrderId(report.getSalesOrderId());
        entity.setAnalysisStatus(status);
        entity.setTotalRequired(report.getTotalRequired());
        entity.setAvailable(report.getFinishedGoodsLineItems());
        entity.setShortage(report.getMaterialShortages());
        entity.setProcurementSuggestions(procurement);
        entity.setProductionSuggestions(production);
        entity.setAnalysisSummary(report.getSummary());
        reportRepository.save(entity);
    }

    /**
     * Sprint3-G S-LOCK-1 — 把 ShortageReport 派生的 reservedQty 写回 SalesOrderItem.
     *
     * <p>映射逻辑: ShortageReport.finishedGoodsLineItems 每条 {@link LineItemMatch} 含
     * productTypeId + requiredQuantity + shortfallQuantity. reservedQty 推算为
     * <code>requiredQuantity - max(0, shortfallQuantity)</code> — 即"已可用部分"。
     *
     * <p>lockedQty 暂置 0 — production_plan reservation 数据源接入留 Sprint3-G follow-up
     * (brief §risks point 1: lockedQty 由生产/调拨锁定, 当前模型无直接源).
     *
     * <p>跨多个 SalesOrderItem 共享同 productTypeId 的情况罕见 (UI 校验同订单不能重复
     * SKU, 见 list.vue:421); 若发生, 第一条命中即覆盖, 其余项保持初值 0.
     */
    private void writebackItemInventoryStatus(String salesOrderId, ShortageReport report) {
        if (report == null || report.getFinishedGoodsLineItems() == null
                || report.getFinishedGoodsLineItems().isEmpty()) {
            return;
        }
        Map<String, BigDecimal> reservedByProductType = new HashMap<>();
        for (LineItemMatch lim : report.getFinishedGoodsLineItems()) {
            if (lim.getProductTypeId() == null) continue;
            BigDecimal required = lim.getRequiredQuantity() != null ? lim.getRequiredQuantity() : BigDecimal.ZERO;
            BigDecimal shortfall = lim.getShortfallQuantity() != null ? lim.getShortfallQuantity() : BigDecimal.ZERO;
            BigDecimal shortPositive = shortfall.signum() < 0 ? BigDecimal.ZERO : shortfall;
            BigDecimal reserved = required.subtract(shortPositive);
            if (reserved.signum() < 0) reserved = BigDecimal.ZERO;
            reservedByProductType.put(lim.getProductTypeId(), reserved);
        }
        if (reservedByProductType.isEmpty()) return;

        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(salesOrderId);
        for (SalesOrderItem item : items) {
            BigDecimal reserved = reservedByProductType.get(item.getProductTypeId());
            if (reserved != null) {
                item.setReservedQty(reserved);
            }
            // lockedQty MVP=0 — keep existing value (default 0 from Flyway).
        }
        salesOrderItemRepository.saveAll(items);
    }

    private void persistFailedPlaceholder(String factoryId, String salesOrderId, String reason) {
        try {
            SalesOrderShortageReport entity = reportRepository
                    .findByFactoryIdAndSalesOrderId(factoryId, salesOrderId)
                    .orElseGet(SalesOrderShortageReport::new);
            entity.setFactoryId(factoryId);
            entity.setSalesOrderId(salesOrderId);
            entity.setAnalysisStatus("FAILED");
            entity.setAnalysisSummary("分析失败: " + (reason != null ? reason : "(unknown)"));
            entity.setTotalRequired(Collections.emptyList());
            entity.setAvailable(Collections.emptyList());
            entity.setShortage(Collections.emptyList());
            entity.setProcurementSuggestions(Collections.emptyList());
            entity.setProductionSuggestions(Collections.emptyList());
            reportRepository.save(entity);
        } catch (Exception inner) {
            log.error("[ShortageReport] FAILED placeholder also failed: SO={}", salesOrderId, inner);
        }
    }

    /**
     * 仅当存在 FG 或原料缺口时才推通知 — 充足直接发货的情况不打扰管理员。
     */
    private void notifyIfShortage(String factoryId,
                                  String salesOrderId,
                                  ShortageReport report,
                                  List<ProcurementSuggestion> procurement,
                                  List<ProductionPlanSuggestion> production) {
        if (report == null || report.isFullySatisfied()) {
            return;
        }
        int matShort = report.getMaterialShortages() != null ? report.getMaterialShortages().size() : 0;
        int fgShort = report.getFinishedGoodsLineItems() == null ? 0 :
                (int) report.getFinishedGoodsLineItems().stream().filter(l -> !l.isFullySatisfied()).count();
        if (matShort == 0 && fgShort == 0) {
            return;
        }

        String title = "缺料告警";
        String body = String.format(
                "销售单 %s 财务审核通过, 检测到 %d 个 SKU 成品缺料 / %d 种原料短缺。建议: %d 张采购单 + %d 个生产任务。",
                salesOrderId, fgShort, matShort, procurement.size(), production.size());
        try {
            notificationService.notifyRole(factoryId, NOTIFY_ROLE_FACTORY_ADMIN, title, body);
        } catch (Exception e) {
            log.warn("[ShortageReport] notifyRole 失败 (不影响主流程): SO={}, role={}",
                    salesOrderId, NOTIFY_ROLE_FACTORY_ADMIN, e);
        }
    }

    private void notifyFailure(String factoryId, String salesOrderId, String reason) {
        try {
            notificationService.notifyRole(factoryId, NOTIFY_ROLE_FACTORY_ADMIN,
                    "缺料分析失败",
                    String.format("销售单 %s 缺料分析异常, 请排查: %s",
                            salesOrderId, reason != null ? reason : "(unknown)"));
        } catch (Exception e) {
            log.warn("[ShortageReport] notifyFailure 失败: SO={}", salesOrderId, e);
        }
    }
}
