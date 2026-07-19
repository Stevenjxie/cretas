package com.cretas.aims.ai.capability;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** Resolves organization type from server-side Factory truth, never from request claims or JSON. */
@Component
public final class FactoryBusinessTypeResolver {
    private final FactoryRepository factoryRepository;

    public FactoryBusinessTypeResolver(FactoryRepository factoryRepository) {
        this.factoryRepository = Objects.requireNonNull(factoryRepository, "factoryRepository");
    }

    public Optional<FactoryType> resolveActive(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Optional.empty();
        }
        return factoryRepository.findById(factoryId)
                .filter(factory -> Boolean.TRUE.equals(factory.getIsActive()))
                .map(Factory::getType);
    }
}
