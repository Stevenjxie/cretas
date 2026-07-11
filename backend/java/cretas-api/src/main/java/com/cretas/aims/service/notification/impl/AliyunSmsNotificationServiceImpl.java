package com.cretas.aims.service.notification.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.notification.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aliyun 短信网关 NotificationService — Sprint 9 P1.2 (升级真 SMS).
 *
 * <p>Sprint 6 ship 时 {@code LoggingNotificationServiceImpl} 仅 log; Sprint 8 P1.1 升级为
 * {@link DbNotificationServiceImpl} 持久化到 {@code notifications} 表; 本 impl (Sprint 9 P1.2)
 * 在 DB 持久化基础上**额外发送 SMS** 给目标用户手机号 (来自 {@link User#getPhone}).
 *
 * <h2>开启方式</h2>
 * <pre>
 *   notification.gateway=aliyun-sms          # default: logging (LoggingFallback)
 *   aliyun.sms.access-key-id=${ALIBABA_ACCESSKEY_ID}
 *   aliyun.sms.access-key-secret=${ALIBABA_SECRET_KEY}
 *   aliyun.sms.sign-name=Cretas              # 短信签名 (placeholder, 由 Steve 在阿里云 短信服务控制台配置)
 *   aliyun.sms.template-code=SMS_XXXXXXXXX   # 通知模板 ID (placeholder, 由 Steve 申请)
 *   aliyun.sms.endpoint=dysmsapi.aliyuncs.com
 * </pre>
 *
 * <h2>失败行为 (graceful degrade)</h2>
 * <ul>
 *   <li>Aliyun API 抛异常 → log error + 继续 DB 持久化 (caller 不感知)</li>
 *   <li>用户无手机号 → 仅 DB 持久化, 不发 SMS (warn log)</li>
 *   <li>aliyun.sms.* 配置不全 (sign-name / template-code = placeholder) → 启动时 warn,
 *       runtime 仅 DB 不发 SMS</li>
 * </ul>
 *
 * <h2>Bean 优先级</h2>
 * {@code @Primary} 让 caller 注入 {@link NotificationService} 自动拿到本 impl
 * (当 {@code notification.gateway=aliyun-sms}). {@link DbNotificationServiceImpl} 仍存在,
 * 作为持久化 delegate (本 impl 通过 {@link UserRepository} + Aliyun SDK 自行处理 SMS,
 * 但 DB 持久化复用 {@link DbNotificationServiceImpl} 减少重复代码).
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 9 P1.2)
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "notification.gateway", havingValue = "aliyun-sms")
public class AliyunSmsNotificationServiceImpl implements NotificationService {

