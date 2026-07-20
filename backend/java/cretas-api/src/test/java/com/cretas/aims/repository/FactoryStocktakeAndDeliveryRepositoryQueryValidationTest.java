package com.cretas.aims.repository;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for the stocktake CAS and delivery line locks. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Stocktake and delivery repository query startup validation")
class FactoryStocktakeAndDeliveryRepositoryQueryValidationTest {

    @Autowired private FactoryStocktakeRepository stocktakeRepository;
    @Autowired private SalesDeliveryItemRepository deliveryItemRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("lock queries and new entity mappings parse in a real JPA context")
    void repositoriesAndMappingsBoot() {
        assertNotNull(stocktakeRepository);
        assertNotNull(deliveryItemRepository);
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("version")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(SalesDeliveryItem.class).getAttribute("salesOrderItemId")).isNotNull();
    }
}
