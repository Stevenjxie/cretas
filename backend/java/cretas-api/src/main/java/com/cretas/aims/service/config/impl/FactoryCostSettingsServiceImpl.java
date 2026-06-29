package com.cretas.aims.service.config.impl;

import com.cretas.aims.entity.config.FactoryCostSettings;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactoryCostSettingsRepository;
import com.cretas.aims.service.config.FactoryCostSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryCostSettingsServiceImpl implements FactoryCostSettingsService {

    private final FactoryCostSettingsRepository repository;

    @Override
    public BigDecimal getLaborHourlyRate(String factoryId) {
        return repository.findByFactoryId(factoryId)
                .map(FactoryCostSettings::getLaborHourlyRate)
                .orElse(null);
    }

    @Override
    @Transactional
    public BigDecimal upsertLaborHourlyRate(String factoryId, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new BusinessException(400, "工时单价必须大于 0");
        }
        FactoryCostSettings settings = repository.findByFactoryId(factoryId)
                .orElseGet(() -> {
                    FactoryCostSettings created = new FactoryCostSettings();
                    created.setFactoryId(factoryId);
                    return created;
                });
        settings.setLaborHourlyRate(rate);
        FactoryCostSettings saved = repository.save(settings);
        log.info("[COST-SETTINGS] factory={} laborHourlyRate set to {}", factoryId, saved.getLaborHourlyRate());
        return saved.getLaborHourlyRate();
    }
}
