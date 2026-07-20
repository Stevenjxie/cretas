package com.cretas.aims.ai.capability;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FactoryCapabilityPackRoutingPolicyTest {

    @Test
    void defaultDisabledDoesNotReadFactoryTruth() {
        FactoryRepository repository = mock(FactoryRepository.class);
        FactoryCapabilityPackRoutingPolicy policy = policy(repository, false);

        FactoryCapabilityPackRoutingPolicy.Route route = policy.evaluate(
                "F001", "operator", "查看批次进度");

        assertThat(route.status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.RouteStatus.DISABLED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("fourPackRoles")
    void selectsAllFourPackRolesFromOneServerSideFactoryRead(
            String role, String query, String expectedPackId) {
        FactoryRepository repository = mock(FactoryRepository.class);
        when(repository.findById("F001")).thenReturn(Optional.of(factory(FactoryType.FACTORY)));
        FactoryCapabilityPackRoutingPolicy policy = policy(repository, true);

        FactoryCapabilityPackRoutingPolicy.Route route = policy.evaluate("F001", role, query);

        assertThat(route.isConstrained()).isTrue();
        assertThat(route.pack().packId()).isEqualTo(expectedPackId);
        verify(repository, times(1)).findById("F001");
    }

    @Test
    void supportsCentralKitchenButExplicitlyExcludesRestaurant() {
        FactoryRepository repository = mock(FactoryRepository.class);
        when(repository.findById("CK001"))
                .thenReturn(Optional.of(factory(FactoryType.CENTRAL_KITCHEN)));
        when(repository.findById("R001"))
                .thenReturn(Optional.of(factory(FactoryType.RESTAURANT)));
        FactoryCapabilityPackRoutingPolicy policy = policy(repository, true);

        FactoryCapabilityPackRoutingPolicy.Route central = policy.evaluate(
                "CK001", "warehouse_worker", "查看库存和过期批次");
        FactoryCapabilityPackRoutingPolicy.Route restaurant = policy.evaluate(
                "R001", "warehouse_worker", "查看库存和过期批次");

        assertThat(central.isConstrained()).isTrue();
        assertThat(central.factoryType()).isEqualTo(FactoryType.CENTRAL_KITCHEN);
        assertThat(restaurant.status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.RouteStatus.NOT_APPLICABLE);
        assertThat(restaurant.reason()).isEqualTo("restaurant-excluded");
        verify(repository, times(1)).findById("CK001");
        verify(repository, times(1)).findById("R001");
    }

    @Test
    void matchTermsOnlyEnterDomainThenRecognizerSelectionIsIndependentlyConstrained() {
        FactoryRepository repository = mock(FactoryRepository.class);
        when(repository.findById("F001")).thenReturn(Optional.of(factory(FactoryType.FACTORY)));
        FactoryCapabilityPackRoutingPolicy policy = policy(repository, true);

        FactoryCapabilityPackRoutingPolicy.Route route = policy.evaluate(
                "F001", "operator", "查看批次进度");

        assertThat(policy.authorize(
                route, "PROCESSING_BATCH_DETAIL", "processing_batch_detail", false).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.ALLOW_READ);
        assertThat(policy.authorize(
                route, "REPORT_INVENTORY", "report_inventory", false).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.NO_MATCH);
        assertThat(policy.authorize(
                route, "PRODUCTION_REPORT", "production_report_submit", true).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.GUIDANCE);
        assertThat(policy.authorize(
                route, "PROCESSING_BATCH_START", "processing_batch_start", true).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.GUIDANCE);
        assertThat(policy.authorize(
                route, "UNDECLARED_WRITE", "batch_complete", true).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.NO_MATCH);

        FactoryCapabilityPackRoutingPolicy.Route managerRoute = policy.evaluate(
                "F001", "dispatcher", "查看生产看板");
        assertThat(policy.authorize(
                managerRoute, "PRODUCTION_DASHBOARD", null, false).status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.ExecutionStatus.GUIDANCE);
    }

    @Test
    void packExternalQueryAndUntrustedRoleDoNotFallIntoThePack() {
        FactoryRepository repository = mock(FactoryRepository.class);
        when(repository.findById("F001")).thenReturn(Optional.of(factory(FactoryType.FACTORY)));
        FactoryCapabilityPackRoutingPolicy policy = policy(repository, true);

        FactoryCapabilityPackRoutingPolicy.Route outside = policy.evaluate(
                "F001", "quality_inspector", "审批付款");
        FactoryCapabilityPackRoutingPolicy.Route untrusted = policy.evaluate(
                "F001", "QUALITY_INSPECTOR", "查看质检");

        assertThat(outside.shouldBlock()).isTrue();
        assertThat(outside.reason()).isEqualTo("outside-pack-domain");
        assertThat(untrusted.status())
                .isEqualTo(FactoryCapabilityPackRoutingPolicy.RouteStatus.NOT_APPLICABLE);
        verify(repository, times(1)).findById("F001");
        verify(repository, never()).findById("");
    }

    private static Stream<Arguments> fourPackRoles() {
        return Stream.of(
                Arguments.of("operator", "查看批次进度", "factory.operator"),
                Arguments.of("warehouse_worker", "查看库存和过期批次", "factory.warehouse"),
                Arguments.of("quality_inspector", "查看待检关键项", "factory.quality"),
                Arguments.of("dispatcher", "查看生产质量异常看板", "factory.manager"));
    }

    private FactoryCapabilityPackRoutingPolicy policy(
            FactoryRepository repository, boolean enabled) {
        FactoryCapabilityPackSelector selector = new FactoryCapabilityPackSelector(
                new FactoryCapabilityPackRegistry());
        return new FactoryCapabilityPackRoutingPolicy(repository, selector, enabled);
    }

    private Factory factory(FactoryType type) {
        Factory factory = new Factory();
        factory.setId("test-factory");
        factory.setType(type);
        factory.setIsActive(true);
        return factory;
    }
}
