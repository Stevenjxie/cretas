package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.repository.FactoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Builds the asserted USER identity for trusted in-process Gateway callers. */
@Component
@RequiredArgsConstructor
public class AuthenticatedToolPrincipalFactory {

    private final FactoryRepository factoryRepository;

    public ExecutionPrincipal create(String factoryId, Long userId, String userRole) {
        if (factoryId == null
                || factoryId.isBlank()
                || userId == null
                || userId <= 0
                || userRole == null
                || userRole.isBlank()) {
            throw new SecurityException("Authenticated tool identity is incomplete");
        }
        Factory factory = factoryRepository.findById(factoryId)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .filter(candidate -> candidate.getType() != null)
                .orElseThrow(() -> new SecurityException(
                        "Authenticated tool tenant is unavailable"));
        return new ExecutionPrincipal(
                factory.getId(),
                factory.getType().name(),
                userId.toString(),
                PrincipalType.USER,
                Set.of(userRole),
                Set.of(),
                Set.of());
    }
}
