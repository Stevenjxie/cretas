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
import com.cretas.aims.service.notify.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DingTalkSender} — Phase 3 Canvas-Notify Step T4 real impl.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-DT-01: supports(DINGTALK) only</li>
 *   <li>UT-DT-02: 签名生成正确 (HMAC-SHA256 + base64 + urlencode + timestamp/sign query)</li>
 *   <li>UT-DT-03: 签名为空 → 直接返 webhookUrl 不加 sign</li>
 *   <li>UT-DT-04: text body shape — msgtype=text, content=title+\n+body, at.atMobiles=[], isAtAll=false</li>
 *   <li>UT-DT-05: markdown body shape — msgtype=markdown, title/text 分开</li>
 *   <li>UT-DT-06: atMobiles 从 params 提取 — List / String / String[] 三态兼容</li>
 *   <li>UT-DT-07: extractErrcode — 0/非0/缺失/非数字</li>
 *   <li>UT-DT-08: webhook 未配置 → FAILED + 写 FAILED log per recipient + 不调 HTTP</li>
 *   <li>UT-DT-09: happy path — 模板存在 + 钉钉返 errcode=0 → SENT + 写 SENT log per recipient</li>
 *   <li>UT-DT-10: 模板不存在 → FAILED</li>
 *   <li>UT-DT-11: 钉钉返 errcode != 0 → FAILED + 写 FAILED log</li>
 *   <li>UT-DT-12: HTTP 非 200 → FAILED + 写 FAILED log</li>
 *   <li>UT-DT-13: RestTemplate 抛 RestClientException → FAILED + 写 FAILED log (不 swallow)</li>
 *   <li>UT-DT-14: logRepository.save 抛异常 → NotifyAuditException (review High #2/#3)</li>
 *   <li>UT-DT-15: render 抛 IAE (缺变量) → FAILED + 每 recipient 写 FAILED log</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 real impl)
 */
@ExtendWith(MockitoExtension.class)
class DingTalkSenderTest {

    private static final String FACTORY_ID = "F001";
    private static final String TEMPLATE_CODE = "PO_APPROVAL_PENDING";
    private static final String WEBHOOK_URL = "https://oapi.dingtalk.com/robot/send?access_token=test_token_123";
    private static final String SECRET = "SEC123abcXYZ";

    @Mock
    private NotifyTemplateRepository templateRepository;

    @Mock
    private NotifyLogRepository logRepository;

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private TemplateEngine templateEngine = new TemplateEngine();

    @InjectMocks
    private DingTalkSender sender;

    @BeforeEach
    void setUp() {
        // 注入配置 (不真触发 @Value, 因为非 Spring 上下文)
        ReflectionTestUtils.setField(sender, "webhookUrl", WEBHOOK_URL);
        ReflectionTestUtils.setField(sender, "secret", SECRET);
        ReflectionTestUtils.setField(sender, "msgType", "text");
        ReflectionTestUtils.setField(sender, "connectTimeoutMs", 5000);
        ReflectionTestUtils.setField(sender, "readTimeoutMs", 10000);
        sender.setRestTemplate(restTemplate);
    }

    @Test
    @DisplayName("UT-DT-01: supports(DINGTALK) only")
    void supportsDingTalkOnly() {
        assertTrue(sender.supports(NotifyChannel.DINGTALK));
        for (NotifyChannel ch : NotifyChannel.values()) {
            if (ch != NotifyChannel.DINGTALK) {
                assertEquals(false, sender.supports(ch), "should not support " + ch);
            }
        }
    }

