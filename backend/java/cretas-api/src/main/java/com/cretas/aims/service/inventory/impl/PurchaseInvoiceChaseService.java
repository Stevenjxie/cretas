package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseLevel;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoiceChaseLog;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceChaseService {

    private static final int ESCALATION_AFTER_DAYS = 7;
    private static final int CHASE_WINDOW_DAYS = 7;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceChaseLogRepository chaseLogRepository;
    private final FactorySettingsRepository factorySettingsRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(cron = "0 30 8 * * ?")
    @SchedulerLock(name = "PurchaseInvoiceChaseService.scanAll",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void scanAll() {
        LocalDate today = LocalDate.now();
        List<String> factoryIds = purchaseOrderRepository.findDistinctFactoryIds();
        int pushed = 0;
        for (String factoryId : factoryIds) {
            pushed += scanFactory(factoryId, today);
        }
        log.info("[PurchaseInvoiceChase] pushed {} chase notifications across {} factories",
                pushed, factoryIds.size());
    }

    @Transactional
    public int scanFactory(String factoryId, LocalDate today) {
        List<PurchaseOrder> candidates = purchaseOrderRepository.findInvoiceChaseCandidates(factoryId);
        Optional<FactorySettings> settings = Optional.empty();
        boolean settingsLoaded = false;
        int pushed = 0;

        for (PurchaseOrder po : candidates) {
            Integer reminderDays = resolvePurchaseOrderReminderDays(po);
            if (reminderDays == null) {
                if (!settingsLoaded) {
                    settings = factorySettingsRepository.findByFactoryId(factoryId);
                    settingsLoaded = true;
                }
                reminderDays = resolveFactoryReminderDays(settings);
            }

            if (reminderDays == null || reminderDays <= 0) {
                log.info("[PurchaseInvoiceChase] skip poId={} factoryId={} because reminderDays is not configured",
                        po.getId(), factoryId);
                continue;
            }

            LocalDate dueDate = po.getOrderDate().plusDays(reminderDays);
            if (!today.isAfter(dueDate)) {
                continue;
            }

            long daysOverdue = ChronoUnit.DAYS.between(dueDate, today);
            PurchaseInvoiceChaseLevel level = daysOverdue > ESCALATION_AFTER_DAYS
                    ? PurchaseInvoiceChaseLevel.ESCALATED
                    : PurchaseInvoiceChaseLevel.NORMAL;
            LocalDate windowStart = dueDate.plusDays((daysOverdue / CHASE_WINDOW_DAYS) * CHASE_WINDOW_DAYS);

            if (chaseLogRepository.existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
                    factoryId, po.getId(), level, windowStart)) {
                continue;
            }

            pushNotifications(factoryId, po, dueDate, daysOverdue, level);
            chaseLogRepository.save(buildLog(factoryId, po, dueDate, daysOverdue, level, windowStart));
            pushed++;
        }

        return pushed;
    }

    private Integer resolvePurchaseOrderReminderDays(PurchaseOrder po) {
        Integer reminderDays = po.getInvoiceReminderDays();
        return reminderDays != null && reminderDays > 0 ? reminderDays : null;
    }

    private Integer resolveFactoryReminderDays(Optional<FactorySettings> settings) {
        if (settings.isEmpty() || settings.get().getNotificationSettings() == null
                || settings.get().getNotificationSettings().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(settings.get().getNotificationSettings());
            JsonNode reminderDays = root.get("reminderDays");
            if (reminderDays == null || !reminderDays.canConvertToInt()) {
                return null;
            }
            int value = reminderDays.asInt();
            return value > 0 ? value : null;
        } catch (Exception ex) {
            log.warn("[PurchaseInvoiceChase] invalid factory notificationSettings JSON for factoryId={}",
                    settings.get().getFactoryId(), ex);
            return null;
        }
    }

    private void pushNotifications(String factoryId, PurchaseOrder po, LocalDate dueDate,
                                   long daysOverdue, PurchaseInvoiceChaseLevel level) {
        String title = "[采购催票] 采购订单 " + po.getOrderNumber() + " 未到票";
        String body = String.format("采购订单 %s 已超过到票期限 %s 天，最晚到票日 %s，请跟进供应商发票并完成核销。",
                po.getOrderNumber(), daysOverdue, dueDate);

        notificationService.notifyRole(
                factoryId, FactoryUserRole.procurement_manager.name(), title, body);
        notificationService.notifyRole(
                factoryId, FactoryUserRole.finance_manager.name(), title, body);

        if (level == PurchaseInvoiceChaseLevel.ESCALATED) {
            notificationService.notifyRole(
                    factoryId,
                    FactoryUserRole.factory_super_admin.name(),
                    "[采购催票升级] 采购订单 " + po.getOrderNumber() + " 长期未到票",
                    body);
        }
    }

    private PurchaseInvoiceChaseLog buildLog(String factoryId, PurchaseOrder po, LocalDate dueDate,
                                             long daysOverdue, PurchaseInvoiceChaseLevel level,
                                             LocalDate windowStart) {
        PurchaseInvoiceChaseLog logEntry = new PurchaseInvoiceChaseLog();
        logEntry.setFactoryId(factoryId);
        logEntry.setPurchaseOrderId(po.getId());
        logEntry.setOrderNumber(po.getOrderNumber());
        logEntry.setChaseLevel(level);
        logEntry.setStatus(PurchaseInvoiceChaseStatus.SENT);
        logEntry.setDueDate(dueDate);
        logEntry.setDaysOverdue(Math.toIntExact(daysOverdue));
        logEntry.setChaseWindowStart(windowStart);
        return logEntry;
    }
}