    private final DbNotificationServiceImpl dbDelegate;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aliyun.sms.access-key-id:placeholder}")
    private String accessKeyId;

    @Value("${aliyun.sms.access-key-secret:placeholder}")
    private String accessKeySecret;

    @Value("${aliyun.sms.endpoint:dysmsapi.aliyuncs.com}")
    private String endpoint;

    @Value("${aliyun.sms.sign-name:placeholder}")
    private String signName;

    @Value("${aliyun.sms.template-code:placeholder}")
    private String templateCode;

    /** Lazily-initialized Aliyun SDK client; null when credentials are placeholder. */
    private Client smsClient;

    /** True iff configuration is complete enough to actually send SMS (vs. just persisting). */
    private boolean smsEnabled;

    @Autowired
    public AliyunSmsNotificationServiceImpl(DbNotificationServiceImpl dbDelegate,
            UserRepository userRepository) {
        this.dbDelegate = dbDelegate;
        this.userRepository = userRepository;
    }

    /** Visible-for-test constructor: lets tests inject mocked delegate + repository + client. */
    AliyunSmsNotificationServiceImpl(DbNotificationServiceImpl dbDelegate,
            UserRepository userRepository, Client smsClient,
            String signName, String templateCode) {
        this.dbDelegate = dbDelegate;
        this.userRepository = userRepository;
        this.smsClient = smsClient;
        this.signName = signName;
        this.templateCode = templateCode;
        this.smsEnabled = smsClient != null
                && !"placeholder".equals(signName)
                && !"placeholder".equals(templateCode);
    }

    @PostConstruct
    void init() {
        boolean credentialsOk = !"placeholder".equals(accessKeyId)
                && !"placeholder".equals(accessKeySecret);
        boolean templateOk = !"placeholder".equals(signName)
                && !"placeholder".equals(templateCode);

        if (!credentialsOk || !templateOk) {
            this.smsEnabled = false;
            log.warn("[AliyunSms] 配置不完整 — credentialsOk={}, templateOk={}. "
                    + "通知仍会 DB 持久化, 但不发 SMS. "
                    + "需配置 aliyun.sms.{access-key-id, access-key-secret, sign-name, template-code}.",
                    credentialsOk, templateOk);
            return;
        }
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint);
            this.smsClient = new Client(config);
            this.smsEnabled = true;
            log.info("[AliyunSms] 初始化完成 — endpoint={}, signName={}, templateCode={}",
                    endpoint, signName, templateCode);
        } catch (Exception e) {
            this.smsEnabled = false;
            log.error("[AliyunSms] 初始化失败 — {}. 通知仅 DB 持久化, 不发 SMS.",
                    e.getMessage(), e);
        }
    }

    @Override
    public void notifyRole(String factoryId, String roleCode, String title, String body) {
        // 1. 持久化 (复用 DbNotificationServiceImpl)
        dbDelegate.notifyRole(factoryId, roleCode, title, body);

        // 2. 查 role 下用户 + 群发 SMS
        if (!smsEnabled) {
            return;
        }
        try {
            List<User> users = userRepository.findByFactoryIdAndRoleCode(factoryId, roleCode);
            int sent = 0, skipped = 0;
            for (User u : users) {
                if (u.getPhone() == null || u.getPhone().isBlank()) {
                    skipped++;
                    continue;
                }
                if (sendSms(u.getPhone(), title, body)) {
                    sent++;
                } else {
                    skipped++;
                }
            }
            log.info("[AliyunSms] notifyRole factory={} role={}: sent={}, skipped={}",
                    factoryId, roleCode, sent, skipped);
        } catch (Exception e) {
            // graceful: DB 已持久化, SMS 失败不传播
            log.error("[AliyunSms] notifyRole SMS 群发失败 factory={} role={}: {}",
                    factoryId, roleCode, e.getMessage(), e);
        }
    }

    @Override
    public void notifyUser(String factoryId, Long userId, String title, String body) {
        dbDelegate.notifyUser(factoryId, userId, title, body);

        if (!smsEnabled) {
            return;
        }
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
                log.warn("[AliyunSms] notifyUser userId={} 无手机号, 仅 DB 持久化", userId);
                return;
            }
            sendSms(user.getPhone(), title, body);
        } catch (Exception e) {
            log.error("[AliyunSms] notifyUser SMS 发送失败 factory={} userId={}: {}",
                    factoryId, userId, e.getMessage(), e);
        }
    }

    @Override
    public void broadcastFactory(String factoryId, String title, String body) {
        // 广播 SMS 风险高 (短信成本 + 用户骚扰), 仅 DB 持久化, 不发 SMS
        // (per system 设计: factory-wide broadcast 留 push notification + UI bell, 不上 SMS)
        dbDelegate.broadcastFactory(factoryId, title, body);
        log.info("[AliyunSms] broadcastFactory factory={} title={} — 仅 DB, 不发 SMS (政策)",
                factoryId, title);
    }

    /**
     * 调 Aliyun SMS API 发送单条短信.
     *
     * @return true=API 调用且返回 OK; false=失败 (已 log error)
     */
    boolean sendSms(String phoneNumber, String title, String body) {
        if (smsClient == null || !smsEnabled) {
            return false;
        }
        try {
            // 模板参数 (template 由 Steve 在阿里云短信服务控制台预先创建,
            // 例: 模板 = "【Cretas】${title}: ${content}", 含 title/content 两个变量)
            Map<String, String> templateParams = new HashMap<>();
            templateParams.put("title", truncate(title, 30));
            templateParams.put("content", truncate(body, 50));
            String templateParamJson = objectMapper.writeValueAsString(templateParams);

            SendSmsRequest req = new SendSmsRequest()
                    .setPhoneNumbers(phoneNumber)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam(templateParamJson);

            SendSmsResponse resp = smsClient.sendSms(req);

            String code = resp.getBody() != null ? resp.getBody().getCode() : null;
            if ("OK".equals(code)) {
                log.debug("[AliyunSms] SMS sent OK — phone={}, bizId={}",
                        maskPhone(phoneNumber),
                        resp.getBody() != null ? resp.getBody().getBizId() : "?");
                return true;
            } else {
                log.error("[AliyunSms] SMS API 返回失败 — phone={}, code={}, msg={}",
                        maskPhone(phoneNumber), code,
                        resp.getBody() != null ? resp.getBody().getMessage() : "?");
                return false;
            }
        } catch (JsonProcessingException e) {
            log.error("[AliyunSms] 序列化 templateParam 失败 — phone={}: {}",
                    maskPhone(phoneNumber), e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("[AliyunSms] SMS API 调用异常 — phone={}: {}",
                    maskPhone(phoneNumber), e.getMessage(), e);
            return false;
        }
    }

    /** Mask phone for log: 13812345678 → 138****5678. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** Truncate to N chars to fit SMS template variable limits (typically 30/50). */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    boolean isSmsEnabled() {
        return smsEnabled;
    }

    /**
     * Public interface method — {@code AlertEventNotificationListener} (餐饮经营
     * 体检预警) reads this to decide whether to record an honest "SMS 网关未配置"
     * audit note instead of silently no-op-ing. Delegates to the same
     * {@link #smsEnabled} flag {@link #sendSms} already checks.
     *
     * @since 2026-07-11
     */
    @Override
    public boolean isSmsAvailable() {
        return smsEnabled;
    }
}
