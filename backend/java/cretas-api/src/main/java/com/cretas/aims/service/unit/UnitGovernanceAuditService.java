package com.cretas.aims.service.unit;

import com.cretas.aims.dto.unit.UnitGovernanceConflictDTO;

import java.util.List;

public interface UnitGovernanceAuditService {

    /** Scans persisted unit contracts without changing master data or Workflow definitions. */
    List<UnitGovernanceConflictDTO> scan(String factoryId);
}
