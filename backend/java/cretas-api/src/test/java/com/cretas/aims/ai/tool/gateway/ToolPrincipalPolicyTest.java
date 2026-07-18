package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolPrincipalPolicyTest {

    @Mock UserRepository userRepository;
    @Mock FactoryRepository factoryRepository;
    @Mock PermissionService permissionService;

    ToolPrincipalPolicy policy;
    User user;
    Factory factory;

    @BeforeEach
    void setUp() {
        policy = new ToolPrincipalPolicy(userRepository, factoryRepository, permissionService);
        user = new User();
        user.setId(42L);
        user.setFactoryId("F-REST");
        user.setIsActive(true);
        user.setRoleCode("restaurant_manager");
        factory = new Factory();
        factory.setId("F-REST");
        factory.setIsActive(true);
        factory.setType(FactoryType.RESTAURANT);
    }

    @Test
    void reloadsIdentityAndPermissionsAndIgnoresCommandClaims() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F-REST")).thenReturn(Optional.of(factory));
        when(permissionService.getUserPermissions(user))
                .thenReturn(Set.of("restaurant:read_write"));

        ExecutionPrincipal asserted = principal(
                "F-REST", "restaurant", Set.of("ADMIN"), Set.of("*:admin"));
        ToolPrincipalPolicy.RehydratedPrincipal current = policy.rehydrate(asserted).orElseThrow();

        assertThat(current.principal().tenantId()).isEqualTo("F-REST");
        assertThat(current.principal().businessType()).isEqualTo("RESTAURANT");
        assertThat(current.principal().roles()).containsExactly("restaurant_manager");
        assertThat(current.principal().permissions()).containsExactly("restaurant:read_write");
        assertThat(current.executionContext())
                .containsEntry("factoryId", "F-REST")
                .containsEntry("businessType", "RESTAURANT")
                .containsEntry("userId", 42L)
                .doesNotContainKey("username");
    }

    @Test
    void rejectsBusinessTypeTenantActiveAndNonUserMismatches() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(factoryRepository.findById("F-REST")).thenReturn(Optional.of(factory));

        assertThat(policy.rehydrate(principal(
                "F-REST", "FACTORY", Set.of("role"), Set.of()))).isEmpty();
        assertThat(policy.rehydrate(principal(
                "OTHER", "RESTAURANT", Set.of("role"), Set.of()))).isEmpty();
        assertThat(policy.rehydrate(new ExecutionPrincipal(
                "F-REST", "RESTAURANT", "service-1", PrincipalType.SERVICE,
                Set.of(), Set.of(), Set.of("tool.execute")))).isEmpty();

        user.setIsActive(false);
        assertThat(policy.rehydrate(principal(
                "F-REST", "RESTAURANT", Set.of("role"), Set.of()))).isEmpty();
    }

    private static ExecutionPrincipal principal(
            String tenant,
            String businessType,
            Set<String> roles,
            Set<String> permissions) {
        return new ExecutionPrincipal(
                tenant, businessType, "42", PrincipalType.USER, roles, permissions, Set.of());
    }
}
