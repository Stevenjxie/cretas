package com.cretas.aims.ai.capability;

import com.cretas.aims.ai.capability.FactoryCapabilityPack.PackStatus;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventory;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import com.cretas.aims.entity.enums.FactoryUserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Startup-loaded registry for the exact four version-controlled factory packs. */
@Component
public final class FactoryCapabilityPackRegistry {
    public static final Map<String, String> EXPECTED_RESOURCE_DIGESTS = Map.of(
            "ai/capability-packs/operator-v1.yaml",
            "eed7d0a6c5a14ba4e514e71ba11166292200fe0c439359596fcca223832f58ad",
            "ai/capability-packs/warehouse-v1.yaml",
            "ada8ea1b6abea51072a25e12c980047c8381e723cc4597dea7bce17791b4dcd6",
            "ai/capability-packs/quality-v1.yaml",
            "032d51ebcf9a11423c3d76f8b62f60ece4eedc235b5fbf7aaa228b592a54e9d1",
            "ai/capability-packs/manager-v1.yaml",
            "cd7ef5defcb40168aa558af8b363f6949ffa1a2d28706889e1133464b985ad98");
    private static final Set<String> EXPECTED_PACK_IDS = Set.of(
            "factory.operator", "factory.warehouse", "factory.quality", "factory.manager");

    private final List<FactoryCapabilityPack> packs;
    private final Map<String, FactoryCapabilityPack> byId;

    public FactoryCapabilityPackRegistry() {
        this(new FactoryCapabilityPackLoader(),
                new ToolDescriptorInventoryLoader().loadDefault(), EXPECTED_RESOURCE_DIGESTS);
    }

    FactoryCapabilityPackRegistry(
            FactoryCapabilityPackLoader loader,
            ToolDescriptorInventory inventory,
            Map<String, String> expectedResourceDigests) {
        if (expectedResourceDigests.size() != 4) {
            throw new IllegalArgumentException("exactly four capability resources are required");
        }
        List<FactoryCapabilityPack> loaded = expectedResourceDigests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> loadVerified(loader, inventory, entry.getKey(), entry.getValue()))
                .toList();
        validateSet(loaded);
        this.packs = List.copyOf(loaded);
        Map<String, FactoryCapabilityPack> index = new LinkedHashMap<>();
        loaded.forEach(pack -> index.put(pack.packId(), pack));
        this.byId = Map.copyOf(index);
    }

    public List<FactoryCapabilityPack> packs() {
        return packs;
    }

    public Optional<FactoryCapabilityPack> findById(String packId) {
        return Optional.ofNullable(byId.get(packId));
    }

    private static FactoryCapabilityPack loadVerified(
            FactoryCapabilityPackLoader loader,
            ToolDescriptorInventory inventory,
            String resourcePath,
            String expectedDigest) {
        FactoryCapabilityPack pack = loader.loadResource(resourcePath, inventory);
        if (!pack.digest().equals(expectedDigest)) {
            throw new IllegalStateException(
                    "capability pack digest drift for " + resourcePath
                            + "; expected=" + expectedDigest + ", actual=" + pack.digest());
        }
        return pack;
    }

    private static void validateSet(List<FactoryCapabilityPack> packs) {
        if (packs.size() != 4
                || !packs.stream().map(FactoryCapabilityPack::packId).collect(
                        java.util.stream.Collectors.toSet()).equals(EXPECTED_PACK_IDS)
                || packs.stream().anyMatch(pack -> pack.status() != PackStatus.PUBLISHED)) {
            throw new IllegalArgumentException("registry requires exact four published factory packs");
        }
        Set<FactoryUserRole> roles = new LinkedHashSet<>();
        for (FactoryCapabilityPack pack : packs) {
            for (FactoryUserRole role : pack.roles()) {
                if (!roles.add(role)) {
                    throw new IllegalArgumentException("role is ambiguous across packs: " + role);
                }
            }
        }
    }
}
