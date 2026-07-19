package com.cretas.aims.controller.ai;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class FactoryCapabilityPackControllerTest {
    private MockMvc mvc;
    private ObjectMapper mapper;
    private FactoryRepository factoryRepository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        factoryRepository = mock(FactoryRepository.class);
        setFactoryType(FactoryType.FACTORY);
        FactoryCapabilityPackController controller = new FactoryCapabilityPackController(
                new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                new FactoryBusinessTypeResolver(factoryRepository));
        mvc = standaloneSetup(controller).build();
    }

    @Test
    void returnsSafeSummaryForTrustedExactFactoryPrincipal() throws Exception {
        String body = mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.pack.packId").value("factory.operator"))
                .andExpect(jsonPath("$.data.pack.digest").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "instructions", "readToolAllowlist", "fewShots", "evalCases",
                "factoryId", "userId");
        verify(factoryRepository).findById("F001");
    }

    @Test
    void missingPrincipalCrossFactoryRestaurantAndRoleMismatchFailClosed() throws Exception {
        mvc.perform(get("/api/mobile/F001/ai/capability-pack"))
                .andExpect(status().isUnauthorized());
        mvc.perform(trusted(get("/api/mobile/F002/ai/capability-pack"),
                        "F001", "operator"))
                .andExpect(status().isForbidden());
        setFactoryType(FactoryType.RESTAURANT);
        mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "operator").requestAttr("businessType", "FACTORY"))
                .andExpect(status().isForbidden());
        when(factoryRepository.findById("F001")).thenReturn(Optional.empty());
        mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "operator"))
                .andExpect(status().isForbidden());
        setFactoryType(FactoryType.FACTORY);
        mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "viewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyProductionManagerNormalizesOnlyToDispatcherForGetAndPost() throws Exception {
        mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "production_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pack.packId").value("factory.manager"));
        mvc.perform(trusted(post("/api/mobile/F001/ai/capability-pack/match")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(
                                        java.util.Map.of("query", "生产概览"))),
                        "F001", "production_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.pack.packId").value("factory.manager"));
        mvc.perform(trusted(get("/api/mobile/F001/ai/capability-pack"),
                        "F001", "legacy_manager"))
                .andExpect(status().isForbidden());
    }

    @Test
    void matchRejectsIdentityFieldsAndQueryParameterInjection() throws Exception {
        mvc.perform(trusted(post("/api/mobile/F001/ai/capability-pack/match")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"查看批次\",\"factoryId\":\"F002\"}"),
                        "F001", "operator"))
                .andExpect(status().isBadRequest());
        mvc.perform(trusted(post("/api/mobile/F001/ai/capability-pack/match?tenantId=F002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"query\":\"查看批次\"}"),
                        "F001", "operator"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noQueryMatchIsExplicitAndNotFabricated() throws Exception {
        mvc.perform(trusted(post("/api/mobile/F001/ai/capability-pack/match")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(
                                        java.util.Map.of("query", "审批付款"))),
                        "F001", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.pack").doesNotExist());
    }

    private MockHttpServletRequestBuilder trusted(
            MockHttpServletRequestBuilder request,
            String factoryId,
            String role) {
        return request
                .requestAttr("factoryId", factoryId)
                .requestAttr("userId", 42L)
                .requestAttr("role", role);
    }

    private void setFactoryType(FactoryType type) {
        Factory factory = new Factory();
        factory.setId("F001");
        factory.setType(type);
        factory.setIsActive(true);
        when(factoryRepository.findById("F001")).thenReturn(Optional.of(factory));
    }
}
