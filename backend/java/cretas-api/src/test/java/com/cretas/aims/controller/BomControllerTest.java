package com.cretas.aims.controller;

import com.cretas.aims.service.BomService;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BomControllerTest {

    @Mock BomService bomService;
    @Mock RecursiveBomExpansionService recursiveBomExpansionService;
    @Mock BomYieldEstimateService bomYieldEstimateService;
    @InjectMocks BomController controller;
}
