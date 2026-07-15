package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.SemiFinishedYieldStatsDTO;

public interface SemiFinishedYieldStatsService {

    SemiFinishedYieldStatsDTO getStats(String factoryId, String semiFinishedSkuId);
}