    @Test
    @DisplayName("UT-DT-02: 签名生成正确 — HMAC-SHA256 + base64 + urlencode")
    void signedUrlMatchesOfficialAlgo() throws Exception {
        long ts = 1700000000000L;
        String signedUrl = sender.buildSignedUrl(WEBHOOK_URL, SECRET, ts);

        // 1. URL 含 timestamp + sign 参数
        assertTrue(signedUrl.contains("&timestamp=" + ts), "missing timestamp param");
        assertTrue(signedUrl.contains("&sign="), "missing sign param");

        // 2. 重新计算 expected sign, 比对
        String stringToSign = ts + "\n" + SECRET;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sigBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String expectedSign = Base64.getEncoder().encodeToString(sigBytes);

        // 3. 从 URL 取出 sign param, urldecode, 比对
        int signIdx = signedUrl.indexOf("&sign=") + "&sign=".length();
        String actualSignUrlEncoded = signedUrl.substring(signIdx);
        String actualSign = URLDecoder.decode(actualSignUrlEncoded, StandardCharsets.UTF_8);
        assertEquals(expectedSign, actualSign, "signature mismatch — algo divergence");

        // 4. base URL prefix 保留
        assertTrue(signedUrl.startsWith(WEBHOOK_URL), "base URL should prefix signed URL");
    }

    @Test
    @DisplayName("UT-DT-03: 签名为空 → 不附加 timestamp/sign")
    void noSignWhenSecretBlank() throws Exception {
        String url1 = sender.buildSignedUrl(WEBHOOK_URL, null, 1700000000000L);
        String url2 = sender.buildSignedUrl(WEBHOOK_URL, "", 1700000000000L);
        String url3 = sender.buildSignedUrl(WEBHOOK_URL, "   ", 1700000000000L);
        assertEquals(WEBHOOK_URL, url1);
        assertEquals(WEBHOOK_URL, url2);
        assertEquals(WEBHOOK_URL, url3);
    }

    @Test
    @DisplayName("UT-DT-04: text body shape — msgtype=text, content=title+\\n+body")
    void textBodyShape() {
        Map<String, Object> body = sender.buildMessageBody("您有 2 笔待审单", "请审核 PO-001", List.of());
        assertEquals("text", body.get("msgtype"));
        assertNotNull(body.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, String> textBlock = (Map<String, String>) body.get("text");
        assertEquals("您有 2 笔待审单\n请审核 PO-001", textBlock.get("content"));
        // at block
        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) body.get("at");
        assertEquals(List.of(), at.get("atMobiles"));
        assertEquals(false, at.get("isAtAll"));
    }

    @Test
    @DisplayName("UT-DT-04b: text body — title 为空时只用 body 不前置换行")
    void textBodyTitleBlank() {
        Map<String, Object> body = sender.buildMessageBody("", "仅 body", List.of());
        @SuppressWarnings("unchecked")
        Map<String, String> textBlock = (Map<String, String>) body.get("text");
        assertEquals("仅 body", textBlock.get("content"));
    }

    @Test
    @DisplayName("UT-DT-05: markdown body shape — msgtype=markdown, title/text 分开")
    void markdownBodyShape() {
        ReflectionTestUtils.setField(sender, "msgType", "markdown");
        Map<String, Object> body = sender.buildMessageBody("标题", "**正文**", List.of("13800138000"));
        assertEquals("markdown", body.get("msgtype"));
        @SuppressWarnings("unchecked")
        Map<String, String> md = (Map<String, String>) body.get("markdown");
        assertEquals("标题", md.get("title"));
        assertEquals("**正文**", md.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) body.get("at");
        assertEquals(List.of("13800138000"), at.get("atMobiles"));
    }

    @Test
    @DisplayName("UT-DT-06: atMobiles 从 params 提取 — List / String / String[]")
    void extractAtMobilesAllShapes() {
        // 1. List
        assertEquals(List.of("138", "139"),
                sender.extractAtMobiles(Map.of("atMobiles", List.of("138", "139"))));
        // 2. 单 String
        assertEquals(List.of("138"),
                sender.extractAtMobiles(Map.of("atMobiles", "138")));
        // 3. 空 String
        assertEquals(List.of(),
                sender.extractAtMobiles(Map.of("atMobiles", "")));
        // 4. String[]
        assertEquals(List.of("138", "139"),
                sender.extractAtMobiles(Map.of("atMobiles", new String[]{"138", "139"})));
        // 5. null params
        assertEquals(List.of(), sender.extractAtMobiles(null));
        // 6. 无 key
        assertEquals(List.of(), sender.extractAtMobiles(Map.of("other", "x")));
        // 7. 类型不支持
        assertEquals(List.of(), sender.extractAtMobiles(Map.of("atMobiles", 123)));
    }

