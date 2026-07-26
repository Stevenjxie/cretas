package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
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

/** Real Hibernate startup gate for the PO-line receipt identity mapping. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.generate-ddl=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Purchase receipt order-line repository mapping validation")
class PurchaseReceiveItemRepositoryQueryValidationTest {

    @Autowired private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void repositoryAndOrderItemIdentityMappingBoot() {
        assertNotNull(receiveRecordRepository);
        assertThat(entityManager.getMetamodel().entity(PurchaseReceiveItem.class)
                .getAttribute("purchaseOrderItemId")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(PurchaseReceiveItem.class)
                .getAttribute("materialPackagingSpecId")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(PurchaseReceiveItem.class)
                .getAttribute("inventoryQuantitySnapshot")).isNotNull();
    }
}
