package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 批量通知客户 Tool (WRITE + Preview) — Sprint 8 P3 Phase B (食品召回闭环 step 5b).
 *
 * <p>给指定召回事件涉及的全部客户群发召回通知. Preview 显短信草稿 + 影响 customers count,
 * 确认后实际通过 NotificationService 推送 (当前 impl 为 logging, 未来可接钉钉/短信网关).
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: preview 显客户总数 + 短信草稿 (前置边界)</li>
 *   <li>R2: message 含 recallEventId + batchNumber + customerCount (上下文)</li>
 *   <li>R3: message 可选 — 缺省自动生成 (基于 RecallEvent 的 triggerReason)</li>
 *   <li>R4: 同 recallEventId + batchNumber 已通知 → 返 status=ALREADY_NOTIFIED</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"通知所有买过 B-X 的客户"</li>
 *   <li>"召回通知客户"</li>
 *   <li>"群发召回短信"</li>
 * </ul>
 *
 * <p>Intent Code: {@code CUSTOMER_NOTIFY_BATCH}
 */
@Slf4j
@Component
public class CustomerNotifyBatchTool extends AbstractBusinessTool {

    /** 短信草稿模板 — 实际推送时由 NotificationService 渲染. */
    private static final String SMS_TEMPLATE =
            "【%s食品安全召回】尊敬的%s, 我司召回 %s 批次产品 (原因: %s). " +
            "请勿食用并联系销售员处理. 给您带来不便, 深表歉意.";

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RecallEventRepository recallEventRepository;

    @Autowired
    private RecallActionRepository recallActionRepository;

    @Autowired
    private NotificationService notificationService;

    @Override
    public String getToolName() {
        return "customer_notify_batch";
    }

    @Override
    public String getDescription() {
        return "批量通知客户召回事件 — 给指定召回事件涉及的全部客户群发召回通知 (SMS/微信). "
                + "WRITE + Preview. LLM 触发: '通知所有买过 B-X 的客户' / '召回通知客户' / "
                + "'群发召回短信'. 召回 SOP 客户通知 step.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填). 用 ShipmentRecord 反查涉及客户");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> recallEventId = new HashMap<>();
        recallEventId.put("type", "integer");
        recallEventId.put("description", "RecallEvent ID (必填). 用于 audit 关联召回事件");
        properties.put("recallEventId", recallEventId);

        Map<String, Object> message = new HashMap<>();
        message.put("type", "string");
        message.put("description",
                "通知消息 (可选). 缺省时基于 RecallEvent 的 triggerReason 自动生成");
        properties.put("message", message);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber", "recallEventId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber", "recallEventId");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        Long recallEventId = getLong(params, "recallEventId");
        String userMessage = getString(params, "message", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("batchNumber", batchNumber);
        result.put("recallEventId", recallEventId);

        // 查 RecallEvent 验证存在 + 拿 triggerReason
        Optional<RecallEvent> eventOpt = recallEventRepository.findById(recallEventId);
        if (eventOpt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ RecallEvent #%d 不存在, 无法通知客户", recallEventId));
            return result;
        }
        RecallEvent event = eventOpt.get();
        if (!factoryId.equals(event.getFactoryId())) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ RecallEvent #%d 属于其他工厂, 无权访问", recallEventId));
            return result;
        }

        // R4 dedup: 查 RecallAction 看是否已有 NOTIFY_CUSTOMER COMPLETED
        List<RecallAction> existing = recallActionRepository
                .findByRecallEventIdOrderByCreatedAtAsc(recallEventId);
        boolean alreadyNotified = existing.stream()
                .anyMatch(a -> "NOTIFY_CUSTOMER".equals(a.getActionType())
                        && "COMPLETED".equals(a.getStatus()));
        if (alreadyNotified) {
            result.put("canDo", false);
            result.put("status", "ALREADY_NOTIFIED");
            result.put("message", String.format(
                    "ℹ️ RecallEvent #%d 已群发过客户通知, 无需重复发送", recallEventId));
            return result;
        }

        // 反查影响客户 (per batchNumber)
        Set<String> affectedCustomerIds = findAffectedCustomerIds(factoryId, batchNumber);
        result.put("customerCount", affectedCustomerIds.size());

