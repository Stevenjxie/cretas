package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/JPA startup and behavior gate for product/material master-data queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductMasterDataRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired ProductTypeRepository productRepository;
    @Autowired RawMaterialTypeRepository materialRepository;
    @Autowired MaterialPackagingHierarchyRepository hierarchyRepository;
    @Autowired ProductProcessWorkflowRepository workflowRepository;

    @Test
    void repositoriesBootAndProductQueriesHideRawMaterialOwner() {
        Factory factory = new Factory();
        factory.setId("F-JPA-MASTER");
        factory.setName("JPA master data query gate factory");
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);

        User user = new User();
        user.setFactoryId(factory.getId());
        user.setUsername("jpa-master-query-user");
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();

        ProductType visible = product("P-VISIBLE", factory.getId(), user.getId(),
                "CPF0060149", "  Case Product  ", "FINISHED_PRODUCT");
        visible.setGramsPerUnit(new BigDecimal("800"));
        ProductType hiddenOwner = product("P-RAW-OWNER", factory.getId(), user.getId(),
                "RAW-OWNER-1", "内部原料 owner", "RAW_MATERIAL");
        entityManager.persist(visible);
        entityManager.persist(hiddenOwner);

        ProductProcessWorkflow snapshot = new ProductProcessWorkflow();
        snapshot.setFactoryId(factory.getId());
        snapshot.setProductTypeId(visible.getId());
        snapshot.setStatus(ProductProcessWorkflow.Status.SNAPSHOT);
        snapshot.setDefinitionVersion(7);
        snapshot.setNodesJson("[]");
        snapshot.setEdgesJson("[]");
        snapshot.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        entityManager.persist(snapshot);

        RawMaterialType raw = new RawMaterialType();
        raw.setId("R-JPA-MASTER");
        raw.setFactoryId(factory.getId());
        raw.setCode("0010010001000001");
        raw.setName("  Raw Material  ");
        raw.setCategory("原料");
        raw.setUnit("kg");
        raw.setIsActive(true);
        raw.setCreatedBy(user.getId());
        entityManager.persist(raw);
        entityManager.flush();
        entityManager.clear();

        assertThat(productRepository.findOptionsByFactoryId(factory.getId()))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.getId()).isEqualTo("P-VISIBLE");
                    assertThat(option.getUnit()).isEqualTo("盒");
                    assertThat(option.getGramsPerUnit()).isEqualByComparingTo("800");
                });
        assertThat(productRepository.findVisibleByFactoryId(factory.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(ProductType::getId)
                .containsExactly("P-VISIBLE");
        assertThat(productRepository.findVisibleByFactoryIdAndIsActiveTrue(factory.getId()))
                .extracting(ProductType::getId)
                .containsExactly("P-VISIBLE");
        assertThat(productRepository.findVisibleByFactoryIdAndProductCategory(
                factory.getId(), "RAW_MATERIAL", PageRequest.of(0, 20))).isEmpty();
        assertThat(productRepository.findVisibleByFactoryIdAndCategory(factory.getId(), "RAW_MATERIAL"))
                .isEmpty();
        assertThat(productRepository.searchVisibleProductTypes(
                factory.getId(), "owner", PageRequest.of(0, 20))).isEmpty();
        assertThat(productRepository.findByFiltersWithUnitAndTemperatureZone(
                factory.getId(), null, null, "盒", null, PageRequest.of(0, 20)).getContent())
                .extracting(ProductType::getId)
                .containsExactly("P-VISIBLE");
        assertThat(productRepository.existsByFactoryIdAndNormalizedName(factory.getId(), " case product ")).isTrue();
        assertThat(productRepository.existsByFactoryIdAndNormalizedName(
                factory.getId(), "internal raw OWNER")).isFalse();
        assertThat(productRepository.existsByFactoryIdAndNormalizedNameExcludingId(
                factory.getId(), " CASE PRODUCT ", "P-VISIBLE")).isFalse();
        assertThat(productRepository.findCodesByFactoryIdAndGeneratedPrefix(factory.getId(), "CPF006"))
                .containsExactly("CPF0060149");
        assertThat(materialRepository.existsByFactoryIdAndNormalizedName(factory.getId(), " raw material ")).isTrue();
        assertThat(materialRepository.existsByFactoryIdAndNormalizedNameExcludingId(
                factory.getId(), " RAW MATERIAL ", raw.getId())).isFalse();
        assertThat(hierarchyRepository).isNotNull();
        assertThat(workflowRepository
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factory.getId(), visible.getId(), ProductProcessWorkflow.Status.SNAPSHOT))
                .get()
                .extracting(ProductProcessWorkflow::getDefinitionVersion)
                .isEqualTo(7);
        assertThat(workflowRepository.findMaxDefinitionVersion(factory.getId(), visible.getId()))
                .contains(7);
        assertThat(workflowRepository.findVersionSummaries(factory.getId(), visible.getId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.getId()).isNotNull();
                    assertThat(summary.getDefinitionVersion()).isEqualTo(7);
                    assertThat(summary.getStatus()).isEqualTo(ProductProcessWorkflow.Status.SNAPSHOT);
                    assertThat(summary.getUpdatedAt()).isNotNull();
                });
    }

    private ProductType product(String id, String factoryId, Long userId,
                                String code, String name, String productCategory) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(factoryId);
        product.setCreatedBy(userId);
        product.setCode(code);
        product.setName(name);
        product.setUnit("盒");
        product.setCategory(productCategory);
        product.setProductCategory(productCategory);
        product.setIsActive(true);
        return product;
    }
}
