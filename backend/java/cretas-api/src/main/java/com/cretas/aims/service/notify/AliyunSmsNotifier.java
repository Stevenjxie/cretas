package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Aliyun 短信通知 gateway — Phase 3 Canvas-Notify SMS channel SDK integration.
 *
 * <p><b>Architecture</b>: 本类是 Canvas-Notify 体系的 SMS gateway, 跟
 * {@link com.cretas.aims.service.notify.impl.SmsSender} ({@link NotifySender} 接口实现)
 * 解耦. Future sister chat 会让 {@code SmsSender} 注入本类来真正发送短信; 本 PR 仅交付
 * gateway + 测试, **不修改** SmsSender / NotifyDispatchService / Template Controller.
 *
 * <p><b>POP API signed request</b>: 实现阿里云 POP API 公共参数 + RPC 风格签名 (HMAC-SHA1),
 * 不引入 aliyun POP SDK 减小依赖体积. 协议参考
 * <a href="https://help.aliyun.com/document_detail/55284.html">阿里云短信 API 文档</a>.
 *
 * <p>对照 {@code service.notification.impl.AliyunSmsNotificationServiceImpl} (Sprint 9 P1.2):
 * 那个是给老的 {@code NotificationService} 接口用 (用 aliyun dysmsapi SDK), 本类是给 Canvas-Notify
 * 用 (NotifyTemplate / params / 多 recipient). 两者并存, 命名空间 (config / package) 隔离.
 *
 * <h2>配置 (application properties)</h2>
 * <pre>
 *   notify.aliyun-sms.access-key=${ALIYUN_SMS_ACCESS_KEY:}
 *   notify.aliyun-sms.access-secret=${ALIYUN_SMS_ACCESS_SECRET:}
 *   notify.aliyun-sms.sign-name=${ALIYUN_SMS_SIGN_NAME:}
 *   notify.aliyun-sms.endpoint=https://dysmsapi.aliyuncs.com/
 *   notify.aliyun-sms.region=cn-hangzhou
 * </pre>
 *
 * <h2>NotifyTemplate → Aliyun 模板映射</h2>
 * <ul>
 *   <li>NotifyTemplate.templateCode → Aliyun {@code TemplateCode} (e.g. {@code SMS_12345678})</li>
 *   <li>NotifyRequest.params (Map) → Aliyun {@code TemplateParam} (JSON 字符串)</li>
 *   <li>所以 Canvas 配 NotifyTemplate.templateCode 时需用阿里云已审核通过的模板 code,
 *       变量名也需与阿里云模板变量名一致.</li>
 * </ul>
 *
 * <h2>错误处理 (fool-proof Rule 1 — pre-check, not silent fail)</h2>
 * <ul>
 *   <li>配置不全 → {@link IllegalStateException} (清楚错误信息, 不静默)</li>
 *   <li>recipientPhones 为空 → 直接返空 list, 不 call API (省 quota)</li>
 *   <li>Aliyun API 返非 OK → 标记每个手机号 FAILED + 保留 Code/Message</li>
 *   <li>网络/IO 异常 → 标记全部手机号 FAILED + 异常摘要</li>
 * </ul>
 *
 * <h2>合规提醒 (per CREDENTIAL-MANAGEMENT.md + Aliyun 规约)</h2>
 * <ul>
 *   <li>短信营销需备案签名 + 模板, 业务通知短信请走"通知"类模板, 不要发营销内容 (违反工信部规定)</li>
 *   <li>同号码同模板 1 分钟最多 1 条, 1 小时最多 5 条, 24 小时最多 10 条 (Aliyun 默认限制)</li>
 *   <li>群发时控制并发, 避免触发 {@code isv.BUSINESS_LIMIT_CONTROL} 频控</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-21 (Phase 3 SMS gateway)
 */
@Slf4j
@Service
public class AliyunSmsNotifier {

    /** POP API version for dysmsapi (per Aliyun 短信 API 文档). */
    private static final String API_VERSION = "2017-05-25";

    /** RPC action name. */
    private static final String ACTION_SEND_SMS = "SendSms";

    /** Signature algorithm per POP RPC signing spec. */
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String SIGNATURE_VERSION = "1.0";

    /** ISO-8601 timestamp formatter (UTC, no millis), Aliyun convention. */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Value("${notify.aliyun-sms.access-key:}")
    private String accessKey;

    @Value("${notify.aliyun-sms.access-secret:}")
    private String accessSecret;

    @Value("${notify.aliyun-sms.sign-name:}")
    private String signName;

    @Value("${notify.aliyun-sms.endpoint:https://dysmsapi.aliyuncs.com/}")
    private String endpoint;

