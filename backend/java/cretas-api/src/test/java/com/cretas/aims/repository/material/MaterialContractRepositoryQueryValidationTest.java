package com.cretas.aims.repository.material;

import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class MaterialContractRepositoryQueryValidationTest {

    @Autowired MaterialCodeSegmentRepository segmentRepository;
    @Autowired RawMaterialTypeRepository materialTypeRepository;
    @Autowired UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Test
    void repositoriesBootAndAllDeclaredQueriesParse() {
        assertNotNull(segmentRepository);
        assertNotNull(materialTypeRepository);
        assertNotNull(unitOfMeasurementRepository);
    }
}
