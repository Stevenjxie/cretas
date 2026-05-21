package com.cretas.aims.service.notify;

import com.cretas.aims.entity.notify.NotifyTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AliyunSmsNotifier} — Phase 3 Canvas-Notify SMS gateway.
 *
 * <p>Covers:
 * <ul>
 *   <li>UT-SMS-01: signature generation deterministic + reproducible (HMAC-SHA1)</li>
 *   <li>UT-SMS-02: POP percent-encoding edge cases ({@code +}, {@code *}, {@code ~})</li>
 *   <li>UT-SMS-03: happy path — Aliyun returns OK → SmsResult.success=true + bizId</li>
 *   <li>UT-SMS-04: Aliyun returns non-OK code → SmsResult.success=false + code/message preserved</li>
 *   <li>UT-SMS-05: HTTP 500 → SmsResult.failed("HTTP_500", body snippet)</li>
 *   <li>UT-SMS-06: 网络 IOException → SmsResult.failed("EXCEPTION", ...)</li>
 *   <li>UT-SMS-07: empty recipientPhones → empty list, no API call</li>
 *   <li>UT-SMS-08: missing config → IllegalStateException 清晰提示</li>
 *   <li>UT-SMS-09: template/templateCode null → IllegalArgumentException</li>
 *   <li>UT-SMS-10: 多 recipient → 多次 API call, per-recipient 独立结果</li>
 *   <li>UT-SMS-11: empty phone in list → SmsResult.failed("EMPTY_PHONE")</li>
 *   <li>UT-SMS-12: POST body contains required fields (Action/Version/Signature/PhoneNumbers/...)</li>
 *   <li>UT-SMS-13: maskPhone helper</li>
 *   <li>UT-SMS-14: isConfigured reflects state</li>
 * </ul>
 *
 * @since 2026-05-21 (Phase 3 SMS gateway tests)
 */
@ExtendWith(MockitoExtension.class)
class AliyunSmsNotifierTest {

    private static final String AK = "LTAI5tTestKey";
    private static final String SK = "TestSecretKey1234567";
    private static final String SIGN = "Cretas";
    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final String REGION = "cn-hangzhou";

    private static final String TEMPLATE_CODE = "SMS_123456789";

    private NotifyTemplate newTemplate(String templateCode) {
        return NotifyTemplate.builder()
                .id(UUID.randomUUID())
                .factoryId("F001")
                .templateCode(templateCode)
                .title("您有 {{count}} 笔单")
                .bodyTemplate("待审 {{poNumber}}")
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return (HttpResponse<String>) resp;
    }

    @Test
    @DisplayName("UT-SMS-01: HMAC-SHA1 signing deterministic for known input")
    void signatureDeterministic() {
        // 同 input 两次签名应一致 (HMAC-SHA1 是确定的)
        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", AK);
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", "13812345678");
        params.put("RegionId", REGION);
        params.put("SignName", SIGN);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "fixed-nonce-for-test");
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", TEMPLATE_CODE);
        params.put("TemplateParam", "{\"count\":\"3\"}");
        params.put("Timestamp", "2026-05-21T00:00:00Z");
        params.put("Version", "2017-05-25");