        if (affectedCustomerIds.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "ℹ️ 批次 %s 无出货客户 (无人需要通知)", batchNumber));
            return result;
        }

        // 收集 customer name 样例 (最多 5 个用于 preview 展示)
        List<String> sampleNames = new ArrayList<>();
        int sampleLimit = Math.min(5, affectedCustomerIds.size());
        int collected = 0;
        for (String cid : affectedCustomerIds) {
            if (collected >= sampleLimit) break;
            Optional<Customer> cOpt = customerRepository.findByIdAndFactoryId(cid, factoryId);
            cOpt.ifPresent(c -> sampleNames.add(c.getName()));
            collected++;
        }
        result.put("sampleCustomerNames", sampleNames);

        // 短信草稿
        String factoryDisplay = factoryId; // 简化: 未来可查 Factory 拿 displayName
        String smsDraft = userMessage != null && !userMessage.isBlank()
                ? userMessage
                : String.format(SMS_TEMPLATE, factoryDisplay, "[客户名]", batchNumber,
                        event.getTriggerReason());
        result.put("smsDraft", smsDraft);

        result.put("canDo", true);
        result.put("message", String.format(
                "📱 准备群发召回通知 → 批次 %s 涉及 %d 家客户. 样例: %s. " +
                "确认后 NotificationService 推送短信/钉钉",
                batchNumber, affectedCustomerIds.size(),
                String.join(", ", sampleNames)));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        Long recallEventId = getLong(params, "recallEventId");
        String userMessage = getString(params, "message", null);
        Long userId = getUserId(context);

        log.info("customer_notify_batch — factory={} batch={} event={} userId={}",
                factoryId, batchNumber, recallEventId, userId);

        Optional<RecallEvent> eventOpt = recallEventRepository.findById(recallEventId);
        if (eventOpt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("RecallEvent #%d 不存在", recallEventId));
        }
        RecallEvent event = eventOpt.get();
        if (!factoryId.equals(event.getFactoryId())) {
            throw new BusinessException(403,
                    String.format("RecallEvent #%d 属于其他工厂, 无权访问", recallEventId));
        }

        // R4 dedup
        List<RecallAction> existing = recallActionRepository
                .findByRecallEventIdOrderByCreatedAtAsc(recallEventId);
        boolean alreadyNotified = existing.stream()
                .anyMatch(a -> "NOTIFY_CUSTOMER".equals(a.getActionType())
                        && "COMPLETED".equals(a.getStatus()));
        if (alreadyNotified) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ALREADY_NOTIFIED");
            result.put("recallEventId", recallEventId);
            result.put("message", String.format("ℹ️ RecallEvent #%d 已群发过客户通知", recallEventId));
            return result;
        }

        Set<String> affectedCustomerIds = findAffectedCustomerIds(factoryId, batchNumber);
        if (affectedCustomerIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NO_CUSTOMERS");
            result.put("batchNumber", batchNumber);
            result.put("message", String.format("ℹ️ 批次 %s 无出货客户, 无人需要通知", batchNumber));
            return result;
        }

        // 实际推送 + RecallAction audit
        int sentCount = 0;
        int failedCount = 0;
        for (String cid : affectedCustomerIds) {
            Optional<Customer> cOpt = customerRepository.findByIdAndFactoryId(cid, factoryId);
            String customerName = cOpt.map(Customer::getName).orElse("尊敬的客户");
            String body = userMessage != null && !userMessage.isBlank()
                    ? userMessage
                    : String.format(SMS_TEMPLATE, factoryId, customerName, batchNumber,
                            event.getTriggerReason());
            try {
                // 简化: 用 broadcastFactory 而非 notifyUser (Customer 不一定有内部 userId)
                // 实际场景: 接 SMS gateway 用 customer.phone
                notificationService.broadcastFactory(factoryId,
                        "食品召回通知 - " + batchNumber, body);
                sentCount++;
            } catch (Exception ex) {
                log.warn("notify failed customer={} batch={}: {}",
                        cid, batchNumber, ex.getMessage());
                failedCount++;
            }
        }

        // 记 RecallAction
        RecallAction action = RecallAction.builder()
                .recallEventId(recallEventId)
                .actionType("NOTIFY_CUSTOMER")
                .targetEntityType("CUSTOMER")
                .targetEntityId(0L)  // 多客户群发, 用 0 表示批量
                .status("COMPLETED")
                .executedAt(LocalDateTime.now())
                .executionNotes(String.format(
                        "群发召回通知: 批次=%s, 成功=%d, 失败=%d, by user=%d",
                        batchNumber, sentCount, failedCount, userId))
                .build();
        recallActionRepository.save(action);

        // 更新 RecallEvent status → NOTIFYING (如果还在 INVESTIGATING)
        if ("INVESTIGATING".equals(event.getStatus())) {
            event.setStatus("NOTIFYING");
            recallEventRepository.save(event);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recallEventId", recallEventId);
        data.put("batchNumber", batchNumber);
        data.put("customerCount", affectedCustomerIds.size());
        data.put("sentCount", sentCount);
        data.put("failedCount", failedCount);
        data.put("recallActionId", action.getId());
        data.put("actionHint", "/quality/recall/" + recallEventId);

        String message = String.format(
                "✅ 已群发召回通知 → 批次 %s, %d 客户, 成功 %d / 失败 %d. RecallEvent #%d 状态 → NOTIFYING",
                batchNumber, affectedCustomerIds.size(), sentCount, failedCount, recallEventId);
        return buildSimpleResult(message, data);
    }

    private Set<String> findAffectedCustomerIds(String factoryId, String batchNumber) {
        List<ShipmentRecord> shipments = shipmentRecordRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        Set<String> ids = new LinkedHashSet<>();
        for (ShipmentRecord s : shipments) {
            if (s.getCustomerId() != null) ids.add(s.getCustomerId());
        }
        return ids;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
