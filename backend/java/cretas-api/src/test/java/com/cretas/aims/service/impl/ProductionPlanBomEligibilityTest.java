package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionSettlementBomEligibilityResponse;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionPlanBomEligibilityTest {

    @Mock private ProductionPlanRepository planRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository materialConsumptionRepo;
    @Mock private ProductionPlanBatchUsageRepository usageRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductionPlanMapper mapper;
    @Mock private ConversionRepository conversionRepo;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepo;
    @Mock private UserRepository userRepo;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepo;
    @Mock private SalesOrderItemRepository salesOrderItemRepo;
    @Mock private BomRecipeRepository recipeRepo;
    @Mock private BomRecipeItemRepository itemRepo;
    @Mock private BomItemSubstituteRepository substituteRepo;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                planRepo, batchRepo, materialBatchRepo, materialConsumptionRepo, usageRepo,
                productTypeRepo, mapper, conversionRepo, schedulingService, productionLineRepo,
                userRepo, excelUtil, salesOrderRepo, salesOrderItemRepo);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", recipeRepo);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", itemRepo);
        ReflectionTestUtils.setField(service, "bomItemSubstituteRepository", substituteRepo);
    }

    @Test
    void outputRecipeEligibilityIsExactSharedAndTargetExclusiveMaterialsPlusTheirSubstitutes() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-B");
        plan.setFactoryId("F006");
        plan.setProductTypeId("FG-B");
        plan.setSelectedBomRecipeId("BOM-B");
        plan.setSelectedBomVersion(2);

        BomRecipe shared = recipe("BOM-SHARED", "FG-A", null, 2);
        BomRecipe outputB = recipe("BOM-B", "FG-B", "BOM-SHARED", 2);
        BomRecipeItem sharedInput = item(1L, "BOM-SHARED", "RAW-A", "SHARED");
        BomRecipeItem siblingExclusive = item(9L, "BOM-SHARED", "PACK-A", "OUTPUT_EXCLUSIVE");
        BomRecipeItem targetExclusive = item(2L, "BOM-B", "PACK-B", "OUTPUT_EXCLUSIVE");
        BomRecipeItem duplicatedShared = item(3L, "BOM-B", "RAW-B-SHOULD-NOT-REPEAT", "SHARED");

        when(planRepo.findByIdAndFactoryId("PLAN-B", "F006")).thenReturn(Optional.of(plan));
        when(recipeRepo.findById("BOM-B")).thenReturn(Optional.of(outputB));
        when(recipeRepo.findById("BOM-SHARED")).thenReturn(Optional.of(shared));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("BOM-SHARED"))
                .thenReturn(List.of(sharedInput, siblingExclusive));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("BOM-B"))
                .thenReturn(List.of(targetExclusive, duplicatedShared));
        when(substituteRepo.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc("F006", "BOM-SHARED"))
                .thenReturn(List.of(
                        substitute("BOM-SHARED", 1L, "RAW-A-SUB"),
                        substitute("BOM-SHARED", 99L, "UNRELATED-SUB")));
        when(substituteRepo.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc("F006", "BOM-B"))
                .thenReturn(List.of(
                        substitute("BOM-B", 2L, "PACK-B-SUB"),
                        substitute("BOM-B", 3L, "WRONG-BRANCH-SUB")));

        ProductionSettlementBomEligibilityResponse response =
                service.getSettlementBomEligibility("F006", "PLAN-B");

        assertThat(response.isRestricted()).isTrue();
        assertThat(response.isBomFound()).isTrue();
        assertThat(response.getMaterialTypeIds())
                .containsExactlyInAnyOrder("RAW-A", "RAW-A-SUB", "PACK-B", "PACK-B-SUB");
    }

    private BomRecipe recipe(String id, String productTypeId, String sharedRecipeId, int version) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        recipe.setProductTypeId(productTypeId);
        recipe.setSharedRecipeId(sharedRecipeId);
        recipe.setVersion(version);
        return recipe;
    }

    private BomRecipeItem item(Long id, String recipeId, String materialTypeId, String costScope) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setRecipeId(recipeId);
        item.setFactoryId("F006");
        item.setMaterialTypeId(materialTypeId);
        item.setCostScope(costScope);
        return item;
    }

    private BomItemSubstitute substitute(String recipeId, Long parentId, String materialTypeId) {
        BomItemSubstitute substitute = new BomItemSubstitute();
        substitute.setFactoryId("F006");
        substitute.setRecipeId(recipeId);
        substitute.setParentKind(BomItemSubstitute.ParentKind.RECIPE_ITEM);
        substitute.setParentRecipeItemId(parentId);
        substitute.setSubstituteMaterialTypeId(materialTypeId);
        return substitute;
    }
}
