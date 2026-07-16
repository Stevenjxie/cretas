package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.BomCopyToDraftRequest;
import com.cretas.aims.entity.bom.BomRecipe;

import java.util.List;

public interface BomCopyService {

    List<BomCopyCandidateDTO> listCandidates(String factoryId, String targetProductTypeId);

    BomRecipe copySelectedRulesToDraft(String factoryId, BomCopyToDraftRequest request);
}
