package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.permission.PermissionLevel;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserModuleAccessServiceImpl implements UserModuleAccessService {

    private final UserModuleAccessRepository repository;
    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final FactoryModuleConfigRepository factoryModuleConfigRepository;
    private final PlatformRolePermissionRepository platformRolePermissionRepository;

    @Autowired
    public UserModuleAccessServiceImpl(
            UserModuleAccessRepository repository,
            PermissionService permissionService,
            UserRepository userRepository,
            FactoryModuleConfigRepository factoryModuleConfigRepository,
            PlatformRolePermissionRepository platformRolePermissionRepository) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.factoryModuleConfigRepository = factoryModuleConfigRepository;
        this.platformRolePermissionRepository = platformRolePermissionRepository;
    }

    public UserModuleAccessServiceImpl(
            UserModuleAccessRepository repository,
            PermissionService permissionService,
            UserRepository userRepository) {
        this(repository, permissionService, userRepository, null, null);
    }

    public UserModuleAccessServiceImpl(
            UserModuleAccessRepository repository,
            PermissionService permissionService) {
        this(repository, permissionService, null, null, null);
    }

    @Override
    public boolean canAccessModule(User user, String moduleCode) {
        if (user == null || moduleCode == null || moduleCode.isBlank()) {
            return false;
        }
        String factoryId = user.getFactoryId();
        String userId = user.getId() == null ? null : String.valueOf(user.getId());
        if (factoryId == null || userId == null) {
            return false;
        }

        return getEffectiveLevel(factoryId, userId, user.getRoleCode(), moduleCode).canRead();
    }

    @Override
    public PermissionLevel getEffectiveLevel(String factoryId, String userId, String roleCode, String moduleCode) {
        if (roleCode == null || moduleCode == null || moduleCode.isBlank()) {
            return PermissionLevel.HIDDEN;
        }
        if ("factory_super_admin".equals(roleCode) || "platform_admin".equals(roleCode)) {
            return PermissionLevel.WRITE;
        }

        if (factoryId != null && userId != null) {
            var override = repository.findActive(factoryId, userId, moduleCode);
            if (override.isPresent()) {
                return override.get().getEffectivePermissionLevel();
            }
        }

        PermissionLevel factoryRoleLevel = resolveFactoryRoleLevel(factoryId, roleCode, moduleCode);
        if (factoryRoleLevel != null) {
            return factoryRoleLevel;
        }

        if (platformRolePermissionRepository != null) {
            return platformRolePermissionRepository
                    .findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode)
                    .map(permission -> PermissionLevel.fromAny(permission.getPermissionLevel()))
                    .orElse(PermissionLevel.HIDDEN);
        }

        return PermissionLevel.HIDDEN;
    }

    @Override
    public boolean canWriteModule(String factoryId, String userId, String roleCode, String moduleCode) {
        return getEffectiveLevel(factoryId, userId, roleCode, moduleCode).canWrite();
    }

    @Override
    public List<ModuleAccessView> listEffectiveAccess(String factoryId, String userId) {
        User user = loadUser(factoryId, userId);
        Map<String, UserModuleAccess> overrides = repository.findActiveByFactoryAndUser(factoryId, userId).stream()
                .collect(Collectors.toMap(UserModuleAccess::getModuleCode, Function.identity(), (a, b) -> a));

        return ProductionModuleRegistry.modules().stream()
                .map(definition -> {
                    UserModuleAccess override = overrides.get(definition.moduleCode());
                    PermissionLevel roleDefaultLevel = getRoleDefaultLevel(factoryId, user, definition.moduleCode());
                    boolean roleDefault = roleDefaultLevel.canRead();
                    PermissionLevel effectiveLevel = override == null
                            ? roleDefaultLevel
                            : override.getEffectivePermissionLevel();
                    boolean effective = override == null
                            ? roleDefault
                            : effectiveLevel.canRead();
                    return new ModuleAccessView(
                            definition.moduleCode(),
                            definition.displayName(),
                            definition.category(),
                            definition.permissionModule(),
                            roleDefault,
                            override == null ? null : override.getAccessType().name(),
                            effective,
                            roleDefaultLevel.apiCode(),
                            effectiveLevel.apiCode());
                })
                .toList();
    }

    @Override
    @Transactional
    public UserModuleAccess setOverride(
            String factoryId,
            String userId,
            String moduleCode,
            UserModuleAccess.AccessType accessType,
            String grantedBy,
            String remark) {
        validateModule(moduleCode);
        loadUser(factoryId, userId);

        UserModuleAccess row = repository.findAny(factoryId, userId, moduleCode)
                .orElseGet(() -> {
                    UserModuleAccess created = new UserModuleAccess();
                    created.setId(UUID.randomUUID().toString());
                    created.setFactoryId(factoryId);
                    created.setUserId(userId);
                    created.setModuleCode(moduleCode);
                    return created;
                });
        row.setAccessType(accessType);
        row.setPermissionLevel(accessType == UserModuleAccess.AccessType.GRANT
                ? PermissionLevel.WRITE.apiCode()
                : PermissionLevel.HIDDEN.apiCode());
        row.setGrantedBy(grantedBy);
        row.setRemark(remark);
        row.setDeletedAt(null);
        return repository.save(row);
    }

    @Override
    @Transactional
    public void clearOverride(String factoryId, String userId, String moduleCode) {
        validateModule(moduleCode);
        repository.findActive(factoryId, userId, moduleCode).ifPresent(access -> {
            access.softDelete();
            repository.save(access);
        });
    }

    private boolean roleDefaultAllows(User user, String moduleCode) {
        if (user == null) {
            return false;
        }
        return getRoleDefaultLevel(user.getFactoryId(), user, moduleCode).canRead();
    }

    private PermissionLevel getRoleDefaultLevel(String factoryId, User user, String moduleCode) {
        if (user == null) {
            return PermissionLevel.HIDDEN;
        }
        String roleCode = user.getRoleCode();
        if ("factory_super_admin".equals(roleCode) || "platform_admin".equals(roleCode)) {
            return PermissionLevel.WRITE;
        }
        PermissionLevel factoryRoleLevel = resolveFactoryRoleLevel(factoryId, roleCode, moduleCode);
        if (factoryRoleLevel != null) {
            return factoryRoleLevel;
        }
        if (platformRolePermissionRepository != null) {
            return platformRolePermissionRepository
                    .findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode)
                    .map(permission -> PermissionLevel.fromAny(permission.getPermissionLevel()))
                    .orElse(PermissionLevel.HIDDEN);
        }
        String permissionModule = ProductionModuleRegistry.permissionModule(moduleCode);
        boolean allowed = permissionService.hasAnyPermission(
                user,
                permissionModule + ":read",
                permissionModule + ":write",
                permissionModule + ":read_write");
        return allowed ? PermissionLevel.WRITE : PermissionLevel.HIDDEN;
    }

    private PermissionLevel resolveFactoryRoleLevel(String factoryId, String roleCode, String moduleCode) {
        if (factoryId == null || roleCode == null || factoryModuleConfigRepository == null) {
            return null;
        }
        return factoryModuleConfigRepository.findByFactoryIdAndConfigVersion(factoryId, 1).stream()
                .map(config -> config.getRoleModuleOverride().get(roleCode))
                .filter(Objects::nonNull)
                .map(roleModules -> roleModules.get(moduleCode))
                .filter(Objects::nonNull)
                .map(PermissionLevel::fromAny)
                .findFirst()
                .orElse(null);
    }

    private User loadUser(String factoryId, String userId) {
        if (userRepository == null) {
            throw new IllegalStateException("UserRepository is required for this operation");
        }
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

    private void validateModule(String moduleCode) {
        if (!ProductionModuleRegistry.moduleCodes().contains(moduleCode)) {
            throw new IllegalArgumentException("Invalid module_code: " + moduleCode);
        }
    }
}
