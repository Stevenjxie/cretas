package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(BomRecipeServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("BOM draft lifecycle JPA integration")
class BomRecipeDraftLifecycleJpaTest {

    @Autowired
    private BomRecipeService service;

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private BomRecipeRepository recipeRepository;

    @Autowired
    private BomRecipeItemRepository itemRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ProductWorkflowResolutionService workflowResolutionService;

    @MockBean
    private MaterialUomConverter materialUomConverter;

    @MockBean
    private NestedBomCostService nestedBomCostService;

    @MockBean
    private UnitContractService unitContractService;

    @Test
    @DisplayName("zero versions creates an empty v1 DRAFT from SKU metadata")
    void createsEmptyFirstDraft() {
        ProductType product = saveProduct("FIRST", "袋", new BigDecimal("500"));

        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(draft.getVersion()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(BomRecipe.Status.DRAFT);
        assertThat(draft.getIsCurrent()).isFalse();
        assertThat(draft.getProductName()).isEqualTo(product.getName());
        assertThat(draft.getOutputUnit()).isEqualTo("袋");
        assertThat(draft.getOutputQuantityPerUnit()).isEqualByComparingTo("500");
        assertThat(draft.getItems()).isEmpty();
    }

    @Test
    @DisplayName("repeated ensure returns the same draft without incrementing version")
    void repeatedEnsureReusesDraft() {
        ProductType product = saveProduct("REPEAT", "盒", new BigDecimal("250"));

        BomRecipe first = service.ensureDraft(product.getFactoryId(), product.getId());
        BomRecipe second = service.ensureDraft(product.getFactoryId(), product.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent ensure calls serialize on the SKU and create one draft")
    void concurrentEnsureCreatesOneDraft() throws Exception {
        ProductType product = saveProduct("CONCURRENT", "包", new BigDecimal("180"));
        int callers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.ensureDraft(product.getFactoryId(), product.getId()).getId();
                }));
            }
            ready.await();
            start.countDown();

            Set<String> ids = new java.util.HashSet<>();
            for (Future<String> future : futures) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                    product.getFactoryId(), product.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("factory scoped lookup does not expose another factory SKU")
    void rejectsCrossFactoryProduct() {
        ProductType product = saveProduct("ISOLATION", "袋", new BigDecimal("300"));

        assertThatThrownBy(() -> service.ensureDraft("OTHER-FACTORY", product.getId()))
                .hasMessageContaining("产品不存在");
        assertThat(recipeRepository.countByFactoryIdAndProductTypeId(
                product.getFactoryId(), product.getId())).isZero();
    }

    @Test
    @DisplayName("activation commits without replacing the orphan-removal item collection")
    void activationCommitsWithPersistedRawItems() {
        ProductType product = saveProduct("ACTIVATE", "袋", new BigDecimal("500"));
        BomRecipe draft = service.ensureDraft(product.getFactoryId(), product.getId());

        RawMaterialType material = new RawMaterialType();
        material.setId("RAW-BOM-ACTIVATE");
        material.setFactoryId(product.getFactoryId());
        material.setCode("RAW-ACTIVATE");
        material.setName("激活测试原料");
        material.setCategory("RAW");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setIsAbacaPackaging(false);
        material.setCreatedBy(1L);
        rawMaterialTypeRepository.saveAndFlush(material);

        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(draft.getId());
        item.setFactoryId(product.getFactoryId());
        item.setMaterialTypeId(material.getId());
        item.setMaterialName(material.getName());
        item.setUnit("kg");
        item.setMaterialCategory("RAW");
        item.setYieldRate(new BigDecimal("100.00"));
        item.setSortOrder(0);
        item.setIsOptional(false);
        item.setPerPortion(false);
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        itemRepository.saveAndFlush(item);

        BomRecipe activated = service.activateRecipe(product.getFactoryId(), draft.getId(), 1309L);

        assertThat(activated.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
        assertThat(activated.getIsCurrent()).isTrue();
        assertThat(recipeRepository.findById(draft.getId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
                    assertThat(saved.getIsCurrent()).isTrue();
                });
        assertThat(itemRepository.findByRecipeIdOrderBySortOrderAsc(draft.getId()))
                .extracting(BomRecipeItem::getMaterialTypeId)
                .containsExactly(material.getId());
    }

    private ProductType saveProduct(String suffix, String unit, BigDecimal gramsPerUnit) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        String factoryId = "F-BOM-" + suffix;
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("BOM lifecycle " + suffix);
        factoryRepository.saveAndFlush(factory);

        ProductType product = new ProductType();
        product.setId("SKU-BOM-" + suffix);
        product.setFactoryId(factoryId);
        product.setCode("SKU-" + suffix);
        product.setName("测试 SKU " + suffix);
        product.setUnit(unit);
        product.setGramsPerUnit(gramsPerUnit);
        product.setIsActive(true);
        product.setCreatedBy(1L);
        return productTypeRepository.saveAndFlush(product);
    }
}
