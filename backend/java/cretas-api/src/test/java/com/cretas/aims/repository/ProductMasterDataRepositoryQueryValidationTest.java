package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
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
import java.time.LocalDateTime;

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
    @Autowired MaterialPackagingSpecRepository materialPackagingSpecRepository;
    @Autowired ProductProcessWorkflowRepository workflowRepository;

    @Test
    void repositoriesBootAndProductQueriesHideRawMaterialOwner() throws Exception {
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

        MaterialCodeSegment l1 = taxonomy(factory.getId(), (short) 1, null, "原料");
        entityManager.persist(l1);
        entityManager.flush();
        MaterialCodeSegment l2 = taxonomy(factory.getId(), (short) 2, l1.getId(), "肉类");
        entityManager.persist(l2);
        entityManager.flush();
        MaterialCodeSegment l3 = taxonomy(factory.getId(), (short) 3, l2.getId(), "牛肉");
        entityManager.persist(l3);
        entityManager.flush();

        RawMaterialType raw = new RawMaterialType();
        raw.setId("R-JPA-MASTER");
        raw.setFactoryId(factory.getId());
        raw.setCode("YL065");
        raw.setClassificationSegmentId(l3.getId());
        raw.setPrimaryCode("001");
        raw.setName("  Raw Material  ");
        raw.setCategory("原料");
        raw.setUnit("kg");
        raw.setIsActive(true);
        raw.setCreatedBy(user.getId());
        entityManager.persist(raw);

        RawMaterialType deletedRaw = new RawMaterialType();
        deletedRaw.setId("R-JPA-DELETED");
        deletedRaw.setFactoryId(factory.getId());
        deletedRaw.setCode("YL066");
        deletedRaw.setName("Deleted material code owner");
        deletedRaw.setCategory("原料");
        deletedRaw.setUnit("kg");
        deletedRaw.setIsActive(false);
        deletedRaw.setCreatedBy(user.getId());
        deletedRaw.setDeletedAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        entityManager.persist(deletedRaw);

        MaterialPackagingSpec packagingSpec = new MaterialPackagingSpec();
        packagingSpec.setFactoryId(factory.getId());
        packagingSpec.setMaterialTypeId(raw.getId());
        packagingSpec.setName("默认包装");
        packagingSpec.setPackageUnit("箱");
        packagingSpec.setBaseUnit("kg");
        packagingSpec.setConversionFactor(new BigDecimal("10"));
        packagingSpec.setDefaultSpec(true);
        packagingSpec.setActive(true);
        packagingSpec.setSortOrder(0);
        packagingSpec.setVersion(0L);
        entityManager.persist(packagingSpec);
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
        assertThat(materialRepository.findCodesByFactoryIdAndCodePrefix(factory.getId(), "yl"))
                .containsExactly("YL065", "YL066");
        String prefixQuery = RawMaterialTypeRepository.class
                .getMethod("findCodesByFactoryIdAndCodePrefix", String.class, String.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class)
                .value();
        assertThat(prefixQuery)
                .as("PostgreSQL stringtype=unspecified requires an explicit prefix parameter type")
                .contains("CAST(:prefix AS VARCHAR)");
        assertThat(materialRepository.findCodeConflictIncludingDeleted(factory.getId(), "yl066"))
                .get()
                .satisfies(conflict -> {
                    assertThat(conflict.getName()).isEqualTo("Deleted material code owner");
                    assertThat(conflict.getDeletedAt()).isNotNull();
                });
        assertThat(materialRepository.filterBySegmentPrefixAndKeyword(
                factory.getId(), l1.getId(), "", PageRequest.of(0, 20)).getContent())
                .extracting(RawMaterialType::getId)
                .containsExactly(raw.getId());
        assertThat(materialRepository.countActiveByFactoryIdAndClassificationSegmentId(factory.getId(), l3.getId()))
                .isEqualTo(1);
        assertThat(hierarchyRepository).isNotNull();
        assertThat(materialPackagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                        factory.getId(), raw.getId()))
                .singleElement()
                .satisfies(spec -> {
                    assertThat(spec.getPackageUnit()).isEqualTo("箱");
                    assertThat(spec.getConversionFactor()).isEqualByComparingTo("10");
                });
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

    private MaterialCodeSegment taxonomy(String factoryId, short level, Long parentId, String label) {
        return MaterialCodeSegment.builder()
                .factoryId(factoryId)
                .level(level)
                .parentId(parentId)
                .segmentLabel(label)
                .normalizedLabel(label)
                .sortOrder(0)
                .isActive(true)
                .build();
    }
}
