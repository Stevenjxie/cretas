package com.cretas.aims.config;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModuleEnabledInterceptorUserAccessTest {

    @Mock
    private FactoryConfigService factoryConfigService;

    @Mock
    private UserModuleAccessService userModuleAccessService;

    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ModuleEnabledInterceptor interceptor = new ModuleEnabledInterceptor(
                factoryConfigService,
                userModuleAccessService,
                userRepository,
                new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedModuleController())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("DENY user module access blocks direct API access after factory module is enabled")
    void denyBlocksDirectApiAccess() throws Exception {
        User user = user(1309L, "F006", "f006_admin", "factory_super_admin");
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);
        when(userRepository.findById(1309L)).thenReturn(Optional.of(user));
        when(userModuleAccessService.canAccessModule(user, "production_plan")).thenReturn(false);

        mockMvc.perform(asUser(get("/api/mobile/F006/test-protected/production-plan"), 1309L))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("f006_admin")))
                .andExpect(jsonPath("$.message", containsString("production_plan")))
                .andExpect(jsonPath("$.actionHint", containsString("管理员")));
    }

    @Test
    @DisplayName("Allowed user module access reaches controller")
    void allowedModuleAccessReachesController() throws Exception {
        User user = user(1310L, "F006", "planner", "dispatcher");
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);
        when(userRepository.findById(1310L)).thenReturn(Optional.of(user));
        when(userModuleAccessService.canAccessModule(user, "production_plan")).thenReturn(true);

        mockMvc.perform(asUser(get("/api/mobile/F006/test-protected/production-plan"), 1310L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    @DisplayName("Missing logged-in user is fail-closed for module-gated endpoint")
    void missingLoggedInUserFailsClosed() throws Exception {
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);

        mockMvc.perform(get("/api/mobile/F006/test-protected/production-plan"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verify(userModuleAccessService, never()).canAccessModule(org.mockito.Mockito.any(), org.mockito.Mockito.anyString());
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, long userId) {
        return builder.requestAttr("userId", userId);
    }

    private static User user(Long id, String factoryId, String username, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setRoleCode(roleCode);
        return user;
    }

    @RestController
    @RequestMapping("/api/mobile/{factoryId}/test-protected")
    static class ProtectedModuleController {
        @GetMapping("/production-plan")
        @RequireModule("production_plan")
        java.util.Map<String, Object> productionPlan(@PathVariable String factoryId) {
            return java.util.Map.of("ok", true, "factoryId", factoryId);
        }
    }
}
