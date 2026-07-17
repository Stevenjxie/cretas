package com.cretas.aims.service.impl;

import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BomServiceImpl audit actor")
@ExtendWith(MockitoExtension.class)
class BomServiceImplAuditActorTest {

    @Mock private BomItemRepository bomItemRepository;
    @Mock private LaborCostConfigRepository laborCostConfigRepository;
    @Mock private OverheadCostConfigRepository overheadCostConfigRepository;
    @Mock private BomChangeLogRepository bomChangeLogRepository;

    private BomServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository,
                overheadCostConfigRepository);
        ReflectionTestUtils.setField(service, "bomChangeLogRepository", bomChangeLogRepository);
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(invocation -> {
            BomItem item = invocation.getArgument(0);
            item.setId(276L);
            return item;
        });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("HTTP request audit stores JWT actor attributes")
    void saveBomItemStoresAuthenticatedActor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "1309");
        request.setAttribute("username", "f006_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.saveBomItem(rawMaterialLink());

        ArgumentCaptor<BomChangeLog> captor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(captor.capture());
        assertEquals(1309L, captor.getValue().getChangedBy());
        assertEquals("f006_admin", captor.getValue().getChangedByName());
    }

    @Test
    @DisplayName("system context remains unattributed instead of inventing a user")
    void saveBomItemWithoutRequestKeepsActorNull() {
        service.saveBomItem(rawMaterialLink());

        ArgumentCaptor<BomChangeLog> captor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(captor.capture());
        assertNull(captor.getValue().getChangedBy());
        assertNull(captor.getValue().getChangedByName());
    }

    private BomItem rawMaterialLink() {
        return BomItem.builder()
                .factoryId("F006")
                .productTypeId("P_TEST")
                .materialTypeId("RMT_TEST")
                .materialName("测试原料")
                .materialCategory("RAW")
                .unit("kg")
                .build();
    }
}
