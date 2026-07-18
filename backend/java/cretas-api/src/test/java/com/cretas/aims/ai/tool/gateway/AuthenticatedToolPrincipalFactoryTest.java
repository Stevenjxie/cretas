package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedToolPrincipalFactoryTest {

    private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
    private final AuthenticatedToolPrincipalFactory factory =
            new AuthenticatedToolPrincipalFactory(factoryRepository);

    @Test
    void preservesExactBranchBusinessTypeAndBuildsUserAssertion() {
        Factory tenant = tenant("BR-1", FactoryType.BRANCH, true);
        when(factoryRepository.findById("BR-1")).thenReturn(Optional.of(tenant));

        ExecutionPrincipal principal = factory.create("BR-1", 42L, "restaurant_owner");

        assertThat(principal.tenantId()).isEqualTo("BR-1");
        assertThat(principal.businessType()).isEqualTo("BRANCH");
        assertThat(principal.principalType()).isEqualTo(PrincipalType.USER);
        assertThat(principal.principalId()).isEqualTo("42");
        assertThat(principal.roles()).containsExactly("restaurant_owner");
        assertThat(principal.permissions()).isEmpty();
    }

    @Test
    void failsClosedForMissingInactiveOrIncompleteIdentity() {
        when(factoryRepository.findById("missing")).thenReturn(Optional.empty());
        when(factoryRepository.findById("inactive"))
                .thenReturn(Optional.of(tenant("inactive", FactoryType.RESTAURANT, false)));

        assertThatThrownBy(() -> factory.create("missing", 42L, "owner"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> factory.create("inactive", 42L, "owner"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> factory.create("BR-1", null, "owner"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> factory.create("BR-1", 0L, "owner"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> factory.create("BR-1", 42L, " "))
                .isInstanceOf(SecurityException.class);
    }

    private Factory tenant(String id, FactoryType type, boolean active) {
        Factory tenant = new Factory();
        tenant.setId(id);
        tenant.setType(type);
        tenant.setIsActive(active);
        return tenant;
    }
}