    @Value("${notify.aliyun-sms.region:cn-hangzhou}")
    private String regionId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HTTP client (lazily initialized in {@link #httpClient()} to allow test injection). */
    private HttpClient httpClient;

    /**
     * Default Spring-injected constructor.
     */
    public AliyunSmsNotifier() {
        // Spring sets @Value fields after construction
    }

    /**
     * Visible-for-test constructor: lets tests inject mocked HttpClient + config.
     */
    AliyunSmsNotifier(HttpClient httpClient, String accessKey, String accessSecret,
                     String signName, String endpoint, String regionId) {
        this.httpClient = httpClient;
        this.accessKey = accessKey;
        this.accessSecret = accessSecret;
        this.signName = signName;
        this.endpoint = endpoint;
        this.regionId = regionId;
    }

    /**
     * 发送短信到一批手机号.
     *
     * <p>同模板批量发: 阿里云 SendSms 单次最多 1000 个手机号, 但本方法 1 个号 1 次请求 (per-recipient
     * audit + 每个号独立成功/失败状态). 调用方 (e.g. 未来 SmsSender) 负责 fan-out 控制 + Rate limit.
     *
     * @param template NotifyTemplate; {@link NotifyTemplate#getTemplateCode} 需用阿里云已审模板 code,
     *                 variablesSchemaJson 字段约定的变量名需与阿里云模板一致.
     * @param variables 实际参数, key 对应模板变量名 (例: {"name":"张三","code":"123456"})
     * @param recipientPhones 收件手机号列表 (E.164 或 国内 11 位)
     * @return per-recipient result list (顺序与 recipientPhones 一致). 空 input → 空 list.
     * @throws IllegalStateException 配置不全 (access-key / access-secret / sign-name 任一为空)
     */
    public List<SmsResult> send(NotifyTemplate template, Map<String, Object> variables,
                                 List<String> recipientPhones) {
        if (template == null) {
            throw new IllegalArgumentException("AliyunSmsNotifier.send: template 不能为空");
        }
        if (template.getTemplateCode() == null || template.getTemplateCode().isBlank()) {
            throw new IllegalArgumentException(
                    "AliyunSmsNotifier.send: template.templateCode 为空 — 需用阿里云已审核通过的模板 code");
        }
        if (recipientPhones == null || recipientPhones.isEmpty()) {
            log.debug("[AliyunSmsNotifier] recipientPhones 为空, 跳过 API 调用");
            return Collections.emptyList();
        }
        ensureConfigured();

        // 序列化 variables → JSON string (阿里云要求 TemplateParam 是 JSON object 字符串)
        String templateParam;
        try {
            templateParam = objectMapper.writeValueAsString(variables != null ? variables : Map.of());
        } catch (JsonProcessingException e) {
            String err = "templateParam 序列化失败: " + e.getMessage();
            log.error("[AliyunSmsNotifier] {}", err, e);
            return failAll(recipientPhones, err);
        }

        List<SmsResult> results = new ArrayList<>(recipientPhones.size());
        for (String phone : recipientPhones) {
            results.add(sendOne(phone, template.getTemplateCode(), templateParam));
        }
        return results;
    }

    /**
     * 单号发送, 不抛异常, 失败返 SmsResult.failed.
     */
    SmsResult sendOne(String phone, String templateCode, String templateParam) {
        if (phone == null || phone.isBlank()) {
            return SmsResult.failed(phone, "EMPTY_PHONE", "手机号为空");
        }
        try {
            Map<String, String> params = buildBusinessParams(phone, templateCode, templateParam);
            Map<String, String> commonParams = buildCommonParams();
            // merge
            TreeMap<String, String> allParams = new TreeMap<>();
            allParams.putAll(commonParams);
            allParams.putAll(params);

            String signature = sign(allParams, accessSecret);
            allParams.put("Signature", signature);

            String body = encodeForm(allParams);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
            return parseResponse(phone, resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.error("[AliyunSmsNotifier] sendOne 异常 phone={}: {}",
                    maskPhone(phone), e.getMessage(), e);
            return SmsResult.failed(phone, "EXCEPTION", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 解析阿里云 SendSms 响应.
     *
     * <p>成功 body 样例: {@code {"Message":"OK","RequestId":"...","Code":"OK","BizId":"..."}}.
     * 失败 body 含 {@code Code} 非 OK (e.g. {@code isv.BUSINESS_LIMIT_CONTROL}).
     */
    SmsResult parseResponse(String phone, int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            return SmsResult.failed(phone, "HTTP_" + statusCode, truncate(body, 200));
        }
        try {
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            String code = String.valueOf(resp.getOrDefault("Code", "?"));
            String message = String.valueOf(resp.getOrDefault("Message", ""));
            String bizId = resp.containsKey("BizId") ? String.valueOf(resp.get("BizId")) : null;
            String requestId = resp.containsKey("RequestId") ? String.valueOf(resp.get("RequestId")) : null;

            if ("OK".equals(code)) {
                log.debug("[AliyunSmsNotifier] SMS sent OK phone={} bizId={} requestId={}",
                        maskPhone(phone), bizId, requestId);
                return SmsResult.sent(phone, bizId, requestId);
            }
            log.warn("[AliyunSmsNotifier] SMS API 拒绝 phone={} code={} msg={}",
                    maskPhone(phone), code, message);
            return SmsResult.failed(phone, code, message);
        } catch (Exception e) {
            return SmsResult.failed(phone, "PARSE_ERROR", e.getMessage() + " | body=" + truncate(body, 100));
        }
    }

    /** Construct business-specific params for SendSms. */
    private Map<String, String> buildBusinessParams(String phone, String templateCode, String templateParam) {
        Map<String, String> m = new HashMap<>();
        m.put("Action", ACTION_SEND_SMS);
        m.put("Version", API_VERSION);
        m.put("RegionId", regionId);
        m.put("PhoneNumbers", phone);
        m.put("SignName", signName);
        m.put("TemplateCode", templateCode);
        m.put("TemplateParam", templateParam);
        return m;
    }

    /** Common POP RPC parameters (per Aliyun signing spec). */
    private Map<String, String> buildCommonParams() {
        Map<String, String> m = new HashMap<>();
        m.put("Format", "JSON");
        m.put("AccessKeyId", accessKey);
        m.put("SignatureMethod", SIGNATURE_METHOD);
        m.put("SignatureVersion", SIGNATURE_VERSION);
        m.put("SignatureNonce", UUID.randomUUID().toString());
        m.put("Timestamp", TIMESTAMP_FORMATTER.format(Instant.now()));
        return m;
    }

    /**
     * Compute POP RPC signature per
     * <a href="https://help.aliyun.com/document_detail/315526.html">signing spec</a>.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Sort params by key (ASCII)</li>
     *   <li>Build canonical query string: {@code k1=encode(v1)&k2=encode(v2)} using POP-percent-encoding</li>
     *   <li>String-to-sign: {@code HTTPMethod + "&" + percentEncode("/") + "&" + percentEncode(canonical)}</li>
     *   <li>HMAC-SHA1 with key = {@code accessSecret + "&"}, then Base64</li>
     * </ol>
     *
     * <p>Visible for test.
     */
    static String sign(TreeMap<String, String> params, String accessSecret) {
        // Canonical query
        StringBuilder canonical = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) canonical.append('&');
            canonical.append(popEncode(e.getKey()))
                     .append('=')
                     .append(popEncode(e.getValue()));
            first = false;
        }
        String stringToSign = "POST&" + popEncode("/") + "&" + popEncode(canonical.toString());

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessSecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 signing failed: " + e.getMessage(), e);
        }
    }

    /**
     * POP-style percent encoding per Aliyun spec:
     * <ul>
     *   <li>URLEncoder.encode UTF-8</li>
     *   <li>{@code +} → {@code %20}</li>
     *   <li>{@code *} → {@code %2A}</li>
     *   <li>{@code %7E} → {@code ~}</li>
     * </ul>
     *
     * <p>Visible for test.
     */
    static String popEncode(String s) {
        if (s == null) return "";
        String encoded = URLEncoder.encode(s, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /** Encode params as form body (different rules than POP percent — but Aliyun accepts standard form). */
    private static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /** Validate required config before any network call. fool-proof Rule 1. */
    private void ensureConfigured() {
        boolean ok = isNonBlank(accessKey) && isNonBlank(accessSecret) && isNonBlank(signName);
        if (!ok) {
            throw new IllegalStateException(
                    "AliyunSmsNotifier 配置不全 — 请检查 notify.aliyun-sms.access-key / "
                            + "notify.aliyun-sms.access-secret / notify.aliyun-sms.sign-name "
                            + "(via env ALIYUN_SMS_ACCESS_KEY / ALIYUN_SMS_ACCESS_SECRET / ALIYUN_SMS_SIGN_NAME)");
        }
    }

    /** Lazily get HttpClient (allows tests to inject mock via package-private constructor). */
    private HttpClient httpClient() {
        if (httpClient == null) {
            // Connect timeout 5s, read timeout per-request (10s in sendOne)
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }
        return httpClient;
    }

    private static List<SmsResult> failAll(List<String> phones, String err) {
        List<SmsResult> out = new ArrayList<>(phones.size());
        for (String p : phones) out.add(SmsResult.failed(p, "PREPARE_ERROR", err));
        return out;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Mask phone for log: 13812345678 → 138****5678. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return String.valueOf(phone);
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Check if SMS gateway is configured (avoids reflection in callers / tests). */
    public boolean isConfigured() {
        return isNonBlank(accessKey) && isNonBlank(accessSecret) && isNonBlank(signName);
    }

    /**
     * Per-recipient send result.
     *
     * @param phone     收件号码 (未 mask, 调用方需自行 mask)
     * @param success   true=Aliyun returned Code=OK
     * @param code      Aliyun response Code or local error key (e.g. HTTP_500 / EXCEPTION / EMPTY_PHONE)
     * @param message   Aliyun response Message or local error message
     * @param bizId     Aliyun BizId on success, null on failure
     * @param requestId Aliyun RequestId, may be null
     */
    public record SmsResult(String phone, boolean success, String code, String message,
                            String bizId, String requestId) {
        public static SmsResult sent(String phone, String bizId, String requestId) {
            return new SmsResult(phone, true, "OK", "OK", bizId, requestId);
        }
        public static SmsResult failed(String phone, String code, String message) {
            return new SmsResult(phone, false, code, message, null, null);
        }
    }
}
