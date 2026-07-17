package com.cretas.aims.service;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(SkuAssemblyService.class)
@DisplayName("SkuAssemblyService")
class SkuAssemblyServiceTest {

    private static final String FACTORY_ID = "F-SKU-CLONE";
    private static final String TEMPLATE_ID = "PT-TEMPLATE-CLONE";
    private static final String CUSTOMER_ID = "CUS-SKU-CLONE";
    private static final Long USER_ID = 1L;
    private static final DateTimeFormatter CODE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired private SkuAssemblyService skuAssemblyService;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private ProductWorkProcessRepository productWorkProcessRepository;
    @Autowired private BomRecipeRepository bomRecipeRepository;
    @Autowired private BomRecipeItemRepository bomRecipeItemRepository;
    @Autowired private BomSeasoningItemRepository bomSeasoningItemRepository;
    @Autowired private BomItemRepository bomItemRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("assemblesku clones template process chain, current BOM, seasoning, and legacy BOM once")
    void assembleskuClonesProcessChainAndBomFromTemplate() {
        seedTemplateWithProcessChainAndBom();

        ProductType sku = skuAssemblyService.assemblesku(
                FACTORY_ID, TEMPLATE_ID, CUSTOMER_ID, "v1", null, USER_ID);
        entityManager.flush();
        entityManager.clear();

        ProductType savedSku = productTypeRepository.findById(sku.getId()).orElseThrow();
        assertEquals("Template Product (Clone Customer)", savedSku.getName());
        assertEquals(new BigDecimal("250.00"), savedSku.getGramsPerUnit());
        assertEquals(new BigDecimal("120.000"), savedSku.getSinglePotCapacity());

        List<ProductWorkProcess> copiedProcesses =
                productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
                        FACTORY_ID, sku.getId());
        assertEquals(3, copiedProcesses.size());
        assertEquals(List.of(1, 2, 3), copiedProcesses.stream()
                .map(ProductWorkProcess::getProcessOrder)
                .toList());
        assertEquals(Boolean.FALSE, copiedProcesses.get(1).getReportingRequired());
        assertEquals(Boolean.TRUE, copiedProcesses.get(1).getAllowSemiFinishedInjection());
        assertEquals(Boolean.TRUE, copiedProcesses.get(2).getAllowMultipleUpstreamSources());
        assertEquals("AUXILIARY", copiedProcesses.get(2).getDefaultCostCategory());
        assertEquals(new BigDecimal("0.8500"), copiedProcesses.get(2).getStandardYieldRate());
        assertNotEquals(templateProcessIds(), copiedProcesses.stream()
                .map(ProductWorkProcess::getId)
                .toList());
        assertTrue(copiedProcesses.stream()
                .allMatch(process -> process.getResponsibleWorkerId() == null));

