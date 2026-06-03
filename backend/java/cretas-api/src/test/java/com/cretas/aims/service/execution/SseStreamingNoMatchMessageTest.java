package com.cretas.aims.service.execution;

import com.cretas.aims.dto.intent.IntentMatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1b: the streaming no-match reply must surface a negation VETO_READ clarificationQuestion
 * instead of the generic "我没有理解您的意图" — so the RN streaming chat reply matches the
 * deliberate clarification, not a "didn't understand" message.
 */
class SseStreamingNoMatchMessageTest {

    @Test
    void surfacesClarificationQuestion_whenNoConversationMessage() {
        IntentMatchResult r = IntentMatchResult.builder()
                .bestMatch(null)
                .clarificationQuestion("您是要取消这次操作吗?需要我帮您查询或处理什么?")
                .build();
        assertThat(SseStreamingService.selectNoMatchStreamMessage(r))
                .isEqualTo("您是要取消这次操作吗?需要我帮您查询或处理什么?");
    }

    @Test
    void conversationMessageTakesPriorityOverClarification() {
        IntentMatchResult r = IntentMatchResult.builder()
                .conversationMessage("继续上一步操作")
                .clarificationQuestion("您是要取消这次操作吗?")
                .build();
        assertThat(SseStreamingService.selectNoMatchStreamMessage(r)).isEqualTo("继续上一步操作");
    }

    @Test
    void genericMessage_whenNeitherSet() {
        IntentMatchResult r = IntentMatchResult.builder().bestMatch(null).build();
        assertThat(SseStreamingService.selectNoMatchStreamMessage(r))
                .isEqualTo("我没有理解您的意图，请更详细地描述您的需求。");
    }

    @Test
    void genericMessage_whenClarificationBlank() {
        IntentMatchResult r = IntentMatchResult.builder().clarificationQuestion("").build();
        assertThat(SseStreamingService.selectNoMatchStreamMessage(r))
                .isEqualTo("我没有理解您的意图，请更详细地描述您的需求。");
    }

    // ── noMatchCompleteStatus ──────────────────────────────────────────────

    @Test
    void completeStatus_isNeedClarification_whenClarificationQuestionPresent() {
        IntentMatchResult r = IntentMatchResult.builder()
                .clarificationQuestion("您是要取消这次操作吗?")
                .build();
        assertThat(SseStreamingService.noMatchCompleteStatus(r)).isEqualTo("NEED_CLARIFICATION");
    }

    @Test
    void completeStatus_isNoMatch_whenNeitherMessageSet() {
        IntentMatchResult r = IntentMatchResult.builder().bestMatch(null).build();
        assertThat(SseStreamingService.noMatchCompleteStatus(r)).isEqualTo("NO_MATCH");
    }

    @Test
    void completeStatus_isNoMatch_whenClarificationBlank() {
        IntentMatchResult r = IntentMatchResult.builder().clarificationQuestion("").build();
        assertThat(SseStreamingService.noMatchCompleteStatus(r)).isEqualTo("NO_MATCH");
    }

    @Test
    void completeStatus_isNeedClarification_whenConversationMessageSetButClarificationAlsoPresent() {
        // conversationMessage does not affect complete status — only clarificationQuestion decides
        IntentMatchResult r = IntentMatchResult.builder()
                .conversationMessage("继续上一步")
                .clarificationQuestion("您是要取消吗?")
                .build();
        assertThat(SseStreamingService.noMatchCompleteStatus(r)).isEqualTo("NEED_CLARIFICATION");
    }
}
