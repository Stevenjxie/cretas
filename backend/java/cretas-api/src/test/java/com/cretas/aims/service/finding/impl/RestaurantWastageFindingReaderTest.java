package com.cretas.aims.service.finding.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.exception.PythonServiceUnavailableException;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RestaurantWastageFindingReader}. */
@ExtendWith(MockitoExtension.class)
class RestaurantWastageFindingReaderTest {

    private static final String FACTORY_ID = "MOCK_REST";

    @InjectMocks
    private RestaurantWastageFindingReader reader;

    @Mock
    private PythonSmartBIClient pythonSmartBIClient;

    /** HashMap 而非 Map.of —— skip_reason 为 null 是真实响应形状, Map.of 不接受 null。 */
    private static Map<String, Object> body(Object applicable, Object skipReason,
                                            List<Object> findings) {
        Map<String, Object> m = new HashMap<>();
        m.put("rule", "type_concentration");
        m.put("applicable", applicable);
        m.put("skip_reason", skipReason);
        m.put("findings", findings);
        return m;
    }

    @Test
    @DisplayName("UT-RWR-01: dict → Finding 形状转换，facts 原样透传")
    void mapsDictToFinding() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of(Map.of(
                        "code", "WASTAGE_TYPE_CONCENTRATION",
                        "subject_id", "变质",
                        "subject_name", "变质",
                        "severity", "INFO",
                        "actionability", 70,
                        "facts", Map.of("cost", 291112.44, "share", 37.2)))));

        List<Finding> findings = reader.read(FACTORY_ID, "type_concentration");

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("WASTAGE_TYPE_CONCENTRATION", f.code());
        assertEquals("restaurant", f.domain());
        assertEquals(Finding.Severity.INFO, f.severity());
        assertEquals(70, f.actionability());
        assertEquals("变质", f.subjectName());
        assertEquals(291112.44, f.facts().get("cost"));
        assertEquals(37.2, f.facts().get("share"));
    }

    @Test
    @DisplayName("UT-RWR-02: 🔴 applicable=false → 抛 FindingNotApplicableException 并带上理由")
    void notApplicableBecomesSkip() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(false, "两期食材名单不可比：近7天 25 种 / 基线 13 种", List.of()));

        FindingNotApplicableException e = assertThrows(FindingNotApplicableException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertTrue(e.reason().contains("名单不可比"), e.reason());
    }

    @Test
    @DisplayName("UT-RWR-03: 🔴 Python 不可达必须上抛，绝不返回空列表")
    void unavailableMustNotBecomeEmptyList() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenThrow(new PythonServiceUnavailableException("OPEN", 30000L));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertFalse(e instanceof FindingNotApplicableException,
                "服务不可达是故障, 不是「数据不足」—— 混淆会让用户以为只是没数据");
    }

    @Test
    @DisplayName("UT-RWR-04: 🔴 缺 applicable 字段视为故障，不得当成 applicable")
    void missingApplicableIsFailureNotSuccess() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(Map.of("rule", "share_spike", "findings", List.of()));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.read(FACTORY_ID, "share_spike"));
        assertFalse(e instanceof FindingNotApplicableException);
    }

    @Test
    @DisplayName("UT-RWR-05: applicable=true 且 findings 为空 → 空列表（真的没有）")
    void applicableWithNoFindingsReturnsEmpty() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of()));

        assertTrue(reader.read(FACTORY_ID, "type_concentration").isEmpty());
    }

    @Test
    @DisplayName("UT-RWR-06: 未知 severity 降级为 INFO 而不是抛异常")
    void unknownSeverityFallsBackToInfo() {
        when(pythonSmartBIClient.getRestaurantWastageFindings(anyString(), anyString()))
                .thenReturn(body(true, null, List.of(Map.of(
                        "code", "WASTAGE_TYPE_CONCENTRATION",
                        "subject_id", "变质", "subject_name", "变质",
                        "severity", "SOMETHING_NEW", "actionability", 70,
                        "facts", Map.of()))));

        assertEquals(Finding.Severity.INFO,
                reader.read(FACTORY_ID, "type_concentration").get(0).severity());
    }

    @Test
    @DisplayName("UT-RWR-07: 两个 provider 的 domain / ruleName")
    void providerMetadata() {
        RestaurantWastageShareSpikeProvider spike =
                new RestaurantWastageShareSpikeProvider(reader);
        RestaurantWastageConcentrationProvider conc =
                new RestaurantWastageConcentrationProvider(reader);

        assertEquals("restaurant", spike.domain());
        assertEquals("restaurant", conc.domain());
        assertEquals("食材损耗离群", spike.ruleName());
        assertEquals("损耗类型集中度", conc.ruleName());
    }
}
