package com.cretas.aims.service.notify.impl;

import com.cretas.aims.entity.notify.NotifyChannel;
import com.cretas.aims.entity.notify.NotifyLog;
import com.cretas.aims.entity.notify.NotifyStatus;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyLogRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.service.notify.NotifyAuditException;
import com.cretas.aims.service.notify.NotifyRequest;
import com.cretas.aims.service.notify.NotifyResult;
import com.cretas.aims.service.notify.NotifySender;
import com.cretas.aims.service.notify.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 钉钉群机器人 sender — Phase 3 Canvas-Notify Step T4 (real impl, 2026-05-21).
 *
 * <p>实现方式: <b>群机器人 webhook</b> (钉钉自定义机器人).
 * 访问令牌存 access_token query param, 加签密钥 (secret) 用于 HMAC-SHA256 签名.
 *
 * <p><b>配置</b> (application.properties):
 * <pre>{@code
 * notify.dingtalk.webhook-url=${DINGTALK_WEBHOOK:}
 * notify.dingtalk.secret=${DINGTALK_SECRET:}
 * notify.dingtalk.msg-type=${DINGTALK_MSG_TYPE:text}   # 可选: text|markdown, 默认 text
 * notify.dingtalk.connect-timeout=${DINGTALK_CONNECT_TIMEOUT_MS:5000}
 * notify.dingtalk.read-timeout=${DINGTALK_READ_TIMEOUT_MS:10000}
 * }</pre>
 *
 * <p><b>签名算法</b> (官方 doc {@code open.dingtalk.com/document/group/custom-robot-access}):
 * <ol>
 *   <li>{@code stringToSign = timestamp + "\n" + secret}</li>
 *   <li>{@code sign = base64(HmacSHA256(secret, stringToSign))}</li>
 *   <li>{@code url = webhookUrl + "&timestamp=" + ts + "&sign=" + urlEncode(sign)}</li>
 * </ol>
 *
 * <p><b>消息体 shape</b>:
 * <pre>{@code
 * // text:
 * { "msgtype": "text",
 *   "text": { "content": "<rendered title>\n<rendered body>" },
 *   "at": { "atMobiles": [...], "isAtAll": false } }
 *
 * // markdown:
 * { "msgtype": "markdown",
 *   "markdown": { "title": "<rendered title>", "text": "<rendered body>" },
 *   "at": { "atMobiles": [...], "isAtAll": false } }
 * }</pre>
 *
 * <p><b>钉钉响应</b>: HTTP 200 + body {@code {"errcode":0,"errmsg":"ok"}} = 成功.
 * 非 0 errcode (如 310000 关键词不命中 / 300001 鉴权失败) 视为 FAILED.
 *
 * <p><b>@-mobiles 来源</b>: 当前从 {@code request.params().get("atMobiles")} 取
 * (List&lt;String&gt; 或 单 String), 兼容 sister 实施时 NotifyRequest 扩展.
 *
 * <p><b>Fool-proof</b> (per {@code .claude/rules/fool-proof-design.md}):
 * <ul>
 *   <li>未配置 webhook → 写 FAILED log + 返 FAILED + 明确 next-action 文案</li>
 *   <li>HTTP 4xx/5xx / 钉钉 errcode != 0 → 写 FAILED log + 返 FAILED + 保留 errmsg</li>
 *   <li>logRepository.save 失败 → throw NotifyAuditException (Phase 3 review High #2/#3)</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 real impl)
 */
@Slf4j
@Component
public class DingTalkSender implements NotifySender {

    /** 钉钉机器人 webhook URL — 完整 URL 含 {@code access_token=xxx}. */
    @Value("${notify.dingtalk.webhook-url:}")
    private String webhookUrl;

    /** 加签密钥 (机器人安全设置选"加签"后获得). 留空表示未启用加签 — 不推荐. */
    @Value("${notify.dingtalk.secret:}")
    private String secret;

    /** 消息类型: text 或 markdown, 默认 text. */
    @Value("${notify.dingtalk.msg-type:text}")
    private String msgType;

    @Value("${notify.dingtalk.connect-timeout:5000}")
    private int connectTimeoutMs;

    @Value("${notify.dingtalk.read-timeout:10000}")
    private int readTimeoutMs;

    @Autowired
    private NotifyTemplateRepository templateRepository;

    @Autowired
    private NotifyLogRepository logRepository;

    @Autowired
    private TemplateEngine templateEngine;

    /** Lazily-built RestTemplate (timeout 应用一次, 注入测试可覆盖). */
    private RestTemplate restTemplate;

    /** Reused ObjectMapper — Jackson 实例线程安全. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        if (restTemplate == null) {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            restTemplate = new RestTemplate(factory);
        }
    }

    /** 测试钩: 注入 mock RestTemplate (避免真发 HTTP). */
    void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public NotifyResult send(NotifyRequest request) {
        if (request == null) {
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, "request 为空");
        }

        // 1. 配置检查
        if (webhookUrl == null || webhookUrl.isBlank()) {
            String errMsg = "钉钉 webhook 未配置 — 请在 application.properties 设置 notify.dingtalk.webhook-url 或环境变量 DINGTALK_WEBHOOK";
            log.warn("[DingTalkSender] {}", errMsg);
            writeFailedLogPerRecipient(request, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }

        String factoryId = request.factoryId();
        String templateCode = request.templateCode();
        List<Long> recipients = request.recipientUserIds();

        // 2. 加载 template (factory-scoped)
        Optional<NotifyTemplate> templateOpt =
                templateRepository.findByFactoryIdAndTemplateCode(factoryId, templateCode);
        if (templateOpt.isEmpty()) {
            String errMsg = "通知模板不存在: factoryId=" + factoryId + ", templateCode=" + templateCode;
            log.warn("[DingTalkSender] {}", errMsg);
            writeLog(factoryId, templateCode, null, NotifyStatus.FAILED, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }

        NotifyTemplate template = templateOpt.get();
        String renderedTitle;
        String renderedBody;
        try {
            renderedTitle = templateEngine.render(template.getTitle(), request.params());
            renderedBody = templateEngine.render(template.getBodyTemplate(), request.params());
        } catch (IllegalArgumentException e) {
            String errMsg = "模板渲染失败: " + e.getMessage();
            log.warn("[DingTalkSender] {}", errMsg);
            writeFailedLogPerRecipient(request, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }

        // 3. 构造请求 body
        List<String> atMobiles = extractAtMobiles(request.params());
        Map<String, Object> body = buildMessageBody(renderedTitle, renderedBody, atMobiles);

        String requestUrl;
        try {
            requestUrl = buildSignedUrl(webhookUrl, secret, System.currentTimeMillis());
        } catch (Exception e) {
            String errMsg = "钉钉签名计算失败: " + e.getMessage();
            log.error("[DingTalkSender] {}", errMsg, e);
            writeFailedLogPerRecipient(request, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }

        // 4. POST
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            String errMsg = "钉钉消息体 JSON 序列化失败: " + e.getMessage();
            log.error("[DingTalkSender] {}", errMsg, e);
            writeFailedLogPerRecipient(request, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }

        HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, entity, String.class);
            int statusCode = response.getStatusCode().value();
            String respBody = response.getBody();

            if (statusCode != 200) {
                String errMsg = "钉钉 HTTP 非 200: status=" + statusCode + ", body=" + truncate(respBody);
                log.warn("[DingTalkSender] {}", errMsg);
                writeFailedLogPerRecipient(request, errMsg);
                return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
            }

            // 钉钉响应 {"errcode":0,"errmsg":"ok"}
            Integer errcode = extractErrcode(respBody);
            if (errcode == null || errcode != 0) {
                String errMsg = "钉钉返回错误: " + truncate(respBody);
                log.warn("[DingTalkSender] {}", errMsg);
                writeFailedLogPerRecipient(request, errMsg);
                return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
            }

            // 成功: 对每个 recipient 写 SENT log. 群机器人是群级推送, 单条 webhook = 1 次 send;
            // recipient list 用于 audit 追踪"哪些 user 期望收到这条群消息".
            int sentCount = 0;
            if (recipients != null && !recipients.isEmpty()) {
                for (Long uid : recipients) {
                    writeLog(factoryId, templateCode, uid, NotifyStatus.SENT, null);
                    sentCount++;
                }
            } else {
                writeLog(factoryId, templateCode, null, NotifyStatus.SENT, null);
                sentCount = 1;
            }
            log.info("[DingTalkSender] 钉钉推送完成: factoryId={}, templateCode={}, recipientCount={}, sentCount={}",
                    factoryId, templateCode, recipients != null ? recipients.size() : 0, sentCount);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.SENT, null);

        } catch (NotifyAuditException e) {
            // audit 失败必 throw 让 Registry 处理, 不能 swallow
            throw e;
        } catch (Exception e) {
            String errMsg = "钉钉 webhook 调用异常: " + e.getMessage();
            log.error("[DingTalkSender] {}", errMsg, e);
            writeFailedLogPerRecipient(request, errMsg);
            return new NotifyResult(NotifyChannel.DINGTALK, NotifyStatus.FAILED, errMsg);
        }
    }

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.DINGTALK;
    }

    // ---------------- helpers (package-private 供测试) ----------------

    /**
     * 构造钉钉消息体. 暴露 package-private 供测试直接验证 shape.
     */
    Map<String, Object> buildMessageBody(String title, String body, List<String> atMobiles) {
        // LinkedHashMap 保留 insertion order — 便于测试稳定 + 钉钉响应也容易看
        Map<String, Object> payload = new LinkedHashMap<>();
        String type = (msgType == null || msgType.isBlank()) ? "text" : msgType.toLowerCase();

        if ("markdown".equals(type)) {
            payload.put("msgtype", "markdown");
            Map<String, String> markdown = new LinkedHashMap<>();
            markdown.put("title", title != null ? title : "");
            markdown.put("text", body != null ? body : "");
            payload.put("markdown", markdown);
        } else {
            // 默认 text — 钉钉 text 消息只有 content 一个字段, 我们把 title + body 用换行拼接
            payload.put("msgtype", "text");
            Map<String, String> textBlock = new HashMap<>();
            String content;
            if (title != null && !title.isBlank() && body != null && !body.isBlank()) {
                content = title + "\n" + body;
            } else if (title != null && !title.isBlank()) {
                content = title;
            } else {
                content = body != null ? body : "";
            }
            textBlock.put("content", content);
            payload.put("text", textBlock);
        }

        // @-mobiles block
        Map<String, Object> at = new LinkedHashMap<>();
        if (atMobiles != null && !atMobiles.isEmpty()) {
            at.put("atMobiles", atMobiles);
            at.put("isAtAll", false);
        } else {
            at.put("atMobiles", List.of());
            at.put("isAtAll", false);
        }
        payload.put("at", at);

        return payload;
    }

    /**
     * 加签 URL. timestamp 是毫秒 epoch.
     * URL shape: {@code <webhookUrl>&timestamp=<ts>&sign=<urlEncode(base64(HMACSHA256(secret, ts+"\n"+secret)))>}.
     *
     * <p>留空 secret 时回退到 unsigned (仅 access_token 校验) — 不推荐生产, 但兼容自托管/keyword
     * 验证机器人.
     */
    String buildSignedUrl(String webhookUrl, String secret, long timestamp) throws Exception {
        if (secret == null || secret.isBlank()) {
            return webhookUrl;
        }
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);

        String separator = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /**
     * 从 NotifyRequest.params 提取 @-mobiles. 兼容 List&lt;String&gt; / String[] / 单 String.
     * key = {@code atMobiles}.
     */
    @SuppressWarnings("unchecked")
    List<String> extractAtMobiles(Map<String, Object> params) {
        if (params == null) {
            return List.of();
        }
        Object raw = params.get("atMobiles");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List) {
            List<String> out = new java.util.ArrayList<>();
            for (Object o : (List<Object>) raw) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (raw instanceof String[]) {
            return List.of((String[]) raw);
        }
        if (raw instanceof String) {
            String s = (String) raw;
            return s.isBlank() ? List.of() : List.of(s);
        }
        return List.of();
    }

    /**
     * 从钉钉响应提取 errcode. 期望 shape: {@code {"errcode":0,"errmsg":"ok"}}.
     * 解析失败返 null (调用方视为 FAILED).
     */
    Integer extractErrcode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(responseBody, Map.class);
            Object code = parsed.get("errcode");
            if (code instanceof Number) {
                return ((Number) code).intValue();
            }
            if (code instanceof String) {
                return Integer.parseInt((String) code);
            }
            return null;
        } catch (Exception e) {
            log.debug("[DingTalkSender] errcode 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private void writeFailedLogPerRecipient(NotifyRequest request, String errMsg) {
        List<Long> recipients = request.recipientUserIds();
        if (recipients != null && !recipients.isEmpty()) {
            for (Long uid : recipients) {
                writeLog(request.factoryId(), request.templateCode(), uid, NotifyStatus.FAILED, errMsg);
            }
        } else {
            writeLog(request.factoryId(), request.templateCode(), null, NotifyStatus.FAILED, errMsg);
        }
    }

    /**
     * 写 audit log. 失败 → throw NotifyAuditException 不静默 swallow.
     *
     * <p><b>Phase 3 review High #2/#3 fix</b>: 原实现 {@code log.error + swallow} 会让 sender
     * 返 SENT 但 0 行 audit 落库. 现改为 throw, 由
     * {@link com.cretas.aims.service.notify.NotifySenderRegistry#sendAll} 在 channel
     * 边界捕获标该 channel FAILED.
     */
    private void writeLog(String factoryId, String templateCode, Long recipientUserId,
                          NotifyStatus status, String errorMsg) {
        NotifyLog logEntry = NotifyLog.builder()
                .factoryId(factoryId)
                .templateCode(templateCode)
                .recipientUserId(recipientUserId)
                .channel(NotifyChannel.DINGTALK)
                .status(status)
                .errorMsg(errorMsg)
                .sentAt(LocalDateTime.now())
                .build();
        try {
            logRepository.save(logEntry);
        } catch (Exception e) {
            DingTalkSender.log.error(
                    "[DingTalkSender] Failed to write NotifyLog: factoryId={}, channel={}, recipient={}",
                    factoryId, NotifyChannel.DINGTALK, recipientUserId, e);
            throw new NotifyAuditException(
                    "通知发送审计写入失败 — 请联系运维 (channel=DINGTALK, recipient="
                            + recipientUserId + ")", e);
        }
    }

    /** 安全截断 errMsg, 避免极长 response body 灌爆 NotifyLog.error_msg. */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= 500) {
            return s;
        }
        return s.substring(0, 500) + "...(truncated)";
    }
}
