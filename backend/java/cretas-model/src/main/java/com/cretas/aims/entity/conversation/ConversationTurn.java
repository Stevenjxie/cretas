package com.cretas.aims.entity.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record representing one turn in a conversation.
 *
 * <p>Fields:
 * <ul>
 *   <li>userMessage: 用户原始自然语言输入</li>
 *   <li>intentCode: 识别到的意图 (可为 null — 意图识别前记录, 或 fallback 到 LLM)</li>
 *   <li>toolName: 执行的 tool 名 (可为 null — 若走 skill 编排而非单 tool)</li>
 *   <li>skillName: 执行的 skill 名 (可为 null)</li>
 *   <li>response: tool/skill 返回的结构化结果 (可为 null)</li>
 *   <li>timestamp: epoch millis</li>
 * </ul>
 *
 * <p>Part of P4 Task 4.1. Used by the Tool-Skill platform's conversation
 * state service to store recent turns for multi-turn dialogue support.
 */
public record ConversationTurn(
        @JsonProperty("userMessage") String userMessage,
        @JsonProperty("intentCode") String intentCode,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("skillName") String skillName,
        @JsonProperty("response") Map<String, Object> response,
        @JsonProperty("timestamp") long timestamp
) {
    /** Jackson constructor — explicit for clarity. */
    @JsonCreator
    public ConversationTurn {}

    /** Convenience factory: turn with user message only, no tool/skill yet. */
    public static ConversationTurn userOnly(String message) {
        return new ConversationTurn(
                message,
                null,
                null,
                null,
                null,
                Instant.now().toEpochMilli()
        );
    }
}
