package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomPriceAdjustmentAudit;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.SourceType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.Status;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.bom.BomPriceAdjustmentAuditRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("B9 BOM price adjustment suggestions")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomPriceAdjustmentServiceImplTest {

    @Mock
    private BomRecipeItemRepository itemRepository;
    @Mock
    private BomRecipeRepository recipeRepository;
    @Mock
    private BomPriceAdjustmentProposalRepository proposalRepository;
    @Mock
    private BomPriceAdjustmentAuditRepository auditRepository;

    private BomPriceAdjustmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomPriceAdjustmentServiceImpl(
                itemRepository, recipeRepository, proposalRepository, auditRepository);
        when(proposalRepository.save(any(BomPriceAdjustmentProposal.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(auditRepository.save(any(BomPriceAdjustmentAudit.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("purchase receive pre-tax price change creates pending suggestions with impact preview but does not mutate BOM")
    void purchaseReceivePriceChange_createsSuggestionWithoutUpdatingBomItem() {
        BomRecipeItem beefInMealA = recipeItem(101L, "R-1", "RM-BEEF", "10.0000", "2.0000");
        BomRecipeItem beefInMealB = recipeItem(102L, "R-2", "RM-BEEF", "11.0000", "1.0000");
        BomRecipe recipeA = recipe("R-1", "PT-A", "Meal A");
        BomRecipe recipeB = recipe("R-2", "PT-B", "Meal B");
        when(itemRepository.findByFactoryIdAndMaterialTypeId("F006", "RM-BEEF"))
                .thenReturn(List.of(beefInMealA, beefInMealB));
        when(recipeRepository.findAllById(List.of("R-1", "R-2")))
                .thenReturn(List.of(recipeA, recipeB));

        PurchaseReceiveRecord receive = new PurchaseReceiveRecord();
        receive.setId("RCV-1");
        receive.setFactoryId("F006");
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setId(501L);
        item.setMaterialTypeId("RM-BEEF");
        item.setMaterialName("beef");
        item.setUnitPrice(new BigDecimal("12.5000"));
        receive.setItems(List.of(item));

        List<BomPriceAdjustmentProposal> proposals = service.generateFromReceive("F006", receive);

        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0)).satisfies(proposal -> {
            assertThat(proposal.getStatus()).isEqualTo(Status.PENDING);
            assertThat(proposal.getSourceType()).isEqualTo(SourceType.PURCHASE_RECEIVE);
            assertThat(proposal.getSourceReceiveRecordId()).isEqualTo("RCV-1");
            assertThat(proposal.getSourceReceiveItemId()).isEqualTo(501L);
            assertThat(proposal.getCurrentUnitPrice()).isEqualByComparingTo("10.0000");
            assertThat(proposal.getProposedUnitPrice()).isEqualByComparingTo("12.5000");
            assertThat(proposal.getDeltaAmount()).isEqualByComparingTo("2.5000");
            assertThat(proposal.getDeltaPercent()).isEqualByComparingTo("25.00");
            assertThat(proposal.getAffectedProductCount()).isEqualTo(2);
            assertThat(proposal.getProductTypeId()).isEqualTo("PT-A");
            assertThat(proposal.getProductName()).isEqualTo("Meal A");
        });

        assertThat(beefInMealA.getUnitPrice()).isEqualByComparingTo("10.0000");
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("missing purchase price skips suggestion honestly instead of fabricating zero")
    void missingPurchasePrice_skipsSuggestion() {
        PurchaseReceiveRecord receive = new PurchaseReceiveRecord();
        receive.setId("RCV-NO-PRICE");
        receive.setFactoryId("F006");
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId("RM-NO-PRICE");
        item.setMaterialName("no price material");
        item.setUnitPrice(null);
        receive.setItems(List.of(item));

        List<BomPriceAdjustmentProposal> proposals = service.generateFromReceive("F006", receive);

        assertThat(proposals).isEmpty();
        verify(proposalRepository, never()).save(any(BomPriceAdjustmentProposal.class));
        verify(itemRepository, never()).save(any(BomRecipeItem.class));
    }

    @Test
    @DisplayName("approval applies proposed pre-tax price to BOM item and writes before/after audit")
    void approveSuggestion_updatesBomItemAndWritesAudit() {
        BomRecipeItem bomItem = recipeItem(101L, "R-1", "RM-BEEF", "10.0000", "2.0000");
        BomPriceAdjustmentProposal proposal = new BomPriceAdjustmentProposal();
        proposal.setId(77L);
        proposal.setFactoryId("F006");
        proposal.setRecipeItemId(101L);
        proposal.setCurrentUnitPrice(new BigDecimal("10.0000"));
        proposal.setProposedUnitPrice(new BigDecimal("12.5000"));
        proposal.setStatus(Status.PENDING);

        when(proposalRepository.findByIdAndFactoryId(77L, "F006")).thenReturn(java.util.Optional.of(proposal));
        when(itemRepository.findById(101L)).thenReturn(java.util.Optional.of(bomItem));
        when(itemRepository.save(any(BomRecipeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        BomPriceAdjustmentProposal approved = service.approve("F006", 77L, 9L, "price checked");

        assertThat(approved.getStatus()).isEqualTo(Status.APPROVED);
        assertThat(approved.getApprovedBy()).isEqualTo(9L);
        assertThat(approved.getApprovalComment()).isEqualTo("price checked");
        assertThat(bomItem.getUnitPrice()).isEqualByComparingTo("12.5000");
        assertThat(bomItem.getItemCost()).isEqualByComparingTo("25.0000");

        ArgumentCaptor<BomPriceAdjustmentAudit> auditCaptor =
                ArgumentCaptor.forClass(BomPriceAdjustmentAudit.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue()).satisfies(audit -> {
            assertThat(audit.getProposalId()).isEqualTo(77L);
            assertThat(audit.getRecipeItemId()).isEqualTo(101L);
            assertThat(audit.getBeforeUnitPrice()).isEqualByComparingTo("10.0000");
            assertThat(audit.getAfterUnitPrice()).isEqualByComparingTo("12.5000");
            assertThat(audit.getApprovedBy()).isEqualTo(9L);
        });
    }

    private BomRecipeItem recipeItem(Long id, String recipeId, String materialTypeId,
                                     String unitPrice, String standardQuantity) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setFactoryId("F006");
        item.setRecipeId(recipeId);
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(materialTypeId);
        item.setUnit("kg");
        item.setStandardQuantity(new BigDecimal(standardQuantity));
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    private BomRecipe recipe(String id, String productTypeId, String productName) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        recipe.setRecipeCode("BOM-" + id);
        recipe.setProductTypeId(productTypeId);
        recipe.setProductName(productName);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        return recipe;
    }
}
