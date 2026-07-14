package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomSeasoningWorkspaceResponse;
import com.cretas.aims.dto.bom.SeasoningBindingCreateRequest;
import com.cretas.aims.dto.bom.SeasoningBindingMutationResponse;
import com.cretas.aims.dto.bom.SeasoningBindingUpdateRequest;

public interface BomSeasoningWorkspaceService {
    BomSeasoningWorkspaceResponse getWorkspace(String factoryId, String recipeId);
    SeasoningBindingMutationResponse createBinding(String factoryId, String recipeId,
                                                    String workProcessId, SeasoningBindingCreateRequest request);
    SeasoningBindingMutationResponse updateBinding(String factoryId, String recipeId,
                                                    Long bindingId, SeasoningBindingUpdateRequest request);
    SeasoningBindingMutationResponse deleteBinding(String factoryId, String recipeId,
                                                    Long bindingId, Long expectedRevision);
}
