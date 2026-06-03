package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoldFinanceClientAchievementTest {

    @Mock
    private PythonSmartBIConfig config;

    private GoldFinanceClient newClient() {
        lenient().when(config.getUrl()).thenReturn("http://localhost:8083");
        lenient().when(config.getConnectTimeout()).thenReturn(3000);
        lenient().when(config.getTimeout()).thenReturn(10000);
        return new GoldFinanceClient(config);
    }

    @Test
    void fetchAchievement_nullFactoryId_throwsIllegalArgument() {
        GoldFinanceClient client = newClient();
        assertThrows(IllegalArgumentException.class, () ->
            client.fetchAchievement(null, LocalDate.now().minusDays(7), LocalDate.now(), "revenue", "day")
        );
    }

    @Test
    void fetchAchievement_startAfterEnd_throwsIllegalArgument() {
        GoldFinanceClient client = newClient();
        LocalDate start = LocalDate.now();
        LocalDate end = LocalDate.now().minusDays(1);
        assertThrows(IllegalArgumentException.class, () ->
            client.fetchAchievement("RES_TEST", start, end, "revenue", "day")
        );
    }

    @Test
    void fetchAlerts_nullFactoryId_throwsIllegalArgument() {
        GoldFinanceClient client = newClient();
        assertThrows(IllegalArgumentException.class, () ->
            client.fetchAlerts(null, 7, "revenue")
        );
    }

    @Test
    void fetchAlerts_lookbackDaysOutOfRange_throwsIllegalArgument() {
        GoldFinanceClient client = newClient();
        assertThrows(IllegalArgumentException.class, () ->
            client.fetchAlerts("RES_TEST", 0, "revenue")
        );
        assertThrows(IllegalArgumentException.class, () ->
            client.fetchAlerts("RES_TEST", 31, "revenue")
        );
    }
}
