package com.cretas.aims.controller;

import com.cretas.aims.controller.ConversationController.ConfirmIntentRequest;
import com.cretas.aims.controller.ConversationController.ReplyRequest;
import com.cretas.aims.controller.ConversationController.StartConversationRequest;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationControllerIdentityContractTest {

    private static final String FACTORY_ID = "F-CONVERSATION";
    private static final Long USER_ID = 42L;

    private ConversationService conversationService;
    private ConversationController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        controller = new ConversationController(conversationService);
    }

    @Test
    void startUsesOnlyTrustedRequestAttributeAndRequestDtoHasNoUserId() {
        ConversationService.ConversationResponse expected =
                ConversationService.ConversationResponse.builder()
                        .sessionId("session-1")
                        .status(ConversationSession.SessionStatus.ACTIVE)
                        .build();
        when(conversationService.startConversation(FACTORY_ID, USER_ID, "hello"))
                .thenReturn(expected);

        var response = controller.startConversation(
                FACTORY_ID,
                StartConversationRequest.builder().userInput("hello").build(),
                requestWithUser(USER_ID));

        assertThat(response.getBody()).containsEntry("data", expected);
        assertThat(StartConversationRequest.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("userId");
        verify(conversationService).startConversation(FACTORY_ID, USER_ID, "hello");
    }

    @Test
    void clientHeaderAndQueryCannotReplaceMissingTrustedIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "999");
        request.addParameter("userId", "999");

        assertUnauthorized(() -> controller.startConversation(
                FACTORY_ID,
                StartConversationRequest.builder().userInput("hello").build(),
                request));

        verifyNoInteractions(conversationService);
    }

    @Test
    void allSessionEndpointsRejectMissingIdentityBeforeServiceOrAsyncWork() {
        HttpServletRequest missing = new MockHttpServletRequest();
        StartConversationRequest start = StartConversationRequest.builder().userInput("hello").build();
        ReplyRequest reply = ReplyRequest.builder().userReply("next").build();
        ConfirmIntentRequest confirm = ConfirmIntentRequest.builder().intentCode("QUERY").build();

        assertUnauthorized(() -> controller.startConversationStream(FACTORY_ID, start, missing));
        assertUnauthorized(() -> controller.continueConversation(FACTORY_ID, "session-1", reply, missing));
        assertUnauthorized(() -> controller.continueConversationStream(
                FACTORY_ID, "session-1", reply, missing));
        assertUnauthorized(() -> controller.confirmIntent(
                FACTORY_ID, "session-1", confirm, missing));
        assertUnauthorized(() -> controller.cancelConversation(FACTORY_ID, "session-1", missing));
        assertUnauthorized(() -> controller.getSession(FACTORY_ID, "session-1", missing));
        assertUnauthorized(() -> controller.getActiveSession(FACTORY_ID, missing));

        verifyNoInteractions(conversationService);
    }

    @Test
    void positiveStringAttributeIsParsedAndForwarded() {
        when(conversationService.getSession(FACTORY_ID, USER_ID, "session-1"))
                .thenReturn(Optional.empty());

        controller.getSession(FACTORY_ID, "session-1", requestWithUser("42"));

        verify(conversationService).getSession(FACTORY_ID, USER_ID, "session-1");
    }

    @Test
    void zeroNegativeMalformedAndUnexpectedNumberTypesAreRejected() {
        assertUnauthorized(() -> controller.getActiveSession(FACTORY_ID, requestWithUser(0L)));
        assertUnauthorized(() -> controller.getActiveSession(FACTORY_ID, requestWithUser(-1L)));
        assertUnauthorized(() -> controller.getActiveSession(FACTORY_ID, requestWithUser("not-a-number")));
        assertUnauthorized(() -> controller.getActiveSession(FACTORY_ID, requestWithUser(42)));
        verifyNoInteractions(conversationService);
    }

    private MockHttpServletRequest requestWithUser(Object userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        return request;
    }

    private void assertUnauthorized(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
