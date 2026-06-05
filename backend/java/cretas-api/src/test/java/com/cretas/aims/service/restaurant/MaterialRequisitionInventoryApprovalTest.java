package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.service.restaurant.impl.MaterialRequisitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialRequisition approval inventory posting")
class MaterialRequisitionInventoryApprovalTest {

    private static final String FACTORY = "RES_3101_009";
    private static final Long USER = 7L;

    @Mock MaterialRequisitionRepository requisitionRepository;
    @Mock RecipeRepository recipeRepository;
    @Mock RestaurantInventoryPostingService inventoryPostingService;

    @Test
    @DisplayName("库存扣减失败时状态保持 SUBMITTED，不会变成 APPROVED")
    void approvalFailureKeepsSubmitted() {
        MaterialRequisition req = submittedReq();
        when(requisitionRepository.findByIdAndFactoryId("REQ1", FACTORY)).thenReturn(Optional.of(req));
        doThrow(new BusinessException(409, "库存不足").withCode("INSUFFICIENT_INVENTORY"))
                .when(inventoryPostingService)
                .postMaterialRequisitionIssue(FACTORY, req, USER);

        MaterialRequisitionServiceImpl service = new MaterialRequisitionServiceImpl(
                requisitionRepository, recipeRepository, inventoryPostingService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approveRequisition(FACTORY, "REQ1", USER, new BigDecimal("3.0000")));

        assertEquals(409, ex.getCode());
        assertEquals(MaterialRequisition.Status.SUBMITTED, req.getStatus());
        assertNull(req.getInventoryPostedAt());
        verify(requisitionRepository, never()).save(any(MaterialRequisition.class));
        verify(inventoryPostingService).markMaterialRequisitionPostingFailed(FACTORY, "REQ1", "库存不足");
    }

    @Test
    @DisplayName("已过账 APPROVED 领料单重复审批直接返回，不重复扣库存")
    void duplicateApprovedDoesNotPostAgain() {
        MaterialRequisition req = submittedReq();
        req.setStatus(MaterialRequisition.Status.APPROVED);
        req.setInventoryPostedAt(LocalDateTime.now());
        when(requisitionRepository.findByIdAndFactoryId("REQ1", FACTORY)).thenReturn(Optional.of(req));

        MaterialRequisitionServiceImpl service = new MaterialRequisitionServiceImpl(
                requisitionRepository, recipeRepository, inventoryPostingService);

        MaterialRequisition result = service.approveRequisition(FACTORY, "REQ1", USER, new BigDecimal("3.0000"));

        assertSame(req, result);
        verifyNoInteractions(inventoryPostingService);
        verify(requisitionRepository, never()).save(any(MaterialRequisition.class));
    }

    private MaterialRequisition submittedReq() {
        MaterialRequisition req = new MaterialRequisition();
        req.setId("REQ1");
        req.setFactoryId(FACTORY);
        req.setStatus(MaterialRequisition.Status.SUBMITTED);
        req.setRawMaterialTypeId("RMT_QHJ");
        req.setRequestedQuantity(new BigDecimal("3.0000"));
        return req;
    }
}
