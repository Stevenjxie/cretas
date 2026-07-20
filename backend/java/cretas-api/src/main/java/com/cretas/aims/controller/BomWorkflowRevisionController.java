package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.BomWorkflowRevisionPinRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/recipes/{recipeId}/workflow-revisions")
@RequiredArgsConstructor
@RequireModule("bom")
public class BomWorkflowRevisionController {

    private final BomWorkflowRevisionService service;

    @GetMapping
    public ApiResponse<List<WorkflowRevisionCandidateDTO>> list(
            @PathVariable String factoryId, @PathVariable String recipeId) {
        return ApiResponse.success(service.listCompatible(factoryId, recipeId));
    }

    @PutMapping("/pin")
    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    public ApiResponse<BomRecipe> pin(
            @PathVariable String factoryId,
            @PathVariable String recipeId,
            @RequestBody BomWorkflowRevisionPinRequest request) {
        return ApiResponse.success(service.pin(factoryId, recipeId, request));
    }
}
