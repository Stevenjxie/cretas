package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolutionLane;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationManifest;
import com.cretas.aims.ai.tool.gateway.descriptor.LegacyToolMigrationManifestLoader;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorCatalog;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolGovernanceStatus;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves only the frozen D11B legacy migration allowlist.
 *
 * <p>Eligibility is the intersection of a separate manifest, the legacy audit inventory, the
 * current Spring executor, the current factory switch, and the exact command/principal envelope.
 * Any drift returns no resolution; callers must never fall back after selecting this lane.</p>
 */
@Component
public class LegacyToolMigrationRegistry {

    private final ToolRegistry toolRegistry;
    private final FactoryToolConfigRepository factoryToolConfigRepository;
    private final Map<String, LegacyToolMigrationEntry> entriesByName;
    private final boolean migrationEnabled;

    @Autowired
    public LegacyToolMigrationRegistry(
            ToolRegistry toolRegistry,
            FactoryToolConfigRepository factoryToolConfigRepository,
            @Value("${cretas.ai.tool-gateway.intent-dispatch-migration.enabled:false}")
            boolean migrationEnabled) {
        this(
                toolRegistry,
                factoryToolConfigRepository,
                ToolDescriptorCatalog.loadDefault(),
                new LegacyToolMigrationManifestLoader().loadDefault(),
                RuntimeToolDescriptorRegistry.loadDefault(),
                migrationEnabled);
    }

    LegacyToolMigrationRegistry(
            ToolRegistry toolRegistry,
            FactoryToolConfigRepository factoryToolConfigRepository,
            ToolDescriptorCatalog inventoryCatalog,
            LegacyToolMigrationManifest manifest,
            RuntimeToolDescriptorRegistry approvedRegistry,
            boolean migrationEnabled) {
        this.toolRegistry = toolRegistry;
        this.factoryToolConfigRepository = factoryToolConfigRepository;
        this.migrationEnabled = migrationEnabled;
        Map<String, LegacyToolMigrationEntry> entries = new LinkedHashMap<>();
        for (LegacyToolMigrationEntry entry : manifest.tools()) {
            ToolDescriptorInventoryEntry inventory = inventoryCatalog
                    .findByToolName(entry.toolName())
                    .orElseThrow(() -> drift(entry.toolName(), "missing inventory entry"));
            validateInventoryAlignment(entry, inventory);
            if (approvedRegistry.approvedToolNames().contains(entry.toolName())) {
                throw drift(entry.toolName(), "also present in approved runtime policy");
            }
            entries.put(entry.toolName(), entry);
        }
        this.entriesByName = Collections.unmodifiableMap(entries);
    }

    public boolean contains(String toolName) {
        return toolName != null && entriesByName.containsKey(toolName);
    }

    public Optional<String> expectedVersion(String toolName) {
        LegacyToolMigrationEntry entry = entriesByName.get(toolName);
        return entry == null ? Optional.empty() : Optional.of(entry.version());
    }

