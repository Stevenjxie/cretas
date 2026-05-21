package com.cretas.aims.service.notify;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WeChatNotifier} — Phase 3 Canvas-Notify follow-up.
 *
 * <p>Uses {@link MockWebServer} (square okhttp3 test dep, already in pom)
 * to assert HTTP request shape + access_token cache behavior. No real WeChat
 * API contact.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-WN-01: {@link WeChatNotifier#isConfigured} reflects credential presence</li>
 *   <li>UT-WN-02: send() unconfigured → throws WeChatNotConfiguredException</li>
 *   <li>UT-WN-03: send() empty wechatUserIds → IllegalArgumentException</li>
 *   <li>UT-WN-04: send() empty content → IllegalArgumentException</li>
 *   <li>UT-WN-05: send() >1000 recipients → IllegalArgumentException</li>
 *   <li>UT-WN-06: happy path — gettoken once, message/send POST with correct payload</li>
 *   <li>UT-WN-07: access_token cached across 2 sends (single gettoken call)</li>
 *   <li>UT-WN-08: 42001 expired token → invalidate cache + throw IOException</li>
 *   <li>UT-WN-09: gettoken errcode!=0 → IOException</li>
 *   <li>UT-WN-10: message/send errcode!=0 (non-token) → IOException</li>
 *   <li>UT-WN-11: HTTP 500 on send → IOException with body</li>
 *   <li>UT-WN-12: buildTextMessagePayload produces correct JSON shape</li>
 *   <li>UT-WN-13: empty access_token from API → IOException</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 follow-up)
 */
class WeChatNotifierTest {

    private static final String CORP_ID = "ww-test-corp";
    private static final String APP_SECRET = "secret-xyz";
    private static final Integer AGENT_ID = 1000123;

    private MockWebServer server;
    private OkHttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Short timeouts so test fails fast on misconfig
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    private WeChatNotifier newNotifier(String corpId, String appSecret, Integer agentId) {
        String baseUrl = server.url("/").toString();
        // strip trailing slash to match prod base URL shape
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return new WeChatNotifier(corpId, appSecret, agentId, baseUrl, httpClient);
    }

    private WeChatNotifier configured() {
        return newNotifier(CORP_ID, APP_SECRET, AGENT_ID);
    }

    private static String tokenResp(String token, int expiresIn) {
        return "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"" + token
                + "\",\"expires_in\":" + expiresIn + "}";
    }

    private static String sendOkResp() {
        return "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"msg-12345\"}";
    }

    @Test
    @DisplayName("UT-WN-01: isConfigured reflects credential presence")
    void isConfiguredTrueWhenAllSet() {
        assertTrue(configured().isConfigured());

        assertTrue(!newNotifier("", APP_SECRET, AGENT_ID).isConfigured(), "empty corpId → not configured");
        assertTrue(!newNotifier(CORP_ID, "", AGENT_ID).isConfigured(), "empty secret → not configured");
        assertTrue(!newNotifier(CORP_ID, APP_SECRET, 0).isConfigured(), "agentId=0 → not configured");
        assertTrue(!newNotifier(CORP_ID, APP_SECRET, null).isConfigured(), "null agentId → not configured");
        assertTrue(!newNotifier(null, APP_SECRET, AGENT_ID).isConfigured(), "null corpId → not configured");
        assertTrue(!newNotifier("  ", APP_SECRET, AGENT_ID).isConfigured(), "blank corpId → not configured");
    }

    @Test
    @DisplayName("UT-WN-02: send() unconfigured throws WeChatNotConfiguredException")
    void sendUnconfiguredThrows() {
        WeChatNotifier notifier = newNotifier("", "", 0);
        assertThrows(WeChatNotifier.WeChatNotConfiguredException.class,
                () -> notifier.send(List.of("user-a"), "hello"));
        // server should not have received any request
        assertEquals(0, server.getRequestCount());
    }

