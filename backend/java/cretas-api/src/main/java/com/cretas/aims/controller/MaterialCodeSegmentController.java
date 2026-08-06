package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;
import com.cretas.aims.service.material.MaterialCodeSegmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    @RequirePermission({"production:read_write", "warehouse:read_write", "system:read_write"})
    public ApiResponse<List<MaterialCodeSegmentDTO>> listByLevel(
            @PathVariable String factoryId,
            @RequestParam(required = false, defaultValue = "1") short level) {
        return ApiResponse.success(service.listByLevel(factoryId, level));
    }

    @GetMapping("/tree")
    @Operation(summary = "完整3层树形 (前端级联一次 fetch)")
    @RequirePermission({"production:read_write", "warehouse:read_write", "system:read_write"})
    public ApiResponse<List<MaterialCodeSegmentDTO>> getTree(@PathVariable String factoryId) {
        return ApiResponse.success(service.getTree(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建分段节点")
    @RequirePermission({"system:read_write"})
    public ApiResponse<MaterialCodeSegmentDTO> create(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateMaterialCodeSegmentRequest req) {
        return ApiResponse.success(service.create(factoryId, req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新分段节点")
    @RequirePermission({"system:read_write"})
    public ApiResponse<MaterialCodeSegmentDTO> update(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @Valid @RequestBody CreateMaterialCodeSegmentRequest req) {
        return ApiResponse.success(service.update(factoryId, id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除分段节点")
    @RequirePermission({"system:read_write"})
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        service.delete(factoryId, id);
        return ApiResponse.success(null);
    }

    /**
     * 16位编码预览 (只读, 不写库).
     *
     * <p>前端三级级联选好 L1/L2/L3 后调用, 返回将自动生成的完整16位编码.
     * 若工厂字典尚未配置, 返回 success=false + message 提示 (不抛 4xx, 前端降级处理).
     *
     * @param l1 L1 segmentCode (3位), e.g. "001"
     * @param l2 L2 segmentCode (6位, cumulative), e.g. "001001"
     * @param l3 L3 segmentCode (10位, cumulative), e.g. "0010010001"
     * @return { "success": true, "data": { "code": "0010010001000007" } }
     */
    /** 已删除(软删)的分类清单 —— 界面「显示已删除」开关用。 */
    @GetMapping("/deleted")
    @Operation(summary = "已删除的编码分类",
            description = "软删的行一直躺在库里且继续占着编码, 但界面此前看不到 —— 于是误删只能重建, 重建又撞码。")
    @RequirePermission({"production:read_write"})
    public ApiResponse<List<MaterialCodeSegmentDTO>> listDeleted(
            @PathVariable @Parameter(description = "工厂ID", example = "F006") String factoryId) {
        return ApiResponse.success(service.listDeleted(factoryId));
    }

    /** 恢复一条被软删的分类(编码/名称/归属原样回来)。 */
    @PostMapping("/{id}/restore")
    @Operation(summary = "恢复已删除的编码分类",
            description = "恢复比重建正确: 编码不变, 历史物料的16位码仍然指得回它的分类。")
    @RequirePermission({"production:read_write"})
    public ApiResponse<MaterialCodeSegmentDTO> restore(
            @PathVariable @Parameter(description = "工厂ID", example = "F006") String factoryId,
            @PathVariable Long id) {
        return ApiResponse.success("已恢复", service.restore(factoryId, id));
    }

    /**
     * 取下一个可用的子编码 (只读, 不写库)。
     *
     * <p>前端「新建共享 L2/L3 分类」的系统编码字段必须走这里, <b>不要在前端算</b> ——
     * 前端只看得到活着的子节点, 而编码被软删除的行继续占用 (见
     * {@code MaterialCodeSegmentService#nextSegmentCode})。
     */
    @GetMapping("/next-code")
    @Operation(summary = "取下一个可用子编码",
            description = "按含软删除的口径分配, 与唯一约束 uk_mcs_factory_segment 对齐。前端不得自行计算。")
    @RequirePermission({"production:read_write"})
    public ApiResponse<Map<String, String>> nextCode(
            @PathVariable @Parameter(description = "工厂ID", example = "F006") String factoryId,
            @RequestParam @Parameter(description = "层级 1/2/3", example = "3") short level,
            @RequestParam(required = false)
            @Parameter(description = "父级段码 (L2/L3 必填)", example = "001001") String parentCode) {
        String code = service.nextSegmentCode(factoryId, level, parentCode);
        log.info("next-code: factoryId={} level={} parentCode={} -> {}", factoryId, level, parentCode, code);
        return ApiResponse.success("取编码成功", Map.of("code", code));
    }

    @GetMapping("/generate-code")
    @Operation(summary = "16位编码预览 (generate-code)",
            description = "根据级联选择的 L1/L2/L3 段码预览将生成的完整16位编码, 不写库. 字典未配置时 success=false.")
    @RequirePermission({"production:read_write"})
    public ApiResponse<Map<String, String>> generateCode(
            @PathVariable @Parameter(description = "工厂ID", example = "F006") String factoryId,
            @RequestParam @Parameter(description = "L1 段码 (3位)", example = "001") String l1,
            @RequestParam @Parameter(description = "L2 段码 (6位)", example = "001001") String l2,
            @RequestParam @Parameter(description = "L3 段码 (10位)", example = "0010010001") String l3) {
        log.info("generate-code: factoryId={} l1={} l2={} l3={}", factoryId, l1, l2, l3);
        String code = service.generateCode(factoryId, l1, l2, l3);
        if (code == null) {
            return ApiResponse.error("16位字典尚未配置或参数无效, 无法生成预览编码");
        }
        return ApiResponse.success("编码预览成功", Map.of("code", code));
    }
}
