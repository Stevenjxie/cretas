package com.cretas.aims.controller;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ConversationService.ConversationResponse;
import com.cretas.aims.service.ConversationService.ConversationStatistics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * 多轮对话控制器
 *
 * 提供 Layer 5 多轮对话模式的 REST API:
 * - 开始对话: POST /api/mobile/{factoryId}/conversation/start
 * - 继续对话: POST /api/mobile/{factoryId}/conversation/{sessionId}/reply
 * - 确认意图: POST /api/mobile/{factoryId}/conversation/{sessionId}/confirm
 * - 取消对话: POST /api/mobile/{factoryId}/conversation/{sessionId}/cancel
 * - 获取会话: GET /api/mobile/{factoryId}/conversation/{sessionId}
 * - 获取统计: GET /api/mobile/{factoryId}/conversation/stats
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/conversation")
@RequiredArgsConstructor
@Tag(name = "多轮对话", description = "Layer 5 多轮对话意图澄清 API")
public class ConversationController {

    private final ConversationService conversationService;
    private final ExecutorService sseExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "conversation-sse"); t.setDaemon(true); return t; });

    /**
     * 开始多轮对话
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/start")
    @Operation(summary = "开始多轮对话", description = "当意图识别置信度低于30%时触发多轮对话")
    public ResponseEntity<Map<String, Object>> startConversation(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Valid StartConversationRequest request,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.info("Starting conversation: factory={}, user={}, input='{}'",
                factoryId, userId, truncate(request.getUserInput(), 50));

        ConversationResponse response = conversationService.startConversation(
                factoryId, userId, request.getUserInput());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * 继续多轮对话
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/{sessionId}/reply")
    @Operation(summary = "继续多轮对话", description = "用户回复澄清问题，继续对话")
    public ResponseEntity<Map<String, Object>> continueConversation(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId,
            @RequestBody @Valid ReplyRequest request,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.info("Continuing conversation: session={}, reply='{}'",
                sessionId, truncate(request.getUserReply(), 50));

        ConversationResponse response = conversationService.continueConversation(
                factoryId, userId, sessionId, request.getUserReply());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * SSE 流式 — 开始多轮对话
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/start/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式开始多轮对话 (SSE)")
    public SseEmitter startConversationStream(
            @PathVariable String factoryId,
            @RequestBody @Valid StartConversationRequest request,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.onTimeout(() -> log.warn("Conversation stream timeout"));
        sseExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("processing")
                        .data("{\"message\":\"正在处理...\"}"));

                ConversationResponse response = conversationService.startConversation(
                        factoryId, userId, request.getUserInput());

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("data", response);
                emitter.send(SseEmitter.event().name("result")
                        .data(result));

                emitter.send(SseEmitter.event().name("done")
                        .data("{\"fullContent\":\"" +
                                (response.getMessage() != null ? response.getMessage().replace("\"", "'") : "") +
                                "\"}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"" + ErrorSanitizer.sanitize(e).replace("\"", "'") + "\"}"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /**
     * SSE 流式 — 继续多轮对话
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping(value = "/{sessionId}/reply/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式继续多轮对话 (SSE)")
    public SseEmitter continueConversationStream(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            @RequestBody @Valid ReplyRequest request,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.onTimeout(() -> log.warn("Conversation reply stream timeout"));

        sseExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("processing")
                        .data("{\"message\":\"正在处理...\"}"));

                ConversationResponse response = conversationService.continueConversation(
                        factoryId, userId, sessionId, request.getUserReply());

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("data", response);
                emitter.send(SseEmitter.event().name("result")
                        .data(result));

                emitter.send(SseEmitter.event().name("done")
                        .data("{\"fullContent\":\"" +
                                (response.getMessage() != null ? response.getMessage().replace("\"", "'") : "") +
                                "\"}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"" + ErrorSanitizer.sanitize(e).replace("\"", "'") + "\"}"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /**
     * 确认意图
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/{sessionId}/confirm")
    @Operation(summary = "确认意图", description = "用户确认识别的意图，结束对话并触发学习")
    public ResponseEntity<Map<String, Object>> confirmIntent(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId,
            @RequestBody @Valid ConfirmIntentRequest request,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.info("Confirming intent: session={}, intent={}", sessionId, request.getIntentCode());

        boolean success = conversationService.endConversation(
                factoryId, userId, sessionId, request.getIntentCode());

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "意图已确认，对话结束" : "确认失败");
        result.put("data", Map.of("intentCode", request.getIntentCode()));

        return ResponseEntity.ok(result);
    }

    /**
     * 取消对话
     */
    @RequirePermission({"analytics:read_write"})
    @PostMapping("/{sessionId}/cancel")
    @Operation(summary = "取消对话", description = "用户取消当前对话")
    public ResponseEntity<Map<String, Object>> cancelConversation(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.info("Cancelling conversation: session={}", sessionId);

        boolean success = conversationService.cancelConversation(factoryId, userId, sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "对话已取消" : "取消失败");

        return ResponseEntity.ok(result);
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/{sessionId}")
    @Operation(summary = "获取会话详情", description = "获取指定会话的详细信息")
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "会话ID") String sessionId,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.debug("Getting session: session={}", sessionId);

        var session = conversationService.getSession(factoryId, userId, sessionId);

        Map<String, Object> result = new HashMap<>();
        if (session.isPresent()) {
            result.put("success", true);
            result.put("data", convertToDto(session.get()));
        } else {
            result.put("success", false);
            result.put("message", "会话不存在");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取活跃会话
     */
    @GetMapping("/active")
    @Operation(summary = "获取活跃会话", description = "获取当前用户的活跃会话（如果存在）")
    public ResponseEntity<Map<String, Object>> getActiveSession(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            HttpServletRequest httpRequest) {

        Long userId = requireAuthenticatedUserId(httpRequest);

        log.debug("Getting active session: factory={}, user={}", factoryId, userId);

        var session = conversationService.getActiveSession(factoryId, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (session.isPresent()) {
            result.put("hasActiveSession", true);
            result.put("data", convertToDto(session.get()));
        } else {
            result.put("hasActiveSession", false);
            result.put("data", null);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取对话统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取对话统计", description = "获取多轮对话的统计数据")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "7") @Parameter(description = "统计天数") int days) {

        log.debug("Getting conversation statistics: factory={}, days={}", factoryId, days);

        ConversationStatistics stats = conversationService.getStatistics(factoryId, days);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", stats);

        return ResponseEntity.ok(result);
    }

    // ========== 请求 DTO ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartConversationRequest {
        @NotBlank(message = "用户输入不能为空")
        private String userInput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplyRequest {
        @NotBlank(message = "回复内容不能为空")
        private String userReply;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmIntentRequest {
        @NotBlank(message = "意图代码不能为空")
        private String intentCode;
    }

    // ========== 响应 DTO ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionDto {
        private String sessionId;
        private String factoryId;
        private Long userId;
        private String originalInput;
        private String finalIntentCode;
        private int currentRound;
        private int maxRounds;
        private String status;
        private Double lastConfidence;
        private java.util.List<ConversationSession.Message> messages;
        private java.util.List<ConversationSession.CandidateIntent> candidates;
        private String createdAt;
        private String lastActiveAt;
    }

    // ========== 工具方法 ==========

    private SessionDto convertToDto(ConversationSession session) {
        return SessionDto.builder()
                .sessionId(session.getSessionId())
                .factoryId(session.getFactoryId())
                .userId(session.getUserId())
                .originalInput(session.getOriginalInput())
                .finalIntentCode(session.getFinalIntentCode())
                .currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus().name())
                .lastConfidence(session.getLastConfidence())
                .messages(session.getMessages())
                .candidates(session.getCandidates())
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .lastActiveAt(session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null)
                .build();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private Long requireAuthenticatedUserId(HttpServletRequest request) {
        Object rawUserId = request.getAttribute("userId");
        Long userId = null;
        if (rawUserId instanceof Long value) {
            userId = value;
        } else if (rawUserId instanceof String value) {
            try {
                userId = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // Reject malformed trusted context below.
            }
        }
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "TRUSTED_USER_CONTEXT_REQUIRED");
        }
        return userId;
    }
}
