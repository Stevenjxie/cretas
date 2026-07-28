package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.WechatRecord;
import com.cretas.aims.entity.enums.WechatDirection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.WechatRecordRepository;
import com.cretas.aims.service.WechatRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * 补录微信记录 Tool (Sprint 8 P1 — 卤味老板 Workdesk V1, WRITE + Preview).
 *
 * <p>给销售员"补录刚跟客户的微信对话"的快捷入口. 业务逻辑全走 {@link WechatRecordService}
 * (不重复实现), Tool 只做参数适配 + R4 5min dedup preview.
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: messageContent 必填非空 (由 service 检查)</li>
 *   <li>R2: doExecute 返 message 含 customer 名 + 几个字预览 (LLM 输出明确上下文)</li>
 *   <li>R3: direction enum 白名单 (INBOUND/OUTBOUND/INTERNAL)</li>
 *   <li>R4: doPreview() 5min dedup check, 命中返 status=DUPLICATE + actionHint (不实际写)</li>
 *   <li>error: 后端 message 原样透传, sticky toast</li>
 * </ul>
 *
 * <p>Intent Code: {@code WECHAT_CREATE}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class WechatRecordCreateTool extends AbstractBusinessTool {

    /** 防呆 R4 dedup window — 与 WechatRecordService.create 内部 5min 对齐. */
    public static final int DEDUP_WINDOW_MINUTES = 5;

    /** Content 预览长度 (R2 context message). */
    private static final int CONTENT_PREVIEW_LEN = 40;

    @Autowired
    private WechatRecordService wechatRecordService;

    @Autowired
    private WechatRecordRepository wechatRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "wechat_record_create";
    }

    @Override
    public String getDescription() {
        return "补录跟客户的微信对话记录 (业务员手工录入或粘贴聊天截图整理). "
                + "LLM 触发场景: 用户说 '补录跟张总的微信' / '记一下刚跟王老板的微信' / "
                + "'添加微信记录' / '新增微信跟进'. "
                + "WRITE + Preview 支持. 防呆 R4 5min 内同 (customer, content) 重复抛 409.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 UUID, 必填. 可由 customer_search Tool 解析客户名得到");
        properties.put("customerId", customerId);

        Map<String, Object> direction = new HashMap<>();
        direction.put("type", "string");
        direction.put("description",
                "微信消息方向 (R3 enum 白名单): INBOUND=客户来信 / OUTBOUND=我方去信 / INTERNAL=内部备注");
        direction.put("enum", List.of("INBOUND", "OUTBOUND", "INTERNAL"));
        properties.put("direction", direction);

        Map<String, Object> messageContent = new HashMap<>();
        messageContent.put("type", "string");
        messageContent.put("description", "消息正文, 必填非空");
        properties.put("messageContent", messageContent);

        Map<String, Object> recordTime = new HashMap<>();
        recordTime.put("type", "string");
        recordTime.put("description", "可选 — 微信时间 ISO 格式. 留空默认现在");
        properties.put("recordTime", recordTime);

        Map<String, Object> contactPerson = new HashMap<>();
        contactPerson.put("type", "string");
        contactPerson.put("description", "可选 — 客户端联系人姓名");
        properties.put("contactPerson", contactPerson);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "可选 — 备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", List.of("customerId", "direction", "messageContent"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("customerId", "direction", "messageContent");
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

    /**
     * Preview: R4 dedup check + R2 context summary. 不实际写 DB.
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");
        String directionStr = getString(params, "direction");
        String messageContent = getString(params, "messageContent");

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");

        // 解析 customer 名 (R2 context)
        String customerName = resolveCustomerName(factoryId, customerId);
        result.put("customerId", customerId);
        result.put("customerName", customerName);
        result.put("direction", directionStr);
        result.put("messagePreview", truncate(messageContent, CONTENT_PREVIEW_LEN));

        // R4 dedup check
        if (messageContent != null && !messageContent.isBlank()) {
            LocalDateTime since = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
            List<WechatRecord> recent = wechatRecordRepository.findRecentByContent(
                    factoryId, customerId, messageContent, since);
            if (!recent.isEmpty()) {
                Long existingId = recent.get(0).getId();
                result.put("status", "DUPLICATE");
                result.put("canDo", false);
                result.put("existingId", existingId);
                result.put("actionHint",
                        "/sales/customers/" + customerId + "?tab=wechat&highlight=" + existingId);
                result.put("message", String.format(
                        "⚠️ %s 5 分钟内已有相同内容的微信记录 (id=%d). 请避免重复提交.",
                        nameForDisplay(customerName), existingId));
                return result;
            }
        }

        result.put("canDo", true);
        result.put("message", String.format(
                "✓ 准备补录 %s 的微信记录 (%s): \"%s\"",
                nameForDisplay(customerName),
                directionDisplayForFallback(directionStr),
                truncate(messageContent, CONTENT_PREVIEW_LEN)));
        return result;
    }

    /**
     * Execute: 委托 WechatRecordService.create — 含 service 层的 R4 dedup 二次防御.
     * Service 抛 409 时, Tool catch 返 status=DUPLICATE + actionHint.
     */
    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");
        String directionStr = getString(params, "direction");
        String messageContent = getString(params, "messageContent");
        String recordTimeStr = getString(params, "recordTime", null);
        String contactPerson = getString(params, "contactPerson", null);
        String remark = getString(params, "remark", null);
        Long currentUserId = getUserId(context);
        String currentUserName = (String) context.get("userName");

        WechatDirection direction;
        try {
            direction = WechatDirection.valueOf(directionStr);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400,
                    "direction 非法: " + directionStr + " (合法: INBOUND/OUTBOUND/INTERNAL)");
        }

        LocalDateTime recordTime = null;
        if (recordTimeStr != null && !recordTimeStr.isBlank()) {
            try {
                recordTime = LocalDateTime.parse(recordTimeStr);
            } catch (Exception ex) {
                throw new BusinessException(400, "recordTime 格式非法 (期望 ISO 格式): " + recordTimeStr);
            }
        }

        log.info("wechat_record_create — factory={} customer={} direction={} createdBy={}",
                factoryId, customerId, direction, currentUserId);

        try {
            WechatRecord saved = wechatRecordService.create(factoryId, customerId, direction,
                    messageContent, currentUserId, currentUserName,
                    recordTime, contactPerson, remark);

            String customerName = resolveCustomerName(factoryId, customerId);

            Map<String, Object> data = new HashMap<>();
            data.put("id", saved.getId());
            data.put("customerId", saved.getCustomerId());
            data.put("customerName", customerName);
            data.put("direction", saved.getDirection().name());
            data.put("messagePreview", truncate(saved.getMessageContent(), CONTENT_PREVIEW_LEN));
            data.put("recordTime",
                    saved.getRecordTime() != null ? saved.getRecordTime().toString() : null);

            String message = String.format("✓ 已补录 %s 的微信记录 (id=%d, %s)",
                    nameForDisplay(customerName), saved.getId(),
                    saved.getDirection().getDisplayName());
            return buildSimpleResult(message, data);

        } catch (BusinessException ex) {
            // R4 dedup hit at service layer → 转 status=DUPLICATE struct
            if (ex.getCode() != null && ex.getCode() == 409) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "DUPLICATE");
                result.put("message", "⚠️ " + ex.getMessage());
                if (ex.getActionHint() != null) {
                    result.put("actionHint", ex.getActionHint());
                }
                return result;
            }
            throw ex;
        }
    }

    private String resolveCustomerName(String factoryId, String customerId) {
        if (customerId == null) return null;
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt.map(Customer::getName).orElse(null);
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    private String nameForDisplay(String name) {
        return (name != null && !name.isBlank()) ? name : "该客户";
    }

    private String directionDisplayForFallback(String dir) {
        try {
            return WechatDirection.valueOf(dir).getDisplayName();
        } catch (Exception ex) {
            return dir;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
