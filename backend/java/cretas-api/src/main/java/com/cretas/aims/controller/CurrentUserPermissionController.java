package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.PermissionSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/permissions/me")
public class CurrentUserPermissionController {

    private final PermissionSettingsService permissionSettingsService;

    public CurrentUserPermissionController(PermissionSettingsService permissionSettingsService) {
        this.permissionSettingsService = permissionSettingsService;
    }

    @GetMapping("/effective")
    public ResponseEntity<?> getMyEffectivePermissions(
            @PathVariable String factoryId,
            HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Authentication is required"));
        }
        return ResponseEntity.ok(permissionSettingsService.getUserEffectivePermissions(factoryId, String.valueOf(userId)));
    }
}
