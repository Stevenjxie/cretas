package com.cretas.aims.service.intent.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.util.BusinessDomainUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intent Config Management Service Implementation
 *
 * Manages intent CRUD, permission checks, quota, cache, and business domain resolution.
 *
 * @author Cretas Team
 * @version 2.0.0 (refactored from AIIntentServiceImpl)
 */
@Slf4j
@Service
public class IntentConfigManagementServiceImpl implements IntentConfigManagementService {

    private final AIIntentConfigRepository intentRepository;

    /**
     * v32: factoryId -> businessDomain cache (factory type rarely changes)
     */
    private final ConcurrentHashMap<String, String> factoryDomainCache = new ConcurrentHashMap<>();

    private FactoryRepository factoryRepository;

    @Autowired
    public IntentConfigManagementServiceImpl(AIIntentConfigRepository intentRepository) {
        this.intentRepository = intentRepository;
    }

    @Autowired
    public void setFactoryRepository(FactoryRepository factoryRepository) {
        this.factoryRepository = factoryRepository;
    }

    // ==================== Intent Queries ====================

    @Override
    public Optional<AIIntentConfig> getIntentByCode(String factoryId, String intentCode) {
        log.debug("getIntentByCode(factoryId={}, intentCode={})", factoryId, intentCode);
        List<AIIntentConfig> results = intentRepository.findByIntentCodeAndFactoryIdOrPlatform(intentCode, factoryId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    @Deprecated
    public Optional<AIIntentConfig> getIntentByCode(String intentCode) {
        log.debug("getIntentByCode(intentCode={})", intentCode);
        return intentRepository.findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(intentCode);
    }

    @Override
    @Cacheable(value = "allIntents", key = "#factoryId")
    public List<AIIntentConfig> getAllIntents(String factoryId) {
        log.debug("getAllIntents(factoryId={})", factoryId);
        return intentRepository.findByFactoryIdOrPlatformLevel(factoryId);
    }

    @Override
    @Deprecated
    @Cacheable(value = "allIntents_legacy")
    public List<AIIntentConfig> getAllIntents() {
        log.debug("getAllIntents()");
        return intentRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc();
    }

    @Override
    @Cacheable(value = "intentsByCategory", key = "#factoryId + ':' + #category")
    public List<AIIntentConfig> getIntentsByCategory(String factoryId, String category) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("getIntentsByCategory called without factoryId");
            return List.of();
        }
        return intentRepository.findByFactoryIdAndCategory(factoryId, category);
    }

    @Override
    @Deprecated
    @Cacheable(value = "intentsByCategory_legacy", key = "#category")
    public List<AIIntentConfig> getIntentsByCategory(String category) {
        log.warn("Deprecated getIntentsByCategory() called without factoryId");
        return intentRepository.findByIntentCategoryAndIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc(category);
    }

