package com.cretas.aims.service;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface WorkProcessService {

    WorkProcessDTO create(String factoryId, WorkProcessDTO dto);

    PageResponse<WorkProcessDTO> list(String factoryId, Pageable pageable);

    List<WorkProcessDTO> listActive(String factoryId);

    WorkProcessDTO getById(String factoryId, String id);

    WorkProcessDTO update(String factoryId, String id, WorkProcessDTO dto);

    WorkProcessDTO updateOutputMaterialKind(
            String factoryId,
            String id,
            WorkProcessOutputMaterialKind outputMaterialKind);

    void delete(String factoryId, String id);

    WorkProcessDTO toggleStatus(String factoryId, String id);

    void updateSortOrder(String factoryId, List<WorkProcessDTO.SortOrderUpdate> updates);

    /**
     * C5: Return all duplicate clusters — groups of existing work-processes
     * that share the same (processName, processCategory, unit) within the factory.
     * Only groups with ≥ 2 members are included.
     */
    List<WorkProcessDTO.DuplicateGroup> detectDuplicates(String factoryId);
}
