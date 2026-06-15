package com.cretas.aims.service.impl;

import com.cretas.aims.dto.permission.PermissionSettingsDtos.EffectiveUserPermissionDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.ModuleDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.ModulePermissionDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.PermissionPreviewDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.RoleTemplateDto;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateRoleTemplateRequest;
import com.cretas.aims.dto.permission.PermissionSettingsDtos.UpdateUserOverridesRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.permission.PermissionLevel;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionSettingsService;
import com.cretas.aims.service.UserModuleAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PermissionSettingsServiceImpl implements PermissionSettingsService {

    private static final String SETTINGS_MODULE_CODE = "permission_settings";
    private static final List<String> ADMIN_ROLES = List.of(
            "factory_super_admin",
            "platform_admin",
            "permission_admin");

    private final UserRepository userRepository;
    private final UserModuleAccessRepository userModuleAccessRepository;
    private final UserModuleAccessService userModuleAccessService;
    private final FactoryModuleConfigRepository factoryModuleConfigRepository;
    private final PlatformRolePermissionRepository platformRolePermissionRepository;

    public PermissionSettingsServiceImpl(
            UserRepository userRepository,
            UserModuleAccessRepository userModuleAccessRepository,
            UserModuleAccessService userModuleAccessService,
            FactoryModuleConfigRepository factoryModuleConfigRepository,
            PlatformRolePermissionRepository platformRolePermissionRepository) {
        this.userRepository = userRepository;
        this.userModuleAccessRepository = userModuleAccessRepository;
        this.userModuleAccessService = userModuleAccessService;
        this.factoryModuleConfigRepository = factoryModuleConfigRepository;
        this.platformRolePermissionRepository = platformRolePermissionRepository;
    }

    @Override
    public List<ModuleDto> listModules(String factoryId) {
        return sortedModules().stream().map(this::toModuleDto).toList();
    }

    @Override
    public List<RoleTemplateDto> listRoleTemplates(String factoryId) {
        Set<String> roleCodes = new LinkedHashSet<>(ADMIN_ROLES);
        platformRolePermissionRepository.findByDeletedAtIsNull()
                .forEach(permission -> roleCodes.add(permission.getRoleCode()));
        userRepository.findByFactoryId(factoryId).stream()
                .map(User::getRoleCode)
                .filter(roleCode -> roleCode != null && !roleCode.isBlank())
                .forEach(roleCodes::add);

        return roleCodes.stream()
                .map(roleCode -> new RoleTemplateDto(roleCode, roleCode, rolePermissions(factoryId, roleCode)))
                .toList();
    }

    @Override
    @Transactional
    public RoleTemplateDto updateRoleTemplate(String factoryId, UpdateRoleTemplateRequest request) {
        if (request == null || request.roleCode() == null || request.roleCode().isBlank()) {
            throw new IllegalArgumentException("roleCode is required");
        }
        FactoryModuleConfig config = getOrCreatePermissionConfig(factoryId);
        Map<String, Map<String, String>> overrides = mutableRoleOverrides(config.getRoleModuleOverride());
        Map<String, String> roleModules = new HashMap<>(overrides.getOrDefault(request.roleCode(), Map.of()));
        for (ModulePermissionDto module : safeModules(request.modules())) {
            validateModule(module.moduleCode());
            roleModules.put(module.moduleCode(), PermissionLevel.fromAny(module.permissionLevel()).legacyCode());
        }
        overrides.put(request.roleCode(), roleModules);
        config.setRoleModuleOverride(overrides);
        factoryModuleConfigRepository.save(config);
        return new RoleTemplateDto(request.roleCode(), request.roleCode(), rolePermissions(factoryId, request.roleCode()));
    }

    @Override
    public EffectiveUserPermissionDto getUserEffectivePermissions(String factoryId, String userId) {
        User user = loadUser(factoryId, userId);
        return new EffectiveUserPermissionDto(userId, user.getRoleCode(), userPermissions(factoryId, user));
    }

    @Override
    @Transactional
    public EffectiveUserPermissionDto updateUserOverrides(String factoryId, UpdateUserOverridesRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        User user = loadUser(factoryId, request.userId());
        Map<String, UserModuleAccess> existing = userModuleAccessRepository
                .findByFactoryIdAndUserId(factoryId, request.userId())
                .stream()
                .collect(Collectors.toMap(UserModuleAccess::getModuleCode, Function.identity(), (a, b) -> a));

        for (ModulePermissionDto module : safeModules(request.modules())) {
            validateModule(module.moduleCode());
            UserModuleAccess access = existing.get(module.moduleCode());
            if (access == null) {
                access = new UserModuleAccess();
                access.setId(UUID.randomUUID().toString());
                access.setFactoryId(factoryId);
                access.setUserId(request.userId());
                access.setModuleCode(module.moduleCode());
            }
            access.setEffectivePermissionLevel(PermissionLevel.fromAny(module.permissionLevel()));
            access.setDeletedAt(null);
            userModuleAccessRepository.save(access);
        }

        return new EffectiveUserPermissionDto(request.userId(), user.getRoleCode(), userPermissions(factoryId, user));
    }

    @Override
    @Transactional
    public EffectiveUserPermissionDto clearUserOverride(String factoryId, String userId, String moduleCode) {
        validateModule(moduleCode);
        User user = loadUser(factoryId, userId);
        userModuleAccessService.clearOverride(factoryId, userId, moduleCode);
        return new EffectiveUserPermissionDto(userId, user.getRoleCode(), userPermissions(factoryId, user));
    }

    @Override
    public PermissionPreviewDto previewUserPermissions(String factoryId, String userId) {
        EffectiveUserPermissionDto effective = getUserEffectivePermissions(factoryId, userId);
        List<ModulePermissionDto> visible = effective.modules().stream()
                .filter(module -> !"hidden".equals(module.permissionLevel()))
                .toList();
        List<ModulePermissionDto> denied = effective.modules().stream()
                .filter(module -> "hidden".equals(module.permissionLevel()))
                .toList();
        List<ModulePermissionDto> editable = effective.modules().stream()
                .filter(module -> "write".equals(module.permissionLevel()))
                .toList();
        return new PermissionPreviewDto(userId, visible, denied, editable);
    }

    private List<ModulePermissionDto> rolePermissions(String factoryId, String roleCode) {
        return sortedModules().stream()
                .map(module -> {
                    PermissionLevel level = userModuleAccessService.getEffectiveLevel(
                            factoryId,
                            null,
                            roleCode,
                            module.moduleCode());
                    return new ModulePermissionDto(module.moduleCode(), level.apiCode(), "role_template");
                })
                .toList();
    }

    private List<ModulePermissionDto> userPermissions(String factoryId, User user) {
        Map<String, UserModuleAccess> overrides = userModuleAccessRepository
                .findByFactoryIdAndUserId(factoryId, String.valueOf(user.getId()))
                .stream()
                .collect(Collectors.toMap(UserModuleAccess::getModuleCode, Function.identity(), (a, b) -> a));

        return sortedModules().stream()
                .map(module -> {
                    UserModuleAccess override = overrides.get(module.moduleCode());
                    PermissionLevel level = userModuleAccessService.getEffectiveLevel(
                            factoryId,
                            String.valueOf(user.getId()),
                            user.getRoleCode(),
                            module.moduleCode());
                    String source = sourceFor(user.getRoleCode(), override, level);
                    return new ModulePermissionDto(module.moduleCode(), level.apiCode(), source);
                })
                .toList();
    }

    private String sourceFor(String roleCode, UserModuleAccess override, PermissionLevel level) {
        if ("factory_super_admin".equals(roleCode) || "platform_admin".equals(roleCode)) {
            return "super_admin";
        }
        if (override != null) {
            return "user_override";
        }
        return level == PermissionLevel.HIDDEN ? "hidden_default" : "role_template";
    }

    private FactoryModuleConfig getOrCreatePermissionConfig(String factoryId) {
        return factoryModuleConfigRepository
                .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, SETTINGS_MODULE_CODE, 1)
                .orElseGet(() -> FactoryModuleConfig.builder()
                        .factoryId(factoryId)
                        .moduleCode(SETTINGS_MODULE_CODE)
                        .configVersion(1)
                        .enabled(true)
                        .roleModuleOverride(Map.of())
                        .build());
    }

    private Map<String, Map<String, String>> mutableRoleOverrides(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        if (source != null) {
            source.forEach((role, modules) -> copy.put(role, new HashMap<>(modules)));
        }
        return copy;
    }

    private User loadUser(String factoryId, String userId) {
        Long id;
        try {
            id = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid userId: " + userId, e);
        }
        return userRepository.findById(id)
                .filter(user -> factoryId.equals(user.getFactoryId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "User " + userId + " does not exist in factory " + factoryId));
    }

    private List<ProductionModuleRegistry.ModuleDefinition> sortedModules() {
        return ProductionModuleRegistry.modules().stream()
                .sorted(Comparator
                        .comparing(ProductionModuleRegistry.ModuleDefinition::parentCode)
                        .thenComparingInt(ProductionModuleRegistry.ModuleDefinition::sortOrder)
                        .thenComparing(ProductionModuleRegistry.ModuleDefinition::moduleCode))
                .toList();
    }

    private ModuleDto toModuleDto(ProductionModuleRegistry.ModuleDefinition module) {
        return new ModuleDto(
                module.moduleCode(),
                module.displayName(),
                module.parentCode(),
                module.parentName(),
                module.routePath(),
                module.sortOrder(),
                module.writeSupported());
    }

    private List<ModulePermissionDto> safeModules(List<ModulePermissionDto> modules) {
        return modules == null ? List.of() : new ArrayList<>(modules);
    }

    private void validateModule(String moduleCode) {
        if (!ProductionModuleRegistry.moduleCodes().contains(moduleCode)) {
            throw new IllegalArgumentException("Invalid moduleCode: " + moduleCode);
        }
    }
}
