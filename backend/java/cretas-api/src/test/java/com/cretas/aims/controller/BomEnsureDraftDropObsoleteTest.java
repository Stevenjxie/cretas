package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.EnsureBomDraftRequest;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ensure-draft 的「用户已确认丢弃旧工艺遗留投入」标志必须真的传到服务层。
 *
 * 背景(2026-08-13 生产实测, F006「叮咚好食光红烧猪蹄 250g」): 点画布上已有的包材行
 * 被 409 BOM_WORKFLOW_UPGRADE_OBSOLETE_INPUT 拦下, 提示让去删那几行 ——
 * 而任何路径都删不掉它们:
 *
 *   · 闸在【建草稿】时触发, 此刻草稿还没建成, 那几行属于 ACTIVE 配方,
 *     而 deleteItem 第一件事就是 status != DRAFT → 拒;
 *   · 就算配方是 DRAFT, hasCompleteWorkflowIdentity 也会拒 ——
 *     而「绑着画布槽位」正是这些行被选中的判据, 挑选条件与拒绝条件完全相同。
 *
 * 于是出口是死的。改法: 不放宽 deleteItem 的任何一道闸(从 ACTIVE 里删行会直接改动
 * 生产成本), 而是让用户的确认回到 ensure-draft, 由服务端在同一事务里删它自己算出的孤儿行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ensure-draft 的丢弃确认标志")
class BomEnsureDraftDropObsoleteTest {

    @Mock BomRecipeService recipeService;
    @Mock BomCopyService bomCopyService;
    @Mock BomSeasoningWorkspaceService seasoningWorkspaceService;
    @Mock BomItemSubstituteService substituteService;
    @Mock ProductTypeRepository productTypeRepository;

    private BomRecipeController controller;

    @BeforeEach
    void setUp() {
        controller = new BomRecipeController(
                recipeService, bomCopyService, seasoningWorkspaceService,
                substituteService, productTypeRepository);
        when(recipeService.ensureDraft(anyString(), anyString(), any(), any(Boolean.class)))
                .thenReturn(new BomRecipe());
    }

    private EnsureBomDraftRequest req(Boolean drop) {
        EnsureBomDraftRequest r = new EnsureBomDraftRequest();
        r.setProductTypeId("PT-1");
        r.setWorkflowRevisionId(7L);
        r.setDropObsoleteInputs(drop);
        return r;
    }

    @Test
    @DisplayName("确认后 true 传到服务层 —— 否则用户点了确认还是同一个 409")
    void confirmedFlagReachesTheService() {
        controller.ensureDraft("F006", req(Boolean.TRUE));

        verify(recipeService).ensureDraft(eq("F006"), eq("PT-1"), eq(7L), eq(true));
    }

    @Test
    @DisplayName("没传时按 false —— 默认仍然拦, 不因为加了这个字段就变成默默丢弃")
    void absentFlagDefaultsToFalse() {
        controller.ensureDraft("F006", req(null));

        verify(recipeService).ensureDraft(eq("F006"), eq("PT-1"), eq(7L), eq(false));
    }

    @Test
    @DisplayName("显式 false 也是 false")
    void explicitFalseStaysFalse() {
        controller.ensureDraft("F006", req(Boolean.FALSE));

        verify(recipeService).ensureDraft(eq("F006"), eq("PT-1"), eq(7L), eq(false));
    }
}
