package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SupplierDeliveryNoteController API contract")
class SupplierDeliveryNoteControllerTest {

    @Test
    @DisplayName("read endpoints require restaurant module and role-appropriate read permission")
    void readEndpointsHaveExplicitRestaurantAndReadPermission() throws Exception {
        assertReadContract(method("getLimits", String.class, String.class), "/limits", false);
        assertReadContract(method("list", String.class, String.class, int.class, int.class), "", true);
        assertReadContract(method("detail", String.class, String.class), "/{id}", true);
    }

    @Test
    @DisplayName("write endpoints keep existing methods and add RN-friendly POST compatibility")
    void writeEndpointsExposeCompatibleHttpMethods() throws Exception {
        assertRequestMethods(method("confirm", String.class, String.class, Long.class),
                RequestMethod.PUT, RequestMethod.POST);
        assertRequestMethods(method("reject", String.class, String.class, Long.class,
                        com.cretas.aims.dto.restaurant.SupplierDeliveryNoteDto.RejectRequest.class),
                RequestMethod.PUT, RequestMethod.POST);
        assertRequestMethods(method("updateLines", String.class, String.class, java.util.List.class),
                RequestMethod.PUT, RequestMethod.POST);

        Method delete = method("delete", String.class, String.class);
        DeleteMapping deleteMapping = delete.getAnnotation(DeleteMapping.class);
        assertNotNull(deleteMapping);
        assertArrayEquals(new String[]{"/{id}"}, deleteMapping.value());

        Method deleteByPost = method("deleteByPost", String.class, String.class);
        PostMapping postMapping = deleteByPost.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[]{"/{id}/delete"}, postMapping.value());
    }

    private void assertReadContract(Method method, String path, boolean financeCanRead) {
        RequireModule module = method.getAnnotation(RequireModule.class);
        assertNotNull(module);
        assertEquals("restaurant", module.value());

        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertNotNull(permission);
        Set<String> permissions = Set.copyOf(Arrays.asList(permission.value()));
        assertTrue(permissions.contains("warehouse:read"));
        assertTrue(permissions.contains("warehouse:read_write"));
        if (financeCanRead) {
            assertTrue(permissions.contains("finance:read"));
            assertTrue(permissions.contains("finance:read_write"));
        }

        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(path.isEmpty() ? new String[]{} : new String[]{path}, mapping.value());
    }

    private void assertRequestMethods(Method method, RequestMethod... expected) {
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals(Set.copyOf(Arrays.asList(expected)), Set.copyOf(Arrays.asList(mapping.method())));
    }

    private Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return SupplierDeliveryNoteController.class.getDeclaredMethod(name, parameterTypes);
    }
}
