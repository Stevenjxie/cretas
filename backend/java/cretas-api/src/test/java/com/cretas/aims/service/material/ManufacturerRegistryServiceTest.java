package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateManufacturerRequest;
import com.cretas.aims.dto.material.ManufacturerRegistryDTO;
import com.cretas.aims.dto.material.UpdateManufacturerRequest;
import com.cretas.aims.entity.material.ManufacturerRegistry;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.material.ManufacturerRegistryRepository;
import com.cretas.aims.service.material.impl.ManufacturerRegistryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManufacturerRegistryService")
class ManufacturerRegistryServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String OTHER_FACTORY_ID = "F999";

    @Mock
    private ManufacturerRegistryRepository repository;

    private ManufacturerRegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ManufacturerRegistryServiceImpl(repository);
    }

    @Test
    @DisplayName("create duplicate code in same factory throws 409 with existing name and id")
    void create_duplicateCodeSameFactory_throws409() {
        ManufacturerRegistry existing = manufacturer("MR-1", FACTORY_ID, "321", "上海某某食品");
        when(repository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, "321"))
                .thenReturn(Optional.of(existing));

        CreateManufacturerRequest request = createRequest("321", "新供应商", "上海");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, request));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("321"));
        assertTrue(ex.getMessage().contains("上海某某食品"));
        assertTrue(ex.getMessage().contains("existingId=MR-1"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create same code in different factory succeeds")
    void create_sameCodeDifferentFactory_ok() {
        when(repository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, "321"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ManufacturerRegistry.class))).thenAnswer(inv -> {
            ManufacturerRegistry saved = inv.getArgument(0);
            saved.setId("MR-NEW");
            return saved;
        });

        ManufacturerRegistryDTO dto = service.create(FACTORY_ID, createRequest("321", "上海某某食品", "上海"));

        assertEquals("MR-NEW", dto.getId());
        assertEquals(FACTORY_ID, dto.getFactoryId());
        assertEquals("321", dto.getCode());
        assertEquals("上海某某食品", dto.getName());
        assertEquals("上海", dto.getOriginPlace());
    }

    @Test
    @DisplayName("list filters by factory id and active flag")
    void list_filtersByFactory() {
        ManufacturerRegistry f006 = manufacturer("MR-1", FACTORY_ID, "321", "上海某某食品");
        when(repository.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID))
                .thenReturn(List.of(f006));

        List<ManufacturerRegistryDTO> result = service.list(FACTORY_ID, false);

        assertEquals(1, result.size());
        assertEquals("MR-1", result.get(0).getId());
        assertEquals(FACTORY_ID, result.get(0).getFactoryId());
        verify(repository).findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID);
        verify(repository, never()).findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCodeAsc(OTHER_FACTORY_ID);
    }

    @Test
    @DisplayName("active-only list uses active repository query")
    void list_activeOnly_usesActiveQuery() {
        ManufacturerRegistry f006 = manufacturer("MR-1", FACTORY_ID, "321", "上海某某食品");
        when(repository.findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID))
                .thenReturn(List.of(f006));

        List<ManufacturerRegistryDTO> result = service.list(FACTORY_ID, true);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getIsActive());
        verify(repository).findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID);
    }

    @Test
    @DisplayName("update null fields preserves existing values")
    void update_nullFields_preservesExistingValues() {
        ManufacturerRegistry existing = manufacturer("MR-1", FACTORY_ID, "321", "上海某某食品");
        existing.setOriginPlace("上海");
        existing.setRemark("old");
        when(repository.findById("MR-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(ManufacturerRegistry.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateManufacturerRequest request = new UpdateManufacturerRequest();
        request.setName("上海某某食品有限公司");

        ManufacturerRegistryDTO dto = service.update(FACTORY_ID, "MR-1", request);

        assertEquals("321", dto.getCode());
        assertEquals("上海某某食品有限公司", dto.getName());
        assertEquals("上海", dto.getOriginPlace());
        assertEquals("old", dto.getRemark());
    }

    @Test
    @DisplayName("delete sets deletedAt and saves instead of hard delete")
    void softDelete_setsDeletedAt() {
        ManufacturerRegistry existing = manufacturer("MR-1", FACTORY_ID, "321", "上海某某食品");
        when(repository.findById("MR-1")).thenReturn(Optional.of(existing));

        service.delete(FACTORY_ID, "MR-1");

        ArgumentCaptor<ManufacturerRegistry> captor = ArgumentCaptor.forClass(ManufacturerRegistry.class);
        verify(repository).save(captor.capture());
        assertNotNull(captor.getValue().getDeletedAt());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    private CreateManufacturerRequest createRequest(String code, String name, String originPlace) {
        CreateManufacturerRequest request = new CreateManufacturerRequest();
        request.setCode(code);
        request.setName(name);
        request.setOriginPlace(originPlace);
        return request;
    }

    private ManufacturerRegistry manufacturer(String id, String factoryId, String code, String name) {
        ManufacturerRegistry manufacturer = new ManufacturerRegistry();
        manufacturer.setId(id);
        manufacturer.setFactoryId(factoryId);
        manufacturer.setCode(code);
        manufacturer.setName(name);
        manufacturer.setIsActive(true);
        manufacturer.setCreatedAt(LocalDateTime.now());
        manufacturer.setUpdatedAt(LocalDateTime.now());
        return manufacturer;
    }
}
