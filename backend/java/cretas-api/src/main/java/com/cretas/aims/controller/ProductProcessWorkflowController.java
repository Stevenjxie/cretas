package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
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

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/product-process-workflows")
@Tag(name = "产品工序 Workflow", description = "产品工序图定义草稿与发布版本")
@RequireModule("production_plan")
public class ProductProcessWorkflowController {

    private final ProductProcessWorkflowService service;
    private final ProductProcessWorkflowActivationService activationService;
    private final ProductWorkflowResolutionService resolutionService;

    @Autowired
    public ProductProcessWorkflowController(
            ProductProcessWorkflowService service,
            ProductProcessWorkflowActivationService activationService,
            ProductWorkflowResolutionService resolutionService) {
        this.service = service;
        this.activationService = activationService;
        this.resolutionService = resolutionService;
    }

    public ProductProcessWorkflowController(ProductProcessWorkflowService service) {
        this(service, null, null);
    }

    public ProductProcessWorkflowController(
            ProductProcessWorkflowService service,
            ProductProcessWorkflowActivationService activationService) {
        this(service, activationService, null);
    }

    @GetMapping("/{productTypeId}")
    @Operation(summary = "读取当前草稿；没有草稿时读取最新发布版本")
    public ApiResponse<ProductProcessWorkflowDTO> getEditorDefinition(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(service.getEditorDefinition(factoryId, productTypeId).orElse(null));
    }

    /**
     * raw-centric 多成品 (2026-07-13): 按一组终端成品解析可覆盖它们的已启用 Workflow。
     * 单选优先成品自有图 (SELF_WORKFLOW); 多选只匹配以原料为锚、终端覆盖所选全部成品的图 (RAW_OWNED)。
     * 字面量段 /resolve-by-outputs 与 GET /{productTypeId} 不冲突 (Spring 字面量优先)。
     */
    @PostMapping("/resolve-by-outputs")
    @Operation(summary = "按一组终端成品解析可覆盖它们的已启用 Workflow(单选优先成品自有图, 多选匹配原料 owner 图)")
    public ApiResponse<WorkflowOutputResolutionDTO> resolveByOutputs(
            @PathVariable String factoryId,
            @Valid @RequestBody WorkflowOutputResolutionDTO.Request request) {
        return ApiResponse.success(resolutionService.resolveForOutputs(factoryId, request.getProductTypeIds()));
    }

    @GetMapping("/{productTypeId}/versions")
    @Operation(summary = "读取某产品的全部 Workflow 版本历史(DRAFT + PUBLISHED), 按版本号倒序")
    public ApiResponse<List<ProductProcessWorkflowVersionSummaryDTO>> listVersions(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ApiResponse.success(service.listVersions(factoryId, productTypeId));
    }

    @GetMapping("/{productTypeId}/versions/{version}")
    @Operation(summary = "只读: 读取某产品指定 definitionVersion 的完整 Workflow 图定义")
    public ApiResponse<ProductProcessWorkflowDTO> getVersion(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @PathVariable Integer version) {
        return ApiResponse.success(service.getVersion(factoryId, productTypeId, version));
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
    @PostMapping("/{productTypeId}/snapshot")
    @Operation(summary = "将当前 Workflow 草稿另存为独立版本，不发布、不启用")
    public ApiResponse<ProductProcessWorkflowDTO> snapshot(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestBody @Valid ProductProcessWorkflowDTO.PublishRequest request) {
        return ApiResponse.success(service.snapshot(factoryId, productTypeId, request.getLockVersion()));
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
