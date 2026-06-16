package com.cretas.aims.controller.factory;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * P1-4 工厂仓库管理 Controller (v1 §2.9 双仓体系).
 *
 * <p>超级管理员可创建/编辑/停用仓库，并通过「套模板」快速初始化仓库配置。
 * 只读端点（GET）保持原有语义，供普通仓管/操作员下拉数据使用。
 *
 * <p>权限策略：
 * <ul>
 *   <li>GET（查询）— 无额外角色限制，所有登录用户可访问</li>
 *   <li>POST/PUT/DELETE/apply-template — 仅 factory_super_admin / permission_admin</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/factory/warehouses")
@RequiredArgsConstructor
public class FactoryWarehouseController {

    private final FactoryWarehouseRepository repository;

    // ==================== 查询 ====================

    @GetMapping
    public ApiResponse<List<FactoryWarehouse>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) WarehouseType type) {
        List<FactoryWarehouse> list = type != null
                ? repository.findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(factoryId, type)
                : repository.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(factoryId);
        return ApiResponse.success("查询成功", list);
    }

    // ==================== 创建 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping
    public ApiResponse<FactoryWarehouse> create(
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody CreateWarehouseRequest req) {

        // 幂等防呆：同工厂 code 唯一
        repository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, req.getCode())
                .ifPresent(existing -> {
                    throw new BusinessException(409, "仓库 " + req.getCode() + " 已存在，id=" + existing.getId())
                            .withCode("WAREHOUSE_CODE_DUPLICATE");
                });

        FactoryWarehouse w = new FactoryWarehouse();
        w.setFactoryId(factoryId);
        w.setCode(req.getCode().trim());
        w.setName(req.getName().trim());
        w.setType(req.getType());
        w.setIsActive(req.getIsActive() != null ? req.getIsActive() : Boolean.TRUE);
        w.setRemarks(req.getRemarks());
        FactoryWarehouse saved = repository.save(w);
        log.info("创建仓库 factoryId={} code={} type={}", factoryId, saved.getCode(), saved.getType());
        return ApiResponse.success("仓库已创建", saved);
    }

    // ==================== 编辑 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @PutMapping("/{id}")
    public ApiResponse<FactoryWarehouse> update(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody UpdateWarehouseRequest req) {

        FactoryWarehouse w = repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException("仓库不存在或不属于当前工厂"));

        // code 不可修改
        if (req.getName() != null) w.setName(req.getName().trim());
        if (req.getType() != null) w.setType(req.getType());
        if (req.getIsActive() != null) w.setIsActive(req.getIsActive());
        if (req.getRemarks() != null) w.setRemarks(req.getRemarks());

        FactoryWarehouse saved = repository.save(w);
        log.info("更新仓库 factoryId={} id={} code={}", factoryId, id, saved.getCode());
        return ApiResponse.success("仓库已更新", saved);
    }

    // ==================== 软删除 ====================

    @RequireRole({"factory_super_admin", "permission_admin"})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {

        FactoryWarehouse w = repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException("仓库不存在或不属于当前工厂"));

        w.softDelete();
        repository.save(w);
        log.info("软删除仓库 factoryId={} id={} code={}", factoryId, id, w.getCode());
        return ApiResponse.success("仓库已停用", null);
    }

    // ==================== 套模板 ====================

    /**
     * 一键套用仓库模板.
     *
     * <p>幂等：已存在的 (factory_id, code) 跳过，不报错；返回 created/skipped 统计。
     *
     * @param templateKey 可选预设模板 key：{@code LIUSHANMEN_SALTED}（六扇门含盐化代加工7仓）；
     *                    为 null 时则使用 body 中的 {@code warehouses} 数组
     */
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/apply-template")
    public ApiResponse<ApplyTemplateResult> applyTemplate(
            @PathVariable @NotBlank String factoryId,
            @RequestBody ApplyTemplateRequest req) {

        List<WarehouseSpec> specs = resolveSpecs(req);
        if (specs.isEmpty()) {
            throw new BusinessException("模板或仓库列表不能为空");
        }

        int created = 0;
        int skipped = 0;
        List<FactoryWarehouse> items = new ArrayList<>();

        for (WarehouseSpec spec : specs) {
            boolean exists = repository
                    .findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, spec.getCode())
                    .isPresent();
            if (exists) {
                skipped++;
                log.debug("套模板跳过已存在仓库 factoryId={} code={}", factoryId, spec.getCode());
                continue;
            }
            FactoryWarehouse w = new FactoryWarehouse();
            w.setFactoryId(factoryId);
            w.setCode(spec.getCode().trim());
            w.setName(spec.getName().trim());
            w.setType(spec.getType());
            w.setIsActive(Boolean.TRUE);
            items.add(repository.save(w));
            created++;
        }

        log.info("套模板 factoryId={} templateKey={} created={} skipped={}",
                factoryId, req.getTemplateKey(), created, skipped);
        return ApiResponse.success(
                "套模板完成：新建 " + created + " 个，跳过 " + skipped + " 个（已存在）",
                new ApplyTemplateResult(created, skipped, items));
    }

    // ==================== 预设模板数据 ====================

    private List<WarehouseSpec> resolveSpecs(ApplyTemplateRequest req) {
        if (req.getTemplateKey() != null) {
            return switch (req.getTemplateKey()) {
                case "LIUSHANMEN_SALTED" -> liushanmenSaltedTemplate();
                default -> throw new BusinessException("未知模板 key：" + req.getTemplateKey());
            };
        }
        if (req.getWarehouses() != null && !req.getWarehouses().isEmpty()) {
            return req.getWarehouses();
        }
        return List.of();
    }

    /** 六扇门含盐化代加工 7 仓预设 */
    private List<WarehouseSpec> liushanmenSaltedTemplate() {
        return List.of(
                new WarehouseSpec("WH-LOG",     "物流仓",        WarehouseType.LOGISTICS),
                new WarehouseSpec("WH-RAW",     "原料仓",        WarehouseType.RAW),
                new WarehouseSpec("WH-WIP",     "半成品仓",      WarehouseType.WIP),
                new WarehouseSpec("WH-FG",      "成品仓",        WarehouseType.FINISHED),
                new WarehouseSpec("WH-WKS",     "鲜棉仓",        WarehouseType.WORKSHOP),
                new WarehouseSpec("WH-RD",      "研发中试库",    WarehouseType.RD),
                new WarehouseSpec("SALTED-01",  "盐化仓代加工", WarehouseType.SALTED)
        );
    }

    // ==================== Request / Response DTOs ====================

    @Data
    public static class CreateWarehouseRequest {
        @NotBlank(message = "仓库编码不能为空")
        private String code;
        @NotBlank(message = "仓库名称不能为空")
        private String name;
        @NotNull(message = "仓库类型不能为空")
        private WarehouseType type;
        private Boolean isActive;
        private String remarks;
    }

    @Data
    public static class UpdateWarehouseRequest {
        private String name;
        private WarehouseType type;
        private Boolean isActive;
        private String remarks;
    }

    @Data
    public static class ApplyTemplateRequest {
        /** 预设模板 key，为 null 时使用 warehouses 数组 */
        private String templateKey;
        /** 自选仓型列表（templateKey 为 null 时生效） */
        private List<WarehouseSpec> warehouses;
    }

    @Data
    public static class WarehouseSpec {
        private String code;
        private String name;
        private WarehouseType type;

        /** Jackson 反序列化用 */
        public WarehouseSpec() {}

        public WarehouseSpec(String code, String name, WarehouseType type) {
            this.code = code;
            this.name = name;
            this.type = type;
        }
    }

    @Data
    public static class ApplyTemplateResult {
        private final int created;
        private final int skipped;
        private final List<FactoryWarehouse> items;
    }
}
