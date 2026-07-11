package com.cretas.aims.service.alerts.restaurant;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.alerts.AlertSeverity;
import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.repository.alerts.AlertEventRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.service.alerts.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 餐饮经营体检 → 标准告警 (含反回扣) 桥接服务 — 2026-07-11.
 *
 * <p>数据源: Python {@code GET /api/smartbi/restaurant/{factoryId}/health-check-report}
 * — {@code DiagnosticsEngine} 已 grounded 的 critical/warning 诊断 (食材成本率 /
 * 渠道收款率-佣金估算[反回扣核心信号] / 折扣率 / 食材损耗率 / 客单价达标率 等).
 * 本服务<b>只转发</b> Python 已给出的诊断结果为标准 {@link AlertEvent}, 不做二次
 * 判断/编造 — 禁降级原则.
 *
 * <p>Standing-alert 语义 (per goal):
 * <ul>
 *   <li><b>Dedup</b>: businessEntityId = metricKey (不含 period) — 同一指标持续
 *       异常时保持<b>同一条</b> OPEN 事件 (message 内容随最新 sweep 刷新), 不会
 *       每次 sweep 都重开一条新事件.</li>
 *   <li><b>Auto-resolve</b>: 上次 sweep 敞开的事件, 若这次不再出现在诊断列表里
 *       (指标已恢复正常) → 自动 {@link AlertEngineService#resolve} (userId=null
 *       表示系统自动清除, 接口已支持此语义).</li>
 * </ul>
 *
 * <p>严重度映射: Python {@code severity}="critical" → {@link AlertSeverity#HIGH}
 * (触发 SMS, 见 {@code AlertEventNotificationListener}), "warning" →
 * {@link AlertSeverity#MID} (仅站内通知). "info" / 未知值不构成 standing alert
 * (跳过 — DiagnosticsEngine 本身对健康项也不返回, 这里是防御性双重保险).
 *
 * @since 2026-07-11 (餐饮经营体检预警推送)
 */
@Slf4j
@Service
public class RestaurantHealthAlertBridgeService {

    /** UNIQUE(factory_id, rule_name) — 每工厂自动 ensure 恰好一条. */
    public static final String RULE_NAME = "餐饮经营体检预警(反回扣/异常)";

    private static final String BUSINESS_ENTITY_TYPE = "HEALTH_METRIC";

    /** 老板 + 管理员 — per goal 的推送对象. */
    private static final List<String> DEFAULT_NOTIFY_ROLES =
            List.of("restaurant_owner", "factory_super_admin");

    /** 站内通知恒发; 短信仅 HIGH severity 才实际触发 (见 notify listener 渠道策略). */
    private static final List<String> DEFAULT_NOTIFY_CHANNELS = List.of("IN_APP", "SMS");

    @Autowired
    private PythonSmartBIClient pythonSmartBIClient;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    @Autowired
    private AlertEngineService alertEngineService;

    /**
     * 对单个门店跑一次体检 → 标准告警同步. 幂等 (可被 scheduler 定时调用, 也可被
     * on-demand endpoint 手动调用, 两者语义完全一致 — dedup/auto-resolve 保证
     * 重复调用不产生副作用堆积).
     *
     * @param factoryId 门店 factoryId (调用方需先确认 FactoryType.RESTAURANT)
     * @return 摘要 Map: {available, period, diagnosesChecked, created, refreshed,
     *         resolved, ruleEnabled} — available=false 表示 Python 服务不可用/
     *         请求失败, 本次诚实跳过 (不编造结果)
     */
    @Transactional
    public Map<String, Object> sweepFactory(String factoryId) {
        Map<String, Object> result = new HashMap<>();
        result.put("factoryId", factoryId);

        Map<String, Object> resp;
        try {
            resp = pythonSmartBIClient.getRestaurantHealthCheckReport(factoryId, null);
        } catch (Exception e) {
            log.error("[RestaurantHealthAlertBridgeService] Python 调用异常 factoryId={}", factoryId, e);
            resp = null;
        }
        if (resp == null) {
            log.warn("[RestaurantHealthAlertBridgeService] factoryId={} 体检报告不可用, 本次跳过 (禁降级: 不编造)",
                    factoryId);
            result.put("available", false);
            result.put("reason", "Python 体检报告服务不可用或请求失败");
            return result;
        }
        if (!Boolean.TRUE.equals(resp.get("success"))) {
            log.warn("[RestaurantHealthAlertBridgeService] factoryId={} 体检报告返回失败: {}",
                    factoryId, resp.get("message"));
            result.put("available", false);
            result.put("reason", String.valueOf(resp.get("message")));
            return result;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null) {
            result.put("available", false);
            result.put("reason", "体检报告 data 为空");
            return result;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> reportMeta = (Map<String, Object>) data.get("reportMeta");
        String period = reportMeta != null && reportMeta.get("period") != null
                ? String.valueOf(reportMeta.get("period")) : null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnoses = (List<Map<String, Object>>) data.getOrDefault("diagnoses", List.of());

        AlertRule rule = ensureDefaultRule(factoryId);
        result.put("available", true);
        result.put("period", period);
        result.put("diagnosesChecked", diagnoses.size());
        result.put("ruleEnabled", Boolean.TRUE.equals(rule.getEnabled()));

        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            log.debug("[RestaurantHealthAlertBridgeService] factoryId={} 规则已禁用, 跳过", factoryId);
            result.put("created", 0);
            result.put("refreshed", 0);
            result.put("resolved", 0);
            return result;
        }

        List<AlertEvent> openEvents = eventRepository.findByFactoryIdAndRuleIdAndStatusIn(
                factoryId, rule.getId(), List.of(AlertEventStatus.OPEN, AlertEventStatus.ACKNOWLEDGED));
        Map<String, AlertEvent> openByMetricKey = openEvents.stream()
                .filter(e -> e.getBusinessEntityId() != null)
                .collect(Collectors.toMap(AlertEvent::getBusinessEntityId, e -> e, (a, b) -> a));

        Set<String> currentMetricKeys = new HashSet<>();
        int created = 0;
        int refreshed = 0;

        for (Map<String, Object> diag : diagnoses) {
            AlertSeverity severity = mapSeverity(str(diag.get("severity"), null));
            if (severity == null) {
                continue; // "info" / 未知 — 非 standing alert 级别
            }
            String metricKey = str(diag.get("metricKey"), null);
            if (metricKey == null || metricKey.isBlank()) {
                continue;
            }
            currentMetricKeys.add(metricKey);
            String message = buildMessage(diag, period);

            AlertEvent existing = openByMetricKey.get(metricKey);
            if (existing != null) {
                // Dedup: same metricKey → update in place, do NOT re-create / re-notify.
                boolean changed = !Objects.equals(existing.getMessage(), message)
                        || existing.getSeverity() != severity;
                if (changed) {
                    existing.setMessage(message);
                    existing.setSeverity(severity);
                    eventRepository.save(existing);
                    refreshed++;
                }
                continue;
            }

            Map<String, Object> context = new HashMap<>();
            context.put("severity", severity);
            context.put("message", message);
            List<UUID> createdIds = alertEngineService.triggerAlert(
                    factoryId, AlertType.RESTAURANT_HEALTH_CHECK, BUSINESS_ENTITY_TYPE, metricKey, context);
            if (!createdIds.isEmpty()) {
                created++;
            }
        }

        int resolved = 0;
        for (AlertEvent e : openEvents) {
            if (e.getBusinessEntityId() != null && !currentMetricKeys.contains(e.getBusinessEntityId())) {
                alertEngineService.resolve(e.getId(), null); // null = 系统自动清除
                resolved++;
            }
        }

        log.info("[RestaurantHealthAlertBridgeService] factoryId={} period={} diagnoses={} created={} refreshed={} resolved={}",
                factoryId, period, diagnoses.size(), created, refreshed, resolved);

        result.put("created", created);
        result.put("refreshed", refreshed);
        result.put("resolved", resolved);
        return result;
    }

    private AlertRule ensureDefaultRule(String factoryId) {
        return ruleRepository.findByFactoryIdAndRuleName(factoryId, RULE_NAME)
                .orElseGet(() -> {
                    AlertRule created = ruleRepository.save(AlertRule.builder()
                            .factoryId(factoryId)
                            .alertType(AlertType.RESTAURANT_HEALTH_CHECK)
                            .ruleName(RULE_NAME)
                            // Fallback only — real events use the per-diagnosis severity
                            // override passed via context (see AlertEngineServiceImpl#resolveSeverity).
                            .severity(AlertSeverity.MID)
                            .enabled(true)
                            .notifyChannels(new ArrayList<>(DEFAULT_NOTIFY_CHANNELS))
                            .notifyRoles(new ArrayList<>(DEFAULT_NOTIFY_ROLES))
                            .build());
                    log.info("[RestaurantHealthAlertBridgeService] 自动创建默认规则: factoryId={}, ruleId={}",
                            factoryId, created.getId());
                    return created;
                });
    }

    private static AlertSeverity mapSeverity(String pythonSeverity) {
        if (pythonSeverity == null) {
            return null;
        }
        return switch (pythonSeverity) {
            case "critical" -> AlertSeverity.HIGH;
            case "warning" -> AlertSeverity.MID;
            default -> null;
        };
    }

    private static String buildMessage(Map<String, Object> diag, String period) {
        String nameZh = str(diag.get("metricNameZh"), str(diag.get("metricKey"), "指标"));
        String status = str(diag.get("status"), "");
        String desc = str(diag.get("descriptionZh"), "");
        boolean estimated = Boolean.TRUE.equals(diag.get("estimated"));

        StringBuilder sb = new StringBuilder(nameZh);
        if (period != null && !period.isBlank()) {
            sb.append(" (").append(period).append(")");
        }
        if (!status.isBlank()) {
            sb.append(" 状态: ").append(status);
        }
        if (!desc.isBlank()) {
            sb.append(" — ").append(desc);
        }
        if (estimated) {
            sb.append(" [估算值, 非精确核算]");
        }
        String actionText = firstActionText(diag.get("rxActions"));
        if (!actionText.isBlank()) {
            sb.append(" 建议: ").append(actionText);
        }
        return sb.toString();
    }

    /** 防御性提取第一条建议文案 — 字段名不确定时优雅退化为空字符串, 不抛异常. */
    private static String firstActionText(Object rxActionsRaw) {
        if (!(rxActionsRaw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return first != null ? String.valueOf(first) : "";
        }
        for (String key : List.of("actionZh", "text", "description", "message", "action")) {
            Object v = m.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String str(Object o, String fallback) {
        if (o == null) {
            return fallback;
        }
        String s = String.valueOf(o);
        return s.equals("null") ? fallback : s;
    }
}
