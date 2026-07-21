package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for supplier import, relation, spec and attachment queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SupplierRepositoryQueryValidationTest {
    @Autowired SupplierImportReceiptRepository importReceiptRepository;
    @Autowired SupplierMaterialRepository supplierMaterialRepository;
    @Autowired SupplierMaterialPurchaseSpecRepository purchaseSpecRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired com.cretas.aims.repository.inventory.PurchaseOrderRepository purchaseOrderRepository;
    @Autowired com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository workflowInstanceRepository;

    @Test
    void repositoriesBootAndAllDerivedQueriesParse() {
        assertNotNull(importReceiptRepository);
        assertNotNull(supplierMaterialRepository);
        assertNotNull(purchaseSpecRepository);
        assertNotNull(attachmentRepository);
        assertNotNull(purchaseOrderRepository);
        assertNotNull(workflowInstanceRepository);
    }
}
