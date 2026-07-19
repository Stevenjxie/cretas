package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.AIIntentConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup validation for the single AI intent persistence model. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("AIIntentConfigRepository JPA startup validation")
class AIIntentConfigRepositoryQueryValidationTest {

    @Autowired
    private AIIntentConfigRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("canonical Entity and all repository queries initialize without a shadow mapping")
    void canonicalRepositoryBootsAsOnlyIntentEntity() {
        assertNotNull(repository);

        List<? extends Class<?>> intentEntityTypes = entityManager.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType())
                .filter(type -> type.getSimpleName().equalsIgnoreCase("aiintentconfig"))
                .toList();

        assertEquals(List.of(AIIntentConfig.class), intentEntityTypes);
    }
}