    @Test
    @DisplayName("UT-WN-03: send() with empty recipients throws IllegalArgumentException")
    void sendEmptyRecipientsThrows() {
        WeChatNotifier notifier = configured();
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(List.of(), "hello"));
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(null, "hello"));
    }

    @Test
    @DisplayName("UT-WN-04: send() with blank content throws IllegalArgumentException")
    void sendBlankContentThrows() {
        WeChatNotifier notifier = configured();
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(List.of("user-a"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(List.of("user-a"), "   "));
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(List.of("user-a"), null));
    }

    @Test
    @DisplayName("UT-WN-05: send() >1000 recipients throws IllegalArgumentException")
    void sendTooManyRecipientsThrows() {
        WeChatNotifier notifier = configured();
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) ids.add("u" + i);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> notifier.send(ids, "hi"));
        assertTrue(ex.getMessage().contains("1000"), "应提示 1000 上限, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("UT-WN-06: happy path — gettoken then message/send with correct payload")
    void happyPathSingleSend() throws Exception {
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-A", 7200)));
        server.enqueue(new MockResponse().setBody(sendOkResp()));

        WeChatNotifier notifier = configured();
        Map<String, Object> resp = notifier.send(List.of("alice", "bob"), "您有 1 笔待审单");

        assertEquals("msg-12345", resp.get("msgid"));

        // Verify gettoken request
        RecordedRequest tokenReq = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(tokenReq);
        assertEquals("GET", tokenReq.getMethod());
        assertTrue(tokenReq.getPath().startsWith("/cgi-bin/gettoken"),
                "should call gettoken, got: " + tokenReq.getPath());
        assertTrue(tokenReq.getPath().contains("corpid=" + CORP_ID),
                "should include corpid");
        assertTrue(tokenReq.getPath().contains("corpsecret=" + APP_SECRET),
                "should include corpsecret");

        // Verify message/send request
        RecordedRequest sendReq = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(sendReq);
        assertEquals("POST", sendReq.getMethod());
        assertTrue(sendReq.getPath().startsWith("/cgi-bin/message/send"),
                "should call message/send, got: " + sendReq.getPath());
        assertTrue(sendReq.getPath().contains("access_token=TOKEN-A"),
                "should include access_token in query");
        String body = sendReq.getBody().readUtf8();
        assertTrue(body.contains("\"touser\":\"alice|bob\""),
                "touser should be pipe-joined, got: " + body);
        assertTrue(body.contains("\"msgtype\":\"text\""), "should be text msgtype");
        assertTrue(body.contains("\"agentid\":" + AGENT_ID), "should include agentid");
        assertTrue(body.contains("您有 1 笔待审单"), "should include content");
    }

    @Test
    @DisplayName("UT-WN-07: access_token cached across two sends (single gettoken call)")
    void accessTokenCachedAcrossSends() throws Exception {
        // Only ONE gettoken response — if cache fails, second send will hang/fail
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-CACHED", 7200)));
        server.enqueue(new MockResponse().setBody(sendOkResp()));
        server.enqueue(new MockResponse().setBody(sendOkResp()));

        WeChatNotifier notifier = configured();
        notifier.send(List.of("u1"), "first");
        notifier.send(List.of("u2"), "second");

        // Exactly 3 requests total: 1 gettoken + 2 message/send
        assertEquals(3, server.getRequestCount());

        // First = gettoken
        RecordedRequest r1 = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(r1.getPath().startsWith("/cgi-bin/gettoken"), "first req should be gettoken");

        // Then 2x message/send — both should use TOKEN-CACHED
        RecordedRequest r2 = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest r3 = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(r2.getPath().contains("access_token=TOKEN-CACHED"));
        assertTrue(r3.getPath().contains("access_token=TOKEN-CACHED"));
    }

    @Test
    @DisplayName("UT-WN-08: 42001 errcode invalidates cache + IOException")
    void expiredTokenInvalidatesCache() throws Exception {
        // 1st gettoken returns TOKEN-OLD, message/send returns 42001
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-OLD", 7200)));
        server.enqueue(new MockResponse().setBody(
                "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}"));
        // 2nd gettoken would return TOKEN-NEW
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-NEW", 7200)));
        server.enqueue(new MockResponse().setBody(sendOkResp()));

        WeChatNotifier notifier = configured();
        IOException ex = assertThrows(IOException.class,
                () -> notifier.send(List.of("u1"), "msg"));
        assertTrue(ex.getMessage().contains("42001"), "error should include errcode 42001");

        // After invalidation, next send refreshes token
        Map<String, Object> ok = notifier.send(List.of("u1"), "retry");
        assertEquals("msg-12345", ok.get("msgid"));

        // Verify second send used TOKEN-NEW (not the invalidated TOKEN-OLD)
        // requests: gettoken-1, send-fail-42001, gettoken-2, send-ok
        server.takeRequest(1, TimeUnit.SECONDS); // gettoken-1
        server.takeRequest(1, TimeUnit.SECONDS); // send-fail
        server.takeRequest(1, TimeUnit.SECONDS); // gettoken-2
        RecordedRequest send2 = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(send2.getPath().contains("access_token=TOKEN-NEW"),
                "retry should use new token, got: " + send2.getPath());
    }

    @Test
    @DisplayName("UT-WN-09: gettoken errcode!=0 throws IOException")
    void gettokenErrcodeThrows() {
        server.enqueue(new MockResponse().setBody(
                "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"));

        WeChatNotifier notifier = configured();
        IOException ex = assertThrows(IOException.class,
                () -> notifier.send(List.of("u1"), "hi"));
        assertTrue(ex.getMessage().contains("40001"),
                "should surface gettoken errcode, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid credential"),
                "should surface errmsg, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("UT-WN-10: message/send non-token errcode throws IOException")
    void sendErrcodeThrows() {
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-X", 7200)));
        server.enqueue(new MockResponse().setBody(
                "{\"errcode\":81013,\"errmsg\":\"recipient not found in agent\"}"));

        WeChatNotifier notifier = configured();
        IOException ex = assertThrows(IOException.class,
                () -> notifier.send(List.of("ghost-user"), "hi"));
        assertTrue(ex.getMessage().contains("81013"),
                "should surface message/send errcode, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("UT-WN-11: HTTP 500 response throws IOException with body excerpt")
    void httpFailureThrows() {
        server.enqueue(new MockResponse().setBody(tokenResp("TOKEN-X", 7200)));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal-server-error"));

        WeChatNotifier notifier = configured();
        IOException ex = assertThrows(IOException.class,
                () -> notifier.send(List.of("u1"), "hi"));
        assertTrue(ex.getMessage().contains("500"),
                "should surface HTTP 500, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("UT-WN-12: buildTextMessagePayload produces correct JSON shape")
    void buildTextMessagePayloadShape() {
        WeChatNotifier notifier = configured();
        Map<String, Object> payload = notifier.buildTextMessagePayload(
                List.of("alice", "bob", "charlie"), "hello world");

        assertEquals("alice|bob|charlie", payload.get("touser"));
        assertEquals("text", payload.get("msgtype"));
        assertEquals(AGENT_ID, payload.get("agentid"));

        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) payload.get("text");
        assertNotNull(text);
        assertEquals("hello world", text.get("content"));
    }

    @Test
    @DisplayName("UT-WN-13: empty access_token from API throws IOException")
    void emptyAccessTokenThrows() {
        // API returns errcode 0 but empty access_token — defensive guard
        server.enqueue(new MockResponse().setBody(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"\",\"expires_in\":7200}"));

        WeChatNotifier notifier = configured();
        IOException ex = assertThrows(IOException.class,
                () -> notifier.send(List.of("u1"), "hi"));
        assertTrue(ex.getMessage().contains("access_token"),
                "should mention empty access_token, got: " + ex.getMessage());
    }
}
