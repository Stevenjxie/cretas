package com.cretas.aims.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信 WorkApp 推送客户端 — Phase 3 Canvas-Notify follow-up.
 *
 * <p>实现 {@link com.cretas.aims.service.notify.impl.WeChatSender} 的真实推送路径:
 * 调用企业微信 API {@code https://qyapi.weixin.qq.com/cgi-bin/message/send} 发送
 * text 消息. 自身管理 {@code access_token} 缓存 (TTL ≈ 7200s, per WeChat API doc),
 * sister Email subagent 实施 EmailNotifier — 两者解耦, dispatcher 路由由后续合并 PR 完成.
 *
 * <p><b>WeChat API reference</b>:
 * <a href="https://developer.work.weixin.qq.com/document/path/90236">应用消息</a>
 * +
 * <a href="https://developer.work.weixin.qq.com/document/path/91039">获取 access_token</a>
 *
 * <p><b>认证模型</b>: 企微 API 需用 {@code corpid + secret} 换 {@code access_token}, 然后
 * 把 token 作为 query 参数附在每个业务接口上. Token 7200s 有效, 接近过期 (此处用 100s safety
 * margin) 自动刷新. 高频场景需缓存 — 企微每个 secret 限额每天 ~2000 次 gettoken 调用.
 *
 * <p><b>凭证</b>: 通过 Spring placeholder {@code ${notify.wechat.corp-id}} / {@code ${notify.wechat.app-secret}} /
 * {@code ${notify.wechat.agent-id}} 读取 (默认空字符串 / 0). 任一为空 → {@link #send} 不实际
 * 发送, 抛 {@link WeChatNotConfiguredException}, 让上层 {@code WeChatSender} 写 FAILED log.
 *
 * <p><b>recipient 映射</b>: 当前 NotifyRequest.recipientUserIds 是内部 user id (Long). 企微
 * touser 字段需要的是企微 userid (string), 完整映射需在 {@code User} entity 加 {@code wechatUserId}
 * 字段. 本实现先用 Long.toString() 作为占位 — 实际部署前 sister chat 必须替换为真实 wechatUserId
 * 查询. {@code WeChatSender} 注入 {@link WeChatNotifier} 并自行解析, 避免 Notifier 依赖
 * UserRepository (保持 client 层单一职责).
 *
 * @since 2026-05-21 (Phase 3 follow-up — WeChat WorkApp impl)
 */
@Slf4j
@Service
public class WeChatNotifier {

    /** 企微 base URL. test 时通过 setter 覆盖指向 MockWebServer. */
    static final String DEFAULT_BASE_URL = "https://qyapi.weixin.qq.com";

    /** Access token safety margin — 提前 100s 视为过期, 避免 token 在请求中途失效. */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofSeconds(100);

    /** WeChat WorkApp msgtype=text payload — 简单文本消息, 适合所有通知模板. */
    private static final String MSG_TYPE_TEXT = "text";

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    @Value("${notify.wechat.corp-id:}")
    private String corpId;

    @Value("${notify.wechat.app-secret:}")
    private String appSecret;

    @Value("${notify.wechat.agent-id:0}")
    private Integer agentId;

    @Value("${notify.wechat.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${notify.wechat.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${notify.wechat.read-timeout-ms:10000}")
    private long readTimeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    /** Access token cache state. expiresAt < now() = 强制刷新. */
    private volatile String cachedAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    /**
     * Refresh lock — prevents concurrent gettoken calls from same JVM.
     * WeChat API throttles gettoken to ~2000/day per secret, so racy refresh
     * could exhaust budget under high-load fan-out (50 channels × 20 recipients).
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    public WeChatNotifier() {
        // Default constructor — used by Spring DI. Build OkHttpClient with config-driven
        // timeouts in @PostConstruct so @Value injection completes first.
        this.httpClient = null;
    }

    /** Test-only constructor — bypass Spring DI to inject MockWebServer URL + short timeouts. */
    public WeChatNotifier(String corpId, String appSecret, Integer agentId,
                          String baseUrl, OkHttpClient httpClient) {
        this.corpId = corpId;
        this.appSecret = appSecret;
        this.agentId = agentId;
        this.baseUrl = baseUrl;
        this.connectTimeoutMs = 2000;
        this.readTimeoutMs = 5000;
        this.httpClient = httpClient;
    }

    @PostConstruct
    void init() {
        // Mask secret in startup log — per .claude/rules/CREDENTIAL-MANAGEMENT.md
        // (never write raw secrets to log). corpId is not highly sensitive but agentId
        // is just a number so safe to log directly.
        boolean configured = isConfigured();
        log.info("[WeChatNotifier] init: configured={}, corpIdLen={}, agentId={}, baseUrl={}",
                configured,
                corpId != null ? corpId.length() : 0,
                agentId,
                baseUrl);
        if (!configured) {
            log.warn("[WeChatNotifier] WeChat WorkApp 凭证未配置 (notify.wechat.corp-id / "
                    + "app-secret / agent-id 任一为空) — 推送将抛 WeChatNotConfiguredException, "
                    + "上层 WeChatSender 会写 FAILED audit log. "
                    + "配置方法: .env.prod 加 WECHAT_CORP_ID / WECHAT_APP_SECRET / WECHAT_AGENT_ID");
        }
    }

    /** Whether all required credentials are present. */
    public boolean isConfigured() {
        return corpId != null && !corpId.isBlank()
                && appSecret != null && !appSecret.isBlank()
                && agentId != null && agentId > 0;
    }

    /**
     * Send a text message to a list of WeChat WorkApp userids.
     *
     * @param wechatUserIds recipients (企微 userid, NOT internal user id). Must be non-empty.
     *                      Joined with "|" per API spec, max 1000 recipients per call.
     * @param content        rendered message body (TemplateEngine output)
     * @return raw API response parsed as Map — caller may inspect {@code errcode}/{@code errmsg}
     *         for finer-grained audit logs, but {@link #send} already throws on non-zero errcode.
     * @throws WeChatNotConfiguredException creds missing — caller writes FAILED audit log
     * @throws IOException network / HTTP / parse failure — caller writes FAILED audit log
     */
    public Map<String, Object> send(List<String> wechatUserIds, String content) throws IOException {
        if (!isConfigured()) {
            throw new WeChatNotConfiguredException(
                    "企业微信凭证未配置 — 请设置 WECHAT_CORP_ID / WECHAT_APP_SECRET / WECHAT_AGENT_ID 环境变量");
        }
        if (wechatUserIds == null || wechatUserIds.isEmpty()) {
            throw new IllegalArgumentException("wechatUserIds 不能为空 (WeChat WorkApp 必须指定收件人)");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空 (WeChat text 消息要求非空 content)");
        }
        // API spec: max 1000 recipients per touser, "|" separated
        if (wechatUserIds.size() > 1000) {
            throw new IllegalArgumentException(
                    "wechatUserIds 数量超限 (最大 1000, 当前 " + wechatUserIds.size() + ") — "
                            + "请分批调用");
        }

        String token = getAccessToken();
        HttpUrl url = HttpUrl.parse(baseUrl + "/cgi-bin/message/send")
                .newBuilder()
                .addQueryParameter("access_token", token)
                .build();

        Map<String, Object> payload = buildTextMessagePayload(wechatUserIds, content);
        String body = objectMapper.writeValueAsString(payload);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON_MEDIA))
                .build();

        OkHttpClient client = effectiveHttpClient();
        try (Response resp = client.newCall(request).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "{}";
            if (!resp.isSuccessful()) {
                throw new IOException(
                        "企业微信 message/send HTTP " + resp.code() + ": " + truncate(respBody, 512));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(respBody, Map.class);
            Number errcode = (Number) parsed.getOrDefault("errcode", 0);
            if (errcode.intValue() != 0) {
                // 42001 = access_token expired — invalidate cache for next call
                if (errcode.intValue() == 42001 || errcode.intValue() == 40014) {
                    log.warn("[WeChatNotifier] access_token 过期 (errcode={}) — 清缓存", errcode);
                    invalidateAccessToken();
                }
                throw new IOException(
                        "企业微信 message/send errcode=" + errcode + " errmsg="
                                + parsed.getOrDefault("errmsg", ""));
            }
            log.info("[WeChatNotifier] message sent: recipients={}, agentId={}, msgid={}",
                    wechatUserIds.size(), agentId, parsed.get("msgid"));
            return parsed;
        }
    }

    /**
     * Get a valid access_token — from cache if not expired, else refresh via gettoken API.
     *
     * <p>Thread-safe via {@code refreshLock}. Race-condition pattern: concurrent
     * fan-out (50 recipients × WeChat channel) would otherwise hit gettoken 50×
     * before cache populates. Lock serializes refresh; subsequent threads see
     * cached token after first one returns.
     *
     * @return current valid access_token
     * @throws IOException network / HTTP / parse failure on gettoken
     */
    String getAccessToken() throws IOException {
        // Fast path: cached token not yet expired (with safety margin)
        if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_SAFETY_MARGIN))) {
            return cachedAccessToken;
        }
        // Slow path: acquire lock, double-check, refresh if needed
        refreshLock.lock();
        try {
            if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_SAFETY_MARGIN))) {
                return cachedAccessToken;
            }
            return refreshAccessToken();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Force token refresh on next call — invoked after 42001/40014 errcode. */
    void invalidateAccessToken() {
        refreshLock.lock();
        try {
            cachedAccessToken = null;
            tokenExpiresAt = Instant.EPOCH;
        } finally {
            refreshLock.unlock();
        }
    }

    /** Caller MUST hold {@code refreshLock}. */
    private String refreshAccessToken() throws IOException {
        HttpUrl url = HttpUrl.parse(baseUrl + "/cgi-bin/gettoken")
                .newBuilder()
                .addQueryParameter("corpid", corpId)
                .addQueryParameter("corpsecret", appSecret)
                .build();

        Request request = new Request.Builder().url(url).get().build();
        OkHttpClient client = effectiveHttpClient();
        try (Response resp = client.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "{}";
            if (!resp.isSuccessful()) {
                throw new IOException("企业微信 gettoken HTTP " + resp.code() + ": " + truncate(body, 512));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Number errcode = (Number) parsed.getOrDefault("errcode", 0);
            if (errcode.intValue() != 0) {
                throw new IOException(
                        "企业微信 gettoken errcode=" + errcode + " errmsg=" + parsed.getOrDefault("errmsg", ""));
            }
            String token = (String) parsed.get("access_token");
            Number expiresIn = (Number) parsed.getOrDefault("expires_in", 7200);
            if (token == null || token.isBlank()) {
                throw new IOException("企业微信 gettoken 返回空 access_token: " + truncate(body, 256));
            }
            cachedAccessToken = token;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn.longValue());
            log.info("[WeChatNotifier] access_token refreshed, expires_in={}s", expiresIn);
            return token;
        }
    }

    /** Pick test-injected client over the lazily-built default. */
    private OkHttpClient effectiveHttpClient() {
        if (httpClient != null) {
            return httpClient;
        }
        // Lazy-build using @Value-injected timeouts. Synchronized via class-level
        // double-checked via the volatile lazyClient field if needed; here we
        // accept transient duplicate builds at startup (idempotent + cheap).
        return DefaultClientHolder.get(connectTimeoutMs, readTimeoutMs);
    }

    /**
     * Build WeChat WorkApp text message JSON payload.
     *
     * <p>Spec: {@code {"touser":"a|b", "msgtype":"text", "agentid":1, "text":{"content":"..."}}}.
     * Returns LinkedHashMap so the JSON serialization order matches the spec example
     * — easier for ops to diff network captures.
     */
    Map<String, Object> buildTextMessagePayload(List<String> wechatUserIds, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", String.join("|", wechatUserIds));
        payload.put("msgtype", MSG_TYPE_TEXT);
        payload.put("agentid", agentId);
        Map<String, Object> text = new HashMap<>();
        text.put("content", content);
        payload.put("text", text);
        return payload;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }

    /**
     * Holder for the lazy default OkHttpClient instance.
     *
     * <p>Static inner class so the client is built once per (connectTimeoutMs, readTimeoutMs)
     * combination on first {@link #send} call. Multiple distinct configs are unusual but
     * supported (e.g. test profile overrides).
     */
    private static final class DefaultClientHolder {
        private static volatile OkHttpClient instance;
        private static volatile long connectMs;
        private static volatile long readMs;

        static OkHttpClient get(long connectTimeoutMs, long readTimeoutMs) {
            OkHttpClient current = instance;
            if (current != null && connectMs == connectTimeoutMs && readMs == readTimeoutMs) {
                return current;
            }
            synchronized (DefaultClientHolder.class) {
                if (instance == null || connectMs != connectTimeoutMs || readMs != readTimeoutMs) {
                    instance = new OkHttpClient.Builder()
                            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                            .build();
                    connectMs = connectTimeoutMs;
                    readMs = readTimeoutMs;
                }
                return instance;
            }
        }
    }

    /**
     * Thrown when WeChat WorkApp credentials are missing.
     *
     * <p>{@link com.cretas.aims.service.notify.impl.WeChatSender} catches this
     * and writes a FAILED NotifyLog with a clear error message — same pattern
     * as Email sister subagent's EmailNotConfiguredException.
     */
    public static class WeChatNotConfiguredException extends RuntimeException {
        public WeChatNotConfiguredException(String message) {
            super(message);
        }
    }
}
