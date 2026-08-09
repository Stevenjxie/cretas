package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MobileVersionCheckResponseTest {

    @Test
    void exposesTheSameMinimumVersionUsedForTheRequiredDecision() {
        MobileBusinessServiceImpl service = new MobileBusinessServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "latestVersion", "1.1.0");
        ReflectionTestUtils.setField(service, "minVersion", "1.0.4");
        ReflectionTestUtils.setField(service, "androidDownloadUrl", "");
        ReflectionTestUtils.setField(
                service,
                "androidDownloadBase",
                "https://dl.cretaceousfuture.com/cretas-v"
        );
        ReflectionTestUtils.setField(service, "iosDownloadUrl", "");
        ReflectionTestUtils.setField(service, "releaseNotes", "权限边界更新");
        ReflectionTestUtils.setField(service, "fileSize", 123L);

        MobileDTO.VersionCheckResponse response = service.checkVersion("1.0.3", "android");

        assertThat(response.getMinimumVersion()).isEqualTo("1.0.4");
        assertThat(response.getUpdateRequired()).isTrue();
        assertThat(response.getLatestVersion()).isEqualTo("1.1.0");
        assertThat(response.getDownloadUrl())
                .isEqualTo("https://dl.cretaceousfuture.com/cretas-v1.1.0.apk");
    }
}
