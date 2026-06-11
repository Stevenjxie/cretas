package com.cretas.aims.ai.client;

import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dev.faultinjection.DashScopeFaultInjector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Java 代理，通过 HTTP 调用 Python /api/llm/* 端点。
 *
 * <p>提供与 {@link DashScopeClient} 完全相同的 public 方法签名，
 * 使得后续将 DashScopeClient 改为 shim 时，38 个 caller 无需修改。
 *
 * <p>底层路由到 Python llm_router（4-provider fallback + circuit-breaker），
 * 替代单 provider 的 DashScope 直连。
 *
 * <h3>端点映射</h3>
 * <ul>
 *   <li>POST /api/llm/chat              — 同步对话</li>
 *   <li>POST /api/llm/chat-stream       — SSE 流式对话</li>
 *   <li>POST /api/llm/tool-call         — Function Calling</li>
 *   <li>POST /api/llm/intent-classify   — 意图分类（强制 temperature=0.1）</li>
 *   <li>POST /api/llm/vision            — 视觉识别（multipart）</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-28
 */
@Slf4j
@Component
public class PythonLLMClient {

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * F3 fault-injection hook (optional). Bean exists only under
     * {@code dev-fault-injection} Spring profile; remains {@code null} in prod.
     */
    @Autowired(required = false)
    private DashScopeFaultInjector dashScopeFaultInjector;

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /**
     * 截断保护：超过此字符数的消息内容将被截断，防止 LLM token 上限错误。
     */
    private static final int MAX_MESSAGE_CHARS = 400_000;

    public PythonLLMClient(
            @Value("${python-smartbi.url:http://localhost:8083}") String baseUrl,
            @Qualifier("aiServiceHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        log.info("PythonLLMClient 初始化: baseUrl={}", baseUrl);
    }

    // ══════════════════════════════════════════════════════════════
    // 核心 HTTP 方法
    // ══════════════════════════════════════════════════════════════

    /**
     * 同步 Chat Completion。
     *
     * <p>将请求序列化为 {@code LLMRequest} 格式，POST 到 {@code /api/llm/chat}，
     * 响应按 OpenAI 兼容格式反序列化为 {@link ChatCompletionResponse}。
     *
     * @param request 请求体
     * @return 响应
     */
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        LlmRequest llmReq = buildLlmRequest(request, "chat");
        String slot = Boolean.TRUE.equals(request.getEnableThinking()) ? "reasoning" : llmReq.slot;
        try {
            return post("/api/llm/chat", llmReq, ChatCompletionResponse.class, slot);
        } catch (Exception e) {
            log.error("PythonLLMClient chatCompletion 失败", e);
            return createErrorResponse("Python LLM 调用失败: " + e.getMessage());
        }
    }

