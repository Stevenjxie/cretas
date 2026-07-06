package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionRequest.ExtraBody;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.ai.GenericChatRequest;
import com.cretas.aims.dto.ai.GenericChatResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.annotation.RateLimit.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.exception.BusinessException;

/**
 * 通用 AI Chat Controller
 *
 * 提供通用的 AI 对话能力，用于：
 * - SKU 配置语音交互
 * - 其他需要 AI 对话的场景
 *
 * 路径: /api/mobile/ai/chat
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/ai")
@Tag(name = "通用 AI Chat", description = "通用 AI 对话接口，支持多轮对话")
@Validated
public class GenericAIChatController {

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private DashScopeConfig dashScopeConfig;

    @Autowired
    private ObjectMapper objectMapper;

    private final ExecutorService sseExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "ai-chat-sse"); t.setDaemon(true); return t; });

    /**
     * 通用 AI Chat 接口
     *
     * 接收消息列表，返回 AI 回复
     */
    @PostMapping("/chat")
    @Operation(summary = "通用 AI Chat",
               description = "发送消息列表给 AI，获取回复。支持多轮对话和自定义参数。")
    @RateLimit(count = 240, period = 60, limitType = LimitType.USER, message = "AI对话请求过于频繁，请稍后再试")
    public ApiResponse<GenericChatResponse> chat(
            @RequestBody GenericChatRequest request) {

        log.info("通用 AI Chat 请求: messages={}, temperature={}, maxTokens={}",
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getTemperature(),
                request.getMaxTokens());

        try {
            // 转换消息格式
            List<ChatMessage> chatMessages = convertMessages(request.getMessages());

            // 自动检测是否需要 thinking 模式
            String lastUserMsg = "";
            if (request.getMessages() != null) {
                for (int i = request.getMessages().size() - 1; i >= 0; i--) {
                    if ("user".equalsIgnoreCase(request.getMessages().get(i).getRole())) {
                        lastUserMsg = request.getMessages().get(i).getContent();
                        break;
                    }
                }
            }
            boolean enableThinking = DashScopeClient.shouldEnableThinking(lastUserMsg);

            // P1: 智能模型路由 — 简单查询用快速模型，复杂查询用默认模型
            String model;
            if (request.getModel() != null) {
                model = request.getModel();  // 客户端指定优先
            } else if (enableThinking) {
                model = dashScopeConfig.getModel();      // 复杂 → qwen3.5-plus
            } else {
                model = dashScopeConfig.getFastModel();   // 简单 → qwen3.5-flash
            }

            // P2: 简单查询降低 maxTokens（减少生成时间）
            // 注意: GenericChatRequest 的 @Builder.Default maxTokens=2000，所以 getMaxTokens() 永远非 null
            int maxTokens;
            if (enableThinking) {
                maxTokens = 2000;   // 复杂查询需要详细输出
            } else {
                maxTokens = 500;    // 简单查询不需要长篇大论
            }

            log.info("AI Chat 路由: input='{}', thinking={}, model={}, maxTokens={}",
                    lastUserMsg.length() > 50 ? lastUserMsg.substring(0, 50) + "..." : lastUserMsg,
                    enableThinking, model, maxTokens);

            ChatCompletionRequest aiRequest = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(chatMessages)
                    .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                    .maxTokens(maxTokens)
                    .extraBody(ExtraBody.builder().enableThinking(enableThinking).build())
                    .build();

            // R4: 调用 DashScope API + 计时
            long t0 = System.currentTimeMillis();
            ChatCompletionResponse aiResponse = dashScopeClient.chatCompletion(aiRequest);
            long elapsed = System.currentTimeMillis() - t0;

            // 提取响应内容
            String content = extractContent(aiResponse);
            Integer tokensUsed = extractTokensUsed(aiResponse);
            String finishReason = extractFinishReason(aiResponse);

            log.info("AI Chat 响应: model={}, thinking={}, tokensUsed={}, elapsed={}ms, finishReason={}",
                    model, enableThinking, tokensUsed, elapsed, finishReason);

            GenericChatResponse response = GenericChatResponse.builder()
                    .content(content)
                    .tokensUsed(tokensUsed)
                    .model(model)
                    .finishReason(finishReason)
                    .build();

            return ApiResponse.success(response);

        } catch (BusinessException be) {
            throw be;


        } catch (Exception e) {
            log.error("AI Chat 失败: {}", e.getMessage(), e);
            throw new BusinessException(500, "AI 服务暂时不可用: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * SSE 流式 AI Chat
     *
     * 逐 token 推送，TTFT ~0.5s
     * 事件类型: meta / token / done / error
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式 AI Chat (SSE)",
               description = "SSE 流式输出，逐 token 推送。事件: meta, token, done, error")
    @RateLimit(count = 240, period = 60, limitType = LimitType.USER, message = "AI对话请求过于频繁，请稍后再试")
    public SseEmitter chatStream(@RequestBody GenericChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        emitter.onCompletion(() -> log.debug("Chat stream completed"));
        emitter.onTimeout(() -> log.warn("Chat stream timeout"));
        emitter.onError(e -> log.error("Chat stream error: {}", ErrorSanitizer.sanitize(e)));

        sseExecutor.execute(() -> {
            try {
                List<ChatMessage> chatMessages = convertMessages(request.getMessages());

                // Same P1+P2 logic as sync endpoint
                String lastUserMsg = "";
                if (request.getMessages() != null) {
                    for (int i = request.getMessages().size() - 1; i >= 0; i--) {
                        if ("user".equalsIgnoreCase(request.getMessages().get(i).getRole())) {
                            lastUserMsg = request.getMessages().get(i).getContent();
                            break;
                        }
                    }
                }
                boolean enableThinking = DashScopeClient.shouldEnableThinking(lastUserMsg);

                String model;
                if (request.getModel() != null) {
                    model = request.getModel();
                } else if (enableThinking) {
                    model = dashScopeConfig.getModel();
                } else {
                    model = dashScopeConfig.getFastModel();
                }

                int maxTokens;
                if (enableThinking) {
                    maxTokens = 2000;
                } else {
                    maxTokens = 500;
                }

                log.info("Chat stream: model={}, thinking={}, maxTokens={}", model, enableThinking, maxTokens);

                ChatCompletionRequest aiRequest = ChatCompletionRequest.builder()
                        .model(model)
                        .messages(chatMessages)
                        .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                        .maxTokens(maxTokens)
                        .extraBody(ExtraBody.builder().enableThinking(enableThinking).build())
                        .build();

                // Send metadata event
                emitter.send(SseEmitter.event().name("meta")
                        .data("{\"model\":\"" + model + "\",\"thinking\":" + enableThinking + "}"));

                // R4: Stream tokens with timing
                long streamT0 = System.currentTimeMillis();
                dashScopeClient.chatCompletionStream(aiRequest,
                    token -> {
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (BusinessException be) {
                            throw be;
                        } catch (Exception e) {
                            log.debug("Client disconnected during token send");
                        }
                    },
                    response -> {
                        try {
                            long streamElapsed = System.currentTimeMillis() - streamT0;
                            Integer tokensUsed = extractTokensUsed(response);
                            String finishReason = extractFinishReason(response);
                            String fullContent = extractContent(response);
                            log.info("Chat stream done: model={}, thinking={}, tokensUsed={}, elapsed={}ms",
                                    model, enableThinking, tokensUsed, streamElapsed);
                            Map<String, Object> doneData = new java.util.LinkedHashMap<>();
                            doneData.put("fullContent", fullContent);
                            doneData.put("tokensUsed", tokensUsed != null ? tokensUsed : 0);
                            doneData.put("finishReason", finishReason != null ? finishReason : "stop");
                            doneData.put("model", model);
                            emitter.send(SseEmitter.event().name("done")
                                    .data(objectMapper.writeValueAsString(doneData)));
                            emitter.complete();
                        } catch (BusinessException be) {
                            throw be;
                        } catch (Exception e) {
                            log.debug("Error sending done event: {}", e.getMessage());
                        }
                    });

            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                log.error("Chat stream failed: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"" + ErrorSanitizer.sanitize(e).replace("\"", "'") + "\"}"));
                    emitter.complete();
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception sendErr) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    /**
     * 转换消息格式
     */
    private List<ChatMessage> convertMessages(List<GenericChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }

        return messages.stream()
                .map(msg -> {
                    switch (msg.getRole().toLowerCase()) {
                        case "system":
                            return ChatMessage.system(msg.getContent());
                        case "user":
                            return ChatMessage.user(msg.getContent());
                        case "assistant":
                            return ChatMessage.assistant(msg.getContent());
                        default:
                            throw new IllegalArgumentException("不支持的角色: " + msg.getRole());
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 提取响应内容
     */
    private String extractContent(ChatCompletionResponse response) {
        if (response != null) {
            // 优先使用 getContent() 方法（已处理普通和流式响应）
            String content = response.getContent();
            return content != null ? content : "";
        }
        return "";
    }

    /**
     * 提取使用的 Token 数
     */
    private Integer extractTokensUsed(ChatCompletionResponse response) {
        if (response != null && response.getUsage() != null) {
            return response.getUsage().getTotalTokens();
        }
        return null;
    }

    /**
     * 提取完成原因
     */
    private String extractFinishReason(ChatCompletionResponse response) {
        if (response != null &&
            response.getChoices() != null &&
            !response.getChoices().isEmpty()) {
            return response.getChoices().get(0).getFinishReason();
        }
        return null;
    }
}
