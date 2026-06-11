package com.cretas.aims.ai.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PythonLLMClient} LLM usage logging (账单可观测性止血).
 *
 * <p>Uses MockWebServer to simulate the Python LLM service and Logback ListAppender
 * to capture structured log output without any actual HTTP calls to DashScope.
 *
 * <p>Test IDs: UT-LLMU-01 through UT-LLMU-05.
 */
@ExtendWith(MockitoExtension.class)
class PythonLLMClientUsageLogTest {

    private static final String TAG = "[LLM_USAGE]";

    private MockWebServer mockServer;
    private PythonLLMClient client;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger clientLogger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        OkHttpClient httpClient = new OkHttpClient();

        // Construct PythonLLMClient using the 3-arg constructor (value injection via ReflectionTestUtils)
        client = new PythonLLMClient(baseUrl, httpClient, objectMapper);

        // Attach a ListAppender to the client's logger so we can inspect log output
        clientLogger = (Logger) LoggerFactory.getLogger(PythonLLMClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
        clientLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() throws IOException {
        clientLogger.detachAppender(logAppender);
        mockServer.shutdown();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UT-LLMU-01: chat() — usage log contains model + tokens
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-LLMU-01: chat() emits [LLM_USAGE] with model, prompt_tokens, completion_tokens, total_tokens")
    void chatEmitsUsageLog() throws Exception {
        // Python response with usage data (mirrors call_chain passthrough)
        String responseJson = """
                {
                  "id": "cmpl-abc123",
                  "object": "chat.completion",
                  "model": "qwen3.5-plus",
                  "choices": [{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
                  "usage": {"prompt_tokens": 150, "completion_tokens": 42, "total_tokens": 192}
                }
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"));

        String result = client.chat("sys", "user");

        assertThat(result).isEqualTo("hello");
        assertUsageLogContains("qwen3.5-plus", 150, 42, 192);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UT-LLMU-02: classifyIntent() — slot=intent-classify logged
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-LLMU-02: classifyIntent() emits [LLM_USAGE] with slot=intent-classify")
    void classifyIntentEmitsUsageLog() throws Exception {
        String responseJson = """
                {
                  "model": "qwen3.5-plus",
                  "choices": [{"index":0,"message":{"role":"assistant","content":"INTENT_X"},"finish_reason":"stop"}],
                  "usage": {"prompt_tokens": 80, "completion_tokens": 20, "total_tokens": 100}
                }
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"));

        client.classifyIntent("sys", "user");

        List<String> logMessages = captureUsageLogs();
        assertThat(logMessages).anyMatch(m -> m.contains("slot=intent-classify"));
        assertThat(logMessages).anyMatch(m -> m.contains("total_tokens=100"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UT-LLMU-03: chatWithThinking() — slot=reasoning logged
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-LLMU-03: chatWithThinking() emits [LLM_USAGE] with slot=reasoning")
    void chatWithThinkingEmitsUsageLog() throws Exception {
        String responseJson = """
                {
                  "model": "qwq-plus",
                  "choices": [{"index":0,"message":{"role":"assistant","content":"thought"},"finish_reason":"stop"}],
                  "usage": {"prompt_tokens": 300, "completion_tokens": 200, "total_tokens": 500}
                }
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"));

        ChatCompletionResponse resp = client.chatWithThinking("sys", "user", 50);

        assertThat(resp).isNotNull();
        List<String> logs = captureUsageLogs();
        assertThat(logs).anyMatch(m -> m.contains("slot=reasoning"));
        assertThat(logs).anyMatch(m -> m.contains("model=qwq-plus"));
        assertThat(logs).anyMatch(m -> m.contains("total_tokens=500"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UT-LLMU-04: usage null (provider did not return usage) → total_tokens=0, no NPE
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-LLMU-04: null usage in response → log total_tokens=0 without NPE")
    void nullUsageDoesNotThrow() throws Exception {
        String responseJson = """
                {
                  "model": "glm-4.5-air",
                  "choices": [{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]
                }
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"));

        String result = client.chat("sys", "user");

        assertThat(result).isEqualTo("ok");
        List<String> logs = captureUsageLogs();
        assertThat(logs).anyMatch(m -> m.contains("total_tokens=0"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UT-LLMU-05: logLlmUsage() directly — error response logs at WARN with error field
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-LLMU-05: logLlmUsage() with error response emits WARN log containing error detail")
    void errorResponseLogsAtWarn() {
        ChatCompletionResponse errorResp = new ChatCompletionResponse();
        ChatCompletionResponse.Error error = new ChatCompletionResponse.Error();
        error.setMessage("FreeTierOnly: quota exceeded");
        error.setType("quota_error");
        errorResp.setError(error);
        errorResp.setModel("qwen3.5-plus");

        // Call directly — no HTTP needed
        client.logLlmUsage("chat", "/api/llm/chat", errorResp);

        List<ILoggingEvent> warnEvents = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(TAG))
                .toList();
        assertThat(warnEvents).isNotEmpty();
        assertThat(warnEvents.get(0).getFormattedMessage()).contains("FreeTierOnly");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private List<String> captureUsageLogs() {
        return logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(TAG))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void assertUsageLogContains(String model, int promptTokens, int completionTokens, int totalTokens) {
        List<String> logs = captureUsageLogs();
        assertThat(logs)
                .withFailMessage("Expected [LLM_USAGE] log but found none. All logs: %s",
                        logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .isNotEmpty();
        String entry = logs.get(0);
        assertThat(entry).contains("model=" + model);
        assertThat(entry).contains("prompt_tokens=" + promptTokens);
        assertThat(entry).contains("completion_tokens=" + completionTokens);
        assertThat(entry).contains("total_tokens=" + totalTokens);
    }
}
