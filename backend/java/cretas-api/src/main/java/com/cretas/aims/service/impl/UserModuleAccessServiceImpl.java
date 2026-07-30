package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserModuleAccessServiceImpl implements UserModuleAccessService {

    private final UserModuleAccessRepository repository;
    private final PermissionService permissionService;
    private final UserRepository userRepository;

    @Autowired
    public UserModuleAccessServiceImpl(
            UserModuleAccessRepository repository,
            PermissionService permissionService,
            UserRepository userRepository) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    public UserModuleAccessServiceImpl(
            UserModuleAccessRepository repository,
            PermissionService permissionService) {
        this(repository, permissionService, null);
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

        return repository.findActive(factoryId, userId, moduleCode)
                .map(access -> access.getAccessType() == UserModuleAccess.AccessType.GRANT)
                .orElseGet(() -> roleDefaultAllows(user, moduleCode));
    }

    @Override
    public List<ModuleAccessView> listEffectiveAccess(String factoryId, String userId) {
        User user = loadUser(factoryId, userId);
        Map<String, UserModuleAccess> overrides = repository.findActiveByFactoryAndUser(factoryId, userId).stream()
                .collect(Collectors.toMap(UserModuleAccess::getModuleCode, Function.identity(), (a, b) -> a));

        return ProductionModuleRegistry.modules().stream()
                .map(definition -> {
                    UserModuleAccess override = overrides.get(definition.moduleCode());
                    boolean roleDefault = roleDefaultAllows(user, definition.moduleCode());
                    boolean effective = override == null
                            ? roleDefault
                            : override.getAccessType() == UserModuleAccess.AccessType.GRANT;
                    return new ModuleAccessView(
                            definition.moduleCode(),
                            definition.displayName(),
                            definition.category(),
                            definition.permissionModule(),
                            roleDefault,
                            override == null ? null : override.getAccessType().name(),
                            effective);
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
        String permissionModule = ProductionModuleRegistry.permissionModule(moduleCode);
        return permissionService.hasAnyPermission(
                user,
                permissionModule + ":read",
                permissionModule + ":write",
                permissionModule + ":read_write");
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
