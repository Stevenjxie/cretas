package com.cretas.aims.controller;

import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestockBoardController")
class RestockBoardControllerTest {

    @Mock RestockBoardService service;
    @InjectMocks RestockBoardController controller;

    @Test
    @DisplayName("GET 返回 ApiResponse.success 包看板")
    void getBoard() {
        LocalDate d = LocalDate.of(2026, 6, 3);
        RestockBoardDTO dto = RestockBoardDTO.builder()
                .deliveryDate(d).rows(List.of())
                .summary(RestockBoardDTO.Summary.builder().totalProducts(0).build())
                .build();
        when(service.getRestockBoard("F006", d)).thenReturn(dto);

        var resp = controller.getRestockBoard("F006", d);
        assertTrue(resp.getSuccess());
        assertEquals(d, resp.getData().getDeliveryDate());
        verify(service).getRestockBoard("F006", d);
    }

    @Test
    @DisplayName("GET horizon returns ApiResponse.success")
    void getHorizon() {
        LocalDate start = LocalDate.of(2026, 5, 31);
        LocalDate end = LocalDate.of(2026, 6, 4);
        RestockHorizonDTO dto = RestockHorizonDTO.builder()
                .startDate(start)
                .endDate(end)
                .dates(List.of(start, end))
                .rows(List.of())
                .summary(RestockHorizonDTO.Summary.builder().totalProducts(0).days(5).build())
                .build();
        when(service.getRestockHorizon("F006", start, end)).thenReturn(dto);

        var resp = controller.getRestockHorizon("F006", start, end);

        assertTrue(resp.getSuccess());
        assertEquals(start, resp.getData().getStartDate());
        assertEquals(end, resp.getData().getEndDate());
        verify(service).getRestockHorizon("F006", start, end);
    }
}
