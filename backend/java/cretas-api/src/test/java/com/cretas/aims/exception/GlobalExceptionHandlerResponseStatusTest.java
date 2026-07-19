package com.cretas.aims.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerResponseStatusTest {

    @Test
    void controlledResponseStatusExceptionPreservesHttpStatusAndReason() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(get("/test/runtime-off"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("RESTAURANT_AGENT_RUNTIME_OFF"));
    }

    @Test
    void controlledResponseStatusExceptionPreservesHeadersAndHidesCauseWhenReasonIsNull() throws Exception {
        MockMvc mockMvc = mockMvc();

        String body = mockMvc.perform(get("/test/header-and-blank-reason"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("请求处理失败"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("SENSITIVE_CAUSE_DETAIL"));
    }

    @Test
    void businessExceptionStillUsesItsSpecificHandler() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("BUSINESS_CONFLICT"));
    }

    @Test
    void ordinaryRuntimeExceptionStillUsesSanitizedFallback() throws Exception {
        MockMvc mockMvc = mockMvc();

        String body = mockMvc.perform(get("/test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(500))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("SENSITIVE_RUNTIME_DETAIL"));
    }

    private MockMvc mockMvc() {
        return standaloneSetup(new StatusController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    private static class StatusController {

        @GetMapping("/test/runtime-off")
        void runtimeOff() {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RESTAURANT_AGENT_RUNTIME_OFF");
        }

        @GetMapping("/test/header-and-blank-reason")
        void headerAndBlankReason() {
            throw new HeaderStatusException();
        }

        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(409, "BUSINESS_CONFLICT");
        }

        @GetMapping("/test/runtime")
        void runtime() {
            throw new RuntimeException("SENSITIVE_RUNTIME_DETAIL");
        }
    }

    private static class HeaderStatusException extends ResponseStatusException {

        private final HttpHeaders headers = new HttpHeaders();

        HeaderStatusException() {
            super(
                    HttpStatus.TOO_MANY_REQUESTS,
                    null,
                    new IllegalStateException("SENSITIVE_CAUSE_DETAIL"));
            headers.set(HttpHeaders.RETRY_AFTER, "30");
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
