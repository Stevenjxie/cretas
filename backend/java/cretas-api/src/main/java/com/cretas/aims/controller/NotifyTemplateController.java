package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notify 模板 CRUD — Phase 3 Canvas-Notify Step T6 skeleton.
 *
 * <p>4 endpoints: GET list / POST create / PUT update / DELETE soft-delete.
 * + POST test-send (返 NOT_IMPLEMENTED, sister 接入 NotifyDispatcher 后实现).
 *
 * <p>RequireRole: factory_super_admin / permission_admin.
 *
 * @since 2026-05-18 (Phase 3 skeleton)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/notify/templates")
@RequiredArgsConstructor
@RequireRole({"factory_super_admin", "permission_admin"})
@Tag(name = "Canvas-Notify Templates", description = "通知模板 CRUD (Phase 3 skeleton)")
public class NotifyTemplateController {

    private final NotifyTemplateRepository templateRepo;

    @GetMapping
    @Operation(summary = "列出工厂所有通知模板")
    public ApiResponse<List<NotifyTemplate>> list(@PathVariable String factoryId) {
        return ApiResponse.success(templateRepo.findByFactoryId(factoryId));
    }

    @PostMapping
    @Operation(summary = "创建通知模板 (skeleton — sister 实施时加 UNIQUE 冲突 409 actionHint)")
    public ApiResponse<NotifyTemplate> create(
            @PathVariable String factoryId,
            @RequestBody NotifyTemplate body) {
        return ApiResponse.error(501,
                "NotifyTemplateController.create skeleton — Phase 3 sister chat 实施");
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知模板 (skeleton)")
    public ApiResponse<NotifyTemplate> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody NotifyTemplate body) {
        return ApiResponse.error(501,
                "NotifyTemplateController.update skeleton — Phase 3 sister chat 实施");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除通知模板 (skeleton)")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        return ApiResponse.error(501,
                "NotifyTemplateController.delete skeleton — Phase 3 sister chat 实施");
    }

    @PostMapping("/test-send")
    @Operation(summary = "测试发送 (skeleton — sister 接入 NotifyDispatcher)")
    public ApiResponse<Map<String, Object>> testSend(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.error(501,
                "NotifyTemplateController.testSend skeleton — Phase 3 sister chat: "
                        + "调用 NotifyDispatcher.dispatch(buildRequest(body)) + 返回 NotifyResults");
    }
}
