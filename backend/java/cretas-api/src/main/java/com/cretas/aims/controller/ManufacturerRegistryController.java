package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.material.CreateManufacturerRequest;
import com.cretas.aims.dto.material.ManufacturerRegistryDTO;
import com.cretas.aims.dto.material.UpdateManufacturerRequest;
import com.cretas.aims.service.material.ManufacturerRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/mobile/{factoryId}/manufacturers")
@RequiredArgsConstructor
public class ManufacturerRegistryController {

    private final ManufacturerRegistryService service;

    @RequirePermission({"warehouse:read", "warehouse:read_write"})
    @RequireModule("warehouse")
    @GetMapping
    public ApiResponse<List<ManufacturerRegistryDTO>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false, name = "active") Boolean active) {
        return ApiResponse.success(service.list(factoryId, Boolean.TRUE.equals(active)));
    }

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @PostMapping
    public ApiResponse<ManufacturerRegistryDTO> create(
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody CreateManufacturerRequest request) {
        return ApiResponse.success("厂商登记创建成功", service.create(factoryId, request));
    }

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @PutMapping("/{id}")
    public ApiResponse<ManufacturerRegistryDTO> update(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody UpdateManufacturerRequest request) {
        return ApiResponse.success("厂商登记更新成功", service.update(factoryId, id, request));
    }

    @RequirePermission({"warehouse:read_write"})
    @RequireModule("warehouse")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        service.delete(factoryId, id);
        return ApiResponse.success("厂商登记已停用", null);
    }
}