        String sig1 = AliyunSmsNotifier.sign(params, SK);
        String sig2 = AliyunSmsNotifier.sign(new TreeMap<>(params), SK);
        assertEquals(sig1, sig2, "Same input must produce same signature");
        assertNotNull(sig1);
        assertFalse(sig1.isBlank());
        // Base64 of HMAC-SHA1 → 28 chars (160 bits / 6 bit per char ≈ 28 chars ending '=')
        assertEquals(28, sig1.length(), "HMAC-SHA1 Base64 should be 28 chars");
    }

    @Test
    @DisplayName("UT-SMS-02: POP percent-encoding edge cases (+ → %20, * → %2A, %7E → ~)")
    void popEncodingEdgeCases() {
        // 空格: URLEncoder 默认 + → POP 要 %20
        assertEquals("hello%20world", AliyunSmsNotifier.popEncode("hello world"));
        // *: URLEncoder 默认不编码 → POP 要 %2A
        assertEquals("a%2Ab", AliyunSmsNotifier.popEncode("a*b"));
        // ~: URLEncoder 默认 %7E → POP 要回滚为 ~
        assertEquals("~tilde~", AliyunSmsNotifier.popEncode("~tilde~"));
        // JSON 串
        String enc = AliyunSmsNotifier.popEncode("{\"name\":\"张三\"}");
        assertTrue(enc.contains("%7B"), "{ should encode to %7B");
        assertTrue(enc.contains("%22"), "\" should encode to %22");
        // null safe
        assertEquals("", AliyunSmsNotifier.popEncode(null));
    }

    @Test
    @DisplayName("UT-SMS-03: happy path — Aliyun OK → SmsResult.success=true + bizId")
    void happyPathSingleRecipient() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> okResp = mockResponse(200,
                "{\"Message\":\"OK\",\"RequestId\":\"req-001\",\"Code\":\"OK\",\"BizId\":\"biz-001\"}");
        doReturn(okResp).when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE),
                Map.of("count", 3, "poNumber", "PO-001"),
                List.of("13812345678"));

        assertEquals(1, results.size());
        AliyunSmsNotifier.SmsResult r = results.get(0);
        assertTrue(r.success(), "should be success");
        assertEquals("13812345678", r.phone());
        assertEquals("OK", r.code());
        assertEquals("biz-001", r.bizId());
        assertEquals("req-001", r.requestId());
        verify(mockClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("UT-SMS-04: Aliyun rejects (rate limit) → SmsResult.failed + code preserved")
    void aliyunRejects() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> rejectResp = mockResponse(200,
                "{\"Message\":\"触发短信频率限制\",\"RequestId\":\"r2\","
                        + "\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\"}");
        doReturn(rejectResp).when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE), Map.of("count", 1),
                List.of("13800000001"));

        assertEquals(1, results.size());
        AliyunSmsNotifier.SmsResult r = results.get(0);
        assertFalse(r.success());
        assertEquals("isv.BUSINESS_LIMIT_CONTROL", r.code());
        assertTrue(r.message().contains("频率限制"));
        assertEquals(null, r.bizId());
    }

    @Test
    @DisplayName("UT-SMS-05: HTTP 500 → SmsResult.failed(HTTP_500, body snippet)")
    void httpServerError() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> err = mockResponse(500, "Internal Server Error body");
        doReturn(err).when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE), Map.of(), List.of("13812345678"));

        assertEquals(1, results.size());
        AliyunSmsNotifier.SmsResult r = results.get(0);
        assertFalse(r.success());
        assertEquals("HTTP_500", r.code());
        assertTrue(r.message().contains("Internal Server Error"));
    }

    @Test
    @DisplayName("UT-SMS-06: 网络 IOException → SmsResult.failed(EXCEPTION, ...)")
    void networkException() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doThrow(new IOException("Connection refused"))
                .when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE), Map.of(), List.of("13812345678"));

        assertEquals(1, results.size());
        AliyunSmsNotifier.SmsResult r = results.get(0);
        assertFalse(r.success());
        assertEquals("EXCEPTION", r.code());
        assertTrue(r.message().contains("IOException"));
        assertTrue(r.message().contains("Connection refused"));
    }

    @Test
    @DisplayName("UT-SMS-07: empty recipientPhones → empty list, no API call")
    void emptyRecipientsSkipsApi() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);

        assertEquals(0, notifier.send(newTemplate(TEMPLATE_CODE), Map.of(), List.of()).size());
        assertEquals(0, notifier.send(newTemplate(TEMPLATE_CODE), Map.of(), null).size());
        verify(mockClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("UT-SMS-08: missing config → IllegalStateException + helpful message")
    void missingConfigThrows() {
        AliyunSmsNotifier blankKey = new AliyunSmsNotifier(mock(HttpClient.class), "", SK, SIGN, ENDPOINT, REGION);
        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> blankKey.send(newTemplate(TEMPLATE_CODE), Map.of(), List.of("13812345678")));
        assertTrue(e1.getMessage().contains("access-key"),
                "Should hint missing access-key, got: " + e1.getMessage());

        AliyunSmsNotifier blankSecret = new AliyunSmsNotifier(mock(HttpClient.class), AK, null, SIGN, ENDPOINT, REGION);
        assertThrows(IllegalStateException.class,
                () -> blankSecret.send(newTemplate(TEMPLATE_CODE), Map.of(), List.of("13812345678")));

        AliyunSmsNotifier blankSign = new AliyunSmsNotifier(mock(HttpClient.class), AK, SK, "  ", ENDPOINT, REGION);
        assertThrows(IllegalStateException.class,
                () -> blankSign.send(newTemplate(TEMPLATE_CODE), Map.of(), List.of("13812345678")));
    }

    @Test
    @DisplayName("UT-SMS-09: template / templateCode null → IllegalArgumentException")
    void nullTemplateThrows() {
        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mock(HttpClient.class), AK, SK, SIGN, ENDPOINT, REGION);
        assertThrows(IllegalArgumentException.class,
                () -> notifier.send(null, Map.of(), List.of("13812345678")));

        NotifyTemplate noCode = NotifyTemplate.builder()
                .id(UUID.randomUUID()).factoryId("F001").templateCode("").build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> notifier.send(noCode, Map.of(), List.of("13812345678")));
        assertTrue(e.getMessage().contains("templateCode"));
    }

    @Test
    @DisplayName("UT-SMS-10: multiple recipients → N API calls, per-recipient independent results")
    void multipleRecipients() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(
                mockResponse(200, "{\"Code\":\"OK\",\"BizId\":\"b1\",\"Message\":\"OK\"}"),
                mockResponse(200, "{\"Code\":\"isv.MOBILE_NUMBER_ILLEGAL\",\"Message\":\"号码非法\"}"),
                mockResponse(200, "{\"Code\":\"OK\",\"BizId\":\"b3\",\"Message\":\"OK\"}")
        ).when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE), Map.of("count", 1),
                List.of("13800000001", "12345", "13800000003"));

        assertEquals(3, results.size());
        assertTrue(results.get(0).success());
        assertFalse(results.get(1).success());
        assertEquals("isv.MOBILE_NUMBER_ILLEGAL", results.get(1).code());
        assertTrue(results.get(2).success());
        verify(mockClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("UT-SMS-11: empty / null phone in list → SmsResult.failed(EMPTY_PHONE)")
    void emptyPhoneInList() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(mockResponse(200, "{\"Code\":\"OK\",\"BizId\":\"b1\"}"))
                .when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        // 有效号码 + 空字符串 + 空白
        List<String> phones = new java.util.ArrayList<>();
        phones.add("13800000001");
        phones.add("");
        phones.add("   ");
        List<AliyunSmsNotifier.SmsResult> results = notifier.send(
                newTemplate(TEMPLATE_CODE), Map.of(), phones);

        assertEquals(3, results.size());
        assertTrue(results.get(0).success());
        assertFalse(results.get(1).success());
        assertEquals("EMPTY_PHONE", results.get(1).code());
        assertFalse(results.get(2).success());
        assertEquals("EMPTY_PHONE", results.get(2).code());
        // 只为有效号码调用一次 API
        verify(mockClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("UT-SMS-12: POST body contains all required POP RPC params")
    void postBodyContainsRequiredFields() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        doReturn(mockResponse(200, "{\"Code\":\"OK\",\"BizId\":\"b\"}"))
                .when(mockClient).send(any(HttpRequest.class), any());

        AliyunSmsNotifier notifier = new AliyunSmsNotifier(mockClient, AK, SK, SIGN, ENDPOINT, REGION);
        notifier.send(newTemplate(TEMPLATE_CODE), Map.of("name", "张三", "code", "8888"),
                List.of("13812345678"));

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(reqCaptor.capture(), any());
        HttpRequest req = reqCaptor.getValue();

        // Extract body bytes via subscriber
        TestBodySubscriber subscriber = new TestBodySubscriber();
        req.bodyPublisher().get().subscribe(subscriber);
        String body = subscriber.collect();

        assertTrue(body.contains("Action=SendSms"), "body should contain Action=SendSms");
        assertTrue(body.contains("Version=2017-05-25"), "body should contain Version");
        assertTrue(body.contains("AccessKeyId=" + AK), "body should contain AccessKeyId");
        assertTrue(body.contains("PhoneNumbers=13812345678"), "body should contain PhoneNumbers");
        assertTrue(body.contains("SignName=" + SIGN), "body should contain SignName");
        assertTrue(body.contains("TemplateCode=" + TEMPLATE_CODE), "body should contain TemplateCode");
        assertTrue(body.contains("TemplateParam="), "body should contain TemplateParam");
        assertTrue(body.contains("SignatureMethod=HMAC-SHA1"), "body should contain SignatureMethod");
        assertTrue(body.contains("SignatureVersion=1.0"), "body should contain SignatureVersion");
        assertTrue(body.contains("SignatureNonce="), "body should contain SignatureNonce");
        assertTrue(body.contains("Timestamp="), "body should contain Timestamp");
        assertTrue(body.contains("Signature="), "body should contain computed Signature");

        // Content-Type 必须是 form
        assertEquals(java.util.Optional.of("application/x-www-form-urlencoded"),
                req.headers().firstValue("Content-Type"));

        // URI 必须是 endpoint
        assertEquals(ENDPOINT, req.uri().toString());
    }

    @Test
    @DisplayName("UT-SMS-13: maskPhone masks middle 4 digits, handles short input")
    void maskPhoneHelper() {
        assertEquals("138****5678", AliyunSmsNotifier.maskPhone("13812345678"));
        assertEquals("999****1234", AliyunSmsNotifier.maskPhone("9991231234"));
        assertEquals("12345", AliyunSmsNotifier.maskPhone("12345"));     // too short
        assertEquals("null", AliyunSmsNotifier.maskPhone(null));
    }

    @Test
    @DisplayName("UT-SMS-14: isConfigured reflects config completeness")
    void isConfiguredReflectsState() {
        assertTrue(new AliyunSmsNotifier(mock(HttpClient.class), AK, SK, SIGN, ENDPOINT, REGION).isConfigured());
        assertFalse(new AliyunSmsNotifier(mock(HttpClient.class), "", SK, SIGN, ENDPOINT, REGION).isConfigured());
        assertFalse(new AliyunSmsNotifier(mock(HttpClient.class), AK, null, SIGN, ENDPOINT, REGION).isConfigured());
        assertFalse(new AliyunSmsNotifier(mock(HttpClient.class), AK, SK, "  ", ENDPOINT, REGION).isConfigured());
    }

    /**
     * Helper subscriber to collect HttpRequest body bytes into a String (UTF-8).
     */
    private static class TestBodySubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final java.util.List<byte[]> chunks = new java.util.ArrayList<>();
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer buffer) {
            byte[] arr = new byte[buffer.remaining()];
            buffer.get(arr);
            chunks.add(arr);
        }

        @Override
        public void onError(Throwable throwable) {
            latch.countDown();
        }

        @Override
        public void onComplete() {
            latch.countDown();
        }

        String collect() throws InterruptedException {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] all = new byte[total];
            int p = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, p, c.length);
                p += c.length;
            }
            return new String(all, StandardCharsets.UTF_8);
        }
    }
}
