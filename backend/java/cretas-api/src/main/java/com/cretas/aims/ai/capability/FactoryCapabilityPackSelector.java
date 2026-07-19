package com.cretas.aims.ai.capability;

import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure configuration selector. It has no runtime, planning or tool execution dependency. */
@Component
public final class FactoryCapabilityPackSelector {
    private static final int MAX_QUERY_LENGTH = 256;
    private final FactoryCapabilityPackRegistry registry;

    public FactoryCapabilityPackSelector(FactoryCapabilityPackRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<FactoryCapabilityPack> select(
            FactoryUserRole role, FactoryType businessType) {
        if (role == null || businessType == null) {
            return Optional.empty();
        }
        return registry.packs().stream()
                .filter(pack -> pack.status() == FactoryCapabilityPack.PackStatus.PUBLISHED)
                .filter(pack -> pack.roles().contains(role))
                .filter(pack -> pack.businessTypes().contains(businessType))
                .findFirst();
    }

    public Optional<FactoryCapabilityPack> match(
            FactoryUserRole role, FactoryType businessType, String query) {
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                || query.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("query must be 1..256 safe characters");
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return select(role, businessType)
                .filter(pack -> pack.matchTerms().stream()
                        .map(term -> term.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::contains));
    }
}