    public Optional<ResolvedTool> resolve(
            ToolExecutionCommand command,
            ExecutionPrincipal trustedCurrentPrincipal) {
        try {
            // Defense in depth: in-process callers cannot bypass the dispatch-layer flag.
            if (!migrationEnabled || !hasExactEnvelope(command, trustedCurrentPrincipal)) {
                return Optional.empty();
            }
            LegacyToolMigrationEntry entry = entriesByName.get(command.toolName());
            if (entry == null || !entry.version().equals(command.expectedDescriptorVersion())) {
                return Optional.empty();
            }
            FactoryType businessType = FactoryType.valueOf(
                    trustedCurrentPrincipal.businessType().trim().toUpperCase(Locale.ROOT));
            if (!entry.allowedBusinessTypes().contains(businessType)) {
                return Optional.empty();
            }

            Optional<ToolExecutor> executorOptional = toolRegistry.getExecutor(command.toolName());
            if (executorOptional.isEmpty()) {
                return Optional.empty();
            }
            ToolExecutor executor = executorOptional.get();
            if (!hasExactRuntimeBinding(entry, executor)) {
                return Optional.empty();
            }

            Optional<FactoryToolConfig> factoryOverride =
                    factoryToolConfigRepository.findByFactoryIdAndToolName(
                            trustedCurrentPrincipal.tenantId(), command.toolName());
            if (factoryOverride.isPresent()
                    && !Boolean.TRUE.equals(factoryOverride.get().getEnabled())) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedTool(
                    entry.toDescriptor(),
                    executor,
                    ResolutionLane.LEGACY_INTENT_DISPATCH_MIGRATION));
        } catch (RuntimeException unavailableRuntimeTruth) {
            return Optional.empty();
        }
    }

    private static boolean hasExactEnvelope(
            ToolExecutionCommand command,
            ExecutionPrincipal current) {
        if (command == null || current == null || command.principal() == null) {
            return false;
        }
        ExecutionPrincipal asserted = command.principal();
        return command.source() == ToolExecutionSource.AI_INTENT_DISPATCH
                && command.mode() == ToolExecutionMode.EXECUTE
                && asserted.principalType() == PrincipalType.USER
                && current.principalType() == PrincipalType.USER
                && asserted.tenantId().equals(current.tenantId())
                && asserted.principalId().equals(current.principalId())
                && asserted.businessType().trim().equalsIgnoreCase(
                        current.businessType().trim())
                && command.confirmationProof().isEmpty()
                && command.approvalProof().isEmpty()
                && command.idempotencyKey().isEmpty();
    }

    private static boolean hasExactRuntimeBinding(
            LegacyToolMigrationEntry entry,
            ToolExecutor executor) {
        if (!entry.implementationClass().equals(AopUtils.getTargetClass(executor).getName())
                || !executor.isEnabled()
                || !entry.toolName().equals(executor.getToolName())
                || entry.actionType() != executor.getActionType()
                || entry.riskLevel() != executor.getRiskLevel()
                || entry.supportsPreview() != executor.supportsPreview()
                || entry.requiresPermission() != executor.requiresPermission()
                || !entry.requiredPermissions().equals(executor.getRequiredPermissions())
                || !entry.domainTags().equals(executor.getDomainTags())
                || !entry.version().equals(executor.getVersion())
                || executor instanceof EgressCapableTool) {
            return false;
        }
        return hasExactUnrestrictedRoleBehavior(executor);
    }

    private static boolean hasExactUnrestrictedRoleBehavior(ToolExecutor executor) {
        for (FactoryUserRole role : FactoryUserRole.values()) {
            if (!executor.hasPermission(role.name())) {
                return false;
            }
        }
        return executor.hasPermission(null) && executor.hasPermission("unknown_role");
    }

    private static void validateInventoryAlignment(
            LegacyToolMigrationEntry entry,
            ToolDescriptorInventoryEntry inventory) {
        if (inventory.provenance() != DescriptorProvenance.LEGACY_INFERRED
                || inventory.governanceStatus() != ToolGovernanceStatus.REVIEW_REQUIRED
                || !inventory.implementationClass().equals(entry.implementationClass())
                || inventory.actionType() != entry.actionType()
                || inventory.riskLevel() != entry.riskLevel()
                || inventory.supportsPreview() != entry.supportsPreview()
                || inventory.requiresPermission() != entry.requiresPermission()
                || !inventory.requiredPermissions().equals(entry.requiredPermissions())
                || !inventory.allowedRoles().equals(entry.allowedRoles())
                || !inventory.version().equals(entry.version())
                || !inventory.domainTags().equals(entry.domainTags())) {
            throw drift(entry.toolName(), "manifest/inventory mismatch");
        }
    }

    private static IllegalArgumentException drift(String toolName, String reason) {
        return new IllegalArgumentException(
                "legacy migration drift for " + toolName + ": " + reason);
    }
}