    @Override
    @Cacheable(value = "intentsBySensitivity", key = "#factoryId + ':' + #sensitivityLevel")
    public List<AIIntentConfig> getIntentsBySensitivity(String factoryId, String sensitivityLevel) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("getIntentsBySensitivity called without factoryId");
            return List.of();
        }
        return intentRepository.findByFactoryIdAndSensitivity(factoryId, sensitivityLevel);
    }

    @Override
    @Deprecated
    public List<AIIntentConfig> getIntentsBySensitivity(String sensitivityLevel) {
        log.warn("Deprecated getIntentsBySensitivity() called without factoryId");
        return intentRepository.findBySensitivityLevelAndIsActiveTrueAndDeletedAtIsNull(sensitivityLevel);
    }

    @Override
    @Cacheable(value = "intentCategories", key = "#factoryId")
    public List<String> getAllCategories(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            log.warn("getAllCategories called without factoryId");
            return List.of();
        }
        return intentRepository.findCategoriesByFactoryId(factoryId);
    }

    @Override
    @Deprecated
    @Cacheable(value = "intentCategories_legacy")
    public List<String> getAllCategories() {
        log.warn("Deprecated getAllCategories() called without factoryId");
        return intentRepository.findAllCategories();
    }

    // ==================== CRUD ====================

    @Override
    @Transactional
    public AIIntentConfig createIntent(AIIntentConfig intentConfig) {
        log.debug("createIntent(intentCode={})", intentConfig.getIntentCode());
        return intentRepository.save(intentConfig);
    }

    @Override
    @Transactional
    public AIIntentConfig updateIntent(AIIntentConfig intentConfig) {
        log.debug("updateIntent(intentCode={})", intentConfig.getIntentCode());
        return intentRepository.save(intentConfig);
    }

    @Override
    @Transactional
    public void deleteIntent(String intentCode) {
        log.debug("deleteIntent(intentCode={})", intentCode);
        intentRepository.findByIntentCodeAndDeletedAtIsNull(intentCode).ifPresent(config -> {
            config.setDeletedAt(LocalDateTime.now());
            intentRepository.save(config);
        });
    }

    @Override
    @Transactional
    @CacheEvict(value = {"allIntents", "intentsByCategory", "intentCategories", "intentsBySensitivity",
            "allIntents_legacy", "intentsByCategory_legacy", "intentCategories_legacy"}, allEntries = true)
    public void setIntentActive(String intentCode, boolean active) {
        AIIntentConfig existing = intentRepository
                .findByIntentCodeAndDeletedAtIsNull(intentCode)
                .orElseThrow(() -> new IllegalArgumentException("意图配置不存在: " + intentCode));
        existing.setIsActive(active);
        intentRepository.save(existing);
    }

    // ==================== Permission & Quota ====================

    @Override
    public boolean hasPermission(String factoryId, String intentCode, String userRole) {
        return getIntentByCode(factoryId, intentCode)
                .map(config -> checkRolePermission(config, userRole))
                .orElse(false);
    }

    @Override
    @Deprecated
    public boolean hasPermission(String intentCode, String userRole) {
        return getIntentByCode(intentCode)
                .map(config -> checkRolePermission(config, userRole))
                .orElse(false);
    }

    @Override
    public boolean requiresApproval(String factoryId, String intentCode) {
        return getIntentByCode(factoryId, intentCode)
                .map(AIIntentConfig::needsApproval)
                .orElse(false);
    }

    @Override
    @Deprecated
    public boolean requiresApproval(String intentCode) {
        return getIntentByCode(intentCode)
                .map(AIIntentConfig::needsApproval)
                .orElse(false);
    }

    @Override
    public Optional<String> getApprovalChainId(String factoryId, String intentCode) {
        return getIntentByCode(factoryId, intentCode)
                .filter(AIIntentConfig::needsApproval)
                .map(AIIntentConfig::getApprovalChainId);
    }

    @Override
    @Deprecated
    public Optional<String> getApprovalChainId(String intentCode) {
        return getIntentByCode(intentCode)
                .filter(AIIntentConfig::needsApproval)
                .map(AIIntentConfig::getApprovalChainId);
    }

    @Override
    public int getQuotaCost(String factoryId, String intentCode) {
        return getIntentByCode(factoryId, intentCode)
                .map(AIIntentConfig::getQuotaCost)
                .orElse(1);
    }

    @Override
    @Deprecated
    public int getQuotaCost(String intentCode) {
        return getIntentByCode(intentCode)
                .map(AIIntentConfig::getQuotaCost)
                .orElse(1);
    }

    @Override
    public int getCacheTtl(String factoryId, String intentCode) {
        return getIntentByCode(factoryId, intentCode)
                .map(AIIntentConfig::getCacheTtlMinutes)
                .orElse(0);
    }

    @Override
    @Deprecated
    public int getCacheTtl(String intentCode) {
        return getIntentByCode(intentCode)
                .map(AIIntentConfig::getCacheTtlMinutes)
                .orElse(0);
    }

    // ==================== Cache Management ====================

    @Override
    @CacheEvict(value = {"allIntents", "intentsByCategory", "intentCategories", "intentsBySensitivity",
            "allIntents_legacy", "intentsByCategory_legacy", "intentCategories_legacy"}, allEntries = true)
    public void clearCache() {
        log.info("Cleared AI intent config cache");
    }

    @Override
    public void refreshCache() {
        clearCache();
        getAllIntents(); // Reload cache
        log.info("Refreshed AI intent config cache");
    }

    // ==================== Helper ====================

    @Override
    public AIIntentConfig getIntentConfigByCode(String factoryId, String intentCode) {
        // v27b: Avoid JPQL (:factoryId IS NULL) syntax that causes PostgreSQL 42P18
        try {
            Optional<AIIntentConfig> config = intentRepository.findByIntentCode(intentCode);
            if (config.isPresent()) {
                AIIntentConfig found = config.get();
                if (found.getFactoryId() == null || found.getFactoryId().equals(factoryId)) {
                    return found;
                }
            }
        } catch (Exception e) {
            log.warn("v27b intent config lookup failed: intentCode={}, error={}", intentCode, e.getMessage());
        }
        return null;
    }

    @Override
    public String resolveBusinessDomain(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) return BusinessDomainUtils.FACTORY;
        return factoryDomainCache.computeIfAbsent(factoryId, fid -> {
            try {
                return factoryRepository.findById(fid)
                        .map(Factory::getType)
                        .map(BusinessDomainUtils::resolveDomain)
                        .orElse(BusinessDomainUtils.FACTORY);
            } catch (Exception e) {
                log.warn("v32 resolveBusinessDomain failed for factoryId={}: {}", fid, e.getMessage());
                return BusinessDomainUtils.FACTORY;
            }
        });
    }

    // ==================== Private ====================

    private boolean checkRolePermission(AIIntentConfig config, String userRole) {
        if (config == null || userRole == null) {
            return false;
        }
        if (config.getRequiredRoles() == null || config.getRequiredRoles().isBlank()) {
            return true;
        }
        String roles = config.getRequiredRoles().trim();
        if (roles.startsWith("[")) {
            return roles.contains("\"" + userRole + "\"");
        } else {
            return java.util.Arrays.asList(roles.split(",")).contains(userRole);
        }
    }
}
