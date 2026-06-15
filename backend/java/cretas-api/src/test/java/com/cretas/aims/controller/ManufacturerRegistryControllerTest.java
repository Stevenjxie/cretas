package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.material.CreateManufacturerRequest;
import com.cretas.aims.dto.material.ManufacturerRegistryDTO;
import com.cretas.aims.dto.material.UpdateManufacturerRequest;
import com.cretas.aims.service.material.ManufacturerRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManufacturerRegistryController")
class ManufacturerRegistryControllerTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private ManufacturerRegistryService service;

    @InjectMocks
    private ManufacturerRegistryController controller;

    @Test
    @DisplayName("list passes active query flag and returns manufacturer data")
    void list_passesActiveFlag() {
        ManufacturerRegistryDTO dto = dto("MR-1", "321", "上海某某食品");
        when(service.list(FACTORY_ID, true)).thenReturn(List.of(dto));

        ApiResponse<List<ManufacturerRegistryDTO>> response = controller.list(FACTORY_ID, true);

        assertTrue(response.getSuccess());
        assertEquals(1, response.getData().size());
        assertEquals("321", response.getData().get(0).getCode());
        verify(service).list(FACTORY_ID, true);
    }

    @Test
    @DisplayName("create delegates to service and returns created row")
    void create_delegatesToService() {
        CreateManufacturerRequest request = new CreateManufacturerRequest();
        request.setCode("321");
        request.setName("上海某某食品");
        ManufacturerRegistryDTO created = dto("MR-1", "321", "上海某某食品");
        when(service.create(FACTORY_ID, request)).thenReturn(created);

        ApiResponse<ManufacturerRegistryDTO> response = controller.create(FACTORY_ID, request);

        assertTrue(response.getSuccess());
        assertEquals("MR-1", response.getData().getId());
        verify(service).create(FACTORY_ID, request);
    }

    @Test
    @DisplayName("update delegates to service")
    void update_delegatesToService() {
        UpdateManufacturerRequest request = new UpdateManufacturerRequest();
        request.setName("上海某某食品有限公司");
        ManufacturerRegistryDTO updated = dto("MR-1", "321", "上海某某食品有限公司");
        when(service.update(FACTORY_ID, "MR-1", request)).thenReturn(updated);

        ApiResponse<ManufacturerRegistryDTO> response = controller.update(FACTORY_ID, "MR-1", request);

        assertTrue(response.getSuccess());
        assertEquals("上海某某食品有限公司", response.getData().getName());
        verify(service).update(FACTORY_ID, "MR-1", request);
    }

    @Test
    @DisplayName("delete delegates to service")
    void delete_delegatesToService() {
        ApiResponse<Void> response = controller.delete(FACTORY_ID, "MR-1");

        assertTrue(response.getSuccess());
        assertNull(response.getData());
        verify(service).delete(FACTORY_ID, "MR-1");
    }

    @Test
    @DisplayName("permissions use warehouse namespace only")
    void permissions_useWarehouseNamespace() throws Exception {
        assertPermission("list", new Class<?>[]{String.class, Boolean.class}, "warehouse:read", "warehouse:read_write");
        assertPermission("create", new Class<?>[]{String.class, CreateManufacturerRequest.class}, "warehouse:read_write");
        assertPermission("update", new Class<?>[]{String.class, String.class, UpdateManufacturerRequest.class}, "warehouse:read_write");
        assertPermission("delete", new Class<?>[]{String.class, String.class}, "warehouse:read_write");
    }

    private void assertPermission(String methodName, Class<?>[] parameterTypes, String... expected) throws Exception {
        Method method = ManufacturerRegistryController.class.getMethod(methodName, parameterTypes);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertNotNull(permission);
        assertEquals(List.of(expected), Arrays.asList(permission.value()));
    }

    private ManufacturerRegistryDTO dto(String id, String code, String name) {
        return ManufacturerRegistryDTO.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .code(code)
                .name(name)
                .isActive(true)
                .build();
    }
}
