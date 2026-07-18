package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Joins approved runtime policy to the exact registered Spring Tool implementation. */
@Component
public class ToolRuntimeRegistry {

    private final ToolRegistry toolRegistry;
    private final FactoryToolConfigRepository factoryToolConfigRepository;
    private final RuntimeToolDescriptorRegistry descriptorRegistry;
    private final ToolDescriptorPolicyResolver policyResolver;

    public ToolRuntimeRegistry(
            ToolRegistry toolRegistry,
            FactoryToolConfigRepository factoryToolConfigRepository) {
        this(toolRegistry, factoryToolConfigRepository, RuntimeToolDescriptorRegistry.loadDefault());
    }

    ToolRuntimeRegistry(
            ToolRegistry toolRegistry,
            FactoryToolConfigRepository factoryToolConfigRepository,
            RuntimeToolDescriptorRegistry descriptorRegistry) {
        this.toolRegistry = toolRegistry;
        this.factoryToolConfigRepository = factoryToolConfigRepository;
        this.descriptorRegistry = descriptorRegistry;
        this.policyResolver = new ToolDescriptorPolicyResolver(descriptorRegistry);
    }

    public Optional<ResolvedTool> resolve(
            ToolExecutionCommand command,
            ExecutionPrincipal trustedCurrentPrincipal) {
        try {
            Optional<ToolDescriptor> descriptorOptional =
                    policyResolver.resolve(command, trustedCurrentPrincipal);
            if (descriptorOptional.isEmpty()) {
                return Optional.empty();
            }
            ToolDescriptor descriptor = descriptorOptional.get();
            Optional<ToolExecutor> executorOptional = toolRegistry.getExecutor(command.toolName());
            Optional<String> implementationOptional =
                    descriptorRegistry.approvedImplementationClass(command.toolName());
            if (executorOptional.isEmpty() || implementationOptional.isEmpty()) {
                return Optional.empty();
            }
            ToolExecutor executor = executorOptional.get();
            String targetClassName = AopUtils.getTargetClass(executor).getName();
            if (!implementationOptional.get().equals(targetClassName)
                    || !executor.isEnabled()
                    || !descriptor.toolName().equals(executor.getToolName())
                    || !descriptor.version().equals(executor.getVersion())
                    || descriptor.actionType() != executor.getActionType()
                    || descriptor.riskLevel() != executor.getRiskLevel()
                    || descriptor.supportsPreview() != executor.supportsPreview()
                    || !descriptor.requiredPermissions().equals(executor.getRequiredPermissions())
                    || !descriptor.domainTags().equals(executor.getDomainTags())
                    || !executor.requiresPermission()
                    || !hasExactRoleBehavior(descriptor, executor)) {
                return Optional.empty();
            }

            Optional<FactoryToolConfig> factoryOverride =
                    factoryToolConfigRepository.findByFactoryIdAndToolName(
                            trustedCurrentPrincipal.tenantId(), command.toolName());
            if (factoryOverride.isPresent()
                    && !Boolean.TRUE.equals(factoryOverride.get().getEnabled())) {
                return Optional.empty();
            }
            // No override row deliberately inherits the globally registered/enabled Tool state.
            return Optional.of(new ResolvedTool(descriptor, executor));
        } catch (RuntimeException unavailableRuntimeTruth) {
            return Optional.empty();
        }
    }

    public record ResolvedTool(ToolDescriptor descriptor, ToolExecutor executor) {
    }

    private static boolean hasExactRoleBehavior(
            ToolDescriptor descriptor,
            ToolExecutor executor) {
        for (FactoryUserRole role : FactoryUserRole.values()) {
            if (executor.hasPermission(role.name())
                    != descriptor.allowedRoles().contains(role.name())) {
                return false;
            }
        }
        return !executor.hasPermission(null) && !executor.hasPermission("unknown_role");
    }
}