        BomRecipe copiedRecipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, sku.getId())
                .orElseThrow();
        assertNotEquals("BOM-TEMPLATE-CURRENT", copiedRecipe.getId());
        assertNotEquals(templateRecipeCode(), copiedRecipe.getRecipeCode());
        assertTrue(copiedRecipe.getRecipeCode().matches("BOM-\\d{8}-\\d{3}"));
        assertEquals(savedSku.getName(), copiedRecipe.getProductName());
        assertEquals(BomRecipe.Status.ACTIVE, copiedRecipe.getStatus());
        assertEquals(Boolean.TRUE, copiedRecipe.getIsCurrent());
        assertEquals(new BigDecimal("0.2000"), copiedRecipe.getInjectionRate());

        List<BomRecipeItem> copiedRecipeItems =
                bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(copiedRecipe.getId());
        assertEquals(1, copiedRecipeItems.size());
        assertEquals("MAT-RAW-1", copiedRecipeItems.get(0).getMaterialTypeId());
        assertEquals(Boolean.TRUE, copiedRecipeItems.get(0).getPerPortion());
        assertEquals("g", copiedRecipeItems.get(0).getPriceUnit());
        assertEquals(0, new BigDecimal("0.001").compareTo(copiedRecipeItems.get(0).getQuantityToPriceFactor()));
        assertNotEquals(101L, copiedRecipeItems.get(0).getId());

        List<BomSeasoningItem> copiedSeasoningItems =
                bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(copiedRecipe.getId());
        assertEquals(1, copiedSeasoningItems.size());
        assertEquals("COOKING", copiedSeasoningItems.get(0).getSection());
        assertEquals(Boolean.FALSE, copiedSeasoningItems.get(0).getCountInSeasoning());
        assertNotEquals(201L, copiedSeasoningItems.get(0).getId());

        List<BomItem> copiedLegacyItems =
                bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                        FACTORY_ID, sku.getId());
        assertEquals(1, copiedLegacyItems.size());
        assertEquals(savedSku.getName(), copiedLegacyItems.get(0).getProductName());
        assertEquals("MAT-LEGACY-1", copiedLegacyItems.get(0).getMaterialTypeId());
        assertEquals(Boolean.TRUE, copiedLegacyItems.get(0).getPerPortion());
        assertEquals("g", copiedLegacyItems.get(0).getPriceUnit());
        assertEquals(0, new BigDecimal("0.001").compareTo(copiedLegacyItems.get(0).getQuantityToPriceFactor()));

        assertThrows(BusinessException.class, () -> skuAssemblyService.assemblesku(
                FACTORY_ID, TEMPLATE_ID, CUSTOMER_ID, "v1", null, USER_ID));
        assertEquals(3, productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, sku.getId())
                .size());
        assertTrue(bomRecipeRepository
                .findByFactoryIdAndProductTypeIdOrderByVersionDesc(FACTORY_ID, sku.getId())
                .stream()
                .filter(BomRecipe::getIsCurrent)
                .count() == 1);
        assertEquals(1, bomItemRepository.countByFactoryIdAndProductTypeId(FACTORY_ID, sku.getId()));
    }

    private List<Long> templateProcessIds() {
        return productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY_ID, TEMPLATE_ID)
                .stream()
                .map(ProductWorkProcess::getId)
                .toList();
    }

    private void seedTemplateWithProcessChainAndBom() {
        Factory factory = new Factory();
        factory.setId(FACTORY_ID);
        factory.setName("SKU Clone Factory");
        entityManager.persist(factory);

        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("sku-clone-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();

        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setFactoryId(FACTORY_ID);
        customer.setCode("CUS-CLONE");
        customer.setCustomerCode("CUS-CLONE");
        customer.setName("Clone Customer");
        customer.setIsActive(true);
        customer.setCreatedBy(user.getId());
        customer.setVersion(0L);
        entityManager.persist(customer);

        ProductType template = new ProductType();
        template.setId(TEMPLATE_ID);
        template.setFactoryId(FACTORY_ID);
        template.setCode("TPL-CLONE");
        template.setName("Template Product");
        template.setCategory("finished");
        template.setUnit("kg");
        template.setUnitPrice(new BigDecimal("12.34"));
        template.setGramsPerUnit(new BigDecimal("250.00"));
        template.setSinglePotCapacity(new BigDecimal("120.000"));
        template.setIsActive(true);
        template.setCreatedBy(user.getId());
        productTypeRepository.saveAndFlush(template);

        productWorkProcessRepository.saveAndFlush(process("WP-1", 1, true, false, false));
        productWorkProcessRepository.saveAndFlush(process("WP-2", 2, false, true, false));
        ProductWorkProcess third = process("WP-3", 3, true, false, true);
        third.setDefaultCostCategory("AUXILIARY");
        third.setAuxAllocMethod("BY_OUTPUT");
        third.setStandardYieldRate(new BigDecimal("0.8500"));
        third.setAuxUnitPrice(new BigDecimal("1.2300"));
        third.setAuxBasis("OUTPUT");
        third.setResponsibleWorkerId(user.getId());
        productWorkProcessRepository.saveAndFlush(third);

        BomRecipe recipe = new BomRecipe();
        recipe.setId("BOM-TEMPLATE-CURRENT");
        recipe.setFactoryId(FACTORY_ID);
        recipe.setRecipeCode(templateRecipeCode());
        recipe.setProductTypeId(TEMPLATE_ID);
        recipe.setProductName("Template Product");
        recipe.setVersion(7);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(new BigDecimal("88.50"));
        recipe.setOutputQuantityPerUnit(new BigDecimal("1000.0000"));
        recipe.setOutputUnit("g");
        recipe.setTotalMaterialCost(new BigDecimal("10.0000"));
        recipe.setTotalLaborCost(new BigDecimal("2.0000"));
        recipe.setTotalOverheadCost(new BigDecimal("1.0000"));
        recipe.setTotalCost(new BigDecimal("13.0000"));
        recipe.setStandardSalePrice(new BigDecimal("18.00"));
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setSourceType(BomRecipe.SourceType.MANUAL);
        recipe.setNotes("template bom");
        recipe.setCookingPotBaseKg(new BigDecimal("160.000"));
        recipe.setSubsequentPotRatio(new BigDecimal("0.3333"));
        recipe.setInjectionRate(new BigDecimal("0.2000"));
        bomRecipeRepository.saveAndFlush(recipe);

        BomRecipeItem recipeItem = new BomRecipeItem();
        recipeItem.setRecipeId(recipe.getId());
        recipeItem.setFactoryId(FACTORY_ID);
        recipeItem.setMaterialTypeId("MAT-RAW-1");
        recipeItem.setMaterialName("Raw Material");
        recipeItem.setStandardQuantity(new BigDecimal("5.0000"));
        recipeItem.setYieldRate(new BigDecimal("95.00"));
        recipeItem.setActualQuantity(new BigDecimal("5.2632"));
        recipeItem.setUnit("kg");
        recipeItem.setPriceUnit("g");
        recipeItem.setQuantityToPriceFactor(new BigDecimal("0.00100000"));
        recipeItem.setUnitPrice(new BigDecimal("2.0000"));
        recipeItem.setTaxRate(new BigDecimal("13.00"));
        recipeItem.setItemCost(new BigDecimal("10.5264"));
        recipeItem.setMaterialCategory("RAW");
        recipeItem.setSortOrder(1);
        recipeItem.setIsOptional(false);
        recipeItem.setSubstituteGroup("BASE");
        recipeItem.setRemark("recipe item");
        recipeItem.setPerPortion(true);
        recipeItem.setSemiFinishedRefCode("SFI-RAW");
        recipeItem.setSubProductTypeId("SUB-PRODUCT-1");
        recipeItem.setPrimaryCode("001");
        recipeItem.setPrimaryCodeRef("001");
        bomRecipeItemRepository.saveAndFlush(recipeItem);

        BomSeasoningItem seasoningItem = new BomSeasoningItem();
        seasoningItem.setRecipeId(recipe.getId());
        seasoningItem.setFactoryId(FACTORY_ID);
        seasoningItem.setSection("COOKING");
        seasoningItem.setSeq(1);
        seasoningItem.setName("Seasoning");
        seasoningItem.setDosagePerKgG(new BigDecimal("1.5000"));
        seasoningItem.setPriceSource1(new BigDecimal("3.0000"));
        seasoningItem.setPriceSource2(new BigDecimal("2.5000"));
        seasoningItem.setCountInSeasoning(false);
        seasoningItem.setRemark("seasoning item");
        bomSeasoningItemRepository.saveAndFlush(seasoningItem);

        BomItem legacyItem = new BomItem();
        legacyItem.setFactoryId(FACTORY_ID);
        legacyItem.setProductTypeId(TEMPLATE_ID);
        legacyItem.setProductName("Template Product");
        legacyItem.setMaterialTypeId("MAT-LEGACY-1");
        legacyItem.setMaterialName("Legacy Material");
        legacyItem.setStandardQuantity(new BigDecimal("2.0000"));
        legacyItem.setYieldRate(new BigDecimal("90.00"));
        legacyItem.setUnit("kg");
        legacyItem.setPriceUnit("g");
        legacyItem.setQuantityToPriceFactor(new BigDecimal("0.00100000"));
        legacyItem.setUnitPrice(new BigDecimal("1.0000"));
        legacyItem.setTaxRate(new BigDecimal("13.00"));
        legacyItem.setMaterialCategory("AUXILIARY");
        legacyItem.setSortOrder(1);
        legacyItem.setRemark("legacy item");
        legacyItem.setPerPortion(true);
        legacyItem.setSemiFinishedRefCode("SFI-LEGACY");
        legacyItem.setSubProductTypeId("SUB-PRODUCT-LEGACY");
        bomItemRepository.saveAndFlush(legacyItem);
    }

    private ProductWorkProcess process(String workProcessId, int order, boolean reportingRequired,
                                       boolean allowSemiFinishedInjection,
                                       boolean allowMultipleUpstreamSources) {
        ProductWorkProcess process = new ProductWorkProcess();
        process.setFactoryId(FACTORY_ID);
        process.setProductTypeId(TEMPLATE_ID);
        process.setWorkProcessId(workProcessId);
        process.setProcessOrder(order);
        process.setUnitOverride("kg");
        process.setEstimatedMinutesOverride(10 * order);
        process.setReportingRequired(reportingRequired);
        process.setAllowSemiFinishedInjection(allowSemiFinishedInjection);
        process.setAllowMultipleUpstreamSources(allowMultipleUpstreamSources);
        process.setIsActive(true);
        return process;
    }

    private String templateRecipeCode() {
        return "BOM-" + LocalDate.now().format(CODE_DATE_FMT) + "-001";
    }
}
