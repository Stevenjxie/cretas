package com.cretas.aims.controller;

import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.AIAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AIControllerConversationContractTest {

    private AIAnalysisService legacyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AIController controller = new AIController();
        legacyService = mock(AIAnalysisService.class);
        ReflectionTestUtils.setField(controller, "basicAIService", legacyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void conversationListReturnsExplicitFeatureUnavailable() throws Exception {
        mockMvc.perform(get("/api/mobile/FACTORY-1/ai/conversations")
                        .param("limit", "10"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(501))
                .andExpect(jsonPath("$.message").value("FEATURE_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(legacyService);
    }

    @Test
    void conversationHistoryReturns501WithoutCallingLegacyWrapper() throws Exception {
        mockMvc.perform(get("/api/mobile/FACTORY-1/ai/conversations/session-1"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(501))
                .andExpect(jsonPath("$.message").value("FEATURE_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(legacyService);
    }
}
