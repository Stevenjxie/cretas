package com.cretas.aims.config.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOpsRuntimeShadowRolloutPolicyTest {

    @Test
    void defaultAndIncompleteConfigurationFailClosed() {
        AgentOpsRuntimeShadowRolloutPolicy policy = new AgentOpsRuntimeShadowRolloutPolicy();
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.MASTER_DISABLED);

        policy.setEnabled(true);
        policy.setSampleBps(10_000);
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);

        policy.setFactoryAllowlist("R001");
        policy.setRoleAllowlist("platform_admin");
        policy.setRolloutSalt(" ");
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);

        policy.setRolloutSalt("runtime-shadow-v1");
        policy.setSampleBps(10_001);
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);
    }

    @Test
    void allowlistsAreExplicitAndSupportRoleNormalizationAndWildcard() {
        AgentOpsRuntimeShadowRolloutPolicy policy = eligiblePolicy();
        policy.setFactoryAllowlist("R001,R002");
        policy.setRoleAllowlist("restaurant_owner, PLATFORM_ADMIN");

        assertThat(policy.evaluate("R001", "42", "platform_ADMIN"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.ELIGIBLE);
        assertThat(policy.evaluate("R999", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);
        assertThat(policy.evaluate("R001", "42", "operator"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);

        policy.setFactoryAllowlist("*");
        policy.setRoleAllowlist("*");
        assertThat(policy.evaluate("DEMO_REST", "42", "restaurant_owner"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.ELIGIBLE);
    }

    @Test
    void stableBucketMatchesCrossLanguageContractAndThresholdIsExclusive() {
        assertThat(AgentOpsRuntimeShadowRolloutPolicy.stableBucket(
                "R001", "42", "platform_admin", "runtime-shadow-v1")).isEqualTo(1167);
        assertThat(AgentOpsRuntimeShadowRolloutPolicy.stableBucket(
                "DEMO_REST", "1309", "restaurant_owner", "runtime-shadow-v1")).isEqualTo(2144);
        assertThat(AgentOpsRuntimeShadowRolloutPolicy.stableBucket(
                "F006", "1309", "factory_super_admin", "runtime-shadow-v1")).isEqualTo(2008);
        assertThat(AgentOpsRuntimeShadowRolloutPolicy.stableBucket(
                "R001", "42", "platform_admin", " runtime-shadow-v1 ")).isEqualTo(1167);

        AgentOpsRuntimeShadowRolloutPolicy policy = eligiblePolicy();
        policy.setFactoryAllowlist("R001");
        policy.setRoleAllowlist("platform_admin");
        policy.setSampleBps(1167);
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);
        policy.setSampleBps(1168);
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.ELIGIBLE);
    }

    @Test
    void rolloutMetricsUseOnlyBoundedDecisionTags() {
        AgentOpsRuntimeShadowRolloutPolicy policy = eligiblePolicy();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        policy.setMeterRegistry(registry);

        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.ELIGIBLE);
        policy.setFactoryAllowlist("R002");
        assertThat(policy.evaluate("R001", "42", "platform_admin"))
                .isEqualTo(AgentOpsRuntimeShadowRolloutPolicy.Decision.CANARY_DENIED);

        assertThat(registry.get("agent.ops.runtime.shadow.rollout")
                .tag("decision", "eligible").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("agent.ops.runtime.shadow.rollout")
                .tag("decision", "canary_denied").counter().count()).isEqualTo(1.0d);
    }

    private AgentOpsRuntimeShadowRolloutPolicy eligiblePolicy() {
        AgentOpsRuntimeShadowRolloutPolicy policy = new AgentOpsRuntimeShadowRolloutPolicy();
        policy.setEnabled(true);
        policy.setFactoryAllowlist("*");
        policy.setRoleAllowlist("*");
        policy.setSampleBps(10_000);
        policy.setRolloutSalt("runtime-shadow-v1");
        return policy;
    }
}
