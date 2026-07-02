package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 工厂默认仓路由配置 Controller (V20261027_30, Layer 1 backend).
 *
 * <p>让超级管理员把 {@link com.cretas.aims.service.factory.WarehouseResolver} 硬编码的默认仓
 * (WH-LOG / WH-WKS / WH-RD) 覆盖到本工厂自选的 {@code factory_warehouses} 记录。
 *
 * <p><b>向后兼容</b>: 本表 opt-in。未配置的工厂 resolver 回退硬编码 code, 行为与现状一致。
 *
 * <p>权限策略 (镜像 {@link FactoryWarehouseController}):
 * <ul>
 *   <li>GET — factory_super_admin / permission_admin, warehouse 模块已启用</li>
 *   <li>PUT (upsert) — 同上</li>
 * </ul>
 *
 * <p>多租户安全: upsert 时校验每个 warehouseId 属于 path 上的 factoryId (不能配到别厂的仓库)。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/factory/warehouse-defaults")
@RequiredArgsConstructor
public class FactoryWarehouseDefaultController {

    private final FactoryWarehouseDefaultRepository defaultRepository;
    private final FactoryWarehouseRepository warehouseRepository;

    // ==================== 查询 ====================

    /** 列出该工厂的 purpose → warehouse 映射 (仅显式配置的; 未配置的 purpose 走硬编码, 不出现在列表)。 */
    @RequireRole({"factory_super_admin", "permission_admin"})
    @RequireModule("warehouse")
    @GetMapping
    public ApiResponse<List<FactoryWarehouseDefault>> list(@PathVariable @NotBlank String factoryId) {
        List<FactoryWarehouseDefault> list = defaultRepository.findByFactoryIdAndDeletedAtIsNull(factoryId);
        return ApiResponse.success("查询成功", list);
    }

    // ==================== 幂等 upsert ====================

    /**
     * 幂等 upsert 默认仓配置。
     *
     * <p>Body 支持两种形态 (镜像 apply-template 的 resolveSpecs pattern):
     * <ul>
     *   <li>单条: {@code { "purpose": "LOGISTICS_DEFAULT", "warehouseId": "..." }}</li>
     *   <li>批量: {@code { "defaults": [ { "purpose": ..., "warehouseId": ... }, ... ] }}</li>
     * </ul>
     *
     * <p>幂等: 同 (factory_id, purpose) 已有有效配置 → 更新 warehouse_id; 否则新建。
     */
    @RequireRole({"factory_super_admin", "permission_admin"})
    @RequireModule("warehouse")
    @PutMapping
    public ApiResponse<List<FactoryWarehouseDefault>> upsert(
            @PathVariable @NotBlank String factoryId,
            @RequestBody UpsertRequest req) {

        List<DefaultSpec> specs = resolveSpecs(req);
        if (specs.isEmpty()) {
            throw new BusinessException(400, "purpose/warehouseId 或 defaults 列表不能为空")
                    .withCode("WAREHOUSE_DEFAULT_EMPTY");
        }

        List<FactoryWarehouseDefault> result = new ArrayList<>();
        for (DefaultSpec spec : specs) {
            if (spec.getPurpose() == null) {
                throw new BusinessException(400, "purpose 不能为空").withCode("WAREHOUSE_DEFAULT_INVALID");
            }
            if (spec.getWarehouseId() == null || spec.getWarehouseId().isBlank()) {
                throw new BusinessException(400, "warehouseId 不能为空").withCode("WAREHOUSE_DEFAULT_INVALID");
            }
            String warehouseId = spec.getWarehouseId().trim();

            // 多租户安全: warehouse 必须属于本工厂且未软删。
            FactoryWarehouse target = warehouseRepository
                    .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                    .orElseThrow(() -> new BusinessException(400,
                            "仓库 [" + warehouseId + "] 不存在或不属于当前工厂")
                            .withCode("WAREHOUSE_NOT_IN_FACTORY"));

            FactoryWarehouseDefault entity = defaultRepository
                    .findByFactoryIdAndPurposeAndDeletedAtIsNull(factoryId, spec.getPurpose())
                    .orElseGet(() -> {
                        FactoryWarehouseDefault fresh = new FactoryWarehouseDefault();
                        fresh.setFactoryId(factoryId);
                        fresh.setPurpose(spec.getPurpose());
                        return fresh;
                    });
            entity.setWarehouseId(target.getId());
            result.add(defaultRepository.save(entity));
            log.info("upsert 默认仓配置 factoryId={} purpose={} warehouseId={} ({})",
                    factoryId, spec.getPurpose(), target.getId(), target.getCode());
        }

        return ApiResponse.success("默认仓配置已保存", result);
    }

    // ==================== helpers ====================

    private List<DefaultSpec> resolveSpecs(UpsertRequest req) {
        if (req == null) {
            return List.of();
        }
        if (req.getDefaults() != null && !req.getDefaults().isEmpty()) {
            return req.getDefaults();
        }
        if (req.getPurpose() != null || (req.getWarehouseId() != null && !req.getWarehouseId().isBlank())) {
            DefaultSpec single = new DefaultSpec();
            single.setPurpose(req.getPurpose());
            single.setWarehouseId(req.getWarehouseId());
            return List.of(single);
        }
        return List.of();
    }

    // ==================== Request DTOs ====================

    @Data
    public static class UpsertRequest {
        /** 单条 purpose (defaults 为空时生效) */
        private WarehouseDefaultPurpose purpose;
        /** 单条 warehouseId (defaults 为空时生效) */
        private String warehouseId;
        /** 批量配置列表 (优先于 purpose/warehouseId) */
        private List<DefaultSpec> defaults;
    }

    @Data
    public static class DefaultSpec {
        @NotNull(message = "purpose 不能为空")
        private WarehouseDefaultPurpose purpose;
        @NotBlank(message = "warehouseId 不能为空")
        private String warehouseId;
    }
}