    /**
     * SSE 流式 Chat Completion。
     *
     * <p>调用 {@code /api/llm/chat-stream}，逐行读取 SSE 事件：
     * <ul>
     *   <li>{@code {"type":"delta","text":"..."}} — 调用 onToken</li>
     *   <li>{@code [DONE]} — 流结束</li>
     * </ul>
     *
     * @param request    请求体（会强制设 stream=true）
     * @param onToken    每个 content delta 的回调
     * @param onComplete 流结束时的回调（携带完整内容）
     */
    public void chatCompletionStream(ChatCompletionRequest request,
                                     Consumer<String> onToken,
                                     Consumer<ChatCompletionResponse> onComplete) {
        request.setStream(true);
        LlmRequest llmReq = buildLlmRequest(request, "chat");

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(llmReq);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("PythonLLMClient stream 序列化失败: " + e.getMessage(), e);
        }

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/api/llm/chat-stream")
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("PythonLLMClient stream error: {} - {}", response.code(), errorBody);
                throw new RuntimeException("Python LLM stream 调用失败: " + response.code());
            }

            StringBuilder fullContent = new StringBuilder();
            String finishReason = "stop";
            // Stream usage captured from Python {"type":"usage","tokens":N} SSE event.
            // Python call_chain_stream emits this when upstream reports usage on final [DONE].
            int streamTotalTokens = 0;
            String streamModel = "unknown";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        // SSE event shape from call_chain_stream:
                        // {"type":"delta","text":"..."} or {"type":"usage","tokens":N} or {"type":"done",...}
                        var node = objectMapper.readTree(data);
                        String type = node.path("type").asText("");
                        if ("delta".equals(type)) {
                            String text = node.path("text").asText("");
                            if (!text.isEmpty()) {
                                fullContent.append(text);
                                onToken.accept(text);
                            }
                            // Capture model from delta events if present
                            String m = node.path("model").asText("");
                            if (!m.isEmpty()) {
                                streamModel = m;
                            }
                        } else if ("usage".equals(type)) {
                            // Python call_chain_stream yields {"type":"usage","tokens":N}
                            streamTotalTokens = node.path("tokens").asInt(0);
                        } else if ("done".equals(type) || "finish".equals(type)) {
                            finishReason = node.path("finish_reason").asText("stop");
                        } else if ("error".equals(type)) {
                            String errorMsg = node.path("message").asText("unknown error");
                            log.error("PythonLLMClient stream received error event: {}", errorMsg);
                            throw new RuntimeException("Python LLM stream error: " + errorMsg);
                        }
                    } catch (Exception e) {
                        log.trace("PythonLLMClient stream: skip non-JSON line: {}", data);
                    }
                }
            }
            // Log stream usage (total_tokens only; prompt/completion breakdown not available in stream path)
            log.info("[LLM_USAGE] slot=chat path=/api/llm/chat-stream model={} prompt_tokens=0 completion_tokens=0 total_tokens={}",
                    streamModel, streamTotalTokens);

            // 构建完整响应传给 onComplete
            ChatCompletionResponse result = new ChatCompletionResponse();
            ChatCompletionResponse.Message msg = new ChatCompletionResponse.Message();
            msg.setRole("assistant");
            msg.setContent(fullContent.toString());
            ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
            choice.setIndex(0);
            choice.setMessage(msg);
            choice.setFinishReason(finishReason);
            result.setChoices(List.of(choice));

            onComplete.accept(result);

        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            log.error("PythonLLMClient stream IO 失败", e);
            throw new RuntimeException("Python LLM stream 请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * Function Calling — 带工具列表的对话。
     *
     * @param messages   消息列表
     * @param tools      可用工具列表
     * @param toolChoice 工具选择策略（"auto" / "none" / 指定工具对象）
     * @return 响应（可能包含 tool_calls）
     */
    public ChatCompletionResponse chatCompletionWithTools(
            List<ChatMessage> messages,
            List<Tool> tools,
            String toolChoice) {

        // 序列化 messages 和 tools 到 Map 形式
        List<Object> messagesRaw;
        List<Object> toolsRaw;
        try {
            // Use Jackson to convert DTOs to raw Map/List structures
            String msgsJson = objectMapper.writeValueAsString(messages);
            String toolsJson = objectMapper.writeValueAsString(tools);
            messagesRaw = objectMapper.readValue(msgsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
            toolsRaw = objectMapper.readValue(toolsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
        } catch (JsonProcessingException e) {
            log.error("PythonLLMClient chatCompletionWithTools 序列化失败", e);
            return createErrorResponse("序列化失败: " + e.getMessage());
        }

        LlmRequest llmReq = new LlmRequest();
        llmReq.messages = messagesRaw;
        llmReq.slot = "chat";
        llmReq.tools = toolsRaw;
        llmReq.tool_choice = toolChoice;

        try {
            return post("/api/llm/tool-call", llmReq, ChatCompletionResponse.class, "tool-call");
        } catch (Exception e) {
            log.error("PythonLLMClient chatCompletionWithTools 失败", e);
            return createErrorResponse("Python LLM tool-call 失败: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 便利方法（构建请求后委托核心方法）
    // ══════════════════════════════════════════════════════════════

    /**
     * 简单对话调用。调用 {@code /api/llm/chat}，使用 "chat" slot。
     *
     * @param systemPrompt 系统提示词
     * @param userInput    用户输入
     * @return 响应内容文本
     */
    public String chat(String systemPrompt, String userInput) {
        LlmRequest req = buildSimpleRequest(systemPrompt, userInput, "chat", null, null);
        try {
            ChatCompletionResponse response = post("/api/llm/chat", req, ChatCompletionResponse.class, "chat");
            if (response.hasError()) {
                throw new RuntimeException("Python LLM 错误: " + response.getErrorMessage());
            }
            return response.getContent();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Python LLM chat 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 快速模型对话（Python 路由自动选最快模型，与 chat 行为相同）。
     *
     * @param systemPrompt 系统提示词
     * @param userInput    用户输入
     * @return 响应内容文本
     */
    public String chatFast(String systemPrompt, String userInput) {
        // Python llm_router 已按 slot 自动选模型，Java 侧无需区分 fast/normal
        return chat(systemPrompt, userInput);
    }

    /**
     * 低温度对话（用于需要确定性输出的场景）。
     * 调用 {@code /api/llm/chat}，强制 temperature=0.1。
     *
     * @param systemPrompt 系统提示词
     * @param userInput    用户输入
     * @return 响应内容文本
     */
    public String chatLowTemp(String systemPrompt, String userInput) {
        LlmRequest req = buildSimpleRequest(systemPrompt, userInput, "chat", 0.1, null);
        try {
            ChatCompletionResponse response = post("/api/llm/chat", req, ChatCompletionResponse.class, "chat");
            if (response.hasError()) {
                throw new RuntimeException("Python LLM 错误: " + response.getErrorMessage());
            }
            return response.getContent();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Python LLM chatLowTemp 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 带思考模式的对话。
     * 调用 {@code /api/llm/chat}，附带 enable_thinking=true 和 thinking_budget。
     *
     * @param systemPrompt   系统提示词
     * @param userInput      用户输入
     * @param thinkingBudget 思考预算（10-100，数值越大思考越深）
     * @return 响应（包含思考过程 reasoningContent）
     */
    public ChatCompletionResponse chatWithThinking(String systemPrompt, String userInput, int thinkingBudget) {
        LlmRequest req = buildSimpleRequest(systemPrompt, userInput, "reasoning", null, null);
        req.enable_thinking = true;
        // thinkingBudget 通过 extra_body 传递，Python 端会透传给 provider
        req.extra_body = new ExtraBodyDto(true, thinkingBudget);

        try {
            return post("/api/llm/chat", req, ChatCompletionResponse.class, "reasoning");
        } catch (Exception e) {
            log.error("PythonLLMClient chatWithThinking 失败", e);
            return createErrorResponse("Python LLM thinking 失败: " + e.getMessage());
        }
    }

    /**
     * 意图分类（强制 temperature=0.1 保证确定性）。
     * 调用 {@code /api/llm/intent-classify}。
     *
     * @param systemPrompt 分类提示词
     * @param userInput    用户输入
     * @return JSON 格式的分类结果
     */
    public String classifyIntent(String systemPrompt, String userInput) {
        LlmRequest req = buildSimpleRequest(systemPrompt, userInput, "chat", 0.1, null);
        try {
            ChatCompletionResponse response = post("/api/llm/intent-classify", req, ChatCompletionResponse.class, "intent-classify");
            if (response.hasError()) {
                throw new RuntimeException("Python LLM 错误: " + response.getErrorMessage());
            }
            return response.getContent();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Python LLM classifyIntent 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 带工具调用的简单对话（chatCompletionWithTools 的便利包装）。
     *
     * @param systemPrompt 系统提示词
     * @param userInput    用户输入
     * @param tools        可用工具列表
     * @return 响应（可能包含 tool_calls）
     */
    public ChatCompletionResponse chatWithTools(
            String systemPrompt,
            String userInput,
            List<Tool> tools) {

        List<ChatMessage> messages = List.of(
                ChatMessage.system(systemPrompt),
                ChatMessage.user(userInput)
        );
        return chatCompletionWithTools(messages, tools, "auto");
    }

    // ══════════════════════════════════════════════════════════════
    // 纯工具方法（不发 HTTP，只解析 response）
    // ══════════════════════════════════════════════════════════════

    /**
     * 检查响应是否包含工具调用。
     */
    public boolean hasToolCalls(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return false;
        }
        ChatCompletionResponse.Message message = response.getChoices().get(0).getMessage();
        return message != null
                && message.getToolCalls() != null
                && !message.getToolCalls().isEmpty();
    }

    /**
     * 获取第一个工具调用，没有则返回 null。
     */
    public ToolCall getFirstToolCall(ChatCompletionResponse response) {
        if (!hasToolCalls(response)) {
            return null;
        }
        return response.getChoices().get(0).getMessage().getToolCalls().get(0);
    }

    /**
     * 获取所有工具调用，没有则返回空列表。
     */
    public List<ToolCall> getAllToolCalls(ChatCompletionResponse response) {
        if (!hasToolCalls(response)) {
            return List.of();
        }
        return response.getChoices().get(0).getMessage().getToolCalls();
    }

    /**
     * 检查 Python LLM 服务是否可用（GET /health）。
     */
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/health")
                    .get()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("PythonLLMClient isAvailable 检查失败: {}", e.getMessage());
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 静态工具方法
    // ══════════════════════════════════════════════════════════════

    // 与 DashScopeClient 共用的简单/复杂关键词集合
    private static final Set<String> SIMPLE_INDICATORS = new HashSet<>(Arrays.asList(
            "你好", "您好", "谢谢", "再见", "是什么", "几号", "多少",
            "帮我", "查一下", "告诉我", "在哪", "怎么样", "好的"
    ));

    private static final Set<String> COMPLEX_KEYWORDS = new HashSet<>(Arrays.asList(
            "分析", "对比", "为什么", "建议", "优化", "预测", "评估",
            "趋势", "原因", "策略", "规划", "诊断", "改进", "深入",
            "analyze", "compare", "optimiz", "diagnos", "evaluat", "predict",
            "strateg", "tradeoff", "trade-off", "suggest", "recommend", "why "
    ));

    /**
     * 根据用户输入自动判断是否需要启用 thinking 模式。
     * 纯关键词匹配，无 HTTP 调用。
     *
     * @param userInput 用户原始输入
     * @return true = 需要 thinking（复杂分析），false = 不需要（简单查询）
     */
    public static boolean shouldEnableThinking(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String text = userInput.trim();
        String lower = text.toLowerCase();

        // 1. 输入超过 200 字符 → 复杂查询
        if (text.length() > 200) {
            return true;
        }

        // 2. 包含"分析"/"推理"关键词直接启用
        if (lower.contains("分析") || lower.contains("推理")) {
            return true;
        }

        // 3. 复杂关键词命中 ≥2 → 启用
        int complexCount = 0;
        for (String keyword : COMPLEX_KEYWORDS) {
            if (lower.contains(keyword)) {
                complexCount++;
                if (complexCount >= 2) {
                    return true;
                }
            }
        }

        // 4. 极短查询 → 不需要
        if (text.length() < 6) {
            return false;
        }

        // 5. 简单寒暄 → 不需要
        for (String indicator : SIMPLE_INDICATORS) {
            if (text.contains(indicator)) {
                return false;
            }
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 视觉方法（供 DashScopeVisionClient shim 调用）
    // ══════════════════════════════════════════════════════════════

    /**
     * 视觉识别 — 调用 Python {@code /api/llm/vision} multipart 端点。
     *
     * @param imageBytes  图片字节数组
     * @param contentType MIME 类型（如 "image/jpeg"）
     * @param prompt      文字提示
     * @return LLM 的文本描述
     */
    public String visionChat(byte[] imageBytes, String contentType, String prompt) {
        if (contentType == null || contentType.isBlank()) {
            contentType = "image/jpeg";
        }

        RequestBody imageBody = RequestBody.create(imageBytes, MediaType.parse(contentType));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "image", imageBody)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("slot", "vl")
                .build();

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/api/llm/vision")
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("PythonLLMClient visionChat error: {} - {}", response.code(), errorBody);
                throw new RuntimeException("Python LLM vision 调用失败: " + response.code());
            }
            String responseBody = response.body() != null ? response.body().string() : "{}";
            ChatCompletionResponse resp = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            logLlmUsage("vl", "/api/llm/vision", resp);
            if (resp.hasError()) {
                throw new RuntimeException("Python LLM vision 错误: " + resp.getErrorMessage());
            }
            String content = resp.getContent();
            return content != null ? content : "";
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            log.error("PythonLLMClient visionChat IO 失败", e);
            throw new RuntimeException("Python LLM vision 请求失败: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 私有辅助方法
    // ══════════════════════════════════════════════════════════════

    /**
     * 统一 POST 辅助：序列化 body → HTTP 调用 → 反序列化响应。
     *
     * <p>若响应类型为 {@link ChatCompletionResponse}，则自动记录 LLM usage 计量日志（stop 账单盲飞）。
     *
     * @param path         Python LLM 端点路径（如 "/api/llm/chat"）
     * @param body         请求体（将被序列化为 JSON）
     * @param responseType 响应反序列化类型
     * @param slot         调用 slot 标签（用于 usage 日志，如 "chat", "reasoning", "vl"）
     */
    private <T> T post(String path, Object body, Class<T> responseType, String slot) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        log.debug("PythonLLMClient POST {}: {}", path, jsonBody);

        // F3 fault-injection (dev-fault-injection profile only): simulate DashScope timeout
        if (dashScopeFaultInjector != null) {
            dashScopeFaultInjector.maybeDelay();
        }

        Request httpRequest = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "{}";
            log.debug("PythonLLMClient response {}: {}", response.code(), responseBody);

            if (response.isSuccessful()) {
                T result = objectMapper.readValue(responseBody, responseType);
                if (result instanceof ChatCompletionResponse ccr) {
                    logLlmUsage(slot, path, ccr);
                }
                return result;
            }

            log.error("PythonLLMClient HTTP error: {} {} - {}", response.code(), path, responseBody);
            throw new IOException("HTTP " + response.code() + ": " + responseBody);
        }
    }

    /**
     * Backward-compat overload (no slot → inferred from path).
     * @deprecated Prefer {@link #post(String, Object, Class, String)} with explicit slot.
     */
    private <T> T post(String path, Object body, Class<T> responseType) throws Exception {
        // Infer a best-effort slot label from the path so legacy call sites still log usage.
        String slot = path.contains("vision") ? "vl"
                : path.contains("intent") ? "intent-classify"
                : path.contains("tool") ? "tool-call"
                : "chat";
        return post(path, body, responseType, slot);
    }

    /**
     * 记录 LLM usage 计量日志（INFO 级别，结构化，grep 友好）。
     *
     * <p>日志格式（单行）：
     * <pre>
     * [LLM_USAGE] slot=chat path=/api/llm/chat model=qwen3.5-plus
     *             prompt_tokens=512 completion_tokens=128 total_tokens=640
     * </pre>
     *
     * <p>设计原则：
     * <ul>
     *   <li>只记日志，不修改调用行为（task 约束：禁止更改 LLM 调用行为）。</li>
     *   <li>usage 字段由 Python call_chain 从上游 provider 透传，null 表示 provider 未返回（如 stream）。</li>
     *   <li>model 字段由 Python 透传实际 provider 模型名（如 "qwen3.5-plus"），非 Java 侧估算。</li>
     * </ul>
     *
     * @param slot  调用 slot 标签（"chat" / "reasoning" / "vl" / "intent-classify" / "tool-call"）
     * @param path  HTTP 路径（辅助 debug）
     * @param resp  Python LLM 响应（含 model + usage）
     */
    void logLlmUsage(String slot, String path, ChatCompletionResponse resp) {
        if (resp == null) {
            return;
        }
        String model = resp.getModel() != null ? resp.getModel() : "unknown";
        ChatCompletionResponse.Usage usage = resp.getUsage();
        int promptTokens      = usage != null && usage.getPromptTokens()     != null ? usage.getPromptTokens()     : 0;
        int completionTokens  = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        int totalTokens       = usage != null && usage.getTotalTokens()      != null ? usage.getTotalTokens()
                                                                                     : promptTokens + completionTokens;
        // Usage=0 with error response → log at WARN to surface billing gaps
        if (resp.hasError()) {
            log.warn("[LLM_USAGE] slot={} path={} model={} prompt_tokens={} completion_tokens={} total_tokens={} status=error error={}",
                    slot, path, model, promptTokens, completionTokens, totalTokens, resp.getErrorMessage());
        } else {
            log.info("[LLM_USAGE] slot={} path={} model={} prompt_tokens={} completion_tokens={} total_tokens={}",
                    slot, path, model, promptTokens, completionTokens, totalTokens);
        }
    }

    /**
     * 截断保护：超过 MAX_MESSAGE_CHARS 时保留首尾，中间替换省略标记。
     */
    private String truncate(String s) {
        if (s == null || s.length() <= MAX_MESSAGE_CHARS) {
            return s;
        }
        int keepHead = MAX_MESSAGE_CHARS / 2;
        int keepTail = MAX_MESSAGE_CHARS / 4;
        int dropped = s.length() - keepHead - keepTail;
        String truncated = s.substring(0, keepHead)
                + "\n...[truncated " + dropped + " chars]...\n"
                + s.substring(s.length() - keepTail);
        log.warn("PythonLLMClient: 输入过长 ({} chars)，已截断至 {} chars（丢弃 {} chars）",
                s.length(), truncated.length(), dropped);
        return truncated;
    }

    /**
     * 从 {@link ChatCompletionRequest} 构建 {@link LlmRequest}，转换消息格式。
     */
    @SuppressWarnings("unchecked")
    private LlmRequest buildLlmRequest(ChatCompletionRequest request, String defaultSlot) {
        LlmRequest llmReq = new LlmRequest();
        llmReq.slot = defaultSlot;

        if (request.getTemperature() != null) {
            llmReq.temperature = request.getTemperature();
        }
        if (request.getMaxTokens() != null) {
            llmReq.max_tokens = request.getMaxTokens();
        }
        if (Boolean.TRUE.equals(request.getEnableThinking())) {
            llmReq.enable_thinking = true;
            llmReq.slot = "reasoning";
        }
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            try {
                String toolsJson = objectMapper.writeValueAsString(request.getTools());
                llmReq.tools = objectMapper.readValue(toolsJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
            } catch (JsonProcessingException e) {
                log.warn("PythonLLMClient: tools 序列化失败，跳过", e);
            }
        }
        if (request.getToolChoice() != null) {
            llmReq.tool_choice = request.getToolChoice();
        }

        // 转换 messages：ChatMessage → raw Map（Jackson 自动处理）
        if (request.getMessages() != null) {
            try {
                String msgsJson = objectMapper.writeValueAsString(request.getMessages());
                llmReq.messages = objectMapper.readValue(msgsJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
            } catch (JsonProcessingException e) {
                log.error("PythonLLMClient: messages 序列化失败", e);
                llmReq.messages = List.of();
            }
        }

        return llmReq;
    }

    /**
     * 构建简单 system+user 双消息请求。
     */
    private LlmRequest buildSimpleRequest(String systemPrompt, String userInput,
                                           String slot, Double temperature, Integer maxTokens) {
        LlmRequest req = new LlmRequest();
        req.slot = slot;
        req.messages = List.of(
                java.util.Map.of("role", "system", "content", truncate(systemPrompt != null ? systemPrompt : "")),
                java.util.Map.of("role", "user", "content", truncate(userInput != null ? userInput : ""))
        );
        if (temperature != null) {
            req.temperature = temperature;
        }
        if (maxTokens != null) {
            req.max_tokens = maxTokens;
        }
        return req;
    }

    /**
     * 构建错误响应。
     */
    private ChatCompletionResponse createErrorResponse(String message) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        ChatCompletionResponse.Error error = new ChatCompletionResponse.Error();
        error.setMessage(message);
        error.setType("client_error");
        response.setError(error);
        return response;
    }

    // ══════════════════════════════════════════════════════════════
    // 内部请求 DTO（匹配 Python LlmRequest pydantic model）
    // ══════════════════════════════════════════════════════════════

    /**
     * 对应 Python {@code LLMRequest} Pydantic model 的 Java DTO。
     * 字段使用 snake_case，直接被 Jackson 序列化为 JSON。
     */
    @SuppressWarnings("java:S116") // snake_case field names are intentional (matches Python API)
    private static class LlmRequest {
        public List<Object> messages;
        public String slot = "chat";
        public Double temperature;
        public Integer max_tokens;
        public Boolean enable_thinking;
        public List<Object> tools;
        public Object tool_choice;
        public Object extra_body;
    }

    /**
     * thinking_budget extra_body DTO，与 ChatCompletionRequest.ExtraBody 结构一致。
     */
    @SuppressWarnings("java:S116")
    private static class ExtraBodyDto {
        public Boolean enable_thinking;
        public Integer thinking_budget;

        ExtraBodyDto(Boolean enableThinking, Integer thinkingBudget) {
            this.enable_thinking = enableThinking;
            this.thinking_budget = thinkingBudget;
        }
    }
}
