package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolutionLane;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationManifest;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationManifestLoader;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorCatalog;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishListTool;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyToolMigrationRegistryTest {

    private ToolRegistry toolRegistry;
    private FactoryToolConfigRepository factoryToolConfigRepository;
    private LegacyToolMigrationRegistry registry;
    private ToolExecutor dishListTool;
    private ExecutionPrincipal restaurantUser;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        factoryToolConfigRepository = mock(FactoryToolConfigRepository.class);
        LegacyToolMigrationManifest manifest =
                new LegacyToolMigrationManifestLoader().loadDefault();
        registry = new LegacyToolMigrationRegistry(
                toolRegistry,
                factoryToolConfigRepository,
                ToolDescriptorCatalog.loadDefault(),
                manifest,
                RuntimeToolDescriptorRegistry.loadDefault(),
                true);
        dishListTool = new RestaurantDishListTool();
        restaurantUser = userPrincipal("RESTAURANT");
        when(toolRegistry.getExecutor("restaurant_dish_list"))
                .thenReturn(Optional.of(dishListTool));
        when(factoryToolConfigRepository.findByFactoryIdAndToolName(
                "R-1", "restaurant_dish_list"))
                .thenReturn(Optional.empty());
    }

    @Test
    void resolvesOnlyExactManifestInventoryAndRuntimeIntersection() {
        ToolExecutionCommand command = command(
                restaurantUser,
                ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty());

        var resolved = registry.resolve(command, restaurantUser);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().lane())
                .isEqualTo(ResolutionLane.LEGACY_INTENT_DISPATCH_MIGRATION);
        assertThat(resolved.orElseThrow().descriptor().provenance())
                .isEqualTo(DescriptorProvenance.LEGACY_INFERRED);
        assertThat(resolved.orElseThrow().descriptor().egressPolicy())
                .isEqualTo(ToolEgressPolicy.denyAll());
        assertThat(resolved.orElseThrow().executor()).isSameAs(dishListTool);
    }

    @Test
    void rejectsWrongBusinessTypePrincipalSourceModeAndProofs() {
        ExecutionPrincipal factoryUser = userPrincipal("FACTORY");
        ExecutionPrincipal servicePrincipal = new ExecutionPrincipal(
                "R-1", "RESTAURANT", "svc-1", PrincipalType.SERVICE,
                Set.of(), Set.of(), Set.of("tool:execute"));
        ConfirmationProof proof = new ConfirmationProof(
                "token", "digest", Instant.now().plusSeconds(60));

        assertThat(registry.resolve(command(
                factoryUser, ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE, Optional.empty(), Optional.empty()), factoryUser))
                .isEmpty();
        assertThat(registry.resolve(command(
                servicePrincipal, ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE, Optional.empty(), Optional.empty()), servicePrincipal))
                .isEmpty();
        assertThat(registry.resolve(command(
                restaurantUser, ToolExecutionSource.AI_CHAT,
                ToolExecutionMode.EXECUTE, Optional.empty(), Optional.empty()), restaurantUser))
                .isEmpty();
        assertThat(registry.resolve(command(
                restaurantUser, ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.PREVIEW, Optional.empty(), Optional.empty()), restaurantUser))
                .isEmpty();
        assertThat(registry.resolve(command(
                restaurantUser, ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE, Optional.of("idem"), Optional.empty()), restaurantUser))
                .isEmpty();
        assertThat(registry.resolve(command(
                restaurantUser, ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE, Optional.empty(), Optional.of(proof)), restaurantUser))
                .isEmpty();
    }

    @Test
    void factoryDisableIsFailClosed() {
        when(factoryToolConfigRepository.findByFactoryIdAndToolName(
                "R-1", "restaurant_dish_list"))
                .thenReturn(Optional.of(FactoryToolConfig.builder()
                        .factoryId("R-1")
                        .toolName("restaurant_dish_list")
                        .enabled(false)
                        .build()));

        assertThat(registry.resolve(command(
                restaurantUser,
                ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty()), restaurantUser)).isEmpty();
    }

    @Test
    void disabledKillSwitchRejectsDirectGatewayResolution() {
        LegacyToolMigrationRegistry disabledRegistry = new LegacyToolMigrationRegistry(
                toolRegistry,
                factoryToolConfigRepository,
                ToolDescriptorCatalog.loadDefault(),
                new LegacyToolMigrationManifestLoader().loadDefault(),
                RuntimeToolDescriptorRegistry.loadDefault(),
                false);

        assertThat(disabledRegistry.resolve(command(
                restaurantUser,
                ToolExecutionSource.AI_INTENT_DISPATCH,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty()), restaurantUser)).isEmpty();
    }

    @Test
    void approvedRuntimePolicySetIncludesInventoryWorkflowAndRemainsRestaurantDisjoint() {
        RuntimeToolDescriptorRegistry approved = RuntimeToolDescriptorRegistry.loadDefault();

        assertThat(approved.approvedToolNames()).hasSize(10);
        assertThat(approved.approvedToolNames()).contains(
                "material_stock_summary",
                "material_batch_query",
                "material_expired_query");
        assertThat(approved.approvedToolNames())
                .doesNotContain(
                        "restaurant_dish_list",
                        "restaurant_ingredient_stock",
                        "restaurant_order_statistics");
    }

    private static ExecutionPrincipal userPrincipal(String businessType) {
        return new ExecutionPrincipal(
                "R-1", businessType, "42", PrincipalType.USER,
                Set.of("restaurant_manager"), Set.of(), Set.of());
    }

    private static ToolExecutionCommand command(
            ExecutionPrincipal principal,
            ToolExecutionSource source,
            ToolExecutionMode mode,
            Optional<String> idempotencyKey,
            Optional<ConfirmationProof> confirmationProof) {
        return new ToolExecutionCommand(
                "request-1",
                "correlation-1",
                "trace-1",
                "restaurant_dish_list",
                "1.0.0",
                JsonNodeFactory.instance.objectNode(),
                principal,
                source,
                mode,
                idempotencyKey,
                confirmationProof,
                Optional.empty(),
                Instant.now().plusSeconds(30));
    }
}
