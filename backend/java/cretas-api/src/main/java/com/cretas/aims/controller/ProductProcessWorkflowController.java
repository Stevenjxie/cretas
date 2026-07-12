package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/product-process-workflows")
@Tag(name = "产品工序 Workflow", description = "产品工序图定义草稿与发布版本")
@RequireModule("production_plan")
public class ProductProcessWorkflowController {

    private final ProductProcessWorkflowService service;
    private final ProductProcessWorkflowActivationService activationService;

    @Autowired
    public ProductProcessWorkflowController(
            ProductProcessWorkflowService service,
            ProductProcessWorkflowActivationService activationService) {
        this.service = service;
        this.activationService = activationService;
    }

    public ProductProcessWorkflowController(ProductProcessWorkflowService service) {
        this(service, null);
    }

    @GetMapping("/{productTypeId}")
    @Operation(summary = "读取当前草稿；没有草稿时读取最新发布版本")
    public ApiResponse<ProductProcessWorkflowDTO> getEditorDefinition(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(service.getEditorDefinition(factoryId, productTypeId).orElse(null));
    }

    @RequirePermission({"production:read_write"})
    @RequireRole({"factory_super_admin", "workshop_supervisor", "department_admin"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @PutMapping("/{productTypeId}/draft")
    @Operation(summary = "保存 Workflow 草稿，不修改现有报工运行时")
    public ApiResponse<ProductProcessWorkflowDTO> saveDraft(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestBody @Valid ProductProcessWorkflowDTO definition) {
        return ApiResponse.success(service.saveDraft(factoryId, productTypeId, definition));
    }

    @RequirePermission({"production:read_write"})
    @RequireRole({"factory_super_admin", "workshop_supervisor", "department_admin"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @PostMapping("/{productTypeId}/publish")
    @Operation(summary = "发布 Workflow 图版本；阶段一不投影为生产任务")
    public ApiResponse<ProductProcessWorkflowDTO> publish(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestBody @Valid ProductProcessWorkflowDTO.PublishRequest request) {
        return ApiResponse.success(service.publish(factoryId, productTypeId, request.getLockVersion()));
    }

    @RequirePermission({"production:read_write"})
    @RequireRole({"factory_super_admin", "workshop_supervisor", "department_admin"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @PutMapping("/{workflowId}/activation")
    @Operation(summary = "Activate an exact published product process workflow version")
    public ApiResponse<ProductProcessWorkflowActivationDTO> activate(
            @PathVariable String factoryId,
            @PathVariable Long workflowId,
            HttpServletRequest request) {
        return ApiResponse.success(activationService.activate(
                factoryId, workflowId, (Long) request.getAttribute("userId")));
    }

    @RequirePermission({"production:read_write"})
    @RequireRole({"factory_super_admin", "workshop_supervisor", "department_admin"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @DeleteMapping("/activation")
    @Operation(summary = "Disable workflow activation without deleting runtime instances")
    public ApiResponse<ProductProcessWorkflowActivationDTO> deactivate(
            @PathVariable String factoryId,
            @RequestParam String productTypeId,
            @RequestParam("lockVersion") Long expectedLockVersion) {
        return ApiResponse.success(activationService.deactivate(
                factoryId, productTypeId, expectedLockVersion));
    }

    @GetMapping("/{productTypeId}/activation")
    @Operation(summary = "Read the explicit workflow activation for a product")
    public ApiResponse<ProductProcessWorkflowActivationDTO> getActivation(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(activationService.get(factoryId, productTypeId));
    }
}