    @Test
    @DisplayName("UT-DT-07: extractErrcode — 0/非0/缺失/非数字")
    void extractErrcodeAllShapes() {
        assertEquals(0, sender.extractErrcode("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        assertEquals(310000, sender.extractErrcode("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"));
        // 字符串数字也支持
        assertEquals(0, sender.extractErrcode("{\"errcode\":\"0\"}"));
        // 缺 errcode
        assertNull(sender.extractErrcode("{\"errmsg\":\"???\"}"));
        // 空 body
        assertNull(sender.extractErrcode(""));
        assertNull(sender.extractErrcode(null));
        // 非 JSON
        assertNull(sender.extractErrcode("not json"));
    }

    @Test
    @DisplayName("UT-DT-08: webhook 未配置 → FAILED + 写 FAILED log + 不调 HTTP")
    void webhookNotConfigured() {
        ReflectionTestUtils.setField(sender, "webhookUrl", "");
        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of("count", 1));

        NotifyResult result = sender.send(req);

        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("钉钉 webhook 未配置"));
        // 每 recipient 一条 FAILED log
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED).count();
        assertEquals(2, failedCount, "应有 2 条 FAILED log");
        // 不调 HTTP
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("UT-DT-09: happy path — 模板存在 + errcode=0 → SENT + per-recipient SENT log + HTTP body 正确")
    void happyPath() throws Exception {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔待审单")
                .bodyTemplate("请审核 {{poNumber}}, 金额 {{amount}} 元")
                .channels(List.of(NotifyChannel.DINGTALK))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        // 钉钉返成功
        ResponseEntity<String> okResp = new ResponseEntity<>(
                "{\"errcode\":0,\"errmsg\":\"ok\"}", HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(okResp);

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L, 1003L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE,
                Map.of("count", 2, "poNumber", "PO-001", "amount", 5000,
                        "atMobiles", List.of("13800138000")));

        NotifyResult result = sender.send(req);

        assertEquals(NotifyStatus.SENT, result.status());
        assertEquals(null, result.errorMsg());

        // 验证 HTTP body shape — 用 ArgumentCaptor 拿到实际 POST 的 entity
        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), entityCaptor.capture(), eq(String.class));

        // URL 含签名
        String calledUrl = urlCaptor.getValue();
        assertTrue(calledUrl.contains("&timestamp="), "should be signed URL");
        assertTrue(calledUrl.contains("&sign="), "should be signed URL");

        // body JSON 含渲染后的 title + body + atMobiles
        String body = entityCaptor.getValue().getBody();
        assertNotNull(body);
        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(body, Map.class);
        assertEquals("text", parsed.get("msgtype"));
        @SuppressWarnings("unchecked")
        Map<String, String> textBlock = (Map<String, String>) parsed.get("text");
        assertTrue(textBlock.get("content").contains("您有 2 笔待审单"),
                "渲染后 title 应在 content 中, got: " + textBlock.get("content"));
        assertTrue(textBlock.get("content").contains("PO-001"),
                "渲染后 body 应在 content 中");
        assertTrue(textBlock.get("content").contains("5000"));

        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) parsed.get("at");
        assertEquals(List.of("13800138000"), at.get("atMobiles"));
        assertEquals(false, at.get("isAtAll"));

        // 每 recipient 一条 SENT log
        ArgumentCaptor<NotifyLog> logCaptor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        long sentCount = logCaptor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.SENT)
                .filter(l -> l.getChannel() == NotifyChannel.DINGTALK)
                .filter(l -> FACTORY_ID.equals(l.getFactoryId()))
                .count();
        assertEquals(3, sentCount, "应有 3 条 SENT log per recipient");
    }

    @Test
    @DisplayName("UT-DT-10: 模板不存在 → FAILED")
    void templateNotFound() {
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.empty());

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of());

        NotifyResult result = sender.send(req);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().contains(TEMPLATE_CODE));
        // 不调 HTTP
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("UT-DT-11: 钉钉返 errcode != 0 → FAILED + 保留 errmsg + 写 FAILED log")
    void errcodeNonZero() {
        stubTemplate();
        ResponseEntity<String> errResp = new ResponseEntity<>(
                "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}", HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenReturn(errResp);

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-001", "amount", 100));

        NotifyResult result = sender.send(req);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("keywords not in content"));
        // 写 FAILED log
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(l -> l.getStatus() == NotifyStatus.FAILED && l.getChannel() == NotifyChannel.DINGTALK));
    }

    @Test
    @DisplayName("UT-DT-12: HTTP 非 200 → FAILED")
    void httpNon200() {
        stubTemplate();
        ResponseEntity<String> badResp = new ResponseEntity<>("forbidden", HttpStatus.FORBIDDEN);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenReturn(badResp);

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-001", "amount", 100));

        NotifyResult result = sender.send(req);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("403") || result.errorMsg().contains("status=403"),
                "errMsg 应含 status, got: " + result.errorMsg());

        // 每 recipient 一条 FAILED log
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED).count();
        assertEquals(2, failedCount);
    }

    @Test
    @DisplayName("UT-DT-13: RestTemplate 抛 RestClientException → FAILED (不 swallow)")
    void restTemplateThrows() {
        stubTemplate();
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("connection refused"));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-001", "amount", 100));

        NotifyResult result = sender.send(req);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("connection refused")
                        || result.errorMsg().contains("webhook 调用异常"),
                "errMsg got: " + result.errorMsg());
    }

    @Test
    @DisplayName("UT-DT-14: logRepository.save 抛异常 → NotifyAuditException (review High #2/#3)")
    void auditWriteFailureThrowsNotifyAuditException() {
        stubTemplate();
        ResponseEntity<String> okResp = new ResponseEntity<>(
                "{\"errcode\":0,\"errmsg\":\"ok\"}", HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class))).thenReturn(okResp);
        // DB save 抛异常 (e.g. connection refused)
        when(logRepository.save(any(NotifyLog.class)))
                .thenThrow(new RuntimeException("connection refused"));

        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L), List.of(NotifyChannel.DINGTALK),
                TEMPLATE_CODE, Map.of("count", 1, "poNumber", "PO-001", "amount", 100));

        NotifyAuditException ex = assertThrows(NotifyAuditException.class, () -> sender.send(req));
        assertTrue(ex.getMessage().contains("请联系运维"),
                "errMsg 应含 next-action 提示, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("DINGTALK"),
                "errMsg 应含 channel 信息, got: " + ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("connection refused", ex.getCause().getMessage());
    }

    @Test
    @DisplayName("UT-DT-15: render 抛 IAE (缺变量) → FAILED + 每 recipient 写 FAILED log + 不调 HTTP")
    void renderFailsMissingVar() {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔")
                .bodyTemplate("总额 {{amount}}")
                .channels(List.of(NotifyChannel.DINGTALK))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));

        // params 缺 amount
        NotifyRequest req = new NotifyRequest(
                FACTORY_ID, List.of(1001L, 1002L),
                List.of(NotifyChannel.DINGTALK), TEMPLATE_CODE,
                Map.of("count", 5));

        NotifyResult result = sender.send(req);
        assertEquals(NotifyStatus.FAILED, result.status());
        assertTrue(result.errorMsg().contains("模板渲染失败"));

        // 不调 HTTP (在 render 阶段失败)
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));

        // 每 recipient 一条 FAILED log
        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        long failedCount = captor.getAllValues().stream()
                .filter(l -> l.getStatus() == NotifyStatus.FAILED).count();
        assertEquals(2, failedCount, "应有 2 条 FAILED log per recipient");
    }

    // helper
    private void stubTemplate() {
        NotifyTemplate template = NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId(FACTORY_ID)
                .templateCode(TEMPLATE_CODE)
                .title("您有 {{count}} 笔待审单")
                .bodyTemplate("请审核 {{poNumber}}, 金额 {{amount}} 元")
                .channels(List.of(NotifyChannel.DINGTALK))
                .build();
        when(templateRepository.findByFactoryIdAndTemplateCode(FACTORY_ID, TEMPLATE_CODE))
                .thenReturn(Optional.of(template));
    }
}
