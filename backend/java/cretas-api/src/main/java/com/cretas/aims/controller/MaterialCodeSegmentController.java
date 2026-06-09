package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;
import com.cretas.aims.service.material.MaterialCodeSegmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SP8: 物料16位分段编码字典 CRUD API.
 *
 * <p>端点设计:
 * <ul>
 *   <li>GET  /material-segments?level=1   — 按层级列表</li>
 *   <li>GET  /material-segments/tree       — 完整3层树形</li>
 *   <li>POST /material-segments            — 创建节点</li>
 *   <li>PUT  /material-segments/{id}       — 更新节点</li>
 *   <li>DELETE /material-segments/{id}     — 软删除节点</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/material-segments")
@Tag(name = "MaterialCodeSegment", description = "SP8: 物料分段编码字典 (16位分段体系)")
public class MaterialCodeSegmentController {

    private final MaterialCodeSegmentService service;

    @GetMapping
    @Operation(summary = "按层级列表查询")
    @RequirePermission({"production:read_write"})
    public ApiResponse<List<MaterialCodeSegmentDTO>> listByLevel(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "1") short level) {
        return ApiResponse.success(service.listByLevel(factoryId, level));
    }

    @GetMapping("/tree")
    @Operation(summary = "完整3层树形 (前端级联一次 fetch)")
    @RequirePermission({"production:read_write"})
    public ApiResponse<List<MaterialCodeSegmentDTO>> getTree(@PathVariable String factoryId) {
        return ApiResponse.success(service.getTree(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建分段节点")
    @RequirePermission({"production:read_write"})
    public ApiResponse<MaterialCodeSegmentDTO> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateMaterialCodeSegmentRequest req) {
        return ApiResponse.success(service.create(factoryId, req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分段节点")
    @RequirePermission({"production:read_write"})
    public ApiResponse<MaterialCodeSegmentDTO> update(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @Valid @RequestBody CreateMaterialCodeSegmentRequest req) {
        return ApiResponse.success(service.update(factoryId, id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除分段节点")
    @RequirePermission({"production:read_write"})
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        service.delete(factoryId, id);
        return ApiResponse.success(null);
    }
}
