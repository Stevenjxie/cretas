package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialCodeSegment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = MaterialCodeSegment.class)
@EnableJpaRepositories(basePackageClasses = MaterialCodeSegmentRepository.class)
class MaterialTaxonomyRepositoryQueryValidationTest {

    @Autowired
    MaterialCodeSegmentRepository repository;

    @Test
    void repositoryContextBootsAndParentScopedIdentityQueryExecutes() {
        MaterialCodeSegment segment = MaterialCodeSegment.builder()
                .factoryId("F-TAXONOMY-JPA")
                .level((short) 1)
                .segmentCode("901")
                .segmentLabel("测试分类")
                .normalizedLabel("测试分类")
                .sortOrder(0)
                .isActive(true)
                .build();
        segment = repository.saveAndFlush(segment);

        assertTrue(repository.existsNormalizedLabelWithinParent(
                "F-TAXONOMY-JPA", (short) 1, null, "测试分类", null));
        assertFalse(repository.existsNormalizedLabelWithinParent(
                "F-TAXONOMY-JPA", (short) 1, null, "测试分类", segment.getId()));
    }
}
