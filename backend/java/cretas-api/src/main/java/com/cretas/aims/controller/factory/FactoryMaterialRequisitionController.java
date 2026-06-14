package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/material-requisitions")
@RequiredArgsConstructor
@RequireModule("production_plan")
public class FactoryMaterialRequisitionController {

    private final FactoryMaterialRequisitionService service;

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        String planId = (String) body.get("productionPlanId");
        FactoryMaterialRequisition mr = service.generateFromPlan(factoryId, planId, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", mr, "message", "material requisition generated"));
    }

    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pg = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FactoryMaterialRequisition> result = service.list(factoryId, status, pg);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String factoryId, @PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true, "data", service.getById(factoryId, id)));
    }

    @GetMapping("/by-plan/{planId}")
    public ResponseEntity<?> byPlan(@PathVariable String factoryId, @PathVariable String planId) {
        List<FactoryMaterialRequisition> list = service.listByPlan(factoryId, planId);
        return ResponseEntity.ok(Map.of("success", true, "data", list));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/start-picking")
    public ResponseEntity<?> startPicking(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", service.startPicking(factoryId, id, userId)));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/confirm-picking")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> confirmPicking(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        return ResponseEntity.ok(Map.of("success", true, "data", service.confirmPicking(factoryId, id, userId, items)));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/transfer")
    public ResponseEntity<?> transfer(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", service.transferToFactory(factoryId, id, userId)));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/receive")
    public ResponseEntity<?> receive(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResponseEntity.ok(Map.of("success", true, "data", service.receive(factoryId, id, userId)));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/close")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> close(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        List<Map<String, Object>> items = body == null
                ? List.of()
                : (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", service.close(factoryId, id, userId, items),
                "message", "production material return executed"));
    }

    @RequirePermission({"warehouse:read_write", "production:read_write"})
    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        String reason = (String) body.getOrDefault("reason", "");
        return ResponseEntity.ok(Map.of("success", true, "data", service.cancel(factoryId, id, userId, reason)));
    }
}
