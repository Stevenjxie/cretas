package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnStatus;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.bom.EngineeringChangeNoticeRepository;
import com.cretas.aims.service.bom.BomVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ECNServiceImpl.listEcns() unit test — Sprint 6 W4-C.
 *
 * <p>Verifies paginated list method handles:
 * <ul>
 *   <li>page=1 default size=20</li>
 *   <li>status / reason / bomRecipeId filters pass through to repository</li>
 *   <li>PageResponse correctly wraps Spring Data Page</li>
 *   <li>Sort direction defaults DESC, sortBy defaults createdAt</li>
 * </ul>
 *
 * @author Cretas Team / Sprint 6 W4-C
 * @since 2026-05-20
 */
@DisplayName("ECNServiceImpl.listEcns() — Sprint 6 W4-C paginated list")
@ExtendWith(MockitoExtension.class)
class ECNServiceImplListTest {

    private static final String FACTORY = "F001";

    @Mock
    private EngineeringChangeNoticeRepository ecnRepo;

    @Mock
    private BomVersionRepository versionRepo;

    @Mock
    private BomVersionService versionService;

    @InjectMocks
    private ECNServiceImpl service;

    @Test
    @DisplayName("listEcns: 默认分页参数 page=1 size=20 沿用 + 全部 filter null pass through")
    void listEcns_defaultPagination_passesFiltersAsNull() {
        PageRequest pageRequest = PageRequest.of(1, 20);

        EngineeringChangeNotice ecn1 = EngineeringChangeNotice.builder()
                .id("ecn-1")
                .factoryId(FACTORY)
                .ecnNumber("ECN-2026-0001")
                .bomRecipeId("recipe-1")
                .status(EcnStatus.DRAFT)
                .reason(EcnReason.CUSTOMER_REQUEST)
                .toVersion(2)
                .build();
        Page<EngineeringChangeNotice> mockPage = new PageImpl<>(List.of(ecn1));
        when(ecnRepo.findByFactoryWithFilters(eq(FACTORY), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(mockPage);

        PageResponse<EngineeringChangeNotice> result =
                service.listEcns(FACTORY, pageRequest, null, null, null);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(Integer.valueOf(1), result.getPage());
        assertEquals(Integer.valueOf(20), result.getSize());
    }

    @Test
    @DisplayName("listEcns: status / reason filter 传透到 repository")
    void listEcns_withStatusAndReasonFilter_passesThrough() {
        PageRequest pageRequest = PageRequest.of(1, 10);

        Page<EngineeringChangeNotice> mockPage = new PageImpl<>(List.of());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(ecnRepo.findByFactoryWithFilters(
                eq(FACTORY), eq(EcnStatus.SUBMITTED), eq(EcnReason.COST_OPTIMIZATION),
                eq(null), pageableCaptor.capture()))
                .thenReturn(mockPage);

        PageResponse<EngineeringChangeNotice> result =
                service.listEcns(FACTORY, pageRequest, EcnStatus.SUBMITTED,
                        EcnReason.COST_OPTIMIZATION, null);

        assertNotNull(result);
        assertTrue(result.getContent().isEmpty());

        Pageable usedPageable = pageableCaptor.getValue();
        assertEquals(10, usedPageable.getPageSize());
        assertEquals(0, usedPageable.getPageNumber()); // 1-based → 0-based
        Sort sort = usedPageable.getSort();
        assertNotNull(sort.getOrderFor("createdAt"));
        assertTrue(sort.getOrderFor("createdAt").isDescending());
    }

    @Test
    @DisplayName("listEcns: ASC 排序方向 + 自定义 sortBy 字段")
    void listEcns_withAscSort_buildsPageableCorrectly() {
        PageRequest pageRequest = PageRequest.of(2, 5, "ecnNumber", "ASC");

        Page<EngineeringChangeNotice> mockPage = new PageImpl<>(List.of());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(ecnRepo.findByFactoryWithFilters(eq(FACTORY), any(), any(), any(), pageableCaptor.capture()))
                .thenReturn(mockPage);

        service.listEcns(FACTORY, pageRequest, null, null, "recipe-X");

        Pageable usedPageable = pageableCaptor.getValue();
        assertEquals(5, usedPageable.getPageSize());
        assertEquals(1, usedPageable.getPageNumber()); // page=2 → 1-based offset
        Sort.Order ecnNumberOrder = usedPageable.getSort().getOrderFor("ecnNumber");
        assertNotNull(ecnNumberOrder);
        assertTrue(ecnNumberOrder.isAscending());
    }

    @Test
    @DisplayName("listEcns: bomRecipeId 单独过滤 — 查某 BOM 的全部 ECN")
    void listEcns_withBomRecipeIdFilter_passesThrough() {
        PageRequest pageRequest = PageRequest.of(1, 20);
        String recipeId = "recipe-target";

        EngineeringChangeNotice ecn = EngineeringChangeNotice.builder()
                .id("ecn-target")
                .bomRecipeId(recipeId)
                .build();
        Page<EngineeringChangeNotice> mockPage = new PageImpl<>(List.of(ecn));
        when(ecnRepo.findByFactoryWithFilters(
                eq(FACTORY), eq(null), eq(null), eq(recipeId), any(Pageable.class)))
                .thenReturn(mockPage);

        PageResponse<EngineeringChangeNotice> result =
                service.listEcns(FACTORY, pageRequest, null, null, recipeId);

        assertEquals(1, result.getContent().size());
        assertEquals(recipeId, result.getContent().get(0).getBomRecipeId());
    }
}
